package com.ant.cointrading.api.binance

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * 바이낸스 티커 정보
 * https://api.binance.com/api/v3/ticker/24hr
 */
data class BinanceTickerInfo(
    @JsonProperty("symbol") val symbol: String,
    @JsonProperty("lastPrice") val lastPrice: BigDecimal,
    @JsonProperty("priceChange") val priceChange: BigDecimal?,
    @JsonProperty("priceChangePercent") val priceChangePercent: BigDecimal?,
    @JsonProperty("highPrice") val highPrice: BigDecimal?,
    @JsonProperty("lowPrice") val lowPrice: BigDecimal?,
    @JsonProperty("volume") val volume: BigDecimal?,
    @JsonProperty("quoteVolume") val quoteVolume: BigDecimal?
)

/**
 * 바이낸스 심플 티커 (현재가만)
 * https://api.binance.com/api/v3/ticker/price
 */
data class BinanceSimpleTicker(
    @JsonProperty("symbol") val symbol: String,
    @JsonProperty("price") val price: BigDecimal
)

/**
 * 김치 프리미엄 정보
 */
data class KimchiPremiumInfo(
    val symbol: String,               // 예: BTC
    val domesticPrice: BigDecimal,    // 빗썸 가격 (KRW)
    val foreignPrice: BigDecimal,     // 바이낸스 가격 (USDT)
    val exchangeRate: BigDecimal,     // USD/KRW 환율
    val foreignPriceKrw: BigDecimal,  // 바이낸스 가격 (KRW 환산)
    val premiumAmount: BigDecimal,    // 프리미엄 금액 (KRW)
    val premiumPercent: BigDecimal,   // 프리미엄 퍼센트
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 프리미엄 상태
     */
    val status: PremiumStatus
        get() = when {
            premiumPercent >= BigDecimal("5.0") -> PremiumStatus.EXTREME_HIGH   // 5% 이상
            premiumPercent >= BigDecimal("3.0") -> PremiumStatus.HIGH           // 3~5%
            premiumPercent >= BigDecimal("1.0") -> PremiumStatus.NORMAL         // 1~3%
            premiumPercent >= BigDecimal("-1.0") -> PremiumStatus.LOW           // -1~1%
            premiumPercent >= BigDecimal("-3.0") -> PremiumStatus.DISCOUNT      // -3~-1%
            else -> PremiumStatus.EXTREME_DISCOUNT                              // -3% 이하
        }
}

enum class PremiumStatus(val description: String) {
    EXTREME_HIGH("극도의 과열 (5%+)"),
    HIGH("과열 (3~5%)"),
    NORMAL("정상 (1~3%)"),
    LOW("저평가 (-1~1%)"),
    DISCOUNT("할인 (-3~-1%)"),
    EXTREME_DISCOUNT("극도의 할인 (-3%)")
}
