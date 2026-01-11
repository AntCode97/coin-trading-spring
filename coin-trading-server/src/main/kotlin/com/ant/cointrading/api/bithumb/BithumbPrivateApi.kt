package com.ant.cointrading.api.bithumb

import com.ant.cointrading.config.BithumbProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

/**
 * Bithumb Private API 클라이언트
 * 인증이 필요한 비공개 API
 */
@Component
class BithumbPrivateApi(
    private val bithumbWebClient: WebClient,
    private val properties: BithumbProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

    /**
     * 전체 계좌 잔고 조회
     */
    fun getBalances(): List<Balance>? {
        val token = createToken(null)

        return try {
            bithumbWebClient.get()
                .uri("/v1/accounts")
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<Balance>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get balances: {}", e.message)
            null
        }
    }

    /**
     * 특정 화폐 잔고 조회
     */
    fun getBalance(currency: String): BigDecimal {
        val balances = getBalances() ?: return BigDecimal.ZERO
        return balances.find { it.currency == currency }?.balance ?: BigDecimal.ZERO
    }

    /**
     * 주문 가능 정보 조회
     */
    fun getOrderChance(market: String): Map<String, Any>? {
        val query = "market=$market"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbWebClient.get()
                .uri { it.path("/v1/orders/chance").queryParam("market", market).build() }
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<Map<String, Any>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get order chance: {}", e.message)
            null
        }
    }

    /**
     * 지정가 매수 주문
     */
    fun buyLimitOrder(market: String, price: BigDecimal, volume: BigDecimal): OrderResponse? {
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
     */
    fun sellLimitOrder(market: String, price: BigDecimal, volume: BigDecimal): OrderResponse? {
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
     */
    fun buyMarketOrder(market: String, krwAmount: BigDecimal): OrderResponse? {
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
     */
    fun sellMarketOrder(market: String, volume: BigDecimal): OrderResponse? {
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
     */
    private fun createOrder(body: Map<String, String>): OrderResponse? {
        val query = body.entries.joinToString("&") { "${it.key}=${it.value}" }
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbWebClient.post()
                .uri("/v1/orders")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OrderResponse::class.java)
                .block()
        } catch (e: Exception) {
            log.error("Failed to create order: {}", e.message)
            null
        }
    }

    /**
     * 개별 주문 조회
     */
    fun getOrder(uuid: String): OrderResponse? {
        val query = "uuid=$uuid"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbWebClient.get()
                .uri { it.path("/v1/order").queryParam("uuid", uuid).build() }
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(OrderResponse::class.java)
                .block()
        } catch (e: Exception) {
            log.error("Failed to get order: {}", e.message)
            null
        }
    }

    /**
     * 주문 리스트 조회
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
            bithumbWebClient.get()
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
                .bodyToMono(object : ParameterizedTypeReference<List<OrderResponse>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Failed to get orders: {}", e.message)
            null
        }
    }

    /**
     * 주문 취소
     */
    fun cancelOrder(uuid: String): OrderResponse? {
        val query = "uuid=$uuid"
        val queryHash = createQueryHash(query)
        val token = createToken(queryHash)

        return try {
            bithumbWebClient.delete()
                .uri { it.path("/v1/order").queryParam("uuid", uuid).build() }
                .header("Authorization", token)
                .retrieve()
                .bodyToMono(OrderResponse::class.java)
                .block()
        } catch (e: Exception) {
            log.error("Failed to cancel order: {}", e.message)
            null
        }
    }
}
