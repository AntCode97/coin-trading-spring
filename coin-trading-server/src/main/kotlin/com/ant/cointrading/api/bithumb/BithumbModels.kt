package com.ant.cointrading.api.bithumb

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * 현재가 정보
 */
data class TickerInfo(
    @JsonProperty("market") val market: String,
    @JsonProperty("trade_price") val tradePrice: BigDecimal,
    @JsonProperty("opening_price") val openingPrice: BigDecimal?,
    @JsonProperty("high_price") val highPrice: BigDecimal?,
    @JsonProperty("low_price") val lowPrice: BigDecimal?,
    @JsonProperty("prev_closing_price") val prevClosingPrice: BigDecimal?,
    @JsonProperty("change") val change: String?,
    @JsonProperty("change_price") val changePrice: BigDecimal?,
    @JsonProperty("change_rate") val changeRate: BigDecimal?,
    @JsonProperty("trade_volume") val tradeVolume: BigDecimal?,
    @JsonProperty("acc_trade_volume") val accTradeVolume: BigDecimal?,
    @JsonProperty("acc_trade_price") val accTradePrice: BigDecimal?,
    @JsonProperty("timestamp") val timestamp: Long?,
    @JsonProperty("trade_date") val tradeDate: String?
)

/**
 * 마켓 정보
 */
data class MarketInfo(
    @JsonProperty("market") val market: String,
    @JsonProperty("korean_name") val koreanName: String?,
    @JsonProperty("english_name") val englishName: String?,
    @JsonProperty("market_warning") val marketWarning: String? = null
)

/**
 * 호가창 정보
 */
data class OrderbookInfo(
    @JsonProperty("market") val market: String,
    @JsonProperty("timestamp") val timestamp: Long?,
    @JsonProperty("total_ask_size") val totalAskSize: BigDecimal?,
    @JsonProperty("total_bid_size") val totalBidSize: BigDecimal?,
    @JsonProperty("orderbook_units") val orderbookUnits: List<OrderbookUnit>?
)

/**
 * 호가 단위
 */
data class OrderbookUnit(
    @JsonProperty("ask_price") val askPrice: BigDecimal,
    @JsonProperty("bid_price") val bidPrice: BigDecimal,
    @JsonProperty("ask_size") val askSize: BigDecimal,
    @JsonProperty("bid_size") val bidSize: BigDecimal
)

/**
 * 잔고 정보
 */
data class Balance(
    @JsonProperty("currency") val currency: String,
    @JsonProperty("balance") val balance: BigDecimal,
    @JsonProperty("locked") val locked: BigDecimal?,
    @JsonProperty("avg_buy_price") val avgBuyPrice: BigDecimal?,
    @JsonProperty("avg_buy_price_modified") val avgBuyPriceModified: Boolean?,
    @JsonProperty("unit_currency") val unitCurrency: String?
)

/**
 * 주문 응답
 */
data class OrderResponse(
    @JsonProperty("uuid") val uuid: String,
    @JsonProperty("side") val side: String,
    @JsonProperty("ord_type") val ordType: String,
    @JsonProperty("price") val price: BigDecimal?,
    @JsonProperty("state") val state: String?,
    @JsonProperty("market") val market: String,
    @JsonProperty("created_at") val createdAt: String?,
    @JsonProperty("volume") val volume: BigDecimal?,
    @JsonProperty("remaining_volume") val remainingVolume: BigDecimal?,
    @JsonProperty("reserved_fee") val reservedFee: BigDecimal?,
    @JsonProperty("remaining_fee") val remainingFee: BigDecimal?,
    @JsonProperty("paid_fee") val paidFee: BigDecimal?,
    @JsonProperty("locked") val locked: BigDecimal?,
    @JsonProperty("executed_volume") val executedVolume: BigDecimal?
)

/**
 * 캔들 (OHLCV) API 응답
 */
data class CandleResponse(
    @JsonProperty("market") val market: String,
    @JsonProperty("candle_date_time_utc") val candleDateTimeUtc: String,
    @JsonProperty("candle_date_time_kst") val candleDateTimeKst: String,
    @JsonProperty("opening_price") val openingPrice: BigDecimal,
    @JsonProperty("high_price") val highPrice: BigDecimal,
    @JsonProperty("low_price") val lowPrice: BigDecimal,
    @JsonProperty("trade_price") val tradePrice: BigDecimal,
    @JsonProperty("timestamp") val timestamp: Long,
    @JsonProperty("candle_acc_trade_price") val candleAccTradePrice: BigDecimal,
    @JsonProperty("candle_acc_trade_volume") val candleAccTradeVolume: BigDecimal,
    @JsonProperty("unit") val unit: Int? = null
)

/**
 * 체결 내역 API 응답
 */
data class TradeResponse(
    @JsonProperty("market") val market: String,
    @JsonProperty("trade_date_utc") val tradeDateUtc: String,
    @JsonProperty("trade_time_utc") val tradeTimeUtc: String,
    @JsonProperty("timestamp") val timestamp: Long,
    @JsonProperty("trade_price") val tradePrice: BigDecimal,
    @JsonProperty("trade_volume") val tradeVolume: BigDecimal,
    @JsonProperty("prev_closing_price") val prevClosingPrice: BigDecimal?,
    @JsonProperty("change_price") val changePrice: BigDecimal?,
    @JsonProperty("ask_bid") val askBid: String,
    @JsonProperty("sequential_id") val sequentialId: Long
)

/**
 * 가상자산 경보 정보 (경보제 API)
 *
 * 실제 응답 예시:
 * {"market":"KRW-XPR","warning_type":"TRADING_VOLUME_SUDDEN_FLUCTUATION","end_date":"2026-01-13 06:59:59"}
 *
 * warning_type 종류:
 * - TRADING_VOLUME_SUDDEN_FLUCTUATION: 거래량 급등
 * - DEPOSIT_AMOUNT_SUDDEN_FLUCTUATION: 입금량 급등
 * - PRICE_DIFFERENCE_HIGH: 해외 가격 괴리
 */
data class VirtualAssetWarning(
    @JsonProperty("market") val market: String,
    @JsonProperty("warning_type") val warningType: String?,
    @JsonProperty("end_date") val endDate: String?
)

/**
 * API 에러 응답
 */
data class BithumbError(
    @JsonProperty("error") val error: ErrorDetail?
) {
    data class ErrorDetail(
        @JsonProperty("name") val name: String?,
        @JsonProperty("message") val message: String?
    )
}

/**
 * Bithumb API 예외
 *
 * 주요 에러 코드:
 * - invalid_access_key: 잘못된 API 키
 * - jwt_verification: JWT 인증 실패
 * - expired_access_key: 만료된 API 키
 * - no_authorization_ip: IP 인가 실패
 * - insufficient_funds: 잔고 부족
 * - under_min_total: 최소 주문 금액 미달
 * - invalid_volume: 잘못된 수량
 * - invalid_price: 잘못된 가격
 * - order_not_found: 주문을 찾을 수 없음
 */
class BithumbApiException(
    val errorName: String?,
    val errorMessage: String?,
    cause: Throwable? = null
) : RuntimeException("Bithumb API Error: [$errorName] $errorMessage", cause) {

    fun isInsufficientFunds() = errorName == "insufficient_funds"
    fun isInvalidOrder() = errorName in listOf("invalid_volume", "invalid_price", "under_min_total")
    fun isAuthError() = errorName in listOf("invalid_access_key", "jwt_verification", "expired_access_key", "no_authorization_ip")
    fun isOrderNotFound() = errorName == "order_not_found"
}
