package com.ant.cointrading.notification

import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.order.OrderResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Slack Bot API를 통한 알림 전송
 */
@Component
class SlackNotifier(
    @Qualifier("slackRestClient") private val restClient: RestClient
) {
    private val log = LoggerFactory.getLogger(SlackNotifier::class.java)

    @Value("\${slack.token:}")
    private lateinit var token: String

    @Value("\${slack.channel:coin-bot-alert}")
    private lateinit var channel: String

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    fun sendTradeNotification(signal: TradingSignal, result: OrderResult) {
        if (token.isBlank()) return

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

    fun sendWarning(market: String, message: String) {
        if (token.isBlank()) return

        val text = """
            :warning: 경고

            *마켓*: $market
            *내용*: $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    fun sendError(market: String, message: String) {
        if (token.isBlank()) return

        val text = """
            :x: 에러 발생

            *마켓*: $market
            *내용*: $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    fun sendDailyReport(
        totalPnl: String,
        totalPnlPercent: String,
        trades: Int,
        winRate: String
    ) {
        if (token.isBlank()) return

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

    fun sendSystemNotification(title: String, message: String) {
        if (token.isBlank()) return

        val text = """
            :robot_face: $title

            $message
            *시간*: ${dateFormatter.format(Instant.now())}
        """.trimIndent()

        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        try {
            val response: Map<*, *>? = restClient.post()
                .uri("https://slack.com/api/chat.postMessage")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .body(mapOf("channel" to channel, "text" to text))
                .retrieve()
                .body(Map::class.java)

            if (response?.get("ok") == true) {
                log.debug("Slack 알림 전송 완료")
            } else {
                log.error("Slack 알림 실패: ${response?.get("error")}")
            }
        } catch (e: Exception) {
            log.error("Slack 알림 전송 중 오류: ${e.message}")
        }
    }
}
