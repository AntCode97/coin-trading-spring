package com.ant.cointrading.api.binance

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.util.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * 바이낸스 Futures Public API
 *
 * Funding Rate Arbitrage 전략을 위한 선물 시장 데이터 조회.
 * Phase 1: 모니터링 전용 (Public Endpoint, API Key 불필요)
 * 재시도 로직: Exponential Backoff (초기 1초, 최대 10초, 3회 재시도)
 */
@Component
class BinanceFuturesApi {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl("https://fapi.binance.com")
        .build()

    companion object {
        // 재시도 설정
        private const val MAX_RETRY_ATTEMPTS = 3L
        private val INITIAL_BACKOFF = Duration.ofSeconds(1)
        private val MAX_BACKOFF = Duration.ofSeconds(10)
    }

    /**
     * 현재 펀딩 비율 및 다음 펀딩 시간 조회
     *
     * @param symbol 심볼 (예: BTCUSDT, ETHUSDT)
     * @return 프리미엄 인덱스 정보 (펀딩 비율 포함)
     */
    fun getPremiumIndex(symbol: String): BinancePremiumIndex? {
        return try {
            webClient.get()
                .uri("/fapi/v1/premiumIndex?symbol=$symbol")
                .retrieve()
                .bodyToMono(BinancePremiumIndex::class.java)
                .retryWhen(
                    Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter { it is java.io.IOException || it is java.net.SocketTimeoutException }
                        .doBeforeRetry { signal ->
                            log.warn("Binance API 재시도 [$symbol] (시도 ${signal.totalRetries() + 1}/$MAX_RETRY_ATTEMPTS)")
                        }
                )
                .block()
        } catch (e: Exception) {
            log.error("Binance Futures 프리미엄 인덱스 조회 실패 [$symbol]: ${e.message}")
            null
        }
    }

    /**
     * 모든 심볼의 프리미엄 인덱스 조회
     */
    fun getAllPremiumIndex(): List<BinancePremiumIndex>? {
        return try {
            webClient.get()
                .uri("/fapi/v1/premiumIndex")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<BinancePremiumIndex>>() {})
                .retryWhen(
                    Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter { it is java.io.IOException || it is java.net.SocketTimeoutException }
                        .doBeforeRetry { signal ->
                            log.warn("Binance API 전체 프리미엄 인덱스 재시도 (시도 ${signal.totalRetries() + 1}/$MAX_RETRY_ATTEMPTS)")
                        }
                )
                .block()
        } catch (e: Exception) {
            log.error("Binance Futures 전체 프리미엄 인덱스 조회 실패: ${e.message}")
            null
        }
    }

    /**
     * 펀딩 비율 히스토리 조회
     *
     * @param symbol 심볼
     * @param limit 조회할 개수 (최대 1000)
     */
    fun getFundingRateHistory(symbol: String, limit: Int = 100): List<BinanceFundingRate>? {
        return try {
            webClient.get()
                .uri("/fapi/v1/fundingRate?symbol=$symbol&limit=$limit")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<BinanceFundingRate>>() {})
                .retryWhen(
                    Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter { it is java.io.IOException || it is java.net.SocketTimeoutException }
                        .doBeforeRetry { signal ->
                            log.warn("Binance API 펀딩 비율 히스토리 재시도 [$symbol] (시도 ${signal.totalRetries() + 1}/$MAX_RETRY_ATTEMPTS)")
                        }
                )
                .block()
        } catch (e: Exception) {
            log.error("Binance Futures 펀딩 비율 히스토리 조회 실패 [$symbol]: ${e.message}")
            null
        }
    }

    /**
     * 선물 마크 가격 조회
     *
     * @param symbol 심볼
     */
    fun getMarkPrice(symbol: String): BinanceMarkPrice? {
        return try {
            webClient.get()
                .uri("/fapi/v1/premiumIndex?symbol=$symbol")
                .retrieve()
                .bodyToMono(BinanceMarkPrice::class.java)
                .retryWhen(
                    Retry.backoff(MAX_RETRY_ATTEMPTS, INITIAL_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .filter { it is java.io.IOException || it is java.net.SocketTimeoutException }
                        .doBeforeRetry { signal ->
                            log.warn("Binance API 마크 가격 재시도 [$symbol] (시도 ${signal.totalRetries() + 1}/$MAX_RETRY_ATTEMPTS)")
                        }
                )
                .block()
        } catch (e: Exception) {
            log.error("Binance Futures 마크 가격 조회 실패 [$symbol]: ${e.message}")
            null
        }
    }

    /**
     * 주요 코인의 펀딩 기회 스캔
     *
     * @param minAnnualizedRate 최소 연환산 수익률 (예: 15.0 = 15% APY)
     * @return 조건을 충족하는 펀딩 기회 목록
     */
    fun scanFundingOpportunities(minAnnualizedRate: Double = 15.0): List<FundingOpportunity> {
        val mainSymbols = listOf(
            "BTCUSDT", "ETHUSDT", "XRPUSDT", "SOLUSDT",
            "ADAUSDT", "DOGEUSDT", "AVAXUSDT", "LINKUSDT"
        )

        return mainSymbols.mapNotNull { symbol ->
            try {
                val premiumIndex = getPremiumIndex(symbol) ?: return@mapNotNull null

                val fundingRate = premiumIndex.lastFundingRate.toDouble()
                // 펀딩 비율 × 3회/일 × 365일 × 100 = 연환산 수익률(%)
                val annualizedRate = fundingRate * 3 * 365 * 100

                if (annualizedRate >= minAnnualizedRate) {
                    FundingOpportunity(
                        exchange = "BINANCE",
                        symbol = symbol,
                        fundingRate = fundingRate,
                        annualizedRate = annualizedRate,
                        nextFundingTime = Instant.ofEpochMilli(premiumIndex.nextFundingTime),
                        markPrice = premiumIndex.markPrice.toDouble(),
                        indexPrice = premiumIndex.indexPrice.toDouble()
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                log.warn("펀딩 기회 스캔 중 오류 [$symbol]: ${e.message}")
                null
            }
        }.sortedByDescending { it.annualizedRate }
    }

    /**
     * 단일 심볼의 펀딩 정보 상세 조회
     */
    fun getFundingInfo(symbol: String): FundingInfo? {
        val premiumIndex = getPremiumIndex(symbol) ?: return null
        val history = getFundingRateHistory(symbol, 10) ?: emptyList()

        val fundingRate = premiumIndex.lastFundingRate.toDouble()
        val annualizedRate = fundingRate * 3 * 365 * 100

        // 최근 10회 펀딩 비율 평균
        val avgFundingRate = if (history.isNotEmpty()) {
            history.map { it.fundingRate.toDouble() }.average()
        } else {
            fundingRate
        }
        val avgAnnualizedRate = avgFundingRate * 3 * 365 * 100

        return FundingInfo(
            symbol = symbol,
            currentFundingRate = fundingRate,
            currentAnnualizedRate = annualizedRate,
            avgFundingRate = avgFundingRate,
            avgAnnualizedRate = avgAnnualizedRate,
            nextFundingTime = Instant.ofEpochMilli(premiumIndex.nextFundingTime),
            markPrice = premiumIndex.markPrice.toDouble(),
            indexPrice = premiumIndex.indexPrice.toDouble(),
            recentHistory = history.take(5).map {
                FundingHistoryItem(
                    fundingRate = it.fundingRate.toDouble(),
                    fundingTime = Instant.ofEpochMilli(it.fundingTime)
                )
            }
        )
    }
}
