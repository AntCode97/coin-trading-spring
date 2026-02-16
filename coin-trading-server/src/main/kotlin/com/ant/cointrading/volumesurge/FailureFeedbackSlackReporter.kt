package com.ant.cointrading.volumesurge

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId

/**
 * 일일 실패 패턴 Slack 리포트
 *
 * 매일 새벽 1:05 실행 (Reflector 직후).
 * 패턴별 실패 통계, 차단 현황, 연속 실패 카운터를 Slack에 발송한다.
 */
@Component
class FailureFeedbackSlackReporter(
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val patternTracker: PatternFailureTracker,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 1 * * *")
    fun sendDailyFailureReport() {
        val now = Instant.now()
        val dayStart = now.atZone(ZoneId.of("Asia/Seoul"))
            .toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val closedTrades = tradeRepository.findClosedBetween(dayStart, now)
        if (closedTrades.isEmpty()) return

        val failedTrades = closedTrades.filter { (it.pnlPercent ?: 0.0) < 0 }
        val taggedTrades = failedTrades.filter { it.failurePattern != null }

        val patternStats = taggedTrades.groupBy { it.failurePattern }
            .mapValues { (_, trades) ->
                PatternStat(
                    count = trades.size,
                    avgPnl = trades.mapNotNull { it.pnlPercent }.average(),
                    totalLoss = trades.mapNotNull { it.pnlAmount }.sum()
                )
            }
            .entries.sortedByDescending { it.value.count }

        val suspensions = patternTracker.getSuspensionStatus()

        val sb = StringBuilder()
        sb.appendLine("*[일일 실패 패턴 리포트]*")
        sb.appendLine("총 ${closedTrades.size}건 | 실패 ${failedTrades.size}건 | 태깅 ${taggedTrades.size}건")
        sb.appendLine()

        if (patternStats.isNotEmpty()) {
            sb.appendLine("*패턴별 실패 통계:*")
            patternStats.forEach { (pattern, stat) ->
                val label = runCatching { FailurePattern.valueOf(pattern!!) }.getOrNull()?.label ?: pattern
                sb.appendLine("  - $label: ${stat.count}건 (평균 ${String.format("%.2f", stat.avgPnl)}%, 총손실 ${String.format("%.0f", stat.totalLoss)}원)")
            }
            sb.appendLine()
        }

        if (suspensions.isNotEmpty()) {
            sb.appendLine("*현재 차단 패턴:*")
            suspensions.forEach { (_, info) ->
                val label = runCatching { FailurePattern.valueOf(info.pattern) }.getOrNull()?.label ?: info.pattern
                sb.appendLine("  - $label: ${info.consecutiveFailures}연패 (해제: ${info.suspendedUntil})")
            }
            sb.appendLine()
        }

        val failures = patternTracker.getConsecutiveFailures().filter { it.value > 0 }
        if (failures.isNotEmpty()) {
            sb.appendLine("*연속 실패 현황:*")
            failures.entries.sortedByDescending { it.value }.forEach { (pattern, count) ->
                val label = runCatching { FailurePattern.valueOf(pattern) }.getOrNull()?.label ?: pattern
                sb.appendLine("  - $label: ${count}연패")
            }
        }

        slackNotifier.sendSystemNotification("실패 패턴 분석", sb.toString())
        log.info("[FailureFeedbackSlackReporter] 일일 리포트 발송 완료")
    }

    private data class PatternStat(val count: Int, val avgPnl: Double, val totalLoss: Double)
}
