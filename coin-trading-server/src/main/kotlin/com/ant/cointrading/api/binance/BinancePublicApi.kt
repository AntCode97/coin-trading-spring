package com.ant.cointrading.api.binance

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * 바이낸스 Public API
 *
 * 김치 프리미엄 계산을 위한 해외 거래소 가격 조회.
 * API Key 불필요 (Public Endpoint).
 */
@Component
class BinancePublicApi {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl("https://api.binance.com")
        .build()

    /**
     * 단일 심볼 현재가 조회
     *
     * @param symbol 심볼 (예: BTCUSDT, ETHUSDT)
     */
    fun getPrice(symbol: String): BigDecimal? {
        return try {
            val response = webClient.get()
                .uri("/api/v3/ticker/price?symbol=$symbol")
                .retrieve()
                .bodyToMono(BinanceSimpleTicker::class.java)
                .block()

            response?.price
        } catch (e: Exception) {
            log.error("Binance 현재가 조회 실패 [$symbol]: ${e.message}")
            null
        }
    }

    /**
     * 모든 심볼 현재가 조회
     */
    fun getAllPrices(): List<BinanceSimpleTicker>? {
        return try {
            webClient.get()
                .uri("/api/v3/ticker/price")
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<BinanceSimpleTicker>>() {})
                .block()
        } catch (e: Exception) {
            log.error("Binance 전체 현재가 조회 실패: ${e.message}")
            null
        }
    }

    /**
     * 24시간 티커 정보 조회 (변동률 포함)
     *
     * @param symbol 심볼 (예: BTCUSDT)
     */
    fun get24hrTicker(symbol: String): BinanceTickerInfo? {
        return try {
            webClient.get()
                .uri("/api/v3/ticker/24hr?symbol=$symbol")
                .retrieve()
                .bodyToMono(BinanceTickerInfo::class.java)
                .block()
        } catch (e: Exception) {
            log.error("Binance 24시간 티커 조회 실패 [$symbol]: ${e.message}")
            null
        }
    }

    /**
     * 빗썸 마켓 코드를 바이낸스 심볼로 변환
     *
     * @param bithumbMarket 빗썸 마켓 코드 (예: KRW-BTC)
     * @return 바이낸스 심볼 (예: BTCUSDT)
     */
    fun toBinanceSymbol(bithumbMarket: String): String {
        val coin = bithumbMarket.substringAfter("-")
        return "${coin}USDT"
    }

    /**
     * 주요 코인 현재가 일괄 조회
     */
    fun getMainCoinPrices(): Map<String, BigDecimal> {
        val mainCoins = listOf("BTCUSDT", "ETHUSDT", "XRPUSDT", "SOLUSDT", "ADAUSDT", "DOGEUSDT")
        val allPrices = getAllPrices() ?: return emptyMap()

        return allPrices
            .filter { it.symbol in mainCoins }
            .associate { it.symbol to it.price }
    }
}
