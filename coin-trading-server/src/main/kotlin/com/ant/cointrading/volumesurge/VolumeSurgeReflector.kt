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

        도구 사용 (순서 중요):
        1. getTodayStats: 오늘의 통계 조회
        2. getTodayTrades: 오늘의 트레이드 목록 조회
        3. analyzeMarketCorrelation: 마켓별/지표별 상관관계 분석 (패턴 파악)
        4. analyzeHistoricalPatterns: 시간대/요일별 패턴 분석 (최적 트레이딩 시간 파악)
        5. backtestParameterChange: 파라미터 변경 전 반드시 백테스트 수행
        6. suggestParameterChange: 백테스트 통과 시에만 파라미터 변경 제안
        7. saveReflection: 회고 결과 저장
        8. suggestSystemImprovement: 시스템 개선 아이디어 제안 (Slack 알림 발송)

        중요 규칙:
        - 파라미터 변경 전 반드시 backtestParameterChange로 검증하세요.
        - 백테스트에서 트레이드 50% 이상 감소 예상 시 변경하지 마세요.
        - analyzeMarketCorrelation과 analyzeHistoricalPatterns로 패턴을 먼저 파악하세요.
        - 특히 시스템 개선 아이디어가 있으면 suggestSystemImprovement를 호출해주세요.
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
    private val volumeSurgeProperties: VolumeSurgeProperties,
    private val generalTradeRepository: TradeRepository
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

    @Tool(description = """
        파라미터 변경 전 과거 데이터로 백테스트를 수행합니다.
        파라미터 변경이 과거에 적용되었다면 어떤 결과가 나왔을지 시뮬레이션합니다.
        suggestParameterChange 호출 전에 반드시 이 도구로 검증하세요.
    """)
    fun backtestParameterChange(
        @ToolParam(description = "파라미터 이름") paramName: String,
        @ToolParam(description = "새로운 값") newValue: Double,
        @ToolParam(description = "백테스트 기간 (일)") historicalDays: Int = 7
    ): String {
        log.info("[Tool] backtestParameterChange: $paramName=$newValue, days=$historicalDays")

        val startOfPeriod = Instant.now().minus(historicalDays.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        // 기간 내 트레이드 조회
        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "백테스트 기간($historicalDays 일) 내 트레이드가 없습니다."
        }

        val currentValue = getCurrentParamValue(paramName)

        // 파라미터별 백테스트 시뮬레이션
        val (filteredIn, filteredOut) = when (paramName) {
            "minVolumeRatio" -> {
                val passedNew = trades.count { (it.entryVolumeRatio ?: 0.0) >= newValue }
                val passedOld = trades.count { (it.entryVolumeRatio ?: 0.0) >= currentValue }
                passedNew to (trades.size - passedNew)
            }
            "maxRsi" -> {
                val passedNew = trades.count { (it.entryRsi ?: 100.0) <= newValue }
                val passedOld = trades.count { (it.entryRsi ?: 100.0) <= currentValue }
                passedNew to (trades.size - passedNew)
            }
            "minConfluenceScore" -> {
                val passedNew = trades.count { (it.confluenceScore ?: 0) >= newValue.toInt() }
                val passedOld = trades.count { (it.confluenceScore ?: 0) >= currentValue.toInt() }
                passedNew to (trades.size - passedNew)
            }
            "stopLossPercent" -> {
                // 손절 변경 시 수익 변화 시뮬레이션
                val stoppedEarlier = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent < 0 && pnlPercent > newValue && pnlPercent <= currentValue
                }
                val stoppedLater = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent < 0 && pnlPercent <= newValue && pnlPercent > currentValue
                }
                stoppedEarlier to stoppedLater
            }
            "takeProfitPercent" -> {
                // 익절 변경 시 수익 변화 시뮬레이션
                val wouldHaveTakenProfit = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent > 0 && pnlPercent >= newValue
                }
                wouldHaveTakenProfit to (trades.size - wouldHaveTakenProfit)
            }
            else -> trades.size to 0
        }

        // 필터링된 트레이드의 성과 분석
        val winningTrades = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = trades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (trades.isNotEmpty()) winningTrades.size.toDouble() / trades.size * 100 else 0.0

        // 새 파라미터 적용 시 예상 결과 추정
        val estimatedTradesAfterChange = filteredIn
        val estimatedImpact = when {
            estimatedTradesAfterChange > trades.size * 0.9 -> "거의 변화 없음"
            estimatedTradesAfterChange > trades.size * 0.7 -> "소폭 감소 예상 (-10~30%)"
            estimatedTradesAfterChange > trades.size * 0.5 -> "중간 감소 예상 (-30~50%)"
            else -> "대폭 감소 예상 (-50% 이상)"
        }

        return """
            === 백테스트 결과 ($historicalDays 일) ===

            파라미터: $paramName
            현재값: $currentValue → 새 값: $newValue

            분석 기간 트레이드: ${trades.size}건
            승리: ${winningTrades.size}건 / 패배: ${losingTrades.size}건
            승률: ${String.format("%.1f", winRate)}%
            총 손익: ${String.format("%.0f", totalPnl)}원

            새 파라미터 적용 시:
            - 진입 가능 트레이드: $filteredIn 건
            - 필터링된 트레이드: $filteredOut 건
            - 예상 영향: $estimatedImpact

            권장사항: ${
            when {
                paramName in listOf("stopLossPercent", "takeProfitPercent") ->
                    "손절/익절 변경은 기존 수익률과 비교하여 신중하게 결정하세요."
                filteredIn < trades.size * 0.5 ->
                    "변경 시 트레이드 수가 절반 이하로 감소합니다. 신중하게 결정하세요."
                else -> "변경 적용 가능합니다."
            }
        }
        """.trimIndent()
    }

    @Tool(description = """
        BTC 가격과의 상관관계를 분석합니다.
        특정 마켓의 트레이드가 BTC 가격 변동과 어떤 관계가 있는지 분석합니다.
    """)
    fun analyzeMarketCorrelation(
        @ToolParam(description = "분석 기간 (일)") days: Int = 7,
        @ToolParam(description = "분석할 지표 (btc_price, volume, volatility)") metric: String = "btc_price"
    ): String {
        log.info("[Tool] analyzeMarketCorrelation: days=$days, metric=$metric")

        val startOfPeriod = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        // 기간 내 모든 트레이드 조회
        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "분석 기간($days 일) 내 트레이드가 없습니다."
        }

        // 마켓별 그룹화
        val tradesByMarket = trades.groupBy { it.market }

        // 성공/실패 패턴 분석
        val marketStats = tradesByMarket.map { (market, marketTrades) ->
            val wins = marketTrades.count { (it.pnlAmount ?: 0.0) > 0 }
            val losses = marketTrades.size - wins
            val totalPnl = marketTrades.sumOf { it.pnlAmount ?: 0.0 }
            val avgRsi = marketTrades.mapNotNull { it.entryRsi }.average().takeIf { !it.isNaN() } ?: 0.0
            val avgVolumeRatio = marketTrades.mapNotNull { it.entryVolumeRatio }.average().takeIf { !it.isNaN() } ?: 0.0
            val avgConfluence = marketTrades.mapNotNull { it.confluenceScore }.average().takeIf { !it.isNaN() } ?: 0.0

            mapOf(
                "market" to market,
                "trades" to marketTrades.size,
                "wins" to wins,
                "losses" to losses,
                "winRate" to if (marketTrades.isNotEmpty()) wins.toDouble() / marketTrades.size * 100 else 0.0,
                "totalPnl" to totalPnl,
                "avgRsi" to avgRsi,
                "avgVolumeRatio" to avgVolumeRatio,
                "avgConfluence" to avgConfluence
            )
        }.sortedByDescending { it["totalPnl"] as Double }

        // 성공 트레이드의 공통 특성
        val winningTrades = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }

        val winningPattern = if (winningTrades.isNotEmpty()) {
            mapOf(
                "avgRsi" to winningTrades.mapNotNull { it.entryRsi }.average(),
                "avgVolumeRatio" to winningTrades.mapNotNull { it.entryVolumeRatio }.average(),
                "avgConfluence" to winningTrades.mapNotNull { it.confluenceScore }.average(),
                "commonExitReason" to winningTrades.groupBy { it.exitReason }
                    .maxByOrNull { it.value.size }?.key
            )
        } else null

        val losingPattern = if (losingTrades.isNotEmpty()) {
            mapOf(
                "avgRsi" to losingTrades.mapNotNull { it.entryRsi }.average(),
                "avgVolumeRatio" to losingTrades.mapNotNull { it.entryVolumeRatio }.average(),
                "avgConfluence" to losingTrades.mapNotNull { it.confluenceScore }.average(),
                "commonExitReason" to losingTrades.groupBy { it.exitReason }
                    .maxByOrNull { it.value.size }?.key
            )
        } else null

        return """
            === 시장 상관관계 분석 ($days 일) ===

            총 트레이드: ${trades.size}건
            승리: ${winningTrades.size}건 / 패배: ${losingTrades.size}건

            === 마켓별 성과 ===
            ${marketStats.take(5).joinToString("\n") { stat ->
                """
                ${stat["market"]}:
                  거래: ${stat["trades"]}건 (승률: ${String.format("%.1f", stat["winRate"])}%)
                  손익: ${String.format("%.0f", stat["totalPnl"])}원
                  평균 RSI: ${String.format("%.1f", stat["avgRsi"])}
                  평균 거래량비율: ${String.format("%.1f", stat["avgVolumeRatio"])}x
                """.trimIndent()
            }}

            === 성공 트레이드 패턴 ===
            ${winningPattern?.let {
                """
                평균 RSI: ${String.format("%.1f", it["avgRsi"])}
                평균 거래량비율: ${String.format("%.1f", it["avgVolumeRatio"])}x
                평균 컨플루언스: ${String.format("%.0f", it["avgConfluence"])}
                주요 청산사유: ${it["commonExitReason"]}
                """.trimIndent()
            } ?: "데이터 없음"}

            === 실패 트레이드 패턴 ===
            ${losingPattern?.let {
                """
                평균 RSI: ${String.format("%.1f", it["avgRsi"])}
                평균 거래량비율: ${String.format("%.1f", it["avgVolumeRatio"])}x
                평균 컨플루언스: ${String.format("%.0f", it["avgConfluence"])}
                주요 청산사유: ${it["commonExitReason"]}
                """.trimIndent()
            } ?: "데이터 없음"}

            === 인사이트 ===
            ${generateInsights(winningPattern, losingPattern)}
        """.trimIndent()
    }

    /**
     * 패턴 기반 인사이트 생성
     */
    private fun generateInsights(
        winningPattern: Map<String, Any?>?,
        losingPattern: Map<String, Any?>?
    ): String {
        if (winningPattern == null || losingPattern == null) {
            return "충분한 데이터가 없어 인사이트를 생성할 수 없습니다."
        }

        val insights = mutableListOf<String>()

        // RSI 비교
        val winRsi = winningPattern["avgRsi"] as? Double ?: 0.0
        val loseRsi = losingPattern["avgRsi"] as? Double ?: 0.0
        if (kotlin.math.abs(winRsi - loseRsi) > 5) {
            if (winRsi < loseRsi) {
                insights.add("성공 트레이드는 낮은 RSI(${String.format("%.0f", winRsi)})에서 진입하는 경향이 있습니다.")
            } else {
                insights.add("성공 트레이드는 높은 RSI(${String.format("%.0f", winRsi)})에서 진입하는 경향이 있습니다.")
            }
        }

        // 거래량 비교
        val winVolume = winningPattern["avgVolumeRatio"] as? Double ?: 0.0
        val loseVolume = losingPattern["avgVolumeRatio"] as? Double ?: 0.0
        if (winVolume > loseVolume * 1.2) {
            insights.add("성공 트레이드는 더 높은 거래량 비율(${String.format("%.1f", winVolume)}x)에서 진입합니다.")
        }

        // 컨플루언스 비교
        val winConf = winningPattern["avgConfluence"] as? Double ?: 0.0
        val loseConf = losingPattern["avgConfluence"] as? Double ?: 0.0
        if (winConf > loseConf + 5) {
            insights.add("성공 트레이드는 더 높은 컨플루언스 점수(${String.format("%.0f", winConf)})를 가집니다.")
        }

        return if (insights.isEmpty()) {
            "성공/실패 트레이드 간 명확한 패턴 차이가 발견되지 않았습니다."
        } else {
            insights.joinToString("\n")
        }
    }

    @Tool(description = """
        시간대별/요일별 트레이드 패턴을 분석합니다.
        최적의 트레이딩 시간대를 파악하는 데 도움됩니다.
    """)
    fun analyzeHistoricalPatterns(
        @ToolParam(description = "분석 기간 (일)") days: Int = 14
    ): String {
        log.info("[Tool] analyzeHistoricalPatterns: days=$days")

        val startOfPeriod = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "분석 기간($days 일) 내 트레이드가 없습니다."
        }

        // 시간대별 분석 (KST 기준)
        val hourlyStats = trades.groupBy { trade ->
            trade.entryTime.atZone(ZoneId.of("Asia/Seoul")).hour
        }.mapValues { (_, hourTrades) ->
            val wins = hourTrades.count { (it.pnlAmount ?: 0.0) > 0 }
            val totalPnl = hourTrades.sumOf { it.pnlAmount ?: 0.0 }
            mapOf(
                "count" to hourTrades.size,
                "wins" to wins,
                "winRate" to if (hourTrades.isNotEmpty()) wins.toDouble() / hourTrades.size * 100 else 0.0,
                "totalPnl" to totalPnl
            )
        }.toSortedMap()

        // 요일별 분석
        val dayOfWeekStats = trades.groupBy { trade ->
            trade.entryTime.atZone(ZoneId.of("Asia/Seoul")).dayOfWeek.name
        }.mapValues { (_, dayTrades) ->
            val wins = dayTrades.count { (it.pnlAmount ?: 0.0) > 0 }
            val totalPnl = dayTrades.sumOf { it.pnlAmount ?: 0.0 }
            mapOf(
                "count" to dayTrades.size,
                "wins" to wins,
                "winRate" to if (dayTrades.isNotEmpty()) wins.toDouble() / dayTrades.size * 100 else 0.0,
                "totalPnl" to totalPnl
            )
        }

        // 최적 시간대 추출
        val bestHours = hourlyStats.entries
            .filter { it.value["count"] as Int >= 2 }
            .sortedByDescending { it.value["winRate"] as Double }
            .take(3)

        val worstHours = hourlyStats.entries
            .filter { it.value["count"] as Int >= 2 }
            .sortedBy { it.value["winRate"] as Double }
            .take(3)

        // 청산 사유별 분석
        val exitReasonStats = trades.filter { it.exitReason != null }
            .groupBy { it.exitReason!! }
            .mapValues { (_, reasonTrades) ->
                val avgPnl = reasonTrades.mapNotNull { it.pnlAmount }.average()
                mapOf(
                    "count" to reasonTrades.size,
                    "avgPnl" to avgPnl
                )
            }

        return """
            === 시간대/요일별 패턴 분석 ($days 일) ===

            총 트레이드: ${trades.size}건

            === 최적 시간대 (KST) ===
            ${bestHours.joinToString("\n") { (hour, stats) ->
                "${hour}시: ${stats["count"]}건, 승률 ${String.format("%.0f", stats["winRate"])}%"
            }}

            === 회피 시간대 (KST) ===
            ${worstHours.joinToString("\n") { (hour, stats) ->
                "${hour}시: ${stats["count"]}건, 승률 ${String.format("%.0f", stats["winRate"])}%"
            }}

            === 요일별 성과 ===
            ${dayOfWeekStats.entries.sortedByDescending { it.value["totalPnl"] as Double }
                .joinToString("\n") { (day, stats) ->
                    "$day: ${stats["count"]}건, 승률 ${String.format("%.0f", stats["winRate"])}%, " +
                    "손익 ${String.format("%.0f", stats["totalPnl"])}원"
                }}

            === 청산 사유별 분석 ===
            ${exitReasonStats.entries.sortedByDescending { it.value["count"] as Int }
                .joinToString("\n") { (reason, stats) ->
                    "$reason: ${stats["count"]}건, 평균 손익 ${String.format("%.0f", stats["avgPnl"])}원"
                }}

            === 권장사항 ===
            ${generateTimeBasedRecommendations(bestHours, worstHours)}
        """.trimIndent()
    }

    /**
     * 시간대 기반 권장사항 생성
     */
    private fun generateTimeBasedRecommendations(
        bestHours: List<Map.Entry<Int, Map<String, Any>>>,
        worstHours: List<Map.Entry<Int, Map<String, Any>>>
    ): String {
        val recommendations = mutableListOf<String>()

        if (bestHours.isNotEmpty()) {
            val bestHoursList = bestHours.map { it.key }
            recommendations.add("최적 시간대(${bestHoursList.joinToString(", ")}시)에 트레이딩 집중 권장")
        }

        if (worstHours.isNotEmpty()) {
            val worstWinRate = worstHours.firstOrNull()?.value?.get("winRate") as? Double ?: 0.0
            if (worstWinRate < 30.0) {
                val worstHour = worstHours.first().key
                recommendations.add("${worstHour}시 시간대는 승률이 매우 낮아 트레이딩 회피 권장")
            }
        }

        return if (recommendations.isEmpty()) {
            "특별한 시간대 편향이 발견되지 않았습니다."
        } else {
            recommendations.joinToString("\n")
        }
    }
}
