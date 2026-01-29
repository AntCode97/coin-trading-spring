package com.ant.cointrading.volumesurge

import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.mcp.tool.SlackTools
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.*
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.service.ModelSelector
import com.ant.cointrading.stats.TradeStatsCalculator
import com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE
import com.ant.cointrading.util.DateTimeUtils.today
import com.ant.cointrading.util.DateTimeUtils.todayRange
import java.time.LocalDate
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.Instant

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
    private val reflectorTools: VolumeSurgeReflectorTools,
    private val slackTools: SlackTools
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY_LAST_REFLECTION = "volumesurge.last_reflection"
    }

    private lateinit var systemPrompt: String

    @PostConstruct
    fun init() {
        systemPrompt = loadSystemPrompt()
        if (properties.enabled) {
            log.info("=== Volume Surge Reflector 활성화 ===")
            log.info("회고 크론: ${properties.reflectionCron}")
        }
    }

    private fun loadSystemPrompt(): String {
        return try {
            val resource = ClassPathResource("volumesurge-reflector-prompt.txt")
            resource.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            log.error("시스템 프롬프트 로드 실패: ${e.message}")
            """
            당신은 암호화폐 트레이딩 시스템의 회고 분석가입니다.
            오늘의 거래량 급등 전략 트레이드를 분석하고 개선점을 제안하세요.
            """
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
        val (startOfDay, endOfDay) = todayRange()
        val today = today()

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
            // ReflectorTools와 SlackTools를 결합하여 ChatClient 생성
            val allTools = arrayOf(reflectorTools, slackTools)

            val chatClient = modelSelector.getChatClient()
                .mutate()
                .defaultTools(*allTools)
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

        val tradeStats = TradeStatsCalculator.calculateVolumeSurge(closedTrades)

        return DailyStats(
            date = date,
            totalAlerts = totalAlerts,
            approvedAlerts = approvedAlerts,
            totalTrades = tradeStats.totalTrades,
            winningTrades = tradeStats.winningTrades,
            losingTrades = tradeStats.losingTrades,
            totalPnl = tradeStats.totalPnl,
            winRate = tradeStats.winRate,
            avgHoldingMinutes = tradeStats.avgHoldingMinutes
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
