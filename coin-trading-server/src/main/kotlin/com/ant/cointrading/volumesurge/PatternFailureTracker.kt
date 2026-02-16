package com.ant.cointrading.volumesurge

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 패턴별 연속 실패 추적 + 일시 차단
 *
 * 동일 패턴으로 연속 N회 실패 시 해당 조건 진입을 일시 차단한다.
 * 차단 정책: 3연패→1시간, 5연패→4시간, 10연패→24시간
 */
@Component
class PatternFailureTracker(
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 패턴별 연속 실패 카운터 */
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    /** 패턴별 차단 해제 시각 */
    private val suspendedUntil = ConcurrentHashMap<String, Instant>()

    /** 차단 정책: 연속 실패 N회 → 차단 시간 */
    private val suspensionPolicy = mapOf(
        3 to Duration.ofHours(1),
        5 to Duration.ofHours(4),
        10 to Duration.ofHours(24)
    )

    fun recordFailure(market: String, pattern: FailurePattern) {
        if (pattern == FailurePattern.UNKNOWN) return

        val key = pattern.name
        val count = consecutiveFailures.merge(key, 1) { old, _ -> old + 1 } ?: 1

        suspensionPolicy.entries
            .sortedByDescending { it.key }
            .firstOrNull { count >= it.key }
            ?.let { (_, duration) ->
                val until = Instant.now().plus(duration)
                suspendedUntil[key] = until

                val msg = "[피드백루프] 패턴 '${pattern.label}' ${count}연패 -> ${duration.toHours()}시간 차단 ($market)"
                log.warn(msg)
                slackNotifier.sendWarning(market, msg)
            }
    }

    fun recordSuccess(trade: VolumeSurgeTradeEntity) {
        val possiblePatterns = inferPatternsFromEntry(trade)
        possiblePatterns.forEach { pattern ->
            val prev = consecutiveFailures.remove(pattern.name)
            if (prev != null && prev > 0) {
                suspendedUntil.remove(pattern.name)
                log.info("[PatternFailureTracker] {} 패턴 카운터 리셋 (이전 {}연패)", pattern.label, prev)
            }
        }
    }

    fun isPatternSuspended(pattern: FailurePattern): Boolean {
        val until = suspendedUntil[pattern.name] ?: return false
        if (Instant.now().isAfter(until)) {
            suspendedUntil.remove(pattern.name)
            consecutiveFailures.remove(pattern.name)
            log.info("[PatternFailureTracker] {} 패턴 차단 해제", pattern.label)
            return false
        }
        return true
    }

    /**
     * 진입 조건 기반 차단 확인
     *
     * @return (차단여부, 차단사유)
     */
    fun checkEntryBlocked(
        rsi: Double?,
        macdSignal: String?,
        bbPosition: String?,
        volumeRatio: Double?,
        confluenceScore: Int?
    ): Pair<Boolean, String?> {
        val checks = mutableListOf<FailurePattern>()

        if ((rsi ?: 0.0) >= 65.0) checks.add(FailurePattern.RSI_OVERBOUGHT)
        if (macdSignal in listOf("NEUTRAL", "BEARISH")) checks.add(FailurePattern.MACD_AGAINST)
        if (bbPosition == "UPPER") checks.add(FailurePattern.BOLLINGER_TOP)
        if ((volumeRatio ?: 0.0) < 2.0) checks.add(FailurePattern.WEAK_VOLUME)
        if ((confluenceScore ?: 0) < 40) checks.add(FailurePattern.LOW_CONFLUENCE)

        val blocked = checks.firstOrNull { isPatternSuspended(it) }
        return if (blocked != null) {
            val until = suspendedUntil[blocked.name]
            Pair(true, "${blocked.label} 패턴 차단 중 (해제: $until)")
        } else {
            Pair(false, null)
        }
    }

    fun getSuspensionStatus(): Map<String, SuspensionInfo> {
        return suspendedUntil.mapValues { (key, until) ->
            SuspensionInfo(
                pattern = key,
                consecutiveFailures = consecutiveFailures[key] ?: 0,
                suspendedUntil = until,
                isActive = Instant.now().isBefore(until)
            )
        }.filter { it.value.isActive }
    }

    fun getConsecutiveFailures(): Map<String, Int> = consecutiveFailures.toMap()

    private fun inferPatternsFromEntry(trade: VolumeSurgeTradeEntity): List<FailurePattern> {
        val patterns = mutableListOf<FailurePattern>()
        if ((trade.entryRsi ?: 0.0) >= 65.0) patterns.add(FailurePattern.RSI_OVERBOUGHT)
        if (trade.entryMacdSignal in listOf("NEUTRAL", "BEARISH")) patterns.add(FailurePattern.MACD_AGAINST)
        if (trade.entryBollingerPosition == "UPPER") patterns.add(FailurePattern.BOLLINGER_TOP)
        if ((trade.entryVolumeRatio ?: 0.0) < 2.0) patterns.add(FailurePattern.WEAK_VOLUME)
        if ((trade.confluenceScore ?: 0) < 40) patterns.add(FailurePattern.LOW_CONFLUENCE)
        return patterns
    }

    data class SuspensionInfo(
        val pattern: String,
        val consecutiveFailures: Int,
        val suspendedUntil: Instant,
        val isActive: Boolean
    )
}
