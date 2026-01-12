package com.ant.cointrading.api.bithumb

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * Bithumb Public API 클라이언트
 * 인증 불필요한 공개 API
 */
@Component
class BithumbPublicApi(
    private val bithumbWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * OHLCV(캔들) 데이터 조회
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param interval 캔들 단위 (minute1, minute3, minute5, minute10, minute15, minute30, minute60, minute240, day, week, month)
     * @param count 조회 개수 (최대 200)
     * @param to 마지막 캔들 시간 (yyyy-MM-dd HH:mm:ss)
     */
    fun getOhlcv(
        market: String,
        interval: String,
        count: Int,
        to: String? = null
    ): List<CandleResponse>? {
        val endpoint = when {
            interval == "day" -> "/v1/candles/days"
            interval == "week" -> "/v1/candles/weeks"
            interval == "month" -> "/v1/candles/months"
            interval.startsWith("minute") -> {
                val unit = interval.removePrefix("minute").ifEmpty { "1" }
                "/v1/candles/minutes/$unit"
            }
            else -> "/v1/candles/days"
        }

        return try {
            bithumbWebClient.get()
                .uri { builder ->
                    val b = builder.path(endpoint)
                        .queryParam("market", market)
                        .queryParam("count", count.coerceAtMost(200))
                    if (!to.isNullOrBlank()) {
                        b.queryParam("to", to)
                    }
                    b.build()
                }
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<CandleResponse>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get OHLCV: {}", e.message)
            null
        }
    }

    /**
     * 현재가 정보 조회
     */
    fun getCurrentPrice(markets: String): List<TickerInfo>? {
        return try {
            bithumbWebClient.get()
                .uri { it.path("/v1/ticker").queryParam("markets", markets).build() }
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<TickerInfo>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get current price: {}", e.message)
            null
        }
    }

    /**
     * 호가 정보 조회
     */
    fun getOrderbook(markets: String): List<OrderbookInfo>? {
        return try {
            bithumbWebClient.get()
                .uri { it.path("/v1/orderbook").queryParam("markets", markets).build() }
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<OrderbookInfo>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get orderbook: {}", e.message)
            null
        }
    }

    /**
     * 거래 가능 마켓 목록 조회
     */
    fun getMarketAll(): List<MarketInfo>? {
        return try {
            bithumbWebClient.get()
                .uri("/v1/market/all")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<MarketInfo>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get market list: {}", e.message)
            null
        }
    }

    /**
     * 최근 체결 내역 조회
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param count 조회 개수 (1-500)
     */
    fun getTradesTicks(market: String, count: Int): List<TradeResponse>? {
        return try {
            bithumbWebClient.get()
                .uri { it.path("/v1/trades/ticks")
                    .queryParam("market", market)
                    .queryParam("count", count.coerceAtMost(500))
                    .build()
                }
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<TradeResponse>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get trades ticks: {}", e.message)
            null
        }
    }
}
