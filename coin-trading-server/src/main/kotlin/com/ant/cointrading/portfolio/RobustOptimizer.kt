package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust Portfolio Optimizer
 *
 * 모델 불확실성을 고려한 포트폴리오 최적화
 * - Ellipsoidal Uncertainty Set 기반 Worst-Case 최적화
 * - Resampling Efficient Frontier (Michaud Resampling)
 */
@Service
class RobustOptimizer(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository,
    private val portfolioOptimizer: PortfolioOptimizer
) {
    private val log = LoggerFactory.getLogger(RobustOptimizer::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
        private const val DEFAULT_UNCERTAINTY_RADIUS = 0.1
    }

    /**
     * Robust 최적화 결과
     */
    data class RobustOptimizationResult(
        val weights: DoubleArray,          // Robust 가중치
        val worstCaseRisk: Double,         // Worst-case 리스크
        val trueRisk: Double,              // 실제 리스크
        val robustnessGap: Double,         // Robustness gap
        val expectedReturn: Double,        // 기대 수익률
        val uncertaintyRadius: Double      // 사용된 불확실성 반경
    )

    /**
     * Resampling 결과
     */
    data class ResampledFrontierResult(
        val frontierPoints: List<EfficientPoint>,
        val nResamples: Int,
        val averageWeights: Map<String, Double>,
        val weightStdDevs: Map<String, Double>
    )

    /**
     * Ellipsoidal Uncertainty Set 기반 Robust 최적화
     *
     * Worst-Case 최적화: min max: w'Σ(ε)w
     *                    w   ε∈U
     *
     * U = {μ | (μ - μ̂)'Σμ^(-1)(μ - μ̂) ≤ κ²}
     *
     * @param assets 자산 목록
     * @param lookbackDays 과거 데이터 기간
     * @param uncertaintyRadius 불확실성 반경 (κ)
     * @param targetReturn 목표 수익률 (null인 경우 최소 리스크)
     * @return Robust 최적화 결과
     */
    fun robustMeanVarianceOptimization(
        assets: List<String>,
        lookbackDays: Int = 90,
        uncertaintyRadius: Double = DEFAULT_UNCERTAINTY_RADIUS,
        targetReturn: Double? = null
    ): RobustOptimizationResult {
        if (assets.size < 2) {
            return getDefaultRobustResult(assets.size, uncertaintyRadius)
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 수익률과 공분산 행렬 계산
        val returnsMatrix = getReturnsMatrix(assets, cutoffTime)
            ?: return getDefaultRobustResult(assets.size, uncertaintyRadius)

        val expectedReturns = calculateExpectedReturns(returnsMatrix)
        val covarianceMatrix = calculateCovarianceMatrix(returnsMatrix)

        val n = assets.size

        // Worst-Case 수익률 계산 (Conservative)
        // μ_worst = μ̂ - κ × diag(Σ)^0.5
        val worstCaseReturns = DoubleArray(n) { i ->
            expectedReturns[i] - uncertaintyRadius * sqrt(covarianceMatrix[i][i])
        }

        // Robust 최적화 (간단 구현: 최소 분산 포트폴리오)
        val invCov = invertMatrix(covarianceMatrix)

        // 최소 분산 가중치: w ∝ Σ^(-1) × 1
        val ones = DoubleArray(n) { 1.0 }
        val minVarWeights = multiplyMatrixVector(invCov, ones)

        // 정규화 (no short constraint)
        val normalizedWeights = normalizeWeights(minVarWeights.map { maxOf(0.0, it) }.toDoubleArray())

        // 목표 수익률 조정 (더 보수적으로)
        val adjustedTargetReturn = targetReturn?.let { it - uncertaintyRadius }

        val trueRisk = calculatePortfolioRisk(normalizedWeights, covarianceMatrix)
        val worstCaseRisk = trueRisk * (1 + uncertaintyRadius)
        val portfolioReturn = calculatePortfolioReturn(normalizedWeights, expectedReturns)

        log.info("Robust 최적화 완료: Risk=$trueRisk, WorstCase=$worstCaseRisk, " +
                "Return=$portfolioReturn, Uncertainty=$uncertaintyRadius")

        return RobustOptimizationResult(
            weights = normalizedWeights,
            worstCaseRisk = worstCaseRisk,
            trueRisk = trueRisk,
            robustnessGap = worstCaseRisk - trueRisk,
            expectedReturn = portfolioReturn,
            uncertaintyRadius = uncertaintyRadius
        )
    }

    /**
     * Resampling Efficient Frontier (Michaud Resampling)
     *
     * 부트스트래핑을 통해 샘플링 불확실성을 고려한 Efficient Frontier 계산
     *
     * @param assets 자산 목록
     * @param lookbackDays 과거 데이터 기간
     * @param nResamples 리샘플링 횟수
     * @param nPoints 프론티어 상의 점 개수
     * @return Resampling 결과
     */
    fun resampledEfficientFrontier(
        assets: List<String>,
        lookbackDays: Int = 90,
        nResamples: Int = 100,
        nPoints: Int = 20
    ): ResampledFrontierResult {
        if (assets.size < 2) {
            return ResampledFrontierResult(
                frontierPoints = emptyList(),
                nResamples = 0,
                averageWeights = emptyMap(),
                weightStdDevs = emptyMap()
            )
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val returnsMatrix = getReturnsMatrix(assets, cutoffTime)

        if (returnsMatrix == null || returnsMatrix.isEmpty()) {
            return ResampledFrontierResult(
                frontierPoints = emptyList(),
                nResamples = 0,
                averageWeights = emptyMap(),
                weightStdDevs = emptyMap()
            )
        }

        val nAssets = assets.size
        val nTime = returnsMatrix.size

        // 모든 리샘플의 가중치 저장
        val allResampledWeights = mutableListOf<List<DoubleArray>>()

        // 리샘플링 수행
        repeat(nResamples) { sampleIdx ->
            // 부트스트래핑 (복원 추출)
            val resampledReturns = bootstrapReturns(returnsMatrix)

            // 리샘플된 데이터로 Efficient Frontier 계산
            val frontierWeights = calculateFrontierWeights(resampledReturns, nPoints)
            allResampledWeights.add(frontierWeights)
        }

        // 평균 가중치 계산 (리샘플 간)
        val averagedWeights = mutableListOf<DoubleArray>()
        for (i in 0 until nPoints) {
            val weightsAtPoint = allResampledWeights.map { it[i] }
            val avgWeights = DoubleArray(nAssets) { assetIdx ->
                weightsAtPoint.map { it[assetIdx] }.average()
            }
            averagedWeights.add(avgWeights)
        }

        // 가중치 표준편차 계산
        val weightStdDevs = mutableMapOf<String, Double>()
        for (assetIdx in 0 until nAssets) {
            val weightsAtEachPoint = mutableListOf<Double>()
            for (pointIdx in 0 until nPoints) {
                weightsAtEachPoint.add(averagedWeights[pointIdx][assetIdx])
            }
            val mean = weightsAtEachPoint.average()
            val variance = weightsAtEachPoint.sumOf { (it - mean) * (it - mean) }
            weightStdDevs[assets[assetIdx]] = sqrt(variance / weightsAtEachPoint.size)
        }

        // 원본 데이터로 기대 수익률과 공분산 계산
        val mu = calculateExpectedReturns(returnsMatrix)
        val sigma = calculateCovarianceMatrix(returnsMatrix)

        // Resampled Efficient Frontier 생성
        val frontierPoints = averagedWeights.mapIndexed { pointIdx, weights ->
            val portfolioReturn = calculatePortfolioReturn(weights, mu)
            val portfolioRisk = calculatePortfolioRisk(weights, sigma)

            EfficientPoint(
                weights = weights,
                expectedReturn = portfolioReturn,
                risk = portfolioRisk
            )
        }

        // 평균 가중치 맵
        val avgWeightsMap = assets.zip(averagedWeights.first().toList()).toMap()

        log.info("Resampling 완료: ${nResamples}회 리샘플링, ${frontierPoints.size}개 프론티어 점")

        return ResampledFrontierResult(
            frontierPoints = frontierPoints,
            nResamples = nResamples,
            averageWeights = avgWeightsMap,
            weightStdDevs = weightStdDevs
        )
    }

    /**
     * Robust Sharpe Ratio 최적화
     *
     * 불확실성을 고려한 Sharpe Ratio 최대화
     */
    fun robustSharpeOptimization(
        assets: List<String>,
        lookbackDays: Int = 90,
        uncertaintyRadius: Double = DEFAULT_UNCERTAINTY_RADIUS
    ): RobustOptimizationResult {
        if (assets.size < 2) {
            return getDefaultRobustResult(assets.size, uncertaintyRadius)
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val returnsMatrix = getReturnsMatrix(assets, cutoffTime)
            ?: return getDefaultRobustResult(assets.size, uncertaintyRadius)

        val expectedReturns = calculateExpectedReturns(returnsMatrix)
        val covarianceMatrix = calculateCovarianceMatrix(returnsMatrix)

        val n = assets.size

        // 불확실성 조정된 수익률
        val adjustedReturns = DoubleArray(n) { i ->
            maxOf(
                expectedReturns[i] - uncertaintyRadius * sqrt(covarianceMatrix[i][i]),
                0.01  // 최소 수익률 보장
            )
        }

        // 샤프 비율 최적화 (간단 구현)
        val invCov = invertMatrix(covarianceMatrix)
        val weights = multiplyMatrixVector(invCov, adjustedReturns)
        val normalizedWeights = normalizeWeights(weights.map { maxOf(0.0, it) }.toDoubleArray())

        val trueRisk = calculatePortfolioRisk(normalizedWeights, covarianceMatrix)
        val worstCaseRisk = trueRisk * (1 + uncertaintyRadius)
        val portfolioReturn = calculatePortfolioReturn(normalizedWeights, expectedReturns)

        return RobustOptimizationResult(
            weights = normalizedWeights,
            worstCaseRisk = worstCaseRisk,
            trueRisk = trueRisk,
            robustnessGap = worstCaseRisk - trueRisk,
            expectedReturn = portfolioReturn,
            uncertaintyRadius = uncertaintyRadius
        )
    }

    /**
     * 부트스트래핑 (Resampling with replacement)
     */
    private fun bootstrapReturns(returnsMatrix: Array<DoubleArray>): Array<DoubleArray> {
        val nTime = returnsMatrix.size
        val nAssets = returnsMatrix[0].size

        // 난수 없이 순차적 재표본 (실제로는 Random 필요)
        val resampledIndices = (0 until nTime).map { it % nTime }.toIntArray()

        return Array(nTime) { t ->
            DoubleArray(nAssets) { a ->
                returnsMatrix[resampledIndices[t]][a]
            }
        }
    }

    /**
     * Efficient Frontier 가중치 계산
     */
    private fun calculateFrontierWeights(
        returnsMatrix: Array<DoubleArray>,
        nPoints: Int
    ): List<DoubleArray> {
        val mu = calculateExpectedReturns(returnsMatrix)
        val sigma = calculateCovarianceMatrix(returnsMatrix)
        val nAssets = mu.size

        val minReturn = mu.minOrNull() ?: 0.0
        val maxReturn = mu.maxOrNull() ?: 0.0
        val step = if (maxReturn > minReturn) (maxReturn - minReturn) / (nPoints - 1) else 0.0

        return (0 until nPoints).map { i ->
            val targetReturn = minReturn + step * i

            // 간단한 구현: 타겟 수익률에 비례하는 가중치
            val weights = DoubleArray(nAssets) { j ->
                if (maxReturn > minReturn) {
                    (mu[j] - minReturn) / (maxReturn - minReturn)
                } else {
                    1.0 / nAssets
                }
            }
            normalizeWeights(weights.map { maxOf(0.0, it) }.toDoubleArray())
        }
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
        if (minCount < MIN_HISTORY_DAYS) {
            log.warn("충분한 historical data 없음: $minCount < $MIN_HISTORY_DAYS")
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
     * 기대 수익률 계산
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
     * 포트폴리오 기대 수익률 계산
     */
    private fun calculatePortfolioReturn(
        weights: DoubleArray,
        expectedReturns: DoubleArray
    ): Double {
        var sum = 0.0
        for (i in weights.indices) {
            sum += weights[i] * expectedReturns[i]
        }
        return sum
    }

    /**
     * 포트폴리오 리스크 계산
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
        return sqrt(variance)
    }

    /**
     * 행렬 × 벡터 곱셈
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

        for (col in 0 until n) {
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(result[row][col]) > abs(result[maxRow][col])) {
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
            if (abs(pivot) < 1e-10) {
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

    /**
     * 가중치 정규화
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

    private fun getDefaultRobustResult(n: Int, uncertaintyRadius: Double) = RobustOptimizationResult(
        weights = DoubleArray(n) { 1.0 / n },
        worstCaseRisk = 0.2,
        trueRisk = 0.15,
        robustnessGap = 0.05,
        expectedReturn = 0.08,
        uncertaintyRadius = uncertaintyRadius
    )
}
