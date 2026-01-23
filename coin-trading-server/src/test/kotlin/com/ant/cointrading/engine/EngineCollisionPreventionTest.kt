package com.ant.cointrading.engine

import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertTrue

/**
 * 엔진 간 포지션 충돌 방지 테스트
 *
 * 테스트 시나리오:
 * 1. TradingEngine이 BTC 매수 시도 → VolumeSurge가 BTC 포지션 중이면 진입 차단
 * 2. VolumeSurge가 ETH 매수 시도 → MemeScalper가 ETH 포지션 중이면 진입 차단
 * 3. MemeScalper가 XRP 매수 시도 → TradingEngine이 XRP 포지션 중이면 진입 차단
 */
class EngineCollisionPreventionTest {

    private lateinit var globalPositionManager: GlobalPositionManager
    private lateinit var tradeRepository: TradeRepository
    private lateinit var volumeSurgeRepository: VolumeSurgeTradeRepository
    private lateinit var memeScalperRepository: MemeScalperTradeRepository

    @BeforeEach
    fun setUp() {
        tradeRepository = mock()
        volumeSurgeRepository = mock()
        memeScalperRepository = mock()

        globalPositionManager = GlobalPositionManager(
            tradeRepository,
            volumeSurgeRepository,
            memeScalperRepository
        )
    }

    @Test
    fun `TradingEngine은 VolumeSurge의 포지션을 확인하고 진입을 차단해야 한다`() {
        // Given: VolumeSurgeEngine이 BTC를 보유 중
        whenever(volumeSurgeRepository.findByMarketAndStatus(eq("KRW-BTC"), eq("OPEN")))
            .thenReturn(listOf(createVolumeSurgeTrade("KRW-BTC")))
        whenever(tradeRepository.findLastBuyByMarket(eq("KRW-BTC")))
            .thenReturn(null)

        // 캐시 초기화
        globalPositionManager.invalidateCache()

        // When: TradingEngine이 BTC 매수 시도
        val hasPosition = globalPositionManager.hasOpenPosition("KRW-BTC")

        // Then: 포지션이 있음을 반환
        assertTrue(
            hasPosition,
            "TradingEngine은 VolumeSurge의 BTC 포지션을 감지해야 함"
        )

        // 검증: 모든 Repository가 호출되었는지 확인
        verify(volumeSurgeRepository).findByMarketAndStatus(eq("KRW-BTC"), eq("OPEN"))
        verify(tradeRepository).findLastBuyByMarket(eq("KRW-BTC"))
    }

    @Test
    fun `VolumeSurgeEngine은 MemeScalper의 포지션을 확인하고 진입을 차단해야 한다`() {
        // Given: MemeScalperEngine이 ETH를 보유 중
        whenever(memeScalperRepository.findByMarketAndStatus(eq("KRW-ETH"), eq("OPEN")))
            .thenReturn(listOf(createMemeScalperTrade("KRW-ETH")))
        whenever(tradeRepository.findLastBuyByMarket(eq("KRW-ETH")))
            .thenReturn(null)

        globalPositionManager.invalidateCache()

        // When: VolumeSurgeEngine이 ETH 매수 시도
        val hasPosition = globalPositionManager.hasOpenPosition("KRW-ETH")

        // Then: 포지션이 있음을 반환
        assertTrue(
            hasPosition,
            "VolumeSurgeEngine은 MemeScalper의 ETH 포지션을 감지해야 함"
        )
    }

    @Test
    fun `MemeScalperEngine은 TradingEngine의 포지션을 확인하고 진입을 차단해야 한다`() {
        // Given: TradingEngine이 XRP를 보유 중
        whenever(tradeRepository.findLastBuyByMarket(eq("KRW-XRP")))
            .thenReturn(mock<TradeEntity>())
        whenever(volumeSurgeRepository.findByMarketAndStatus(eq("KRW-XRP"), eq("OPEN")))
            .thenReturn(emptyList())
        whenever(memeScalperRepository.findByMarketAndStatus(eq("KRW-XRP"), eq("OPEN")))
            .thenReturn(emptyList())

        globalPositionManager.invalidateCache()

        // When: MemeScalperEngine이 XRP 매수 시도
        val hasPosition = globalPositionManager.hasOpenPosition("KRW-XRP")

        // Then: 포지션이 있음을 반환
        assertTrue(
            hasPosition,
            "MemeScalperEngine은 TradingEngine의 XRP 포지션을 감지해야 함"
        )
    }

