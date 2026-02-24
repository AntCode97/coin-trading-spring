package com.ant.cointrading.mcp.tool

import com.ant.cointrading.guided.GuidedAgentContextResponse
import com.ant.cointrading.guided.GuidedChartResponse
import com.ant.cointrading.guided.GuidedMarketItem
import com.ant.cointrading.guided.GuidedMarketSortBy
import com.ant.cointrading.guided.GuidedAdoptPositionRequest
import com.ant.cointrading.guided.GuidedAdoptPositionResponse
import com.ant.cointrading.guided.GuidedRecommendation
import com.ant.cointrading.guided.GuidedSortDirection
import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedTradeView
import com.ant.cointrading.guided.GuidedTradingService
import com.ant.cointrading.guided.TradingMode
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
        @McpToolParam(description = "정렬 기준: TURNOVER, CHANGE_RATE, VOLUME, SURGE_RATE, MARKET_CAP_FLOW, RECOMMENDED_ENTRY_WIN_RATE, MARKET_ENTRY_WIN_RATE")
        @ToolParam(description = "정렬 기준: TURNOVER, CHANGE_RATE, VOLUME, SURGE_RATE, MARKET_CAP_FLOW, RECOMMENDED_ENTRY_WIN_RATE, MARKET_ENTRY_WIN_RATE")
        sortBy: String = "TURNOVER",
        @McpToolParam(description = "정렬 방향: ASC, DESC")
        @ToolParam(description = "정렬 방향: ASC, DESC")
        sortDirection: String = "DESC",
        @McpToolParam(description = "캔들 간격 (승률 정렬 시 사용, 예: tick, minute30, day)")
        @ToolParam(description = "캔들 간격 (승률 정렬 시 사용, 예: tick, minute30, day)")
        interval: String = "minute30",
        @McpToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        @ToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        mode: String = "SWING"
    ): List<GuidedMarketItem> {
        val sortType = runCatching { GuidedMarketSortBy.valueOf(sortBy.uppercase()) }
            .getOrDefault(GuidedMarketSortBy.TURNOVER)
        val direction = runCatching { GuidedSortDirection.valueOf(sortDirection.uppercase()) }
            .getOrDefault(GuidedSortDirection.DESC)
        return guidedTradingService.getMarketBoard(sortType, direction, interval, TradingMode.fromString(mode))
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
        closedTradeLimit: Int,
        @McpToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        @ToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        mode: String = "SWING"
    ): GuidedAgentContextResponse {
        return guidedTradingService.getAgentContext(
            market = market.uppercase(),
            interval = interval,
            count = count,
            closedTradeLimit = closedTradeLimit,
            mode = TradingMode.fromString(mode)
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
        count: Int,
        @McpToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        @ToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        mode: String = "SWING"
    ): GuidedRecommendation {
        return guidedTradingService.getRecommendation(market.uppercase(), interval, count, TradingMode.fromString(mode))
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

    @McpTool(description = "수동 트레이딩에서 미체결 대기 중인 진입 주문을 취소합니다.")
    fun cancelGuidedPendingEntry(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)")
        market: String
    ): GuidedTradeView? {
        return guidedTradingService.cancelPendingEntry(market.uppercase())
    }

    @McpTool(description = "Guided 자동매매를 시작합니다. Guided API 우선 경로에 해당합니다.")
    fun guidedStartTrading(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String,
        @McpToolParam(description = "진입 금액(KRW)") amountKrw: Long,
        @McpToolParam(description = "주문 타입: MARKET 또는 LIMIT") orderType: String = "MARKET",
        @McpToolParam(description = "지정가(주문 타입 LIMIT일 때)") limitPrice: Double? = null
    ): GuidedTradeView {
        return guidedTradingService.startAutoTrading(
            GuidedStartRequest(
                market = market.uppercase(),
                amountKrw = amountKrw,
                orderType = orderType,
                limitPrice = limitPrice
            )
        )
    }

    @McpTool(description = "Guided 자동매매 포지션을 정지/청산합니다.")
    fun guidedStopTrading(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String
    ): GuidedTradeView {
        return guidedTradingService.stopAutoTrading(market.uppercase())
    }

    @McpTool(description = "Guided 자동매매 포지션의 부분 익절을 실행합니다.")
    fun guidedPartialTakeProfit(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String,
        @McpToolParam(description = "익절 비율(0.1~0.9)") ratio: Double = 0.5
    ): GuidedTradeView {
        return guidedTradingService.partialTakeProfit(market.uppercase(), ratio)
    }

    @McpTool(description = "외부(MCP 직접 주문) 포지션을 Guided 관리 포지션으로 편입합니다.")
    fun guidedAdoptPosition(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String,
        @McpToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION") mode: String = "SWING",
        @McpToolParam(description = "캔들 간격: tick, minute30, day 등") interval: String = "minute30",
        @McpToolParam(description = "편입 출처 식별값") entrySource: String = "MCP_DIRECT",
        @McpToolParam(description = "편입 메모") notes: String = ""
    ): GuidedAdoptPositionResponse {
        return guidedTradingService.adoptExternalPosition(
            GuidedAdoptPositionRequest(
                market = market.uppercase(),
                mode = mode,
                interval = interval,
                entrySource = entrySource,
                notes = notes
            )
        )
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
        count: Int,
        @McpToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        @ToolParam(description = "트레이딩 모드: SCALP, SWING, POSITION")
        mode: String = "SWING"
    ): GuidedChartResponse {
        return guidedTradingService.getChartData(market.uppercase(), interval, count, TradingMode.fromString(mode))
    }
}
