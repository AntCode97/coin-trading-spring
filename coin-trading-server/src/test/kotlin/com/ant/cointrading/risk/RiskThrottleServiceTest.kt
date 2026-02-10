package com.ant.cointrading.risk

import com.ant.cointrading.config.RiskThrottleProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
@DisplayName("RiskThrottleService")
class RiskThrottleServiceTest {

    @Mock
    private lateinit var tradeRepository: TradeRepository

    @Test
    @DisplayName("표본이 부족하면 스로틀을 적용하지 않는다")
    fun returnsNoThrottleWhenSampleIsInsufficient() {
        val service = RiskThrottleService(
            tradeRepository = tradeRepository,
            tradingProperties = TradingProperties(
                riskThrottle = RiskThrottleProperties(
                    enabled = true,
                    lookbackTrades = 10,
                    minClosedTrades = 5
                )
            )
        )

        whenever(tradeRepository.findTop200ByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
            .thenReturn(
                listOf(
                    trade(strategy = "DCA", pnlPercent = -0.4),
                    trade(strategy = "DCA", pnlPercent = 0.3),
                    trade(strategy = "DCA", pnlPercent = -0.1)
                )
            )

        val decision = service.getDecision("KRW-BTC", "DCA", forceRefresh = true)

        assertEquals(1.0, decision.multiplier)
        assertEquals("INSUFFICIENT_DATA", decision.severity)
        assertFalse(decision.blockNewBuys)
        assertEquals(0, decision.recentConsecutiveLosses)
        assertTrue(decision.reason.contains("샘플 부족"))
        assertFalse(decision.cached)
    }

    @Test
    @DisplayName("성과 둔화 구간에서는 약한 스로틀을 적용한다")
    fun appliesWeakThrottle() {
        val service = RiskThrottleService(
            tradeRepository = tradeRepository,
            tradingProperties = TradingProperties(
                riskThrottle = RiskThrottleProperties(
                    enabled = true,
                    lookbackTrades = 8,
                    minClosedTrades = 8,
                    weakWinRate = 0.5,
                    weakAvgPnlPercent = -0.2,
                    weakMultiplier = 0.7,
                    criticalWinRate = 0.25,
                    criticalAvgPnlPercent = -1.2,
                    criticalMultiplier = 0.4
                )
            )
        )

        whenever(tradeRepository.findTop200ByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
            .thenReturn(
                listOf(
                    trade(strategy = "GRID", pnlPercent = 0.3),
                    trade(strategy = "GRID", pnlPercent = -0.5),
                    trade(strategy = "GRID", pnlPercent = -0.4),
                    trade(strategy = "GRID", pnlPercent = 0.2),
                    trade(strategy = "GRID", pnlPercent = -0.1),
                    trade(strategy = "GRID", pnlPercent = -0.4),
                    trade(strategy = "GRID", pnlPercent = 0.1),
                    trade(strategy = "GRID", pnlPercent = -0.6)
                )
            )

        val decision = service.getDecision("KRW-BTC", "GRID", forceRefresh = true)

        assertEquals(0.7, decision.multiplier)
        assertEquals("WEAK", decision.severity)
        assertFalse(decision.blockNewBuys)
        assertEquals(0, decision.recentConsecutiveLosses)
        assertTrue(decision.reason.contains("완화 축소"))
    }

    @Test
    @DisplayName("성과 붕괴 구간에서는 강한 스로틀을 적용한다")
    fun appliesCriticalThrottle() {
        val service = RiskThrottleService(
            tradeRepository = tradeRepository,
            tradingProperties = TradingProperties(
                riskThrottle = RiskThrottleProperties(
                    enabled = true,
                    lookbackTrades = 8,
                    minClosedTrades = 8,
                    weakWinRate = 0.45,
                    weakAvgPnlPercent = -0.2,
                    weakMultiplier = 0.7,
                    criticalWinRate = 0.35,
                    criticalAvgPnlPercent = -0.8,
                    criticalMultiplier = 0.45
                )
            )
        )

        whenever(tradeRepository.findTop200ByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
            .thenReturn(
                listOf(
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -1.2),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -0.9),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -1.1),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = 0.1),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -0.8),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -1.0),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -0.7),
                    trade(strategy = "MEAN_REVERSION", pnlPercent = -1.3)
                )
            )

        val decision = service.getDecision("KRW-BTC", "MEAN_REVERSION", forceRefresh = true)

        assertEquals(0.45, decision.multiplier)
        assertEquals("CRITICAL", decision.severity)
        assertTrue(decision.blockNewBuys)
        assertEquals(3, decision.recentConsecutiveLosses)
        assertTrue(decision.reason.contains("강한 축소"))
    }

    @Test
    @DisplayName("연속 손실이 임계치를 넘으면 즉시 CRITICAL 처리된다")
    fun turnsCriticalWhenConsecutiveLossesHigh() {
        val service = RiskThrottleService(
            tradeRepository = tradeRepository,
            tradingProperties = TradingProperties(
                riskThrottle = RiskThrottleProperties(
                    enabled = true,
                    lookbackTrades = 8,
                    minClosedTrades = 8,
                    weakWinRate = 0.2,
                    weakAvgPnlPercent = -2.0,
                    weakMultiplier = 0.8,
                    criticalWinRate = 0.1,
                    criticalAvgPnlPercent = -3.0,
                    criticalConsecutiveLosses = 4,
                    criticalMultiplier = 0.45
                )
            )
        )

        whenever(tradeRepository.findTop200ByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
            .thenReturn(
                listOf(
                    trade(strategy = "GRID", pnlPercent = -0.4),
                    trade(strategy = "GRID", pnlPercent = -0.3),
                    trade(strategy = "GRID", pnlPercent = -0.2),
                    trade(strategy = "GRID", pnlPercent = -0.6),
                    trade(strategy = "GRID", pnlPercent = 0.5),
                    trade(strategy = "GRID", pnlPercent = 0.4),
                    trade(strategy = "GRID", pnlPercent = 0.2),
                    trade(strategy = "GRID", pnlPercent = -0.1)
                )
            )

        val decision = service.getDecision("KRW-BTC", "GRID", forceRefresh = true)

        assertEquals("CRITICAL", decision.severity)
        assertTrue(decision.blockNewBuys)
        assertEquals(4, decision.recentConsecutiveLosses)
    }

    @Test
    @DisplayName("비활성화 시 항상 기본 크기를 반환한다")
    fun returnsBaselineWhenDisabled() {
        val service = RiskThrottleService(
            tradeRepository = tradeRepository,
            tradingProperties = TradingProperties(
                riskThrottle = RiskThrottleProperties(enabled = false)
            )
        )

        val decision = service.getDecision("KRW-BTC", "DCA")

        assertEquals(1.0, decision.multiplier)
        assertEquals("DISABLED", decision.severity)
        assertFalse(decision.blockNewBuys)
        assertEquals(0, decision.recentConsecutiveLosses)
        assertFalse(decision.enabled)
    }

    private fun trade(strategy: String, pnlPercent: Double): TradeEntity {
        return TradeEntity(
            orderId = "test-${strategy}-${pnlPercent}",
            market = "KRW-BTC",
            side = "SELL",
            type = "MARKET",
            price = 100_000.0,
            quantity = 0.01,
            totalAmount = 1_000.0,
            fee = 0.4,
            pnl = 0.0,
            pnlPercent = pnlPercent,
            strategy = strategy,
            confidence = 70.0,
            reason = "test",
            simulated = false
        )
    }
}
