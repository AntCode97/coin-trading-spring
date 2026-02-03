package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Kelly Criterion 기반 동적 포지션 사이징
 *
 * - Half Kelly 보수적 접근
 * - 시장 레짐에 따른 조정
 * - 컨플루언스 신호 강도 반영
 */
@Service
class AdaptiveKellySizer(
    private val tradeRepository: TradeRepository
) {
    private val log = LoggerFactory.getLogger(AdaptiveKellySizer::class.java)

    companion object {
        private const val DEFAULT_WIN_RATE = 0.55
        private const val DEFAULT_RISK_REWARD = 2.0
        private const val MAX_POSITION_SIZE = 0.10  // 최대 10%
        private const val MIN_POSITION_SIZE = 0.01  // 최소 1%
        private const val SPAN = 20  // EWMA span
    }

    /**
     * 트레이딩 결과 기록 (학습용)
     */
    data class TradingResult(
        val isWin: Boolean,
        val pnlPercent: Double,
        val confidence: Double,     // 컨플루언스 점수 기반
        val marketRegime: String,   // LOW_VOL / HIGH_VOL / CRISIS
        val timestamp: Instant = Instant.now()
    )

    private val tradingHistory = mutableListOf<TradingResult>()

    /**
     * Kelly Criterion으로 포지션 크기 계산
     *
     * @param winRate 승률 (0~1)
     * @param riskReward 손익비
     * @param confidence 컨플루언스 신뢰도 (0~1)
     * @param marketRegime 시장 레짐
     * @return 자본 대비 포지션 비율
     */
    fun calculatePositionSize(
        winRate: Double = DEFAULT_WIN_RATE,
        riskReward: Double = DEFAULT_RISK_REWARD,
        confidence: Double = 0.5,
        marketRegime: String = "NORMAL"
    ): Double {
        // 1. 과거 데이터 기반 승률 조정
        val adjustedWinRate = calculateAdjustedWinRate(winRate, marketRegime)

        // 2. Kelly 공식: f* = (bp - q) / b
        val rawKelly = (adjustedWinRate * riskReward - (1 - adjustedWinRate)) / riskReward

        // 3. 컨플루언스 신뢰도 반영
        val confidenceAdjusted = rawKelly * (0.5 + confidence)

        // 4. Half Kelly (보수적)
        val halfKelly = confidenceAdjusted / 2

        // 5. 리스크 한도 적용
        return halfKelly.coerceIn(MIN_POSITION_SIZE, MAX_POSITION_SIZE).also {
            log.debug("Kelly Sizing: raw=$rawKelly, confidenceAdjusted=$confidenceAdjusted, halfKelly=$halfKelly, final=$it")
        }
    }

    /**
     * 자산별 포지션 크기 계산 (리스크 패리티 고려)
     *
     * @param assetVolatility 자산 연 변동성
     * @param portfolioVolatility 포트폴리오 목표 변동성
     * @param signalStrength 신호 강도 (0~1)
     * @param totalCapital 총 자본
     * @return 포지션 금액
     */
    fun calculatePositionSizeWithRiskParity(
        assetVolatility: Double,
        portfolioVolatility: Double = 0.2,
        signalStrength: Double = 0.5,
        totalCapital: Double = 1_000_000.0,
        riskBudget: Double = 0.02  // 자본의 2% 리스크
    ): Double {
        // 리스크 패리티 기반 할당
        val riskParityWeight = minOf(
            portfolioVolatility / assetVolatility,
            1.0
        )

        // 신호 강도에 따른 조정
        val adjustedWeight = riskParityWeight * signalStrength

        // 최종 포지션 크기
        val capitalAtRisk = totalCapital * riskBudget
        val positionSize = (capitalAtRisk / assetVolatility) * adjustedWeight

        return positionSize.coerceIn(totalCapital * MIN_POSITION_SIZE, totalCapital * MAX_POSITION_SIZE)
    }

    /**
     * 다자산 Kelly Criterion (Covariance 고려)
     *
     * @param expectedReturns 자산별 기대 수익률
     * @param covarianceMatrix 공분산 행렬
     * @return 자산별 Kelly 가중치
     */
    fun calculateMultiAssetKelly(
        expectedReturns: DoubleArray,
        covarianceMatrix: Array<DoubleArray>,
        riskFreeRate: Double = 0.02
    ): DoubleArray {
        val n = expectedReturns.size
        val excessReturns = expectedReturns.map { it - riskFreeRate }.toDoubleArray()

        // 역공분산행렬 계산
        val invCov = invertMatrix(covarianceMatrix)

        // f* = Σ^(-1) × μ
        val kellyWeights = multiplyMatrixVector(invCov, excessReturns)

        // 음수 가중치 처리 (no-short constraint)
        val normalized = kellyWeights.map { maxOf(0.0, it) }.toDoubleArray()
        val sumW = normalized.sum()

        return if (sumW > 0) {
            normalized.map { it / sumW }.toDoubleArray()
        } else {
            DoubleArray(n) { 1.0 / n }
        }
    }

    /**
     * 지수 가중 이동평균 승률 계산 (최신 데이터 더 중요)
     */
    fun calculateEWMAPWinRate(span: Int = SPAN): Double {
        if (tradingHistory.isEmpty()) return DEFAULT_WIN_RATE

        val alpha = 2.0 / (span + 1)
        var ewma = DEFAULT_WIN_RATE

        tradingHistory.forEach { result ->
            val win = if (result.isWin) 1.0 else 0.0
            ewma = alpha * win + (1 - alpha) * ewma
        }

        return ewma.coerceIn(0.0, 1.0)
    }

    /**
     * 지수 가중 이동평균 손익비 계산
     */
    fun calculateEWMARR(span: Int = SPAN): Double {
        if (tradingHistory.size < 5) return DEFAULT_RISK_REWARD

        val alpha = 2.0 / (span + 1)
        var ewmaWin = 0.0
        var ewmaLoss = 0.0
        var weight = 1.0

        tradingHistory.reversed().forEach { result ->
            if (result.pnlPercent > 0) {
                ewmaWin = alpha * result.pnlPercent + (1 - alpha) * ewmaWin
            } else {
                ewmaLoss = alpha * abs(result.pnlPercent) + (1 - alpha) * ewmaLoss
            }
            weight *= (1 - alpha)
        }

        return if (ewmaLoss > 0) ewmaWin / ewmaLoss else DEFAULT_RISK_REWARD
    }

    /**
     * 레짐별 승률 조정
     */
    private fun calculateAdjustedWinRate(
        baseWinRate: Double,
        marketRegime: String
    ): Double {
        val ewmaWinRate = calculateEWMAPWinRate()

        // 레짐별 승률 보정
        return when (marketRegime) {
            "LOW_VOL", "SIDEWAYS" -> ewmaWinRate * 1.1   // 횡보장: 승률 상향
            "HIGH_VOL" -> ewmaWinRate * 0.9              // 고변동: 승률 하향
            "CRISIS" -> ewmaWinRate * 0.5                // 위기: 승률 급락
            "BULL" -> ewmaWinRate * 1.05                 // 강세장: 약간 상향
            "BEAR" -> ewmaWinRate * 0.95                 // 약세장: 약간 하향
            else -> ewmaWinRate
        }.coerceIn(0.3, 0.9)  // 최소 30%, 최대 90%
    }

    /**
     * 트레이딩 결과 기록 (학습)
     */
    fun recordTrade(result: TradingResult) {
        tradingHistory.add(result)
        // 최근 100개만 유지
        if (tradingHistory.size > 100) {
            tradingHistory.removeAt(0)
        }

        log.debug("트레이딩 결과 기록: win=${result.isWin}, pnl=${result.pnlPercent}%, " +
                "regime=${result.marketRegime}, 총 기록=${tradingHistory.size}건")
    }

    /**
     * DB에서 과거 트레이딩 결과 로드
     */
    fun loadHistoryFromDB(lookbackDays: Int = 30) {
        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 최신 100개 거래 조회 후 필터링
        val allTrades: List<TradeEntity> = tradeRepository.findTop100ByOrderByCreatedAtDesc()
        val trades: List<TradeEntity> = allTrades.filter { trade -> trade.createdAt >= cutoffTime }

        trades.forEach { trade ->
            val pnlPercent = trade.pnlPercent ?: 0.0
            val isWin = pnlPercent > 0

            // 시장 레짐 추정 (regime 필드 사용)
            val regime = trade.regime ?: "NORMAL"

            tradingHistory.add(
                TradingResult(
                    isWin = isWin,
                    pnlPercent = pnlPercent,
                    confidence = trade.confidence,
                    marketRegime = regime,
                    timestamp = trade.createdAt
                )
            )
        }

        log.info("DB에서 트레이딩 히스토리 로드 완료: ${trades.size}건")
    }

    /**
     * 현재 Kelly 파라미터 상태 조회
     */
    fun getKellyStatus(): KellyStatus {
        val winRate = calculateEWMAPWinRate()
        val riskReward = calculateEWMARR()
        val kellyFraction = (winRate * riskReward - (1 - winRate)) / riskReward
        val halfKelly = kellyFraction / 2

        return KellyStatus(
            ewmaWinRate = winRate,
            ewmaRiskReward = riskReward,
            rawKelly = kellyFraction,
            halfKelly = halfKelly,
            recommendedPositionSize = halfKelly.coerceIn(MIN_POSITION_SIZE, MAX_POSITION_SIZE),
            historySize = tradingHistory.size
        )
    }

    /**
     * 히스토리 초기화
     */
    fun clearHistory() {
        tradingHistory.clear()
        log.info("트레이딩 히스토리 초기화")
    }

    // ========== Helper Functions ==========

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
}

/**
 * Kelly Criterion 상태
 */
data class KellyStatus(
    val ewmaWinRate: Double,         // 지수 가중 승률
    val ewmaRiskReward: Double,      // 지수 가중 손익비
    val rawKelly: Double,            // 원본 Kelly 비율
    val halfKelly: Double,           // Half Kelly 비율
    val recommendedPositionSize: Double,  // 권장 포지션 크기
    val historySize: Int             // 히스토리 데이터 수
)
