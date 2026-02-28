package com.ant.cointrading.controller

import com.ant.cointrading.guided.GuidedChartResponse
import com.ant.cointrading.guided.GuidedAgentContextResponse
import com.ant.cointrading.guided.GuidedAutopilotOpportunitiesResponse
import com.ant.cointrading.guided.GuidedClosedTradeView
import com.ant.cointrading.guided.GuidedDailyStats
import com.ant.cointrading.guided.GuidedMarketItem
import com.ant.cointrading.guided.GuidedRecommendation
import com.ant.cointrading.guided.GuidedMarketSortBy
import com.ant.cointrading.guided.GuidedAutopilotLiveResponse
import com.ant.cointrading.guided.GuidedPnlReconcileResult
import com.ant.cointrading.guided.GuidedRealtimeTickerView
import com.ant.cointrading.guided.GuidedSortDirection
import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedAutopilotDecisionLogRequest
import com.ant.cointrading.guided.GuidedAutopilotDecisionLogResponse
import com.ant.cointrading.guided.GuidedAutopilotPerformanceResponse
import com.ant.cointrading.guided.GuidedSyncResult
import com.ant.cointrading.guided.GuidedTradeView
import com.ant.cointrading.guided.GuidedTradingService
import com.ant.cointrading.guided.TradingMode
import com.ant.cointrading.guided.GuidedWinRateThresholdMode
import com.ant.cointrading.guided.GuidedAdoptPositionRequest
import com.ant.cointrading.guided.GuidedAdoptPositionResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/guided-trading")
class GuidedTradingController(
    private val guidedTradingService: GuidedTradingService
) {

    @GetMapping("/markets")
    fun getMarketBoard(
        @RequestParam(defaultValue = "TURNOVER") sortBy: GuidedMarketSortBy,
        @RequestParam(defaultValue = "DESC") sortDirection: GuidedSortDirection,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "SWING") mode: String
    ): List<GuidedMarketItem> {
        return guidedTradingService.getMarketBoard(sortBy, sortDirection, interval, TradingMode.fromString(mode))
    }

    @GetMapping("/chart")
    fun getChart(
        @RequestParam market: String,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "120") count: Int,
        @RequestParam(defaultValue = "SWING") mode: String
    ): GuidedChartResponse {
        return guidedTradingService.getChartData(market.uppercase(), interval, count, TradingMode.fromString(mode))
    }

    @GetMapping("/recommendation")
    fun getRecommendation(
        @RequestParam market: String,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "120") count: Int,
        @RequestParam(defaultValue = "SWING") mode: String
    ): GuidedRecommendation {
        return guidedTradingService.getRecommendation(market.uppercase(), interval, count, TradingMode.fromString(mode))
    }

    @GetMapping("/ticker")
    fun getRealtimeTicker(@RequestParam market: String): GuidedRealtimeTickerView? {
        return guidedTradingService.getRealtimeTicker(market.uppercase())
    }

    @PostMapping("/start")
    fun startTrading(@RequestBody request: GuidedStartRequest): GuidedTradeView {
        return try {
            guidedTradingService.startAutoTrading(request)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "요청값 오류")
        }
    }

    @PostMapping("/stop")
    fun stopTrading(@RequestParam market: String): GuidedTradeView {
        return try {
            guidedTradingService.stopAutoTrading(market.uppercase())
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "청산 실패")
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "요청값 오류")
        }
    }

    @PostMapping("/cancel-pending")
    fun cancelPendingEntry(@RequestParam market: String): GuidedTradeView? {
        return guidedTradingService.cancelPendingEntry(market.uppercase())
    }

    @PostMapping("/partial-take-profit")
    fun partialTakeProfit(
        @RequestParam market: String,
        @RequestParam(defaultValue = "0.5") ratio: Double
    ): GuidedTradeView {
        return try {
            guidedTradingService.partialTakeProfit(market.uppercase(), ratio)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "요청값 오류")
        }
    }

    @GetMapping("/position/{market}")
    fun getPosition(@PathVariable market: String): GuidedTradeView? {
        return guidedTradingService.getActivePosition(market.uppercase())
    }

    @GetMapping("/positions/open")
    fun getOpenPositions(): List<GuidedTradeView> {
        return guidedTradingService.getAllOpenPositions()
    }

    @PostMapping("/positions/adopt")
    fun adoptPosition(@RequestBody request: GuidedAdoptPositionRequest): GuidedAdoptPositionResponse {
        return try {
            guidedTradingService.adoptExternalPosition(request)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "요청값 오류")
        }
    }

    @GetMapping("/stats/today")
    fun getTodayStats(): GuidedDailyStats {
        return guidedTradingService.getTodayStats()
    }

    @GetMapping("/trades/closed")
    fun getClosedTrades(
        @RequestParam(defaultValue = "50") limit: Int
    ): List<GuidedClosedTradeView> {
        return guidedTradingService.getClosedTrades(limit.coerceIn(1, 200))
    }

    @PostMapping("/reconcile/closed")
    fun reconcileClosedTrades(
        @RequestParam(defaultValue = "30") windowDays: Long,
        @RequestParam(defaultValue = "true") dryRun: Boolean
    ): GuidedPnlReconcileResult {
        return guidedTradingService.reconcileClosedTrades(windowDays = windowDays, dryRun = dryRun)
    }

    @PostMapping("/sync")
    fun syncPositions(): GuidedSyncResult {
        return guidedTradingService.syncPositions()
    }

    @GetMapping("/verify-token")
    fun verifyToken(): Map<String, Boolean> {
        // DesktopAccessInterceptor가 이미 토큰을 검증한다.
        // 여기 도달 = 유효 토큰.
        return mapOf("valid" to true)
    }

    @GetMapping("/agent/context")
    fun getAgentContext(
        @RequestParam market: String,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "120") count: Int,
        @RequestParam(defaultValue = "20") closedTradeLimit: Int,
        @RequestParam(defaultValue = "SWING") mode: String
    ): GuidedAgentContextResponse {
        return guidedTradingService.getAgentContext(
            market = market.uppercase(),
            interval = interval,
            count = count,
            closedTradeLimit = closedTradeLimit,
            mode = TradingMode.fromString(mode)
        )
    }

    @GetMapping("/autopilot/live")
    fun getAutopilotLive(
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "SWING") mode: String,
        @RequestParam(required = false) thresholdMode: String?,
        @RequestParam(required = false) minMarketWinRate: Double?,
        @RequestParam(required = false) minRecommendedWinRate: Double?,
        @RequestParam(required = false) strategyCodePrefix: String?
    ): GuidedAutopilotLiveResponse {
        return guidedTradingService.getAutopilotLive(
            interval = interval,
            mode = TradingMode.fromString(mode),
            thresholdMode = GuidedWinRateThresholdMode.fromString(thresholdMode),
            minMarketWinRate = minMarketWinRate,
            minRecommendedWinRate = minRecommendedWinRate,
            strategyCodePrefix = strategyCodePrefix
        )
    }

    @GetMapping("/autopilot/opportunities")
    fun getAutopilotOpportunities(
        @RequestParam(defaultValue = "minute1") interval: String,
        @RequestParam(defaultValue = "minute10") confirmInterval: String,
        @RequestParam(defaultValue = "SCALP") mode: String,
        @RequestParam(defaultValue = "15") universeLimit: Int
    ): GuidedAutopilotOpportunitiesResponse {
        return guidedTradingService.getAutopilotOpportunities(
            interval = interval,
            confirmInterval = confirmInterval,
            mode = TradingMode.fromString(mode),
            universeLimit = universeLimit
        )
    }

    @PostMapping("/autopilot/decisions")
    fun logAutopilotDecision(
        @RequestBody request: GuidedAutopilotDecisionLogRequest
    ): GuidedAutopilotDecisionLogResponse {
        return try {
            guidedTradingService.logAutopilotDecision(request)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "요청값 오류")
        }
    }

    @GetMapping("/autopilot/performance")
    fun getAutopilotPerformance(
        @RequestParam(defaultValue = "30") windowDays: Int,
        @RequestParam(required = false) strategyCodePrefix: String?
    ): GuidedAutopilotPerformanceResponse {
        return guidedTradingService.getAutopilotPerformance(windowDays, strategyCodePrefix)
    }
}
