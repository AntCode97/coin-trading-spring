package com.ant.cointrading.risk

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.max
import kotlin.math.min

/**
 * 동적 손익비 계산 결과
 */
data class DynamicRiskRewardResult(
    val confidence: Double,         // 신뢰도 (0~100)
    val winRate: Double,            // 적용 승률
    val riskReward: Double,         // 손익비
    val stopLossPercent: Double,    // 손절 비율 (%)
    val takeProfitPercent: Double,  // 익절 비율 (%)
    val reason: String               // 설명
)

/**
 * 동적 손익비 계산기
 *
 * quant-trading 스킬 기반 손익비 가이드라인:
 * - 90%+ 신뢰도: R:R 1.5:1, 손절 -2%, 익절 +3%
 * - 70-89%: R:R 2:1, 손절 -2%, 익절 +4%
 * - 50-69%: R:R 3:1, 손절 -2%, 익절 +6%
 * - 50% 미만: R:R 4:1, 손절 -2%, 익절 +8%
 *
 * Kelly Criterion과 연동하여 포지션 크기 조정.
 */
@Component
class DynamicRiskRewardCalculator {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 기본 손절 비율 (고정)
        const val DEFAULT_STOP_LOSS_PERCENT = 2.0

        // 신뢰도별 손익비
        const val HIGH_CONFIDENCE_MIN = 90.0
        const val MEDIUM_CONFIDENCE_MIN = 70.0
        const val LOW_CONFIDENCE_MIN = 50.0

        // 신뢰도별 손익비
        const val HIGH_CONFIDENCE_RR = 1.5
        const val MEDIUM_CONFIDENCE_RR = 2.0
        const val LOW_CONFIDENCE_RR = 3.0
        const val VERY_LOW_CONFIDENCE_RR = 4.0

