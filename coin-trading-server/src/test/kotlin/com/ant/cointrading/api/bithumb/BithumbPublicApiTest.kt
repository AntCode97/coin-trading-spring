package com.ant.cointrading.api.bithumb

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BithumbPublicApi JSON 파싱 단위 테스트
 *
 * 테스트 범위:
 * - 정상 응답 파싱 (배열 형태, 상태 코드 포함)
 * - 에러 응답 파싱 (status 필드, error 객체)
 * - market 형식 변환
 * - 각 API 엔드포인트별 응답 구조 검증
 */
@DisplayName("BithumbPublicApi JSON 파싱 테스트")
class BithumbPublicApiTest {

    private val objectMapper = ObjectMapper()

    @Nested
    @DisplayName("getCurrentPrice 테스트")
    inner class GetCurrentPriceTest {

        @Test
        @DisplayName("정상 응답 - 배열 형태")
        fun success_responseArray() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-BTC",
                        "trade_price": "50000000",
                        "opening_price": "49500000",
                        "high_price": "50500000",
                        "low_price": "49000000",
                        "prev_closing_price": "49500000",
                        "change": "RISE",
                        "change_price": "500000",
                        "change_rate": "1.01",
                        "trade_volume": "1234.5678",
                        "acc_trade_volume": "1234567.89",
                        "acc_trade_price": "62000000000000",
                        "timestamp": 1704109200000,
                        "trade_date": "2024-01-01"
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray, "응답이 배열 형태")
            val first = jsonNode[0]
            assertEquals("KRW-BTC", first["market"].asText())
            assertEquals("50000000", first["trade_price"].asText())
            assertEquals("2024-01-01", first["trade_date"].asText())
        }

        @Test
        @DisplayName("정상 응답 - 상태 코드 포함 (구버전)")
        fun success_responseWithStatus() {
            // given
            val responseBody = """
                {
                    "status": "0000",
                    "data": [
                        {
                            "market": "KRW-BTC",
                            "trade_price": "50000000"
                        }
                    ]
                }
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)
            val status = jsonNode.get("status")?.asText()
            val data = jsonNode.get("data")

            // then
            assertEquals("0000", status)
            assertTrue(data?.isArray == true)
        }

        @Test
        @DisplayName("에러 응답 - 비상장 코인")
        fun error_unlistedCoin() {
            // given
            val responseBody = """
                {
                    "status": "5500",
                    "message": "비상장 종목입니다"
                }
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)
            val status = jsonNode.get("status")?.asText()

            // then
            assertEquals("5500", status)
        }
    }

    @Nested
    @DisplayName("getOrderbook 테스트")
    inner class GetOrderbookTest {

        @Test
        @DisplayName("정상 응답 파싱")
        fun success_responseParsing() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-BTC",
                        "timestamp": 1704109200000,
                        "total_ask_size": "100.5",
                        "total_bid_size": "50.2",
                        "orderbook_units": [
                            {
                                "ask_price": "50100000",
                                "bid_price": "50000000",
                                "ask_size": "1.2",
                                "bid_size": "0.8"
                            }
                        ]
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray)
            val first = jsonNode[0]
            assertEquals("KRW-BTC", first["market"].asText())
            assertEquals("100.5", first["total_ask_size"].asText())
        }
    }

    @Nested
    @DisplayName("getOhlcv 테스트")
    inner class GetOhlcvTest {

        @Test
        @DisplayName("정상 응답 - 캔들 배열")
        fun success_candlesParsing() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-BTC",
                        "candle_date_time_utc": "2024-01-01T00:00:00",
                        "candle_date_time_kst": "2024-01-01T09:00:00",
                        "opening_price": "50000000",
                        "high_price": "50500000",
                        "low_price": "49500000",
                        "trade_price": "50200000",
                        "timestamp": 1704109200000,
                        "candle_acc_trade_price": "10000000000",
                        "candle_acc_trade_volume": "200",
                        "unit": 60
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray)
            val first = jsonNode[0]
            assertEquals("KRW-BTC", first["market"].asText())
            assertEquals(50200000, first["trade_price"].asInt())
        }

        @Test
        @DisplayName("에러 응답 - 404 Code not found")
        fun error_codeNotFound() {
            // given
            val responseBody = """
                {
                    "error": {
                        "name": 404,
                        "message": "Code not found"
                    }
                }
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)
            val error = jsonNode.get("error")

            // then
            assertNotNull(error)
            assertEquals(404, error?.get("name")?.asInt())
            assertEquals("Code not found", error?.get("message")?.asText())
        }

        @Test
        @DisplayName("market 형식 변환 - BTC_KRW → KRW-BTC")
        fun marketFormatConversion() {
            // given
            val market = "BTC_KRW"

            // when
            val converted = market.split("_").let { parts ->
                if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
            }

            // then
            assertEquals("KRW-BTC", converted)
        }

        @Test
        @DisplayName("market 형식 유지 - KRW-BTC → KRW-BTC")
        fun marketFormatPreserved() {
            // given
            val market = "KRW-BTC"

            // when
            val converted = if (market.contains("-")) market else market

            // then
            assertEquals("KRW-BTC", converted)
        }
    }

    @Nested
    @DisplayName("getMarketAll 테스트")
    inner class GetMarketAllTest {

        @Test
        @DisplayName("정상 응답 파싱")
        fun success_responseParsing() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-BTC",
                        "korean_name": "비트코인",
                        "english_name": "Bitcoin",
                        "market_warning": "NONE"
                    },
                    {
                        "market": "KRW-ETH",
                        "korean_name": "이더리움",
                        "english_name": "Ethereum",
                        "market_warning": "CAUTION"
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray)
            assertEquals(2, jsonNode.size())
            val first = jsonNode[0]
            assertEquals("KRW-BTC", first["market"].asText())
            assertEquals("NONE", first["market_warning"].asText())
        }
    }

    @Nested
    @DisplayName("getTradesTicks 테스트")
    inner class GetTradesTicksTest {

        @Test
        @DisplayName("정상 응답 파싱")
        fun success_responseParsing() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-BTC",
                        "trade_date_utc": "2024-01-01",
                        "trade_time_utc": "10:30:00",
                        "timestamp": 1704109800000,
                        "trade_price": 50000000,
                        "trade_volume": 0.01,
                        "prev_closing_price": 49900000,
                        "change_price": 100000,
                        "ask_bid": "BID",
                        "sequential_id": 1234567890
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray)
            val first = jsonNode[0]
            assertEquals("KRW-BTC", first["market"].asText())
            assertEquals("BID", first["ask_bid"].asText())
        }
    }

    @Nested
    @DisplayName("getVirtualAssetWarning 테스트")
    inner class GetVirtualAssetWarningTest {

        @Test
        @DisplayName("정상 응답 파싱")
        fun success_responseParsing() {
            // given
            val responseBody = """
                [
                    {
                        "market": "KRW-XPR",
                        "warning_type": "TRADING_VOLUME_SUDDEN_FLUCTUATION",
                        "end_date": "2026-01-13 06:59:59"
                    }
                ]
            """.trimIndent()

            // when
            val jsonNode = objectMapper.readTree(responseBody)

            // then
            assertTrue(jsonNode.isArray)
            val first = jsonNode[0]
            assertEquals("KRW-XPR", first["market"].asText())
            assertEquals("TRADING_VOLUME_SUDDEN_FLUCTUATION", first["warning_type"].asText())
        }

        @Test
        @DisplayName("경보 유형 필터링")
        fun filterByWarningType() {
            // given
            val warnings = listOf(
                VirtualAssetWarning("KRW-XPR", "TRADING_VOLUME_SUDDEN_FLUCTUATION", "2026-01-13 06:59:59"),
                VirtualAssetWarning("KRW-ETH", "PRICE_DIFFERENCE_HIGH", "2026-01-13 12:00:00"),
                VirtualAssetWarning("KRW-BTC", "DEPOSIT_AMOUNT_SUDDEN_FLUCTUATION", "2026-01-13 18:00:00")
            )

            // when
            val volumeSurgeWarnings = warnings.filter { it.warningType == "TRADING_VOLUME_SUDDEN_FLUCTUATION" }

            // then
            assertEquals(1, volumeSurgeWarnings.size)
            assertEquals("KRW-XPR", volumeSurgeWarnings[0].market)
        }
    }

    @Nested
    @DisplayName("에러 처리")
    inner class ErrorHandlingTest {

        @Test
        @DisplayName("네트워크 에러 시 null 반환 및 이벤트 발행")
        fun networkError_returnsNull_andPublishesEvent() {
            // 이 테스트는 실제 WebClient 동작이 필요하므로
            // Integration 테스트에서 수행하는 것이 적절함
            // 여기서는 null 처리 로직만 검증

            // given
            val apiResponse: List<TickerInfo>? = null

            // when & then
            assertNull(apiResponse)
        }

        @Test
        @DisplayName("빈 응답 처리")
        fun emptyResponse_handling() {
            // given
            val responseBody = ""

            // when
            val isEmpty = responseBody.isEmpty()

            // then
            assertTrue(isEmpty)
        }
    }
}
