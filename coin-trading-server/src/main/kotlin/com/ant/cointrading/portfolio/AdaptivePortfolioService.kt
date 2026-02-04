package com.ant.cointrading.portfolio

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.repository.OhlcvHistoryRepository
import com.ant.cointrading.volumesurge.VolumeSurgeAnalyzer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.max

/**
 * 적응형 포트폴리오 관리 서비스
 *
 * - 시장 레질에 따른 동적 자금 배분
 * - Black-Litterman 뷰 생성 (컨플루언스 기반)
 * - Risk Parity + Kelly Criterion 기반 포지션 사이징
 * - 자동 리밸런싱
 */
@Service
class AdaptivePortfolioService(
    private val portfolioOptimizer: PortfolioOptimizer,
    private val hrpAllocator: HRPAllocator,
    private val blackLittermanModel: BlackLittermanModel,
    private val kellySizer: AdaptiveKellySizer,
    private val ohlcvHistoryRepository: OhlcvHistoryRepository,
    private val tradingProperties: TradingProperties,
    private val volumeSurgeAnalyzer: VolumeSurgeAnalyzer
) {
    private val log = LoggerFactory.getLogger(AdaptivePortfolioService::class.java)

    companion object {
        private const val REBALANCE_THRESHOLD = 0.05  // 5% 변동 시 리밸런싱
        private const val MIN_ALLOCATION = 0.05      // 최소 5% 할당
        private const val MAX_ALLOCATION = 0.40      // 최대 40% 할당
    }

    // 현재 포트폴리오 상태
    private var currentAllocations: Map<String, Double> = emptyMap()
    private var lastRebalanceTime: Instant? = null
    private var currentRegime: MarketRegime = MarketRegime.SIDEWAYS
    private var totalCapital: Double = 1_000_000.0  // 초기 자본 100만원

    /**
     * 최적 포트폴리오 계산
     */
    fun calculateOptimalPortfolio(
        assets: List<String> = tradingProperties.markets,
        method: OptimizationMethod = OptimizationMethod.RISK_PARITY
    ): PortfolioAllocation {
        if (assets.isEmpty()) {
            return PortfolioAllocation(
                allocations = emptyMap(),
                expectedReturn = 0.0,
                portfolioRisk = 0.0,
                sharpeRatio = 0.0,
                method = method.name
            )
        }

        val lookbackDays = when (currentRegime) {
            MarketRegime.HIGH_VOLATILITY -> 30  // 고변동: 짧은 기간
            MarketRegime.BULL_TREND, MarketRegime.BEAR_TREND -> 60
            else -> 90
        }

        val result = when (method) {
            OptimizationMethod.SHARPE_RATIO -> portfolioOptimizer.maximizeSharpeRatio(assets, lookbackDays)
            OptimizationMethod.RISK_PARITY -> portfolioOptimizer.calculateRiskParity(assets, lookbackDays)
            OptimizationMethod.HRP -> {
                val hrpResult = hrpAllocator.calculateHRPWeights(assets, lookbackDays)
                // HRPResult를 OptimizationResult로 변환
                val returnsMatrix = calculateReturnsMatrix(assets, lookbackDays)
                val expRet = if (returnsMatrix != null) {
                    val expectedReturns = DoubleArray(assets.size) { i ->
                        returnsMatrix.map { row -> row[i] }.average()
                    }
                    hrpResult.weights.zip(expectedReturns).sumOf { (w, r) -> w * r }
                } else 0.08
                val covMatrix = if (returnsMatrix != null) calculateCovarianceMatrix(returnsMatrix)
                else Array(assets.size) { i -> DoubleArray(assets.size) { j -> if (i == j) 1.0 else 0.0 } }
                val portRisk = kotlin.math.sqrt(
                    hrpResult.weights.indices.sumOf { i ->
                        hrpResult.weights.indices.sumOf { j ->
                            hrpResult.weights[i] * hrpResult.weights[j] * covMatrix[i][j]
                        }
                    }
                )
                OptimizationResult(
                    weights = hrpResult.weights,
                    expectedReturn = expRet,
                    portfolioRisk = portRisk,
                    sharpeRatio = if (portRisk > 0) (expRet - 0.02) / portRisk else 0.0
                )
            }
            OptimizationMethod.BLACK_LITTERMAN -> calculateBlackLittermanPortfolio(assets, lookbackDays)
        }

        // 가중치 제약 조건 적용
        val constrainedWeights = result.weights.map { w ->
            w.coerceIn(MIN_ALLOCATION, MAX_ALLOCATION)
        }.toDoubleArray()

        // 재정규화
        val sumW = constrainedWeights.sum()
        val normalizedWeights = if (sumW > 0) {
            constrainedWeights.map { it / sumW }.toDoubleArray()
        } else {
            DoubleArray(assets.size) { 1.0 / assets.size }
        }

        // 할당 맵 생성
        val allocations = assets.zip(normalizedWeights.toList()).toMap()

        val expectedReturn = normalizedWeights.sumOf { w -> w * result.expectedReturn }
        val portfolioRisk = result.portfolioRisk
        val sharpeRatio = result.sharpeRatio

        log.info("최적 포트폴리오 계산: method=$method, regime=$currentRegime, " +
                "return=$expectedReturn, risk=$portfolioRisk")

        return PortfolioAllocation(
            allocations = allocations,
            expectedReturn = expectedReturn,
            portfolioRisk = portfolioRisk,
            sharpeRatio = sharpeRatio,
            method = method.name
        )
    }

    /**
     * Black-Litterman 포트폴리오 계산
     */
    private fun calculateBlackLittermanPortfolio(assets: List<String>, lookbackDays: Int): OptimizationResult {
        // 컨플루언스 기반 뷰 생성
        val views = generateConfluenceViews(assets)

        // 시가총액 기반 가중치 (균등 가중치 Fallback)
        val marketCaps = null  // 시가총액 API 연동 시 사용

        val blResult = blackLittermanModel.calculateBlackLittermanReturns(
            assets = assets,
            views = views,
            marketCaps = marketCaps,
            lookbackDays = lookbackDays,
            tau = 0.025
        )

        // BL 수익률로 공분산 계산 후 샤프 비율 최적화
        val returnsMatrix = calculateReturnsMatrix(assets, lookbackDays)
        val covarianceMatrix = if (returnsMatrix != null) {
            calculateCovarianceMatrix(returnsMatrix)
        } else {
            Array(assets.size) { i ->
                DoubleArray(assets.size) { j -> if (i == j) 1.0 else 0.0 }
            }
        }

        val weights = maximizeSharpeRatioWithReturns(blResult.expectedReturns, covarianceMatrix)

        val portfolioRisk = calculatePortfolioRisk(weights, covarianceMatrix)
        val portfolioReturn = weights.zip(blResult.expectedReturns).sumOf { (w, r) -> w * r }
        val sharpeRatio = if (portfolioRisk > 0) (portfolioReturn - 0.02) / portfolioRisk else 0.0

        return OptimizationResult(
            weights = weights,
            expectedReturn = portfolioReturn,
            portfolioRisk = portfolioRisk,
            sharpeRatio = sharpeRatio
        )
    }

    /**
     * 컨플루언스 기반 Black-Litterman 뷰 생성
     */
    private fun generateConfluenceViews(assets: List<String>): List<BlackLittermanModel.View> {
        val views = mutableListOf<BlackLittermanModel.View>()

        // 각 자산별 컨플루언스 점수 확인
        val confluenceScores = assets.associateWith { asset ->
            try {
                val analysis = volumeSurgeAnalyzer.analyze(asset)
                if (analysis != null && analysis.rejectReason == null) {
                    analysis.confluenceScore
                } else {
                    50  // 기본 점수
                }
            } catch (e: Exception) {
                log.warn("[$asset] 컨플루언스 계산 실패: ${e.message}")
                50
            }
        }

        // 상위 50% 자산은 긍정적 뷰, 하위 50%는 부정적 뷰
        val sortedByScore = confluenceScores.entries.sortedByDescending { it.value }
        val midPoint = sortedByScore.size / 2

        val bullishAssets = sortedByScore.take(midPoint).map { it.key to it.value }
        val bearishAssets = sortedByScore.drop(midPoint).map { it.key to it.value }

        // 상대적 뷰 생성: 상위 vs 하위
        if (bullishAssets.isNotEmpty() && bearishAssets.isNotEmpty()) {
            val topAsset = bullishAssets.first()
            val bottomAsset = bearishAssets.first()

            val assetIndices = listOf(
                assets.indexOf(topAsset.first),
                assets.indexOf(bottomAsset.first)
            ).filter { it >= 0 }

            if (assetIndices.size == 2) {
                val confidence = (topAsset.second - bottomAsset.second) / 100.0
                val viewReturn = when (currentRegime) {
                    MarketRegime.BULL_TREND -> 0.15   // 15% 기대
                    MarketRegime.SIDEWAYS -> 0.08    // 8% 기대
                    MarketRegime.BEAR_TREND -> 0.03   // 3% 기대
                    MarketRegime.HIGH_VOLATILITY -> 0.05
                }

                views.add(
                    BlackLittermanModel.View(
                        assets = assetIndices,
                        viewReturn = viewReturn,
                        confidence = confidence.coerceIn(0.1, 0.9),
                        description = "컨플루언스 기반: ${topAsset.first}(+) vs ${bottomAsset.first}(-)"
                    )
                )
            }
        }

        log.info("BL 뷰 생성: ${views.size}개 (상위 ${bullishAssets.size} vs 하위 ${bearishAssets.size})")
        return views
    }

    /**
     * 포지션 사이징 (Kelly Criterion + Risk Parity)
     */
    fun calculatePositionSize(
        asset: String,
        signalStrength: Double = 0.5,
        totalCapital: Double = this.totalCapital
    ): PositionSizingResult {
        // 자산 변동성 계산
        val volatility = calculateAssetVolatility(asset, 30)

        // Kelly Criterion 기반 크기
        val kellySize = kellySizer.calculatePositionSize(
            confidence = signalStrength,
            marketRegime = currentRegime.name
        )

        // Risk Parity 기반 크기
        val riskParitySize = kellySizer.calculatePositionSizeWithRiskParity(
            assetVolatility = volatility,
            signalStrength = signalStrength,
            totalCapital = totalCapital
        )

        // 두 방식 평균
        val blendedSize = (kellySize + riskParitySize) / 2.0
        val positionAmount = totalCapital * blendedSize

        log.info("[$asset] 포지션 사이징: Kelly=${String.format("%.2f%%", kellySize * 100)}, " +
                "RiskParity=${String.format("%.2f%%", riskParitySize * 100)}, " +
                "Blended=${String.format("%.2f%%", blendedSize * 100)}, " +
                "Amount=${String.format("%.0f", positionAmount)}원")

        return PositionSizingResult(
            asset = asset,
            kellyFraction = kellySize,
            riskParityFraction = riskParitySize,
            blendedFraction = blendedSize,
            positionAmount = positionAmount,
            volatility = volatility
        )
    }

    /**
     * 리밸런싱 필요 여부 체크
     */
    fun shouldRebalance(newAllocations: Map<String, Double>): Boolean {
        if (currentAllocations.isEmpty()) return true

        // 최대 편차 계산
        val maxDeviation = newAllocations.map { (asset, newWeight) ->
            val currentWeight = currentAllocations[asset] ?: newWeight
            kotlin.math.abs(newWeight - currentWeight)
        }.maxOrNull() ?: 0.0

        val needsRebalance = maxDeviation > REBALANCE_THRESHOLD

        if (needsRebalance) {
            log.info("리밸런싱 필요: 최대 편차=${String.format("%.2f%%", maxDeviation * 100)}")
        }

        return needsRebalance
    }

    /**
     * 포트폴리오 리밸런싱 실행
     */
    fun rebalance(
        targetAllocations: Map<String, Double>,
        method: OptimizationMethod = OptimizationMethod.RISK_PARITY
    ): RebalanceResult {
        val startTime = Instant.now()
        val trades = mutableListOf<RebalanceTrade>()
        var totalRebalanceAmount = 0.0

        // 현재 포지션과 타겟 비교
        targetAllocations.forEach { (asset, targetWeight) ->
            val currentWeight = currentAllocations[asset] ?: 0.0
            val weightDiff = targetWeight - currentWeight

            if (kotlin.math.abs(weightDiff) > 0.01) {  // 1% 이상 변동 시
                val amount = totalCapital * kotlin.math.abs(weightDiff)
                val action = if (weightDiff > 0) "BUY" else "SELL"

                trades.add(
                    RebalanceTrade(
                        asset = asset,
                        action = action,
                        targetWeight = targetWeight,
                        currentWeight = currentWeight,
                        amount = amount
                    )
                )

                totalRebalanceAmount += amount
            }
        }

        // 할당 업데이트
        currentAllocations = targetAllocations
        lastRebalanceTime = startTime

        log.info("포트폴리오 리밸런싱 완료: ${trades.size}건 trades, " +
                "총금액=${String.format("%.0f", totalRebalanceAmount)}원")

        return RebalanceResult(
            trades = trades,
            totalRebalanceAmount = totalRebalanceAmount,
            newAllocations = targetAllocations,
            rebalanceTime = startTime,
            method = method.name
        )
    }

    /**
     * 자산 변동성 계산
     */
    private fun calculateAssetVolatility(asset: String, days: Int): Double {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(days.toLong()))
        val candles = ohlcvHistoryRepository
            .findByMarketAndIntervalOrderByTimestampAsc(asset, "1d")
            .filter { it.timestamp >= cutoffTime.toEpochMilli() / 1000 }

        if (candles.size < 10) return 0.3  // 기본 30% 연 변동성

        // 일일 수익률 표준편차
        val returns = candles.zipWithNext().map { (prev, curr) ->
            if (prev.close > 0) (curr.close - prev.close) / prev.close else 0.0
        }

        val stdDev = kotlin.math.sqrt(
            returns.map { r ->
                val mean = returns.average()
                (r - mean) * (r - mean)
            }.average()
        )

        // 연 환산 (일 변동성 × sqrt(365))
        return stdDev * kotlin.math.sqrt(365.0)
    }

    /**
     * 시장 레짐 업데이트
     */
    fun updateMarketRegime(regime: MarketRegime) {
        if (currentRegime != regime) {
            log.info("시장 레짐 변경: $currentRegime → $regime")
            currentRegime = regime
        }
    }

    /**
     * 총 자본 업데이트
     */
    fun updateTotalCapital(capital: Double) {
        totalCapital = max(capital, 100_000.0)  // 최소 10만원
        log.info("총 자본 업데이트: ${String.format("%.0f", totalCapital)}원")
    }

    // ========== Helper Functions ==========

    private fun maximizeSharpeRatioWithReturns(
        expectedReturns: DoubleArray,
        covarianceMatrix: Array<DoubleArray>
    ): DoubleArray {
        val invCov = invertMatrix(covarianceMatrix)
        val excessReturns = expectedReturns.map { it - 0.02 }.toDoubleArray()
        val weights = multiplyMatrixVector(invCov, excessReturns)

        val sumW = weights.map { maxOf(0.0, it) }.sum()
        return if (sumW > 0) {
            weights.map { maxOf(0.0, it) / sumW }.toDoubleArray()
        } else {
            DoubleArray(expectedReturns.size) { 1.0 / expectedReturns.size }
        }
    }

    private fun calculatePortfolioReturn(weights: DoubleArray, returns: DoubleArray): Double {
        return weights.zip(returns).sumOf { (w, r) -> w * r }
    }

    private fun calculatePortfolioRisk(weights: DoubleArray, covMatrix: Array<DoubleArray>): Double {
        val n = weights.size
        var variance = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                variance += weights[i] * weights[j] * covMatrix[i][j]
            }
        }
        return kotlin.math.sqrt(variance)
    }

    private fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val result = Array(n) { i -> matrix[i].copyOf() }
        val identity = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }

        for (col in 0 until n) {
            var maxRow = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(result[row][col]) > kotlin.math.abs(result[maxRow][col])) {
                    maxRow = row
                }
            }

            val temp = result[col]
            result[col] = result[maxRow]
            result[maxRow] = temp

            val tempId = identity[col]
            identity[col] = identity[maxRow]
            identity[maxRow] = tempId

            val pivot = result[col][col]
            if (kotlin.math.abs(pivot) < 1e-10) {
                return Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
            }

            for (j in 0 until n) {
                result[col][j] /= pivot
                identity[col][j] /= pivot
            }

            for (row in 0 until n) {
                if (row != col) {
                    val factor = result[row][col]
                    for (j in 0 until n) {
                        result[row][j] -= factor * result[col][j]
                        identity[row][j] -= factor * identity[col][j]
                    }
                }
            }
        }

        return identity
    }

    private fun multiplyMatrixVector(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val n = matrix.size
        return DoubleArray(n) { i ->
            var sum = 0.0
            for (j in vector.indices) {
                sum += matrix[i][j] * vector[j]
            }
            sum
        }
    }

    private fun getFallbackResult(n: Int): OptimizationResult {
        return OptimizationResult(
            weights = DoubleArray(n) { 1.0 / n },
            expectedReturn = 0.08,
            portfolioRisk = 0.2,
            sharpeRatio = 0.3
        )
    }

    /**
     * 현재 포트폴리오 상태 조회
     */
    fun getPortfolioStatus(): PortfolioStatus {
        return PortfolioStatus(
            currentAllocations = currentAllocations,
            lastRebalanceTime = lastRebalanceTime,
            currentRegime = currentRegime.name,
            totalCapital = totalCapital,
            kellyStatus = kellySizer.getKellyStatus()
        )
    }

    /**
     * 수익률 행렬 계산
     */
    private fun calculateReturnsMatrix(
        assets: List<String>,
        lookbackDays: Int
    ): Array<DoubleArray>? {
        val interval = "1d"
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val cutoffTimestamp = cutoffTime.toEpochMilli() / 1000

        val histories = assets.associateWith { asset ->
            ohlcvHistoryRepository.findByMarketAndIntervalOrderByTimestampAsc(asset, interval)
                .filter { it.timestamp >= cutoffTimestamp }
        }

        val minCount = histories.values.minOfOrNull { it.size } ?: return null
        if (minCount < 30) {
            log.warn("충분한 historical data 없음: $minCount < 30")
            return null
        }

        val prices = histories.mapValues { (_, history) ->
            history.take(minCount).map { it.close }
        }

        val returns = prices.mapValues { (_, assetPrices) ->
            assetPrices.zipWithNext().map { (prev, curr) ->
                if (prev > 0) (curr - prev) / prev else 0.0
            }
        }

        val numReturns = returns.values.first().size
        return Array(numReturns) { t ->
            DoubleArray(assets.size) { a ->
                returns[assets[a]]?.get(t) ?: 0.0
            }
        }
    }

    /**
     * 공분산 행렬 계산
     */
    private fun calculateCovarianceMatrix(returnsMatrix: Array<DoubleArray>): Array<DoubleArray> {
        val nAssets = returnsMatrix[0].size
        val means = DoubleArray(nAssets) { i ->
            returnsMatrix.map { row -> row[i] }.average()
        }

        return Array(nAssets) { i ->
            DoubleArray(nAssets) { j ->
                val n = returnsMatrix.size
                var cov = 0.0
                for (t in 0 until n) {
                    cov += (returnsMatrix[t][i] - means[i]) * (returnsMatrix[t][j] - means[j])
                }
                cov / (n - 1)
            }
        }
    }

    /**
     * 자동 리밸런싱 (매일 새벽 1시)
     */
    @Scheduled(cron = "0 0 1 * * *")
    fun autoRebalance() {
        log.info("[자동 리밸런싱] 시작")
        try {
            val optimalPortfolio = calculateOptimalPortfolio()
            if (shouldRebalance(optimalPortfolio.allocations)) {
                rebalance(optimalPortfolio.allocations)
                log.info("[자동 리밸런싱] 완료")
            } else {
                log.info("[자동 리밸런싱] 스킵 (변동 미미)")
            }
        } catch (e: Exception) {
            log.error("[자동 리밸런싱] 실패: ${e.message}", e)
        }
    }

    /**
     * Kelly 히스토리 로드 (앱 시작 시)
     */
    @jakarta.annotation.PostConstruct
    fun loadHistory() {
        kellySizer.loadHistoryFromDB(30)
    }
}

