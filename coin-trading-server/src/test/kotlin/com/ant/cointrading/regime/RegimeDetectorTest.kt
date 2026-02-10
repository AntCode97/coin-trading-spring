package com.ant.cointrading.regime

import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Regime Detector")
class RegimeDetectorTest {

    private val detector = RegimeDetector()

    @Test
    @DisplayName("고변동 횡보 패턴은 HIGH_VOLATILITY로 분류한다")
    fun shouldDetectHighVolatilityInSidewaysWhipsaw() {
        val candles = buildWhipsawSidewaysCandles()

        val analysis = detector.detect(candles)

        assertEquals(MarketRegime.HIGH_VOLATILITY, analysis.regime)
        assertTrue(analysis.atrPercent >= 2.0, "atrPercent=${analysis.atrPercent}")
    }

    @Test
    @DisplayName("완만한 하락 드리프트는 BEAR_TREND로 분류한다")
    fun shouldDetectBearTrendOnDownwardDrift() {
        val candles = buildDownwardDriftCandles()

        val analysis = detector.detect(candles)

        assertEquals(MarketRegime.BEAR_TREND, analysis.regime)
        assertTrue(analysis.trendDirection <= 0, "trendDirection=${analysis.trendDirection}")
    }

    private fun buildWhipsawSidewaysCandles(): List<Candle> {
        val start = Instant.parse("2025-09-01T00:00:00Z")
        val candles = mutableListOf<Candle>()
        var price = 100.0

        repeat(120) { index ->
            val open = price
            val shock = when (index % 8) {
                0 -> 2.1
                1 -> -1.8
                2 -> 1.7
                3 -> -2.2
                4 -> 2.0
                5 -> -1.6
                6 -> 1.4
                else -> -1.7
            }
            val close = (open + shock).coerceAtLeast(1.0)
            val high = max(open, close) * 1.026
            val low = min(open, close) * 0.974

            candles += Candle(
                timestamp = start.plus(index.toLong(), ChronoUnit.HOURS),
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(1100.0 + (index % 5) * 180.0)
            )

            price = close
        }

        return candles
    }

    private fun buildDownwardDriftCandles(): List<Candle> {
        val start = Instant.parse("2025-09-01T00:00:00Z")
        val candles = mutableListOf<Candle>()
        var price = 220.0

        repeat(120) { index ->
            val open = price
            val drift = 0.75 + (index % 4) * 0.08
            val rebound = if (index % 9 == 0) 0.22 else 0.0
            val close = (open - drift + rebound).coerceAtLeast(1.0)
            val high = max(open, close) * 1.008
            val low = min(open, close) * 0.992

            candles += Candle(
                timestamp = start.plus(index.toLong(), ChronoUnit.HOURS),
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(1500.0 + (index % 6) * 90.0)
            )

            price = close
        }

        return candles
    }
}
