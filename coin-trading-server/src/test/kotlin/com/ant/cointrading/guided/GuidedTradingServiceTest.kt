package com.ant.cointrading.guided

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.api.bithumb.MarketInfo
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.repository.GuidedTradeEntity
import com.ant.cointrading.repository.GuidedTradeEventRepository
import com.ant.cointrading.repository.GuidedTradeRepository
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@DisplayName("GuidedTradingService")
class GuidedTradingServiceTest {

    private val bithumbPublicApi: BithumbPublicApi = mock()
    private val bithumbPrivateApi: BithumbPrivateApi = mock()
    private val guidedTradeRepository: GuidedTradeRepository = mock()
    private val guidedTradeEventRepository: GuidedTradeEventRepository = mock()

    private lateinit var service: GuidedTradingService
    private lateinit var markets: List<MarketInfo>
    private lateinit var tickers: List<TickerInfo>

    @BeforeEach
    fun setUp() {
        service = GuidedTradingService(
            bithumbPublicApi = bithumbPublicApi,
            bithumbPrivateApi = bithumbPrivateApi,
            guidedTradeRepository = guidedTradeRepository,
            guidedTradeEventRepository = guidedTradeEventRepository
        )

        markets = (1..61).map { index ->
            val symbol = "C${index.toString().padStart(3, '0')}"
            MarketInfo(
                market = "KRW-$symbol",
                koreanName = "코인$index",
                englishName = "Coin$index",
                marketWarning = null
            )
        }

        tickers = markets.mapIndexed { index, market ->
            val rank = index + 1
            val turnover = (61 - index) * 1_000_000.0
            TickerInfo(
                market = market.market,
                tradePrice = BigDecimal.valueOf(1_000.0 + rank),
                openingPrice = BigDecimal.valueOf(995.0 + rank),
                highPrice = BigDecimal.valueOf(1_020.0 + rank),
                lowPrice = BigDecimal.valueOf(980.0 + rank),
                prevClosingPrice = BigDecimal.valueOf(994.0 + rank),
                change = if (rank % 2 == 0) "RISE" else "FALL",
                changePrice = BigDecimal.valueOf(5.0),
                changeRate = BigDecimal.valueOf(((rank % 10) + 1) / 100.0),
                tradeVolume = BigDecimal.valueOf(120.0 + rank),
                accTradeVolume = BigDecimal.valueOf(30_000.0 + rank * 100),
                accTradePrice = BigDecimal.valueOf(turnover),
                accTradePrice24h = BigDecimal.valueOf(turnover),
                accTradeVolume24h = BigDecimal.valueOf(35_000.0 + rank * 100),
                timestamp = 1_700_000_000_000L + rank,
                tradeDate = "2026-02-24"
            )
        }

        whenever(bithumbPublicApi.getMarketAll()).thenReturn(markets)
        whenever(bithumbPublicApi.getCurrentPrice(any())).thenReturn(tickers)
        whenever(bithumbPublicApi.getOhlcv(any(), any(), any(), anyOrNull())).thenAnswer { invocation ->
            val market = invocation.getArgument<String>(0)
            buildCandles(market)
        }
        whenever(
            guidedTradeRepository.findTop80ByMarketAndStatusOrderByCreatedAtDesc(
                any(),
                eq(GuidedTradeEntity.STATUS_CLOSED)
            )
        ).thenReturn(emptyList())
        whenever(
            guidedTradeRepository.findTop200ByStatusOrderByCreatedAtDesc(
                eq(GuidedTradeEntity.STATUS_CLOSED)
            )
        ).thenReturn(emptyList())
        whenever(guidedTradeRepository.save(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<GuidedTradeEntity>(0)
            if (entity.id == null) entity.id = 1L
            entity
        }
        whenever(guidedTradeEventRepository.save(any())).thenAnswer { invocation ->
            invocation.getArgument(0)
        }
    }

    @AfterEach
    fun tearDown() {
        service.shutdown()
    }

    @Test
    @DisplayName("추천가 승률 정렬은 거래대금 상위 60개만 계산하고 미계산 코인은 마지막으로 배치한다")
    fun recommendedWinRateSortComputesTop60Only() {
        val sorted = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute30",
            mode = TradingMode.SWING
        )

        assertEquals(61, sorted.size)
        assertEquals(60, sorted.count { it.recommendedEntryWinRate != null })
        assertTrue(sorted.take(60).all { it.recommendedEntryWinRate != null })
        assertEquals("KRW-C061", sorted.last().market)
        assertEquals(null, sorted.last().recommendedEntryWinRate)
        assertEquals(null, sorted.last().marketEntryWinRate)
    }

