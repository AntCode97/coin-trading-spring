package com.ant.cointrading.event

import com.ant.cointrading.notification.SlackNotifier
import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.io.StringWriter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * 트레이딩 에러 이벤트 핸들러
 *
 * 에러 발생 시 Slack으로 알림 전송
 * @Async로 비동기 처리하여 메인 로직 방지
 *
 * 중복 에러 방지: 동일 컴포넌트/작업에 대해 5분 이내 재발생 시 알림 스킵
 *
 * Slack 알림 제외 대상:
 * - Jackson 역직렬화 에러 (Bithumb API 응답 필드 변경 등 일시적 이슈)
 * - BithumbPublicApi 데이터 조회 (getOhlcv, getCurrentPrice, getOrderbook, getTrades)
 */
@Component
class ErrorEventHandler(
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    // 중복 에러 방지 (컴포넌트 + 작업 + 마켓 -> 마지막 알림 시각)
    private val lastAlertTime = ConcurrentHashMap<String, Instant>()
    private val ALERT_COOLDOWN_MINUTES = 5L

    // Slack 알림 제외 컴포넌트/작업 조합
    private val SLACK_EXCLUDED = setOf(
        "BithumbPublicApi:getOhlcv",
        "BithumbPublicApi:getCurrentPrice",
        "BithumbPublicApi:getOrderbook",
        "BithumbPublicApi:getTrades",
    )

    @Async
    @EventListener
    fun handleTradingError(event: TradingErrorEvent) {
        try {
            // Jackson 역직렬화 에러는 Slack 알림 제외 (일시적 API 응답 변경 등)
            if (event.exception is JsonProcessingException) {
                log.debug("[${event.component}] Jackson 에러로 Slack 알림 스킵: ${event.operation}")
                return
            }

            // BithumbPublicApi 데이터 조회 에러는 Slack 알림 제외
            val key = "${event.component}:${event.operation}"
            if (key in SLACK_EXCLUDED) {
                log.debug("[${event.component}] Slack 알림 제외 대상: ${event.operation}")
                return
            }

            // 중복 체크
            val alertKey = buildAlertKey(event)
            val now = Instant.now()
            val lastTime = lastAlertTime[alertKey]

            if (lastTime != null) {
                val elapsedMinutes = java.time.Duration.between(lastTime, now).toMinutes()
                if (elapsedMinutes < ALERT_COOLDOWN_MINUTES) {
                    log.debug("[${event.component}] 중복 에러 스킵 (${elapsedMinutes}분 경과): ${event.operation}")
                    return
                }
            }

            val stackTrace = if (event.exception != null) {
                val sw = StringWriter()
                event.exception.printStackTrace(java.io.PrintWriter(sw))
                sw.toString().take(500)  // 너무 길면 500자로 제한 (Slack 메시지 제한)
            } else {
                "No exception details"
            }

            val message = buildString {
                appendLine("*:warning: 트레이딩 시스템 에러*")
                appendLine()
                appendLine("**컴포넌트**: `${event.component}`")
                appendLine("**작업**: `${event.operation}`")
                if (event.market != null) {
                    appendLine("**마켓**: `${event.market}`")
                }
                appendLine("**에러**: `${event.errorMessage}`")
                appendLine("**시간**: `${dateFormatter.format(now)}`")
                appendLine()
                appendLine("```")
                appendLine(stackTrace)
                appendLine("```")
            }

            slackNotifier.sendSystemNotification(
                "Trading System Error [${event.component}]",
                message
            )

            lastAlertTime[alertKey] = now
            log.warn("[${event.component}] 에러 Slack 알림 전송: ${event.operation}")
        } catch (e: Exception) {
            log.error("에러 알림 전송 실패: ${e.message}")
        }
    }

    private fun buildAlertKey(event: TradingErrorEvent): String {
        return "${event.component}:${event.operation}:${event.market ?: "ALL"}"
    }
}
