package com.ant.cointrading.api.binance

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class BinanceFuturesPrivateApi(
    private val binanceRestClient: RestClient,
    private val auth: BinanceFuturesAuth,
    private val properties: BinanceFuturesProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 10000L
    }

    fun placeOrder(
        symbol: String,
        side: String,
        quantity: BigDecimal,
        orderType: String = "MARKET",
        price: BigDecimal? = null
    ): OrderResult? {
        val requestBody = mutableMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to orderType,
            "quantity" to quantity.toPlainString(),
            "timestamp" to System.currentTimeMillis().toString()
        )

        if (orderType == "LIMIT" && price != null) {
            requestBody["price"] = price.toPlainString()
            requestBody["timeInForce"] = "GTC"
        }

        return executeWithRetry("place_order") {
            val signature = auth.generateSignature(buildQueryString(requestBody))
            val queryString = buildQueryString(requestBody) + "&signature=$signature"

            val result = binanceRestClient.post()
                .uri("${properties.baseUrl}/fapi/v1/order?$queryString")
                .headers { headers ->
                    headers.add("X-MBX-APIKEY", properties.apiKey)
                    headers.add("Content-Type", "application/x-www-form-urlencoded")
                }
                .retrieve()
                .body(OrderResult::class.java)

            log.info("[$symbol] 주문 완료: orderId=${result?.orderId}, status=${result?.status}, executedQty=${result?.executedQty}/${result?.origQty}")

            result
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

            binanceRestClient.delete()
                .uri("${properties.baseUrl}/fapi/v1/order?$queryString")
                .headers { headers ->
                    headers.add("X-MBX-APIKEY", properties.apiKey)
                    headers.add("Content-Type", "application/x-www-form-urlencoded")
                }
                .retrieve()
                .body(OrderResult::class.java)
        }
    }

    fun getOrderStatus(symbol: String, orderId: Long): OrderResult? {
        val requestBody = mapOf(
            "symbol" to symbol,
            "orderId" to orderId,
            "timestamp" to System.currentTimeMillis().toString()
        )

        return executeWithRetry("get_order_status") {
            val signature = auth.generateSignature(buildQueryString(requestBody))
            val queryString = buildQueryString(requestBody) + "&signature=$signature"

            binanceRestClient.get()
                .uri("${properties.baseUrl}/fapi/v1/order?$queryString")
                .headers { headers ->
                    headers.add("X-MBX-APIKEY", properties.apiKey)
                }
                .retrieve()
                .body(OrderResult::class.java)
        }
    }

    fun placeOrderWithPartialFill(
        symbol: String,
        side: String,
        quantity: BigDecimal,
        orderType: String = "MARKET",
        price: BigDecimal? = null,
        maxReorderAttempts: Int = 3
    ): OrderResultWithReorder? {
        val initialResult = placeOrder(symbol, side, quantity, orderType, price)

        if (initialResult == null) {
            log.error("[$symbol] 주문 실패: null response")
            return OrderResultWithReorder(
                success = false,
                symbol = symbol,
                totalExecutedQty = BigDecimal.ZERO,
                orderIds = emptyList(),
                errorMessage = "주문 실패"
            )
        }

        val orderIds = mutableListOf<Long>()

        initialResult.orderId?.let { orderIds.add(it) }

        if (!initialResult.isPartialFill()) {
            return OrderResultWithReorder(
                success = initialResult.isFilled(),
                symbol = symbol,
                totalExecutedQty = initialResult.executedQty,
                orderIds = orderIds,
                errorMessage = if (initialResult.isRejected()) initialResult.status else null
            )
        }

        log.warn("[$symbol] 부분 체결 발생: ${initialResult.executedQty}/${initialResult.origQty}. 재주문 시도...")

        var totalExecutedQty = initialResult.executedQty
        val remainingQty = initialResult.origQty.subtract(initialResult.executedQty)

        for (attempt in 1..maxReorderAttempts) {
            val reorderId = placeOrder(symbol, side, remainingQty, orderType, price)

            if (reorderId == null) {
                log.warn("[$symbol] 재주문 실패 (시도 $attempt/$maxReorderAttempts)")
                continue
            }

            reorderId.orderId?.let { orderIds.add(it) }
            totalExecutedQty = totalExecutedQty.add(reorderId.executedQty)
            val newRemaining = remainingQty.subtract(reorderId.executedQty)

            log.info("[$symbol] 재주문 완료 (시도 $attempt): executedQty=${reorderId.executedQty}, remaining=$newRemaining")

            if (!reorderId.isPartialFill() || newRemaining.compareTo(BigDecimal.ZERO) == 0) {
                return OrderResultWithReorder(
                    success = true,
                    symbol = symbol,
                    totalExecutedQty = totalExecutedQty,
                    orderIds = orderIds,
                    errorMessage = null
                )
            }
        }

        log.error("[$symbol] 최대 재주문 시도 초과: 총 체결=${totalExecutedQty}, 원래 수량=${quantity}")
        return OrderResultWithReorder(
            success = false,
            symbol = symbol,
            totalExecutedQty = totalExecutedQty,
            orderIds = orderIds,
            errorMessage = "최대 재주문 시도 초과"
        )
    }

    data class OrderResult(
        val orderId: Long?,
        val clientOrderId: String?,
        val symbol: String,
        val status: String,
        val transactTime: Long,
        val origQty: BigDecimal = BigDecimal.ZERO,
        val executedQty: BigDecimal = BigDecimal.ZERO
    ) {
        fun isFilled(): Boolean = status == "FILLED"
        fun isPartiallyFilled(): Boolean = status == "PARTIALLY_FILLED"
        fun isRejected(): Boolean = status == "REJECTED" || status == "EXPIRED"
        fun isPending(): Boolean = status == "NEW" || status == "PARTIALLY_FILLED"
        fun isPartialFill(): Boolean = executedQty < origQty && executedQty > BigDecimal.ZERO
    }

    data class OrderResultWithReorder(
        val success: Boolean,
        val symbol: String,
        val totalExecutedQty: BigDecimal,
        val orderIds: List<Long>,
        val errorMessage: String?
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
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), StandardCharsets.UTF_8)}" }
    }
}
