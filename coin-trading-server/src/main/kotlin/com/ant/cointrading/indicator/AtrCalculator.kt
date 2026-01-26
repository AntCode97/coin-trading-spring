package com.ant.cointrading.indicator

import com.ant.cointrading.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ATR (Average True Range) 계산기
 *
 * 변동성 측정, 동적 손절/포지션 사이징에 사용
 */
class AtrCalculator(private val period: Int = 14) {

    /**
     * ATR 계산
     *
     * @param candles 캔들 데이터 (최소 period+1개 필요)
     * @return ATR 값 리스트 (첫 period-1개는 null)
     */
    fun calculate(candles: List<Candle>): List<Double?> {
        if (candles.size <= period) return listOf()

        val trueRanges = mutableListOf<Double>()
        val atrList = mutableListOf<Double?>()

        // 첫 TR은 SMA로 초기화
        for (i in 1 until minOf(period + 1, candles.size)) {
            trueRanges.add(calculateTr(candles[i - 1], candles[i]))
        }

        val initialAtr = trueRanges.average()
        atrList.add(initialAtr)

        // 이후는 Wilder's smoothing
        for (i in (period + 1) until candles.size) {
            val tr = calculateTr(candles[i - 1], candles[i])
            val lastAtr = atrList.lastOrNull() ?: initialAtr
            val atr = (lastAtr * (period - 1) + tr) / period
            atrList.add(atr)
        }

        return atrList
    }

    /**
     * 최신 ATR 값만 반환
     */
    fun getLatestAtr(candles: List<Candle>): Double? {
        return calculate(candles).lastOrNull()
    }

    /**
     * True Range 계산
     *
     * TR = max(H-L, |H-PC|, |L-PC|)
     * H: 고가, L: 저가, PC: 전일 종가
     */
    private fun calculateTr(prevCandle: Candle, currentCandle: Candle): Double {
        val high = currentCandle.high.toDouble()
        val low = currentCandle.low.toDouble()
        val prevClose = prevCandle.close.toDouble()

        val hl = high - low
        val hpc = abs(high - prevClose)
        val lpc = abs(low - prevClose)

        return max(hl, max(hpc, lpc))
    }

    companion object {
        /**
         * 표준 기간별 권장값
         */
        const val STANDARD_PERIOD = 14
        const val SHORT_PERIOD = 7
        const val LONG_PERIOD = 21
    }
}
