package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Tactical Asset Allocator
 *
 * 모멘텀 기반 전술적 배분, 변동성 타겟팅, 시장 레짐 조정 구현
 */
@Service
class TacticalAssetAllocator(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository,
    private val portfolioOptimizer: PortfolioOptimizer
) {
    private val log = LoggerFactory.getLogger(TacticalAssetAllocator::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
        private const val DEFAULT_LOOKBACK_PERIOD = 30  // 30일 모멘텀
        private const val DEFAULT_REBALANCE_THRESHOLD = 0.05  // 5% 변동 시 리밸런싱
        private const val TARGET_VOLATILITY = 0.15  // 연 15% 변동성 목표
        private const val MAX_LEVERAGE = 1.5
    }

    /**
     * 시장 레짐
     */
    enum class MarketRegime {
        BULL,      // 강세장
        BEAR,      // 약세장
        SIDEWAYS,  // 횡보장
        HIGH_VOL,  // 고변동
        LOW_VOL    // 저변동
    }

    /**
     * 모멘텀 기반 전술적 배분 결과
     */
    data class TacticalAllocationResult(
        val strategicWeights: Map<String, Double>,      // 장기 전략적 가중치
        val momentumScores: Map<String, Double>,         // 모멘텀 점수
        val regimeAdjustments: Map<String, Double>,      // 레짐별 조정 계수
        val tacticalWeights: Map<String, Double>,        // 최종 전술적 가중치
        val currentRegime: MarketRegime                  // 현재 시장 레짐
    )

    /**
     * 모멘텀 점수 결과
     */
    data class MomentumScore(
        val asset: String,
        val score: Double,           // 모멘텀 점수
        val normalizedScore: Double, // 정규화된 점수
        val rank: Int                // 순위
    )

    /**
     * 시간 가중 모멘텀 점수 계산
     *
     * 최신 데이터에 더 높은 가중치 부여 (Exponential decay)
     *
     * @param asset 자산 심볼
     * @param lookbackPeriod 기간 (일)
     * @param halflife 반감기 (기본 10일)
     * @return 모멘텀 점수
     */
    fun calculateMomentumScore(
        asset: String,
        lookbackPeriod: Int = DEFAULT_LOOKBACK_PERIOD,
        halflife: Int = 10
    ): MomentumScore {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackPeriod.toLong()))
        val returns = getAssetReturns(asset, cutoffTime)

        val score = if (returns != null && returns.size >= halflife) {
            var momentumSum = 0.0
            var weight = 1.0
            val decayFactor = exp(-ln(2.0) / halflife)

            // 최신 데이터부터 역순으로 계산
            for (i in (returns.size - 1) downTo maxOf(0, returns.size - lookbackPeriod)) {
                momentumSum += returns[i] * weight
                weight *= decayFactor
            }

            momentumSum
        } else {
            0.0
        }

        return MomentumScore(
            asset = asset,
            score = score,
            normalizedScore = 0.0,  // calculateMomentumScores에서 계산
            rank = 0
        )
    }

    /**
     * 여러 자산의 모멘텀 점수 계산
     */
    fun calculateMomentumScores(
        assets: List<String>,
        lookbackPeriod: Int = DEFAULT_LOOKBACK_PERIOD,
        halflife: Int = 10
    ): List<MomentumScore> {
        val scores = assets.map { asset ->
            calculateMomentumScore(asset, lookbackPeriod, halflife)
        }

        // 정규화 (Temperature scaling)
        val avgScore = scores.map { it.score }.average()
        val stdScore = kotlin.math.sqrt(scores.map { (it.score - avgScore).let { d -> d * d } }.average())
        val normalizedScores = scores.map { score ->
            score.copy(
                normalizedScore = if (stdScore > 0) {
                    exp((score.score - avgScore) / (0.1 + stdScore))
                } else {
                    1.0
                }
            )
        }

        // 정규화 후 합이 1이 되도록 재조정
        val totalNormalized = normalizedScores.sumOf { it.normalizedScore }
        val finalScores = normalizedScores.map { score ->
            score.copy(normalizedScore = if (totalNormalized > 0) score.normalizedScore / totalNormalized else 1.0 / scores.size)
        }

        // 순위 매기기
        val rankedScores = finalScores.sortedByDescending { it.score }
        return finalScores.map { score ->
            score.copy(rank = rankedScores.indexOf(score) + 1)
        }.sortedByDescending { it.score }
    }

    /**
     * 시장 레짐 감지
     *
     * @param assets 자산 목록
     * @param lookbackDays 분석 기간
     * @return 현재 시장 레짐
     */
    fun detectMarketRegime(
        assets: List<String>,
        lookbackDays: Int = 30
    ): MarketRegime {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        val returns = assets.mapNotNull { asset ->
            getAssetReturns(asset, cutoffTime)?.let { asset to it }
        }

        if (returns.isEmpty()) return MarketRegime.SIDEWAYS

        // 평균 수익률과 변동성 계산
        val allReturnsList = mutableListOf<Double>()
        for (entry in returns) {
            for (ret in entry.second) {
                allReturnsList.add(ret)
            }
        }
        val allReturns = allReturnsList.toDoubleArray()
        val avgReturn = allReturns.average()
        var variance = 0.0
        for (ret in allReturns) {
            val diff = ret - avgReturn
            variance += diff * diff
        }
        val volatility = kotlin.math.sqrt(variance / allReturns.size)

        // 일일 변동성 기준 연환산
        val annualizedVol = volatility * kotlin.math.sqrt(365.0)

        return when {
            // 고변동: 연 60% 이상
            annualizedVol > 0.6 -> MarketRegime.HIGH_VOL

            // 저변동: 연 20% 미만
            annualizedVol < 0.2 -> MarketRegime.LOW_VOL

            // 강세장: 평균 수익률이 양수이고 높은 변동성
            avgReturn > 0.005 -> MarketRegime.BULL

            // 약세장: 평균 수익률이 음수
            avgReturn < -0.005 -> MarketRegime.BEAR

            // 횡보장
            else -> MarketRegime.SIDEWAYS
        }
    }

    /**
     * 모멘텀 기반 가중치 조정
     *
     * Strategic (장기) + Tactical (단기) 혼합
     *
     * @param baseWeights 전략적 기본 가중치
     * @param assets 자산 목록
     * @param strategicTacticalMix 전략/전술 혼합 비율 (기본 0.7 = 70% 전략, 30% 전술)
     * @return 조정된 가중치
     */
    fun adjustWeightsByMomentum(
        baseWeights: Map<String, Double>,
        assets: List<String>,
        strategicTacticalMix: Double = 0.7
    ): Map<String, Double> {
        // 모멘텀 점수 계산
        val momentumScores = calculateMomentumScores(assets)

        // 점수 기반 매핑
        val scoreMap = momentumScores.associate { it.asset to it.normalizedScore }

        // 전술적 가중치 = Strategic × Momentum
        val tacticalWeights = baseWeights.mapValues { (asset, baseWeight) ->
            val momentumMultiplier = scoreMap[asset] ?: 1.0
            val momentumWeight = momentumMultiplier * assets.size  // 정규화된 점수를 가중치로 변환

            // Strategic + Tactical 혼합
            strategicTacticalMix * baseWeight + (1 - strategicTacticalMix) * momentumWeight
        }

        // 음수 처리 및 정규화
        val adjustedWeights = tacticalWeights.mapValues { maxOf(0.0, it.value) }
        val totalWeight = adjustedWeights.values.sum()

        return if (totalWeight > 0) {
            adjustedWeights.mapValues { it.value / totalWeight }
        } else {
            baseWeights
        }
    }

    /**
     * 시장 레짐별 가중치 조정 계수
     */
    fun getRegimeAdjustments(regime: MarketRegime, assets: List<String>): Map<String, Double> {
        return when (regime) {
            MarketRegime.BULL -> mapOf(
                "BTC_KRW" to 1.2,
                "ETH_KRW" to 1.1,
                "SOL_KRW" to 1.3,
                "XRP_KRW" to 1.2
            )
            MarketRegime.BEAR -> mapOf(
                "BTC_KRW" to 0.8,
                "ETH_KRW" to 0.7,
                "SOL_KRW" to 0.5,
                "XRP_KRW" to 0.6
            )
            MarketRegime.HIGH_VOL -> mapOf(
                "BTC_KRW" to 1.1,  // 변동성에 강한 자산
                "ETH_KRW" to 0.9,
                "SOL_KRW" to 0.7,
                "XRP_KRW" to 0.8
            )
            MarketRegime.LOW_VOL -> mapOf(
                "BTC_KRW" to 1.0,
                "ETH_KRW" to 1.1,  // 횡보장에서 알트코인 유리
                "SOL_KRW" to 1.2,
                "XRP_KRW" to 1.1
            )
            else -> assets.associateWith { 1.0 }
        }
    }

    /**
     * 전술적 배분 계산 (완전 통합)
     *
     * 1. 장기 전략적 배분 (HRP 또는 Sharpe Ratio)
     * 2. 모멘텀 점수 계산
     * 3. 시장 레짐 감지
     * 4. 전술적 가중치 = Strategic × Momentum × Regime
     */
    fun calculateTacticalAllocation(
        assets: List<String>,
        lookbackDays: Int = 90,
        strategicMethod: String = "SHARPE"  // SHARPE, RISK_PARITY, HRP
    ): TacticalAllocationResult {
        // 1. 장기 전략적 배분
        val strategicResult = when (strategicMethod) {
            "SHARPE" -> portfolioOptimizer.maximizeSharpeRatio(assets, lookbackDays)
            "RISK_PARITY" -> portfolioOptimizer.calculateRiskParity(assets, lookbackDays)
            else -> portfolioOptimizer.maximizeSharpeRatio(assets, lookbackDays)
        }

        val strategicWeights = assets.zip(strategicResult.weights.toList()).toMap()

        // 2. 모멘텀 점수 계산
        val momentumScores = calculateMomentumScores(assets, lookbackDays)

        // 3. 시장 레짐 감지
        val currentRegime = detectMarketRegime(assets, lookbackDays)

        // 4. 레짐별 조정 계수
        val regimeAdjustments = getRegimeAdjustments(currentRegime, assets)

        // 5. 전술적 가중치 계산
        val momentumWeightMap = momentumScores.associate { it.asset to it.normalizedScore }

        val tacticalWeights = assets.map { asset ->
            val strategicWeight = strategicWeights[asset] ?: (1.0 / assets.size)
            val momentumWeight = momentumWeightMap[asset] ?: (1.0 / assets.size)
            val regimeAdjust = regimeAdjustments[asset] ?: 1.0

            // Strategic × Momentum × Regime (70:20:10 비율)
            val finalWeight = strategicWeight * 0.7 + momentumWeight * 0.2 + regimeAdjust * 0.1
            asset to maxOf(0.0, finalWeight)
        }.toMap()

        // 정규화
        val totalTactical = tacticalWeights.values.sum()
        val normalizedTactical = if (totalTactical > 0) {
            tacticalWeights.mapValues { it.value / totalTactical }
        } else {
            strategicWeights
        }

        log.info("전술적 배분 완료: Regime=$currentRegime, " +
                "Strategic=$strategicWeights, " +
                "Tactical=$normalizedTactical")

        return TacticalAllocationResult(
            strategicWeights = strategicWeights,
            momentumScores = momentumScores.associate { it.asset to it.normalizedScore },
            regimeAdjustments = regimeAdjustments,
            tacticalWeights = normalizedTactical,
            currentRegime = currentRegime
        )
    }

    /**
     * 변동성 타겟팅
     *
     * 포트폴리오 변동성이 목표 변동성에 도달하도록 레버리지 조정
     */
    fun volatilityTargeting(
        currentWeights: Map<String, Double>,
        assets: List<String>,
        lookbackDays: Int = 30,
        targetVolatility: Double = TARGET_VOLATILITY,
        maxLeverage: Double = MAX_LEVERAGE
    ): Map<String, Double> {
        // 공분산 행렬 계산
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val returnsMatrix = getReturnsMatrix(assets, cutoffTime)

        if (returnsMatrix == null || returnsMatrix.isEmpty()) {
            return currentWeights
        }

        val covMatrix = calculateCovarianceMatrix(returnsMatrix)
        val weightsArray = assets.map { currentWeights[it] ?: 0.0 }.toDoubleArray()

        // 현재 포트폴리오 변동성 계산
        val currentVol = calculatePortfolioVolatility(weightsArray, covMatrix)

        // 변동성 스케일링 팩터
        val scaleFactor = minOf(
            targetVolatility / currentVol,
            maxLeverage
        ).coerceAtLeast(0.5)  // 최소 50% 레버리지

        log.info("변동성 타겟팅: CurrentVol=$currentVol, Target=$targetVolatility, Scale=$scaleFactor")

        return currentWeights.mapValues { it.value * scaleFactor }
    }

    /**
     * 자산 수익률 조회
     */
    private fun getAssetReturns(asset: String, cutoffTime: Instant): DoubleArray? {
        val interval = "1d"
        val cutoffTimestamp = cutoffTime.toEpochMilli() / 1000

        val history = ohlcvHistoryRepository.findByMarketAndIntervalOrderByTimestampAsc(asset, interval)
            .filter { it.timestamp >= cutoffTimestamp }

        if (history.size < 2) return null

        return history.zipWithNext().map { (prev, curr) ->
            if (prev.close > 0) (curr.close - prev.close) / prev.close else 0.0
        }.toDoubleArray()
    }

    /**
     * 수익률 행렬 계산
     */
    private fun getReturnsMatrix(
        assets: List<String>,
        cutoffTime: Instant
    ): Array<DoubleArray>? {
        val interval = "1d"
        val cutoffTimestamp = cutoffTime.toEpochMilli() / 1000

        val histories = assets.associateWith { asset ->
            ohlcvHistoryRepository.findByMarketAndIntervalOrderByTimestampAsc(asset, interval)
                .filter { it.timestamp >= cutoffTimestamp }
        }

        val minCount = histories.values.minOfOrNull { it.size } ?: return null
        if (minCount < MIN_HISTORY_DAYS) return null

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
     * 포트폴리오 변동성 계산
     */
    private fun calculatePortfolioVolatility(
        weights: DoubleArray,
        covMatrix: Array<DoubleArray>
    ): Double {
        val n = weights.size
        var variance = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                variance += weights[i] * weights[j] * covMatrix[i][j]
            }
        }
        return kotlin.math.sqrt(variance) * kotlin.math.sqrt(365.0)  // 연환산
    }
}
