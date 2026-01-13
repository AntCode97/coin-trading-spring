package com.ant.cointrading.volumesurge

import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.*
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.service.ModelSelector
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Volume Surge 전략 회고 시스템
 *
 * LlmOptimizer 패턴을 따라 일일 트레이드를 분석하고
 * 학습/개선점을 도출한다.
 */
@Component
class VolumeSurgeReflector(
    private val properties: VolumeSurgeProperties,
    private val modelSelector: ModelSelector,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val summaryRepository: VolumeSurgeDailySummaryRepository,
    private val keyValueService: KeyValueService,
    private val slackNotifier: SlackNotifier,
    private val objectMapper: ObjectMapper,
    private val reflectorTools: VolumeSurgeReflectorTools
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY_LAST_REFLECTION = "volumesurge.last_reflection"
    }

    private val systemPrompt = """
        당신은 암호화폐 트레이딩 시스템의 회고 분석가입니다.
        오늘의 거래량 급등 전략 트레이드를 분석하고 개선점을 제안하세요.

        분석 관점:
        1. 성공/실패 패턴 분석
           - 성공한 트레이드의 공통점 (RSI, MACD, 볼린저밴드, 거래량 비율)
           - 실패한 트레이드의 공통점
        2. LLM 필터 정확도 평가
           - 승인 후 성공률
           - 거부했어야 할 케이스
        3. 파라미터 조정 제안
           - 진입 조건 (RSI, 컨플루언스 점수 등)
           - 청산 조건 (손절, 익절, 타임아웃)
        4. 내일 주의해야 할 점
        5. 시스템 개선 제안 (중요!)
           - 현재 시스템에 없지만 있으면 좋을 기능
           - 더 나은 분석을 위해 필요한 데이터
           - 자동화할 수 있는 수동 작업
           - 추가 API 연동 제안 (예: 특정 거래소, 뉴스 소스)

        도구 사용:
        - getTodayStats: 오늘의 통계 조회
        - getTodayTrades: 오늘의 트레이드 목록 조회
        - saveReflection: 회고 결과 저장
        - suggestParameterChange: 파라미터 변경 제안 (Slack 알림 발송됨)
        - suggestSystemImprovement: 시스템 개선 아이디어 제안 (Slack 알림 발송됨)

        반드시 모든 도구를 사용하여 분석을 완료하세요.
        특히 시스템 개선 아이디어가 있으면 suggestSystemImprovement를 호출해주세요.
    """.trimIndent()

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Volume Surge Reflector 활성화 ===")
            log.info("회고 크론: ${properties.reflectionCron}")
        }
    }

    /**
     * 일일 회고 실행 (매일 새벽 1시)
     */
    @Scheduled(cron = "\${volumesurge.reflection-cron:0 0 1 * * *}")
    fun runDailyReflection() {
        if (!properties.enabled) {
            return
        }

        log.info("=== Volume Surge 일일 회고 시작 ===")

        try {
            val result = reflect()
            log.info("=== Volume Surge 일일 회고 완료 ===")
            log.info(result)

            // Slack 알림
            slackNotifier.sendSystemNotification("Volume Surge 일일 회고", result)

        } catch (e: Exception) {
            log.error("Volume Surge 회고 실패: ${e.message}", e)
            slackNotifier.sendError("VOLUME_SURGE", "일일 회고 실패: ${e.message}")
        }
    }

    /**
     * 수동 회고 실행
     */
    fun runManualReflection(): String {
        return reflect()
    }

    /**
     * 회고 실행
     */
    private fun reflect(): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        // 오늘의 트레이드 조회
        val todayTrades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfDay, endOfDay)

        if (todayTrades.isEmpty()) {
            log.info("오늘 트레이드 없음")
            return "오늘 트레이드가 없어 회고를 건너뜁니다."
        }

        // 통계 계산
        val stats = calculateDailyStats(today, startOfDay, endOfDay)

        // LLM 회고 요청
        val userPrompt = buildUserPrompt(stats, todayTrades)

        try {
            val chatClient = modelSelector.getChatClient()
                .mutate()
                .defaultTools(reflectorTools)
                .build()

            val response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()

            // 마지막 회고 시각 저장
            keyValueService.set(KEY_LAST_REFLECTION, Instant.now().toString())

            return response ?: "LLM 응답 없음"

        } catch (e: Exception) {
            log.error("LLM 회고 호출 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 일일 통계 계산
     */
    private fun calculateDailyStats(
        date: LocalDate,
        startOfDay: Instant,
        endOfDay: Instant
    ): DailyStats {
        val totalAlerts = alertRepository.countByDetectedAtBetween(startOfDay, endOfDay).toInt()
        val approvedAlerts = alertRepository.countApprovedBetween(startOfDay, endOfDay).toInt()

        val closedTrades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfDay, endOfDay)
            .filter { it.status == "CLOSED" }

        val totalTrades = closedTrades.size
        val winningTrades = closedTrades.count { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = closedTrades.count { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = closedTrades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        val avgHoldingMinutes = closedTrades
            .filter { it.exitTime != null }
            .map { ChronoUnit.MINUTES.between(it.entryTime, it.exitTime) }
            .average()
            .takeIf { !it.isNaN() }

        return DailyStats(
            date = date,
            totalAlerts = totalAlerts,
            approvedAlerts = approvedAlerts,
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            totalPnl = totalPnl,
            winRate = winRate,
            avgHoldingMinutes = avgHoldingMinutes
        )
    }

    /**
     * 사용자 프롬프트 생성
     */
    private fun buildUserPrompt(stats: DailyStats, trades: List<VolumeSurgeTradeEntity>): String {
        val successCases = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
            .take(5)
            .joinToString("\n") { formatTradeCase(it, "성공") }

        val failureCases = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }
            .take(5)
            .joinToString("\n") { formatTradeCase(it, "실패") }

        return """
            오늘 날짜: ${stats.date}

            === 오늘 통계 ===
            총 경보: ${stats.totalAlerts}건
            승인된 경보: ${stats.approvedAlerts}건 (${if (stats.totalAlerts > 0) (stats.approvedAlerts * 100 / stats.totalAlerts) else 0}%)
            총 트레이드: ${stats.totalTrades}건
            승리: ${stats.winningTrades}건 / 패배: ${stats.losingTrades}건
            승률: ${String.format("%.1f", stats.winRate * 100)}%
            총 손익: ${String.format("%.0f", stats.totalPnl)}원
            평균 보유시간: ${stats.avgHoldingMinutes?.let { String.format("%.1f", it) } ?: "N/A"}분

            === 성공 케이스 (최대 5건) ===
            $successCases

            === 실패 케이스 (최대 5건) ===
            $failureCases

            위 데이터를 분석하고 도구를 사용하여:
            1. getTodayStats로 통계 확인
            2. getTodayTrades로 상세 트레이드 조회
            3. 패턴 분석 후 saveReflection으로 회고 저장
            4. 필요시 suggestParameterChange로 파라미터 변경 제안
        """.trimIndent()
    }

    private fun formatTradeCase(trade: VolumeSurgeTradeEntity, type: String): String {
        return """
            [$type] ${trade.market}
            진입: ${trade.entryPrice}원 → 청산: ${trade.exitPrice}원
            손익: ${trade.pnlAmount?.let { String.format("%.0f", it) }}원 (${trade.pnlPercent?.let { String.format("%.2f", it) }}%)
            RSI: ${trade.entryRsi} | MACD: ${trade.entryMacdSignal} | BB: ${trade.entryBollingerPosition}
            거래량비율: ${trade.entryVolumeRatio} | 컨플루언스: ${trade.confluenceScore}
            청산사유: ${trade.exitReason}
        """.trimIndent()
    }
}

/**
 * 일일 통계
 */
data class DailyStats(
    val date: LocalDate,
    val totalAlerts: Int,
    val approvedAlerts: Int,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val totalPnl: Double,
    val winRate: Double,
    val avgHoldingMinutes: Double?
)

/**
 * Volume Surge 회고용 LLM 도구
 */
@Component
class VolumeSurgeReflectorTools(
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val summaryRepository: VolumeSurgeDailySummaryRepository,
    private val keyValueService: KeyValueService,
    private val objectMapper: ObjectMapper,
    private val slackNotifier: SlackNotifier,
    private val volumeSurgeProperties: VolumeSurgeProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 변경 가능한 파라미터 목록과 KeyValue 키 매핑 */
        val ALLOWED_PARAMS = mapOf(
            "minVolumeRatio" to "volumesurge.minVolumeRatio",
            "maxRsi" to "volumesurge.maxRsi",
            "minConfluenceScore" to "volumesurge.minConfluenceScore",
            "stopLossPercent" to "volumesurge.stopLossPercent",
            "takeProfitPercent" to "volumesurge.takeProfitPercent",
            "trailingStopTrigger" to "volumesurge.trailingStopTrigger",
            "trailingStopOffset" to "volumesurge.trailingStopOffset",
            "positionTimeoutMin" to "volumesurge.positionTimeoutMin",
            "cooldownMin" to "volumesurge.cooldownMin",
            "maxConsecutiveLosses" to "volumesurge.maxConsecutiveLosses",
            "dailyMaxLossKrw" to "volumesurge.dailyMaxLossKrw",
            "positionSizeKrw" to "volumesurge.positionSizeKrw",
            "alertFreshnessMin" to "volumesurge.alertFreshnessMin"
        )
    }

    @Tool(description = "오늘의 Volume Surge 전략 통계를 조회합니다")
    fun getTodayStats(): String {
        log.info("[Tool] getTodayStats")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val totalAlerts = alertRepository.countByDetectedAtBetween(startOfDay, endOfDay)
        val approvedAlerts = alertRepository.countApprovedBetween(startOfDay, endOfDay)
        val totalPnl = tradeRepository.sumPnlBetween(startOfDay, endOfDay)
        val winCount = tradeRepository.countWinningBetween(startOfDay, endOfDay)
        val loseCount = tradeRepository.countLosingBetween(startOfDay, endOfDay)
        val avgHolding = tradeRepository.avgHoldingMinutesBetween(startOfDay, endOfDay)

        return """
            === 오늘 통계 ($today) ===
            총 경보: ${totalAlerts}건
            승인된 경보: ${approvedAlerts}건
            총 손익: ${String.format("%.0f", totalPnl)}원
            승리/패배: $winCount / $loseCount
            평균 보유시간: ${avgHolding?.let { String.format("%.1f", it) } ?: "N/A"}분
        """.trimIndent()
    }

    @Tool(description = "오늘의 트레이드 목록을 조회합니다")
    fun getTodayTrades(
        @ToolParam(description = "조회할 트레이드 수 (기본 20)") limit: Int = 20
    ): String {
        log.info("[Tool] getTodayTrades: limit=$limit")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfDay, endOfDay)
            .take(limit)

        if (trades.isEmpty()) {
            return "오늘 트레이드가 없습니다."
        }

        return trades.joinToString("\n\n") { trade ->
            val result = if ((trade.pnlAmount ?: 0.0) > 0) "성공" else "실패"
            """
                [$result] ${trade.market} (ID: ${trade.id})
                진입: ${trade.entryPrice}원 → 청산: ${trade.exitPrice ?: "미청산"}원
                손익: ${trade.pnlAmount?.let { String.format("%.0f", it) } ?: "N/A"}원
                RSI: ${trade.entryRsi} | MACD: ${trade.entryMacdSignal}
                BB: ${trade.entryBollingerPosition} | 거래량: ${trade.entryVolumeRatio}
                컨플루언스: ${trade.confluenceScore} | 청산사유: ${trade.exitReason ?: "N/A"}
            """.trimIndent()
        }
    }

    @Tool(description = """
        오늘의 회고 결과를 저장합니다.
        반드시 분석 완료 후 호출하세요.
    """)
    fun saveReflection(
        @ToolParam(description = "회고 요약") summary: String,
        @ToolParam(description = "주요 발견 사항") findings: String,
        @ToolParam(description = "내일 주의점") tomorrowFocus: String
    ): String {
        log.info("[Tool] saveReflection")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        // 기존 요약이 있으면 업데이트, 없으면 생성
        val existingSummary = summaryRepository.findByDate(today)
        val summaryEntity = existingSummary ?: VolumeSurgeDailySummaryEntity(date = today)

        summaryEntity.reflectionSummary = """
            === 회고 요약 ===
            $summary

            === 주요 발견 ===
            $findings

            === 내일 주의점 ===
            $tomorrowFocus
        """.trimIndent()

        summaryRepository.save(summaryEntity)

        return "회고가 저장되었습니다."
    }

    @Tool(description = """
        파라미터를 변경합니다.
        변경이 필요한 경우에만 호출하세요.
        실제 파라미터가 변경되고 Slack 알림이 발송됩니다.

        허용된 파라미터:
        - minVolumeRatio: 최소 거래량 비율 (기본 3.0)
        - maxRsi: 최대 RSI (기본 70.0)
        - minConfluenceScore: 최소 컨플루언스 점수 (기본 60)
        - stopLossPercent: 손절 퍼센트 (기본 -2.0)
        - takeProfitPercent: 익절 퍼센트 (기본 5.0)
        - trailingStopTrigger: 트레일링 스탑 시작 (기본 2.0)
        - trailingStopOffset: 트레일링 스탑 오프셋 (기본 1.0)
        - positionTimeoutMin: 포지션 타임아웃 분 (기본 30)
        - cooldownMin: 재진입 쿨다운 분 (기본 60)
        - maxConsecutiveLosses: 연속 손실 제한 (기본 3)
        - dailyMaxLossKrw: 일일 최대 손실 원 (기본 30000)
        - positionSizeKrw: 포지션 크기 원 (기본 10000)
        - alertFreshnessMin: 경보 신선도 분 (기본 5)
    """)
    fun suggestParameterChange(
        @ToolParam(description = "파라미터 이름 (예: minVolumeRatio, maxRsi, minConfluenceScore)") paramName: String,
        @ToolParam(description = "새로운 값") newValue: Double,
        @ToolParam(description = "변경 이유") reason: String
    ): String {
        log.info("[Tool] suggestParameterChange: $paramName -> $newValue")

        // 허용된 파라미터인지 검증
        val keyValueKey = ALLOWED_PARAMS[paramName]
        if (keyValueKey == null) {
            log.warn("허용되지 않은 파라미터: $paramName")
            return """
                오류: 허용되지 않은 파라미터입니다.
                파라미터명: $paramName

                허용된 파라미터 목록:
                ${ALLOWED_PARAMS.keys.joinToString(", ")}
            """.trimIndent()
        }

        // 현재 값 조회
        val currentValue = getCurrentParamValue(paramName)

        // 값이 동일하면 변경 불필요
        if (currentValue == newValue) {
            return "파라미터 $paramName 의 현재값($currentValue)과 새 값($newValue)이 동일하여 변경하지 않았습니다."
        }

        try {
            // 1. KeyValueService에 저장 (영속성 - 재시작 후에도 유지)
            keyValueService.set(
                key = keyValueKey,
                value = newValue.toString(),
                category = "volumesurge",
                description = "LLM 자동 변경: $reason"
            )

            // 2. VolumeSurgeProperties 필드 직접 변경 (즉시 반영)
            applyParamToProperties(paramName, newValue)

            // 3. DB 요약에 변경 기록 저장
            val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val existingSummary = summaryRepository.findByDate(today)
                ?: VolumeSurgeDailySummaryEntity(date = today)

            val changeRecord = mapOf(
                "param" to paramName,
                "oldValue" to currentValue,
                "newValue" to newValue,
                "reason" to reason,
                "timestamp" to Instant.now().toString(),
                "applied" to true
            )

            val existingChanges = existingSummary.parameterChanges?.let {
                try {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(it, List::class.java) as List<Map<String, Any>>
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            val updatedChanges = existingChanges + changeRecord
            existingSummary.parameterChanges = objectMapper.writeValueAsString(updatedChanges)
            summaryRepository.save(existingSummary)

            // 4. Slack 알림 발송
            slackNotifier.sendSystemNotification(
                "[자동] 파라미터 변경 완료",
                """
                    파라미터: $paramName
                    변경: $currentValue → $newValue
                    이유: $reason

                    KeyValue 키: $keyValueKey
                    즉시 반영됨 (재시작 후에도 유지)
                """.trimIndent()
            )

            log.info("파라미터 변경 완료: $paramName = $currentValue -> $newValue")

            return """
                파라미터 변경 완료:
                - $paramName: $currentValue → $newValue
                - 이유: $reason
                - KeyValue 저장: $keyValueKey
                - 즉시 반영됨
            """.trimIndent()

        } catch (e: Exception) {
            log.error("파라미터 변경 실패: $paramName, error: ${e.message}", e)

            slackNotifier.sendError(
                "VOLUME_SURGE",
                "파라미터 변경 실패: $paramName -> $newValue, 오류: ${e.message}"
            )

            return """
                오류: 파라미터 변경 실패
                - 파라미터: $paramName
                - 오류: ${e.message}
            """.trimIndent()
        }
    }

    /**
     * 현재 파라미터 값 조회
     */
    private fun getCurrentParamValue(paramName: String): Double {
        return when (paramName) {
            "minVolumeRatio" -> volumeSurgeProperties.minVolumeRatio
            "maxRsi" -> volumeSurgeProperties.maxRsi
            "minConfluenceScore" -> volumeSurgeProperties.minConfluenceScore.toDouble()
            "stopLossPercent" -> volumeSurgeProperties.stopLossPercent
            "takeProfitPercent" -> volumeSurgeProperties.takeProfitPercent
            "trailingStopTrigger" -> volumeSurgeProperties.trailingStopTrigger
            "trailingStopOffset" -> volumeSurgeProperties.trailingStopOffset
            "positionTimeoutMin" -> volumeSurgeProperties.positionTimeoutMin.toDouble()
            "cooldownMin" -> volumeSurgeProperties.cooldownMin.toDouble()
            "maxConsecutiveLosses" -> volumeSurgeProperties.maxConsecutiveLosses.toDouble()
            "dailyMaxLossKrw" -> volumeSurgeProperties.dailyMaxLossKrw.toDouble()
            "positionSizeKrw" -> volumeSurgeProperties.positionSizeKrw.toDouble()
            "alertFreshnessMin" -> volumeSurgeProperties.alertFreshnessMin.toDouble()
            else -> 0.0
        }
    }

    /**
     * VolumeSurgeProperties에 파라미터 값 적용
     */
    private fun applyParamToProperties(paramName: String, value: Double) {
        when (paramName) {
            "minVolumeRatio" -> volumeSurgeProperties.minVolumeRatio = value
            "maxRsi" -> volumeSurgeProperties.maxRsi = value
            "minConfluenceScore" -> volumeSurgeProperties.minConfluenceScore = value.toInt()
            "stopLossPercent" -> volumeSurgeProperties.stopLossPercent = value
            "takeProfitPercent" -> volumeSurgeProperties.takeProfitPercent = value
            "trailingStopTrigger" -> volumeSurgeProperties.trailingStopTrigger = value
            "trailingStopOffset" -> volumeSurgeProperties.trailingStopOffset = value
            "positionTimeoutMin" -> volumeSurgeProperties.positionTimeoutMin = value.toInt()
            "cooldownMin" -> volumeSurgeProperties.cooldownMin = value.toInt()
            "maxConsecutiveLosses" -> volumeSurgeProperties.maxConsecutiveLosses = value.toInt()
            "dailyMaxLossKrw" -> volumeSurgeProperties.dailyMaxLossKrw = value.toInt()
            "positionSizeKrw" -> volumeSurgeProperties.positionSizeKrw = value.toInt()
            "alertFreshnessMin" -> volumeSurgeProperties.alertFreshnessMin = value.toInt()
        }
    }

    @Tool(description = """
        시스템 개선 아이디어를 제안합니다.
        현재 시스템에 없지만 있으면 좋을 기능이나 개선점이 있으면 호출하세요.
        Slack 알림이 발송되어 개발자가 검토할 수 있습니다.
    """)
    fun suggestSystemImprovement(
        @ToolParam(description = "제안 제목 (간결하게)") title: String,
        @ToolParam(description = "상세 설명 (왜 필요한지, 어떤 효과가 있을지)") description: String,
        @ToolParam(description = "우선순위 (HIGH/MEDIUM/LOW)") priority: String,
        @ToolParam(description = "카테고리 (FEATURE/DATA/AUTOMATION/INTEGRATION)") category: String
    ): String {
        log.info("[Tool] suggestSystemImprovement: $title ($priority)")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        // DB에 제안 기록 저장
        val existingSummary = summaryRepository.findByDate(today)
            ?: VolumeSurgeDailySummaryEntity(date = today)

        val improvementRecord = mapOf(
            "title" to title,
            "description" to description,
            "priority" to priority.uppercase(),
            "category" to category.uppercase(),
            "timestamp" to Instant.now().toString()
        )

        // 기존 반영 노트에 추가
        val existingNotes = existingSummary.reflectionSummary ?: ""
        existingSummary.reflectionSummary = """
            $existingNotes

            === 시스템 개선 제안 ===
            [$priority] $title
            카테고리: $category
            $description
        """.trimIndent()

        summaryRepository.save(existingSummary)

        // Slack 알림 발송 (개발자가 바로 확인)
        val priorityEmoji = when (priority.uppercase()) {
            "HIGH" -> "[긴급]"
            "MEDIUM" -> "[보통]"
            else -> "[참고]"
        }

        slackNotifier.sendSystemNotification(
            "$priorityEmoji 시스템 개선 제안",
            """
                제목: $title
                카테고리: $category
                우선순위: $priority

                $description

                ---
                LLM 회고 시스템이 자동 생성한 제안입니다.
            """.trimIndent()
        )

        return """
            시스템 개선 제안이 기록되고 Slack 알림이 발송되었습니다:
            - 제목: $title
            - 카테고리: $category
            - 우선순위: $priority
        """.trimIndent()
    }
}
