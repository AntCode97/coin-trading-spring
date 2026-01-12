package com.ant.cointrading.controller

import com.ant.cointrading.api.binance.KimchiPremiumInfo
import com.ant.cointrading.service.KimchiPremiumService
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * 김치 프리미엄 API 컨트롤러
 */
@RestController
@RequestMapping("/api/kimchi-premium")
class KimchiPremiumController(
    private val kimchiPremiumService: KimchiPremiumService
) {

    /**
     * 모든 주요 코인 프리미엄 조회
     */
    @GetMapping
    fun getAllPremiums(): Map<String, Any> {
        val premiums = kimchiPremiumService.getAllPremiums()
        val avgPremium = kimchiPremiumService.getAveragePremium()

        return mapOf(
            "exchangeRate" to kimchiPremiumService.getExchangeRate(),
            "averagePremium" to avgPremium,
            "premiums" to premiums.map { it.toResponse() }
        )
    }

    /**
     * 단일 코인 프리미엄 조회
     */
    @GetMapping("/{symbol}")
    fun getPremium(@PathVariable symbol: String): Map<String, Any?> {
        val premium = kimchiPremiumService.calculatePremium(symbol.uppercase())

        return if (premium != null) {
            mapOf(
                "success" to true,
                "exchangeRate" to kimchiPremiumService.getExchangeRate(),
                "premium" to premium.toResponse()
            )
        } else {
            mapOf(
                "success" to false,
                "error" to "프리미엄 조회 실패: $symbol"
            )
        }
    }

    /**
     * 캐시된 프리미엄 조회 (API 호출 없음)
     */
    @GetMapping("/cached")
    fun getCachedPremiums(): Map<String, Any> {
        val cached = kimchiPremiumService.getCachedPremiums()
        val avgPremium = kimchiPremiumService.getAveragePremium()

        return mapOf(
            "exchangeRate" to kimchiPremiumService.getExchangeRate(),
            "averagePremium" to avgPremium,
            "premiums" to cached.values.map { it.toResponse() },
            "cachedAt" to System.currentTimeMillis()
        )
    }

    /**
     * 환율 캐시 강제 갱신
     */
    @PostMapping("/exchange-rate/refresh")
    fun refreshExchangeRate(): Map<String, Any> {
        val oldRate = kimchiPremiumService.getExchangeRate()
        val newRate = kimchiPremiumService.refreshExchangeRate()

        return mapOf(
            "success" to true,
            "oldRate" to oldRate,
            "newRate" to newRate,
            "message" to "환율 캐시가 갱신되었습니다"
        )
    }

    /**
     * 현재 환율 조회
     */
    @GetMapping("/exchange-rate")
    fun getExchangeRate(): Map<String, Any> {
        val cacheStatus = kimchiPremiumService.getExchangeRateCacheStatus()

        return mapOf(
            "exchangeRate" to kimchiPremiumService.getExchangeRate(),
            "currency" to "USD/KRW",
            "cacheValid" to cacheStatus.cacheValid,
            "lastFetchTime" to (cacheStatus.lastFetchTime?.toString() ?: "N/A"),
            "remainingMinutes" to cacheStatus.remainingMinutes
        )
    }

    /**
     * 프리미엄 요약 (대시보드용)
     */
    @GetMapping("/summary")
    fun getSummary(): Map<String, Any> {
        val cached = kimchiPremiumService.getCachedPremiums()
        val avgPremium = kimchiPremiumService.getAveragePremium()

        val highPremium = cached.values.filter { it.premiumPercent >= BigDecimal("3.0") }
        val lowPremium = cached.values.filter { it.premiumPercent <= BigDecimal("-1.0") }

        return mapOf(
            "exchangeRate" to kimchiPremiumService.getExchangeRate(),
            "averagePremium" to avgPremium,
            "totalCoins" to cached.size,
            "highPremiumCoins" to highPremium.map { it.symbol },
            "lowPremiumCoins" to lowPremium.map { it.symbol },
            "marketStatus" to when {
                avgPremium >= BigDecimal("5.0") -> "EXTREME_OVERHEAT"
                avgPremium >= BigDecimal("3.0") -> "OVERHEAT"
                avgPremium >= BigDecimal("1.0") -> "NORMAL"
                avgPremium >= BigDecimal("-1.0") -> "STABLE"
                avgPremium >= BigDecimal("-3.0") -> "DISCOUNT"
                else -> "EXTREME_DISCOUNT"
            }
        )
    }

    private fun KimchiPremiumInfo.toResponse(): Map<String, Any> {
        return mapOf(
            "symbol" to symbol,
            "domesticPrice" to domesticPrice,
            "foreignPrice" to foreignPrice,
            "foreignPriceKrw" to foreignPriceKrw,
            "premiumAmount" to premiumAmount,
            "premiumPercent" to premiumPercent,
            "status" to status.name,
            "statusDescription" to status.description,
            "timestamp" to timestamp
        )
    }
}
