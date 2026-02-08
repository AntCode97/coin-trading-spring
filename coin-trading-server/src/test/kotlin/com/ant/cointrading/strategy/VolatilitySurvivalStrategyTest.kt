package com.ant.cointrading.strategy

import com.ant.cointrading.config.VolatilitySurvivalProperties
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.model.SignalAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Volatility Survival Strategy")
class VolatilitySurvivalStrategyTest {

    private val properties = VolatilitySurvivalProperties(
        stopLossCooldownMinutes = 60
    )

    private val strategy = VolatilitySurvivalStrategy(properties)

    @Test
    @DisplayName("패닉 하락 후 반등 조건이면 매수 신호를 낸다")
    fun shouldBuyAfterPanicDropAndRebound() {
        val candles = buildCrashReboundCandles(finalClose = 108.6, finalVolume = 3100.0)

        val signal = strategy.analyze(
            market = "KRW-BTC",
            candles = candles,
            currentPrice = candles.last().close,
            regime = highVolatilityRegime()
        )

        assertEquals(SignalAction.BUY, signal.action, "reason=${signal.reason}")
        assertTrue(signal.reason.contains("패닉 반등"))
    }

    @Test
    @DisplayName("진입 후 손절가 이탈 시 즉시 매도한다")
    fun shouldSellWhenStopLossBreached() {
        val entryCandles = buildCrashReboundCandles(finalClose = 108.6, finalVolume = 3100.0)
        val entrySignal = strategy.analyze(
            market = "KRW-ETH",
            candles = entryCandles,
            currentPrice = entryCandles.last().close,
            regime = highVolatilityRegime()
        )
        assertEquals(SignalAction.BUY, entrySignal.action, "reason=${entrySignal.reason}")

        val stopLossCandles = buildCrashReboundCandles(finalClose = 104.2, finalVolume = 1800.0)
        val stopLossSignal = strategy.analyze(
            market = "KRW-ETH",
            candles = stopLossCandles,
            currentPrice = stopLossCandles.last().close,
            regime = highVolatilityRegime()
        )

        assertEquals(SignalAction.SELL, stopLossSignal.action)
        assertTrue(stopLossSignal.reason.contains("손절"))
    }

    @Test
    @DisplayName("손절 직후에는 쿨다운으로 재진입을 막는다")
    fun shouldBlockReEntryDuringCooldownAfterStopLoss() {
        val entryCandles = buildCrashReboundCandles(finalClose = 108.6, finalVolume = 3100.0)
        val entrySignal = strategy.analyze(
            market = "KRW-XRP",
            candles = entryCandles,
            currentPrice = entryCandles.last().close,
            regime = highVolatilityRegime()
        )
        assertEquals(SignalAction.BUY, entrySignal.action, "reason=${entrySignal.reason}")

        val stopLossCandles = buildCrashReboundCandles(finalClose = 104.2, finalVolume = 2000.0)
        val stopLossSignal = strategy.analyze(
            market = "KRW-XRP",
            candles = stopLossCandles,
            currentPrice = stopLossCandles.last().close,
            regime = highVolatilityRegime()
        )
        assertEquals(SignalAction.SELL, stopLossSignal.action)

        val immediateRetrySignal = strategy.analyze(
            market = "KRW-XRP",
            candles = entryCandles,
            currentPrice = entryCandles.last().close,
            regime = highVolatilityRegime()
        )

        assertEquals(SignalAction.HOLD, immediateRetrySignal.action)
        assertTrue(immediateRetrySignal.reason.contains("쿨다운"))
    }

    @Test
    @DisplayName("고변동성/베어 + 높은 ATR에서만 전략 적합 판정")
    fun suitabilityDependsOnRegimeAndAtr() {
        assertTrue(strategy.isSuitableFor(highVolatilityRegime()))

        val bearHighAtr = RegimeAnalysis(
            regime = MarketRegime.BEAR_TREND,
            confidence = 72.0,
            adx = 31.0,
            atr = 2.1,
            atrPercent = 2.4,
            trendDirection = -1,
            timestamp = Instant.now()
        )
        assertTrue(strategy.isSuitableFor(bearHighAtr))

        val sideways = RegimeAnalysis(
            regime = MarketRegime.SIDEWAYS,
            confidence = 65.0,
            adx = 18.0,
            atr = 0.9,
            atrPercent = 0.9,
            trendDirection = 0,
            timestamp = Instant.now()
        )
        assertFalse(strategy.isSuitableFor(sideways))
    }

    private fun highVolatilityRegime(): RegimeAnalysis {
        return RegimeAnalysis(
            regime = MarketRegime.HIGH_VOLATILITY,
            confidence = 78.0,
            adx = 24.0,
            atr = 4.0,
            atrPercent = 3.8,
            trendDirection = -1,
            timestamp = Instant.now()
        )
    }

    private fun buildCrashReboundCandles(finalClose: Double, finalVolume: Double): List<Candle> {
        val closes = mutableListOf<Double>()
        var price = 132.0

        repeat(35) {
            closes += price
            price -= 0.35
        }

        repeat(11) {
            closes += price
            price -= 1.35
        }

        closes += listOf(price + 0.4, price + 1.0, price + 1.9, price + 2.8, finalClose)

        val baseVolumes = MutableList(closes.size) { 1000.0 }
        baseVolumes[closes.lastIndex - 1] = 1700.0
        baseVolumes[closes.lastIndex] = finalVolume

        val start = Instant.parse("2025-11-01T00:00:00Z")

        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            val high = maxOf(open, close) * 1.006
            val low = minOf(open, close) * 0.994

            Candle(
                timestamp = start.plus(index.toLong(), ChronoUnit.HOURS),
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(baseVolumes[index])
            )
        }
    }
}
