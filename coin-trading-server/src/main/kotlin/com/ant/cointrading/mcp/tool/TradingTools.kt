package com.ant.cointrading.mcp.tool

import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.OrderResponse
import org.slf4j.LoggerFactory
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

// ========================================
// 응답 Data Classes
// ========================================

/** 단일 화폐 잔고 조회 결과 */
data class CurrencyBalance(
    val currency: String,
    val balance: BigDecimal
)

/** 주문 결과 (성공/실패) */
data class OrderResult(
    val success: Boolean,
    val order: OrderResponse? = null,
    val error: String? = null
)

/** 가격 검증 결과 */
data class PriceValidation(
    val valid: Boolean,
    val message: String? = null,
    val currentPrice: BigDecimal? = null
)

/**
 * 거래 관련 MCP 도구
 */
@Component
class TradingTools(
    private val privateApi: BithumbPrivateApi,
    private val publicApi: BithumbPublicApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @McpTool(description = "전체 계좌 잔고를 조회합니다.")
    fun getBalances(): List<Balance>? {
        return privateApi.getBalances()
    }

    @McpTool(description = "특정 화폐의 잔고를 조회합니다.")
    fun getBalance(
        @McpToolParam(description = "화폐 코드 (예: KRW, BTC, ETH)") currency: String
    ): CurrencyBalance {
        val balance = privateApi.getBalance(currency)
        return CurrencyBalance(currency = currency, balance = balance)
    }

    @McpTool(description = "지정가 매수 주문을 생성합니다. 주문 전 잔고와 가격 검증을 수행합니다.")
    fun buyLimitOrder(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "주문 가격 (KRW)") price: BigDecimal,
        @McpToolParam(description = "주문 수량") volume: BigDecimal
    ): OrderResult {
        val krwBalance = privateApi.getBalance("KRW")
        val totalCost = price.multiply(volume)

        if (totalCost > krwBalance) {
            return OrderResult(
                success = false,
                error = "잔고 부족. 필요: $totalCost KRW, 보유: $krwBalance KRW"
            )
        }

        val validation = validateOrderPrice(market, price)
        if (!validation.valid) {
            return OrderResult(success = false, error = validation.message)
        }

        return try {
            val order = privateApi.buyLimitOrder(market, price, volume)
            OrderResult(success = true, order = order)
        } catch (e: Exception) {
            log.error("Buy limit order failed: {}", e.message)
            OrderResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @McpTool(description = "지정가 매도 주문을 생성합니다. 주문 전 잔고와 가격 검증을 수행합니다.")
    fun sellLimitOrder(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "주문 가격 (KRW)") price: BigDecimal,
        @McpToolParam(description = "주문 수량") volume: BigDecimal
    ): OrderResult {
        val currency = if (market.contains("-")) market.split("-")[1] else market
        val coinBalance = privateApi.getBalance(currency)

        if (volume > coinBalance) {
            return OrderResult(
                success = false,
                error = "잔고 부족. 필요: $volume $currency, 보유: $coinBalance $currency"
            )
        }

        val validation = validateOrderPrice(market, price)
        if (!validation.valid) {
            return OrderResult(success = false, error = validation.message)
        }

        return try {
            val order = privateApi.sellLimitOrder(market, price, volume)
            OrderResult(success = true, order = order)
        } catch (e: Exception) {
            log.error("Sell limit order failed: {}", e.message)
            OrderResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @McpTool(description = "시장가 매수 주문을 생성합니다. 지정한 KRW 금액만큼 즉시 매수합니다.")
    fun buyMarketOrder(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "매수에 사용할 KRW 금액") krwAmount: BigDecimal
    ): OrderResult {
        val krwBalance = privateApi.getBalance("KRW")

        if (krwAmount > krwBalance) {
            return OrderResult(
                success = false,
                error = "잔고 부족. 필요: $krwAmount KRW, 보유: $krwBalance KRW"
            )
        }

        return try {
            val order = privateApi.buyMarketOrder(market, krwAmount)
            OrderResult(success = true, order = order)
        } catch (e: Exception) {
            log.error("Buy market order failed: {}", e.message)
            OrderResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @McpTool(description = "시장가 매도 주문을 생성합니다. 지정한 수량을 즉시 매도합니다.")
    fun sellMarketOrder(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "매도 수량") volume: BigDecimal
    ): OrderResult {
        val currency = if (market.contains("-")) market.split("-")[1] else market
        val coinBalance = privateApi.getBalance(currency)

        if (volume > coinBalance) {
            return OrderResult(
                success = false,
                error = "잔고 부족. 필요: $volume $currency, 보유: $coinBalance $currency"
            )
        }

        return try {
            val order = privateApi.sellMarketOrder(market, volume)
            OrderResult(success = true, order = order)
        } catch (e: Exception) {
            log.error("Sell market order failed: {}", e.message)
            OrderResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @McpTool(description = "주문 상태를 조회합니다.")
    fun getOrder(
        @McpToolParam(description = "주문 UUID") uuid: String
    ): OrderResponse? {
        return privateApi.getOrder(uuid)
    }

    @McpTool(description = "주문 목록을 조회합니다.")
    fun getOrders(
        @McpToolParam(description = "마켓 ID (선택)") market: String?,
        @McpToolParam(description = "주문 상태: wait, watch, done, cancel") state: String?,
        @McpToolParam(description = "페이지 번호") page: Int,
        @McpToolParam(description = "조회 개수 (최대 100)") limit: Int
    ): List<OrderResponse>? {
        return privateApi.getOrders(market, state, page, limit.coerceAtMost(100))
    }

    @McpTool(description = "주문을 취소합니다.")
    fun cancelOrder(
        @McpToolParam(description = "취소할 주문의 UUID") uuid: String
    ): OrderResult {
        return try {
            val order = privateApi.cancelOrder(uuid)
            OrderResult(success = true, order = order)
        } catch (e: Exception) {
            log.error("Cancel order failed: {}", e.message)
            OrderResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * 주문 가격이 현재 시장가의 5% 이내인지 검증
     */
    private fun validateOrderPrice(market: String, orderPrice: BigDecimal): PriceValidation {
        return try {
            val tickers = publicApi.getCurrentPrice(market)
            if (tickers.isNullOrEmpty()) {
                return PriceValidation(valid = false, message = "현재가 조회 실패")
            }

            val currentPrice = tickers.first().tradePrice
            val lowerBound = currentPrice.multiply(BigDecimal.valueOf(0.95))
            val upperBound = currentPrice.multiply(BigDecimal.valueOf(1.05))

            if (orderPrice < lowerBound || orderPrice > upperBound) {
                return PriceValidation(
                    valid = false,
                    message = "주문가격 ${orderPrice}이(가) 현재가 ${currentPrice}의 5% 범위(${
                        lowerBound.setScale(0, RoundingMode.HALF_UP)
                    } ~ ${upperBound.setScale(0, RoundingMode.HALF_UP)})를 벗어남",
                    currentPrice = currentPrice
                )
            }

            PriceValidation(valid = true, currentPrice = currentPrice)
        } catch (e: Exception) {
            PriceValidation(valid = false, message = "가격 검증 실패: ${e.message}")
        }
    }
}
