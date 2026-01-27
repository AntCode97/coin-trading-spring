package com.ant.cointrading.api.bithumb

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BithumbPrivateApi 통합 테스트 (실제 API 호출)
 *
 * 빗썸 Private API는 JWT 인증이 필요하며 특정 IP에서만 호출 가능합니다.
 *
 * 실행 방법:
 * - 명시적 실행: ./gradlew test --tests "*BithumbPrivateApiIntegrationTest" -DENABLE_INTEGRATION_TEST=true
 * - CI: 자동으로 제외됨 (@Tag("integration"))
 *
 * 환경 변수 필요:
 * - BITHUMB_ACCESS_KEY
 * - BITHUMB_SECRET_KEY
 */
@Tag("integration")
@ActiveProfiles("test")
@DisplayName("BithumbPrivateApi 통합 테스트 (실제 API 호출)")
class BithumbPrivateApiIntegrationTest {

    private lateinit var bithumbPrivateApi: BithumbPrivateApi
    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    @BeforeEach
    fun setup() {
        // ENABLE_INTEGRATION_TEST 시스템 프로퍼티가 true일 때만 실행
        val enabled = System.getProperty("ENABLE_INTEGRATION_TEST")?.toBoolean() ?: false
        Assumptions.assumeTrue(enabled, "통합 테스트는 -DENABLE_INTEGRATION_TEST=true 설정 시에만 실행됩니다")

        // CI 환경에서는 실행 안 함
        val isCI = System.getenv("CI")?.toBoolean() ?: false
        Assumptions.assumeFalse(isCI, "CI 환경에서는 Bithumb Private API 통합 테스트를 건너뜁니다 (IP 제한)")

        // API 키 확인
        val accessKey = System.getenv("BITHUMB_ACCESS_KEY")
        val secretKey = System.getenv("BITHUMB_SECRET_KEY")

        Assumptions.assumeTrue(!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank(),
            "BITHUMB_ACCESS_KEY와 BITHUMB_SECRET_KEY 환경 변수가 필요합니다")

        // RestClient와 Properties 직접 생성
        val restClient = RestClient.builder()
            .baseUrl("https://api.bithumb.com")
            .build()

        val properties = com.ant.cointrading.config.BithumbProperties(
            baseUrl = "https://api.bithumb.com",
            accessKey = accessKey,
            secretKey = secretKey
        )

        bithumbPrivateApi = BithumbPrivateApi(restClient, properties, objectMapper)
    }

    @Test
    @DisplayName("getBalances - 전체 잔고 조회")
    fun getBalances() {
        // when
        val balances = bithumbPrivateApi.getBalances()

        // then
        assertNotNull(balances, "잔고 조회 성공")
        assertTrue(balances!!.isNotEmpty(), "잔고 목록 존재")

        // KRW 잔고 확인
        val krw = balances.find { it.currency == "KRW" }
        assertNotNull(krw, "KRW 잔고 존재")
        println("KRW 잔고: ${krw.balance}원 (락: ${krw.locked}원)")

        // 보유 코인 확인
        val coins = balances.filter { it.balance > java.math.BigDecimal.ZERO && it.currency != "KRW" }
        if (coins.isNotEmpty()) {
            println("보유 코인:")
            coins.forEach { coin ->
                println("  ${coin.currency}: ${coin.balance} (평단가: ${coin.avgBuyPrice}원)")
            }
        } else {
            println("보유 코인 없음")
        }
    }

    @Test
    @DisplayName("getOrders - 미체결 주문 조회")
    fun getOrders() {
        // when
        val orders = bithumbPrivateApi.getOrders(
            market = "KRW-BTC",
            state = "wait",
            page = 1,
            limit = 100
        )

        // then
        assertNotNull(orders, "주문 조회 성공")
        println("미체결 주문 수: ${orders?.size ?: 0}개")

        orders?.forEach { order ->
            println("  ${order.market}: ${order.side} ${order.ordType} ${order.volume}개 @ ${order.price}원 (${order.state})")
        }
    }

    @Test
    @DisplayName("getOrderChance - 주문 가능 정보 조회")
    fun getOrderChance() {
        // when
        val chance = bithumbPrivateApi.getOrderChance("KRW-BTC")

        // then
        assertNotNull(chance, "주문 가능 정보 조회 성공")
        println("KRW-BTC 주문 가능 정보: $chance")
    }

    @Test
    @DisplayName("getOrder - 개별 주문 조회 (UUID 없으면 빈 결과)")
    fun getOrder() {
        // when
        // 존재하지 않는 UUID로 조회 (에러 응답 확인용)
        val result = runCatching {
            bithumbPrivateApi.getOrder("invalid-uuid-12345")
        }

        // then
        // 에러가 발생하거나 null이 반환되어야 함
        assertTrue(result.isFailure || result.getOrNull() == null, "유효하지 않은 UUID는 에러 또는 null")
        println("유효하지 않은 UUID 조회 결과: ${result.exceptionOrNull()?.message ?: "null 반환"}")
    }
}
