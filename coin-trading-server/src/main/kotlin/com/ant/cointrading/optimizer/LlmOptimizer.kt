package com.ant.cointrading.optimizer

import com.ant.cointrading.config.LlmProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.AuditLogEntity
import com.ant.cointrading.repository.AuditLogRepository
import com.ant.cointrading.service.LlmPromptService
import com.ant.cointrading.service.ModelSelector
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * LLM 기반 전략 최적화기 (Spring AI Tool Calling 버전)
 *
 * Spring AI ChatClient + Tool Calling을 사용하여
 * LLM이 직접 성과 분석 및 전략 조정 함수를 호출.
 *
 * 지원 모델:
 * - OpenAI GPT
 *
 * 모델 변경:
 * - API를 통해 llm.model.name 변경
 * - 다음 요청부터 새 모델 사용
 *
 * 핵심 기능:
 * 1. LLM이 OptimizerTools를 통해 성과 데이터 직접 조회
 * 2. LLM이 분석 후 전략 파라미터 직접 조정
 * 3. 모든 도구 호출 감사 로그 저장
 * 4. 안전장치 적용 (도구 내부에서 범위 검증)
 *
 * 안전장치:
 * - 파라미터 범위 검증: OptimizerTools 내부에서 수행
 * - 변경 속도 제한: 주 1회 이상 변경 금지
 * - 실행 전 확인: LLM에게 신중한 결정 유도
 */
