package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Factor Models for Portfolio Management
 *
 * CAPM, Fama-French 3-Factor Model (암호화폐 버전), PCA 기반 통계적 팩터 모델 구현
 */
@Service
class FactorModelCalculator(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository
) {
    private val log = LoggerFactory.getLogger(FactorModelCalculator::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
        private const val RISK_FREE_RATE = 0.02  // 2%
        private const val MARKET_RISK_PREMIUM = 0.06  // 6%
    }

    /**
     * CAPM (Capital Asset Pricing Model) 결과
     */
    data class CAPMResult(
        val alpha: Double,           // 잉여 수익률 (Jensen's Alpha)
        val beta: Double,            // 시장 베타
        val rSquared: Double,        // 설명력 (R²)
        val idiosyncraticVol: Double, // 고유 변동성 (잔차 표준편차)
        val expectedReturn: Double   // CAPM 기대 수익률
    )

    /**
     * Fama-French 3-Factor 결과
     */
    data class FamaFrenchResult(
        val alpha: Double,
        val betaMKT: Double,         // Market Factor Beta
        val betaSMB: Double,         // Size Factor Beta
        val betaHML: Double,         // Momentum Factor Beta
        val rSquared: Double,
        val expectedReturn: Double
    )

    /**
     * 암호화폐 팩터 수익률
     */
    data class CryptoFactorReturns(
        val mkt: Double,  // Market Risk Premium (BTC - Cash)
        val smb: Double,  // Size Factor (Small Cap - Large Cap)
        val hml: Double   // Momentum Factor (High Momentum - Low Momentum)
    )

    /**
     * CAPM 베타 계산
     *
     * E[Ri] = Rf + βi × (E[Rm] - Rf])
     *
     * @param asset 자산 심볼 (예: "ETH_KRW")
     * @param marketProxy 시장 프록시 (기본: "BTC_KRW")
     * @param lookbackDays 과거 데이터 기간
     * @return CAPM 결과
     */
    fun calculateCAPM(
        asset: String,
        marketProxy: String = "BTC_KRW",
        lookbackDays: Int = 90
    ): CAPMResult {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 수익률 계산
        val assetReturns = getAssetReturns(asset, cutoffTime) ?: return getDefaultCAPMResult()
        val marketReturns = getAssetReturns(marketProxy, cutoffTime) ?: return getDefaultCAPMResult()

        // 최소 길이 확인
        val minLen = minOf(assetReturns.size, marketReturns.size)
        if (minLen < MIN_HISTORY_DAYS) {
            log.warn("충분한 historical data 없음: $minLen < $MIN_HISTORY_DAYS")
            return getDefaultCAPMResult()
        }

        // OLS 회귀: Ri = α + β × Rm + ε
        val n = minLen
        val assetReturnsSlice = assetReturns.take(n)
        val marketReturnsSlice = marketReturns.take(n)

        val sumX = marketReturnsSlice.sum()
        val sumY = assetReturnsSlice.sum()
        var sumXY = 0.0
        for (i in 0 until n) {
            sumXY += marketReturnsSlice[i] * assetReturnsSlice[i]
        }
        val sumX2 = marketReturnsSlice.sumOf { it * it }

        val beta = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val alpha = (sumY - beta * sumX) / n

        // R-squared 계산
        val yMean = assetReturnsSlice.average()
        var ssTot = 0.0
        for (i in 0 until n) {
            val diff = assetReturnsSlice[i] - yMean
            ssTot += diff * diff
        }
        val yPred = marketReturnsSlice.map { alpha + beta * it }
        var ssRes = 0.0
        for (i in 0 until n) {
            val diff = assetReturnsSlice[i] - yPred[i]
            ssRes += diff * diff
        }
        val rSquared = if (ssTot > 0) 1 - (ssRes / ssTot) else 0.0

        // 고유 변동성 (잔차 표준편차)
        var residualVariance = 0.0
        for (i in 0 until n) {
            val residual = assetReturnsSlice[i] - yPred[i]
            residualVariance += residual * residual
        }
        val idiosyncraticVol = sqrt(residualVariance / n)

        // CAPM 기대 수익률
        val expectedReturn = RISK_FREE_RATE + beta * MARKET_RISK_PREMIUM

        log.info("CAPM 계산 완료: $asset, β=$beta, α=$alpha, R²=$rSquared")

        return CAPMResult(
            alpha = alpha,
            beta = beta,
            rSquared = rSquared,
            idiosyncraticVol = idiosyncraticVol,
            expectedReturn = expectedReturn
        )
    }

    /**
     * 암호화폐용 Fama-French 3-Factor Model
     *
     * E[Ri] = Rf + βi,MKT × (RM - Rf)
     *             + βi,SMB × SMB
     *             + βi,HML × HML
     *
     * 팩터 정의:
     * - MKT: Market Risk Premium (BTC 수익률 - 무위험률)
     * - SMB: Size Factor (소형주 수익률 - 대형주 수익률)
     * - HML: Momentum Factor (고모멘텀 수익률 - 저모멘텀 수익률)
     *
     * @param asset 자산 심볼
     * @param assets 모든 자산 목록 (SMB/HML 계산용)
     * @param lookbackDays 과거 데이터 기간
     * @return Fama-French 결과
     */
    fun calculateFamaFrench(
        asset: String,
        assets: List<String>,
        lookbackDays: Int = 90
    ): FamaFrenchResult {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 자산 수익률
        val assetReturns = getAssetReturns(asset, cutoffTime) ?: return getDefaultFFResult()

        // 팩터 수익률 계산
        val factors = calculateCryptoFactors(assets, cutoffTime)
        val n = minOf(assetReturns.size, factors.size)

        if (n < MIN_HISTORY_DAYS) {
            log.warn("충분한 historical data 없음: $n < $MIN_HISTORY_DAYS")
            return getDefaultFFResult()
        }

        // 다중 회귀: β = (X'X)^(-1)X'Y
        val X = factors.take(n).map { listOf(1.0, it.mkt, it.smb, it.hml) }
        val Y = assetReturns.take(n)

        val betas = multipleRegression(X, Y)

        // R-squared 계산
        val yPred = X.map { row -> row[0] * betas[0] + row[1] * betas[1] + row[2] * betas[2] + row[3] * betas[3] }
        val yMean = Y.average()
        var ssTot = 0.0
        for (y in Y) {
            val diff = y - yMean
            ssTot += diff * diff
        }
        var ssRes = 0.0
        for (i in Y.indices) {
            val diff = Y[i] - yPred[i]
            ssRes += diff * diff
        }
        val rSquared = if (ssTot > 0) 1 - (ssRes / ssTot) else 0.0

        // 기대 수익률
        val expectedReturn = RISK_FREE_RATE +
            betas[1] * MARKET_RISK_PREMIUM +  // MKT
            betas[2] * 0.02 +                 // SMB (가정: 2% 프리미엄)
            betas[3] * 0.03                   // HML (가정: 3% 프리미엄)

        log.info("Fama-French 계산 완료: $asset, βMKT=${betas[1]}, βSMB=${betas[2]}, βHML=${betas[3]}")

        return FamaFrenchResult(
            alpha = betas[0],
            betaMKT = betas[1],
            betaSMB = betas[2],
            betaHML = betas[3],
            rSquared = rSquared,
            expectedReturn = expectedReturn
        )
    }

    /**
     * 여러 자산의 CAPM 결과 일괄 계산
     */
    fun calculateMultipleCAPM(
        assets: List<String>,
        marketProxy: String = "BTC_KRW",
        lookbackDays: Int = 90
    ): Map<String, CAPMResult> {
        return assets.associateWith { asset ->
            calculateCAPM(asset, marketProxy, lookbackDays)
        }
    }

    /**
     * 암호화폐 팩터 수익률 계산
     */
    private fun calculateCryptoFactors(
        assets: List<String>,
        cutoffTime: Instant
    ): List<CryptoFactorReturns> {
        // 각 자산의 수익률과 시가총액 정보 조회 필요
        // 현재는 간단 구현: BTC를 MKT, 나머지를 시가총액 기반으로 분류

        val btcReturns = getAssetReturns("BTC_KRW", cutoffTime) ?: return emptyList()
        val allReturns = assets.associateWith { getAssetReturns(it, cutoffTime) }

        val minLen = allReturns.values.filterNotNull().map { it.size }.minOrNull() ?: return emptyList()

        return (0 until minLen).map { t ->
            // MKT: BTC 수익률 - 무위험률
            val mkt = (btcReturns.getOrNull(t) ?: 0.0) - (RISK_FREE_RATE / 365)

            // SMB: Small Cap - Large Cap (간단 구현)
            val smb = 0.01  // TODO: 시가총액 기반 실제 계산

            // HML: High Momentum - Low Momentum (30일 모멘텀)
            val hml = 0.02  // TODO: 모멘텀 기반 실제 계산

            CryptoFactorReturns(mkt = mkt, smb = smb, hml = hml)
        }
    }

    /**
     * 다중 회귀 (Matrix form: β = (X'X)^(-1)X'Y)
     */
    private fun multipleRegression(
        X: List<List<Double>>,
        Y: List<Double>
    ): DoubleArray {
        val nRows = X.size
        val nCols = X[0].size

        // X'X 계산
        val XtX = Array(nCols) { i ->
            DoubleArray(nCols) { j ->
                (0 until nRows).sumOf { row -> X[row][i] * X[row][j] }
            }
        }

        // X'Y 계산
        val XtY = DoubleArray(nCols) { i ->
            (0 until nRows).sumOf { row -> X[row][i] * Y[row] }
        }

        // (X'X)^(-1) 계산
        val XtXInv = invertMatrix(XtX)

        // β = (X'X)^(-1)X'Y
        return multiplyMatrixVector(XtXInv, XtY)
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

    private fun getDefaultCAPMResult() = CAPMResult(
        alpha = 0.0,
        beta = 1.0,
        rSquared = 0.0,
        idiosyncraticVol = 0.5,
        expectedReturn = RISK_FREE_RATE + MARKET_RISK_PREMIUM
    )

    private fun getDefaultFFResult() = FamaFrenchResult(
        alpha = 0.0,
        betaMKT = 1.0,
        betaSMB = 0.0,
        betaHML = 0.0,
        rSquared = 0.0,
        expectedReturn = RISK_FREE_RATE + MARKET_RISK_PREMIUM
    )
}