    @Test
    @DisplayName("현재가 승률 정렬은 ASC/DESC 모두 null을 마지막으로 유지한다")
    fun marketEntryWinRateSortSupportsAscDescAndNullLast() {
        val desc = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute30",
            mode = TradingMode.SWING
        )
        val asc = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.ASC,
            interval = "minute30",
            mode = TradingMode.SWING
        )

        val descValues = desc.mapNotNull { it.marketEntryWinRate }
        val ascValues = asc.mapNotNull { it.marketEntryWinRate }

        assertEquals(60, descValues.size)
        assertEquals(60, ascValues.size)
        assertTrue(descValues.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(ascValues.zipWithNext().all { (a, b) -> a <= b })
        assertEquals("KRW-C061", desc.last().market)
        assertEquals("KRW-C061", asc.last().market)
        assertEquals(null, desc.last().marketEntryWinRate)
        assertEquals(null, asc.last().marketEntryWinRate)
    }

    @Test
    @DisplayName("승률 정렬은 요청 interval과 고정 캔들 수(120)를 사용한다")
    fun winRateSortUsesRequestedIntervalAndFixedCandleCount() {
        service.getMarketBoard(
            sortBy = GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute10",
            mode = TradingMode.POSITION
        )

        verify(bithumbPublicApi, times(60))
            .getOhlcv(any(), eq("minute10"), eq(120), isNull())
    }

    @Test
    @DisplayName("승률 정렬은 mode에 따라 계산 결과가 달라진다")
    fun winRateSortReflectsMode() {
        val swing = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute30",
            mode = TradingMode.SWING
        )
        val position = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute30",
            mode = TradingMode.POSITION
        )

        val swingByMarket = swing.associateBy({ it.market }, { it.recommendedEntryWinRate })
        val positionByMarket = position.associateBy({ it.market }, { it.recommendedEntryWinRate })

        val anyChanged = swingByMarket.keys.any { market ->
            val swingValue = swingByMarket[market]
            val positionValue = positionByMarket[market]
            swingValue != null && positionValue != null && kotlin.math.abs(swingValue - positionValue) > 0.0001
        }
        assertTrue(anyChanged)
    }

    @Test
    @DisplayName("기존 정렬(TURNOVER)은 승률 계산을 호출하지 않는다")
    fun turnoverSortDoesNotComputeWinRates() {
        val sorted = service.getMarketBoard(
            sortBy = GuidedMarketSortBy.TURNOVER,
            sortDirection = GuidedSortDirection.DESC,
            interval = "minute30",
            mode = TradingMode.SWING
        )

        verify(bithumbPublicApi, never()).getOhlcv(any(), any(), any(), anyOrNull())
        assertTrue(sorted.all { it.recommendedEntryWinRate == null && it.marketEntryWinRate == null })
    }

    @Test
    @DisplayName("외부 포지션 편입은 신규 Guided 포지션을 생성한다")
    fun adoptExternalPositionCreatesGuidedTrade() {
        whenever(guidedTradeRepository.findTopByMarketAndStatusInOrderByCreatedAtDesc(eq("KRW-BTC"), any()))
            .thenReturn(null)
        whenever(bithumbPrivateApi.getBalances()).thenReturn(
            listOf(
                Balance(
                    currency = "BTC",
                    balance = BigDecimal("0.01000000"),
                    locked = BigDecimal.ZERO,
                    avgBuyPrice = null,
                    avgBuyPriceModified = null,
                    unitCurrency = "KRW"
                )
            )
        )
        whenever(bithumbPublicApi.getCurrentPrice(eq("KRW-BTC"))).thenReturn(
            listOf(
                TickerInfo(
                    market = "KRW-BTC",
                    tradePrice = BigDecimal("93000000"),
                    openingPrice = BigDecimal("92000000"),
                    highPrice = BigDecimal("94000000"),
                    lowPrice = BigDecimal("91500000"),
                    prevClosingPrice = BigDecimal("92500000"),
                    change = "RISE",
                    changePrice = BigDecimal("500000"),
                    changeRate = BigDecimal("0.01"),
                    tradeVolume = BigDecimal("1.0"),
                    accTradeVolume = BigDecimal("1000"),
                    accTradePrice = BigDecimal("1000000000"),
                    accTradePrice24h = BigDecimal("1000000000"),
                    accTradeVolume24h = BigDecimal("1000"),
                    timestamp = 1_700_000_000_000L,
                    tradeDate = "2026-02-24"
                )
            )
        )

        val adopted = service.adoptExternalPosition(
            GuidedAdoptPositionRequest(
                market = "KRW-BTC",
                mode = "SWING",
                interval = "minute30",
                entrySource = "MCP_DIRECT",
                notes = "test"
            )
        )

        assertTrue(adopted.adopted)
        assertEquals(1L, adopted.positionId)
        assertEquals(0.01, adopted.quantity, 0.0000001)
        verify(guidedTradeRepository, times(1)).save(any())
    }

    @Test
    @DisplayName("외부 포지션 편입은 기존 활성 포지션이 있으면 idempotent 응답을 반환한다")
    fun adoptExternalPositionReturnsExistingWhenAlreadyOpen() {
        whenever(guidedTradeRepository.findTopByMarketAndStatusInOrderByCreatedAtDesc(eq("KRW-BTC"), any()))
            .thenReturn(
                GuidedTradeEntity(
                    id = 99L,
                    market = "KRW-BTC",
                    status = GuidedTradeEntity.STATUS_OPEN,
                    averageEntryPrice = BigDecimal("91000000"),
                    entryQuantity = BigDecimal("0.02"),
                    remainingQuantity = BigDecimal("0.02"),
                    stopLossPrice = BigDecimal("90000000"),
                    takeProfitPrice = BigDecimal("93000000"),
                )
            )

        val adopted = service.adoptExternalPosition(GuidedAdoptPositionRequest(market = "KRW-BTC"))

        assertEquals(false, adopted.adopted)
        assertEquals(99L, adopted.positionId)
        verify(guidedTradeRepository, never()).save(any())
    }

    private fun buildCandles(market: String): List<CandleResponse> {
        val idx = market.removePrefix("KRW-C").toInt()
        val drift = when (idx % 4) {
            0 -> 2.2
            1 -> 1.0
            2 -> 0.3
            else -> -0.4
        }
        val base = 900.0 + idx * 8

        return (1..120).map { step ->
            val close = base + step * drift
            val open = close - drift * 0.5
            val high = max(open, close) + 1.8
            val low = min(open, close) - 1.8
            CandleResponse(
                market = market,
                candleDateTimeUtc = "2026-01-01T00:00:00",
                candleDateTimeKst = "2026-01-01T09:00:00",
                openingPrice = BigDecimal.valueOf(open),
                highPrice = BigDecimal.valueOf(high),
                lowPrice = BigDecimal.valueOf(low),
                tradePrice = BigDecimal.valueOf(close),
                timestamp = 1_700_000_000_000L + step * 60_000L,
                candleAccTradePrice = BigDecimal.valueOf(close * 100),
                candleAccTradeVolume = BigDecimal.valueOf(100.0),
                unit = 30,
                prevClosingPrice = BigDecimal.valueOf(close - drift)
            )
        }
    }
}
