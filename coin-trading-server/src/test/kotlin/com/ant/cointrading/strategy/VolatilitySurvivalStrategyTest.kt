package com.ant.cointrading.strategy

import com.ant.cointrading.backtesting.BasicSimulator
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
    @DisplayName("횡보 고변동 하락 구간의 하단 회복에서는 레인지 리클레임 매수를 낸다")
    fun shouldBuyOnRangeReclaimInHighVolatilitySideways() {
        val candles = buildRangeReclaimCandles()

        val signal = strategy.analyze(
            market = "KRW-BTC",
            candles = candles,
            currentPrice = candles.last().close,
            regime = sidewaysHighVolatilityRegime()
        )

        assertEquals(SignalAction.BUY, signal.action, "reason=${signal.reason}")
        assertTrue(signal.reason.contains("레인지 리클레임"), "reason=${signal.reason}")
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

    @Test
    @DisplayName("합성 고변동성 장세 백테스트에서 양수 수익률을 낸다")
    fun shouldProducePositiveReturnInSyntheticHighVolatilityBacktest() {
        val simulator = BasicSimulator()
        val candles = buildSyntheticWhipsawCandles()

        val result = simulator.simulate(
            strategy = strategy,
            historicalData = candles,
            initialCapital = 1_000_000.0,
            commissionRate = 0.0004
        )

        assertTrue(result.totalTrades >= 1, "trades=${result.totalTrades}")
        assertTrue(result.totalReturn > 0.0, "return=${result.totalReturn}")
        if (result.profitFactor != null) {
            assertTrue(result.profitFactor > 1.0, "profitFactor=${result.profitFactor}")
        }
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

    private fun sidewaysHighVolatilityRegime(): RegimeAnalysis {
        return RegimeAnalysis(
            regime = MarketRegime.SIDEWAYS,
            confidence = 74.0,
            adx = 19.0,
            atr = 2.1,
            atrPercent = 2.1,
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

    private fun buildRangeReclaimCandles(): List<Candle> {
        val closes = mutableListOf<Double>()
        var price = 122.0

        repeat(52) {
            closes += price
            price -= 0.12
        }

        closes += listOf(
            price - 0.3,
            price - 0.9,
            price - 1.2,
            price - 0.8,
            price - 0.45,
            price - 0.32
        )

        val start = Instant.parse("2025-11-10T00:00:00Z")
        val volumes = MutableList(closes.size) { 980.0 }
        volumes[closes.lastIndex - 1] = 1350.0
        volumes[closes.lastIndex] = 1620.0

        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            val high = maxOf(open, close) * 1.012
            val low = minOf(open, close) * 0.988

            Candle(
                timestamp = start.plus(index.toLong(), ChronoUnit.HOURS),
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(volumes[index])
            )
        }
    }

    private fun buildSyntheticWhipsawCandles(): List<Candle> {
        val closes = mutableListOf<Double>()
        var price = 155.0

        repeat(60) {
            closes += price
            price -= 0.35
        }

        repeat(5) { cycle ->
            val cycleBase = 134.0 - (cycle * 3.2)
            closes += listOf(
                cycleBase,
                cycleBase * 0.985,
                cycleBase * 0.965,
                cycleBase * 0.942,
                cycleBase * 0.918,
                cycleBase * 0.902,
                cycleBase * 0.909,
                cycleBase * 0.925,
                cycleBase * 0.946,
                cycleBase * 0.966,
                cycleBase * 0.984,
                cycleBase * 0.972,
                cycleBase * 0.954
            )
        }

        val start = Instant.parse("2025-12-01T00:00:00Z")

        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            val high = maxOf(open, close) * 1.007
            val low = minOf(open, close) * 0.993

            val volume = when {
                index == 0 -> 1000.0
                close > closes[index - 1] -> 2600.0
                else -> 1000.0
            }

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
