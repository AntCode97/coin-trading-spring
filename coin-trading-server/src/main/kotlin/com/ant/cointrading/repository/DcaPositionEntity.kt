package com.ant.cointrading.repository

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
    var id: Long? = null,

    /** 거래 마켓 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    var market: String = "",

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
    var takeProfitPercent: Double = 15.0,

    /** 손절 비율 (%) */
    @Column
    var stopLossPercent: Double = -10.0,

    /** 포지션 상태 (OPEN/CLOSED) */
    @Column(nullable = false, length = 20)
    var status: String = "OPEN",

    /** 청산 사유 (TAKE_PROFIT/STOP_LOSS/TIMEOUT/MANUAL) */
    @Column(length = 30)
    var exitReason: String? = null,

    /** 청산 시각 */
    @Column
    var exitedAt: Instant? = null,

    /** 청산가 (KRW) */
    @Column
    var exitPrice: Double? = null,

    /** 실현 손익 (KRW) */
    @Column
    var realizedPnl: Double? = null,

    /** 실현 손익률 (%) */
    @Column
    var realizedPnlPercent: Double? = null,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 수정 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/**
 * DCA 포지션 Repository
 */
interface DcaPositionRepository : JpaRepository<DcaPositionEntity, Long> {
    fun findByMarketAndStatus(market: String, status: String): List<DcaPositionEntity>
    fun findByStatus(status: String): List<DcaPositionEntity>
    fun findByMarketOrderByCreatedAtDesc(market: String): List<DcaPositionEntity>
}
