package com.ant.cointrading.indicator

import com.ant.cointrading.config.TradingConstants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * EMA(Exponential Moving Average) 계산기
 *
 * 지수이동평균은 최근 데이터에 더 높은 가중치를 부여하는
 * 이동평균 방식으로, 일반 이동평균보다 빠르게 가격 변화에 반응한다.
 *
 * 계산公式:
 * - Multiplier = 2 / (period + 1)
 * - 첫 번째 EMA = SMA (단순이동평균)
 * - 이후 EMA = (현재가 - 이전 EMA) × Multiplier + 이전 EMA
 */
object EmaCalculator {

    /**
     * EMA 계산 (Double 버전)
     *
     * @param data 데이터 리스트 (오래된 순)
     * @param period EMA 기간 (일반적으로 12, 26)
     * @return EMA 값 리스트
     */
    fun calculate(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val emaList = mutableListOf<Double>()

        // 첫 EMA는 SMA
        var ema = data.take(period).average()
        emaList.add(ema)

        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
            emaList.add(ema)
        }

        return emaList
    }

    /**
     * EMA 계산 (BigDecimal 버전 - 정밀도 필요 시)
     *
     * @param data 데이터 리스트 (오래된 순)
     * @param period EMA 기간
     * @return EMA 값 리스트
     */
    fun calculateBig(data: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (data.size < period) return emptyList()

        val emaList = mutableListOf<BigDecimal>()
        val multiplier = BigDecimal.valueOf(2.0 / (period + 1))

        // 첫 번째 EMA는 SMA로 시작
        val firstSma = data.take(period)
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)
        emaList.add(firstSma)

        // 이후 EMA 계산
        for (i in period until data.size) {
            val currentPrice = data[i]
            val previousEma = emaList.last()
            val currentEma = currentPrice.subtract(previousEma)
                .multiply(multiplier)
                .add(previousEma)
            emaList.add(currentEma)
        }

        return emaList
    }

    /**
     * 단일 EMA 값 계산 (마지막 값만 필요할 때)
     *
     * @param data 데이터 리스트 (오래된 순)
     * @param period EMA 기간
     * @return 마지막 EMA 값, 데이터 부족 시 null
     */
    fun calculateLast(data: List<Double>, period: Int): Double? {
        return calculate(data, period).lastOrNull()
    }

    /**
     * MACD 계산을 위한 EMA 계산
     *
     * @param data 데이터 리스트
     * @param fastPeriod 빠른 EMA 기간 (일반적으로 12)
     * @param slowPeriod 느린 EMA 기간 (일반적으로 26)
     * @return Pair<빠른 EMA 리스트, 느린 EMA 리스트>
     */
    fun calculateMacdEmas(
        data: List<Double>,
        fastPeriod: Int,
        slowPeriod: Int
    ): Pair<List<Double>, List<Double>> {
        val fastEma = calculate(data, fastPeriod)
        val slowEma = calculate(data, slowPeriod)
        return fastEma to slowEma
    }

    // 일반적으로 사용하는 EMA 기간 상수 (표준값은 TradingConstants 사용)
    object Periods {
        const val MACD_FAST = TradingConstants.MACD_FAST_STANDARD
        const val MACD_SLOW = TradingConstants.MACD_SLOW_STANDARD
        const val MACD_SIGNAL = TradingConstants.MACD_SIGNAL_STANDARD
    }
}
