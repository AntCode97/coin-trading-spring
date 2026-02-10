package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
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
        try {
            val result = if (orderType == "market") {
                // 시장가 매수
                val krwAmount = amountKrw ?: BigDecimal.valueOf(10000)
                bithumbPrivateApi.buyMarketOrder(market, krwAmount)
            } else {
                // 지정가 매수
                requireNotNull(price) { "지정가 주문 시 price는 필수입니다" }
                requireNotNull(quantity) { "지정가 주문 시 quantity는 필수입니다" }
                bithumbPrivateApi.buyLimitOrder(market, price, quantity)
            }

            return apiSuccess(
                "market" to market,
                "orderType" to orderType,
                "orderId" to result.uuid,
                "amountKrw" to amountKrw,
                "quantity" to quantity,
                "price" to price,
                "message" to "매수 주문이 완료되었습니다"
            )
        } catch (e: Exception) {
            return apiFailure(e.message ?: "알 수 없는 오류", "market" to market)
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
        try {
            val result = if (orderType == "market") {
                // 시장가 매도
                bithumbPrivateApi.sellMarketOrder(market, quantity)
            } else {
                // 지정가 매도
                requireNotNull(price) { "지정가 주문 시 price는 필수입니다" }
                bithumbPrivateApi.sellLimitOrder(market, price, quantity)
            }

            return apiSuccess(
                "market" to market,
                "orderType" to orderType,
                "orderId" to result.uuid,
                "quantity" to quantity,
                "price" to price,
                "message" to "매도 주문이 완료되었습니다"
            )
        } catch (e: Exception) {
            return apiFailure(e.message ?: "알 수 없는 오류", "market" to market)
        }
    }

    /**
     * 잔고에서 특정 코인의 수량 조회
     */
    @GetMapping("/balance/{market}")
    suspend fun getBalance(@PathVariable market: String): Map<String, Any?> {
        try {
            val balances = bithumbPrivateApi.getBalances() ?: emptyList()
            val currency = market.removePrefix("KRW-")
            val balance = balances.find { it.currency == currency }

            return apiSuccess(
                "market" to market,
                "currency" to currency,
                "balance" to balance?.balance,
                "avgBuyPrice" to balance?.avgBuyPrice,
                "available" to balance?.balance
            )
        } catch (e: Exception) {
            return apiFailure(e.message ?: "알 수 없는 오류", "market" to market)
        }
    }
}
