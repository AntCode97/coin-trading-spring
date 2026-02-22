package com.ant.cointrading.controller

import com.ant.cointrading.guided.GuidedChartResponse
import com.ant.cointrading.guided.GuidedMarketItem
import com.ant.cointrading.guided.GuidedRecommendation
import com.ant.cointrading.guided.GuidedMarketSortBy
import com.ant.cointrading.guided.GuidedCopilotRequest
import com.ant.cointrading.guided.GuidedCopilotResponse
import com.ant.cointrading.guided.GuidedRealtimeTickerView
import com.ant.cointrading.guided.GuidedSortDirection
import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedTradeView
import com.ant.cointrading.guided.GuidedTradingCopilotService
import com.ant.cointrading.guided.GuidedTradingService
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
    private val guidedTradingService: GuidedTradingService,
    private val guidedTradingCopilotService: GuidedTradingCopilotService
) {

    @GetMapping("/markets")
    fun getMarketBoard(
        @RequestParam(defaultValue = "TURNOVER") sortBy: GuidedMarketSortBy,
        @RequestParam(defaultValue = "DESC") sortDirection: GuidedSortDirection
    ): List<GuidedMarketItem> {
        return guidedTradingService.getMarketBoard(sortBy, sortDirection)
    }

    @GetMapping("/chart")
    fun getChart(
        @RequestParam market: String,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "120") count: Int
    ): GuidedChartResponse {
        return guidedTradingService.getChartData(market.uppercase(), interval, count)
    }

    @GetMapping("/recommendation")
    fun getRecommendation(
        @RequestParam market: String,
        @RequestParam(defaultValue = "minute30") interval: String,
        @RequestParam(defaultValue = "120") count: Int
    ): GuidedRecommendation {
        return guidedTradingService.getRecommendation(market.uppercase(), interval, count)
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
        return guidedTradingService.stopAutoTrading(market.uppercase())
    }

    @GetMapping("/position/{market}")
    fun getPosition(@PathVariable market: String): GuidedTradeView? {
        return guidedTradingService.getActivePosition(market.uppercase())
    }

    @PostMapping("/copilot/analyze")
    fun analyzeWithCopilot(@RequestBody request: GuidedCopilotRequest): GuidedCopilotResponse {
        return guidedTradingCopilotService.analyze(request)
    }
}
