package com.ant.cointrading.repository

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 청산 복구 큐 상태
 */
enum class CloseRecoveryTaskStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    COMPLETED
}

/**
 * 청산 복구 큐 엔티티
 *
 * ABANDONED/실패 포지션을 별도 큐에서 자동 복구한다.
 */
@Entity
@Table(
    name = "close_recovery_queue",
    indexes = [
        Index(name = "idx_close_recovery_status_next", columnList = "status, nextAttemptAt"),
        Index(name = "idx_close_recovery_market", columnList = "market"),
        Index(name = "idx_close_recovery_strategy_position", columnList = "strategy, positionId")
    ]
)
class CloseRecoveryQueueEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 전략 키 (MEME_SCALPER / VOLUME_SURGE / DCA) */
    @Column(nullable = false, length = 30)
    var strategy: String = "",

    /** 포지션 식별자 */
    @Column(nullable = false)
    var positionId: Long = 0,

    /** 마켓 코드 */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 큐 상태 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CloseRecoveryTaskStatus = CloseRecoveryTaskStatus.PENDING,

    /** 큐 등록 사유 */
    @Column(nullable = false, length = 200)
    var reason: String = "",

    /** 진입가 (PnL 추정/복구용) */
    @Column(nullable = false)
    var entryPrice: Double = 0.0,

    /** 목표 청산 수량 (0 이하이면 실제 잔고 전량) */
    @Column(nullable = false)
    var targetQuantity: Double = 0.0,

    /** 마지막 확인 가격 */
    @Column
    var lastKnownPrice: Double? = null,

    /** 시도 횟수 */
    @Column(nullable = false)
    var attemptCount: Int = 0,

    /** 다음 시도 예정 시각 */
    @Column(nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    /** 마지막 시도 시각 */
    @Column
    var lastAttemptAt: Instant? = null,

    /** 마지막 실패 메시지 */
    @Column(length = 500)
    var lastError: String? = null,

    /** 완료 시각 */
    @Column
    var completedAt: Instant? = null,

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

@Repository
interface CloseRecoveryQueueRepository : JpaRepository<CloseRecoveryQueueEntity, Long> {
    fun findTop50ByStatusInAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
        statuses: Collection<CloseRecoveryTaskStatus>,
        now: Instant
    ): List<CloseRecoveryQueueEntity>

    fun findTopByStrategyAndPositionIdAndStatusInOrderByCreatedAtDesc(
        strategy: String,
        positionId: Long,
        statuses: Collection<CloseRecoveryTaskStatus>
    ): CloseRecoveryQueueEntity?
}
