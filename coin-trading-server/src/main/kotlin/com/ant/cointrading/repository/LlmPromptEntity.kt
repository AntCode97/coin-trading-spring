package com.ant.cointrading.repository

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * LLM 프롬프트 엔티티 (자기 개선형)
 *
 * LLM이 스스로 프롬프트를 개선할 수 있도록 버전 관리
 */
@Entity
@Table(
    name = "llm_prompts",
    indexes = [
        Index(name = "idx_prompt_type_name", columnList = "promptType, promptName"),
        Index(name = "idx_active", columnList = "isActive"),
        Index(name = "idx_version", columnList = "promptName, version")
    ]
)
data class LlmPromptEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val promptType: PromptType,

    @Column(nullable = false, length = 100)
    val promptName: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(nullable = false)
    val version: Int = 1,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column(nullable = false, length = 50)
    val createdBy: String,  // SYSTEM, LLM, HUMAN

    @Column(precision = 5, scale = 2)
    var performanceScore: BigDecimal? = null,

    @Column(nullable = false)
    var usageCount: Int = 0,

    @Column(columnDefinition = "TEXT")
    var changeReason: String? = null,

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    fun incrementUsage() {
        usageCount++
    }
}

enum class PromptType {
    SYSTEM,
    USER
}
