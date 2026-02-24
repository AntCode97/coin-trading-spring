package com.ant.cointrading.repository

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

enum class OrderLifecycleEventType {
    BUY_REQUESTED,
    BUY_FILLED,
    SELL_REQUESTED,
    SELL_FILLED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED,
}

enum class OrderLifecycleStrategyGroup {
    MANUAL,
    GUIDED,
    AUTOPILOT_MCP,
    CORE_ENGINE,
}

@Entity
@Table(
    name = "order_lifecycle_events",
    indexes = [
        Index(name = "idx_order_lifecycle_created_at", columnList = "createdAt"),
        Index(name = "idx_order_lifecycle_order_id", columnList = "orderId"),
        Index(name = "idx_order_lifecycle_group", columnList = "strategyGroup,eventType"),
    ]
)
class OrderLifecycleEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(length = 64)
    var orderId: String? = null,

    @Column(nullable = false, length = 20)
    var market: String = "",

    @Column(length = 10)
    var side: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var eventType: OrderLifecycleEventType = OrderLifecycleEventType.BUY_REQUESTED,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var strategyGroup: OrderLifecycleStrategyGroup = OrderLifecycleStrategyGroup.CORE_ENGINE,

    @Column(length = 50)
    var strategyCode: String? = null,

    @Column(precision = 20, scale = 8)
    var price: BigDecimal? = null,

    @Column(precision = 20, scale = 8)
    var quantity: BigDecimal? = null,

    @Column(length = 500)
    var message: String? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Repository
interface OrderLifecycleEventRepository : JpaRepository<OrderLifecycleEventEntity, Long> {
    fun findTop300ByCreatedAtBetweenOrderByCreatedAtDesc(start: Instant, end: Instant): List<OrderLifecycleEventEntity>

    fun findByCreatedAtBetweenOrderByCreatedAtDesc(start: Instant, end: Instant): List<OrderLifecycleEventEntity>

    fun existsByOrderIdAndEventType(orderId: String, eventType: OrderLifecycleEventType): Boolean

    fun existsByOrderIdAndEventTypeIn(orderId: String, eventTypes: Collection<OrderLifecycleEventType>): Boolean

    fun findByEventTypeInAndCreatedAtBetween(
        eventTypes: Collection<OrderLifecycleEventType>,
        start: Instant,
        end: Instant,
    ): List<OrderLifecycleEventEntity>
}
