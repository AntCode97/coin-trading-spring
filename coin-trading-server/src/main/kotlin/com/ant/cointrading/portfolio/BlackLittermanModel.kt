package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-Litterman Model
 *
 * 시장均衡점 + 투자자 뷰 결합을 통한 포트폴리오 최적화
 */
@Service
class BlackLittermanModel(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository
) {
    private val log = LoggerFactory.getLogger(BlackLittermanModel::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
        private const val RISK_FREE_RATE = 0.02  // 2%
        private const val MARKET_RISK_PREMIUM = 0.06  // 6%
        private const val DEFAULT_TAU = 0.025  // 스케일링 파라미터
    }

    /**
     * 투자자 뷰
     */
    data class View(
        val assets: List<Int>,           // 관련 자산 인덱스 (예: [0, 2] = BTC, XRP)
        val viewReturn: Double,          // 기대 수익률 (예: 0.15 = +15%)
        val confidence: Double,          // 신뢰도 (0.0~1.0)
        val description: String = ""     // 뷰 설명
    )

    /**
     * Black-Litterman 수익률 계산
     *
     * @param assets 자산 목록
     * @param views 투자자 뷰 목록
     * @param marketCaps 시가총액 (시가총액 기반均衡점 계산용, null인 경우 균등 가중치 사용)
     * @param lookbackDays 과거 데이터 기간
     * @param tau 스케일링 파라미터 (기본 0.025)
     * @return BL 수익률
     */
    fun calculateBlackLittermanReturns(
        assets: List<String>,
        views: List<View>,
        marketCaps: DoubleArray? = null,
        lookbackDays: Int = 90,
        tau: Double = DEFAULT_TAU
    ): BLResult {
        if (assets.isEmpty()) {
            return BLResult(
                expectedReturns = doubleArrayOf(),
                impliedReturns = doubleArrayOf(),
                viewAdjustedReturns = doubleArrayOf(),
                newCovariance = emptyArray()
            )
        }

        val n = assets.size

        // 1. 공분산 행렬 계산
        val covarianceMatrix = calculateCovarianceMatrix(assets, lookbackDays)
            ?: return getFallbackResult(assets.size)

        // 2. Implied Equilibrium Return (Π) 계산
        val impliedReturns = calculateImpliedReturns(
            assets,
            marketCaps,
            covarianceMatrix,
            lookbackDays
        )

        // 3. 뷰 설정 (P, Q, Ω)
        val pickMatrix = buildPickMatrix(views, n)
        val viewVector = views.map { it.viewReturn }.toDoubleArray()
        val viewUncertainty = calculateViewUncertainty(
            covarianceMatrix,
            pickMatrix,
            views.map { it.confidence }.toDoubleArray(),
            tau
        )

        // 4. Black-Litterman 수익률 계산
        // E[R] = [(τΣ)^(-1) + P'Ω^(-1)P]^(-1) × [(τΣ)^(-1)Π + P'Ω^(-1)Q]
        val blReturns = calculateBLReturns(
            impliedReturns,
            covarianceMatrix,
            pickMatrix,
            viewVector,
            viewUncertainty,
            tau
        )

        // 5. 조정된 공분산 행렬
        val newCovariance = adjustCovarianceMatrix(covarianceMatrix, pickMatrix, tau)

        log.info("BL 계산 완료: ${assets.size}자산, ${views.size}개 뷰")

        return BLResult(
            expectedReturns = blReturns,
            impliedReturns = impliedReturns,
            viewAdjustedReturns = blReturns,
            newCovariance = newCovariance
        )
    }

    /**
     * Implied Equilibrium Return (Π) 계산
     *
     * 시가총액 기반 또는 균등 가중치 기반
     */
    fun calculateImpliedReturns(
        assets: List<String>,
        marketCaps: DoubleArray?,
        covarianceMatrix: Array<DoubleArray>,
        lookbackDays: Int
    ): DoubleArray {
        val n = assets.size

        // 시가총액 기반 가중치
        val weights = if (marketCaps != null && marketCaps.size == n) {
            val totalCap = marketCaps.sum()
            marketCaps.map { it / totalCap }.toDoubleArray()
        } else {
            // 균등 가중치 (Fallback)
            DoubleArray(n) { 1.0 / n }
        }

        // CAPM: E[Ri] = Rf + βi × (E[Rm] - Rf])
        // 암호화폐는 β ≈ 1 (전체 시장과 높은 상관관계)
        return DoubleArray(n) { i ->
            RISK_FREE_RATE + 1.0 * MARKET_RISK_PREMIUM
        }
    }

    /**
     * Pick Matrix (P) 빌드
     *
     * 뷰와 자산 간의 매핑 행렬
     */
    private fun buildPickMatrix(views: List<View>, nAssets: Int): Array<DoubleArray> {
        val pickMatrix = Array(views.size) { i ->
            DoubleArray(nAssets) { 0.0 }
        }

        views.forEachIndexed { viewIndex, view ->
            view.assets.forEach { assetIndex ->
                if (assetIndex in 0 until nAssets) {
                    // 상대적 뷰: 첫 번째 자산은 +1, 나머지는 -1
                    if (view.assets.indexOf(assetIndex) == 0) {
                        pickMatrix[viewIndex][assetIndex] = 1.0
                    } else {
                        pickMatrix[viewIndex][assetIndex] = -1.0 / (view.assets.size - 1)
                    }
                }
            }
        }

        return pickMatrix
    }

    /**
     * 뷰 불확실성 행렬 (Ω) 계산
     *
     * Ω = diag(P × Σ × P') / τ
     */
    private fun calculateViewUncertainty(
        covarianceMatrix: Array<DoubleArray>,
        pickMatrix: Array<DoubleArray>,
        confidences: DoubleArray,
        tau: Double
    ): Array<DoubleArray> {
        val nViews = pickMatrix.size
        val omega = Array(nViews) { i ->
            DoubleArray(nViews) { 0.0 }
        }

        for (i in 0 until nViews) {
            // P × Σ × P' 계산
            var pSigmaPt = 0.0
            for (j in 0 until covarianceMatrix.size) {
                for (k in 0 until covarianceMatrix.size) {
                    pSigmaPt += pickMatrix[i][j] * covarianceMatrix[j][k] * pickMatrix[i][k]
                }
            }

            // 신뢰도 기반 조정 (신뢰도가 낮을수록 불확실성 증가)
            val confidence = confidences[i].coerceIn(0.01, 1.0)
            val scaledVariance = pSigmaPt / (confidence * confidence * tau)

            omega[i][i] = scaledVariance
        }

        return omega
    }

    /**
     * Black-Litterman 수익률 계산 (간략 버전)
     *
     * E[R] = Π + Σ × P' × (P × Σ × P' + Ω)^(-1) × (Q - P × Π)
     */
    private fun calculateBLReturns(
        impliedReturns: DoubleArray,
        covarianceMatrix: Array<DoubleArray>,
        pickMatrix: Array<DoubleArray>,
        viewVector: DoubleArray,
        viewUncertainty: Array<DoubleArray>,
        tau: Double
    ): DoubleArray {
        val n = impliedReturns.size

        // 1. (P × Σ × P' + Ω)^(-1) 계산
        val pSigmaPt = Array(pickMatrix.size) { i ->
            DoubleArray(pickMatrix.size) { j ->
                var sum = 0.0
                for (k in 0 until n) {
                    for (l in 0 until n) {
                        sum += pickMatrix[i][k] * covarianceMatrix[k][l] * pickMatrix[j][l]
                    }
                }
                sum
            }
        }

        val m1 = addMatrices(pSigmaPt, viewUncertainty)
        val m1Inv = invertMatrix(m1)

        // 2. (Q - P × Π) 계산
        val qMinusPpi = DoubleArray(viewVector.size) { i ->
            var pPi = 0.0
            for (j in 0 until n) {
                pPi += pickMatrix[i][j] * impliedReturns[j]
            }
            viewVector[i] - pPi
        }

        // 3. Σ × P' 계산
        val sigmaPt = Array(n) { i ->
            DoubleArray(viewVector.size) { j ->
                var sum = 0.0
                for (k in 0 until n) {
                    sum += covarianceMatrix[i][k] * pickMatrix[j][k]
                }
                sum
            }
        }

        // 4. (Σ × P') × (P × Σ × P' + Ω)^(-1) × (Q - P × Π) 계산
        val adjustment = DoubleArray(n) { i ->
            var sum = 0.0
            for (j in 0 until viewVector.size) {
                for (k in 0 until viewVector.size) {
                    sum += sigmaPt[i][j] * m1Inv[j][k] * qMinusPpi[k]
                }
            }
            sum
        }

        // 5. E[R] = Π + adjustment
        return DoubleArray(n) { i ->
            impliedReturns[i] + adjustment[i]
        }
    }

    /**
     * 조정된 공분산 행렬
     *
     * Σ* = (1 + τ) × Σ
     */
    private fun adjustCovarianceMatrix(
        covarianceMatrix: Array<DoubleArray>,
        pickMatrix: Array<DoubleArray>,
        tau: Double
    ): Array<DoubleArray> {
        val n = covarianceMatrix.size
        return Array(n) { i ->
            DoubleArray(n) { j ->
                covarianceMatrix[i][j] * (1 + tau)
            }
        }
    }

    /**
     * 공분산 행렬 계산
     */
    private fun calculateCovarianceMatrix(
        assets: List<String>,
        lookbackDays: Int
    ): Array<DoubleArray>? {
        val returnsMatrix = calculateReturnsMatrix(assets, lookbackDays)
            ?: return null

        val nAssets = assets.size
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

    // ========== Helper Functions ==========

    private fun addMatrices(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>
    ): Array<DoubleArray> {
        val n = a.size
        val m = a[0].size
        return Array(n) { i ->
            DoubleArray(m) { j ->
                a[i][j] + b[i][j]
            }
        }
    }

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
                return Array(n) { i ->
                    DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 }
                }
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

    private fun getFallbackResult(n: Int): BLResult {
        return BLResult(
            expectedReturns = DoubleArray(n) { 0.08 },
            impliedReturns = DoubleArray(n) { 0.08 },
            viewAdjustedReturns = DoubleArray(n) { 0.08 },
            newCovariance = Array(n) { i ->
                DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 }
            }
        )
    }
}

/**
 * Black-Litterman 결과
 */
data class BLResult(
    val expectedReturns: DoubleArray,     // BL 조정 후 기대 수익률
    val impliedReturns: DoubleArray,       // 시장均衡점 수익률 (Π)
    val viewAdjustedReturns: DoubleArray,  // 뷰 반영 후 수익률
    val newCovariance: Array<DoubleArray>  // 조정된 공분산 행렬
)
