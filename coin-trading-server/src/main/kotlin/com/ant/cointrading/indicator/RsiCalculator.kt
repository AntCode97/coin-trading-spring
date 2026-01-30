package com.ant.cointrading.indicator

import kotlin.math.abs

/**
 * RSI 계산기 (Wilder's Smoothing)
 *
 * RSI(Relative Strength Index)는 일정 기간 동안의
 * 상승/하락 폭을 이용하여 과매수/과매도를 판단하는 지표.
 *
 * Wilder's Smoothing 방식 사용 (이동평균과 유사한 가중 평균).
 */
object RsiCalculator {

    /**
     * RSI 계산
     *
     * @param closes 종가 리스트 (오래된 순)
     * @param period RSI 기간 (일반적으로 14)
     * @return RSI 값 (0 ~ 100), 데이터 부족 시 50.0 반환
     */
    fun calculate(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0

        val changes = closes.zipWithNext { a, b -> b - a }

        var avgGain = changes.take(period).filter { it > 0 }.sum() / period
        var avgLoss = abs(changes.take(period).filter { it < 0 }.sum()) / period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0

        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    /**
     * RSI 계산 (기본 14기간)
     */
    fun calculate(closes: List<Double>): Double = calculate(closes, 14)

    /**
     * 각 시점별 RSI 계산 (다이버전스 탐지용)
     *
     * @param closes 종가 리스트 (오래된 순)
     * @param period RSI 기간 (일반적으로 14)
     * @return 각 시점별 RSI 값 리스트 (데이터 부족 시점 제외)
     */
    fun calculateAll(closes: List<Double>, period: Int = 14): List<Double> {
        if (closes.size < period + 1) return emptyList()

        val changes = closes.zipWithNext { a, b -> b - a }
        val rsiValues = mutableListOf<Double>()

        var avgGain = changes.take(period).filter { it > 0 }.sum() / period
        var avgLoss = abs(changes.take(period).filter { it < 0 }.sum()) / period

        // 초기 RSI (첫 번째 기간 완료 시점)
        if (avgLoss == 0.0) {
            rsiValues.add(100.0)
        } else {
            val rs = avgGain / avgLoss
            rsiValues.add(100 - (100 / (1 + rs)))
        }

        // 이후 RSI (Wilder's Smoothing)
        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period

            if (avgLoss == 0.0) {
                rsiValues.add(100.0)
            } else {
                val rs = avgGain / avgLoss
                rsiValues.add(100 - (100 / (1 + rs)))
            }
        }

        return rsiValues
    }

    /**
     * 과매수/과매도 상태 판단
     *
     * @param rsi RSI 값
     * @param oversold 과매도 기준 (기본 30)
     * @param overbought 과매수 기준 (기본 70)
     * @return 상태: OVERSOLD, OVERBOUGHT, NEUTRAL
     */
    fun getStatus(rsi: Double, oversold: Double = 30.0, overbought: Double = 70.0): RsiStatus {
        return when {
            rsi <= oversold -> RsiStatus.OVERSOLD
            rsi >= overbought -> RsiStatus.OVERBOUGHT
            else -> RsiStatus.NEUTRAL
        }
    }
}

/**
 * RSI 상태
 */
enum class RsiStatus {
    OVERSOLD,   // 과매도 (매수 기회)
    OVERBOUGHT, // 과매수 (매도 기회)
    NEUTRAL     // 중립
}
