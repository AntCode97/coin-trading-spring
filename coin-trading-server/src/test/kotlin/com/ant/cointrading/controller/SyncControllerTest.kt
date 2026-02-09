package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.dca.DcaEngine
import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@DisplayName("SyncController")
class SyncControllerTest {

    @Mock
    private lateinit var bithumbPrivateApi: BithumbPrivateApi

    @Mock
    private lateinit var bithumbPublicApi: BithumbPublicApi

    @Mock
    private lateinit var memeScalperRepository: MemeScalperTradeRepository

    @Mock
    private lateinit var volumeSurgeRepository: VolumeSurgeTradeRepository

    @Mock
    private lateinit var dcaPositionRepository: DcaPositionRepository

    @Mock
    private lateinit var memeScalperEngine: MemeScalperEngine

    @Mock
    private lateinit var volumeSurgeEngine: VolumeSurgeEngine

    @Mock
    private lateinit var dcaEngine: DcaEngine

    private lateinit var syncController: SyncController

    @BeforeEach
    fun setUp() {
        syncController = SyncController(
            bithumbPrivateApi = bithumbPrivateApi,
            bithumbPublicApi = bithumbPublicApi,
            memeScalperRepository = memeScalperRepository,
            volumeSurgeRepository = volumeSurgeRepository,
            dcaPositionRepository = dcaPositionRepository,
            memeScalperEngine = memeScalperEngine,
            volumeSurgeEngine = volumeSurgeEngine,
            dcaEngine = dcaEngine
        )
    }

    @Test
    @DisplayName("locked 잔고를 포함한 총 잔고로 수량 검증한다")
    fun includesLockedBalanceInQuantityCheck() {
        val openPosition = MemeScalperTradeEntity(
            market = "KRW-BTC",
            quantity = 1.0,
            status = "OPEN"
        )

        whenever(bithumbPrivateApi.getBalances()).thenReturn(
            listOf(
                balance(currency = "BTC", available = "0.6", locked = "0.4"),
                balance(currency = "KRW", available = "1000000", locked = "0")
            )
        )
        whenever(memeScalperRepository.findByStatus("OPEN")).thenReturn(listOf(openPosition))
        whenever(volumeSurgeRepository.findByStatus("OPEN")).thenReturn(emptyList())
        whenever(dcaPositionRepository.findByStatus("OPEN")).thenReturn(emptyList())
        whenever(bithumbPrivateApi.getOrders("KRW-BTC", "done", 0, 500)).thenReturn(emptyList())

        val result = syncController.syncPositions()

        assertTrue(result.success)
        assertEquals(0, result.actions.count { it.action == "QUANTITY_MISMATCH" })
        assertEquals(1, result.verifiedCount)
        verify(memeScalperRepository, never()).save(any())
    }

    @Test
    @DisplayName("동일 코인을 여러 전략이 보유하면 합산 수량으로 검증한다")
    fun aggregatesQuantityAcrossStrategiesByCoin() {
        val memeOpen = MemeScalperTradeEntity(
            market = "KRW-BTC",
            quantity = 0.3,
            status = "OPEN"
        )
        val volumeOpen = VolumeSurgeTradeEntity(
            market = "KRW-BTC",
            quantity = 0.4,
            status = "OPEN"
        )

        whenever(bithumbPrivateApi.getBalances()).thenReturn(
            listOf(
                balance(currency = "BTC", available = "0.7", locked = "0")
            )
        )
        whenever(memeScalperRepository.findByStatus("OPEN")).thenReturn(listOf(memeOpen))
        whenever(volumeSurgeRepository.findByStatus("OPEN")).thenReturn(listOf(volumeOpen))
        whenever(dcaPositionRepository.findByStatus("OPEN")).thenReturn(emptyList())
        whenever(bithumbPrivateApi.getOrders("KRW-BTC", "done", 0, 500)).thenReturn(emptyList())

        val result = syncController.syncPositions()

        assertTrue(result.success)
        assertEquals(0, result.actions.count { it.action == "QUANTITY_MISMATCH" })
        assertEquals(2, result.verifiedCount)
    }

    @Test
    @DisplayName("실제 총 잔고가 DB 합산보다 작으면 수량 불일치를 1회만 기록한다")
    fun recordsSingleMismatchWhenAggregatedQuantityDiffers() {
        val memeOpen = MemeScalperTradeEntity(
            market = "KRW-BTC",
            quantity = 0.7,
            status = "OPEN"
        )
        val volumeOpen = VolumeSurgeTradeEntity(
            market = "KRW-BTC",
            quantity = 0.5,
            status = "OPEN"
        )

        whenever(bithumbPrivateApi.getBalances()).thenReturn(
            listOf(
                balance(currency = "BTC", available = "0.95", locked = "0")
            )
        )
        whenever(memeScalperRepository.findByStatus("OPEN")).thenReturn(listOf(memeOpen))
        whenever(volumeSurgeRepository.findByStatus("OPEN")).thenReturn(listOf(volumeOpen))
        whenever(dcaPositionRepository.findByStatus("OPEN")).thenReturn(emptyList())
        whenever(bithumbPrivateApi.getOrders("KRW-BTC", "done", 0, 500)).thenReturn(emptyList())

        val result = syncController.syncPositions()

        val mismatches = result.actions.filter { it.action == "QUANTITY_MISMATCH" }
        assertEquals(1, mismatches.size)
        assertEquals(1.2, mismatches.first().dbQuantity, 1e-9)
        assertEquals(0.95, mismatches.first().actualQuantity, 1e-9)
        assertTrue(mismatches.first().reason.contains("locked="))
    }

    private fun balance(currency: String, available: String, locked: String): Balance {
        return Balance(
            currency = currency,
            balance = BigDecimal(available),
            locked = BigDecimal(locked),
            avgBuyPrice = null,
            avgBuyPriceModified = false,
            unitCurrency = "KRW"
        )
    }
}
