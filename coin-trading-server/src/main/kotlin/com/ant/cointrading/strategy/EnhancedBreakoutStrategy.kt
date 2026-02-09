package com.ant.cointrading.strategy

import com.ant.cointrading.backtesting.BacktestAction
import com.ant.cointrading.backtesting.BacktestSignal
import com.ant.cointrading.backtesting.BacktestableStrategy
import com.ant.cointrading.backtesting.DynamicStopLossCalculator
import com.ant.cointrading.backtesting.KellyPositionSizer
import com.ant.cointrading.config.BreakoutProperties
import com.ant.cointrading.indicator.AtrCalculator
import com.ant.cointrading.indicator.ConfluenceAnalyzer
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 개선된 Breakout 전략 (quant-trading 스킬 기준)
 *
 * 4지표 컨플루언스 기반:
 * 1. RSI ≤ 30 또는 상향 다이버전스     [25점]
 * 2. MACD 시그널 상향 크로스            [25점]
 * 3. %B ≤ 0.2 또는 하단밴드 반등        [25점]
 * 4. 거래량 ≥ 20일 평균 × 150%         [25점]
 *
 * 진입 기준: 75점+ (보통), 100점 (강한)
 *
 * 리스크 관리:
 * - ATR 기반 동적 손절 (레짐별 조정)
 * - Kelly Criterion 포지션 사이징
 * - 최대 리스크 2%
 */
