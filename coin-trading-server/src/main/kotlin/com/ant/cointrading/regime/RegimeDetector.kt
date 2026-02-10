package com.ant.cointrading.regime

import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        const val ADX_SIDEWAYS_THRESHOLD = 20.0
        const val ADX_TREND_THRESHOLD = 25.0
        const val TREND_EMA_FAST_PERIOD = 12
        const val TREND_EMA_SLOW_PERIOD = 26
        const val PRICE_MOMENTUM_LOOKBACK = 12
        const val HIGH_VOLATILITY_ABSOLUTE_ATR_PERCENT = 2.5
        const val HIGH_VOLATILITY_ZSCORE_THRESHOLD = 1.0
        const val HIGH_VOLATILITY_PERCENTILE = 0.8
        const val VOLATILITY_LOOKBACK = 30
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
        val volatilityContext = analyzeVolatilityContext(candles, atrPercent)
        val closes = candles.map { it.close.toDouble() }
        val emaFast = calculateEmaLast(closes, TREND_EMA_FAST_PERIOD) ?: currentPrice
        val emaSlow = calculateEmaLast(closes, TREND_EMA_SLOW_PERIOD) ?: currentPrice
        val emaGapPercent = if (currentPrice > 0) ((emaFast - emaSlow) / currentPrice) * 100 else 0.0
        val momentumPercent = calculateMomentumPercent(closes, PRICE_MOMENTUM_LOOKBACK)

        // 추세 방향 판단 (DI+ vs DI-)
        val trendDirection = adxResult.trendDirection

        // 절대/상대 변동성을 결합해 고변동성 판정
        val isHighVolatility = atrPercent >= HIGH_VOLATILITY_ABSOLUTE_ATR_PERCENT ||
            volatilityContext.percentile >= HIGH_VOLATILITY_PERCENTILE ||
            volatilityContext.zScore >= HIGH_VOLATILITY_ZSCORE_THRESHOLD

        val regime = when {
            adxResult.adx >= ADX_TREND_THRESHOLD && trendDirection > 0 && emaGapPercent >= 0.0 ->
                MarketRegime.BULL_TREND
            adxResult.adx >= ADX_TREND_THRESHOLD && trendDirection < 0 && emaGapPercent <= 0.0 ->
                MarketRegime.BEAR_TREND
            isBearDriftRegime(trendDirection, emaGapPercent, momentumPercent, atrPercent) ->
                MarketRegime.BEAR_TREND
            isHighVolatility -> MarketRegime.HIGH_VOLATILITY
            else -> MarketRegime.SIDEWAYS
        }

        // 신뢰도 계산
        val confidence = when (regime) {
            MarketRegime.HIGH_VOLATILITY -> {
                55.0 +
                    (volatilityContext.percentile * 20.0) +
                    (volatilityContext.zScore.coerceAtLeast(0.0) * 8.0).coerceAtMost(16.0) +
                    if (adxResult.adx < ADX_SIDEWAYS_THRESHOLD) 6.0 else 0.0
            }
            MarketRegime.BULL_TREND, MarketRegime.BEAR_TREND -> {
                val adxScore = ((adxResult.adx - ADX_SIDEWAYS_THRESHOLD).coerceAtLeast(0.0) * 2.0).coerceAtMost(28.0)
                val trendGapScore = abs(emaGapPercent).coerceAtMost(1.8) * 10.0
                val momentumScore = abs(momentumPercent).coerceAtMost(4.0) * 2.5
                45.0 + adxScore + trendGapScore + momentumScore
            }
            MarketRegime.SIDEWAYS -> {
                val adxScore = ((ADX_SIDEWAYS_THRESHOLD - adxResult.adx).coerceAtLeast(0.0) * 2.2).coerceAtMost(24.0)
                val lowVolBonus = ((1.0 - volatilityContext.percentile).coerceIn(0.0, 1.0) * 16.0)
                val neutralTrendBonus = (2.0 - abs(momentumPercent)).coerceIn(0.0, 2.0) * 5.0
                45.0 + adxScore + lowVolBonus + neutralTrendBonus
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

    private data class VolatilityContext(
        val percentile: Double,
        val zScore: Double,
        val baselineAtrPercent: Double
    )

    private fun isBearDriftRegime(
        trendDirection: Int,
        emaGapPercent: Double,
        momentumPercent: Double,
        atrPercent: Double
    ): Boolean {
        if (trendDirection > 0) return false

        val momentumBearish = momentumPercent <= -1.2
        val emaBearish = emaGapPercent <= -0.12
        val volatilitySupport = atrPercent >= 1.2

        return (momentumBearish && emaBearish) || (trendDirection < 0 && volatilitySupport && momentumPercent <= -0.8)
    }

    private fun analyzeVolatilityContext(candles: List<Candle>, currentAtrPercent: Double): VolatilityContext {
        if (candles.size < ATR_PERIOD + 5) {
            return VolatilityContext(
                percentile = if (currentAtrPercent > HIGH_VOLATILITY_ABSOLUTE_ATR_PERCENT) 1.0 else 0.5,
                zScore = if (currentAtrPercent > HIGH_VOLATILITY_ABSOLUTE_ATR_PERCENT) 1.0 else 0.0,
                baselineAtrPercent = currentAtrPercent
            )
        }

        val atrPercentSeries = mutableListOf<Double>()

        // 현재 시점 직전까지의 ATR% 분포를 기준선으로 사용
        for (i in ATR_PERIOD until candles.lastIndex) {
            val window = candles.subList(0, i + 1)
            val atr = calculateATR(window)
            val price = window.last().close.toDouble()
            if (atr > 0 && price > 0) {
                atrPercentSeries += (atr / price) * 100
            }
        }

        if (atrPercentSeries.isEmpty()) {
            return VolatilityContext(0.5, 0.0, currentAtrPercent)
        }

        val recent = atrPercentSeries.takeLast(min(VOLATILITY_LOOKBACK, atrPercentSeries.size))
        val mean = recent.average()
        val variance = recent.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val zScore = if (stdDev > 0.0) (currentAtrPercent - mean) / stdDev else 0.0

        val rank = recent.count { it <= currentAtrPercent }
        val percentile = if (recent.isNotEmpty()) rank.toDouble() / recent.size else 0.5

        return VolatilityContext(
            percentile = percentile.coerceIn(0.0, 1.0),
            zScore = zScore,
            baselineAtrPercent = mean
        )
    }

    private fun calculateMomentumPercent(closes: List<Double>, lookback: Int): Double {
        if (closes.size < 2) return 0.0
        val effectiveLookback = min(lookback, closes.size - 1)
        val basePrice = closes[closes.size - 1 - effectiveLookback]
        val currentPrice = closes.last()
        if (basePrice <= 0) return 0.0
        return ((currentPrice - basePrice) / basePrice) * 100
    }

    private fun calculateEmaLast(values: List<Double>, period: Int): Double? {
        if (values.size < period || period <= 1) return null

        val multiplier = 2.0 / (period + 1)
        var ema = values.take(period).average()
        for (i in period until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
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

        if (trueRanges.size < ADX_PERIOD) {
            return ADXResult(0.0, 0.0, 0.0, 0)
        }

        var smoothedTR = trueRanges.take(ADX_PERIOD).average()
        var smoothedPlusDM = plusDMs.take(ADX_PERIOD).average()
        var smoothedMinusDM = minusDMs.take(ADX_PERIOD).average()

        val dxSeries = mutableListOf<Double>()
        var plusDI = 0.0
        var minusDI = 0.0

        for (i in ADX_PERIOD until trueRanges.size) {
            smoothedTR = ((smoothedTR * (ADX_PERIOD - 1)) + trueRanges[i]) / ADX_PERIOD
            smoothedPlusDM = ((smoothedPlusDM * (ADX_PERIOD - 1)) + plusDMs[i]) / ADX_PERIOD
            smoothedMinusDM = ((smoothedMinusDM * (ADX_PERIOD - 1)) + minusDMs[i]) / ADX_PERIOD

            plusDI = if (smoothedTR > 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
            minusDI = if (smoothedTR > 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0

            val diSum = plusDI + minusDI
            val dx = if (diSum > 0) (abs(plusDI - minusDI) / diSum) * 100 else 0.0
            dxSeries += dx
        }

        val adx = when {
            dxSeries.isEmpty() -> 0.0
            dxSeries.size <= ADX_PERIOD -> dxSeries.average()
            else -> {
                var smoothedAdx = dxSeries.take(ADX_PERIOD).average()
                for (i in ADX_PERIOD until dxSeries.size) {
                    smoothedAdx = ((smoothedAdx * (ADX_PERIOD - 1)) + dxSeries[i]) / ADX_PERIOD
                }
                smoothedAdx
            }
        }

        // Trend direction
        val trendDirection = when {
            plusDI > minusDI + 2 -> 1   // 상승
            minusDI > plusDI + 2 -> -1  // 하락
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
                timestamp = parseInstantSafe(response.candleDateTimeUtc),
                open = response.openingPrice,
                high = response.highPrice,
                low = response.lowPrice,
                close = response.tradePrice,
                volume = response.candleAccTradeVolume
            )
        }
        return detect(candles)
    }

    /**
     * 안전한 Instant 파싱 (시간대 정보 없으면 UTC로 간주)
     */
    private fun parseInstantSafe(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()

        return try {
            Instant.parse(dateTimeStr)
        } catch (e: Exception) {
            try {
                Instant.parse("${dateTimeStr}Z")
            } catch (e2: Exception) {
                val localDateTime = java.time.LocalDateTime.parse(dateTimeStr)
                localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
            }
        }
    }
}

/**
 * 엔진용 레짐 감지 유틸리티 (공통)
 *
 * MemeScalperEngine, VolumeSurgeEngine에서 사용하는 레짐 감지 로직.
 */
fun detectMarketRegime(
    bithumbPublicApi: com.ant.cointrading.api.bithumb.BithumbPublicApi,
    regimeDetector: RegimeDetector,
    market: String,
    log: org.slf4j.Logger
): String? {
    return try {
        val candles = bithumbPublicApi.getOhlcv(market, "minute60", 100)
        if (!candles.isNullOrEmpty() && candles.size >= 15) {
            regimeDetector.detectFromBithumb(candles).regime.name
        } else {
            log.debug("[$market] 캔들 데이터 부족으로 레짐 감지 스킵")
            null
        }
    } catch (e: Exception) {
        log.warn("[$market] 레짐 감지 실패: ${e.message}")
        null
    }
}
