package com.ant.cointrading.indicator

import com.ant.cointrading.model.Candle
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Confluence Analyzer")
class ConfluenceAnalyzerTest {

    private val analyzer = ConfluenceAnalyzer()

    @Test
    @DisplayName("지속 상승 구간을 과매도로 오판하지 않는다")
    fun shouldNotTreatRisingMarketAsOversold() {
        val candles = buildLinearTrendCandles(
            startPrice = 100.0,
            stepPerCandle = 1.2,
            count = 90,
            volume = 1200.0
        )

        val result = analyzer.analyze(candles)

        assertEquals(0, result.rsiScore, "상승장 RSI 오판: ${result.details}")
    }

    @Test
    @DisplayName("하락 후 반등 구간에서 MACD 점수가 산출된다")
    fun shouldProduceMacdScoreOnBullishRecovery() {
        val closes = mutableListOf<Double>()
        var price = 220.0

        repeat(55) {
            closes += price
            price -= 1.0
        }

        repeat(35) {
            closes += price
            price += 2.1
        }

        val candles = buildCandlesFromCloses(closes)
        val result = analyzer.analyze(candles)

        assertTrue(result.macdScore > 0, "MACD 점수 미산출: ${result.details}")
    }

    private fun buildLinearTrendCandles(
        startPrice: Double,
        stepPerCandle: Double,
        count: Int,
        volume: Double
    ): List<Candle> {
        val closes = mutableListOf<Double>()
        var price = startPrice
        repeat(count) {
            closes += price
            price += stepPerCandle
        }
        return buildCandlesFromCloses(closes, volume)
    }

    private fun buildCandlesFromCloses(
        closes: List<Double>,
        baseVolume: Double = 1200.0
    ): List<Candle> {
        val start = Instant.parse("2025-10-01T00:00:00Z")

        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            val high = maxOf(open, close) * 1.004
            val low = minOf(open, close) * 0.996
            val volume = if (close >= open) baseVolume * 1.2 else baseVolume

            Candle(
                timestamp = start.plus(index.toLong(), ChronoUnit.HOURS),
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(volume)
            )
        }
    }
}