        // 승률 가정
        const val HIGH_CONFIDENCE_WIN_RATE = 0.85
        const val MEDIUM_CONFIDENCE_WIN_RATE = 0.70
        const val LOW_CONFIDENCE_WIN_RATE = 0.55
        const val VERY_LOW_CONFIDENCE_WIN_RATE = 0.45
    }

    /**
     * 신뢰도에 따른 동적 손익비 계산
     */
    fun calculate(confidence: Double, customStopLoss: Double? = null): DynamicRiskRewardResult {
        val stopLoss = customStopLoss ?: DEFAULT_STOP_LOSS_PERCENT

        val winRate: Double
        val riskReward: Double
        val takeProfit: Double
        val reason: String

        when {
            confidence >= HIGH_CONFIDENCE_MIN -> {
                // 90%+ 신뢰도: 공격적 포지션 (R:R 1.5:1)
                val tp = stopLoss * HIGH_CONFIDENCE_RR
                winRate = HIGH_CONFIDENCE_WIN_RATE
                riskReward = HIGH_CONFIDENCE_RR
                takeProfit = tp
                reason = "높은 신뢰도: 공격적 익절 (R:R ${HIGH_CONFIDENCE_RR}:1, 익절 +${tp}%)"
            }
            confidence >= MEDIUM_CONFIDENCE_MIN -> {
                // 70-89%: 균형적 (R:R 2:1)
                val tp = stopLoss * MEDIUM_CONFIDENCE_RR
                winRate = MEDIUM_CONFIDENCE_WIN_RATE
                riskReward = MEDIUM_CONFIDENCE_RR
                takeProfit = tp
                reason = "중간 신뢰도: 균형적 익절 (R:R ${MEDIUM_CONFIDENCE_RR}:1, 익절 +${tp}%)"
            }
            confidence >= LOW_CONFIDENCE_MIN -> {
                // 50-69%: 보수적 (R:R 3:1)
                val tp = stopLoss * LOW_CONFIDENCE_RR
                winRate = LOW_CONFIDENCE_WIN_RATE
                riskReward = LOW_CONFIDENCE_RR
                takeProfit = tp
                reason = "낮은 신뢰도: 보수적 익절 (R:R ${LOW_CONFIDENCE_RR}:1, 익절 +${tp}%)"
            }
            else -> {
                // 50% 미만: 매우 보수적 (R:R 4:1)
                val tp = stopLoss * VERY_LOW_CONFIDENCE_RR
                winRate = VERY_LOW_CONFIDENCE_WIN_RATE
                riskReward = VERY_LOW_CONFIDENCE_RR
                takeProfit = tp
                reason = "매우 낮은 신뢰도: 최소 익절 (R:R ${VERY_LOW_CONFIDENCE_RR}:1, 익절 +${tp}%)"
            }
        }

        return DynamicRiskRewardResult(
            confidence = confidence,
            winRate = winRate,
            riskReward = riskReward,
            stopLossPercent = stopLoss,
            takeProfitPercent = takeProfit,
            reason = reason
        )
    }

    /**
     * 익절가 계산 (진입가 기준)
     */
    fun calculateTakeProfitPrice(
        entryPrice: Double,
        stopLossPercent: Double,
        riskReward: Double,
        isBuy: Boolean = true
    ): Double {
        val stopLossAmount = entryPrice * (stopLossPercent / 100)
        val takeProfitAmount = stopLossAmount * riskReward

        return if (isBuy) {
            entryPrice + takeProfitAmount
        } else {
            entryPrice - takeProfitAmount
        }
    }

    /**
     * 손절가 계산 (진입가 기준)
     */
    fun calculateStopLossPrice(
        entryPrice: Double,
        stopLossPercent: Double,
        isBuy: Boolean = true
    ): Double {
        return if (isBuy) {
            entryPrice * (1 - stopLossPercent / 100)
        } else {
            entryPrice * (1 + stopLossPercent / 100)
        }
    }

    /**
     * ATR 기반 동적 손절 비율 계산
     *
     * StopLossCalculator와 연동하여 ATR 변동성에 따라 손절 거리 조정.
     * 단, 최소/최대 손절 비율은 유지.
     */
    fun calculateAtrBasedStopLoss(
        atrPercent: Double,           // ATR 비율 (%)
        regime: com.ant.cointrading.model.MarketRegime,
        volatilityLevel: Double       // 변동성 레벨 (0~100)
    ): Double {
        // 기본 손절 비율
        val baseStopLoss = DEFAULT_STOP_LOSS_PERCENT

        // 레짐별 ATR 배수 (StopLossCalculator 기준)
        val atrMultiplier = when (regime) {
            com.ant.cointrading.model.MarketRegime.SIDEWAYS -> 1.5
            com.ant.cointrading.model.MarketRegime.BULL_TREND,
            com.ant.cointrading.model.MarketRegime.BEAR_TREND -> 3.0
            com.ant.cointrading.model.MarketRegime.HIGH_VOLATILITY -> 4.0
        }

        // ATR 기반 손절 거리
        val atrBasedStopLoss = atrPercent * atrMultiplier

        // 변동성 레벨에 따른 조정
        val volatilityAdjusted = when {
            volatilityLevel > 80 -> atrBasedStopLoss * 1.3   // 고변동: 30% 추가
            volatilityLevel > 60 -> atrBasedStopLoss * 1.15  // 중고변동: 15% 추가
            volatilityLevel > 40 -> atrBasedStopLoss          // 보통
            else -> atrBasedStopLoss * 0.8                   // 저변동: 20% 감소
        }

        // 최소/최대 제한
        return volatilityAdjusted.coerceIn(
            StopLossCalculator.MIN_STOP_LOSS_PERCENT,
            StopLossCalculator.MAX_STOP_LOSS_PERCENT
        )
    }

    /**
     * 분할 청산 비율 계산
     *
     * 익절 목표에 도달 시 분할 매도하여 수익 확정.
     */
    fun calculatePartialExitLevels(
        entryPrice: Double,
        takeProfitPrice: Double,
        isBuy: Boolean = true
    ): List<PartialExitLevel> {
        val totalProfit = if (isBuy) {
            takeProfitPrice - entryPrice
        } else {
            entryPrice - takeProfitPrice
        }

        return listOf(
            // 1차 익절 (30%): 목표의 50% 도달 시
            PartialExitLevel(
                level = 1,
                percentage = 30,
                price = if (isBuy) entryPrice + totalProfit * 0.5 else entryPrice - totalProfit * 0.5,
                reason = "1차 익절: 30% 매도, 손익비 0.75:1 확정"
            ),
            // 2차 익절 (30%): 목표의 75% 도달 시
            PartialExitLevel(
                level = 2,
                percentage = 30,
                price = if (isBuy) entryPrice + totalProfit * 0.75 else entryPrice - totalProfit * 0.75,
                reason = "2차 익절: 30% 매도, 손익비 1.5:1 확정"
            ),
            // 3차 익절 (40%): 목표 도달 시
            PartialExitLevel(
                level = 3,
                percentage = 40,
                price = takeProfitPrice,
                reason = "3차 익절: 40% 매도, 손익비 2:1 확정"
            )
        )
    }

    /**
     * Kelly Criterion과 연동한 포지션 크기 계산
     *
     * 신뢰도와 손익비를 고려하여 최적 포지션 크기 계산.
     */
    fun calculateOptimalPositionSize(
        capital: Double,
        confidence: Double,
        stopLossPercent: Double,
        positionSizer: PositionSizer
    ): Double {
        // 동적 손익비 계산
        val rrResult = calculate(confidence)

        // Kelly Criterion 기반 포지션 크기
        val positionAmount = positionSizer.calculatePositionSize(
            rrResult.winRate,
            rrResult.riskReward,
            capital
        )

        // 리스크 한도 체크 및 조정
        return positionSizer.adjustForRiskLimit(
            positionAmount,
            stopLossPercent,
            capital,
            maxRiskPercent = 2.0
        )
    }

    /**
     * 손익비별 최소 승률 계산
     *
     * 손익비가 주어졌을 때 수익을 내기 위한 최소 승률.
     * Kelly Criterion이 0이 되는 지점.
     *
     * f* = (bp - q) / b > 0
     * bp - q > 0
     * bp - (1 - p) > 0
     * bp - 1 + p > 0
     * p(b + 1) > 1
     * p > 1 / (b + 1)
     */
    fun calculateMinWinRateForProfit(riskReward: Double): Double {
        return 1.0 / (riskReward + 1)
    }

    /**
     * 현재 전략의 기대 수익률 계산
     *
     * 기대 수익률 = (승률 × 수익) - (패률 × 손실)
     */
    fun calculateExpectedReturn(
        winRate: Double,
        riskReward: Double
    ): Double {
        val loseRate = 1 - winRate
        return (winRate * riskReward) - (loseRate * 1.0)  // 손실은 1로 가정
    }
}

/**
 * 분할 청산 레벨
 */
data class PartialExitLevel(
    val level: Int,                // 차수
    val percentage: Int,            // 청산 비율 (%)
    val price: Double,              // 청산 가격
    val reason: String              // 설명
)
