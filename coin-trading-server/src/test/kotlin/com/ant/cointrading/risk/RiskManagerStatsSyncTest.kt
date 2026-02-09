package com.ant.cointrading.risk

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RiskManager Stats Sync Tests")
class RiskManagerStatsSyncTest {

    @Mock
    private lateinit var tradeRepository: TradeRepository

    private lateinit var riskManager: RiskManager

    private val market = "KRW-BTC"
    private val balance = BigDecimal("1000000")

    @BeforeEach
    fun setup() {
        riskManager = RiskManager(TradingProperties(), tradeRepository)
        whenever(tradeRepository.findByMarketAndSimulatedAndCreatedAtAfter(eq(market), eq(false), any()))
            .thenReturn(emptyList())
    }

    @Test
    @DisplayName("Kelly 통계는 실현 SELL 거래(pnl 존재)만 반영해야 한다")
    fun shouldUseOnlyRealizedSellTradesForStats() {
        val now = Instant.now()
        whenever(tradeRepository.findByMarketAndSimulatedOrderByCreatedAtDesc(market, false))
            .thenReturn(
                listOf(
                    trade(orderId = "buy-1", side = "BUY", pnl = null, createdAt = now),
                    trade(orderId = "sell-loss", side = "SELL", pnl = -100.0, createdAt = now.minusSeconds(10)),
                    trade(orderId = "sell-win", side = "SELL", pnl = 200.0, createdAt = now.minusSeconds(20)),
                    trade(orderId = "buy-2", side = "BUY", pnl = null, createdAt = now.minusSeconds(30))
                )
            )

        val stats = riskManager.getRiskStats(market, balance)

        assertEquals(2, stats.totalTrades, "BUY/null pnl 거래는 통계에서 제외되어야 함")
        assertEquals(50.0, stats.winRate, 0.001)
        assertEquals(0, stats.avgWin.compareTo(BigDecimal("200.0")))
        assertEquals(0, stats.avgLoss.compareTo(BigDecimal("100.0")))
        assertEquals(2.0, stats.profitFactor, 0.001)
    }

    @Test
    @DisplayName("최신 SELL 실현손익 기준으로 연속 손실을 계산해야 한다")
    fun shouldBlockTradeWhenRecentConsecutiveLossesReachThreshold() {
        val now = Instant.now()
        whenever(tradeRepository.findByMarketAndSimulatedOrderByCreatedAtDesc(market, false))
            .thenReturn(
                listOf(
                    trade(orderId = "sell-loss-1", side = "SELL", pnl = -30.0, createdAt = now),
                    trade(orderId = "buy-ignored", side = "BUY", pnl = null, createdAt = now.minusSeconds(5)),
                    trade(orderId = "sell-loss-2", side = "SELL", pnl = -20.0, createdAt = now.minusSeconds(10)),
                    trade(orderId = "sell-loss-3", side = "SELL", pnl = -10.0, createdAt = now.minusSeconds(15)),
                    trade(orderId = "sell-win-old", side = "SELL", pnl = 40.0, createdAt = now.minusSeconds(20))
                )
            )

        val check = riskManager.canTrade(market, balance)

        assertFalse(check.canTrade)
        assertTrue(check.reason.contains("연속 손실 3회"))
    }

    @Test
    @DisplayName("refreshStats 호출 시 캐시를 즉시 최신 DB 상태로 갱신해야 한다")
    fun shouldRefreshCachedStatsImmediately() {
        val now = Instant.now()
        var snapshot = listOf(
            trade(orderId = "sell-win", side = "SELL", pnl = 100.0, createdAt = now)
        )
        whenever(tradeRepository.findByMarketAndSimulatedOrderByCreatedAtDesc(eq(market), eq(false)))
            .thenAnswer { snapshot }

        val initial = riskManager.getRiskStats(market, balance)
        assertEquals(1, initial.totalTrades)
        assertEquals(100.0, initial.winRate, 0.001)

        // 캐시 TTL(30초) 이전에는 getRiskStats만으로는 갱신되지 않음
        snapshot = listOf(
            trade(orderId = "sell-loss-new", side = "SELL", pnl = -50.0, createdAt = now.plusSeconds(1)),
            trade(orderId = "sell-win-old", side = "SELL", pnl = 100.0, createdAt = now)
        )
        val cached = riskManager.getRiskStats(market, balance)
        assertEquals(1, cached.totalTrades)

        riskManager.refreshStats(market)
        val refreshed = riskManager.getRiskStats(market, balance)
        assertEquals(2, refreshed.totalTrades)
        assertEquals(50.0, refreshed.winRate, 0.001)
    }

    private fun trade(
        orderId: String,
        side: String,
        pnl: Double?,
        createdAt: Instant
    ): TradeEntity {
        return TradeEntity(
            orderId = orderId,
            market = market,
            side = side,
            type = "MARKET",
            price = 1_000.0,
            quantity = 1.0,
            totalAmount = 1_000.0,
            fee = 0.4,
            strategy = "TEST",
            confidence = 80.0,
            reason = "test",
            simulated = false,
            pnl = pnl,
            createdAt = createdAt
        )
    }
}
