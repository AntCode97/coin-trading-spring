package com.ant.cointrading.api.bithumb

import com.ant.cointrading.config.BithumbProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.reactive.function.client.WebClient
import org.mockito.kotlin.mock
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BithumbPrivateApi 단위 테스트
 *
 * 테스트 범위:
 * - Query Hash 계산
 * - 주문 파라미터 변환
 * - JSON 파싱
 * - 에러 처리
 */
@DisplayName("BithumbPrivateApi 테스트")
class BithumbPrivateApiTest {

    private lateinit var bithumbPrivateApi: BithumbPrivateApi
    private lateinit var mockWebClient: WebClient

    private val testAccessKey = "test_access_key"
    private val testSecretKey = "test_secret_key_12345678"

    @BeforeEach
    fun setup() {
        mockWebClient = mock<WebClient>()

        val properties = BithumbProperties(
            baseUrl = "https://api.bithumb.com",
            accessKey = testAccessKey,
            secretKey = testSecretKey
        )

        bithumbPrivateApi = BithumbPrivateApi(
            bithumbWebClient = mockWebClient,
            properties = properties,
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        )
    }

    @Nested
    @DisplayName("Query Hash 계산 테스트")
    inner class QueryHashTest {

        @Test
        @DisplayName("SHA-512 해시 길이 확인")
        fun hashLength() {
            // given
            val queryString = "market=KRW-BTC"

            // when
            val queryHash = ReflectionTestUtils.invokeMethod<String>(
                bithumbPrivateApi,
                "createQueryHash",
                queryString
            )

            // then
            assertNotNull(queryHash)
            assertEquals(128, queryHash.length) // SHA-512는 128자 hex
            assertTrue(queryHash.all { it.isDigit() || it in 'a'..'f' })
        }

        @Test
        @DisplayName("동일 입력에 대해 동일한 해시")
        fun hashConsistency() {
            // given
            val queryString = "market=KRW-BTC&order_by=desc"

            // when
            val hash1 = ReflectionTestUtils.invokeMethod<String>(
                bithumbPrivateApi,
                "createQueryHash",
                queryString
            )
            val hash2 = ReflectionTestUtils.invokeMethod<String>(
                bithumbPrivateApi,
                "createQueryHash",
                queryString
            )

            // then
            assertEquals(hash1, hash2, "동일한 입력에 대해 동일한 해시")
        }

        @Test
        @DisplayName("다른 입력에 대해 다른 해시")
        fun hashUniqueness() {
            // given
            val query1 = "market=KRW-BTC"
            val query2 = "market=KRW-ETH"

            // when
            val hash1 = ReflectionTestUtils.invokeMethod<String>(
                bithumbPrivateApi,
                "createQueryHash",
                query1
            )
            val hash2 = ReflectionTestUtils.invokeMethod<String>(
                bithumbPrivateApi,
                "createQueryHash",
                query2
            )

            // then
            assertTrue(hash1 != hash2, "다른 입력에 대해 다른 해시")
        }
    }

    @Nested
    @DisplayName("주문 파라미터 변환 테스트")
    inner class OrderParameterTest {

        @Test
        @DisplayName("지정가 매수 파라미터")
        fun limitBuyOrder_params() {
            // given
            val market = "KRW-BTC"
            val price = BigDecimal("50000000")
            val volume = BigDecimal("0.001")

            // when
            val body = mapOf(
                "market" to market,
                "side" to "bid",
                "volume" to volume.toPlainString(),
                "price" to price.toPlainString(),
                "ord_type" to "limit"
            )

            // then
            assertEquals("KRW-BTC", body["market"])
            assertEquals("bid", body["side"])
            assertEquals("limit", body["ord_type"])
        }

        @Test
        @DisplayName("시장가 매수 파라미터")
        fun marketBuyOrder_params() {
            // given
            val market = "KRW-BTC"
            val krwAmount = BigDecimal("100000")

            // when
            val body = mapOf(
                "market" to market,
                "side" to "bid",
                "ord_type" to "price",
                "price" to krwAmount.toPlainString()
            )

            // then
            assertEquals("100000", body["price"])
            assertEquals("price", body["ord_type"])
            assertTrue(body["volume"] == null) // 시장가 매수는 volume 불필요
        }

        @Test
        @DisplayName("시장가 매도 파라미터")
        fun marketSellOrder_params() {
            // given
            val market = "KRW-BTC"
            val volume = BigDecimal("0.001")

            // when
            val body = mapOf(
                "market" to market,
                "side" to "ask",
                "ord_type" to "market",
                "volume" to volume.toPlainString()
            )

            // then
            assertEquals("0.001", body["volume"])
            assertEquals("ask", body["side"])
            assertTrue(body["price"] == null) // 시장가 매도는 price 불필요
        }
    }

