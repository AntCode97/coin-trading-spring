package com.ant.cointrading.api.binance

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class BinanceFuturesPrivateApi(
    private val restClient: RestClient,
    private val auth: BinanceFuturesAuth,
    private val properties: BinanceFuturesProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 10000L
    }

    fun placeOrder(symbol: String, side: String, quantity: BigDecimal): OrderResult? {
        val requestBody = mapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to "MARKET",
            "quantity" to quantity.toPlainString(),
            "timestamp" to System.currentTimeMillis().toString()
        )

        return executeWithRetry("place_order") {
            val signature = auth.generateSignature(buildQueryString(requestBody))
            val queryString = buildQueryString(requestBody) + "&signature=$signature"

            restClient.post()
                .uri("${properties.baseUrl}/fapi/v1/order?$queryString")
                .headers { headers ->
                    headers.add("X-MBX-APIKEY", properties.apiKey)
                    headers.add("Content-Type", "application/x-www-form-urlencoded")
                }
                .retrieve()
                .body(OrderResult::class.java)
        }
    }

    fun cancelOrder(symbol: String, orderId: Long): OrderResult? {
        val requestBody = mapOf(
            "symbol" to symbol,
            "orderId" to orderId,
            "timestamp" to System.currentTimeMillis().toString()
        )

        return executeWithRetry("cancel_order") {
            val signature = auth.generateSignature(buildQueryString(requestBody))
            val queryString = buildQueryString(requestBody) + "&signature=$signature"

            restClient.delete()
                .uri("${properties.baseUrl}/fapi/v1/order?$queryString")
                .headers { headers ->
                    headers.add("X-MBX-APIKEY", properties.apiKey)
                    headers.add("Content-Type", "application/x-www-form-urlencoded")
                }
                .retrieve()
                .body(OrderResult::class.java)
        }
    }

    data class OrderResult(
        val orderId: Long?,
        val clientOrderId: String?,
        val symbol: String,
        val status: String,
        val transactTime: Long
    )

    private fun <T> executeWithRetry(operationName: String, block: () -> T?): T? {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    val backoffMs = calculateBackoff(attempt)
                    log.warn("Binance Futures API 에러 [$operationName] (시도 $attempt/$MAX_RETRY_ATTEMPTS). ${backoffMs}ms 후 재시도")
                    Thread.sleep(backoffMs)
                }
            }
        }

        log.error("Binance Futures API 재시도 실패 [$operationName]: 최대 재시도 횟수 초과")
        return null
    }

    private fun calculateBackoff(attempt: Int): Long {
        val backoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun buildQueryString(params: Map<String, Any>): String {
        return params.entries
            .filter { it.value != null }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value!!.toString(), StandardCharsets.UTF_8)}" }
    }
}
