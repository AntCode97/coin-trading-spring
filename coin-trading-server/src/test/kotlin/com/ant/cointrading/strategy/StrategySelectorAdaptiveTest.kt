package com.ant.cointrading.strategy

import com.ant.cointrading.config.BreakoutProperties
import com.ant.cointrading.config.StrategyConfig
import com.ant.cointrading.config.StrategyType
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.VolatilitySurvivalProperties
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.service.KeyValueService
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

@DisplayName("Strategy Selector Adaptive Mapping")
class StrategySelectorAdaptiveTest {

    @Test
    @DisplayName("횡보+고변동 구간은 VolatilitySurvival을 선택한다")
    fun shouldSelectVolatilitySurvivalForHighVolSideways() {
        val selector = createSelector()
        val selected = confirmRegime(
            selector = selector,
            market = "KRW-BTC",
            regime = regime(
                regime = MarketRegime.SIDEWAYS,
                confidence = 78.0,
                adx = 18.0,
                atrPercent = 2.9,
                trendDirection = 0
            )
        )

        assertEquals("VOLATILITY_SURVIVAL", selected.name)
    }

    @Test
    @DisplayName("저변동 하락 추세에서는 MeanReversion으로 방어적 운용")
    fun shouldSelectMeanReversionForLowVolBearTrend() {
        val selector = createSelector()
        val selected = confirmRegime(
            selector = selector,
            market = "KRW-ETH",
            regime = regime(
                regime = MarketRegime.BEAR_TREND,
                confidence = 72.0,
                adx = 26.0,
                atrPercent = 1.1,
                trendDirection = -1
            )
        )

        assertEquals("MEAN_REVERSION", selected.name)
    }

    @Test
    @DisplayName("강한 상승 추세에서는 EnhancedBreakout을 우선 선택한다")
    fun shouldPreferEnhancedBreakoutInStrongBullTrend() {
        val selector = createSelector()
        val selected = confirmRegime(
            selector = selector,
            market = "KRW-SOL",
            regime = regime(
                regime = MarketRegime.BULL_TREND,
                confidence = 82.0,
                adx = 34.0,
                atrPercent = 1.9,
                trendDirection = 1
            )
        )

        assertEquals("BREAKOUT_ENHANCED", selected.name)
    }

    @Test
    @DisplayName("신뢰도 낮아도 ATR이 높은 경우 보수적으로 VolatilitySurvival 선택")
    fun shouldFallbackToVolatilitySurvivalWhenConfidenceLowButAtrHigh() {
        val selector = createSelector()
        val selected = confirmRegime(
            selector = selector,
            market = "KRW-XRP",
            regime = regime(
                regime = MarketRegime.SIDEWAYS,
                confidence = 42.0,
                adx = 17.0,
                atrPercent = 3.1,
                trendDirection = 0
            )
        )

        assertEquals("VOLATILITY_SURVIVAL", selected.name)
    }

    private fun createSelector(defaultStrategy: StrategyType = StrategyType.MEAN_REVERSION): StrategySelector {
        val tradingProperties = TradingProperties(
            enabled = true,
            markets = listOf("KRW-BTC"),
            orderAmountKrw = BigDecimal("10000"),
            strategy = StrategyConfig(type = defaultStrategy)
        )

        val keyValueService = mockk<KeyValueService>(relaxed = true)
        val dcaRepository = mockk<DcaPositionRepository>(relaxed = true)
        val marketConditionChecker = mockk<MarketConditionChecker>(relaxed = true)

        val dca = DcaStrategy(tradingProperties, keyValueService, dcaRepository)
        val grid = GridStrategy(tradingProperties, keyValueService)
        val meanReversion = MeanReversionStrategy(tradingProperties)
        val orderBook = OrderBookImbalanceStrategy(
            tradingProperties = tradingProperties,
            bithumbPublicApi = mockk(relaxed = true),
            marketConditionChecker = marketConditionChecker
        )
        val breakoutProperties = BreakoutProperties()
        val breakout = BreakoutStrategy(breakoutProperties)
        val enhancedBreakout = EnhancedBreakoutStrategy(breakoutProperties)
        val volatility = VolatilitySurvivalStrategy(VolatilitySurvivalProperties())

        return StrategySelector(
            tradingProperties = tradingProperties,
            dcaStrategy = dca,
            gridStrategy = grid,
            meanReversionStrategy = meanReversion,
            orderBookImbalanceStrategy = orderBook,
            breakoutStrategy = breakout,
            enhancedBreakoutStrategy = enhancedBreakout,
            volatilitySurvivalStrategy = volatility
        )
    }

    private fun confirmRegime(
        selector: StrategySelector,
        market: String,
        regime: RegimeAnalysis
    ): TradingStrategy {
        repeat(2) {
            selector.selectStrategy(regime, market)
        }
        return selector.selectStrategy(regime, market)
    }

    private fun regime(
        regime: MarketRegime,
        confidence: Double,
        adx: Double,
        atrPercent: Double,
        trendDirection: Int
    ): RegimeAnalysis {
        return RegimeAnalysis(
            regime = regime,
            confidence = confidence,
            adx = adx,
            atr = atrPercent,
            atrPercent = atrPercent,
            trendDirection = trendDirection
        )
    }
}
