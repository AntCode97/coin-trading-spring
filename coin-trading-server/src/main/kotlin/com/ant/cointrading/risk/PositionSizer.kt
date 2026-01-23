package com.ant.cointrading.risk

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kelly Criterion 기반 포지션 사이징
 *
 * 퀀트 연구 기반:
 * - Kelly 공식: f* = (bp - q) / b
 *   f* = 최적 베팅 비율
 *   b  = 손익비 (Risk-Reward Ratio)
 *   p  = 승률
 *   q  = 1 - p
 *
 * - Half Kelly 권장 (변동성 감소)
 * - 단일 거래 리스크: 자본의 1-2%
 * - 일일 최대 손실: 자본의 5%
 */
@Component
class PositionSizer {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Kelly Criterion 파라미터
        const val DEFAULT_WIN_RATE = 0.50      // 기본 승률 50%
        const val DEFAULT_RISK_REWARD = 2.0    // 기본 손익비 2:1

        // 리스크 관리 상수
        const val MAX_POSITION_PERCENT = 5.0   // 최대 포지션 5% (Full Kelly의 1/4 수준)
        const val DEFAULT_POSITION_PERCENT = 1.0  // 기본 포지션 1%
        const val MIN_POSITION_PERCENT = 0.5   // 최소 포지션 0.5%

        // Kelly 조정
        const val KELLY_FRACTION = 0.5         // Half Kelly 사용 (안전성)
        const val MIN_WIN_RATE_FOR_KELLY = 0.40   // Kelly 적용 최소 승률
    }

    /**
     * Kelly Criterion 기반 포지션 크기 계산
     *
     * @param winRate 승률 (0.0 ~ 1.0)
     * @param riskReward 손익비 (예: 2.0 = 2:1)
     * @param capital 총 자본
     * @return 포지션 금액 (KRW)
     */
    fun calculatePositionSize(
        winRate: Double,
        riskReward: Double,
        capital: Double
    ): Double {
        // Kelly Criterion 계산
        val kellyPercent = calculateKellyPercent(winRate, riskReward)

        // 자본에 Kelly 비율을 적용
        val positionAmount = capital * (kellyPercent / 100)

        log.info("Kelly 포지션: 승률=${String.format("%.1f%%", winRate * 100)}, R:R=$riskReward, Kelly=${String.format("%.2f%%", kellyPercent)}, 금액=${String.format("%.0f", positionAmount)}원")

        return positionAmount
    }

    /**
     * Kelly 비율 계산 (%)
     *
     * Half Kelly 사용: kelly / 2
     */
    fun calculateKellyPercent(
        winRate: Double,
        riskReward: Double
    ): Double {
        // 승률이 낮으면 Kelly 적용 안 하고 기본값 사용
        if (winRate < MIN_WIN_RATE_FOR_KELLY) {
            return DEFAULT_POSITION_PERCENT
        }

        val p = winRate.coerceIn(0.0, 1.0)
        val q = 1 - p
        val b = riskReward.coerceAtLeast(1.0)

        // Kelly Criterion: f* = (bp - q) / b
        val kelly = (b * p - q) / b

        // Half Kelly (안전성)
        val halfKelly = kelly / 2

        // 퍼센트로 변환하고 안전장치 적용
        val kellyPercent = (halfKelly * 100).coerceIn(MIN_POSITION_PERCENT, MAX_POSITION_PERCENT)

        return kellyPercent
    }

    /**
     * 신뢰도별 Kelly 파라미터 추천
     *
     * quant-trading 스킬 기반:
     * - 90%+ 승률: R:R 1.5:1
     * - 70-89%: R:R 2:1
     * - 50-69%: R:R 3:1
     */
    fun getRecommendedParams(confidence: Double): KellyParams {
        return when {
            confidence >= 90.0 -> KellyParams(
                winRate = 0.85,   // 높은 신뢰도 = 높은 승률 가정
                riskReward = 1.5,
                reason = "높은 신뢰도: 공격적 포지션 (R:R 1.5:1)"
            )
            confidence >= 70.0 -> KellyParams(
                winRate = 0.70,
                riskReward = 2.0,
                reason = "중간 신뢰도: 균형적 포지션 (R:R 2:1)"
            )
            confidence >= 50.0 -> KellyParams(
                winRate = 0.55,
                riskReward = 3.0,
                reason = "낮은 신뢰도: 보수적 포지션 (R:R 3:1)"
            )
            else -> KellyParams(
                winRate = DEFAULT_WIN_RATE,
                riskReward = DEFAULT_RISK_REWARD,
                reason = "매우 낮은 신뢰도: 기본값 사용"
            )
        }
    }

    /**
     * 수량 계산 (금액 기반)
     *
     * @param amount 투자 금액 (KRW)
     * @param price 현재가 (KRW)
     * @return 수량
     */
    fun calculateQuantity(amount: Double, price: Double): Double {
        if (price <= 0) return 0.0
        return amount / price
    }

    /**
     * 리스크 금액 계산 (손절 시 예상 손실)
     *
     * @param positionAmount 포지션 금액
     * @param stopLossPercent 손절 비율 (%)
     * @return 리스크 금액
     */
    fun calculateRiskAmount(positionAmount: Double, stopLossPercent: Double): Double {
        return positionAmount * (stopLossPercent / 100)
    }

    /**
     * 리스크가 자본의 1-2% 이내인지 확인
     */
    fun isRiskAcceptable(
        positionAmount: Double,
        stopLossPercent: Double,
        capital: Double,
        maxRiskPercent: Double = 2.0
    ): Boolean {
        val riskAmount = calculateRiskAmount(positionAmount, stopLossPercent)
        val riskPercent = (riskAmount / capital) * 100

        val isAcceptable = riskPercent <= maxRiskPercent

        if (!isAcceptable) {
            log.warn("리스크 초과: ${String.format("%.2f%%", riskPercent)} > ${String.format("%.2f%%", maxRiskPercent)}")
        }

        return isAcceptable
    }

    /**
     * 포지션 크기 조정 (리스크 제한 내로)
     */
    fun adjustForRiskLimit(
        positionAmount: Double,
        stopLossPercent: Double,
        capital: Double,
        maxRiskPercent: Double = 2.0
    ): Double {
        val riskAmount = calculateRiskAmount(positionAmount, stopLossPercent)
        val riskPercent = (riskAmount / capital) * 100

        return if (riskPercent > maxRiskPercent) {
            // 리스크 한도에 맞춰 포지션 축소
            val adjustedAmount = capital * (maxRiskPercent / 100) / (stopLossPercent / 100)
            log.info("포지션 축소: ${String.format("%.0f", positionAmount)} → ${String.format("%.0f", adjustedAmount)}")
            adjustedAmount
        } else {
            positionAmount
        }
    }
}

/**
 * Kelly 파라미터
 */
data class KellyParams(
    val winRate: Double,
    val riskReward: Double,
    val reason: String
)

/**
 * 포지션 사이징 결과
 */
data class PositionSizeResult(
    val amount: Double,           // 투자 금액 (KRW)
    val quantity: Double,         // 수량
    val kellyPercent: Double,     // Kelly 비율 (%)
    val riskPercent: Double,      // 리스크 비율 (%)
    val riskAmount: Double,       // 리스크 금액 (KRW)
    val winRate: Double,          // 적용 승률
    val riskReward: Double,       // 적용 손익비
    val reason: String            // 사이징 사유
)
