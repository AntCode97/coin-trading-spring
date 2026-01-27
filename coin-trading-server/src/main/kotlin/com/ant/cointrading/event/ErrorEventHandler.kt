package com.ant.cointrading.event

import com.ant.cointrading.notification.SlackNotifier
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.io.StringWriter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 트레이딩 에러 이벤트 핸들러
 *
 * 에러 발생 시 Slack으로 알림 전송
 * @Async로 비동기 처리하여 메인 로직 방지
 */
@Component
class ErrorEventHandler(
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    @Async
    @EventListener
    fun handleTradingError(event: TradingErrorEvent) {
        try {
            val stackTrace = if (event.exception != null) {
                val sw = StringWriter()
                event.exception!!.printStackTrace(java.io.PrintWriter(sw))
                sw.toString().take(1000)  // 너무 길면 1000자로 제한
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
                appendLine("**시간**: `${dateFormatter.format(event.timestamp)}`")
                appendLine()
                appendLine("```")
                appendLine(stackTrace)
                appendLine("```")
            }

            slackNotifier.sendSystemNotification(
                "Trading System Error [${event.component}]",
                message
            )

            log.warn("[${event.component}] 에러 Slack 알림 전송: ${event.operation}")
        } catch (e: Exception) {
            log.error("에러 알림 전송 실패: ${e.message}", e)
        }
    }
}
