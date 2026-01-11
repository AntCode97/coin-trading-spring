package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant

/**
 * LLM 결정 감사 로그 엔티티
 * AI 의사결정 기록 및 추적
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_type", columnList = "eventType"),
        Index(name = "idx_audit_created", columnList = "createdAt"),
        Index(name = "idx_audit_market", columnList = "market")
    ]
)
class AuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 이벤트 유형 (STRATEGY_CHANGE/PARAM_UPDATE/TRADE_DECISION) */
    @Column(nullable = false, length = 50)
    var eventType: String = "",

    /** 대상 마켓 */
    @Column(length = 20)
    var market: String? = null,

    /** 수행 액션 */
    @Column(nullable = false, length = 100)
    var action: String = "",

    /** 입력 데이터 (JSON) */
    @Column(columnDefinition = "TEXT")
    var inputData: String? = null,

    /** 출력 데이터 (JSON) */
    @Column(columnDefinition = "TEXT")
    var outputData: String? = null,

    /** LLM 판단 사유 */
    @Column(length = 500)
    var reason: String? = null,

    /** 신뢰도 (0.0~1.0) */
    var confidence: Double? = null,

    /** 적용 여부 */
    var applied: Boolean = false,

    /** 거부 사유 (미적용 시) */
    @Column(length = 500)
    var rejectionReason: String? = null,

    /** 로그 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 트리거 주체 (SYSTEM/SCHEDULER/MANUAL) */
    @Column(length = 50)
    var triggeredBy: String = "SYSTEM"
)
