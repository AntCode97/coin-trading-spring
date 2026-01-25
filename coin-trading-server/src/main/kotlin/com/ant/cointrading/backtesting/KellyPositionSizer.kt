package com.ant.cointrading.backtesting

import kotlin.math.max

/**
 * Kelly Criterion 기반 포지션 사이징
 *
 * Kelly 공식: f* = (bp - q) / b
 * - f* = 최적 베팅 비율
 * - b = 손익비 (Risk-Reward Ratio)
 * - p = 승률
 * - q = 1 - p
 *
 * Half Kelly 사용 (안정성): kelly / 2
 */
object KellyPositionSizer {

    /**
     * Kelly Criterion 기반 포지션 크기 계산
     *
     * @param winRate 승률 (0.0 ~ 1.0, 예: 0.7 = 70%)
     * @param riskReward 손익비 (예: 2.0 = 2:1)
     * @param capital 총 자본
     * @param maxRiskPercent 최대 리스크 비율 (기본 2%)
     * @return 투자 금액
     */
    fun calculatePositionSize(
        winRate: Double,
        riskReward: Double,
        capital: Double,
        maxRiskPercent: Double = 2.0
    ): Double {
        // Kelly 공식
        val kelly = (winRate * riskReward - (1 - winRate)) / riskReward

        // Half Kelly (안정성)
        val halfKelly = kelly / 2

        // 최대 5%로 제한 (과도한 레버리지 방지)
        val maxKelly = 0.05

        val adjustedKelly = halfKelly.coerceIn(0.0, maxKelly)

        // 자본의 최대 maxRiskPercent%로 제한
        val maxPosition = capital * (maxRiskPercent / 100.0)

        return (capital * adjustedKelly).coerceAtMost(maxPosition)
    }

    /**
     * 신뢰도별 Kelly 파라미터 추천 (quant-trading 스킬 기준)
     *
     * @param confidence 컨플루언스 신뢰도 (0~100)
     * @return Pair(승률, 손익비)
     */
    fun getKellyParamsByConfidence(confidence: Double): Pair<Double, Double> {
        return when {
            confidence >= 90 -> Pair(0.80, 1.5)  // 90%+ 승률, 1.5:1 (공격적)
            confidence >= 70 -> Pair(0.70, 2.0)  // 70-89% 승률, 2:1 (균형적)
            confidence >= 50 -> Pair(0.60, 3.0)  // 50-69% 승률, 3:1 (보수적)
            else -> Pair(0.50, 4.0)              // 50% 미만, 4:1 (매우 보수적)
        }
    }

    /**
     * 신뢰도 기반 포지션 크기 계산 (간편 버전)
     *
     * @param confidence 컨플루언스 신뢰도 (0~100)
     * @param capital 총 자본
     * @return 투자 금액
     */
    fun calculateByConfidence(
        confidence: Double,
        capital: Double,
        maxRiskPercent: Double = 2.0
    ): Double {
        val (winRate, riskReward) = getKellyParamsByConfidence(confidence)
        return calculatePositionSize(winRate, riskReward, capital, maxRiskPercent)
    }

    /**
     * 리스크 허용 여부 확인
     *
     * @param positionAmount 포지션 금액
     * @param stopLossPercent 손절 비율 (%)
     * @param capital 총 자본
     * @param maxRiskPercent 최대 허용 리스크 (기본 2%)
     * @return 허용 여부
     */
    fun isRiskAcceptable(
        positionAmount: Double,
        stopLossPercent: Double,
        capital: Double,
        maxRiskPercent: Double = 2.0
    ): Boolean {
        val potentialLoss = positionAmount * (kotlin.math.abs(stopLossPercent) / 100.0)
        val riskPercent = (potentialLoss / capital) * 100
        return riskPercent <= maxRiskPercent
    }
}