/**
 * 최적화 방법
 */
enum class OptimizationMethod {
    SHARPE_RATIO,      // 샤프 비율 최대화
    RISK_PARITY,       // 리스크 패리티
    HRP,              // 계층적 리스크 패리티
    BLACK_LITTERMAN   // Black-Litterman
}

/**
 * 포트폴리오 할당 결과
 */
data class PortfolioAllocation(
    val allocations: Map<String, Double>,    // 자산별 가중치
    val expectedReturn: Double,              // 기대 수익률
    val portfolioRisk: Double,               // 포트폴리오 리스크
    val sharpeRatio: Double,                 // 샤프 비율
    val method: String                       // 최적화 방법
)

/**
 * 포지션 사이징 결과
 */
data class PositionSizingResult(
    val asset: String,
    val kellyFraction: Double,
    val riskParityFraction: Double,
    val blendedFraction: Double,
    val positionAmount: Double,
    val volatility: Double
)

/**
 * 리밸런싱 결과
 */
data class RebalanceResult(
    val trades: List<RebalanceTrade>,
    val totalRebalanceAmount: Double,
    val newAllocations: Map<String, Double>,
    val rebalanceTime: Instant,
    val method: String
)

/**
 * 리밸런싱 트레이드
 */
data class RebalanceTrade(
    val asset: String,
    val action: String,           // BUY/SELL
    val targetWeight: Double,
    val currentWeight: Double,
    val amount: Double
)

/**
 * 포트폴리오 상태
 */
data class PortfolioStatus(
    val currentAllocations: Map<String, Double>,
    val lastRebalanceTime: Instant?,
    val currentRegime: String,
    val totalCapital: Double,
    val kellyStatus: com.ant.cointrading.portfolio.KellyStatus
)
