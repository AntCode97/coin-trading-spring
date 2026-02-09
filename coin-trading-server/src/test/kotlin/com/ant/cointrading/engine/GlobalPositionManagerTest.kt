package com.ant.cointrading.engine

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlobalPositionManager")
class GlobalPositionManagerTest {

    @Mock
    private lateinit var tradingProperties: TradingProperties

    @Mock
    private lateinit var tradeRepository: TradeRepository

    @Mock
    private lateinit var volumeSurgeRepository: VolumeSurgeTradeRepository

    @Mock
    private lateinit var memeScalperRepository: MemeScalperTradeRepository

    private lateinit var manager: GlobalPositionManager

    @BeforeEach
    fun setup() {
        whenever(tradingProperties.markets).thenReturn(listOf("BTC_KRW", "ETH_KRW"))
        whenever(volumeSurgeRepository.findByMarketAndStatus(any(), any())).thenReturn(emptyList())
        whenever(memeScalperRepository.findByMarketAndStatus(any(), any())).thenReturn(emptyList())
        whenever(volumeSurgeRepository.countByStatus("OPEN")).thenReturn(0L)
        whenever(memeScalperRepository.countByStatus("OPEN")).thenReturn(0)

        manager = GlobalPositionManager(
            tradingProperties,
            tradeRepository,
            volumeSurgeRepository,
            memeScalperRepository
        )
    }

    @Nested
    @DisplayName("Trading Position Detection")
    inner class TradingPositionDetection {

        @Test
        @DisplayName("Latest SELL means no open trading position")
        fun latestSellMeansClosed() {
            whenever(tradeRepository.findTopByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
                .thenReturn(trade(side = "SELL"))

            assertFalse(manager.hasOpenPosition("BTC_KRW"))
        }

        @Test
        @DisplayName("Latest BUY means open trading position")
        fun latestBuyMeansOpen() {
            whenever(tradeRepository.findTopByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
                .thenReturn(trade(side = "BUY"))

            assertTrue(manager.hasOpenPosition("BTC_KRW"))
        }
    }

    @Test
    @DisplayName("Total open positions uses configured markets")
    fun totalOpenPositionsUsesConfiguredMarkets() {
        whenever(volumeSurgeRepository.countByStatus("OPEN")).thenReturn(1L)
        whenever(memeScalperRepository.countByStatus("OPEN")).thenReturn(2)
        whenever(tradeRepository.findTopByMarketAndSimulatedOrderByCreatedAtDesc("KRW-BTC", false))
            .thenReturn(trade(market = "KRW-BTC", side = "BUY"))
        whenever(tradeRepository.findTopByMarketAndSimulatedOrderByCreatedAtDesc("KRW-ETH", false))
            .thenReturn(trade(market = "KRW-ETH", side = "SELL"))

        val total = manager.getTotalOpenPositions()

        assertEquals(4, total)
    }

    private fun trade(market: String = "KRW-BTC", side: String): TradeEntity {
        return TradeEntity(
            orderId = "order-${side.lowercase()}",
            market = market,
            side = side,
            type = "MARKET",
            price = 100.0,
            quantity = 1.0,
            totalAmount = 100.0,
            fee = 0.04,
            strategy = "TEST",
            confidence = 80.0,
            reason = "test",
            simulated = false,
            createdAt = Instant.now()
        )
    }
}
