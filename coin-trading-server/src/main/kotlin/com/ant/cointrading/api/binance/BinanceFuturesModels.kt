package com.ant.cointrading.api.binance

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant

/**
 * 바이낸스 Futures 프리미엄 인덱스
 * https://fapi.binance.com/fapi/v1/premiumIndex
 */
data class BinancePremiumIndex(
    @JsonProperty("symbol") val symbol: String,
    @JsonProperty("markPrice") val markPrice: BigDecimal,
    @JsonProperty("indexPrice") val indexPrice: BigDecimal,
    @JsonProperty("estimatedSettlePrice") val estimatedSettlePrice: BigDecimal?,
    @JsonProperty("lastFundingRate") val lastFundingRate: BigDecimal,
    @JsonProperty("nextFundingTime") val nextFundingTime: Long,
    @JsonProperty("interestRate") val interestRate: BigDecimal?,
    @JsonProperty("time") val time: Long
)

/**
 * 바이낸스 Futures 펀딩 비율 히스토리
 * https://fapi.binance.com/fapi/v1/fundingRate
 */
data class BinanceFundingRate(
    @JsonProperty("symbol") val symbol: String,
    @JsonProperty("fundingRate") val fundingRate: BigDecimal,
    @JsonProperty("fundingTime") val fundingTime: Long,
    @JsonProperty("markPrice") val markPrice: BigDecimal?
)

/**
 * 바이낸스 Futures 마크 가격
 */
data class BinanceMarkPrice(
    @JsonProperty("symbol") val symbol: String,
    @JsonProperty("markPrice") val markPrice: BigDecimal,
    @JsonProperty("indexPrice") val indexPrice: BigDecimal,
    @JsonProperty("lastFundingRate") val lastFundingRate: BigDecimal,
    @JsonProperty("nextFundingTime") val nextFundingTime: Long,
    @JsonProperty("time") val time: Long
)

/**
 * 펀딩 기회 정보
 */
data class FundingOpportunity(
    val exchange: String,           // "BINANCE" or "BYBIT"
    val symbol: String,             // "BTCUSDT"
    val fundingRate: Double,        // 0.0001 = 0.01%
    val annualizedRate: Double,     // 연환산 수익률 (%)
    val nextFundingTime: Instant,   // 다음 펀딩 시간
    val markPrice: Double,          // 마크 가격
    val indexPrice: Double          // 인덱스 가격 (현물 기반)
) {
    /**
     * 다음 펀딩까지 남은 시간 (분)
     */
    fun minutesUntilFunding(): Long {
        return java.time.Duration.between(Instant.now(), nextFundingTime).toMinutes()
    }

    /**
     * 펀딩 비율 포맷팅 (%)
     */
    fun fundingRatePercent(): String {
        return String.format("%.4f%%", fundingRate * 100)
    }

    /**
     * 연환산 수익률 포맷팅
     */
    fun annualizedRateFormatted(): String {
        return String.format("%.2f%% APY", annualizedRate)
    }

    /**
     * 진입 권장 여부
     * - 펀딩까지 2시간 이내
     * - 연환산 수익률 15% 이상
     */
    fun isRecommendedEntry(): Boolean {
        return minutesUntilFunding() <= 120 && annualizedRate >= 15.0
    }
}

/**
 * 펀딩 상세 정보
 */
data class FundingInfo(
    val symbol: String,
    val currentFundingRate: Double,
    val currentAnnualizedRate: Double,
    val avgFundingRate: Double,         // 최근 10회 평균
    val avgAnnualizedRate: Double,
    val nextFundingTime: Instant,
    val markPrice: Double,
    val indexPrice: Double,
    val recentHistory: List<FundingHistoryItem>
) {
    /**
     * 펀딩 비율 안정성 (변동성이 낮을수록 안정적)
     */
    fun isStable(): Boolean {
        // 현재 비율과 평균 비율의 차이가 50% 이내면 안정적
        if (avgFundingRate == 0.0) return false
        val deviation = kotlin.math.abs((currentFundingRate - avgFundingRate) / avgFundingRate)
        return deviation <= 0.5
    }
}

/**
 * 펀딩 히스토리 항목
 */
data class FundingHistoryItem(
    val fundingRate: Double,
    val fundingTime: Instant
)
