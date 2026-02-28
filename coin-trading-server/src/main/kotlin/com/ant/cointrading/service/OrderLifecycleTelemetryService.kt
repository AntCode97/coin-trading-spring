package com.ant.cointrading.service

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.repository.OrderLifecycleEventEntity
import com.ant.cointrading.repository.OrderLifecycleEventRepository
import com.ant.cointrading.repository.OrderLifecycleEventType
import com.ant.cointrading.repository.OrderLifecycleStrategyGroup
import com.ant.cointrading.repository.PendingOrderRepository
import com.ant.cointrading.repository.PendingOrderStatus
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

data class OrderLifecycleGroupSummary(
    val buyRequested: Int,
    val buyFilled: Int,
    val sellRequested: Int,
    val sellFilled: Int,
    val pending: Int,
    val cancelled: Int,
)

data class OrderLifecycleSummary(
    val total: OrderLifecycleGroupSummary,
    val groups: Map<String, OrderLifecycleGroupSummary>,
)

data class OrderLifecycleEvent(
    val id: Long,
    val orderId: String?,
    val market: String,
    val side: String?,
    val eventType: String,
    val strategyGroup: String,
    val strategyCode: String?,
    val price: Double?,
    val quantity: Double?,
    val message: String?,
    val createdAt: Instant,
)

data class OrderLifecycleSnapshot(
    val orderSummary: OrderLifecycleSummary,
    val orderEvents: List<OrderLifecycleEvent>,
)

