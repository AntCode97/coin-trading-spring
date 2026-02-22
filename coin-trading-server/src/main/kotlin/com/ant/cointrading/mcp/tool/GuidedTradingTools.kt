package com.ant.cointrading.mcp.tool

import com.ant.cointrading.guided.GuidedAgentContextResponse
import com.ant.cointrading.guided.GuidedChartResponse
import com.ant.cointrading.guided.GuidedMarketItem
import com.ant.cointrading.guided.GuidedMarketSortBy
import com.ant.cointrading.guided.GuidedRecommendation
import com.ant.cointrading.guided.GuidedSortDirection
import com.ant.cointrading.guided.GuidedTradeView
import com.ant.cointrading.guided.GuidedTradingService
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
class GuidedTradingTools(
    private val guidedTradingService: GuidedTradingService
) {

    @McpTool(description = "수동 트레이딩 워크스페이스용 마켓 목록을 조회합니다.")
    @Tool(description = "수동 트레이딩 워크스페이스용 마켓 목록을 조회합니다.")
    fun getGuidedMarkets(
        @McpToolParam(description = "정렬 기준: VOLUME, CHANGE_RATE, PRICE")
        @ToolParam(description = "정렬 기준: VOLUME, CHANGE_RATE, PRICE")
        sortBy: String,
        @McpToolParam(description = "정렬 방향: ASC, DESC")
        @ToolParam(description = "정렬 방향: ASC, DESC")
        sortDirection: String
    ): List<GuidedMarketItem> {
        val sortType = runCatching { GuidedMarketSortBy.valueOf(sortBy.uppercase()) }
            .getOrDefault(GuidedMarketSortBy.TURNOVER)
        val direction = runCatching { GuidedSortDirection.valueOf(sortDirection.uppercase()) }
            .getOrDefault(GuidedSortDirection.DESC)
        return guidedTradingService.getMarketBoard(sortType, direction)
    }

    @McpTool(description = "수동 트레이딩용 차트/포지션/체결 이력 컨텍스트를 한 번에 조회합니다.")
    @Tool(description = "수동 트레이딩용 차트/포지션/체결 이력 컨텍스트를 한 번에 조회합니다.")
    fun getGuidedAgentContext(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)")
        @ToolParam(description = "마켓 코드 (예: KRW-BTC)")
        market: String,
        @McpToolParam(description = "캔들 간격 (예: minute30, minute60, day)")
        @ToolParam(description = "캔들 간격 (예: minute30, minute60, day)")
        interval: String,
        @McpToolParam(description = "캔들 조회 개수")
        @ToolParam(description = "캔들 조회 개수")
        count: Int,
        @McpToolParam(description = "최근 청산 트레이드 조회 개수")
        @ToolParam(description = "최근 청산 트레이드 조회 개수")
        closedTradeLimit: Int
    ): GuidedAgentContextResponse {
        return guidedTradingService.getAgentContext(
            market = market.uppercase(),
            interval = interval,
            count = count,
            closedTradeLimit = closedTradeLimit
        )
    }

    @McpTool(description = "현재 수동 트레이딩 추천값(추천가/손절가/익절가/승률)을 조회합니다.")
    @Tool(description = "현재 수동 트레이딩 추천값(추천가/손절가/익절가/승률)을 조회합니다.")
    fun getGuidedRecommendation(
        @McpToolParam(description = "마켓 코드")
        @ToolParam(description = "마켓 코드")
        market: String,
        @McpToolParam(description = "캔들 간격")
        @ToolParam(description = "캔들 간격")
        interval: String,
        @McpToolParam(description = "캔들 조회 개수")
        @ToolParam(description = "캔들 조회 개수")
        count: Int
    ): GuidedRecommendation {
        return guidedTradingService.getRecommendation(market.uppercase(), interval, count)
    }

    @McpTool(description = "현재 수동 트레이딩 포지션을 조회합니다. 없으면 null을 반환합니다.")
    @Tool(description = "현재 수동 트레이딩 포지션을 조회합니다. 없으면 null을 반환합니다.")
    fun getGuidedPosition(
        @McpToolParam(description = "마켓 코드")
        @ToolParam(description = "마켓 코드")
        market: String
    ): GuidedTradeView? {
        return guidedTradingService.getActivePosition(market.uppercase())
    }

    @McpTool(description = "수동 트레이딩 워크스페이스용 차트/호가/주문 스냅샷을 조회합니다.")
    @Tool(description = "수동 트레이딩 워크스페이스용 차트/호가/주문 스냅샷을 조회합니다.")
    fun getGuidedChartSnapshot(
        @McpToolParam(description = "마켓 코드")
        @ToolParam(description = "마켓 코드")
        market: String,
        @McpToolParam(description = "캔들 간격")
        @ToolParam(description = "캔들 간격")
        interval: String,
        @McpToolParam(description = "캔들 조회 개수")
        @ToolParam(description = "캔들 조회 개수")
        count: Int
    ): GuidedChartResponse {
        return guidedTradingService.getChartData(market.uppercase(), interval, count)
    }
}
