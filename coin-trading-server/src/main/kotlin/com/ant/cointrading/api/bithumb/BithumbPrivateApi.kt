package com.ant.cointrading.api.bithumb

import com.ant.cointrading.config.BithumbProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

/**
 * Bithumb Private API 클라이언트
 *
 * 인증이 필요한 비공개 API (주문, 잔고, 입출금)
 * JWT + SHA512 해시 인증 사용
 *
 * RestClient 사용 (Virtual Thread 기반)
 */
@Component
class BithumbPrivateApi(
    private val bithumbRestClient: RestClient,
    private val properties: BithumbProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ==================== 잔고 조회 ====================

    /**
     * 전체 계좌 잔고 조회
     */
    fun getBalances(): List<Balance>? {
        val token = createToken(null)

        return try {
            bithumbRestClient.get()
                .uri("/v1/accounts")
                .header("Authorization", token)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<Balance>>() {})
        } catch (e: Exception) {
            log.error("잔고 조회 실패: {}", extractErrorMessage(e))
            null
        }
    }

    /**
     * 특정 화폐 잔고 조회
     *
     * @param currency 화폐 코드 (예: KRW, BTC)
     * @return 가용 잔고 (없으면 0)
     */
    fun getBalance(currency: String): BigDecimal {
        val balances = getBalances() ?: return BigDecimal.ZERO
        return balances.find { it.currency == currency }?.balance ?: BigDecimal.ZERO
    }

    // ==================== 주문 가능 정보 ====================

    /**
     * 주문 가능 정보 조회
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @return 주문 가능 정보 (최소 주문 금액, 수수료 등)
     */
    fun getOrderChance(market: String): Map<String, Any>? {
        val query = "market=$market"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbRestClient.get()
                .uri { it.path("/v1/orders/chance").queryParam("market", market).build() }
                .header("Authorization", token)
                .retrieve()
                .body(object : ParameterizedTypeReference<Map<String, Any>>() {})
        } catch (e: Exception) {
            log.error("주문 가능 정보 조회 실패 [{}]: {}", market, extractErrorMessage(e))
            null
        }
    }

    // ==================== 주문 생성 ====================

    /**
     * 지정가 매수 주문
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param price 주문 가격 (KRW)
     * @param volume 주문 수량
     * @throws BithumbApiException 주문 실패 시 (잔고 부족, 최소 주문 금액 미달 등)
     */
    fun buyLimitOrder(market: String, price: BigDecimal, volume: BigDecimal): OrderResponse {
        log.info("지정가 매수 주문 [{}] 가격={} 수량={}", market, price.toPlainString(), volume.toPlainString())
        val body = mapOf(
            "market" to market,
            "side" to "bid",
            "ord_type" to "limit",
            "price" to price.toPlainString(),
            "volume" to volume.toPlainString()
        )
        return createOrder(body)
    }

    /**
     * 지정가 매도 주문
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param price 주문 가격 (KRW)
     * @param volume 주문 수량
     * @throws BithumbApiException 주문 실패 시 (잔고 부족, 최소 주문 금액 미달 등)
     */
    fun sellLimitOrder(market: String, price: BigDecimal, volume: BigDecimal): OrderResponse {
        log.info("지정가 매도 주문 [{}] 가격={} 수량={}", market, price.toPlainString(), volume.toPlainString())
        val body = mapOf(
            "market" to market,
            "side" to "ask",
            "ord_type" to "limit",
            "price" to price.toPlainString(),
            "volume" to volume.toPlainString()
        )
        return createOrder(body)
    }

    /**
     * 시장가 매수 주문
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param krwAmount 주문 금액 (KRW)
     * @throws BithumbApiException 주문 실패 시 (잔고 부족, 최소 주문 금액 미달 등)
     */
    fun buyMarketOrder(market: String, krwAmount: BigDecimal): OrderResponse {
        log.info("시장가 매수 주문 [{}] 금액={}", market, krwAmount.toPlainString())
        val body = mapOf(
            "market" to market,
            "side" to "bid",
            "ord_type" to "price",
            "price" to krwAmount.toPlainString()
        )
        return createOrder(body)
    }

    /**
     * 시장가 매도 주문
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param volume 주문 수량
     * @throws BithumbApiException 주문 실패 시 (잔고 부족 등)
     */
    fun sellMarketOrder(market: String, volume: BigDecimal): OrderResponse {
        log.info("시장가 매도 주문 [{}] 수량={}", market, volume.toPlainString())
        val body = mapOf(
            "market" to market,
            "side" to "ask",
            "ord_type" to "market",
            "volume" to volume.toPlainString()
        )
        return createOrder(body)
    }

    /**
     * 주문 생성 (내부 메서드)
     *
     * @throws BithumbApiException API 에러 발생 시
     */
    private fun createOrder(body: Map<String, String>): OrderResponse {
        val query = body.entries.joinToString("&") { "${it.key}=${it.value}" }
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            val response = bithumbRestClient.post()
                .uri("/v1/orders")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity<OrderResponse>()

            response.body ?: throw BithumbApiException("empty_response", "주문 응답이 비어있음")
        } catch (e: BithumbApiException) {
            throw e
        } catch (e: Exception) {
            val errorResponse = try {
                // RestClientException에서 body 추출 시도
                e.message?.let { parseError(it) }
            } catch (ex: Exception) {
                null
            }

            log.error("주문 실패 [{}]: {}", body["market"], errorResponse?.error?.message ?: e.message)
            throw BithumbApiException(
                errorResponse?.error?.name,
                errorResponse?.error?.message,
                e
            )
        }
    }

    // ==================== 주문 조회 ====================

    /**
     * 개별 주문 조회
     *
     * @param uuid 주문 UUID
     */
    fun getOrder(uuid: String): OrderResponse? {
        val query = "uuid=$uuid"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbRestClient.get()
                .uri { it.path("/v1/order").queryParam("uuid", uuid).build() }
                .header("Authorization", token)
                .retrieve()
                .body(OrderResponse::class.java)
        } catch (e: Exception) {
            log.error("주문 조회 실패 [{}]: {}", uuid, extractErrorMessage(e))
            null
        }
    }

    /**
     * 주문 리스트 조회
     *
     * @param market 마켓 코드 (선택)
     * @param state 주문 상태 (wait, watch, done, cancel)
     * @param page 페이지 번호
     * @param limit 조회 개수
     */
    fun getOrders(market: String?, state: String?, page: Int, limit: Int): List<OrderResponse>? {
        val queryParts = mutableListOf<String>()
        market?.let { queryParts.add("market=$it") }
        state?.let { queryParts.add("state=$it") }
        queryParts.add("page=$page")
        queryParts.add("limit=$limit")
        queryParts.add("order_by=desc")

        val query = queryParts.joinToString("&")
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbRestClient.get()
                .uri { builder ->
                    val b = builder.path("/v1/orders")
                        .queryParam("page", page)
                        .queryParam("limit", limit)
                        .queryParam("order_by", "desc")
                    market?.let { b.queryParam("market", it) }
                    state?.let { b.queryParam("state", it) }
                    b.build()
                }
                .header("Authorization", token)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<OrderResponse>>() {})
        } catch (e: Exception) {
            log.error("주문 목록 조회 실패: {}", extractErrorMessage(e))
            null
        }
    }

    // ==================== 주문 취소 ====================

    /**
     * 주문 취소
     *
     * @param uuid 주문 UUID
     * @throws BithumbApiException 취소 실패 시 (이미 체결됨, 주문 없음 등)
     */
    fun cancelOrder(uuid: String): OrderResponse {
        log.info("주문 취소 [{}]", uuid)
        val query = "uuid=$uuid"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            val response = bithumbRestClient.delete()
                .uri { it.path("/v1/order").queryParam("uuid", uuid).build() }
                .header("Authorization", token)
                .retrieve()
                .toEntity<OrderResponse>()

            response.body ?: throw BithumbApiException("empty_response", "취소 응답이 비어있음")
        } catch (e: BithumbApiException) {
            throw e
        } catch (e: Exception) {
            val errorResponse = try {
                e.message?.let { parseError(it) }
            } catch (ex: Exception) {
                null
            }

            log.error("주문 취소 실패 [{}]: {}", uuid, errorResponse?.error?.message ?: e.message)
            throw BithumbApiException(
                errorResponse?.error?.name,
                errorResponse?.error?.message,
                e
            )
        }
    }

    // ==================== 인증 헬퍼 ====================

    /**
     * JWT 토큰 생성
     */
    private fun createToken(queryHash: String?): String {
        val key = Keys.hmacShaKeyFor(properties.secretKey.toByteArray(StandardCharsets.UTF_8))

        val claims = mutableMapOf<String, Any>(
            "access_key" to properties.accessKey,
            "nonce" to UUID.randomUUID().toString(),
            "timestamp" to System.currentTimeMillis()
        )

        if (queryHash != null) {
            claims["query_hash"] = queryHash
            claims["query_hash_alg"] = "SHA512"
        }

        return "Bearer " + Jwts.builder()
            .claims(claims)
            .signWith(key)
            .compact()
    }

    /**
     * 쿼리 문자열의 SHA512 해시 생성
     */
    private fun createQueryHash(query: String): String {
        val digest = MessageDigest.getInstance("SHA-512")
        val hash = digest.digest(query.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ==================== 에러 처리 헬퍼 ====================

    /**
     * API 에러 응답 파싱
     */
    private fun parseError(body: String): BithumbError? {
        return try {
            objectMapper.readValue(body, BithumbError::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 예외에서 에러 메시지 추출
     */
    private fun extractErrorMessage(e: Exception): String {
        return when (e) {
            is BithumbApiException -> e.message ?: "Unknown error"
            else -> e.message ?: "Unknown error"
        }
    }
}
