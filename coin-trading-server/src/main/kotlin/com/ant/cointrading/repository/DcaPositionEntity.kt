package com.ant.cointrading.repository

import com.ant.cointrading.engine.PositionEntity
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

/**
 * DCA 포지션 엔티티
 *
 * DCA 전략으로 매수한 코인 보유 추적.
 * 평균 매입가, 보유 수량, 총 투자금액을 관리하며
 * 익절/손절 조건에 따라 매도 실행.
 */
@Entity
@Table(
    name = "dca_positions",
    indexes = [
        Index(name = "idx_dca_positions_market", columnList = "market"),
        Index(name = "idx_dca_positions_status", columnList = "status"),
        Index(name = "idx_dca_positions_created", columnList = "createdAt")
    ]
)
class DcaPositionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override var id: Long? = null,

    /** 거래 마켓 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    override var market: String = "",

    /** 총 매수 수량 */
    @Column(nullable = false)
    var totalQuantity: Double = 0.0,

    /** 평균 매입가 (KRW) */
    @Column(nullable = false)
    var averagePrice: Double = 0.0,

    /** 총 투자금액 (KRW) */
    @Column(nullable = false)
    var totalInvested: Double = 0.0,

    /** 현재가 마지막 갱신 시각 */
    @Column
    var lastPriceUpdate: Instant? = null,

    /** 현재가 (마지막 확인 시) */
    @Column
    var lastPrice: Double? = null,

    /** 진입가 대비 현재 수익률 (%) */
    @Column
    var currentPnlPercent: Double? = null,

    /** 익절 비율 (%) */
    @Column
    var takeProfitPercent: Double = 5.0,

    /** 손절 비율 (%) */
    @Column
    var stopLossPercent: Double = -3.0,

    /** 포지션 상태 (OPEN/CLOSING/CLOSED/ABANDONED) */
    @Column(nullable = false, length = 20)
    override var status: String = "OPEN",

    /** 청산 시도 횟수 */
    @Column(nullable = false)
    override var closeAttemptCount: Int = 0,

    /** 청산 주문 ID */
    @Column(length = 100)
    override var closeOrderId: String? = null,

    /** 마지막 청산 시도 시각 */
    @Column
    override var lastCloseAttempt: Instant? = null,

    /** 청산 사유 (TAKE_PROFIT/STOP_LOSS/TIMEOUT/MANUAL/ABANDONED_...) */
    @Column(length = 50)
    var exitReason: String? = null,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 수정 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) : PositionEntity {
    // PositionEntity interface implementations (JPA-mapped backing fields)
    @Column(name = "exitedAt")
    private var _exitTime: Instant? = null
    override var exitTime: Instant?
        get() = _exitTime
        set(value) { _exitTime = value }

    @Column(name = "exitPrice")
    private var _exitPrice: Double? = null
    override var exitPrice: Double?
        get() = _exitPrice
        set(value) { _exitPrice = value }

    @Column(name = "realizedPnl")
    private var _pnlAmount: Double? = null
    override var pnlAmount: Double?
        get() = _pnlAmount
        set(value) { _pnlAmount = value }

    @Column(name = "realizedPnlPercent")
    private var _pnlPercent: Double? = null
    override var pnlPercent: Double?
        get() = _pnlPercent
        set(value) { _pnlPercent = value }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    // PositionEntity interface implementations - mapping DCA-specific fields
    override val entryPrice: Double
        get() = averagePrice

    override val quantity: Double
        get() = totalQuantity

    override val entryTime: Instant
        get() = createdAt

    // DCA-specific aliases (for backward compatibility with existing code)
    var exitedAt: Instant?
        get() = exitTime
        set(value) { exitTime = value }

    var realizedPnl: Double?
        get() = pnlAmount
        set(value) { pnlAmount = value }

    var realizedPnlPercent: Double?
        get() = pnlPercent
        set(value) { pnlPercent = value }
}

/**
 * DCA 포지션 Repository
 */
interface DcaPositionRepository : JpaRepository<DcaPositionEntity, Long> {
    fun findByMarketAndStatus(market: String, status: String): List<DcaPositionEntity>
    fun findByStatus(status: String): List<DcaPositionEntity>
    fun findByStatusAndLastCloseAttemptBefore(status: String, cutoff: Instant): List<DcaPositionEntity>
    fun findByMarketOrderByCreatedAtDesc(market: String): List<DcaPositionEntity>
}
