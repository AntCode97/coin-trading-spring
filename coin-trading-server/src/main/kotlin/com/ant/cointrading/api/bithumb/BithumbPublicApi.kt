package com.ant.cointrading.api.bithumb

import com.ant.cointrading.event.TradingErrorEvent
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.toEntity
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Bithumb Public API 클라이언트
 * 인증 불필요한 공개 API
 *
 * RestClient 사용 (Virtual Thread 기반)
 *
 * 에러 발생 시 TradingErrorEvent 발행하여 Slack 알림
 * 타임아웃/연결 에러 시 자동 재시도 (최대 3회)
 */
@Component
class BithumbPublicApi(
    private val bithumbRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile
    private var cachedMarketAll: List<MarketInfo>? = null
    @Volatile
    private var cachedMarketAllAt: Instant? = null

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
        private val MARKET_ALL_CACHE_TTL: Duration = Duration.ofMinutes(5)
    }

    /**
     * OHLCV(캔들) 데이터 조회
     *
     * Bithumb API 특성: 비상장 코인 요청 시 HTTP 200 + {"status":"5500",...} 반환
     * ObjectMapper로 직접 파싱하여 status 필드 체크
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
            val response = bithumbRestClient.get()
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
                .toEntity<String>()

            val responseBody = response.body
            if (responseBody == null) {
                log.warn("Empty response for OHLCV $market")
                return null
            }

            // JSON 파싱
            val jsonNode: JsonNode = objectMapper.readTree(responseBody)

            // /v1/candles API는 배열 형태로 직접 반환
            if (jsonNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<CandleResponse>>() {}
                )
            }

            // 에러 응답 체크: {"error":{"name":404,"message":"Code not found"}}
            val errorNode = jsonNode.get("error")
            if (errorNode != null) {
                val errorCode = errorNode.get("name")?.asText()
                val errorMessage = errorNode.get("message")?.asText() ?: "Unknown error"
                log.warn("Bithumb API error [$errorCode] for OHLCV $market: $errorMessage")
                return null
            }

            // 구버전 API 형식: {"status":"0000","data":[...]}
            val status = jsonNode.get("status")?.asText()
            if (status != null && status != "0000") {
                val message = jsonNode.get("message")?.asText() ?: "Unknown error"
                if (status == "5500") {
                    log.debug("비상장 코인 OHLCV $market: $message")
                } else {
                    log.warn("Bithumb API error [$status] for OHLCV $market: $message")
                }
                return null
            }

            val dataNode = jsonNode.get("data")
            if (dataNode != null && dataNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(dataNode.toString().toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<CandleResponse>>() {}
                )
            }

            val responsePreview = responseBody.take(200)
            log.warn("Invalid response format for OHLCV $market - Response: $responsePreview")
            return null
        } catch (e: Exception) {
            log.error("Failed to get OHLCV for $market: ${e.message}", e)
            eventPublisher.publishEvent(TradingErrorEvent(
                eventSource = this,
                component = "BithumbPublicApi",
                operation = "getOhlcv",
                market = market,
                errorMessage = e.message ?: "Unknown error",
                exception = e
            ))
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
            val response = bithumbRestClient.get()
                .uri { it.path("/v1/ticker").queryParam("markets", markets).build() }
                .retrieve()
                .toEntity<String>()

            val responseBody = response.body
            if (responseBody == null) {
                log.warn("Empty response for market $markets")
                return null
            }

            // JSON 파싱
            val jsonNode: JsonNode = objectMapper.readTree(responseBody)

            // /v1/ticker?markets= API는 배열 형태로 직접 반환
            if (jsonNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<TickerInfo>>() {}
                )
            }

            // 구버전 API 형식: {"status":"0000","data":[...]}
            val status = jsonNode.get("status")?.asText()
            if (status != null && status != "0000") {
                val message = jsonNode.get("message")?.asText() ?: "Unknown error"
                log.warn("Bithumb API error [$status] for market $markets: $message")
                return null
            }

            val dataNode = jsonNode.get("data")
            if (dataNode != null && dataNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(dataNode.toString().toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<TickerInfo>>() {}
                )
            }

            val responsePreview = responseBody.take(200)
            log.warn("Invalid response format for market $markets - Response: $responsePreview")
            return null
        } catch (e: Exception) {
            log.error("Failed to get current price for $markets: ${e.message}", e)
            eventPublisher.publishEvent(TradingErrorEvent(
                eventSource = this,
                component = "BithumbPublicApi",
                operation = "getCurrentPrice",
                market = markets,
                errorMessage = e.message ?: "Unknown error",
                exception = e
            ))
            null
        }
    }

    /**
     * 호가 정보 조회
     *
     * Bithumb API 특성: 비상장 코인 요청 시 HTTP 200 + {"status":"5500",...} 반환
     * ObjectMapper로 직접 파싱하여 status 필드 체크
     */
    fun getOrderbook(markets: String): List<OrderbookInfo>? {
        return try {
            val response = bithumbRestClient.get()
                .uri { it.path("/v1/orderbook").queryParam("markets", markets).build() }
                .retrieve()
                .toEntity<String>()

            val responseBody = response.body
            if (responseBody == null) {
                log.warn("Empty response for orderbook $markets")
                return null
            }

            // JSON 파싱
            val jsonNode: JsonNode = objectMapper.readTree(responseBody)

            // /v1/orderbook?markets= API는 배열 형태로 직접 반환
            if (jsonNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<OrderbookInfo>>() {}
                )
            }

            // 구버전 API 형식: {"status":"0000","data":[...]}
            val status = jsonNode.get("status")?.asText()
            if (status != null && status != "0000") {
                val message = jsonNode.get("message")?.asText() ?: "Unknown error"
                log.warn("Bithumb API error [$status] for orderbook $markets: $message")
                return null
            }

            val dataNode = jsonNode.get("data")
            if (dataNode != null && dataNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(dataNode.toString().toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<OrderbookInfo>>() {}
                )
            }

            val responsePreview = responseBody.take(200)
            log.warn("Invalid response format for orderbook $markets - Response: $responsePreview")
            return null
        } catch (e: Exception) {
            log.error("Failed to get orderbook for $markets: ${e.message}", e)
            eventPublisher.publishEvent(TradingErrorEvent(
                eventSource = this,
                component = "BithumbPublicApi",
                operation = "getOrderbook",
                market = markets,
                errorMessage = e.message ?: "Unknown error",
                exception = e
            ))
            null
        }
    }

    /**
     * 거래 가능 마켓 목록 조회
     * TTL 기반 캐시 적용 (5분) - 마켓 목록은 거의 변하지 않으므로 빈번한 API 호출 불필요
     * 캐시 만료 시 API 갱신 시도, 실패하면 stale 캐시 반환
     */
    fun getMarketAll(): List<MarketInfo>? {
        // 캐시가 유효하면 API 호출 없이 반환
        val cached = cachedMarketAll
        val cachedAt = cachedMarketAllAt
        if (!cached.isNullOrEmpty() && cachedAt != null
            && Duration.between(cachedAt, Instant.now()) < MARKET_ALL_CACHE_TTL
        ) {
            return cached
        }

        // 캐시 만료 또는 없음 - API 갱신 시도
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val markets = bithumbRestClient.get()
                    .uri("/v1/market/all")
                    .retrieve()
                    .body(object : ParameterizedTypeReference<List<MarketInfo>>() {})

                if (markets != null) {
                    cachedMarketAll = markets
                    cachedMarketAllAt = Instant.now()
                }
                return markets
            } catch (e: ResourceAccessException) {
                lastException = e
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn("getMarketAll 실패 (연결 에러), 재시도 $attempt/$MAX_RETRY_ATTEMPTS: ${e.message}")
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                } else {
                    log.error("getMarketAll 실패: 최대 재시도 횟수 초과")
                }
            } catch (e: Exception) {
                lastException = e
                log.error("getMarketAll 실패 (시도 $attempt/$MAX_RETRY_ATTEMPTS): ${e.message}")
                break
            }
        }

        // 갱신 실패 - stale 캐시가 있으면 그대로 사용 (에러 이벤트 발행 안 함)
        if (!cached.isNullOrEmpty()) {
            val ageMinutes = if (cachedAt != null) Duration.between(cachedAt, Instant.now()).toMinutes() else -1
            log.warn("getMarketAll 갱신 실패 - stale 캐시 사용 (age={}m, size={})", ageMinutes, cached.size)
            return cached
        }

        // 캐시도 없고 API도 실패 - 이때만 에러 이벤트 발행
        eventPublisher.publishEvent(TradingErrorEvent(
            eventSource = this,
            component = "BithumbPublicApi",
            operation = "getMarketAll",
            market = null,
            errorMessage = lastException?.message ?: "Max retry exceeded",
            exception = lastException
        ))
        return null
    }

    /**
     * 최근 체결 내역 조회
     *
     * Bithumb API 특성: 비상장 코인 요청 시 HTTP 200 + {"status":"5500",...} 반환
     * ObjectMapper로 직접 파싱하여 status 필드 체크
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param count 조회 개수 (1-500)
     */
    fun getTradesTicks(market: String, count: Int): List<TradeResponse>? {
        return try {
            val response = bithumbRestClient.get()
                .uri { it.path("/v1/trades/ticks")
                    .queryParam("market", market)
                    .queryParam("count", count.coerceAtMost(500))
                    .build()
                }
                .retrieve()
                .toEntity<String>()

            val responseBody = response.body
            if (responseBody == null) {
                log.warn("Empty response for trades ticks $market")
                return null
            }

            // JSON 파싱
            val jsonNode: JsonNode = objectMapper.readTree(responseBody)

            // /v1/trades/ticks API는 배열 형태로 직접 반환
            if (jsonNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<TradeResponse>>() {}
                )
            }

            // 구버전 API 형식: {"status":"0000","data":[...]}
            val status = jsonNode.get("status")?.asText()
            if (status != null && status != "0000") {
                val message = jsonNode.get("message")?.asText() ?: "Unknown error"
                log.warn("Bithumb API error [$status] for trades ticks $market: $message")
                return null
            }

            val dataNode = jsonNode.get("data")
            if (dataNode != null && dataNode.isArray) {
                return objectMapper.readValue(
                    ByteArrayInputStream(dataNode.toString().toByteArray(StandardCharsets.UTF_8)),
                    object : TypeReference<List<TradeResponse>>() {}
                )
            }

            val responsePreview = responseBody.take(200)
            log.warn("Invalid response format for trades ticks $market - Response: $responsePreview")
            return null
        } catch (e: Exception) {
            log.error("Failed to get trades ticks for $market: ${e.message}", e)
            eventPublisher.publishEvent(TradingErrorEvent(
                eventSource = this,
                component = "BithumbPublicApi",
                operation = "getTradesTicks",
                market = market,
                errorMessage = e.message ?: "Unknown error",
                exception = e
            ))
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
            bithumbRestClient.get()
                .uri("/v1/market/virtual_asset_warning")
                .retrieve()
                .body(object : ParameterizedTypeReference<List<VirtualAssetWarning>>() {})
        } catch (e: Exception) {
            log.error("Failed to get virtual asset warning: {}", e.message, e)
            eventPublisher.publishEvent(TradingErrorEvent(
                eventSource = this,
                component = "BithumbPublicApi",
                operation = "getVirtualAssetWarning",
                market = null,
                errorMessage = e.message ?: "Unknown error",
                exception = e
            ))
            null
        }
    }
}