    @Test
    fun `포지션이 없으면 모든 엔진이 진입 가능해야 한다`() {
        // Given: 모든 엔진에 포지션 없음
        whenever(tradeRepository.findLastBuyByMarket(any())).thenReturn(null)
        whenever(volumeSurgeRepository.findByMarketAndStatus(any(), eq("OPEN"))).thenReturn(emptyList())
        whenever(memeScalperRepository.findByMarketAndStatus(any(), eq("OPEN"))).thenReturn(emptyList())

        globalPositionManager.invalidateCache()

        // When: 각 엔진이 포지션 확인
        val hasBtc = globalPositionManager.hasOpenPosition("KRW-BTC")
        val hasEth = globalPositionManager.hasOpenPosition("KRW-ETH")
        val hasXrp = globalPositionManager.hasOpenPosition("KRW-XRP")

        // Then: 모두 false
        kotlin.test.assertFalse(hasBtc, "BTC 포지션 없음")
        kotlin.test.assertFalse(hasEth, "ETH 포지션 없음")
        kotlin.test.assertFalse(hasXrp, "XRP 포지션 없음")
    }

    @Test
    fun `마켓명 정규화가 제대로 작동해야 한다`() {
        // Given: 다양한 형식의 마켓명
        val markets = listOf("KRW-BTC", "BTC_KRW", "KRW_BTC", "KRW-ETH", "ETH_KRW")

        // When: 정규화
        val normalized = markets.map { globalPositionManager.normalizeMarket(it) }

        // Then: 모두 KRW-XXX 형식으로 변환
        assertTrue(
            normalized.all { it.startsWith("KRW-") },
            "모든 마켓명이 KRW-XXX 형식으로 정규화되어야 함"
        )
    }

    @Test
    fun `캐시 무효화 후 다음 조회 시 DB를 다시 조회해야 한다`() {
        // Given: 처음 조회는 DB 호출
        whenever(tradeRepository.findLastBuyByMarket(eq("KRW-BTC")))
            .thenReturn(null)
        whenever(volumeSurgeRepository.findByMarketAndStatus(eq("KRW-BTC"), eq("OPEN")))
            .thenReturn(emptyList())
        whenever(memeScalperRepository.findByMarketAndStatus(eq("KRW-BTC"), eq("OPEN")))
            .thenReturn(emptyList())

        // When: 첫 번째 조회 (DB 호출)
        val firstResult = globalPositionManager.hasOpenPosition("KRW-BTC")

        // Then: DB가 호출됨
        verify(tradeRepository).findLastBuyByMarket(eq("KRW-BTC"))

        // Given: 캐시 TTL 이내에 두 번째 조회
        val secondResult = globalPositionManager.hasOpenPosition("KRW-BTC")

        // Then: DB가 호출되지 않음 (캐시 사용)
        verify(tradeRepository, org.mockito.Mockito.times(1)).findLastBuyByMarket(any())

        // Given: 캐시 무효화 후 세 번째 조회
        globalPositionManager.invalidateCache("KRW-BTC")
        val thirdResult = globalPositionManager.hasOpenPosition("KRW-BTC")

        // Then: DB가 다시 호출됨
        verify(tradeRepository, org.mockito.Mockito.times(2)).findLastBuyByMarket(eq("KRW-BTC"))
    }

    // Helper methods
    private fun createVolumeSurgeTrade(market: String): VolumeSurgeTradeEntity {
        return VolumeSurgeTradeEntity(
            market = market,
            entryPrice = 100000.0,
            quantity = 0.001,
            entryTime = Instant.now(),
            status = "OPEN",
            entryRsi = 50.0,
            appliedStopLossPercent = 3.0,
            stopLossMethod = "FIXED"
        )
    }

    private fun createMemeScalperTrade(market: String): MemeScalperTradeEntity {
        return MemeScalperTradeEntity(
            market = market,
            entryPrice = 3000.0,
            quantity = 0.33,
            entryTime = Instant.now(),
            status = "OPEN",
            entryRsi = 55.0,
            trailingActive = false
        )
    }
}
