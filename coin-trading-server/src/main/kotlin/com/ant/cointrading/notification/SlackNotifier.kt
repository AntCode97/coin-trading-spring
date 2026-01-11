package com.ant.cointrading.notification

import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.order.OrderResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Slack 알림 전송
 */
@Component
class SlackNotifier(
    private val webClient: WebClient
) {

    private val log = LoggerFactory.getLogger(SlackNotifier::class.java)

    @Value("\${slack.webhook-url:}")
    private lateinit var webhookUrl: String

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    /**
     * 거래 알림
     */
    fun sendTradeNotification(signal: TradingSignal, result: OrderResult) {
        if (webhookUrl.isBlank()) return

        val emoji = when {
            result.isSimulated -> ":test_tube:"
            signal.action.name == "BUY" -> ":chart_with_upwards_trend:"
            else -> ":chart_with_downwards_trend:"
        }

        val status = if (result.isSimulated) "[시뮬레이션]" else "[실거래]"

        val message = """
            $emoji $status 거래 실행

            *마켓*: ${signal.market}
            *행동*: ${signal.action}
            *가격*: ${signal.price}
            *금액*: ${result.quantity ?: "-"}
            *전략*: ${signal.strategy}
            *신뢰도*: ${String.format("%.1f", signal.confidence)}%
            *사유*: ${signal.reason}
            *주문ID*: ${result.orderId ?: "-"}
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(message)
    }

    /**
     * 경고 알림
     */
    fun sendWarning(market: String, message: String) {
        if (webhookUrl.isBlank()) return

        val text = """
            :warning: 경고

            *마켓*: $market
            *내용*: $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    /**
     * 에러 알림
     */
    fun sendError(market: String, message: String) {
        if (webhookUrl.isBlank()) return

        val text = """
            :x: 에러 발생

            *마켓*: $market
            *내용*: $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    /**
     * 일일 리포트
     */
    fun sendDailyReport(
        totalPnl: String,
        totalPnlPercent: String,
        trades: Int,
        winRate: String
    ) {
        if (webhookUrl.isBlank()) return

        val emoji = if (totalPnl.startsWith("-")) ":small_red_triangle_down:" else ":small_green_triangle:"

        val message = """
            :bar_chart: 일일 리포트

            $emoji *총 손익*: $totalPnl ($totalPnlPercent)
            :arrows_counterclockwise: *거래 횟수*: $trades
            :dart: *승률*: $winRate
            :clock3: *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(message)
    }

    /**
     * 시스템 알림
     */
    fun sendSystemNotification(title: String, message: String) {
        if (webhookUrl.isBlank()) return

        val text = """
            :robot_face: $title

            $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        try {
            webClient.post()
                .uri(webhookUrl)
                .bodyValue(mapOf("text" to text))
                .retrieve()
                .bodyToMono(String::class.java)
                .subscribe(
                    { log.debug("Slack 알림 전송 완료") },
                    { error -> log.error("Slack 알림 전송 실패: ${error.message}") }
                )
        } catch (e: Exception) {
            log.error("Slack 알림 전송 중 오류: ${e.message}")
        }
    }
}
