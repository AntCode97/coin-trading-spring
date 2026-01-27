package com.ant.cointrading.api.bithumb

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BithumbPublicApi 통합 테스트 (실제 API 호출)
 *
 * 빗썸 API는 특정 IP에서만 호출 가능하므로 기본적으로 비활성화됩니다.
 *
 * 실행 방법:
 * - 명시적 실행: ./gradlew test --tests "*BithumbPublicApiIntegrationTest" -DENABLE_INTEGRATION_TEST=true
 * - CI: 자동으로 제외됨 (@Tag("integration") + build.gradle.kts 설정)
 *
 * 테스트 범위:
 * - 실제 Public API 호출 (매수/매도 제외)
 * - getCurrentPrice, getOrderbook, getOhlcv, getMarketAll 등
 */
@Tag("integration")
@ActiveProfiles("test")
@DisplayName("BithumbPublicApi 통합 테스트 (실제 API 호출)")
class BithumbPublicApiIntegrationTest {

    private lateinit var bithumbPublicApi: BithumbPublicApi
    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    @BeforeEach
    fun setup() {
        // ENABLE_INTEGRATION_TEST 시스템 프로퍼티가 true일 때만 실행
        val enabled = System.getProperty("ENABLE_INTEGRATION_TEST")?.toBoolean() ?: false
        Assumptions.assumeTrue(enabled, "통합 테스트는 -DENABLE_INTEGRATION_TEST=true 설정 시에만 실행됩니다")

        // CI 환경에서는 실행 안 함
        val isCI = System.getenv("CI")?.toBoolean() ?: false
        Assumptions.assumeFalse(isCI, "CI 환경에서는 Bithumb API 통합 테스트를 건너뜁니다 (IP 제한)")

        // WebClient 직접 생성
        val webClient = WebClient.builder()
            .baseUrl("https://api.bithumb.com")
            .build()

        // 이벤트 퍼블리셔는 mock (실제로는 사용하지 않음)
        val eventPublisher = org.mockito.kotlin.mock<org.springframework.context.ApplicationEventPublisher>()

        bithumbPublicApi = BithumbPublicApi(webClient, objectMapper, eventPublisher)
    }

    @Test
    @DisplayName("getCurrentPrice - KRW-BTC")
    fun getCurrentPrice_btc() {
        // when
        val tickers = bithumbPublicApi.getCurrentPrice("KRW-BTC")

        // then
        assertNotNull(tickers, "BTC 현재가 조회 성공")
        assertTrue(tickers!!.isNotEmpty(), "응답 존재")
        val btc = tickers.first()
        assertTrue(btc.tradePrice > java.math.BigDecimal.ZERO, "가격은 0보다 커야 함")
        println("BTC 현재가: ${btc.tradePrice}원")
    }

    @Test
    @DisplayName("getMarketAll - 전체 마켓 목록")
    fun getMarketAll() {
        // when
        val markets = bithumbPublicApi.getMarketAll()

        // then
        assertNotNull(markets, "마켓 목록 조회 성공")
        assertTrue(markets!!.isNotEmpty(), "마켓 목록이 비어있지 않아야 함")
        assertTrue(markets.any { it.market == "KRW-BTC" }, "KRW-BTC 포함")

        println("전체 마켓 수: ${markets.size}개")
        println("KRW 마켓: ${markets.filter { it.market.startsWith("KRW-") }.size}개")
    }

    @Test
    @DisplayName("getOrderbook - KRW-BTC")
    fun getOrderbook() {
        // when
        val orderbooks = bithumbPublicApi.getOrderbook("KRW-BTC")

        // then
        assertNotNull(orderbooks, "호가 조회 성공")
        assertTrue(orderbooks!!.isNotEmpty(), "호가 데이터 존재")

        val orderbook = orderbooks.first()
        assertNotNull(orderbook.orderbookUnits, "호가 유닛 존재")
        assertTrue(orderbook.orderbookUnits!!.isNotEmpty(), "호가 유닛 비어있지 않음")

        val bestAsk = orderbook.orderbookUnits!!.first().askPrice
        val bestBid = orderbook.orderbookUnits!!.first().bidPrice
        println("최우선 매도호가: ${bestAsk}원, 최우선 매수호가: ${bestBid}원")
    }

    @Test
    @DisplayName("getOhlcv - KRW-BTC 60분봉")
    fun getOhlcv() {
        // when
        val candles = bithumbPublicApi.getOhlcv("KRW-BTC", "minute60", 10)

        // then
        assertNotNull(candles, "캔들 조회 성공")
        assertTrue(candles!!.isNotEmpty(), "캔들 데이터 존재")
        assertTrue(candles.size <= 10, "요청한 개수 이하")

        val latest = candles.first()
        println("최신 캔들: 시가 ${latest.openingPrice}원, 종가 ${latest.tradePrice}원")
    }

    @Test
    @DisplayName("getTradesTicks - KRW-BTC 최근 체결")
    fun getTradesTicks() {
        // when
        val trades = bithumbPublicApi.getTradesTicks("KRW-BTC", 10)

        // then
        assertNotNull(trades, "체결 내역 조회 성공")
        assertTrue(trades!!.isNotEmpty(), "체결 내역 존재")

        val latest = trades.first()
        println("최신 체결: ${latest.tradePrice}원, ${latest.tradeVolume}개, ${latest.askBid}")
    }

    @Test
    @DisplayName("getVirtualAssetWarning - 경보 종목 조회")
    fun getVirtualAssetWarning() {
        // when
        val warnings = bithumbPublicApi.getVirtualAssetWarning()

        // then
        assertNotNull(warnings, "경보 조회 성공")
        println("현재 경보 종목 수: ${warnings?.size ?: 0}개")

        warnings?.forEach { warning ->
            println("  ${warning.market}: ${warning.warningType} (${warning.endDate})")
        }
    }

    @Test
    @DisplayName("다중 마켓 getCurrentPrice - 한 번에 여러 종목 조회")
    fun getCurrentPrice_multiple() {
        // when - 콤마로 구분하여 여러 마켓 조회
        val tickers = bithumbPublicApi.getCurrentPrice("KRW-BTC,KRW-ETH,KRW-XRP")

        // then
        assertNotNull(tickers, "다중 종목 조회 성공")
        assertTrue(tickers!!.size >= 3, "최소 3개 종목 존재")

        tickers.take(3).forEach { ticker ->
            println("${ticker.market}: ${ticker.tradePrice}원")
        }
    }
}
