package com.ant.cointrading.api.bithumb

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Bithumb Public API 클라이언트
 * 인증 불필요한 공개 API
 */
@Component
class BithumbPublicApi(
    private val bithumbWebClient: WebClient,
    private val objectMapper: ObjectMapper
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
     *
     * Bithumb API 특성: 비상장 코인 요청 시 HTTP 200 + {"status":"5500",...} 반환
     * ObjectMapper로 직접 파싱하여 status 필드 체크
     */
    fun getCurrentPrice(markets: String): List<TickerInfo>? {
        return try {
            val responseBody = bithumbWebClient.get()
                .uri { it.path("/v1/ticker").queryParam("markets", markets).build() }
                .retrieve()
                .bodyToMono(String::class.java)
                .block()

            if (responseBody == null) {
                log.warn("Empty response for market $markets")
                return null
            }

            // JSON 파싱 및 status 체크
            val jsonNode: JsonNode = objectMapper.readTree(responseBody)

            // Bithumb API 응답 형식: {"status":"0000","data":[...]} 또는 {"status":"5500","message":"..."}
            val status = jsonNode.get("status")?.asText()
            if (status != null && status != "0000") {
                val message = jsonNode.get("message")?.asText() ?: "Unknown error"
                log.warn("Bithumb API error [$status] for market $markets: $message")
                return null
            }

            // 정상 응답: data 필드의 리스트 반환
            val dataNode = jsonNode.get("data")
            if (dataNode == null || !dataNode.isArray) {
                log.warn("Invalid response format for market $markets")
                return null
            }

            // List<TickerInfo>로 변환
            objectMapper.readValue(
                ByteArrayInputStream(dataNode.toString().toByteArray(StandardCharsets.UTF_8)),
                object : TypeReference<List<TickerInfo>>() {}
            )

        } catch (e: Exception) {
            log.error("Failed to get current price for $markets: ${e.message}")
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

    /**
     * 가상자산 경보 정보 조회 (경보제 API)
     *
     * 경보 유형:
     * - TRADING_VOLUME_SUDDEN_FLUCTUATION: 거래량 급등
     * - PRICE_SUDDEN_FLUCTUATION: 가격 급변
     * - GLOBAL_PRICE_DIFFERENCE: 해외 가격 괴리
     * - CONCENTRATION_OF_SMALL_ACCOUNTS: 소액 계좌 집중
     */
    fun getVirtualAssetWarning(): List<VirtualAssetWarning>? {
        return try {
            bithumbWebClient.get()
                .uri("/v1/market/virtual_asset_warning")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<VirtualAssetWarning>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get virtual asset warning: {}", e.message)
            null
        }
    }
}
