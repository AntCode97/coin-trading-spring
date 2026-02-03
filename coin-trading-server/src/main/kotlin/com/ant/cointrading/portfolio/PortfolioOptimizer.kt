package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Markowitz Mean-Variance Optimization
 *
 * 암호화폐 포트폴리오 최적화를 위한 현대 포트폴리오 이론 구현
 */
@Service
class PortfolioOptimizer(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository
) {
    private val log = LoggerFactory.getLogger(PortfolioOptimizer::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
        private const val RISK_FREE_RATE = 0.02  // 2% 무위험이자율
    }

    /**
     * 샤프 비율 기반 최적 가중치 계산
     *
     * @param assets 자산 목록 (예: ["BTC_KRW", "ETH_KRW", "XRP_KRW"])
     * @param lookbackDays 과거 데이터 기간 (기본 90일)
     * @return 최적 가중치 배열
     */
    fun maximizeSharpeRatio(
        assets: List<String>,
        lookbackDays: Int = 90
    ): OptimizationResult {
        if (assets.size < 2) {
            return OptimizationResult(
                weights = doubleArrayOf(1.0),
                expectedReturn = 0.0,
                portfolioRisk = 0.0,
                sharpeRatio = 0.0
            )
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 수익률 행렬 계산
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime)
            ?: return getEqualWeights(assets)

        val expectedReturns = calculateExpectedReturns(returnsMatrix)
        val covarianceMatrix = calculateCovarianceMatrix(returnsMatrix)

        // 샤프 비율 최적화 (Inverse Variance Weighting 근사)
        val invCov = invertMatrix(covarianceMatrix)
        val weights = multiplyMatrixVector(invCov, expectedReturns.map { it - RISK_FREE_RATE }.toDoubleArray())
        val normalizedWeights = normalizeWeights(weights)

        val portfolioReturn = calculatePortfolioReturn(normalizedWeights, expectedReturns)
        val portfolioRisk = calculatePortfolioRisk(normalizedWeights, covarianceMatrix)
        val sharpeRatio = if (portfolioRisk > 0) (portfolioReturn - RISK_FREE_RATE) / portfolioRisk else 0.0

        log.info("샤프 비율 최적화 완료: Sharpe=$sharpeRatio, Return=$portfolioReturn, Risk=$portfolioRisk")

        return OptimizationResult(
            weights = normalizedWeights,
            expectedReturn = portfolioReturn,
            portfolioRisk = portfolioRisk,
            sharpeRatio = sharpeRatio
        )
    }

    /**
     * 리스크 패리티 기반 가중치 계산
     *
     * 모든 자산이 포트폴리오 리스크에 동등하게 기여하도록 가중치 배정
     */
    fun calculateRiskParity(
        assets: List<String>,
        lookbackDays: Int = 90,
        tolerance: Double = 1e-8,
        maxIter: Int = 1000
    ): OptimizationResult {
        if (assets.size < 2) {
            return OptimizationResult(
                weights = doubleArrayOf(1.0),
                expectedReturn = 0.0,
                portfolioRisk = 0.0,
                sharpeRatio = 0.0
            )
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime)
            ?: return getEqualWeights(assets)

        val expectedReturns = calculateExpectedReturns(returnsMatrix)
        val covarianceMatrix = calculateCovarianceMatrix(returnsMatrix)

        val n = assets.size
        var weights = DoubleArray(n) { 1.0 / n }

        repeat(maxIter) {
            val marginalContribs = calculateMarginalContributions(weights, covarianceMatrix)
            val riskContribs = DoubleArray(n) { i -> weights[i] * marginalContribs[i] }
            val avgRiskContrib = riskContribs.sum() / n

            // 가중치 업데이트
            weights = DoubleArray(n) { i ->
                weights[i] * kotlin.math.sqrt(avgRiskContrib / riskContribs[i])
            }

            // 정규화
            val sumW = weights.sum()
            weights = weights.map { it / sumW }.toDoubleArray()

            // 수렴 확인
            val maxDeviation = riskContribs.map { (it - avgRiskContrib).let { d -> kotlin.math.abs(d) } }.maxOrNull() ?: 0.0
            if (maxDeviation < tolerance) {
                log.info("리스크 패리티 수렴: iteration=$it, deviation=$maxDeviation")
                return@repeat
            }
        }

        val portfolioReturn = calculatePortfolioReturn(weights, expectedReturns)
        val portfolioRisk = calculatePortfolioRisk(weights, covarianceMatrix)
        val sharpeRatio = if (portfolioRisk > 0) (portfolioReturn - RISK_FREE_RATE) / portfolioRisk else 0.0

        log.info("리스크 패리티 완료: Sharpe=$sharpeRatio, Return=$portfolioReturn, Risk=$portfolioRisk")

        return OptimizationResult(
            weights = weights,
            expectedReturn = portfolioReturn,
            portfolioRisk = portfolioRisk,
            sharpeRatio = sharpeRatio
        )
    }

    /**
     * Efficient Frontier 계산
     *
     * @param nPoints 프론티어 상의 점 개수
     * @return Efficient Frontier 상의 포인트 목록
     */
    fun calculateEfficientFrontier(
        assets: List<String>,
        lookbackDays: Int = 90,
        nPoints: Int = 20
    ): List<EfficientPoint> {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime) ?: return emptyList()
        val expectedReturns = calculateExpectedReturns(returnsMatrix)
        val covarianceMatrix = calculateCovarianceMatrix(returnsMatrix)

        val minReturn = expectedReturns.minOrNull() ?: 0.0
        val maxReturn = expectedReturns.maxOrNull() ?: 0.0
        val step = (maxReturn - minReturn) / (nPoints - 1)

        return (0 until nPoints).map { i ->
            val targetReturn = minReturn + step * i
            solveConstrainedOptimization(expectedReturns, covarianceMatrix, targetReturn)
        }
    }

    /**
     * 제약 조건 하에서 최적화 (타겟 수익률)
     */
    private fun solveConstrainedOptimization(
        expectedReturns: DoubleArray,
        covarianceMatrix: Array<DoubleArray>,
        targetReturn: Double
    ): EfficientPoint {
        // 간단한 근사: 타겟 수익률에 가까운 자산에 더 큰 가중치
        val n = expectedReturns.size
        val weights = DoubleArray(n) { i ->
            val maxReturn = expectedReturns.maxOrNull() ?: 1.0
            val minReturn = expectedReturns.minOrNull() ?: 0.0
            if (maxReturn > minReturn) {
                (expectedReturns[i] - minReturn) / (maxReturn - minReturn)
            } else {
                1.0 / n
            }
        }

        val normalizedWeights = normalizeWeights(weights)
        val portfolioRisk = calculatePortfolioRisk(normalizedWeights, covarianceMatrix)

        return EfficientPoint(
            weights = normalizedWeights,
            expectedReturn = targetReturn,
            risk = portfolioRisk
        )
    }

    /**
     * 수익률 행렬 계산
     *
     * @return [time][asset] 형태의 2차원 배열
     */
    private fun calculateReturnsMatrix(
        assets: List<String>,
        cutoffTime: Instant
    ): Array<DoubleArray>? {
        // 1일 캔들 데이터 조회
        val interval = "1d"
        val cutoffTimestamp = cutoffTime.toEpochMilli() / 1000

        val histories = assets.associateWith { asset ->
            ohlcvHistoryRepository.findByMarketAndIntervalOrderByTimestampAsc(asset, interval)
                .filter { it.timestamp >= cutoffTimestamp }
        }

        val minCount = histories.values.minOfOrNull { it.size } ?: return null
        if (minCount < MIN_HISTORY_DAYS) {
            log.warn("충분한 historical data 없음: $minCount < $MIN_HISTORY_DAYS")
            return null
        }

        // 가격 데이터 정렬 (모든 자산이 동일한 타임스탬프를 가정)
        val prices = histories.mapValues { (_, history) ->
            history.take(minCount).map { it.close }
        }

        // 수익률 계산
        val returns = prices.mapValues { (_, assetPrices) ->
            assetPrices.zipWithNext().map { (prev, curr) ->
                if (prev > 0) (curr - prev) / prev else 0.0
            }
        }

        // Transpose: [time][asset]
        val numReturns = returns.values.first().size
        return Array(numReturns) { t ->
            DoubleArray(assets.size) { a ->
                returns[assets[a]]?.get(t) ?: 0.0
            }
        }
    }

    /**
     * 기대 수익률 계산 (평균)
     */
    private fun calculateExpectedReturns(returnsMatrix: Array<DoubleArray>): DoubleArray {
        val nAssets = returnsMatrix[0].size
        return DoubleArray(nAssets) { i ->
            returnsMatrix.map { row -> row[i] }.average()
        }
    }

    /**
     * 공분산 행렬 계산
     */
    private fun calculateCovarianceMatrix(returnsMatrix: Array<DoubleArray>): Array<DoubleArray> {
        val nAssets = returnsMatrix[0].size
        val means = calculateExpectedReturns(returnsMatrix)

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
     * 행렬 곱셈 (Matrix × Vector)
     */
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

    /**
     * 역행렬 계산 (Gaussian Elimination)
     */
    private fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val result = Array(n) { i -> matrix[i].copyOf() }
        val identity = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }

        // Gaussian Elimination with Partial Pivoting
        for (col in 0 until n) {
            // Pivot 선택
            var maxRow = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(result[row][col]) > kotlin.math.abs(result[maxRow][col])) {
                    maxRow = row
                }
            }

            // 행 교환
            val temp = result[col]
            result[col] = result[maxRow]
            result[maxRow] = temp

            val tempId = identity[col]
            identity[col] = identity[maxRow]
            identity[maxRow] = tempId

            // 정규화
            val pivot = result[col][col]
            if (kotlin.math.abs(pivot) < 1e-10) {
                // Singular matrix: return diagonal matrix with small values
                return Array(n) { i ->
                    DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 }
                }
            }

            for (j in 0 until n) {
                result[col][j] /= pivot
                identity[col][j] /= pivot
            }

            // 소거
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

    /**
     * 한계 리스크 기여도 계산
     */
    private fun calculateMarginalContributions(
        weights: DoubleArray,
        covarianceMatrix: Array<DoubleArray>
    ): DoubleArray {
        val n = weights.size
        return DoubleArray(n) { i ->
            var sum = 0.0
            for (j in 0 until n) {
                sum += covarianceMatrix[i][j] * weights[j]
            }
            sum
        }
    }

    /**
     * 포트폴리오 기대 수익률 계산
     */
    private fun calculatePortfolioReturn(
        weights: DoubleArray,
        expectedReturns: DoubleArray
    ): Double {
        return weights.zip(expectedReturns).sumOf { (w, r) -> w * r }
    }

    /**
     * 포트폴리오 리스크(표준편차) 계산
     */
    private fun calculatePortfolioRisk(
        weights: DoubleArray,
        covarianceMatrix: Array<DoubleArray>
    ): Double {
        val n = weights.size
        var variance = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                variance += weights[i] * weights[j] * covarianceMatrix[i][j]
            }
        }
        return kotlin.math.sqrt(variance)
    }

    /**
     * 가중치 정규화 (합이 1이 되도록)
     */
    private fun normalizeWeights(weights: DoubleArray): DoubleArray {
        val sum = weights.sum()
        return if (sum > 0) {
            weights.map { it / sum }.toDoubleArray()
        } else {
            val n = weights.size
            DoubleArray(n) { 1.0 / n }
        }
    }

    /**
     * 균등 가중치 (Fallback)
     */
    private fun getEqualWeights(assets: List<String>): OptimizationResult {
        val n = assets.size
        val weights = DoubleArray(n) { 1.0 / n }
        return OptimizationResult(
            weights = weights,
            expectedReturn = 0.0,
            portfolioRisk = 0.0,
            sharpeRatio = 0.0
        )
    }
}

/**
 * 최적화 결과
 */
data class OptimizationResult(
    val weights: DoubleArray,        // 자산별 가중치
    val expectedReturn: Double,       // 포트폴리오 기대 수익률
    val portfolioRisk: Double,        // 포트폴리오 리스크 (표준편차)
    val sharpeRatio: Double           // 샤프 비율
)

/**
 * Efficient Frontier 상의 점
 */
data class EfficientPoint(
    val weights: DoubleArray,
    val expectedReturn: Double,
    val risk: Double
)
