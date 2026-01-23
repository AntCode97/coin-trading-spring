package com.ant.cointrading.regime

import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * 시장 레짐 감지기
 *
 * ADX(Average Directional Index)와 ATR(Average True Range)을 사용하여
 * 현재 시장 상태를 판단.
 *
 * - ADX > 25: 추세 존재
 * - ADX < 25: 횡보
 * - ATR 상위 20%: 고변동성
 */
@Component
class RegimeDetector {

    companion object {
        const val ADX_PERIOD = 14
        const val ATR_PERIOD = 14
        const val ADX_TREND_THRESHOLD = 25.0
        const val HIGH_VOLATILITY_PERCENTILE = 0.8
    }

    /**
     * 시장 레짐 분석
     */
    fun detect(candles: List<Candle>): RegimeAnalysis {
        if (candles.size < ADX_PERIOD + 1) {
            return RegimeAnalysis(
                regime = MarketRegime.SIDEWAYS,
                confidence = 0.0,
                adx = 0.0,
                atr = 0.0,
                atrPercent = 0.0,
                trendDirection = 0
            )
        }

        val adxResult = calculateADX(candles)
        val atr = calculateATR(candles)
        val currentPrice = candles.last().close.toDouble()
        val atrPercent = if (currentPrice > 0) (atr / currentPrice) * 100 else 0.0

        // 추세 방향 판단 (DI+ vs DI-)
        val trendDirection = adxResult.trendDirection

        // 고변동성 체크 (ATR이 평균의 1.5배 이상)
        val isHighVolatility = atrPercent > 3.0  // 3% 이상이면 고변동성

        val regime = when {
            isHighVolatility -> MarketRegime.HIGH_VOLATILITY
            adxResult.adx >= ADX_TREND_THRESHOLD && trendDirection > 0 -> MarketRegime.BULL_TREND
            adxResult.adx >= ADX_TREND_THRESHOLD && trendDirection < 0 -> MarketRegime.BEAR_TREND
            else -> MarketRegime.SIDEWAYS
        }

        // 신뢰도 계산
        val confidence = when (regime) {
            MarketRegime.HIGH_VOLATILITY -> 70.0 + (atrPercent * 5).coerceAtMost(25.0)
            MarketRegime.BULL_TREND, MarketRegime.BEAR_TREND -> {
                50.0 + ((adxResult.adx - ADX_TREND_THRESHOLD) * 2).coerceAtMost(45.0)
            }
            MarketRegime.SIDEWAYS -> {
                50.0 + ((ADX_TREND_THRESHOLD - adxResult.adx) * 2).coerceAtMost(45.0)
            }
        }

        return RegimeAnalysis(
            regime = regime,
            confidence = confidence.coerceIn(30.0, 95.0),
            adx = adxResult.adx,
            atr = atr,
            atrPercent = atrPercent,
            trendDirection = trendDirection,
            timestamp = Instant.now()
        )
    }

    data class ADXResult(
        val adx: Double,
        val plusDI: Double,
        val minusDI: Double,
        val trendDirection: Int  // 1: 상승, -1: 하락, 0: 중립
    )

    /**
     * ADX (Average Directional Index) 계산
     */
    private fun calculateADX(candles: List<Candle>): ADXResult {
        if (candles.size < ADX_PERIOD + 1) {
            return ADXResult(0.0, 0.0, 0.0, 0)
        }

        val trueRanges = mutableListOf<Double>()
        val plusDMs = mutableListOf<Double>()
        val minusDMs = mutableListOf<Double>()

        for (i in 1 until candles.size) {
            val current = candles[i]
            val previous = candles[i - 1]

            val high = current.high.toDouble()
            val low = current.low.toDouble()
            val prevHigh = previous.high.toDouble()
            val prevLow = previous.low.toDouble()
            val prevClose = previous.close.toDouble()

            // True Range
            val tr = maxOf(
                high - low,
                abs(high - prevClose),
                abs(low - prevClose)
            )
            trueRanges.add(tr)

            // Directional Movement
            val plusDM = if (high - prevHigh > prevLow - low) max(high - prevHigh, 0.0) else 0.0
            val minusDM = if (prevLow - low > high - prevHigh) max(prevLow - low, 0.0) else 0.0

            plusDMs.add(plusDM)
            minusDMs.add(minusDM)
        }

        // Smoothed values (Wilder's smoothing)
        val smoothedTR = wilderSmooth(trueRanges, ADX_PERIOD)
        val smoothedPlusDM = wilderSmooth(plusDMs, ADX_PERIOD)
        val smoothedMinusDM = wilderSmooth(minusDMs, ADX_PERIOD)

        // DI calculations
        val plusDI = if (smoothedTR > 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
        val minusDI = if (smoothedTR > 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0

        // DX calculation
        val diSum = plusDI + minusDI
        val dx = if (diSum > 0) (abs(plusDI - minusDI) / diSum) * 100 else 0.0

        // ADX (smoothed DX) - 간단히 마지막 값 사용
        val adx = dx

        // Trend direction
        val trendDirection = when {
            plusDI > minusDI + 5 -> 1   // 상승
            minusDI > plusDI + 5 -> -1  // 하락
            else -> 0                    // 중립
        }

        return ADXResult(adx, plusDI, minusDI, trendDirection)
    }

    /**
     * ATR (Average True Range) 계산
     */
    private fun calculateATR(candles: List<Candle>): Double {
        if (candles.size < ATR_PERIOD + 1) return 0.0

        val trueRanges = mutableListOf<Double>()

        for (i in 1 until candles.size) {
            val current = candles[i]
            val previous = candles[i - 1]

            val high = current.high.toDouble()
            val low = current.low.toDouble()
            val prevClose = previous.close.toDouble()

            val tr = maxOf(
                high - low,
                abs(high - prevClose),
                abs(low - prevClose)
            )
            trueRanges.add(tr)
        }

        return wilderSmooth(trueRanges, ATR_PERIOD)
    }

    /**
     * Wilder's Smoothing Method
     */
    private fun wilderSmooth(values: List<Double>, period: Int): Double {
        if (values.size < period) return values.average()

        // 첫 번째 값은 단순 평균
        var smoothed = values.take(period).average()

        // 이후는 Wilder's smoothing
        for (i in period until values.size) {
            smoothed = ((smoothed * (period - 1)) + values[i]) / period
        }

        return smoothed
    }

    /**
     * Bithumb CandleResponse를 사용한 시장 레짐 분석
     *
     * @param candleResponses Bithumb API에서 반환한 캔들 데이터 목록
     * @return 시장 레짐 분석 결과
     */
    fun detectFromBithumb(candleResponses: List<CandleResponse>): RegimeAnalysis {
        val candles = candleResponses.map { response ->
            Candle(
                timestamp = Instant.parse(response.candleDateTimeUtc),
                open = response.openingPrice,
                high = response.highPrice,
                low = response.lowPrice,
                close = response.tradePrice,
                volume = response.candleAccTradeVolume
            )
        }
        return detect(candles)
    }
}
