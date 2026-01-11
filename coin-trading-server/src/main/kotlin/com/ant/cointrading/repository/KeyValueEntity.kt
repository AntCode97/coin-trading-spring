package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant

/**
 * Key-Value 저장소 엔티티
 * Redis 대체용 동적 설정 관리
 */
@Entity
@Table(name = "key_value_store", indexes = [
    Index(name = "idx_kv_key", columnList = "kv_key", unique = true),
    Index(name = "idx_kv_category", columnList = "category")
])
class KeyValueEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 설정 키 (예: llm.model.provider) */
    @Column(name = "kv_key", nullable = false, unique = true, length = 100)
    var key: String = "",

    /** 설정 값 */
    @Column(name = "kv_value", nullable = false, length = 2000)
    var value: String = "",

    /** 카테고리 (llm/trading/system) */
    @Column(length = 50)
    var category: String? = null,

    /** 설정 설명 */
    @Column(length = 500)
    var description: String? = null,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 마지막 갱신 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
