package com.ant.cointrading.memescalper

import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.MemeScalperDailyStatsEntity
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
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
 * Meme Scalper 전략 회고 시스템
 *
 * 매일 새벽 1시에 전일 트레이드를 분석하고
 * 학습/개선점을 도출한다.
 */
@Component
class MemeScalperReflector(
    private val properties: MemeScalperProperties,
    private val modelSelector: ModelSelector,
    private val tradeRepository: MemeScalperTradeRepository,
    private val statsRepository: MemeScalperDailyStatsRepository,
    private val keyValueService: KeyValueService,
    private val slackNotifier: SlackNotifier,
    private val objectMapper: ObjectMapper,
    private val reflectorTools: MemeScalperReflectorTools
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY_LAST_REFLECTION = "memescalper.last_reflection"
    }

    private val systemPrompt = """
        당신은 암호화폐 스캘핑 트레이딩 시스템의 회고 분석가입니다.
        오늘의 Meme Scalper 트레이드를 분석하고 개선점을 제안하세요.

        Meme Scalper 전략 특성:
        - 펌프앤덤프 초기 진입, 빠른 청산 (5분 타임아웃)
        - 1분봉 기반 분석
        - 거래량 스파이크 + 가격 스파이크 + 호가창 Imbalance + RSI + MACD 컨플루언스
        - 타이트한 손절/익절 (손절 -1.5%, 익절 +3%)
        - 트레일링 스탑 (+1% 이후 고점 대비 -0.5%)

        분석 관점:
        1. 청산 사유별 분석
           - TAKE_PROFIT: 성공 케이스 (익절 도달)
           - TRAILING_STOP: 성공 케이스 (트레일링 후 청산)
           - STOP_LOSS: 실패 케이스 (손절)
           - TIMEOUT: 횡보 케이스 (펌프 아닌 것에 진입)
           - IMBALANCE_FLIP: 조기 청산 케이스
           - VOLUME_DROP: 조기 청산 케이스

        2. 진입 조건 분석
           - 성공 트레이드의 공통점 (거래량 비율, 가격 스파이크, MACD, RSI, 스프레드)
           - TIMEOUT 비율이 높으면 진입 조건 강화 필요

        3. 파라미터 조정 제안
           - 손절/익절 비율
           - 트레일링 스탑 trigger/offset
           - 최소 컨플루언스 점수
           - 포지션 타임아웃

        4. 시스템 개선 제안
           - 현재 시스템에 없지만 있으면 좋을 기능

        도구 사용:
        - getTodayStats: 오늘의 통계 조회
        - getTodayTrades: 오늘의 트레이드 목록 조회
        - saveReflection: 회고 결과 저장
        - suggestParameterChange: 파라미터 변경 제안 (Slack 알림 발송됨)
        - suggestSystemImprovement: 시스템 개선 아이디어 제안

        반드시 모든 도구를 사용하여 분석을 완료하세요.
    """.trimIndent()

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Meme Scalper Reflector 활성화 ===")
            log.info("회고 크론: ${properties.reflectionCron}")
        }
    }

    /**
     * 일일 회고 실행 (매일 새벽 1시)
     */
    @Scheduled(cron = "\${memescalper.reflection-cron:0 0 1 * * *}")
    fun runDailyReflection() {
        if (!properties.enabled) {
            return
        }

        log.info("=== Meme Scalper 일일 회고 시작 ===")

        try {
            val result = reflect()
            log.info("=== Meme Scalper 일일 회고 완료 ===")
            log.info(result)

            slackNotifier.sendSystemNotification("Meme Scalper 일일 회고", result)

        } catch (e: Exception) {
            log.error("Meme Scalper 회고 실패: ${e.message}", e)
            slackNotifier.sendError("MEME_SCALPER", "일일 회고 실패: ${e.message}")
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

        val todayTrades = tradeRepository.findByCreatedAtAfter(startOfDay)
            .filter { it.status == "CLOSED" || it.status == "ABANDONED" }

        if (todayTrades.isEmpty()) {
            log.info("오늘 트레이드 없음")
            return "오늘 트레이드가 없어 회고를 건너뜁니다."
        }

        val stats = calculateDailyStats(today, startOfDay, todayTrades)
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
        trades: List<MemeScalperTradeEntity>
    ): MemeScalperDailyStats {
        val closedTrades = trades.filter { it.status == "CLOSED" }

        val totalTrades = closedTrades.size
        val winningTrades = closedTrades.count { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = closedTrades.count { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = closedTrades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        // 청산 사유별 통계
        val exitReasonStats = closedTrades.groupBy { it.exitReason ?: "UNKNOWN" }
            .mapValues { it.value.size }

        val avgHoldingSeconds = closedTrades
            .filter { it.exitTime != null }
            .map { ChronoUnit.SECONDS.between(it.entryTime, it.exitTime) }
            .average()
            .takeIf { !it.isNaN() }

        return MemeScalperDailyStats(
            date = date,
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            totalPnl = totalPnl,
            winRate = winRate,
            exitReasonStats = exitReasonStats,
            avgHoldingSeconds = avgHoldingSeconds
        )
    }

    /**
     * 사용자 프롬프트 생성
     */
    private fun buildUserPrompt(stats: MemeScalperDailyStats, trades: List<MemeScalperTradeEntity>): String {
        val successCases = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
            .take(5)
            .joinToString("\n") { formatTradeCase(it, "성공") }

        val failureCases = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }
            .take(5)
            .joinToString("\n") { formatTradeCase(it, "실패") }

        val exitReasonSummary = stats.exitReasonStats.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  - ${it.key}: ${it.value}건" }

        return """
            오늘 날짜: ${stats.date}

            === 오늘 통계 ===
            총 트레이드: ${stats.totalTrades}건
            승리: ${stats.winningTrades}건 / 패배: ${stats.losingTrades}건
            승률: ${String.format("%.1f", stats.winRate * 100)}%
            총 손익: ${String.format("%.0f", stats.totalPnl)}원
            평균 보유시간: ${stats.avgHoldingSeconds?.let { String.format("%.0f", it) } ?: "N/A"}초

            === 청산 사유별 통계 ===
            $exitReasonSummary

            === 성공 케이스 (최대 5건) ===
            $successCases

            === 실패 케이스 (최대 5건) ===
            $failureCases

            위 데이터를 분석하고 도구를 사용하여:
            1. getTodayStats로 통계 확인
            2. getTodayTrades로 상세 트레이드 조회
            3. 패턴 분석 후 saveReflection으로 회고 저장
            4. TIMEOUT 비율이 30% 이상이면 진입 조건 강화 제안
            5. 승률이 50% 미만이면 파라미터 변경 제안
        """.trimIndent()
    }

    private fun formatTradeCase(trade: MemeScalperTradeEntity, type: String): String {
        return """
            [$type] ${trade.market}
            진입: ${trade.entryPrice}원 → 청산: ${trade.exitPrice}원
            손익: ${trade.pnlAmount?.let { String.format("%.0f", it) }}원 (${trade.pnlPercent?.let { String.format("%.2f", it) }}%)
            거래량: ${trade.entryVolumeSpikeRatio?.let { String.format("%.1f", it) }}x | 가격: ${trade.entryPriceSpikePercent?.let { String.format("%.1f", it) }}%
            RSI: ${trade.entryRsi?.let { String.format("%.0f", it) }} | MACD: ${trade.entryMacdSignal} | Imbalance: ${trade.entryImbalance?.let { String.format("%.2f", it) }}
            스프레드: ${trade.entrySpread?.let { String.format("%.3f", it) }}% | 청산사유: ${trade.exitReason}
        """.trimIndent()
    }
}

/**
 * Meme Scalper 일일 통계
 */
data class MemeScalperDailyStats(
    val date: LocalDate,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val totalPnl: Double,
    val winRate: Double,
    val exitReasonStats: Map<String, Int>,
    val avgHoldingSeconds: Double?
)

/**
 * Meme Scalper 회고용 LLM 도구
 */
@Component
class MemeScalperReflectorTools(
    private val tradeRepository: MemeScalperTradeRepository,
    private val statsRepository: MemeScalperDailyStatsRepository,
    private val keyValueService: KeyValueService,
    private val objectMapper: ObjectMapper,
    private val slackNotifier: SlackNotifier,
    private val properties: MemeScalperProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val ALLOWED_PARAMS = mapOf(
            "stopLossPercent" to "memescalper.stopLossPercent",
            "takeProfitPercent" to "memescalper.takeProfitPercent",
            "trailingStopTrigger" to "memescalper.trailingStopTrigger",
            "trailingStopOffset" to "memescalper.trailingStopOffset",
            "positionTimeoutMin" to "memescalper.positionTimeoutMin",
            "volumeSpikeRatio" to "memescalper.volumeSpikeRatio",
            "priceSpikePercent" to "memescalper.priceSpikePercent",
            "minBidImbalance" to "memescalper.minBidImbalance",
            "maxRsi" to "memescalper.maxRsi",
            "volumeDropRatio" to "memescalper.volumeDropRatio",
            "cooldownSec" to "memescalper.cooldownSec",
            "maxConsecutiveLosses" to "memescalper.maxConsecutiveLosses",
            "dailyMaxLossKrw" to "memescalper.dailyMaxLossKrw",
            "positionSizeKrw" to "memescalper.positionSizeKrw"
        )
    }

    @Tool(description = "오늘의 Meme Scalper 전략 통계를 조회합니다")
    fun getTodayStats(): String {
        log.info("[Tool] getTodayStats")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val trades = tradeRepository.findByCreatedAtAfter(startOfDay)
            .filter { it.status == "CLOSED" }

        val totalPnl = trades.sumOf { it.pnlAmount ?: 0.0 }
        val winCount = trades.count { (it.pnlAmount ?: 0.0) > 0 }
        val loseCount = trades.count { (it.pnlAmount ?: 0.0) <= 0 }

        val exitReasons = trades.groupBy { it.exitReason ?: "UNKNOWN" }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}: ${it.value}건" }

        return """
            === 오늘 통계 ($today) ===
            총 트레이드: ${trades.size}건
            총 손익: ${String.format("%.0f", totalPnl)}원
            승리/패배: $winCount / $loseCount
            승률: ${if (trades.isNotEmpty()) String.format("%.1f", winCount.toDouble() / trades.size * 100) else 0}%

            청산 사유별:
            $exitReasons
        """.trimIndent()
    }

    @Tool(description = "오늘의 트레이드 목록을 조회합니다")
    fun getTodayTrades(
        @ToolParam(description = "조회할 트레이드 수 (기본 20)") limit: Int = 20
    ): String {
        log.info("[Tool] getTodayTrades: limit=$limit")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val trades = tradeRepository.findByCreatedAtAfter(startOfDay)
            .filter { it.status == "CLOSED" || it.status == "ABANDONED" }
            .sortedByDescending { it.createdAt }
            .take(limit)

        if (trades.isEmpty()) {
            return "오늘 트레이드가 없습니다."
        }

        return trades.joinToString("\n\n") { trade ->
            val result = if ((trade.pnlAmount ?: 0.0) > 0) "성공" else "실패"
            """
                [$result] ${trade.market} (ID: ${trade.id})
                진입: ${trade.entryPrice}원 → 청산: ${trade.exitPrice ?: "미청산"}원
                손익: ${trade.pnlAmount?.let { String.format("%.0f", it) } ?: "N/A"}원 (${trade.pnlPercent?.let { String.format("%.2f", it) } ?: "N/A"}%)
                거래량: ${trade.entryVolumeSpikeRatio?.let { String.format("%.1f", it) }}x | RSI: ${trade.entryRsi?.let { String.format("%.0f", it) }}
                MACD: ${trade.entryMacdSignal} | 스프레드: ${trade.entrySpread?.let { String.format("%.3f", it) }}%
                청산사유: ${trade.exitReason ?: "N/A"}
            """.trimIndent()
        }
    }

    @Tool(description = "오늘의 회고 결과를 저장합니다")
    fun saveReflection(
        @ToolParam(description = "회고 요약") summary: String,
        @ToolParam(description = "주요 발견 사항") findings: String,
        @ToolParam(description = "내일 주의점") tomorrowFocus: String
    ): String {
        log.info("[Tool] saveReflection")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        // 오늘 통계 계산
        val trades = tradeRepository.findByCreatedAtAfter(startOfDay)
            .filter { it.status == "CLOSED" }

        val stats = statsRepository.findByDate(today) ?: MemeScalperDailyStatsEntity(date = today)
        stats.totalTrades = trades.size
        stats.winningTrades = trades.count { (it.pnlAmount ?: 0.0) > 0 }
        stats.losingTrades = trades.count { (it.pnlAmount ?: 0.0) <= 0 }
        stats.totalPnl = trades.sumOf { it.pnlAmount ?: 0.0 }
        stats.winRate = if (trades.isNotEmpty()) stats.winningTrades.toDouble() / trades.size else 0.0

        // 회고 저장 (maxSingleLoss 필드 재활용)
        stats.maxSingleLoss = null  // 리셋
        stats.maxSingleProfit = null

        statsRepository.save(stats)

        // KeyValue에도 회고 저장
        keyValueService.set(
            key = "memescalper.reflection.${today}",
            value = """
                === 회고 요약 ===
                $summary

                === 주요 발견 ===
                $findings

                === 내일 주의점 ===
                $tomorrowFocus
            """.trimIndent(),
            category = "memescalper",
            description = "일일 회고"
        )

        return "회고가 저장되었습니다."
    }

    @Tool(description = """
        파라미터를 변경합니다.
        허용된 파라미터: stopLossPercent, takeProfitPercent, trailingStopTrigger, trailingStopOffset,
        positionTimeoutMin, volumeSpikeRatio, priceSpikePercent, minBidImbalance, maxRsi,
        volumeDropRatio, cooldownSec, maxConsecutiveLosses, dailyMaxLossKrw, positionSizeKrw
    """)
    fun suggestParameterChange(
        @ToolParam(description = "파라미터 이름") paramName: String,
        @ToolParam(description = "새로운 값") newValue: Double,
        @ToolParam(description = "변경 이유") reason: String
    ): String {
        log.info("[Tool] suggestParameterChange: $paramName -> $newValue")

        val keyValueKey = ALLOWED_PARAMS[paramName]
        if (keyValueKey == null) {
            return """
                오류: 허용되지 않은 파라미터입니다.
                파라미터명: $paramName
                허용된 파라미터: ${ALLOWED_PARAMS.keys.joinToString(", ")}
            """.trimIndent()
        }

        val currentValue = getCurrentParamValue(paramName)
        if (currentValue == newValue) {
            return "파라미터 $paramName 의 현재값($currentValue)과 새 값($newValue)이 동일하여 변경하지 않았습니다."
        }

        try {
            keyValueService.set(
                key = keyValueKey,
                value = newValue.toString(),
                category = "memescalper",
                description = "LLM 자동 변경: $reason"
            )

            applyParamToProperties(paramName, newValue)

            slackNotifier.sendSystemNotification(
                "[자동] Meme Scalper 파라미터 변경",
                """
                    파라미터: $paramName
                    변경: $currentValue → $newValue
                    이유: $reason
                """.trimIndent()
            )

            log.info("파라미터 변경 완료: $paramName = $currentValue -> $newValue")

            return """
                파라미터 변경 완료:
                - $paramName: $currentValue → $newValue
                - 이유: $reason
                - 즉시 반영됨
            """.trimIndent()

        } catch (e: Exception) {
            log.error("파라미터 변경 실패: $paramName, error: ${e.message}", e)
            return "오류: 파라미터 변경 실패 - ${e.message}"
        }
    }

    private fun getCurrentParamValue(paramName: String): Double {
        return when (paramName) {
            "stopLossPercent" -> properties.stopLossPercent
            "takeProfitPercent" -> properties.takeProfitPercent
            "trailingStopTrigger" -> properties.trailingStopTrigger
            "trailingStopOffset" -> properties.trailingStopOffset
            "positionTimeoutMin" -> properties.positionTimeoutMin.toDouble()
            "volumeSpikeRatio" -> properties.volumeSpikeRatio
            "priceSpikePercent" -> properties.priceSpikePercent
            "minBidImbalance" -> properties.minBidImbalance
            "maxRsi" -> properties.maxRsi.toDouble()
            "volumeDropRatio" -> properties.volumeDropRatio
            "cooldownSec" -> properties.cooldownSec.toDouble()
            "maxConsecutiveLosses" -> properties.maxConsecutiveLosses.toDouble()
            "dailyMaxLossKrw" -> properties.dailyMaxLossKrw.toDouble()
            "positionSizeKrw" -> properties.positionSizeKrw.toDouble()
            else -> 0.0
        }
    }

    private fun applyParamToProperties(paramName: String, value: Double) {
        when (paramName) {
            "stopLossPercent" -> properties.stopLossPercent = value
            "takeProfitPercent" -> properties.takeProfitPercent = value
            "trailingStopTrigger" -> properties.trailingStopTrigger = value
            "trailingStopOffset" -> properties.trailingStopOffset = value
            "positionTimeoutMin" -> properties.positionTimeoutMin = value.toInt()
            "volumeSpikeRatio" -> properties.volumeSpikeRatio = value
            "priceSpikePercent" -> properties.priceSpikePercent = value
            "minBidImbalance" -> properties.minBidImbalance = value
            "maxRsi" -> properties.maxRsi = value.toInt()
            "volumeDropRatio" -> properties.volumeDropRatio = value
            "cooldownSec" -> properties.cooldownSec = value.toInt()
            "maxConsecutiveLosses" -> properties.maxConsecutiveLosses = value.toInt()
            "dailyMaxLossKrw" -> properties.dailyMaxLossKrw = value.toInt()
            "positionSizeKrw" -> properties.positionSizeKrw = value.toInt()
        }
    }

    @Tool(description = "시스템 개선 아이디어를 제안합니다. Slack 알림이 발송됩니다.")
    fun suggestSystemImprovement(
        @ToolParam(description = "제안 제목") title: String,
        @ToolParam(description = "상세 설명") description: String,
        @ToolParam(description = "우선순위 (HIGH/MEDIUM/LOW)") priority: String,
        @ToolParam(description = "카테고리 (FEATURE/DATA/AUTOMATION/INTEGRATION)") category: String
    ): String {
        log.info("[Tool] suggestSystemImprovement: $title ($priority)")

        val priorityEmoji = when (priority.uppercase()) {
            "HIGH" -> "[긴급]"
            "MEDIUM" -> "[보통]"
            else -> "[참고]"
        }

        slackNotifier.sendSystemNotification(
            "$priorityEmoji Meme Scalper 개선 제안",
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
            시스템 개선 제안이 Slack 알림으로 발송되었습니다:
            - 제목: $title
            - 카테고리: $category
            - 우선순위: $priority
        """.trimIndent()
    }
}
