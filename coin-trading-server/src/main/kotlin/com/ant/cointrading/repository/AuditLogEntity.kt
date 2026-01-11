package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant

/**
 * LLM 결정 감사 로그
 *
 * 모든 LLM 최적화 결정을 추적하여:
 * 1. 결정 이력 조회
 * 2. 문제 발생 시 원인 분석
 * 3. LLM 권장사항 품질 평가
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
data class AuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 50)
    val eventType: String,  // LLM_OPTIMIZATION, STRATEGY_CHANGE, CIRCUIT_BREAKER, PARAMETER_CHANGE

    @Column(length = 20)
    val market: String? = null,

    @Column(nullable = false, length = 100)
    val action: String,  // 수행된 동작

    @Column(columnDefinition = "TEXT")
    val inputData: String? = null,  // LLM에 입력된 데이터 (JSON)

    @Column(columnDefinition = "TEXT")
    val outputData: String? = null,  // LLM 응답 또는 결과 (JSON)

    @Column(length = 500)
    val reason: String? = null,  // 결정 이유

    val confidence: Double? = null,  // 신뢰도

    val applied: Boolean = false,  // 실제 적용 여부

    @Column(length = 500)
    val rejectionReason: String? = null,  // 거부된 경우 사유

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(length = 50)
    val triggeredBy: String = "SYSTEM"  // SCHEDULED, MANUAL, API
)
