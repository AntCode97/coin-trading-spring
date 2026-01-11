package com.ant.cointrading.mcp.tool

import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.OrderResponse
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 거래 관련 MCP 도구
 */
@Component
class TradingTools(
    private val privateApi: BithumbPrivateApi,
    private val publicApi: BithumbPublicApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(description = "전체 계좌 잔고를 조회합니다.")
    fun getBalances(): List<Balance>? {
        return privateApi.getBalances()
    }

    @Tool(description = "특정 화폐의 잔고를 조회합니다.")
    fun getBalance(
        @ToolParam(description = "화폐 코드 (예: KRW, BTC, ETH)") currency: String
    ): Map<String, Any> {
        val balance = privateApi.getBalance(currency)
        return mapOf("currency" to currency, "balance" to balance)
    }

    @Tool(description = "지정가 매수 주문을 생성합니다. 주문 전 잔고와 가격 검증을 수행합니다.")
    fun buyLimitOrder(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "주문 가격 (KRW)") price: BigDecimal,
        @ToolParam(description = "주문 수량") volume: BigDecimal
    ): Map<String, Any> {
        val krwBalance = privateApi.getBalance("KRW")
        val totalCost = price.multiply(volume)

        if (totalCost > krwBalance) {
            return mapOf(
                "success" to false,
                "error" to "잔고 부족. 필요: $totalCost KRW, 보유: $krwBalance KRW"
            )
        }

        val validation = validateOrderPrice(market, price)
        if (validation["valid"] != true) {
            return mapOf("success" to false, "error" to validation["message"]!!)
        }

        return try {
            val order = privateApi.buyLimitOrder(market, price, volume)
            mapOf("success" to true, "order" to (order as Any))
        } catch (e: Exception) {
            log.error("Buy limit order failed: {}", e.message)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    @Tool(description = "지정가 매도 주문을 생성합니다. 주문 전 잔고와 가격 검증을 수행합니다.")
    fun sellLimitOrder(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "주문 가격 (KRW)") price: BigDecimal,
        @ToolParam(description = "주문 수량") volume: BigDecimal
    ): Map<String, Any> {
        val currency = if (market.contains("-")) market.split("-")[1] else market
        val coinBalance = privateApi.getBalance(currency)

        if (volume > coinBalance) {
            return mapOf(
                "success" to false,
                "error" to "잔고 부족. 필요: $volume $currency, 보유: $coinBalance $currency"
            )
        }

        val validation = validateOrderPrice(market, price)
        if (validation["valid"] != true) {
            return mapOf("success" to false, "error" to validation["message"]!!)
        }

        return try {
            val order = privateApi.sellLimitOrder(market, price, volume)
            mapOf("success" to true, "order" to (order as Any))
        } catch (e: Exception) {
            log.error("Sell limit order failed: {}", e.message)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    @Tool(description = "시장가 매수 주문을 생성합니다. 지정한 KRW 금액만큼 즉시 매수합니다.")
    fun buyMarketOrder(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "매수에 사용할 KRW 금액") krwAmount: BigDecimal
    ): Map<String, Any> {
        val krwBalance = privateApi.getBalance("KRW")

        if (krwAmount > krwBalance) {
            return mapOf(
                "success" to false,
                "error" to "잔고 부족. 필요: $krwAmount KRW, 보유: $krwBalance KRW"
            )
        }

        return try {
            val order = privateApi.buyMarketOrder(market, krwAmount)
            mapOf("success" to true, "order" to (order as Any))
        } catch (e: Exception) {
            log.error("Buy market order failed: {}", e.message)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    @Tool(description = "시장가 매도 주문을 생성합니다. 지정한 수량을 즉시 매도합니다.")
    fun sellMarketOrder(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "매도 수량") volume: BigDecimal
    ): Map<String, Any> {
        val currency = if (market.contains("-")) market.split("-")[1] else market
        val coinBalance = privateApi.getBalance(currency)

        if (volume > coinBalance) {
            return mapOf(
                "success" to false,
                "error" to "잔고 부족. 필요: $volume $currency, 보유: $coinBalance $currency"
            )
        }

        return try {
            val order = privateApi.sellMarketOrder(market, volume)
            mapOf("success" to true, "order" to (order as Any))
        } catch (e: Exception) {
            log.error("Sell market order failed: {}", e.message)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    @Tool(description = "주문 상태를 조회합니다.")
    fun getOrder(
        @ToolParam(description = "주문 UUID") uuid: String
    ): OrderResponse? {
        return privateApi.getOrder(uuid)
    }

    @Tool(description = "주문 목록을 조회합니다.")
    fun getOrders(
        @ToolParam(description = "마켓 ID (선택)") market: String?,
        @ToolParam(description = "주문 상태: wait, watch, done, cancel") state: String?,
        @ToolParam(description = "페이지 번호") page: Int,
        @ToolParam(description = "조회 개수 (최대 100)") limit: Int
    ): List<OrderResponse>? {
        return privateApi.getOrders(market, state, page, limit.coerceAtMost(100))
    }

    @Tool(description = "주문을 취소합니다.")
    fun cancelOrder(
        @ToolParam(description = "취소할 주문의 UUID") uuid: String
    ): Map<String, Any> {
        return try {
            val order = privateApi.cancelOrder(uuid)
            mapOf("success" to true, "order" to (order as Any))
        } catch (e: Exception) {
            log.error("Cancel order failed: {}", e.message)
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    /**
     * 주문 가격이 현재 시장가의 5% 이내인지 검증
     */
    private fun validateOrderPrice(market: String, orderPrice: BigDecimal): Map<String, Any> {
        return try {
            val tickers = publicApi.getCurrentPrice(market)
            if (tickers.isNullOrEmpty()) {
                return mapOf("valid" to false, "message" to "현재가 조회 실패")
            }

            val currentPrice = tickers.first().tradePrice
            val lowerBound = currentPrice.multiply(BigDecimal.valueOf(0.95))
            val upperBound = currentPrice.multiply(BigDecimal.valueOf(1.05))

            if (orderPrice < lowerBound || orderPrice > upperBound) {
                return mapOf(
                    "valid" to false,
                    "message" to "주문가격 ${orderPrice}이(가) 현재가 ${currentPrice}의 5% 범위(${
                        lowerBound.setScale(0, RoundingMode.HALF_UP)
                    } ~ ${upperBound.setScale(0, RoundingMode.HALF_UP)})를 벗어남"
                )
            }

            mapOf("valid" to true, "currentPrice" to currentPrice)
        } catch (e: Exception) {
            mapOf("valid" to false, "message" to "가격 검증 실패: ${e.message}")
        }
    }
}
