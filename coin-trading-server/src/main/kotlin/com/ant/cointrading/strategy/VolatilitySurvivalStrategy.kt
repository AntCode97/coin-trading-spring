package com.ant.cointrading.strategy

import com.ant.cointrading.backtesting.BacktestAction
import com.ant.cointrading.backtesting.BacktestSignal
import com.ant.cointrading.backtesting.BacktestableStrategy
import com.ant.cointrading.config.VolatilitySurvivalProperties
import com.ant.cointrading.indicator.AtrCalculator
import com.ant.cointrading.indicator.EmaCalculator
import com.ant.cointrading.indicator.RsiCalculator
import com.ant.cointrading.indicator.calculateBollingerBands
import com.ant.cointrading.indicator.calculateVolumeRatio
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 고변동성 장세 전용 생존/반등 추세 전략.
 *
 * 핵심 아이디어:
 * - 패닉 하락 직후의 과매도 반등만 선별 진입
 * - 손절은 짧게, 익절은 빠르게
 * - 손절 직후에는 강제 쿨다운으로 재진입 중독 차단
 */
@Component
class VolatilitySurvivalStrategy(
    private val properties: VolatilitySurvivalProperties
) : TradingStrategy, BacktestableStrategy {

    private val log = LoggerFactory.getLogger(javaClass)
    private val atrCalculator = AtrCalculator()

    private val openPositions = ConcurrentHashMap<String, PositionState>()
    private val stopLossCooldownUntil = ConcurrentHashMap<String, Instant>()

    data class PositionState(
        val entryPrice: Double,
        val stopLossPrice: Double,
        val takeProfitPrice: Double,
        val entryTime: Instant,
        val entryAtrPercent: Double,
        val peakPrice: Double
    )

    data class MarketShockMetrics(
        val dropFromHighPercent: Double,
        val reboundFromLowPercent: Double
    )

    override val name: String = "VOLATILITY_SURVIVAL"

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        if (!properties.enabled) {
            return createHoldSignal(market, currentPrice, "전략 비활성화")
        }

        if (candles.size < properties.minCandles) {
            return createHoldSignal(
                market,
                currentPrice,
                "데이터 부족 (${candles.size}/${properties.minCandles})"
            )
        }

        val now = Instant.now()
        val closes = candles.map { it.close.toDouble() }
        val currentPriceDouble = currentPrice.toDouble()

        val rsi = RsiCalculator.calculate(closes, properties.rsiPeriod)
        val emaFast = EmaCalculator.calculateLast(closes, properties.emaFastPeriod) ?: currentPriceDouble
        val emaSlow = EmaCalculator.calculateLast(closes, properties.emaSlowPeriod) ?: currentPriceDouble
        val (_, lowerBand, _) = calculateBollingerBands(
            candles,
            properties.bollingerPeriod,
            properties.bollingerStdDev
        )
        val volumeRatio = calculateVolumeRatio(candles, properties.volumePeriod + 1)

        val atr = atrCalculator.getLatestAtr(candles) ?: (currentPriceDouble * 0.015)
        val atrPercent = if (currentPriceDouble > 0) (atr / currentPriceDouble) * 100 else 0.0

        val existingPosition = openPositions[market]
        if (existingPosition != null) {
            return evaluateExitSignal(
                market = market,
                currentPrice = currentPrice,
                currentPriceDouble = currentPriceDouble,
                rsi = rsi,
                emaFast = emaFast,
                position = existingPosition,
                now = now
            )
        }

        val fallbackRiskOffSignal = evaluateFallbackRiskOffSell(
            market = market,
            regime = regime,
            currentPrice = currentPrice,
            currentPriceDouble = currentPriceDouble,
            rsi = rsi,
            emaFast = emaFast,
            emaSlow = emaSlow,
            volumeRatio = volumeRatio
        )
        if (fallbackRiskOffSignal != null) {
            return fallbackRiskOffSignal
        }

        if (!isRegimeTradable(regime, atrPercent)) {
            return createHoldSignal(
                market,
                currentPrice,
                "레짐 부적합: ${regime.regime}, ATR=${String.format("%.2f", atrPercent)}%"
            )
        }

        val cooldownUntil = stopLossCooldownUntil[market]
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            val remainingMinutes = Duration.between(now, cooldownUntil).toMinutes().coerceAtLeast(0)
            return createHoldSignal(
                market,
                currentPrice,
                "손절 쿨다운 중 (${remainingMinutes}분 남음)"
            )
        }

        val shockMetrics = calculateShockMetrics(candles, currentPriceDouble)
        val previousCandle = candles[candles.lastIndex - 1]
        val previousClose = previousCandle.close.toDouble()

        val nearLowerBand = currentPriceDouble <= lowerBand * (1 + properties.lowerBandTolerancePercent / 100)
        val greenRecovery = currentPriceDouble > previousClose && currentPriceDouble >= candles.last().open.toDouble()
        val trendRecovery = currentPriceDouble >= emaFast || shockMetrics.reboundFromLowPercent >= properties.minReboundPercent * 1.5

        val shouldEnter = shockMetrics.dropFromHighPercent <= -properties.minPanicDropPercent &&
            shockMetrics.reboundFromLowPercent >= properties.minReboundPercent &&
            rsi <= properties.rsiEntryThreshold + 4.0 &&
            volumeRatio >= properties.minVolumeRatio &&
            greenRecovery &&
            trendRecovery

        if (!shouldEnter) {
            return createHoldSignal(
                market,
                currentPrice,
                "진입 조건 미달: drop=${String.format("%.2f", shockMetrics.dropFromHighPercent)}%, " +
                    "rebound=${String.format("%.2f", shockMetrics.reboundFromLowPercent)}%, " +
                    "RSI=${String.format("%.1f", rsi)}, vol=${String.format("%.2f", volumeRatio)}x"
            )
        }

        val stopLossPrice = currentPriceDouble * (1 - properties.stopLossPercent / 100)
        val takeProfitPrice = currentPriceDouble * (1 + properties.takeProfitPercent / 100)

        openPositions[market] = PositionState(
            entryPrice = currentPriceDouble,
            stopLossPrice = stopLossPrice,
            takeProfitPrice = takeProfitPrice,
            entryTime = now,
            entryAtrPercent = atrPercent,
            peakPrice = currentPriceDouble
        )

        val confidence = calculateEntryConfidence(
            regime = regime,
            atrPercent = atrPercent,
            rsi = rsi,
            volumeRatio = volumeRatio,
            shockMetrics = shockMetrics
        )

        log.info(
            "[$market] VolatilitySurvival 진입: price={}, RSI={}, drop={}, rebound={}, ATR={}%",
            String.format("%.0f", currentPriceDouble),
            String.format("%.1f", rsi),
            String.format("%.2f", shockMetrics.dropFromHighPercent),
            String.format("%.2f", shockMetrics.reboundFromLowPercent),
            String.format("%.2f", atrPercent)
        )

        return TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = confidence,
            price = currentPrice,
            reason = "패닉 반등 진입: drop=${String.format("%.2f", shockMetrics.dropFromHighPercent)}%, " +
                "rebound=${String.format("%.2f", shockMetrics.reboundFromLowPercent)}%, " +
                "RSI=${String.format("%.1f", rsi)}, ATR=${String.format("%.2f", atrPercent)}%, " +
                "SL=${String.format("%.0f", stopLossPrice)}, TP=${String.format("%.0f", takeProfitPrice)}",
            strategy = name,
            regime = regime.regime.name
        )
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        return when (regime.regime) {
            MarketRegime.HIGH_VOLATILITY -> true
            MarketRegime.BEAR_TREND -> regime.atrPercent >= properties.minAtrPercent
            else -> false
        }
    }

    override fun getDescription(): String {
        return """
            Volatility Survival 전략
            - 패닉 급락 후 반등만 선별 진입
            - 짧은 손절(${properties.stopLossPercent}%) / 빠른 익절(${properties.takeProfitPercent}%)
            - 손절 후 ${properties.stopLossCooldownMinutes}분 강제 쿨다운
            - 고변동성/베어 구간 전용 생존형 전략
        """.trimIndent()
    }

    override fun analyzeForBacktest(
        candles: List<Candle>,
        currentIndex: Int,
        initialCapital: Double,
        currentPrice: BigDecimal,
        currentPosition: Double
    ): BacktestSignal {
        if (currentIndex < properties.minCandles) {
            return BacktestSignal(BacktestAction.HOLD, reason = "데이터 부족")
        }

        val available = candles.subList(0, currentIndex + 1)
        val closes = available.map { it.close.toDouble() }
        val currentPriceDouble = currentPrice.toDouble()

        val rsi = RsiCalculator.calculate(closes, properties.rsiPeriod)
        val emaFast = EmaCalculator.calculateLast(closes, properties.emaFastPeriod) ?: currentPriceDouble
        val volumeRatio = calculateVolumeRatio(available, properties.volumePeriod + 1)
        val shockMetrics = calculateShockMetrics(available, currentPriceDouble)

        if (currentPosition > 0) {
            if (rsi >= properties.rsiExitThreshold || currentPriceDouble < emaFast || volumeRatio < 0.85) {
                return BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 78.0,
                    reason = "리스크 오프 청산 (RSI=${String.format("%.1f", rsi)}, vol=${String.format("%.2f", volumeRatio)}x)"
                )
            }
            return BacktestSignal(BacktestAction.HOLD)
        }

        val entryCondition = shockMetrics.dropFromHighPercent <= -properties.minPanicDropPercent &&
            shockMetrics.reboundFromLowPercent >= properties.minReboundPercent &&
            rsi <= properties.rsiEntryThreshold &&
            volumeRatio >= properties.minVolumeRatio

        if (entryCondition) {
            return BacktestSignal(
                BacktestAction.BUY,
                confidence = 72.0,
                reason = "패닉 반등 진입 조건 충족"
            )
        }

        return BacktestSignal(BacktestAction.HOLD)
    }

    private fun evaluateExitSignal(
        market: String,
        currentPrice: BigDecimal,
        currentPriceDouble: Double,
        rsi: Double,
        emaFast: Double,
        position: PositionState,
        now: Instant
    ): TradingSignal {
        var effectivePosition = position

        if (currentPriceDouble > position.peakPrice) {
            effectivePosition = position.copy(peakPrice = currentPriceDouble)
            openPositions[market] = effectivePosition
        }

        val pnlPercent = ((currentPriceDouble - effectivePosition.entryPrice) / effectivePosition.entryPrice) * 100
        val holdingMinutes = Duration.between(effectivePosition.entryTime, now).toMinutes()

        val trailingStopPrice = effectivePosition.peakPrice * (1 - properties.trailingOffsetPercent / 100)

        if (currentPriceDouble <= effectivePosition.stopLossPrice) {
            openPositions.remove(market)
            stopLossCooldownUntil[market] = now.plusSeconds(properties.stopLossCooldownMinutes * 60)

            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 95.0,
                price = currentPrice,
                reason = "손절 청산 ${String.format("%.2f", pnlPercent)}% (cooldown ${properties.stopLossCooldownMinutes}분)",
                strategy = name
            )
        }

        if (currentPriceDouble >= effectivePosition.takeProfitPrice) {
            openPositions.remove(market)
            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 88.0,
                price = currentPrice,
                reason = "목표가 청산 ${String.format("%.2f", pnlPercent)}%",
                strategy = name
            )
        }

        if (pnlPercent >= properties.trailingActivationPercent && currentPriceDouble <= trailingStopPrice) {
            openPositions.remove(market)
            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 84.0,
                price = currentPrice,
                reason = "트레일링 청산 ${String.format("%.2f", pnlPercent)}%",
                strategy = name
            )
        }

        if (rsi >= properties.rsiExitThreshold && currentPriceDouble < emaFast) {
            openPositions.remove(market)
            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 78.0,
                price = currentPrice,
                reason = "과열 해소 청산 (RSI=${String.format("%.1f", rsi)})",
                strategy = name
            )
        }

        if (holdingMinutes >= properties.maxHoldingMinutes) {
            openPositions.remove(market)
            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 70.0,
                price = currentPrice,
                reason = "타임아웃 청산 (${holdingMinutes}분)",
                strategy = name
            )
        }

        return createHoldSignal(
            market,
            currentPrice,
            "보유 유지: pnl=${String.format("%.2f", pnlPercent)}%, RSI=${String.format("%.1f", rsi)}"
        )
    }

    private fun evaluateFallbackRiskOffSell(
        market: String,
        regime: RegimeAnalysis,
        currentPrice: BigDecimal,
        currentPriceDouble: Double,
        rsi: Double,
        emaFast: Double,
        emaSlow: Double,
        volumeRatio: Double
    ): TradingSignal? {
        val overboughtExhaustion = rsi >= properties.rsiExitThreshold &&
            currentPriceDouble < emaFast &&
            currentPriceDouble < emaSlow &&
            volumeRatio <= 1.1 &&
            regime.regime != MarketRegime.BULL_TREND

        if (overboughtExhaustion) {
            return TradingSignal(
                market = market,
                action = SignalAction.SELL,
                confidence = 68.0,
                price = currentPrice,
                reason = "반등 과열 이후 이탈 감지",
                strategy = name,
                regime = regime.regime.name
            )
        }

        return null
    }

    private fun isRegimeTradable(regime: RegimeAnalysis, atrPercent: Double): Boolean {
        return when (regime.regime) {
            MarketRegime.HIGH_VOLATILITY -> atrPercent >= properties.minAtrPercent
            MarketRegime.BEAR_TREND -> atrPercent >= properties.minAtrPercent
            else -> false
        }
    }

    private fun calculateShockMetrics(candles: List<Candle>, currentPrice: Double): MarketShockMetrics {
        val lookback = candles.takeLast(properties.panicLookbackCandles)

        val recentHigh = lookback.maxOf { it.high.toDouble() }
        val recentLow = lookback.minOf { it.low.toDouble() }

        val dropFromHighPercent = if (recentHigh > 0) {
            ((currentPrice - recentHigh) / recentHigh) * 100
        } else {
            0.0
        }

        val reboundFromLowPercent = if (recentLow > 0) {
            ((currentPrice - recentLow) / recentLow) * 100
        } else {
            0.0
        }

        return MarketShockMetrics(
            dropFromHighPercent = dropFromHighPercent,
            reboundFromLowPercent = reboundFromLowPercent
        )
    }

    private fun calculateEntryConfidence(
        regime: RegimeAnalysis,
        atrPercent: Double,
        rsi: Double,
        volumeRatio: Double,
        shockMetrics: MarketShockMetrics
    ): Double {
        var score = 45.0

        val dropBonus = (-shockMetrics.dropFromHighPercent - properties.minPanicDropPercent).coerceIn(0.0, 10.0) * 2.0
        score += dropBonus

        val oversoldBonus = (properties.rsiEntryThreshold - rsi).coerceIn(0.0, 15.0) * 1.2
        score += oversoldBonus

        val volumeBonus = (volumeRatio - properties.minVolumeRatio).coerceIn(0.0, 2.5) * 6.0
        score += volumeBonus

        val reboundBonus = (shockMetrics.reboundFromLowPercent - properties.minReboundPercent).coerceIn(0.0, 4.0) * 2.5
        score += reboundBonus

        if (regime.regime == MarketRegime.HIGH_VOLATILITY) {
            score += 4.0
        }

        if (atrPercent >= properties.minAtrPercent * 1.3) {
            score += 4.0
        }

        return score.coerceIn(55.0, 92.0)
    }

    private fun createHoldSignal(market: String, currentPrice: BigDecimal, reason: String): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.HOLD,
            confidence = 0.0,
            price = currentPrice,
            reason = reason,
            strategy = name
        )
    }
}
