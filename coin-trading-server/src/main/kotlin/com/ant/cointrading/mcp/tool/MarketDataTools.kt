package com.ant.cointrading.mcp.tool

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.MarketInfo
import com.ant.cointrading.api.bithumb.OrderbookInfo
import com.ant.cointrading.api.bithumb.TickerInfo
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * 시장 데이터 조회 MCP 도구
 */
@Component
class MarketDataTools(
    private val publicApi: BithumbPublicApi
) {

    @Tool(description = "OHLCV(시가, 고가, 저가, 종가, 거래량) 캔들 데이터를 조회합니다. 기술적 분석에 사용됩니다.")
    fun getOhlcv(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "시간 간격: day, week, month, minute1, minute3, minute5, minute10, minute15, minute30, minute60, minute240") interval: String,
        @ToolParam(description = "조회할 캔들 개수 (최대 200)") count: Int
    ): List<Map<String, Any>>? {
        return publicApi.getOhlcv(market, interval, count, null)
    }

    @Tool(description = "특정 마켓의 현재가 정보를 조회합니다.")
    fun getCurrentPrice(
        @ToolParam(description = "마켓 ID 목록 (쉼표로 구분, 예: KRW-BTC,KRW-ETH)") markets: String
    ): List<TickerInfo>? {
        return publicApi.getCurrentPrice(markets)
    }

    @Tool(description = "특정 마켓의 호가(매수/매도 주문장) 정보를 조회합니다.")
    fun getOrderbook(
        @ToolParam(description = "마켓 ID 목록 (쉼표로 구분, 예: KRW-BTC,KRW-ETH)") markets: String
    ): List<OrderbookInfo>? {
        return publicApi.getOrderbook(markets)
    }

    @Tool(description = "거래 가능한 모든 마켓(코인) 목록을 조회합니다.")
    fun getMarketAll(): List<MarketInfo>? {
        return publicApi.getMarketAll()
    }

    @Tool(description = "특정 마켓의 최근 체결 내역을 조회합니다.")
    fun getTradesTicks(
        @ToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @ToolParam(description = "조회할 체결 내역 개수") count: Int
    ): List<Map<String, Any>>? {
        return publicApi.getTradesTicks(market, count)
    }
}
