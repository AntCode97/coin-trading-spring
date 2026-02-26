package com.ant.cointrading.memescalper

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.OrderbookInfo
import com.ant.cointrading.api.bithumb.OrderbookUnit
import com.ant.cointrading.config.MemeScalperProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemeScalperDetector Imbalance Persistence")
class MemeScalperDetectorImbalancePersistenceTest {

    @Mock
    private lateinit var bithumbPublicApi: BithumbPublicApi

    @Mock
    private lateinit var properties: MemeScalperProperties

    private lateinit var detector: MemeScalperDetector

    @BeforeEach
    fun setup() {
        detector = MemeScalperDetector(bithumbPublicApi, properties)

        whenever(properties.entryImbalancePersistenceEnabled).thenReturn(true)
        whenever(properties.entryImbalancePersistenceMin).thenReturn(0.5)
        whenever(properties.entryImbalanceDropThreshold).thenReturn(0.2)
        whenever(properties.entryImbalanceLookbackCandles).thenReturn(2)
    }

    @Test
    @DisplayName("진입 대비 Imbalance 급감이 0.2 이상이면 진입 취소")
    fun rejectWhenImbalanceDropsTooFast() {
        whenever(bithumbPublicApi.getOrderbook(MARKET))
            .thenReturn(listOf(orderbookWithImbalance(MARKET, 0.50)))

        val result = detector.validateEntryImbalancePersistence(signal(entryImbalance = 0.75))

        assertFalse(result.passed)
        assertEquals("IMBALANCE_DROP", result.reason)
        assertTrue(result.dropFromSignal >= 0.2)
    }

    @Test
    @DisplayName("평균 Imbalance가 기준 미만이면 진입 취소")
    fun rejectWhenAverageImbalanceTooLow() {
        whenever(bithumbPublicApi.getOrderbook(MARKET))
            .thenReturn(listOf(orderbookWithImbalance(MARKET, 0.44)))

        val result = detector.validateEntryImbalancePersistence(signal(entryImbalance = 0.55))

        assertFalse(result.passed)
        assertEquals("IMBALANCE_AVERAGE_LOW", result.reason)
        assertTrue(result.averageImbalance < 0.5)
        assertTrue(result.dropFromSignal < 0.2)
    }

    @Test
    @DisplayName("Imbalance가 유지되면 진입 허용")
    fun allowWhenImbalanceIsSustained() {
        whenever(bithumbPublicApi.getOrderbook(MARKET))
            .thenReturn(listOf(orderbookWithImbalance(MARKET, 0.58)))

        val result = detector.validateEntryImbalancePersistence(signal(entryImbalance = 0.63))

        assertTrue(result.passed)
        assertEquals(null, result.reason)
        assertTrue(result.averageImbalance >= 0.5)
        assertTrue(result.dropFromSignal < 0.2)
    }

    private fun signal(entryImbalance: Double): PumpSignal {
        return PumpSignal(
            market = MARKET,
            volumeSpikeRatio = 5.0,
            priceSpikePercent = 3.0,
            bidImbalance = entryImbalance,
            rsi = 60.0,
            macdSignal = "BULLISH",
            spreadPercent = 0.2,
            currentPrice = BigDecimal("1000"),
            tradingValue = BigDecimal("2000000000"),
            score = 80
        )
    }

    private fun orderbookWithImbalance(market: String, imbalance: Double): OrderbookInfo {
        val totalVolume = 100.0
        val bidVolume = ((imbalance + 1.0) / 2.0) * totalVolume
        val askVolume = totalVolume - bidVolume

        return OrderbookInfo(
            market = market,
            timestamp = System.currentTimeMillis(),
            totalAskSize = BigDecimal.valueOf(askVolume),
            totalBidSize = BigDecimal.valueOf(bidVolume),
            orderbookUnits = listOf(
                OrderbookUnit(
                    askPrice = BigDecimal("1001"),
                    bidPrice = BigDecimal("1000"),
                    askSize = BigDecimal.valueOf(askVolume),
                    bidSize = BigDecimal.valueOf(bidVolume)
                )
            )
        )
    }

    companion object {
        private const val MARKET = "KRW-TEST"
    }
}