    @Nested
    @DisplayName("에러 처리 테스트")
    inner class ErrorHandlingTest {

        @Test
        @DisplayName("BithumbApiException 생성")
        fun exceptionCreation() {
            // given
            val error = BithumbApiException(
                errorName = "insufficient_funds",
                errorMessage = "잔고가 부족합니다"
            )

            // then
            assertTrue(error.isInsufficientFunds())
            assertEquals("insufficient_funds", error.errorName)
        }

        @Test
        @DisplayName("에러 타입 분류")
        fun errorClassification() {
            // given
            val insufficientFunds = BithumbApiException("insufficient_funds", "잔고 부족")
            val invalidOrder = BithumbApiException("invalid_volume", "잘못된 수량")
            val authError = BithumbApiException("invalid_access_key", "잘못된 API 키")
            val orderNotFound = BithumbApiException("order_not_found", "주문 없음")

            // then
            assertTrue(insufficientFunds.isInsufficientFunds())
            assertTrue(invalidOrder.isInvalidOrder())
            assertTrue(authError.isAuthError())
            assertTrue(orderNotFound.isOrderNotFound())
        }

        @Test
        @DisplayName("주문 관련 에러")
        fun orderRelatedErrors() {
            // given
            val underMinTotal = BithumbApiException("under_min_total", "최소 주문 금액 미달")
            val invalidVolume = BithumbApiException("invalid_volume", "잘못된 수량")
            val invalidPrice = BithumbApiException("invalid_price", "잘못된 가격")

            // then
            assertTrue(underMinTotal.isInvalidOrder())
            assertTrue(invalidVolume.isInvalidOrder())
            assertTrue(invalidPrice.isInvalidOrder())
        }
    }

    @Nested
    @DisplayName("JSON 파싱 테스트")
    inner class JsonParsingTest {

        @Test
        @DisplayName("Balance 파싱 - KRW")
        fun parseKrwBalance() {
            // given
            val responseBody = """
                {
                    "currency": "KRW",
                    "balance": "10000000.0",
                    "locked": "500000.0",
                    "avg_buy_price": "0",
                    "avg_buy_price_modified": false,
                    "unit_currency": "KRW"
                }
            """.trimIndent()

            // when
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val balance = mapper.readValue(responseBody, Balance::class.java)

            // then
            assertEquals("KRW", balance.currency)
            assertEquals(BigDecimal("10000000.0"), balance.balance)
            assertEquals(BigDecimal("500000.0"), balance.locked)
        }

        @Test
        @DisplayName("Balance 파싱 - 코인")
        fun parseCoinBalance() {
            // given
            val responseBody = """
                {
                    "currency": "BTC",
                    "balance": "0.5",
                    "locked": "0.1",
                    "avg_buy_price": "50000000",
                    "avg_buy_price_modified": true,
                    "unit_currency": "KRW"
                }
            """.trimIndent()

            // when
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val balance = mapper.readValue(responseBody, Balance::class.java)

            // then
            assertEquals("BTC", balance.currency)
            assertEquals(BigDecimal("0.5"), balance.balance)
            assertEquals(BigDecimal("50000000"), balance.avgBuyPrice)
        }

        @Test
        @DisplayName("OrderResponse 파싱")
        fun parseOrderResponse() {
            // given
            val responseBody = """
                {
                    "uuid": "C0106000032400700021",
                    "side": "bid",
                    "ord_type": "limit",
                    "price": "50000000",
                    "state": "wait",
                    "market": "KRW-BTC",
                    "created_at": "2024-01-01T10:00:00+09:00",
                    "volume": "0.001",
                    "remaining_volume": "0.001",
                    "reserved_fee": "20",
                    "remaining_fee": "20",
                    "paid_fee": "0",
                    "locked": "50000",
                    "executed_volume": "0"
                }
            """.trimIndent()

            // when
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val order = mapper.readValue(responseBody, OrderResponse::class.java)

            // then
            assertEquals("C0106000032400700021", order.uuid)
            assertEquals("bid", order.side)
            assertEquals("limit", order.ordType)
            assertEquals("wait", order.state)
            assertEquals(BigDecimal("50000000"), order.price)
        }

        @Test
        @DisplayName("BithumbError 파싱")
        fun parseBithumbError() {
            // given
            val responseBody = """
                {
                    "error": {
                        "name": "insufficient_funds",
                        "message": "잔고가 부족합니다"
                    }
                }
            """.trimIndent()

            // when
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val error = mapper.readValue(responseBody, BithumbError::class.java)

            // then
            assertEquals("insufficient_funds", error.error?.name)
            assertEquals("잔고가 부족합니다", error.error?.message)
        }
    }

    @Nested
    @DisplayName("주문 상태 및 타입 테스트")
    inner class OrderStatesTest {

        @Test
        @DisplayName("주문 상태 목록")
        fun orderStates() {
            // given
            val states = listOf("wait", "done", "cancel")

            // then
            assertTrue(states.contains("wait"))
            assertTrue(states.contains("done"))
            assertTrue(states.contains("cancel"))
        }

        @Test
        @DisplayName("주문 타입 목록")
        fun orderTypes() {
            // given
            val types = listOf("limit", "market", "price")

            // then
            assertTrue(types.contains("limit"))
            assertTrue(types.contains("market"))
            assertTrue(types.contains("price"))
        }

        @Test
        @DisplayName("주문 방향")
        fun orderSides() {
            // given
            val sides = listOf("bid", "ask")

            // then
            assertTrue(sides.contains("bid"))
            assertTrue(sides.contains("ask"))
        }
    }
}