@Component
class LlmOptimizer(
    private val llmProperties: LlmProperties,
    private val optimizerTools: OptimizerTools,
    private val optimizationValidationService: OptimizationValidationService,
    private val slackNotifier: SlackNotifier,
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
    private val modelSelector: ModelSelector,
    private val llmPromptService: LlmPromptService
) {

    private val log = LoggerFactory.getLogger(LlmOptimizer::class.java)

    // 최근 최적화 기록
    private var lastOptimizationTime: Instant? = null
    private var lastParameterChangeTime: Instant? = null
    private var lastOptimizationSummary: String = ""

    companion object {
        const val MIN_DAYS_BETWEEN_CHANGES = 7  // 최소 7일 간격으로 변경
    }

    @PostConstruct
    fun init() {
        if (llmProperties.enabled) {
            log.info("=== LLM Optimizer 활성화 (Tool Calling) ===")
            log.info("Provider: ${modelSelector.getCurrentProvider()}")
            log.info("Model: ${modelSelector.getCurrentModelName()}")
            log.info("Available: ${modelSelector.getAvailableProviders()}")
            log.info("스케줄: ${llmProperties.cron}")
        } else {
            log.info("LLM Optimizer 비활성화")
        }
    }

    /**
     * 회고/최적화용 ChatClient 생성 (무조건 OpenAI 사용)
     *
     * OpenAI가 무료 토큰을 더 많이 제공하여 회고/최적화에 적합
     */
    private fun createChatClient(): ChatClient {
        return modelSelector.getChatClientForReflection()
            .mutate()
            .defaultTools(optimizerTools)
            .build()
    }

    /**
     * 주기적 최적화 실행 (매일 자정 기본값)
     */
    @Scheduled(cron = "\${llm.optimizer.cron:0 0 0 * * *}")
    fun runScheduledOptimization() {
        if (!llmProperties.enabled) {
            return
        }

        log.info("=== LLM 최적화 시작 (Tool Calling) ===")

        try {
            val result = optimize()
            log.info("=== LLM 최적화 완료 ===")
            log.info(result)

            // 감사 로그 저장
            saveAuditLog("LLM_OPTIMIZATION", "SCHEDULED_RUN", result)

            // Slack 알림
            slackNotifier.sendSystemNotification("LLM 최적화 완료", result)

        } catch (e: Exception) {
            log.error("LLM 최적화 실패: ${e.message}", e)
            slackNotifier.sendError("SYSTEM", "LLM 최적화 실패: ${e.message}")
            saveAuditLog("LLM_OPTIMIZATION", "ERROR", e.message)
        }
    }

    /**
     * 최적화 실행
     *
     * LLM에게 도구를 제공하고, 자율적으로 분석 및 조정하도록 함.
     */
    fun optimize(): String {
        // 변경 속도 제한 체크
        if (!canApplyChanges()) {
            val daysRemaining = lastParameterChangeTime?.let {
                MIN_DAYS_BETWEEN_CHANGES - ChronoUnit.DAYS.between(it, Instant.now())
            } ?: 0

            return "변경 속도 제한: ${daysRemaining}일 후 파라미터 변경 가능"
        }

        val gateStatus = optimizationValidationService.getGateStatus()
        if (!gateStatus.canApplyChanges) {
            return """
                전략 변경 차단 (수익성 검증 실패):
                - 이유: ${gateStatus.reason}
                - 마켓: ${gateStatus.market}
                - OOS Sharpe: ${String.format("%.2f", gateStatus.avgOutOfSampleSharpe)}
                - OOS 평균 거래수: ${String.format("%.1f", gateStatus.avgOutOfSampleTrades)}
                - Sharpe Decay: ${String.format("%.1f", gateStatus.decayPercent)}%
            """.trimIndent()
        }

        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserPrompt()

        log.info("LLM 분석 요청 중... (provider: ${modelSelector.getCurrentProvider()})")

        try {
            // 매 요청마다 현재 설정된 모델로 ChatClient 생성
            val chatClient = createChatClient()

            val response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()

            lastOptimizationTime = Instant.now()
            lastOptimizationSummary = response ?: "응답 없음"

            // 파라미터 변경이 있었는지 확인 (도구 호출 로그 기반)
            if (response?.contains("변경되었습니다") == true) {
                lastParameterChangeTime = Instant.now()
            }

            return response ?: "LLM 응답 없음"

        } catch (e: Exception) {
            log.error("LLM 호출 실패: ${e.message}")
            saveAuditLog("LLM_OPTIMIZATION", "API_ERROR", e.message)
            throw e
        }
    }

    /**
     * 시스템 프롬프트 로드 (DB에서 조회, 없으면 기본값)
     */
    private fun buildSystemPrompt(): String {
        return llmPromptService.getSystemPrompt()
    }

    /**
     * 사용자 프롬프트 로드 (DB에서 조회, 없으면 기본값)
     */
    private fun buildUserPrompt(): String {
        return llmPromptService.getUserPrompt()
    }

    /**
     * 변경 가능 여부 확인 (속도 제한)
     */
    private fun canApplyChanges(): Boolean {
        val lastChange = lastParameterChangeTime ?: return true
        val daysSince = ChronoUnit.DAYS.between(lastChange, Instant.now())
        return daysSince >= MIN_DAYS_BETWEEN_CHANGES
    }

    /**
     * 감사 로그 저장
     */
    private fun saveAuditLog(eventType: String, action: String, data: Any?) {
        try {
            val entity = AuditLogEntity(
                eventType = eventType,
                action = action,
                outputData = data?.let {
                    if (it is String) it else objectMapper.writeValueAsString(it)
                },
                applied = action != "ERROR",
                triggeredBy = if (action == "SCHEDULED_RUN") "SCHEDULED" else "SYSTEM"
            )
            auditLogRepository.save(entity)
        } catch (e: Exception) {
            log.warn("감사 로그 저장 실패: ${e.message}")
        }
    }

    /**
     * 마지막 최적화 결과 조회
     */
    fun getLastOptimizationResult(): Map<String, Any?> {
        val modelStatus = modelSelector.getStatus()
        val gateStatus = optimizationValidationService.getGateStatus()
        return mapOf(
            "lastOptimizationTime" to lastOptimizationTime?.toString(),
            "lastParameterChangeTime" to lastParameterChangeTime?.toString(),
            "summary" to lastOptimizationSummary,
            "enabled" to llmProperties.enabled,
            "cron" to llmProperties.cron,
            "currentProvider" to modelStatus["currentProvider"],
            "currentModel" to modelStatus["currentModelName"],
            "availableProviders" to modelStatus["availableProviders"],
            "canApplyChanges" to canApplyChanges(),
            "validationGate" to gateStatus
        )
    }

    /**
     * 감사 로그 조회
     */
    fun getAuditLogs(limit: Int = 100): List<AuditLogEntity> {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc().take(limit)
    }
}
