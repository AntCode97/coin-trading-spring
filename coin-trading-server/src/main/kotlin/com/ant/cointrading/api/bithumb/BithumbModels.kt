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
    @JsonProperty("timestamp") val timestamp: Long?
)

/**
 * 마켓 정보
 */
data class MarketInfo(
    @JsonProperty("market") val market: String,
    @JsonProperty("korean_name") val koreanName: String?,
    @JsonProperty("english_name") val englishName: String?
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
