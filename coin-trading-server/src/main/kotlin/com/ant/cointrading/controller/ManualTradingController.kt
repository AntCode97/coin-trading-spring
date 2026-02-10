package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.util.apiFailure
import com.ant.cointrading.util.apiSuccess
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * 수동 트레이딩 REST API
 *
 * 사용자가 버튼을 눌러 직접 매수/매도할 때 사용
 */
@RestController
@RequestMapping("/api/manual-trading")
class ManualTradingController(
    private val bithumbPrivateApi: BithumbPrivateApi,
) {

    /**
     * 수동 매수
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param amountKrw 투자 금액 (KRW) - 시장가 시
     * @param quantity 수량 - 지정가 시
     * @param orderType 주문 유형 (price: 지정가, market: 시장가)
     * @param price 지정가 (orderType=price일 때만)
     */
    @PostMapping("/buy")
    suspend fun buy(
        @RequestParam market: String,
        @RequestParam(required = false) amountKrw: BigDecimal?,
        @RequestParam(required = false) quantity: BigDecimal?,
        @RequestParam(defaultValue = "market") orderType: String,
        @RequestParam(required = false) price: BigDecimal?
    ): Map<String, Any?> {
        return withMarketError(market) {
            val result = placeBuyOrder(market, orderType, amountKrw, quantity, price)
            apiSuccess(
                "market" to market,
                "orderType" to orderType,
                "orderId" to result.uuid,
                "amountKrw" to amountKrw,
                "quantity" to quantity,
                "price" to price,
                "message" to "매수 주문이 완료되었습니다"
            )
        }
    }

    /**
     * 수동 매도
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param quantity 수량 (해당 코인 수량)
     * @param orderType 주문 유형 (price: 지정가, market: 시장가)
     * @param price 지정가 (orderType=price일 때만)
     */
    @PostMapping("/sell")
    suspend fun sell(
        @RequestParam market: String,
        @RequestParam quantity: BigDecimal,
        @RequestParam(defaultValue = "market") orderType: String,
        @RequestParam(required = false) price: BigDecimal?
    ): Map<String, Any?> {
        return withMarketError(market) {
            val result = placeSellOrder(market, orderType, quantity, price)
            apiSuccess(
                "market" to market,
                "orderType" to orderType,
                "orderId" to result.uuid,
                "quantity" to quantity,
                "price" to price,
                "message" to "매도 주문이 완료되었습니다"
            )
        }
    }

    /**
     * 잔고에서 특정 코인의 수량 조회
     */
    @GetMapping("/balance/{market}")
    suspend fun getBalance(@PathVariable market: String): Map<String, Any?> {
        return withMarketError(market) {
            val balances = bithumbPrivateApi.getBalances() ?: emptyList()
            val currency = market.removePrefix("KRW-")
            val balance = balances.find { it.currency == currency }

            apiSuccess(
                "market" to market,
                "currency" to currency,
                "balance" to balance?.balance,
                "avgBuyPrice" to balance?.avgBuyPrice,
                "available" to balance?.balance
            )
        }
    }

    private inline fun withMarketError(
        market: String,
        block: () -> Map<String, Any?>
    ): Map<String, Any?> {
        return try {
            block()
        } catch (e: Exception) {
            apiFailure(e.message ?: "알 수 없는 오류", "market" to market)
        }
    }

    private fun placeBuyOrder(
        market: String,
        orderType: String,
        amountKrw: BigDecimal?,
        quantity: BigDecimal?,
        price: BigDecimal?
    ): OrderResponse {
        if (orderType == "market") {
            val krwAmount = amountKrw ?: BigDecimal.valueOf(10000)
            return bithumbPrivateApi.buyMarketOrder(market, krwAmount)
        }

        requireNotNull(price) { "지정가 주문 시 price는 필수입니다" }
        requireNotNull(quantity) { "지정가 주문 시 quantity는 필수입니다" }
        return bithumbPrivateApi.buyLimitOrder(market, price, quantity)
    }

    private fun placeSellOrder(
        market: String,
        orderType: String,
        quantity: BigDecimal,
        price: BigDecimal?
    ): OrderResponse {
        if (orderType == "market") {
            return bithumbPrivateApi.sellMarketOrder(market, quantity)
        }

        requireNotNull(price) { "지정가 주문 시 price는 필수입니다" }
        return bithumbPrivateApi.sellLimitOrder(market, price, quantity)
    }
}