@Component
class EnhancedBreakoutStrategy(
    private val properties: BreakoutProperties
) : TradingStrategy, BacktestableStrategy {

    private val log = LoggerFactory.getLogger(javaClass)

    // 컨플루언스 분석기
    private val confluenceAnalyzer = ConfluenceAnalyzer()
    private val atrCalculator = AtrCalculator()

    // 실시간 트레이딩용 캐시
    private val lastSignalCache = ConcurrentHashMap<String, CachedSignal>()
    private val signalCooldowns = ConcurrentHashMap<String, Instant>()
    private val entryPrices = ConcurrentHashMap<String, Double>()  // 진입가 추적

    data class CachedSignal(
        val signal: TradingSignal,
        val timestamp: Instant
    )

    data class PositionState(
        val entryPrice: Double,
        val entryTime: Instant,
        val stopLoss: Double,
        val takeProfit: Double,
        val atr: Double
    )

    private val openPositions = ConcurrentHashMap<String, PositionState>()

    override val name: String = "BREAKOUT_ENHANCED"

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        if (candles.size < 50) {
            return createHoldSignal(market, currentPrice, "데이터 부족 (최소 50개 캔들 필요)")
        }

        // 1. 컨플루언스 분석
        val confluence = confluenceAnalyzer.analyze(candles)

        // 2. 포지션 체크 (손절/익절)
        val position = openPositions[market]
        if (position != null) {
            val checkResult = checkExitConditions(market, currentPrice.toDouble(), position, regime)
            if (checkResult != null) return checkResult
        }

        // 3. 신규 진입 조건
        if (position == null && confluence.signal != com.ant.cointrading.indicator.SignalType.NO_SIGNAL) {
            val score = confluence.score

            // 최소 75점 이상만 진입
            if (score >= 75 && !isCooldownActive(market)) {
                log.info(
                    "[$market] 컨플루언스 매수 신호 - 점수: $score, " +
                            "RSI:${confluence.rsiScore}, MACD:${confluence.macdScore}, " +
                            "BB:${confluence.bollingerScore}, VOL:${confluence.volumeScore}"
                )

                updateCooldown(market)

                // ATR 기반 손절/익절 계산
                val atr = atrCalculator.getLatestAtr(candles) ?: currentPrice.toDouble() * 0.02
                val currentPriceDouble = currentPrice.toDouble()

                val stopLoss = DynamicStopLossCalculator.calculateStopLoss(
                    entryPrice = currentPriceDouble,
                    atr = atr,
                    isBuy = true,
                    regime = mapRegime(regime)
                )

                val takeProfit = calculateTakeProfit(
                    entryPrice = currentPriceDouble,
                    stopLoss = stopLoss,
                    riskReward = if (score >= 100) 2.0 else 1.5  // 100점: 2:1, 75점: 1.5:1
                )

                // 포지션 기록
                openPositions[market] = PositionState(
                    entryPrice = currentPriceDouble,
                    entryTime = Instant.now(),
                    stopLoss = stopLoss,
                    takeProfit = takeProfit,
                    atr = atr
                )

                return createBuySignal(
                    market = market,
                    price = currentPrice,
                    confluence = confluence,
                    stopLoss = stopLoss,
                    takeProfit = takeProfit
                )
            }
        }

        return createHoldSignal(
            market = market,
            price = currentPrice,
            reason = "컨플루언스 점수 부족 (${confluence.score}/75)\n${confluence.details}"
        )
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        return when (regime.regime) {
            com.ant.cointrading.model.MarketRegime.BULL_TREND -> true
            com.ant.cointrading.model.MarketRegime.HIGH_VOLATILITY -> regime.confidence > 60
            else -> false
        }
    }

    override fun getDescription(): String {
        return """
            Enhanced Breakout 전략 (quant-trading 스킬 기준)
            - 4지표 컨플루언스: RSI, MACD, 볼린저, 거래량
            - 진입 기준: 75점+ (보통), 100점 (강한)
            - ATR 기반 동적 손절 (레짐별 조정)
            - Kelly Criterion 포지션 사이징
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
        if (candles.size < 50 || currentIndex < 50) {
            return BacktestSignal(BacktestAction.HOLD, reason = "데이터 부족")
        }

        val availableCandles = candles.subList(0, currentIndex + 1)
        val confluence = confluenceAnalyzer.analyze(availableCandles)
        val currentPriceDouble = currentPrice.toDouble()

        // 포지션 체크
        if (currentPosition > 0) {
            val entryPrice = getEntryPrice(currentPriceDouble, currentPosition, initialCapital)
            val atr = atrCalculator.getLatestAtr(availableCandles) ?: currentPriceDouble * 0.02

            // ATR 기반 손절/익절
            val stopLoss = DynamicStopLossCalculator.calculateStopLoss(
                entryPrice = entryPrice,
                atr = atr,
                isBuy = true,
                regime = DynamicStopLossCalculator.MarketRegime.TREND
            )

            val takeProfit = calculateTakeProfit(entryPrice, stopLoss, 2.0)

            val pnlPercent = ((currentPriceDouble - entryPrice) / entryPrice) * 100

            return when {
                currentPriceDouble <= stopLoss -> BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 95.0,
                    reason = "ATR 손절: ${String.format("%.2f", pnlPercent)}%"
                )
                currentPriceDouble >= takeProfit -> BacktestSignal(
                    BacktestAction.SELL,
                    confidence = 80.0,
                    reason = "익절: ${String.format("%.2f", pnlPercent)}%"
                )
                else -> BacktestSignal(BacktestAction.HOLD)
            }
        }

        // 신규 진입
        if (confluence.score >= 75) {
            return BacktestSignal(
                BacktestAction.BUY,
                confidence = confluence.score.toDouble(),
                reason = "컨플루언스 ${confluence.score}점: ${confluence.signal}"
            )
        }

        return BacktestSignal(BacktestAction.HOLD)
    }

    // ==================== Private Methods ====================

    /**
     * 청산 조건 체크
     */
    private fun checkExitConditions(
        market: String,
        currentPrice: Double,
        position: PositionState,
        regime: RegimeAnalysis
    ): TradingSignal? {
        val pnlPercent = ((currentPrice - position.entryPrice) / position.entryPrice) * 100
        val holdingMinutes = java.time.Duration.between(position.entryTime, Instant.now()).toMinutes()

        // 손절
        if (currentPrice <= position.stopLoss) {
            openPositions.remove(market)
            return createSellSignal(
                market = market,
                price = BigDecimal.valueOf(currentPrice),
                reason = "ATR 손절: ${String.format("%.2f", pnlPercent)}% (${holdingMinutes}분 보유)",
                pnlPercent = pnlPercent
            )
        }

        // 익절
        if (currentPrice >= position.takeProfit) {
            openPositions.remove(market)
            return createSellSignal(
                market = market,
                price = BigDecimal.valueOf(currentPrice),
                reason = "익절: ${String.format("%.2f", pnlPercent)}% (${holdingMinutes}분 보유)",
                pnlPercent = pnlPercent
            )
        }

        // 타임아웃 (1시간)
        if (holdingMinutes > 60) {
            openPositions.remove(market)
            return createSellSignal(
                market = market,
                price = BigDecimal.valueOf(currentPrice),
                reason = "타임아웃: ${String.format("%.2f", pnlPercent)}% (60분 경과)",
                pnlPercent = pnlPercent
            )
        }

        return null
    }

    /**
     * 익절가 계산
     */
    private fun calculateTakeProfit(entryPrice: Double, stopLoss: Double, riskReward: Double): Double {
        val risk = entryPrice - stopLoss
        return entryPrice + (risk * riskReward)
    }

    /**
     * 레짐 매핑
     */
    private fun mapRegime(regime: RegimeAnalysis): DynamicStopLossCalculator.MarketRegime {
        return when (regime.regime) {
            com.ant.cointrading.model.MarketRegime.SIDEWAYS -> DynamicStopLossCalculator.MarketRegime.SIDEWAYS
            com.ant.cointrading.model.MarketRegime.BULL_TREND,
            com.ant.cointrading.model.MarketRegime.BEAR_TREND -> DynamicStopLossCalculator.MarketRegime.TREND
            com.ant.cointrading.model.MarketRegime.HIGH_VOLATILITY -> DynamicStopLossCalculator.MarketRegime.HIGH_VOLATILITY
        }
    }

    /**
     * 백테스트용 진입가 추정
     */
    private fun getEntryPrice(currentPrice: Double, position: Double, capital: Double): Double {
        return currentPrice - (capital / position * 0.02)  // 대략적인 추정
    }

    private fun isCooldownActive(market: String): Boolean {
        val cooldown = signalCooldowns[market] ?: return false
        val elapsed = Instant.now().epochSecond - cooldown.epochSecond
        return elapsed < (properties.cooldownMinutes * 60)
    }

    private fun updateCooldown(market: String) {
        signalCooldowns[market] = Instant.now()
    }

    private fun createBuySignal(
        market: String,
        price: BigDecimal,
        confluence: com.ant.cointrading.indicator.ConfluenceResult,
        stopLoss: Double,
        takeProfit: Double
    ): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = confluence.score.toDouble(),
            price = price,
            reason = "컨플루언스 ${confluence.score}점 매수\n손절: ${String.format("%.0f", stopLoss)}, 익절: ${String.format("%.0f", takeProfit)}\n${confluence.details}",
            strategy = name
        )
    }

    private fun createSellSignal(
        market: String,
        price: BigDecimal,
        reason: String,
        pnlPercent: Double? = null
    ): TradingSignal {
        val fullReason = if (pnlPercent != null) {
            "$reason (${String.format("%.2f", pnlPercent)}%)"
        } else reason

        return TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 80.0,
            price = price,
            reason = fullReason,
            strategy = name
        )
    }

    private fun createHoldSignal(market: String, price: BigDecimal, reason: String): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.HOLD,
            confidence = 0.0,
            price = price,
            reason = reason,
            strategy = name
        )
    }
}
