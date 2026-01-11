package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface LlmPromptRepository : JpaRepository<LlmPromptEntity, Long> {

    /**
     * 활성 프롬프트 조회
     */
    fun findByPromptNameAndIsActiveTrue(promptName: String): LlmPromptEntity?

    /**
     * 프롬프트 히스토리 조회 (버전 내림차순)
     */
    fun findByPromptNameOrderByVersionDesc(promptName: String): List<LlmPromptEntity>

    /**
     * 최신 버전 번호 조회
     */
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM LlmPromptEntity p WHERE p.promptName = :promptName")
    fun findMaxVersionByPromptName(promptName: String): Int

    /**
     * 기존 활성 프롬프트 비활성화
     */
    @Modifying
    @Query("UPDATE LlmPromptEntity p SET p.isActive = false WHERE p.promptName = :promptName AND p.isActive = true")
    fun deactivateByPromptName(promptName: String): Int

    /**
     * 모든 활성 프롬프트 조회
     */
    fun findByIsActiveTrue(): List<LlmPromptEntity>
}
