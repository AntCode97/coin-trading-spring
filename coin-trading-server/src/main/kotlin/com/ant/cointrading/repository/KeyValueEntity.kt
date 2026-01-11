package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant

/**
 * Key-Value 저장소 엔티티 (Redis 대체)
 *
 * MySQL 기반 간단한 키-값 저장소.
 * 동적 설정, 런타임 변경이 필요한 값들을 저장.
 *
 * 주요 키:
 * - llm.model.provider: 사용할 LLM 제공자 (anthropic, openai, google)
 * - llm.model.name: 사용할 모델명 (claude-sonnet-4-20250514 등)
 * - trading.enabled: 거래 활성화 여부
 * - system.maintenance: 시스템 점검 모드
 */
@Entity
@Table(name = "key_value_store", indexes = [
    Index(name = "idx_kv_key", columnList = "kv_key", unique = true),
    Index(name = "idx_kv_category", columnList = "category")
])
data class KeyValueEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "kv_key", nullable = false, unique = true, length = 100)
    val key: String,

    @Column(name = "kv_value", nullable = false, length = 2000)
    var value: String,

    @Column(length = 50)
    val category: String? = null,  // llm, trading, system 등

    @Column(length = 500)
    val description: String? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
