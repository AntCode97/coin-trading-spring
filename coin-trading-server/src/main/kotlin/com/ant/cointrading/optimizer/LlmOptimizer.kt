package com.ant.cointrading.optimizer

import com.ant.cointrading.config.LlmProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.AuditLogEntity
import com.ant.cointrading.repository.AuditLogRepository
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
 * 지원 모델 (ModelSelector를 통해 동적 선택):
 * - Anthropic Claude
 * - OpenAI GPT
 * - Google Gemini
 *
 * 모델 변경:
 * - API를 통해 llm.model.provider 키 변경
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
    private val tradingProperties: TradingProperties,
    private val optimizerTools: OptimizerTools,
    private val slackNotifier: SlackNotifier,
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
    private val modelSelector: ModelSelector
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
     * 현재 설정된 모델로 ChatClient 생성
     */
    private fun createChatClient(): ChatClient {
        return modelSelector.createChatClient(optimizerTools)
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
     * 시스템 프롬프트 생성
     */
    private fun buildSystemPrompt(): String {
        return """
당신은 암호화폐 자동 매매 시스템의 전략 최적화 전문가입니다.

## 역할
- 거래 성과를 분석하고 전략 파라미터를 최적화합니다.
- 제공된 도구(Tool)를 사용하여 데이터를 조회하고 설정을 변경합니다.

## 사용 가능한 도구

### 분석 도구
- getPerformanceSummary(days): 최근 N일 성과 요약 조회
- getStrategyPerformance(): 전략별 성과 비교
- getDailyPerformance(days): 일별 성과 추이
- getOptimizationReport(): 시스템 최적화 리포트
- getRecentTrades(limit): 최근 거래 내역

### 설정 조회 도구
- getStrategyConfig(): 현재 전략 설정 조회
- getStrategyGuide(): 전략별 가이드

### 설정 변경 도구 (신중하게 사용)
- setStrategy(strategyType): 전략 유형 변경
- setMeanReversionThreshold(threshold): Mean Reversion 임계값 변경
- setRsiThresholds(oversold, overbought): RSI 임계값 변경
- setBollingerParams(period, stdDev): 볼린저 밴드 파라미터 변경
- setGridLevels(levels): Grid 레벨 수 변경
- setGridSpacing(spacingPercent): Grid 간격 변경
- setDcaInterval(intervalMs): DCA 매수 간격 변경

## 주의사항
1. 먼저 getPerformanceSummary와 getStrategyConfig로 현재 상태를 파악하세요.
2. getOptimizationReport로 시스템 권장사항을 확인하세요.
3. 파라미터 변경은 명확한 근거가 있을 때만 수행하세요.
4. 급격한 변경보다 점진적인 조정을 선호하세요.
5. 변경 후 결과를 명확히 요약해 주세요.

## 과적합 방지
- 최근 며칠의 데이터만으로 큰 변경을 하지 마세요.
- 30일 이상의 데이터를 기반으로 판단하세요.
- 확실하지 않으면 변경하지 않는 것이 낫습니다.
""".trimIndent()
    }

    /**
     * 사용자 프롬프트 생성
     */
    private fun buildUserPrompt(): String {
        return """
최근 30일간의 거래 성과를 분석하고, 필요한 경우 전략 파라미터를 최적화해 주세요.

다음 단계로 진행해 주세요:
1. getPerformanceSummary(30)로 최근 30일 성과 확인
2. getStrategyConfig()로 현재 전략 설정 확인
3. getOptimizationReport()로 시스템 권장사항 확인
4. 필요시 getStrategyPerformance()로 전략별 성과 비교
5. 분석 결과를 바탕으로 파라미터 조정 여부 결정
6. 조정이 필요하면 해당 설정 변경 도구 호출
7. 최종 분석 결과 및 조치 사항 요약

변경이 필요 없다면 "현재 설정 유지"라고 답변하고 그 이유를 설명해 주세요.
""".trimIndent()
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
        return mapOf(
            "lastOptimizationTime" to lastOptimizationTime?.toString(),
            "lastParameterChangeTime" to lastParameterChangeTime?.toString(),
            "summary" to lastOptimizationSummary,
            "enabled" to llmProperties.enabled,
            "cron" to llmProperties.cron,
            "currentProvider" to modelStatus["currentProvider"],
            "currentModel" to modelStatus["currentModelName"],
            "availableProviders" to modelStatus["availableProviders"],
            "canApplyChanges" to canApplyChanges()
        )
    }

    /**
     * 감사 로그 조회
     */
    fun getAuditLogs(limit: Int = 100): List<AuditLogEntity> {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc().take(limit)
    }
}
