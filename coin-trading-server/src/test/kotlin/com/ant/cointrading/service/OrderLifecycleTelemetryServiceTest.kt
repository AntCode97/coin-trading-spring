package com.ant.cointrading.service

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.repository.OrderLifecycleEventEntity
import com.ant.cointrading.repository.OrderLifecycleEventRepository
import com.ant.cointrading.repository.OrderLifecycleEventType
import com.ant.cointrading.repository.OrderLifecycleStrategyGroup
import com.ant.cointrading.repository.PendingOrderEntity
import com.ant.cointrading.repository.PendingOrderRepository
import com.ant.cointrading.repository.PendingOrderStatus
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("OrderLifecycleTelemetryService")
class OrderLifecycleTelemetryServiceTest {

    private val eventRepository: OrderLifecycleEventRepository = mock()
    private val pendingOrderRepository: PendingOrderRepository = mock()
    private val tradeRepository: TradeRepository = mock()
    private val bithumbPrivateApi: BithumbPrivateApi = mock()

    private lateinit var service: OrderLifecycleTelemetryService

    @BeforeEach
    fun setUp() {
        service = OrderLifecycleTelemetryService(
            orderLifecycleEventRepository = eventRepository,
            pendingOrderRepository = pendingOrderRepository,
            tradeRepository = tradeRepository,
            bithumbPrivateApi = bithumbPrivateApi
        )

        whenever(eventRepository.save(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<OrderLifecycleEventEntity>(0)
            if (entity.id == null) {
                entity.id = 1L
            }
            entity
        }
        whenever(eventRepository.findTop300ByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(emptyList())
        whenever(eventRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(emptyList())
        whenever(eventRepository.findByEventTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(emptyList())
        whenever(eventRepository.existsByOrderIdAndEventType(any(), any())).thenReturn(false)
        whenever(eventRepository.existsByOrderIdAndEventTypeIn(any(), any())).thenReturn(false)

        whenever(pendingOrderRepository.findByCreatedAtBetween(any(), any())).thenReturn(emptyList())
        whenever(tradeRepository.findByCreatedAtBetween(any(), any())).thenReturn(emptyList())
    }

    @Test
    @DisplayName("요청/체결 집계는 전략 그룹과 코어 엔진을 분리해 계산한다")
    fun buildSummarySeparatesGroupsAndCounts() {
        val start = Instant.parse("2026-02-23T15:00:00Z")
        val end = Instant.parse("2026-02-24T02:00:00Z")

        whenever(eventRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(eq(start), eq(end))).thenReturn(
            listOf(
                event(OrderLifecycleStrategyGroup.MANUAL, OrderLifecycleEventType.BUY_REQUESTED, "m-buy-1", "KRW-BTC", "BUY"),
                event(OrderLifecycleStrategyGroup.MANUAL, OrderLifecycleEventType.BUY_FILLED, "m-buy-1", "KRW-BTC", "BUY"),
                event(OrderLifecycleStrategyGroup.GUIDED, OrderLifecycleEventType.SELL_REQUESTED, "g-sell-1", "KRW-ETH", "SELL"),
                event(OrderLifecycleStrategyGroup.AUTOPILOT_MCP, OrderLifecycleEventType.BUY_REQUESTED, "a-buy-1", "KRW-XRP", "BUY"),
                event(OrderLifecycleStrategyGroup.AUTOPILOT_MCP, OrderLifecycleEventType.CANCELLED, "a-buy-1", "KRW-XRP", "BUY"),
            )
        )

        whenever(pendingOrderRepository.findByCreatedAtBetween(eq(start), eq(end))).thenReturn(
            listOf(
                PendingOrderEntity(
                    orderId = "core-buy-1",
                    market = "KRW-SOL",
                    side = "BUY",
                    orderType = "LIMIT",
                    orderPrice = BigDecimal("100"),
                    orderQuantity = BigDecimal("1"),
                    orderAmountKrw = BigDecimal("100"),
                    status = PendingOrderStatus.PENDING,
                    strategy = "DCA",
                ),
                PendingOrderEntity(
                    orderId = "core-sell-1",
                    market = "KRW-SOL",
                    side = "SELL",
                    orderType = "LIMIT",
                    orderPrice = BigDecimal("100"),
                    orderQuantity = BigDecimal("1"),
                    orderAmountKrw = BigDecimal("100"),
                    filledQuantity = BigDecimal("1"),
                    status = PendingOrderStatus.FILLED,
                    strategy = "VOLUME_SURGE",
                )
            )
        )

        whenever(tradeRepository.findByCreatedAtBetween(eq(start), eq(end))).thenReturn(
            listOf(
                TradeEntity(
                    orderId = "core-sell-2",
                    market = "KRW-SOL",
                    side = "SELL",
                    type = "MARKET",
                    price = 101.0,
                    quantity = 1.0,
                    totalAmount = 101.0,
                    fee = 0.0,
                    strategy = "DCA",
                    confidence = 0.8,
                    reason = "test"
                )
            )
        )

        val summary = service.buildSummary(start, end)

        val manual = summary.groups.getValue(OrderLifecycleStrategyGroup.MANUAL.name)
        assertEquals(1, manual.buyRequested)
        assertEquals(1, manual.buyFilled)

        val guided = summary.groups.getValue(OrderLifecycleStrategyGroup.GUIDED.name)
        assertEquals(1, guided.sellRequested)
        assertEquals(1, guided.pending)

        val autopilotMcp = summary.groups.getValue(OrderLifecycleStrategyGroup.AUTOPILOT_MCP.name)
        assertEquals(1, autopilotMcp.buyRequested)
        assertEquals(1, autopilotMcp.cancelled)

        val core = summary.groups.getValue(OrderLifecycleStrategyGroup.CORE_ENGINE.name)
        assertEquals(1, core.buyRequested)
        assertEquals(0, core.buyFilled)
        assertEquals(2, core.sellRequested)
        assertEquals(2, core.sellFilled)
        assertEquals(1, core.pending)

        assertTrue(summary.total.buyRequested >= 3)
        assertTrue(summary.total.sellRequested >= 3)
    }

    @Test
    @DisplayName("체결 이벤트는 주문 ID 기준으로 1회만 기록한다")
    fun recordFilledIfFirstIsIdempotent() {
        whenever(eventRepository.existsByOrderIdAndEventType("order-1", OrderLifecycleEventType.BUY_FILLED))
            .thenReturn(false)
            .thenReturn(true)

        val first = service.recordFilledIfFirst(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = "KRW-BTC",
            side = "BUY",
            orderId = "order-1",
            price = BigDecimal("93000000"),
            quantity = BigDecimal("0.001"),
            strategyCode = "GUIDED_TRADING"
        )
        val second = service.recordFilledIfFirst(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = "KRW-BTC",
            side = "BUY",
            orderId = "order-1",
            price = BigDecimal("93000000"),
            quantity = BigDecimal("0.001"),
            strategyCode = "GUIDED_TRADING"
        )

        assertTrue(first)
        assertFalse(second)
        verify(eventRepository, times(1)).save(any())
    }

    @Test
    @DisplayName("KST 기준 오늘 00시 경계를 정확히 계산한다")
    fun kstDayWindowUsesKstMidnight() {
        val now = Instant.parse("2026-02-24T03:00:00Z") // KST 12:00

        val (start, end) = service.kstDayWindow(now)

        assertEquals(Instant.parse("2026-02-23T15:00:00Z"), start)
        assertEquals(now, end)
    }

    @Test
    @DisplayName("텔레메트리 저장 실패는 호출자 예외로 전파되지 않는다")
    fun telemetryFailureIsIsolated() {
        whenever(eventRepository.save(any())).thenThrow(RuntimeException("db down"))

        assertDoesNotThrow {
            service.recordRequested(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = "KRW-BTC",
                side = "BUY",
                orderId = "order-x",
                strategyCode = "GUIDED_TRADING",
                price = BigDecimal("93000000"),
                quantity = BigDecimal("0.001"),
                message = "test"
            )
        }
    }

    private fun event(
        strategyGroup: OrderLifecycleStrategyGroup,
        eventType: OrderLifecycleEventType,
        orderId: String,
        market: String,
        side: String,
    ): OrderLifecycleEventEntity {
        return OrderLifecycleEventEntity(
            id = null,
            orderId = orderId,
            market = market,
            side = side,
            eventType = eventType,
            strategyGroup = strategyGroup,
            strategyCode = strategyGroup.name,
            createdAt = Instant.parse("2026-02-24T01:00:00Z"),
        )
    }
}
