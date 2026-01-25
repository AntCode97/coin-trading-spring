package com.ant.cointrading.strategy

import com.ant.cointrading.backtesting.BacktestAction
import com.ant.cointrading.backtesting.BacktestSignal
import com.ant.cointrading.backtesting.BacktestableStrategy
import com.ant.cointrading.config.BreakoutProperties
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Breakout 전략
 *
 * 볼린저 밴드 돌파 전략:
 * - 상단 밴드 돌파 + 거래량 증가 = 매수
 * - 하단 밴드 돌파 + 거래량 증가 = 매도
 *
 * 적합한 시장 레짐:
 * - 추세 시장 (BULL_TREND, BEAR_TREND)
 * - 고변동성 시장 (HIGH_VOLATILITY)
 */
@Component
class BreakoutStrategy(
    private val properties: BreakoutProperties
) : TradingStrategy, BacktestableStrategy {

    private val log = LoggerFactory.getLogger(javaClass)

    // 실시간 트레이딩용 캐시
    private val lastSignalCache = ConcurrentHashMap<String, CachedSignal>()
    private val signalCooldowns = ConcurrentHashMap<String, Instant>()

    data class CachedSignal(
        val signal: TradingSignal,
        val timestamp: Instant
    )

    override val name: String = "BREAKOUT"

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        if (candles.size < properties.bollingerPeriod) {
            return createHoldSignal(market, "데이터 부족 (필요: ${properties.bollingerPeriod}개)")
        }

        // 볼린저 밴드 계산
        val (upperBand, lowerBand, middleBand) = calculateBollingerBands(candles)
        val currentPriceDouble = currentPrice.toDouble()

        // 돌파 조건 확인
        val isBreakoutUp = currentPriceDouble > upperBand
        val isBreakoutDown = currentPriceDouble < lowerBand

        // 거래량 확인 (돌파 시 거래량 증가했는지)
        val volumeRatio = calculateVolumeRatio(candles)

        // 쿨다운 체크
        val cooldownActive = isCooldownActive(market)

        return when {
            isBreakoutUp && volumeRatio >= properties.minBreakoutVolumeRatio && !cooldownActive -> {
                log.info(
                    "[$market] Breakout 상승 감지 - 가격: ${String.format("%.0f", currentPriceDouble)}, " +
                            "상단밴드: ${String.format("%.0f", upperBand)}, 거래량: ${String.format("%.1f", volumeRatio)}x"
                )
                updateCooldown(market)
                createBuySignal(market, currentPrice, volumeRatio)
            }

            isBreakoutDown && volumeRatio >= properties.minBreakoutVolumeRatio && !cooldownActive -> {
                log.info(
                    "[$market] Breakout 하락 감지 - 가격: ${String.format("%.0f", currentPriceDouble)}, " +
                            "하단밴드: ${String.format("%.0f", lowerBand)}, 거래량: ${String.format("%.1f", volumeRatio)}x"
                )
                updateCooldown(market)
                createSellSignal(market, currentPrice, volumeRatio)
            }

            else -> createHoldSignal(
                market,
                "Breakout 미달: " +
                        "상단돌파=$isBreakoutUp, " +
                        "하단돌파=$isBreakoutDown, " +
                        "거래량=${String.format("%.1f", volumeRatio)}x, " +
                        "쿨다운=$cooldownActive"
            )
        }
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        return when (regime.regime) {
            com.ant.cointrading.model.MarketRegime.BULL_TREND -> true
            com.ant.cointrading.model.MarketRegime.BEAR_TREND -> true
            com.ant.cointrading.model.MarketRegime.HIGH_VOLATILITY -> regime.confidence > 60
            com.ant.cointrading.model.MarketRegime.SIDEWAYS -> false
        }
    }

    override fun getDescription(): String {
        return """
            Breakout 전략 (볼린저 밴드 돌파)
            - 상단/하단 밴드 돌파 시 진입
            - 거래량 ${properties.minBreakoutVolumeRatio}배 이상 증가 필요
            - 볼린저 기간: ${properties.bollingerPeriod}, 표준편차: ${properties.bollingerStdDev}
            - 손절: ${properties.stopLossPercent}%, 익절: ${properties.takeProfitPercent}%
        """.trimIndent()
    }

    /**
     * 백테스팅용 신호 생성
     */
    override fun analyzeForBacktest(
        candles: List<Candle>,
        currentIndex: Int,
        initialCapital: Double,
        currentPrice: BigDecimal,
        currentPosition: Double
    ): BacktestSignal {
        if (candles.size < properties.bollingerPeriod || currentIndex < properties.bollingerPeriod) {
            return BacktestSignal(BacktestAction.HOLD, reason = "데이터 부족")
        }

        val availableCandles = candles.subList(0, currentIndex + 1)
        val (upperBand, lowerBand, _) = calculateBollingerBands(availableCandles)
        val currentPriceDouble = currentPrice.toDouble()
        val volumeRatio = calculateVolumeRatio(availableCandles)

        val isBreakoutUp = currentPriceDouble > upperBand
        val isBreakoutDown = currentPriceDouble < lowerBand

        return when {
            currentPosition == 0.0 && isBreakoutUp && volumeRatio >= properties.minBreakoutVolumeRatio -> {
                BacktestSignal(
                    BacktestAction.BUY,
                    confidence = 80.0,
                    reason = "Breakout 상승 (거래량 ${String.format("%.1f", volumeRatio)}x)"
                )
            }
            currentPosition > 0 && isBreakoutDown && volumeRatio >= properties.minBreakoutVolumeRatio -> {
                BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 80.0,
                    reason = "Breakout 하락 (거래량 ${String.format("%.1f", volumeRatio)}x)"
                )
            }
            currentPosition > 0 && isInProfit(currentPriceDouble, currentPosition) -> {
                BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 70.0,
                    reason = "익절 목표 도달"
                )
            }
            currentPosition > 0 && isInLoss(currentPriceDouble, currentPosition) -> {
                BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 90.0,
                    reason = "손절 기준 도달"
                )
            }
            else -> BacktestSignal(BacktestAction.HOLD)
        }
    }

    // ==================== Private Methods ====================

    private fun calculateBollingerBands(candles: List<Candle>): Triple<Double, Double, Double> {
        val closes = candles.map { it.close.toDouble() }
        val period = minOf(properties.bollingerPeriod, closes.size)

        val recentCloses = closes.takeLast(period)
        val sma = recentCloses.average()
        val variance = recentCloses.map { val diff = it - sma; diff * diff }.average()
        val stdDev = sqrt(variance)

        val upperBand = sma + (properties.bollingerStdDev * stdDev)
        val lowerBand = sma - (properties.bollingerStdDev * stdDev)

        return Triple(upperBand, lowerBand, sma)
    }

    private fun calculateVolumeRatio(candles: List<Candle>): Double {
        if (candles.size < 21) return 1.0

        val currentVolume = candles.last().volume.toDouble()
        val avgVolume = candles.takeLast(21).dropLast(1).map { it.volume.toDouble() }.average()

        return if (avgVolume > 0) {
            currentVolume / avgVolume
        } else 1.0
    }

    private fun isInProfit(price: Double, position: Double): Boolean {
        // TODO: 진입 가격 추적 필요
        return false
    }

    private fun isInLoss(price: Double, position: Double): Boolean {
        // TODO: 진입 가격 추적 필요
        return false
    }

    private fun isCooldownActive(market: String): Boolean {
        val cooldown = signalCooldowns[market] ?: return false
        val elapsed = Instant.now().epochSecond - cooldown.epochSecond
        return elapsed < (properties.cooldownMinutes * 60)
    }

    private fun updateCooldown(market: String) {
        signalCooldowns[market] = Instant.now()
    }

    private fun createBuySignal(market: String, price: BigDecimal, volumeRatio: Double): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = 80.0,
            price = price,
            reason = "Breakout 상승: 볼린저 상단 돌파 (거래량 ${String.format("%.1f", volumeRatio)}x)",
            strategy = name
        )
    }

    private fun createSellSignal(market: String, price: BigDecimal, volumeRatio: Double): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 80.0,
            price = price,
            reason = "Breakout 하락: 볼린저 하단 돌파 (거래량 ${String.format("%.1f", volumeRatio)}x)",
            strategy = name
        )
    }

    private fun createHoldSignal(market: String, reason: String): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.HOLD,
            confidence = 0.0,
            price = BigDecimal.ZERO,
            reason = reason,
            strategy = name
        )
    }
}
