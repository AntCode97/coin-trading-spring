package com.ant.cointrading.repository

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "guided_trades",
    indexes = [
        Index(name = "idx_guided_trades_market", columnList = "market"),
        Index(name = "idx_guided_trades_status", columnList = "status"),
        Index(name = "idx_guided_trades_created", columnList = "createdAt")
    ]
)
class GuidedTradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 20)
    var market: String = "",

    @Column(nullable = false, length = 20)
    var status: String = STATUS_PENDING_ENTRY,

    @Column(nullable = false, length = 12)
    var entryOrderType: String = ORDER_TYPE_MARKET,

    @Column(length = 60)
    var entryOrderId: String? = null,

    @Column(length = 60)
    var lastExitOrderId: String? = null,

    @Column(nullable = false, precision = 20, scale = 2)
    var targetAmountKrw: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var averageEntryPrice: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var entryQuantity: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var remainingQuantity: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var stopLossPrice: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var takeProfitPrice: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 4)
    var trailingTriggerPercent: BigDecimal = BigDecimal("2.0"),

    @Column(nullable = false, precision = 10, scale = 4)
    var trailingOffsetPercent: BigDecimal = BigDecimal("1.0"),

    @Column(nullable = false)
    var trailingActive: Boolean = false,

    @Column(precision = 20, scale = 8)
    var trailingPeakPrice: BigDecimal? = null,

    @Column(precision = 20, scale = 8)
    var trailingStopPrice: BigDecimal? = null,

    @Column(nullable = false, precision = 10, scale = 4)
    var dcaStepPercent: BigDecimal = BigDecimal("2.0"),

    @Column(nullable = false)
    var maxDcaCount: Int = 2,

    @Column(nullable = false)
    var dcaCount: Int = 0,

    @Column(nullable = false, precision = 10, scale = 4)
    var halfTakeProfitRatio: BigDecimal = BigDecimal("0.5"),

    @Column(nullable = false)
    var halfTakeProfitDone: Boolean = false,

    @Column(nullable = false, precision = 20, scale = 8)
    var cumulativeExitQuantity: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 8)
    var averageExitPrice: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 2)
    var realizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 4)
    var realizedPnlPercent: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, length = 16)
    var pnlConfidence: String = PNL_CONFIDENCE_LEGACY,

    @Column
    var pnlReconciledAt: Instant? = null,

    @Column(length = 30)
    var exitReason: String? = null,

    @Column(length = 120)
    var lastAction: String? = null,

    @Column(columnDefinition = "TEXT")
    var recommendationReason: String? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column
    var closedAt: Instant? = null
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    companion object {
        const val STATUS_PENDING_ENTRY = "PENDING_ENTRY"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_CLOSED = "CLOSED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        const val PNL_CONFIDENCE_HIGH = "HIGH"
        const val PNL_CONFIDENCE_LOW = "LOW"
        const val PNL_CONFIDENCE_LEGACY = "LEGACY"

        const val ORDER_TYPE_MARKET = "MARKET"
        const val ORDER_TYPE_LIMIT = "LIMIT"
    }
}

@Entity
@Table(
    name = "guided_trade_events",
    indexes = [
        Index(name = "idx_guided_trade_events_trade", columnList = "tradeId"),
        Index(name = "idx_guided_trade_events_created", columnList = "createdAt")
    ]
)
class GuidedTradeEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var tradeId: Long = 0,

    @Column(nullable = false, length = 40)
    var eventType: String = "",

    @Column(precision = 20, scale = 8)
    var price: BigDecimal? = null,

    @Column(precision = 20, scale = 8)
    var quantity: BigDecimal? = null,

    @Column(columnDefinition = "TEXT")
    var message: String? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
)

interface GuidedTradeRepository : JpaRepository<GuidedTradeEntity, Long> {
    fun findByStatusIn(statuses: List<String>): List<GuidedTradeEntity>
    fun findByMarketAndStatusIn(market: String, statuses: List<String>): List<GuidedTradeEntity>
    fun findTopByMarketAndStatusInOrderByCreatedAtDesc(market: String, statuses: List<String>): GuidedTradeEntity?
    fun findTop80ByMarketAndStatusOrderByCreatedAtDesc(market: String, status: String): List<GuidedTradeEntity>
    fun findTop200ByStatusOrderByCreatedAtDesc(status: String): List<GuidedTradeEntity>
    fun findByStatusAndClosedAtAfter(status: String, after: Instant): List<GuidedTradeEntity>
    fun findByStatusOrderByClosedAtDesc(status: String): List<GuidedTradeEntity>
}

interface GuidedTradeEventRepository : JpaRepository<GuidedTradeEventEntity, Long> {
    fun findByTradeIdOrderByCreatedAtAsc(tradeId: Long): List<GuidedTradeEventEntity>
    fun findTopByTradeIdAndEventTypeOrderByCreatedAtDesc(tradeId: Long, eventType: String): GuidedTradeEventEntity?
}
