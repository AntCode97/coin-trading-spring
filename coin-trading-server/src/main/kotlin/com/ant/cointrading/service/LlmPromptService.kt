package com.ant.cointrading.service

import com.ant.cointrading.repository.LlmPromptEntity
import com.ant.cointrading.repository.LlmPromptRepository
import com.ant.cointrading.repository.PromptType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * LLM 프롬프트 서비스
 *
 * 프롬프트 조회, 업데이트, 버전 관리
 */
@Service
class LlmPromptService(
    private val llmPromptRepository: LlmPromptRepository
) {
    private val log = LoggerFactory.getLogger(LlmPromptService::class.java)

    companion object {
        const val OPTIMIZER_SYSTEM_PROMPT = "optimizer_system"
        const val OPTIMIZER_USER_PROMPT = "optimizer_user"

        // 기본 프롬프트 (DB에 없을 때 사용)
        val DEFAULT_SYSTEM_PROMPT = """
당신은 암호화폐 자동 매매 시스템의 전략 최적화 전문가입니다.

## 역할
- 거래 성과를 분석하고 전략 파라미터를 최적화합니다.
- 제공된 도구(Tool)를 사용하여 데이터를 조회하고 설정을 변경합니다.

## 주의사항
1. 먼저 getPerformanceSummary와 getStrategyConfig로 현재 상태를 파악하세요.
2. 파라미터 변경은 명확한 근거가 있을 때만 수행하세요.
3. 급격한 변경보다 점진적인 조정을 선호하세요.
""".trimIndent()

        val DEFAULT_USER_PROMPT = """
최근 30일간의 거래 성과를 분석하고, 필요한 경우 전략 파라미터를 최적화해 주세요.

다음 단계로 진행해 주세요:
1. getPerformanceSummary(30)로 최근 30일 성과 확인
2. getStrategyConfig()로 현재 전략 설정 확인
3. 분석 결과를 바탕으로 파라미터 조정 여부 결정
4. 최종 분석 결과 및 조치 사항 요약
""".trimIndent()
    }

    /**
     * 활성 프롬프트 조회 (없으면 기본값)
     */
    fun getActivePrompt(promptName: String): String {
        val entity = llmPromptRepository.findByPromptNameAndIsActiveTrue(promptName)

        if (entity != null) {
            // 사용 횟수 증가
            entity.incrementUsage()
            llmPromptRepository.save(entity)
            return entity.content
        }

        // DB에 없으면 기본값 반환
        return when (promptName) {
            OPTIMIZER_SYSTEM_PROMPT -> DEFAULT_SYSTEM_PROMPT
            OPTIMIZER_USER_PROMPT -> DEFAULT_USER_PROMPT
            else -> throw IllegalArgumentException("Unknown prompt: $promptName")
        }
    }

    /**
     * 시스템 프롬프트 조회
     */
    fun getSystemPrompt(): String = getActivePrompt(OPTIMIZER_SYSTEM_PROMPT)

    /**
     * 사용자 프롬프트 조회
     */
    fun getUserPrompt(): String = getActivePrompt(OPTIMIZER_USER_PROMPT)

    /**
     * 프롬프트 히스토리 조회
     */
    fun getPromptHistory(promptName: String): List<PromptHistoryDto> {
        return llmPromptRepository.findByPromptNameOrderByVersionDesc(promptName)
            .map { entity ->
                PromptHistoryDto(
                    version = entity.version,
                    isActive = entity.isActive,
                    createdBy = entity.createdBy,
                    changeReason = entity.changeReason,
                    usageCount = entity.usageCount,
                    performanceScore = entity.performanceScore?.toString(),
                    contentPreview = entity.content.take(200) + if (entity.content.length > 200) "..." else "",
                    createdAt = entity.createdAt.toString()
                )
            }
    }

    /**
     * 프롬프트 업데이트 (새 버전 생성)
     */
    @Transactional
    fun updatePrompt(
        promptName: String,
        newContent: String,
        reason: String,
        createdBy: String = "LLM"
    ): PromptUpdateResult {
        // 검증
        if (newContent.isBlank()) {
            return PromptUpdateResult(
                success = false,
                message = "프롬프트 내용이 비어있습니다."
            )
        }

        if (newContent.length < 100) {
            return PromptUpdateResult(
                success = false,
                message = "프롬프트가 너무 짧습니다. 최소 100자 이상이어야 합니다."
            )
        }

        if (reason.isBlank()) {
            return PromptUpdateResult(
                success = false,
                message = "변경 이유를 명시해야 합니다."
            )
        }

        // 프롬프트 타입 결정
        val promptType = when {
            promptName.contains("system", ignoreCase = true) -> PromptType.SYSTEM
            promptName.contains("user", ignoreCase = true) -> PromptType.USER
            else -> PromptType.USER
        }

        // 기존 활성 프롬프트 비활성화
        val deactivatedCount = llmPromptRepository.deactivateByPromptName(promptName)

        // 새 버전 번호
        val newVersion = llmPromptRepository.findMaxVersionByPromptName(promptName) + 1

        // 새 프롬프트 저장
        val newEntity = LlmPromptEntity(
            promptType = promptType,
            promptName = promptName,
            content = newContent,
            version = newVersion,
            isActive = true,
            createdBy = createdBy,
            changeReason = reason
        )
        llmPromptRepository.save(newEntity)

        log.info("프롬프트 업데이트: $promptName v$newVersion by $createdBy - $reason")

        return PromptUpdateResult(
            success = true,
            message = "프롬프트가 v$newVersion 으로 업데이트되었습니다.",
            newVersion = newVersion,
            previousVersion = if (deactivatedCount > 0) newVersion - 1 else null
        )
    }

    /**
     * 성과 점수 업데이트
     */
    @Transactional
    fun updatePerformanceScore(promptName: String, score: Double) {
        val entity = llmPromptRepository.findByPromptNameAndIsActiveTrue(promptName)
        if (entity != null) {
            entity.performanceScore = score.toBigDecimal()
            llmPromptRepository.save(entity)
            log.info("프롬프트 성과 점수 업데이트: $promptName = $score")
        }
    }

    /**
     * 모든 활성 프롬프트 조회
     */
    fun getAllActivePrompts(): List<ActivePromptDto> {
        return llmPromptRepository.findByIsActiveTrue()
            .map { entity ->
                ActivePromptDto(
                    promptName = entity.promptName,
                    promptType = entity.promptType.name,
                    version = entity.version,
                    usageCount = entity.usageCount,
                    performanceScore = entity.performanceScore?.toString(),
                    createdBy = entity.createdBy,
                    createdAt = entity.createdAt.toString()
                )
            }
    }
}

data class PromptHistoryDto(
    val version: Int,
    val isActive: Boolean,
    val createdBy: String,
    val changeReason: String?,
    val usageCount: Int,
    val performanceScore: String?,
    val contentPreview: String,
    val createdAt: String
)

data class PromptUpdateResult(
    val success: Boolean,
    val message: String,
    val newVersion: Int? = null,
    val previousVersion: Int? = null
)

data class ActivePromptDto(
    val promptName: String,
    val promptType: String,
    val version: Int,
    val usageCount: Int,
    val performanceScore: String?,
    val createdBy: String,
    val createdAt: String
)
