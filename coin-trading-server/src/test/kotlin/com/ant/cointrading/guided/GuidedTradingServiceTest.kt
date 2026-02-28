package com.ant.cointrading.guided

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.api.bithumb.MarketInfo
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.repository.GuidedTradeEntity
import com.ant.cointrading.repository.GuidedTradeEventEntity
import com.ant.cointrading.repository.GuidedTradeEventRepository
import com.ant.cointrading.repository.GuidedAutopilotDecisionRepository
import com.ant.cointrading.repository.GuidedTradeRepository
import com.ant.cointrading.service.OrderLifecycleTelemetryService
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
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
    private val orderLifecycleTelemetryService: OrderLifecycleTelemetryService = mock()
    private val guidedAutopilotDecisionRepository: GuidedAutopilotDecisionRepository = mock()

    private lateinit var service: GuidedTradingService
    private lateinit var markets: List<MarketInfo>
    private lateinit var tickers: List<TickerInfo>

    @BeforeEach
    fun setUp() {
        service = GuidedTradingService(
            bithumbPublicApi = bithumbPublicApi,
            bithumbPrivateApi = bithumbPrivateApi,
            guidedTradeRepository = guidedTradeRepository,
            guidedTradeEventRepository = guidedTradeEventRepository,
            orderLifecycleTelemetryService = orderLifecycleTelemetryService,
            guidedAutopilotDecisionRepository = guidedAutopilotDecisionRepository
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
    @DisplayName("오토파일럿 opportunities는 유니버스 제한(15)과 stage 산출 규칙을 적용한다")
    fun autopilotOpportunitiesUseUniverseLimitAndStageRules() {
        val result = service.getAutopilotOpportunities(
            interval = "minute1",
            confirmInterval = "minute10",
            mode = TradingMode.SCALP,
            universeLimit = 15
        )

        val expectedUniverse = markets.take(15).map { it.market }.toSet()
        assertEquals(15, result.appliedUniverseLimit)
        assertEquals(15, result.opportunities.size)
        assertTrue(result.opportunities.all { it.market in expectedUniverse })
        assertTrue(result.opportunities.all { it.score in 0.0..100.0 })
        assertTrue(result.opportunities.all { it.stage in setOf("AUTO_PASS", "BORDERLINE", "RULE_FAIL") })
        assertTrue(result.opportunities.zipWithNext().all { (left, right) -> left.score >= right.score })
    }

    @Test
    @DisplayName("오토파일럿 opportunities 기대값/점수 계산식은 고정 산식을 따른다")
    fun autopilotOpportunityFormulaMatchesSpec() {
        val method = GuidedTradingService::class.java.getDeclaredMethod(
            "buildAutopilotOpportunity",
            GuidedMarketItem::class.java,
            GuidedRecommendation::class.java,
            GuidedRecommendation::class.java
        )
        method.isAccessible = true

        val marketItem = GuidedMarketItem(
            market = "KRW-TEST",
            symbol = "TEST",
            koreanName = "테스트",
            englishName = "TEST",
            tradePrice = 100.0,
            changeRate = 0.0,
            changePrice = 0.0,
            accTradePrice = 1_000_000.0,
            accTradeVolume = 10_000.0,
            surgeRate = 0.0
        )
        val primary = GuidedRecommendation(
            market = "KRW-TEST",
            currentPrice = 100.0,
            recommendedEntryPrice = 99.5,
            stopLossPrice = 98.9,
            takeProfitPrice = 101.7,
            confidence = 68.0,
            predictedWinRate = 61.0,
            recommendedEntryWinRate = 62.0,
            marketEntryWinRate = 59.0,
            riskRewardRatio = 1.45,
            winRateBreakdown = GuidedWinRateBreakdown(
                trend = 60.0,
                pullback = 58.0,
                volatility = 55.0,
                riskReward = 62.0
            ),
            suggestedOrderType = "LIMIT",
            rationale = listOf("formula-test")
        )
        val confirm = primary.copy(
            recommendedEntryWinRate = 58.0,
            marketEntryWinRate = 57.0,
            riskRewardRatio = 1.22
        )

        val result = method.invoke(service, marketItem, primary, confirm) as GuidedAutopilotOpportunityView

        val p = 0.6 * (62.0 / 100.0) + 0.4 * (58.0 / 100.0)
        val profitPct = (101.7 - 99.5) / 99.5 * 100.0
        val lossPct = (99.5 - 98.9) / 99.5 * 100.0
        val expectancy = p * profitPct - (1 - p) * lossPct
        val entryGapPct = max(0.0, (100.0 - 99.5) / 99.5 * 100.0)
        val rawScore =
            50.0 +
                22.0 * expectancy +
                12.0 * (1.45 - 1.0) +
                0.35 * (62.0 - 50.0) +
                0.25 * (58.0 - 50.0) -
                8.0 * max(0.0, entryGapPct - 0.35)
        val expectedScore = rawScore.coerceIn(0.0, 100.0)

        assertEquals(expectancy, result.expectancyPct, 1e-9)
        assertEquals(expectedScore, result.score, 1e-9)
        assertEquals("AUTO_PASS", result.stage)
    }

    @Test
    @DisplayName("MARKET 진입가 계산은 locked보다 investedAmount를 우선 사용한다")
    fun resolveEntryPricePrefersInvestedAmount() {
        val method = GuidedTradingService::class.java.getDeclaredMethod(
            "resolveEntryPrice",
            OrderResponse::class.java,
            String::class.java,
            BigDecimal::class.java,
            BigDecimal::class.java,
            BigDecimal::class.java
        )
        method.isAccessible = true

        val order = orderResponse(
            uuid = "entry-1",
            side = "bid",
            market = "KRW-BTC",
            price = BigDecimal("93000000"),
            executedVolume = BigDecimal("0.00100000"),
            locked = BigDecimal("20000")
        )

        val resolved = method.invoke(
            service,
            order,
            GuidedTradeEntity.ORDER_TYPE_MARKET,
            BigDecimal("10000"),
            null,
            BigDecimal("92000000")
        ) as BigDecimal

        assertEquals(0, resolved.compareTo(BigDecimal("10000000.00000000")))
    }

    @Test
    @DisplayName("AUTOPILOT 진입은 entrySource/strategyCode를 포지션에 저장한다")
    fun startAutoTradingPersistsEntrySourceAndStrategyCode() {
        whenever(
            bithumbPrivateApi.buyMarketOrder(
                eq("KRW-BTC"),
                eq(BigDecimal("10000"))
            )
        ).thenReturn(
            orderResponse(
                uuid = "entry-1",
                side = "bid",
                market = "KRW-BTC",
                state = "done",
                executedVolume = BigDecimal("0.00100000"),
                volume = BigDecimal("0.00100000")
            )
        )
        whenever(bithumbPrivateApi.getOrder("entry-1")).thenReturn(
            orderResponse(
                uuid = "entry-1",
                side = "bid",
                market = "KRW-BTC",
                state = "done",
                executedVolume = BigDecimal("0.00100000"),
                volume = BigDecimal("0.00100000")
            )
        )

        service.startAutoTrading(
            GuidedStartRequest(
                market = "KRW-BTC",
                amountKrw = 10000,
                orderType = "MARKET",
                interval = "minute1",
                mode = "SCALP",
                entrySource = "AUTOPILOT",
                strategyCode = "guided_autopilot"
            )
        )

        val captor = argumentCaptor<GuidedTradeEntity>()
        verify(guidedTradeRepository, times(1)).save(captor.capture())
        assertEquals("AUTOPILOT", captor.firstValue.entrySource)
        assertEquals("GUIDED_AUTOPILOT", captor.firstValue.strategyCode)
    }

    @Test
    @DisplayName("pending 진입은 getOrder null이어도 age timeout 취소를 수행한다")
    fun reconcilePendingEntryTimeoutEvenWhenOrderMissing() {
        val trade = GuidedTradeEntity(
            id = 10L,
            market = "KRW-BTC",
            status = GuidedTradeEntity.STATUS_PENDING_ENTRY,
            entryOrderType = GuidedTradeEntity.ORDER_TYPE_LIMIT,
            entryOrderId = "entry-timeout",
            averageEntryPrice = BigDecimal("92000000"),
            targetAmountKrw = BigDecimal("10000"),
            stopLossPrice = BigDecimal("90000000"),
            takeProfitPrice = BigDecimal("94000000"),
            createdAt = Instant.now().minusSeconds(901)
        )

        whenever(bithumbPrivateApi.getOrder("entry-timeout")).thenReturn(null)
        whenever(bithumbPrivateApi.cancelOrder("entry-timeout")).thenReturn(
            orderResponse(
                uuid = "entry-timeout",
                side = "bid",
                market = "KRW-BTC",
                state = "cancel"
            )
        )

        val method = GuidedTradingService::class.java.getDeclaredMethod(
            "reconcilePendingEntry",
            GuidedTradeEntity::class.java
        )
        method.isAccessible = true
        method.invoke(service, trade)

        verify(bithumbPrivateApi, times(1)).cancelOrder("entry-timeout")
        assertEquals(GuidedTradeEntity.STATUS_CANCELLED, trade.status)
        assertEquals("ENTRY_TIMEOUT", trade.exitReason)
    }

    @Test
    @DisplayName("수동 stop에서 매도 미체결이면 포지션을 강제 CLOSED 하지 않는다")
    fun stopAutoTradingNoFillKeepsTradeOpen() {
        val trade = GuidedTradeEntity(
            id = 11L,
            market = "KRW-BTC",
            status = GuidedTradeEntity.STATUS_OPEN,
            entryOrderType = GuidedTradeEntity.ORDER_TYPE_MARKET,
            averageEntryPrice = BigDecimal("92000000"),
            entryQuantity = BigDecimal("0.01000000"),
            remainingQuantity = BigDecimal("0.01000000"),
            stopLossPrice = BigDecimal("90000000"),
            takeProfitPrice = BigDecimal("94000000"),
        )
        whenever(guidedTradeRepository.findByMarketAndStatusIn(eq("KRW-BTC"), any())).thenReturn(listOf(trade))
        whenever(bithumbPublicApi.getCurrentPrice(eq("KRW-BTC"))).thenReturn(
            listOf(
                tickers.first().copy(
                    market = "KRW-BTC",
                    tradePrice = BigDecimal("93000000")
                )
            )
        )
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
        whenever(bithumbPrivateApi.sellMarketOrder(eq("KRW-BTC"), any())).thenReturn(
            orderResponse(uuid = "exit-1", side = "ask", market = "KRW-BTC", state = "wait")
        )
        whenever(bithumbPrivateApi.getOrder("exit-1")).thenReturn(
            orderResponse(
                uuid = "exit-1",
                side = "ask",
                market = "KRW-BTC",
                state = "wait",
                executedVolume = BigDecimal.ZERO
            )
        )

        val result = service.stopAutoTrading("KRW-BTC")

        assertEquals(GuidedTradeEntity.STATUS_OPEN, trade.status)
        assertEquals("MANUAL_STOP_PENDING_EXIT", trade.lastAction)
        assertEquals("OPEN", result.status)
        assertNull(trade.closedAt)
    }

    @Test
    @DisplayName("최근 30일 보정은 이벤트 체결값으로 손익을 재계산하고 HIGH로 마킹한다")
    fun reconcileClosedTradesRecalculatesFromEvents() {
        val trade = GuidedTradeEntity(
            id = 21L,
            market = "KRW-BTC",
            status = GuidedTradeEntity.STATUS_CLOSED,
            entryOrderType = GuidedTradeEntity.ORDER_TYPE_MARKET,
            averageEntryPrice = BigDecimal("1"),
            entryQuantity = BigDecimal("1"),
            remainingQuantity = BigDecimal.ZERO,
            stopLossPrice = BigDecimal("1"),
            takeProfitPrice = BigDecimal("1"),
            cumulativeExitQuantity = BigDecimal("1"),
            averageExitPrice = BigDecimal("1"),
            realizedPnl = BigDecimal.ZERO,
            realizedPnlPercent = BigDecimal.ZERO,
            closedAt = Instant.now().minusSeconds(30)
        )
        whenever(
            guidedTradeRepository.findByStatusAndClosedAtAfter(
                eq(GuidedTradeEntity.STATUS_CLOSED),
                any()
            )
        ).thenReturn(listOf(trade))
        whenever(guidedTradeEventRepository.findByTradeIdOrderByCreatedAtAsc(eq(21L))).thenReturn(
            listOf(
                GuidedTradeEventEntity(
                    tradeId = 21L,
                    eventType = "ENTRY_FILLED",
                    price = BigDecimal("1000000"),
                    quantity = BigDecimal("0.01000000"),
                    createdAt = Instant.now().minusSeconds(20)
                ),
                GuidedTradeEventEntity(
                    tradeId = 21L,
                    eventType = "CLOSE_ALL",
                    price = BigDecimal("1020000"),
                    quantity = BigDecimal("0.01000000"),
                    createdAt = Instant.now().minusSeconds(10)
                )
            )
        )

        val result = service.reconcileClosedTrades(windowDays = 30, dryRun = false)

        assertEquals(1, result.scannedTrades)
        assertEquals(1, result.updatedTrades)
        assertEquals(1, result.highConfidenceTrades)
        assertEquals(0, result.lowConfidenceTrades)
        assertEquals(GuidedTradeEntity.PNL_CONFIDENCE_HIGH, trade.pnlConfidence)
        assertEquals(BigDecimal("200.00"), trade.realizedPnl)
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
        val captor = argumentCaptor<GuidedTradeEntity>()
        verify(guidedTradeRepository, times(1)).save(captor.capture())
        assertEquals("MCP_DIRECT", captor.firstValue.entrySource)
        assertEquals("GUIDED_AUTOPILOT", captor.firstValue.strategyCode)
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
        val symbol = market.removePrefix("KRW-")
        val idx = symbol
            .filter { it.isDigit() }
            .toIntOrNull()
            ?: (symbol.fold(0) { acc, ch -> acc + ch.code } % 60 + 1)
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

    private fun orderResponse(
        uuid: String,
        side: String,
        market: String,
        state: String? = "done",
        price: BigDecimal? = BigDecimal("93000000"),
        volume: BigDecimal? = BigDecimal("0.01000000"),
        executedVolume: BigDecimal? = BigDecimal("0.01000000"),
        locked: BigDecimal? = BigDecimal.ZERO,
    ): OrderResponse {
        return OrderResponse(
            uuid = uuid,
            side = side,
            ordType = "market",
            price = price,
            state = state,
            market = market,
            createdAt = "2026-02-24T00:00:00",
            volume = volume,
            remainingVolume = BigDecimal.ZERO,
            reservedFee = BigDecimal.ZERO,
            remainingFee = BigDecimal.ZERO,
            paidFee = BigDecimal.ZERO,
            locked = locked,
            executedVolume = executedVolume
        )
    }
}