@Service
class OrderLifecycleTelemetryService(
    private val orderLifecycleEventRepository: OrderLifecycleEventRepository,
    private val pendingOrderRepository: PendingOrderRepository,
    private val tradeRepository: TradeRepository,
    private val bithumbPrivateApi: BithumbPrivateApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val REQUESTED_EVENTS = listOf(
            OrderLifecycleEventType.BUY_REQUESTED,
            OrderLifecycleEventType.SELL_REQUESTED,
        )
        val TERMINAL_EVENTS = listOf(
            OrderLifecycleEventType.BUY_FILLED,
            OrderLifecycleEventType.SELL_FILLED,
            OrderLifecycleEventType.CANCELLED,
            OrderLifecycleEventType.FAILED,
        )
    }

    fun kstDayWindow(now: Instant = Instant.now()): Pair<Instant, Instant> {
        val date = LocalDate.ofInstant(now, KST)
        val start = date.atStartOfDay(KST).toInstant()
        return start to now
    }

    fun recordRequested(
        strategyGroup: OrderLifecycleStrategyGroup,
        market: String,
        side: String,
        orderId: String?,
        strategyCode: String? = null,
        price: BigDecimal? = null,
        quantity: BigDecimal? = null,
        message: String? = null,
    ) {
        telemetrySafe(action = "recordRequested", fallback = Unit) {
            val normalizedSide = normalizeSide(side) ?: return@telemetrySafe Unit
            val eventType = if (normalizedSide == "BUY") OrderLifecycleEventType.BUY_REQUESTED else OrderLifecycleEventType.SELL_REQUESTED
            saveEvent(
                orderId = normalizeOrderId(orderId),
                market = normalizeMarket(market),
                side = normalizedSide,
                eventType = eventType,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode,
                price = price,
                quantity = quantity,
                message = message,
            )
        }
    }

    fun recordFilledIfFirst(
        strategyGroup: OrderLifecycleStrategyGroup,
        market: String,
        side: String,
        orderId: String?,
        price: BigDecimal?,
        quantity: BigDecimal?,
        strategyCode: String? = null,
        message: String? = null,
    ): Boolean {
        return telemetrySafe(action = "recordFilledIfFirst", fallback = false) {
            val normalizedOrderId = normalizeOrderId(orderId) ?: return@telemetrySafe false
            val normalizedSide = normalizeSide(side) ?: return@telemetrySafe false
            val eventType = if (normalizedSide == "BUY") OrderLifecycleEventType.BUY_FILLED else OrderLifecycleEventType.SELL_FILLED

            if (orderLifecycleEventRepository.existsByOrderIdAndEventType(normalizedOrderId, eventType)) {
                return@telemetrySafe false
            }

            saveEvent(
                orderId = normalizedOrderId,
                market = normalizeMarket(market),
                side = normalizedSide,
                eventType = eventType,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode,
                price = price,
                quantity = quantity,
                message = message,
            )
            true
        }
    }

    fun recordCancelRequested(
        strategyGroup: OrderLifecycleStrategyGroup,
        market: String,
        side: String?,
        orderId: String?,
        strategyCode: String? = null,
        message: String? = null,
    ) {
        telemetrySafe(action = "recordCancelRequested", fallback = Unit) {
            saveEvent(
                orderId = normalizeOrderId(orderId),
                market = normalizeMarket(market),
                side = normalizeSide(side),
                eventType = OrderLifecycleEventType.CANCEL_REQUESTED,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode,
                message = message,
            )
        }
    }

    fun recordCancelledIfFirst(
        strategyGroup: OrderLifecycleStrategyGroup,
        market: String,
        side: String?,
        orderId: String?,
        strategyCode: String? = null,
        message: String? = null,
    ): Boolean {
        return telemetrySafe(action = "recordCancelledIfFirst", fallback = false) {
            val normalizedOrderId = normalizeOrderId(orderId) ?: return@telemetrySafe false
            if (orderLifecycleEventRepository.existsByOrderIdAndEventType(normalizedOrderId, OrderLifecycleEventType.CANCELLED)) {
                return@telemetrySafe false
            }

            saveEvent(
                orderId = normalizedOrderId,
                market = normalizeMarket(market),
                side = normalizeSide(side),
                eventType = OrderLifecycleEventType.CANCELLED,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode,
                message = message,
            )
            true
        }
    }

    fun recordFailed(
        strategyGroup: OrderLifecycleStrategyGroup,
        market: String,
        side: String?,
        orderId: String?,
        strategyCode: String? = null,
        message: String? = null,
    ) {
        telemetrySafe(action = "recordFailed", fallback = Unit) {
            saveEvent(
                orderId = normalizeOrderId(orderId),
                market = normalizeMarket(market),
                side = normalizeSide(side),
                eventType = OrderLifecycleEventType.FAILED,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode,
                message = message,
            )
        }
    }

    fun reconcileOrderState(
        strategyGroup: OrderLifecycleStrategyGroup,
        order: OrderResponse?,
        fallbackMarket: String? = null,
        strategyCode: String? = null,
    ) {
        telemetrySafe(action = "reconcileOrderState", fallback = Unit) {
            if (order == null) return@telemetrySafe Unit
            val orderId = normalizeOrderId(order.uuid) ?: return@telemetrySafe Unit
            val side = normalizeSide(order.side)
            val market = normalizeMarket(order.market.ifBlank { fallbackMarket ?: "" })
            val executedVolume = order.executedVolume ?: BigDecimal.ZERO

            if (side != null && executedVolume > BigDecimal.ZERO) {
                recordFilledIfFirst(
                    strategyGroup = strategyGroup,
                    market = market,
                    side = side,
                    orderId = orderId,
                    price = order.price,
                    quantity = executedVolume,
                    strategyCode = strategyCode,
                    message = "reconcile:${order.state}",
                )
            }

            val state = order.state?.trim()?.lowercase()
            if (state == "cancel") {
                recordCancelledIfFirst(
                    strategyGroup = strategyGroup,
                    market = market,
                    side = side,
                    orderId = orderId,
                    strategyCode = strategyCode,
                    message = "reconcile:cancel",
                )
            }
        }
    }

    fun getLiveSnapshot(limit: Int = 200, strategyCodePrefix: String? = null): OrderLifecycleSnapshot {
        val (start, end) = kstDayWindow()
        val normalizedPrefix = strategyCodePrefix?.trim()?.uppercase()?.ifBlank { null }
        val effectiveLimit = limit.coerceIn(1, 300)

        if (normalizedPrefix == null) {
            val events = orderLifecycleEventRepository
                .findTop300ByCreatedAtBetweenOrderByCreatedAtDesc(start, end)
                .map { it.toView() }
                .take(effectiveLimit)

            val summary = buildSummary(start, end)
            return OrderLifecycleSnapshot(
                orderSummary = summary,
                orderEvents = events,
            )
        }

        val filteredTelemetryEvents = orderLifecycleEventRepository
            .findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)
            .filter { event -> matchesStrategyCodePrefix(event.strategyCode, normalizedPrefix) }
        val summary = buildSummaryFromTelemetryEvents(
            telemetryEvents = filteredTelemetryEvents,
            includeCoreEngine = false,
            start = start,
            end = end
        )
        return OrderLifecycleSnapshot(
            orderSummary = summary,
            orderEvents = filteredTelemetryEvents
                .take(effectiveLimit)
                .map { it.toView() },
        )
    }

    fun buildSummary(start: Instant, end: Instant): OrderLifecycleSummary {
        val telemetryEvents = orderLifecycleEventRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)
        return buildSummaryFromTelemetryEvents(
            telemetryEvents = telemetryEvents,
            includeCoreEngine = true,
            start = start,
            end = end
        )
    }

    private fun buildSummaryFromTelemetryEvents(
        telemetryEvents: List<OrderLifecycleEventEntity>,
        includeCoreEngine: Boolean,
        start: Instant,
        end: Instant,
    ): OrderLifecycleSummary {
        val groupCounters = linkedMapOf(
            OrderLifecycleStrategyGroup.MANUAL to MutableCounter(),
            OrderLifecycleStrategyGroup.GUIDED to MutableCounter(),
            OrderLifecycleStrategyGroup.AUTOPILOT_MCP to MutableCounter(),
            OrderLifecycleStrategyGroup.CORE_ENGINE to MutableCounter(),
        )

        telemetryEvents.forEach { event ->
            val counter = groupCounters.getOrPut(event.strategyGroup) { MutableCounter() }
            applyEvent(counter, event.eventType)
        }

        if (includeCoreEngine) {
            val coreCounter = groupCounters.getOrPut(OrderLifecycleStrategyGroup.CORE_ENGINE) { MutableCounter() }
            coreCounter.merge(buildCoreEngineCounter(start, end))
        }

        groupCounters.values.forEach { it.normalizePending() }

        val groupViews = groupCounters.mapValues { (_, counter) -> counter.toSummary() }
        val totalCounter = MutableCounter().apply {
            groupCounters.values.forEach { merge(it) }
            normalizePending()
        }

        return OrderLifecycleSummary(
            total = totalCounter.toSummary(),
            groups = groupViews.mapKeys { it.key.name },
        )
    }

    @Scheduled(fixedDelay = 7000)
    fun reconcileOutstandingOrders() {
        val (start, end) = kstDayWindow()
        val requested = orderLifecycleEventRepository.findByEventTypeInAndCreatedAtBetween(REQUESTED_EVENTS, start, end)
        if (requested.isEmpty()) return

        val latestByOrderId = requested
            .asSequence()
            .mapNotNull { event ->
                val orderId = normalizeOrderId(event.orderId) ?: return@mapNotNull null
                orderId to event
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, events) -> events.maxByOrNull { it.createdAt } ?: return@mapValues null }
            .filterValues { it != null }
            .mapValues { it.value!! }

        latestByOrderId.entries
            .sortedByDescending { it.value.createdAt }
            .take(220)
            .forEach { (orderId, requestEvent) ->
                if (orderLifecycleEventRepository.existsByOrderIdAndEventTypeIn(orderId, TERMINAL_EVENTS)) {
                    return@forEach
                }

                val order = runCatching { bithumbPrivateApi.getOrder(orderId) }
                    .onFailure { ex -> log.debug("order reconcile 조회 실패: orderId={}, error={}", orderId, ex.message) }
                    .getOrNull()
                    ?: return@forEach

                reconcileOrderState(
                    strategyGroup = requestEvent.strategyGroup,
                    order = order,
                    fallbackMarket = requestEvent.market,
                    strategyCode = requestEvent.strategyCode,
                )
            }
    }

    private fun buildCoreEngineCounter(start: Instant, end: Instant): MutableCounter {
        val counter = MutableCounter()
        val filledOrderKeys = mutableSetOf<String>()

        val pendingOrders = pendingOrderRepository.findByCreatedAtBetween(start, end)
            .filter { isCoreStrategy(it.strategy) }

        pendingOrders.forEach { order ->
            val side = normalizeSide(order.side)
            if (side == "BUY") {
                counter.buyRequested += 1
            } else if (side == "SELL") {
                counter.sellRequested += 1
            }

            when (order.status) {
                PendingOrderStatus.PENDING -> counter.pending += 1
                PendingOrderStatus.PARTIALLY_FILLED -> {
                    if (side != null && order.filledQuantity > BigDecimal.ZERO) {
                        val key = "${order.orderId}:$side"
                        if (filledOrderKeys.add(key)) {
                            if (side == "BUY") counter.buyFilled += 1 else counter.sellFilled += 1
                        }
                    }
                    counter.pending += 1
                }
                PendingOrderStatus.FILLED -> {
                    if (side != null) {
                        val key = "${order.orderId}:$side"
                        if (filledOrderKeys.add(key)) {
                            if (side == "BUY") counter.buyFilled += 1 else counter.sellFilled += 1
                        }
                    }
                }
                PendingOrderStatus.CANCELLED,
                PendingOrderStatus.EXPIRED,
                PendingOrderStatus.FAILED,
                PendingOrderStatus.REPLACED,
                -> counter.cancelled += 1
            }
        }

        val coreTrades = tradeRepository.findByCreatedAtBetween(start, end)
            .filter { isCoreStrategy(it.strategy) }

        coreTrades.forEach { trade ->
            val side = normalizeSide(trade.side) ?: return@forEach
            val key = "${trade.orderId.ifBlank { "trade-${trade.id ?: 0}" }}:$side"
            if (!filledOrderKeys.add(key)) {
                return@forEach
            }

            if (side == "BUY") {
                counter.buyFilled += 1
            } else {
                counter.sellFilled += 1
            }
        }

        counter.buyRequested = max(counter.buyRequested, counter.buyFilled)
        counter.sellRequested = max(counter.sellRequested, counter.sellFilled)
        counter.pending = max(counter.pending, 0)
        return counter
    }

    private fun applyEvent(counter: MutableCounter, eventType: OrderLifecycleEventType) {
        when (eventType) {
            OrderLifecycleEventType.BUY_REQUESTED -> counter.buyRequested += 1
            OrderLifecycleEventType.BUY_FILLED -> counter.buyFilled += 1
            OrderLifecycleEventType.SELL_REQUESTED -> counter.sellRequested += 1
            OrderLifecycleEventType.SELL_FILLED -> counter.sellFilled += 1
            OrderLifecycleEventType.CANCEL_REQUESTED -> {
                // 취소 요청은 타임라인용으로 유지, 퍼널 카운트는 취소 완료 시 반영한다.
            }
            OrderLifecycleEventType.CANCELLED,
            OrderLifecycleEventType.FAILED,
            -> counter.cancelled += 1
        }
    }

    private fun saveEvent(
        orderId: String?,
        market: String,
        side: String?,
        eventType: OrderLifecycleEventType,
        strategyGroup: OrderLifecycleStrategyGroup,
        strategyCode: String? = null,
        price: BigDecimal? = null,
        quantity: BigDecimal? = null,
        message: String? = null,
    ) {
        orderLifecycleEventRepository.save(
            OrderLifecycleEventEntity(
                orderId = orderId,
                market = market,
                side = side,
                eventType = eventType,
                strategyGroup = strategyGroup,
                strategyCode = strategyCode?.takeIf { it.isNotBlank() },
                price = price,
                quantity = quantity,
                message = message?.take(500),
            )
        )
    }

    private inline fun <T> telemetrySafe(action: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            log.warn("order telemetry {} 실패: {}", action, e.message)
            fallback
        }
    }

    private fun normalizeSide(side: String?): String? {
        if (side.isNullOrBlank()) return null
        return when (side.trim().uppercase()) {
            "BUY", "BID" -> "BUY"
            "SELL", "ASK" -> "SELL"
            else -> null
        }
    }

    private fun normalizeMarket(market: String): String {
        val value = market.trim().uppercase()
        return if (value.startsWith("KRW-")) value else value
    }

    private fun normalizeOrderId(orderId: String?): String? {
        val normalized = orderId?.trim()
        return if (normalized.isNullOrBlank()) null else normalized
    }

    private fun matchesStrategyCodePrefix(strategyCode: String?, prefix: String): Boolean {
        val normalized = strategyCode?.trim()?.uppercase() ?: return false
        return normalized.startsWith(prefix)
    }

    private fun isCoreStrategy(strategyCode: String?): Boolean {
        val normalized = strategyCode?.trim()?.uppercase() ?: return false
        if (normalized.isBlank()) return false
        if (normalized.contains("GUIDED")) return false
        if (normalized.contains("MANUAL")) return false
        if (normalized.contains("AUTOPILOT")) return false
        if (normalized.contains("MCP")) return false
        return true
    }

    private fun OrderLifecycleEventEntity.toView(): OrderLifecycleEvent {
        return OrderLifecycleEvent(
            id = id ?: 0L,
            orderId = orderId,
            market = market,
            side = side,
            eventType = eventType.name,
            strategyGroup = strategyGroup.name,
            strategyCode = strategyCode,
            price = price?.toDouble(),
            quantity = quantity?.toDouble(),
            message = message,
            createdAt = createdAt,
        )
    }

    private class MutableCounter(
        var buyRequested: Int = 0,
        var buyFilled: Int = 0,
        var sellRequested: Int = 0,
        var sellFilled: Int = 0,
        var pending: Int = 0,
        var cancelled: Int = 0,
    ) {
        fun merge(other: MutableCounter) {
            buyRequested += other.buyRequested
            buyFilled += other.buyFilled
            sellRequested += other.sellRequested
            sellFilled += other.sellFilled
            pending += other.pending
            cancelled += other.cancelled
        }

        fun normalizePending() {
            val inferredPending = buyRequested + sellRequested - buyFilled - sellFilled - cancelled
            pending = max(pending, max(0, inferredPending))
        }

        fun toSummary(): OrderLifecycleGroupSummary {
            return OrderLifecycleGroupSummary(
                buyRequested = buyRequested,
                buyFilled = buyFilled,
                sellRequested = sellRequested,
                sellFilled = sellFilled,
                pending = pending,
                cancelled = cancelled,
            )
        }
    }
}
