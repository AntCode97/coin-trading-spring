package com.ant.cointrading.risk

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Kelly Criterion 기반 포지션 사이징 (Jim Simons 스타일)
 *
 * Kelly Criterion:
 * f* = (bp - q) / b
 *
 * where:
 * - f* = 베팅 비율 (자본의 몇 %를 베팅할지)
 * - b = 배당률 (평균 수익 / 평균 손실)
 * - p = 승률
 * - q = 패률 (1 - p)
 *
 * Jim Simons의 원칙:
 * 1. 승률 50% 미만이면 베팅하지 마라 (f* < 0)
 * 2. Kelly의 절반만 베팅해라 (Half-Kelly는 실무 표준)
 * 3. 변동성이 크면 더 보수적으로
 */
@Component
class KellyPositionSizer {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Jim Simons: 최소 샘플 수 (통계적 유의성)
        const val MIN_TRADES_FOR_KELLY = 30

        // Half-Kelly (실무 표준, Simons도 사용)
        const val KELLY_FRACTION = 0.5

        // 최대/최소 포지션 비율
        const val MAX_POSITION_RATIO = 0.02  // 최대 2% (리스크 관리)
        const val MIN_POSITION_RATIO = 0.005 // 최소 0.5%
    }

    /**
     * Kelly Criterion으로 최적 포지션 크기 계산
     *
     * @param winRate 승률 (0.0 ~ 1.0)
     * @param avgWinProfit 평균 수익률 (%)
     * @param avgLossLoss 평균 손실률 (%, 절대값)
     * @param capital 총 자본
     * @return 포지션 크기 (KRW)
     */
    fun calculatePositionSize(
        winRate: Double,
        avgWinProfit: Double,
        avgLossLoss: Double,
        capital: Double
    ): Double {
        // Kelly Criterion 계산
        val b = avgWinProfit / avgLossLoss  // 배당률
        val p = winRate
        val q = 1.0 - winRate

        val kellyFraction = (b * p - q) / b

        // 승률 50% 미만이면 최소 포지션 (Simons: "패배를 인정하고 물러나라")
        if (winRate < 0.5) {
            log.warn("승률 ${String.format("%.1f", winRate * 100)}% < 50%, 최소 포지션 사용")
            return capital * MIN_POSITION_RATIO
        }

        // Half-Kelly (실무 표준)
        val halfKelly = kellyFraction * KELLY_FRACTION

        // 최대/최한 제한
        val clampedKelly = halfKelly.coerceIn(MIN_POSITION_RATIO, MAX_POSITION_RATIO)

        val positionSize = capital * clampedKelly

        log.info("""
            Kelly Criterion 포지션 계산:
            - 승률: ${String.format("%.1f", winRate * 100)}%
            - 배당률: ${String.format("%.2f", b)}:1
            - Kelly f*: ${String.format("%.2f", kellyFraction * 100)}%
            - Half-Kelly: ${String.format("%.2f", halfKelly * 100)}%
            - 포지션: ${String.format("%.0f", positionSize)}원 (${String.format("%.2f", clampedKelly * 100)}%)
        """.trimIndent())

        return positionSize
    }

    /**
     * R:R (Risk-Reward Ratio) 계산
     *
     * Cliff Asness: "좋은 전략은 최소 2:1 이상이어야 한다"
     */
    fun calculateRiskRewardRatio(
        avgWinProfit: Double,
        avgLossLoss: Double
    ): Double {
        return avgWinProfit / avgLossLoss
    }

    /**
     * 전략이 Kelly Criterion을 만족하는지 확인
     *
     * @param winRate 승률
     * @param totalTrades 총 거래 횟수
     * @return true if Kelly Criterion 만족
     */
    fun isValidKellyStrategy(
        winRate: Double,
        totalTrades: Int
    ): Boolean {
        if (totalTrades < MIN_TRADES_FOR_KELLY) {
            log.warn("거래 횟수 $totalTrades < $MIN_TRADES_FOR_KELLY (통계적 유의성 부족)")
            return false
        }

        if (winRate < 0.5) {
            log.warn("승률 ${String.format("%.1f", winRate * 100)}% < 50% (Kelly Criterion 불만족)")
            return false
        }

        return true
    }
}
