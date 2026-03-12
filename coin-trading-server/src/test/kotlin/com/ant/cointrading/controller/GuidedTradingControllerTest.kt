package com.ant.cointrading.controller

import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedAdoptPositionRequest
import com.ant.cointrading.guided.GuidedAiScalpFeaturePack
import com.ant.cointrading.guided.GuidedAiScalpRecommendationSummary
import com.ant.cointrading.guided.GuidedAiScalpScanMarketView
import com.ant.cointrading.guided.GuidedAiScalpScanResponse
import com.ant.cointrading.guided.GuidedAutopilotCandidateView
import com.ant.cointrading.guided.GuidedAutopilotEvent
import com.ant.cointrading.guided.GuidedAutopilotLiveResponse
import com.ant.cointrading.guided.GuidedAutopilotOpportunitiesResponse
import com.ant.cointrading.guided.GuidedAutopilotOpportunityView
import com.ant.cointrading.guided.GuidedAutopilotOpportunityProfile
import com.ant.cointrading.guided.GuidedAutopilotDecisionStats
import com.ant.cointrading.guided.GuidedAutopilotDecisionLogRequest
import com.ant.cointrading.guided.GuidedAutopilotDecisionLogResponse
import com.ant.cointrading.guided.GuidedAutopilotPerformanceResponse
import com.ant.cointrading.guided.GuidedStrategyCodeSummary
import com.ant.cointrading.guided.GuidedClosedTradeView
import com.ant.cointrading.guided.GuidedDailyStats
import com.ant.cointrading.guided.GuidedPnlReconcileItem
import com.ant.cointrading.guided.GuidedPnlReconcileResult
import com.ant.cointrading.guided.GuidedTradingService
import com.ant.cointrading.guided.GuidedTradeView
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import com.ant.cointrading.service.OrderLifecycleEvent
import com.ant.cointrading.service.OrderLifecycleGroupSummary
import com.ant.cointrading.service.OrderLifecycleSummary

@DisplayName("GuidedTradingController")
class GuidedTradingControllerTest {

    private val guidedTradingService: GuidedTradingService = mock()
    private val controller = GuidedTradingController(guidedTradingService)

    @Test
    @DisplayName("startTrading은 서비스 입력 오류를 400으로 변환한다")
    fun startTradingMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.startAutoTrading(any()))
            .thenThrow(IllegalArgumentException("잘못된 주문"))

        val exception = assertThrows<ResponseStatusException> {
            controller.startTrading(
                GuidedStartRequest(
                    market = "KRW-BTC",
                    amountKrw = 10000,
                )
            )
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    @DisplayName("partialTakeProfit은 서비스 입력 오류를 400으로 변환한다")
    fun partialTakeProfitMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.partialTakeProfit("KRW-BTC", -1.0))
            .thenThrow(IllegalArgumentException("ratio 오류"))

        val exception = assertThrows<ResponseStatusException> {
            controller.partialTakeProfit("KRW-BTC", -1.0)
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    @DisplayName("adoptPosition은 서비스 입력 오류를 400으로 변환한다")
    fun adoptPositionMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.adoptExternalPosition(any()))
            .thenThrow(IllegalArgumentException("adopt 오류"))

        val exception = assertThrows<ResponseStatusException> {
            controller.adoptPosition(GuidedAdoptPositionRequest(market = "KRW-BTC"))
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    @DisplayName("ai-scalp/scan은 서비스 응답 스키마를 그대로 반환한다")
    fun getAiScalpScanReturnsServicePayload() {
        val expected = GuidedAiScalpScanResponse(
            generatedAt = Instant.parse("2026-03-06T01:00:00Z"),
            interval = "minute1",
            universeLimit = 36,
            strategyCodePrefix = "AI_SCALP_TRADER",
            positions = emptyList(),
            markets = listOf(
                GuidedAiScalpScanMarketView(
                    market = "KRW-BTC",
                    koreanName = "비트코인",
                    tradePrice = 103_000_000.0,
                    changeRate = 1.2,
                    turnover = 650_000_000_000.0,
                    liquidityRank = 1,
                    recommendation = GuidedAiScalpRecommendationSummary(
                        currentPrice = 103_000_000.0,
                        recommendedEntryPrice = 102_800_000.0,
                        stopLossPrice = 102_200_000.0,
                        takeProfitPrice = 103_700_000.0,
                        recommendedEntryWinRate = 61.5,
                        marketEntryWinRate = 58.0,
                        riskRewardRatio = 1.45,
                        confidence = 67.0,
                        suggestedOrderType = "MARKET",
                        rationale = listOf("flow strong"),
                        expectancyPct = 0.08
                    ),
                    featurePack = GuidedAiScalpFeaturePack(
                        marketCapFlowScore = 84.0,
                        turnoverKrw = 650_000_000_000.0,
                        changeRate = 1.2,
                        riskRewardRatio = 1.45,
                        recommendedEntryWinRate = 61.5,
                        marketEntryWinRate = 58.0,
                        entryGapPct = 0.19,
                        expectancyPct = 0.08,
                        confidence = 67.0,
                        crowdFlowScore = 78.0,
                        spreadPercent = 0.04,
                        bidImbalance = 0.15,
                        notionalSpikeRatio = 2.7
                    )
                )
            )
        )
        whenever(guidedTradingService.getAiScalpScan(any(), any(), any(), anyOrNull())).thenReturn(expected)

        val actual = controller.getAiScalpScan(
            interval = "minute1",
            universeLimit = 36,
            strategyCodePrefix = "AI_SCALP_TRADER",
            markets = null
        )

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("stats/today는 strategyCodePrefix를 서비스로 전달한다")
    fun getTodayStatsPassesStrategyCodePrefix() {
        val expected = GuidedDailyStats(
            totalTrades = 2,
            wins = 1,
            losses = 1,
            totalPnlKrw = 1200.0,
            avgPnlPercent = 0.45,
            winRate = 50.0,
            openPositionCount = 1,
            totalInvestedKrw = 10000.0,
            trades = listOf(
                GuidedClosedTradeView(
                    tradeId = 11L,
                    market = "KRW-BTC",
                    averageEntryPrice = 100000000.0,
                    averageExitPrice = 100800000.0,
                    entryQuantity = 0.0001,
                    realizedPnl = 800.0,
                    realizedPnlPercent = 0.8,
                    dcaCount = 0,
                    halfTakeProfitDone = false,
                    createdAt = Instant.parse("2026-03-07T00:10:00Z"),
                    closedAt = Instant.parse("2026-03-07T00:15:00Z"),
                    exitReason = "AI_EXIT"
                )
            )
        )
        whenever(guidedTradingService.getTodayStats(org.mockito.kotlin.eq("AI_SCALP_TRADER"))).thenReturn(expected)

        val actual = controller.getTodayStats("AI_SCALP_TRADER")

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("stopTrading은 exitReason을 서비스로 전달한다")
    fun stopTradingPassesExitReason() {
        val expected = GuidedTradeView(
            tradeId = 91L,
            market = "KRW-BTC",
            status = "CLOSED",
            entryOrderType = "MARKET",
            entryOrderId = "entry-1",
            averageEntryPrice = 100000000.0,
            currentPrice = 100100000.0,
            entryQuantity = 0.0001,
            remainingQuantity = 0.0,
            stopLossPrice = 99500000.0,
            takeProfitPrice = 100800000.0,
            trailingActive = false,
            trailingPeakPrice = null,
            trailingStopPrice = null,
            dcaCount = 0,
            maxDcaCount = 0,
            halfTakeProfitDone = false,
            unrealizedPnlPercent = 0.0,
            realizedPnl = 12.0,
            realizedPnlPercent = 0.12,
            lastAction = "CLOSED_AI_TIME_STOP",
            recommendationReason = null,
            entrySource = "AI_SCALP",
            strategyCode = "AI_SCALP_TRADER",
            createdAt = Instant.parse("2026-03-07T00:00:00Z"),
            updatedAt = Instant.parse("2026-03-07T00:20:00Z"),
            closedAt = Instant.parse("2026-03-07T00:20:00Z"),
            exitReason = "AI_TIME_STOP"
        )
        whenever(guidedTradingService.stopAutoTrading("KRW-BTC", "AI_TIME_STOP")).thenReturn(expected)

        val actual = controller.stopTrading("KRW-BTC", "AI_TIME_STOP")

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/live는 서비스 응답 스키마를 그대로 반환한다")
    fun getAutopilotLiveReturnsServicePayload() {
        val expected = GuidedAutopilotLiveResponse(
            orderSummary = OrderLifecycleSummary(
                total = OrderLifecycleGroupSummary(
                    buyRequested = 3,
                    buyFilled = 2,
                    sellRequested = 1,
                    sellFilled = 1,
                    pending = 1,
                    cancelled = 0
                ),
                groups = mapOf(
                    "GUIDED" to OrderLifecycleGroupSummary(
                        buyRequested = 2,
                        buyFilled = 1,
                        sellRequested = 1,
                        sellFilled = 1,
                        pending = 0,
                        cancelled = 0
                    )
                )
            ),
            orderEvents = listOf(
                OrderLifecycleEvent(
                    id = 1L,
                    orderId = "order-1",
                    market = "KRW-BTC",
                    side = "BUY",
                    eventType = "BUY_REQUESTED",
                    strategyGroup = "GUIDED",
                    strategyCode = "GUIDED_TRADING",
                    price = 93000000.0,
                    quantity = 0.0002,
                    message = "requested",
                    createdAt = Instant.parse("2026-02-24T01:00:00Z")
                )
            ),
            autopilotEvents = listOf(
                GuidedAutopilotEvent(
                    market = "KRW-BTC",
                    eventType = "BUY_REQUESTED",
                    level = "INFO",
                    message = "ok",
                    createdAt = Instant.parse("2026-02-24T01:00:00Z")
                )
            ),
            candidates = listOf(
                GuidedAutopilotCandidateView(
                    market = "KRW-BTC",
                    koreanName = "비트코인",
                    recommendedEntryWinRate = 68.5,
                    marketEntryWinRate = 62.1,
                    stage = "RULE_PASS",
                    reason = "pass"
                )
            ),
            thresholdMode = "DYNAMIC_P70",
            appliedRecommendedWinRateThreshold = 60.0,
            requestedMinRecommendedWinRate = null,
            decisionStats = GuidedAutopilotDecisionStats(
                rulePass = 1,
                ruleFail = 0,
                llmReject = 0,
                entered = 1,
                pendingTimeout = 0
            ),
            strategyCodeSummary = mapOf(
                "GUIDED_TRADING" to GuidedStrategyCodeSummary(
                    buyRequested = 2,
                    buyFilled = 1,
                    sellRequested = 1,
                    sellFilled = 1,
                    failed = 0,
                    cancelled = 0
                )
            )
        )
        whenever(
            guidedTradingService.getAutopilotLive(
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any()
            )
        ).thenReturn(expected)

        val actual = controller.getAutopilotLive(
            interval = "minute30",
            mode = "SWING",
            thresholdMode = null,
            minMarketWinRate = null,
            minRecommendedWinRate = null,
            strategyCodePrefix = null,
            opportunityProfile = null
        )

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/live는 strategyCodePrefix를 서비스로 전달한다")
    fun getAutopilotLivePassesStrategyCodePrefix() {
        val expected = GuidedAutopilotLiveResponse(
            orderSummary = OrderLifecycleSummary(
                total = OrderLifecycleGroupSummary(0, 0, 0, 0, 0, 0),
                groups = emptyMap()
            ),
            orderEvents = emptyList(),
            autopilotEvents = emptyList(),
            candidates = emptyList(),
            thresholdMode = "DYNAMIC_P70",
            appliedRecommendedWinRateThreshold = 0.0,
            requestedMinRecommendedWinRate = null,
            decisionStats = GuidedAutopilotDecisionStats(
                rulePass = 0,
                ruleFail = 0,
                llmReject = 0,
                entered = 0,
                pendingTimeout = 0
            ),
            strategyCodeSummary = emptyMap(),
        )
        whenever(
            guidedTradingService.getAutopilotLive(
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                org.mockito.kotlin.eq("GUIDED_AUTOPILOT_SCALP"),
                any()
            )
        ).thenReturn(expected)

        val actual = controller.getAutopilotLive(
            interval = "minute1",
            mode = "SCALP",
            thresholdMode = null,
            minMarketWinRate = null,
            minRecommendedWinRate = null,
            strategyCodePrefix = "GUIDED_AUTOPILOT_SCALP",
            opportunityProfile = null
        )

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/opportunities는 서비스 응답 스키마를 그대로 반환한다")
    fun getAutopilotOpportunitiesReturnsServicePayload() {
        val expected = GuidedAutopilotOpportunitiesResponse(
            generatedAt = Instant.parse("2026-02-24T01:00:00Z"),
            primaryInterval = "minute1",
            confirmInterval = "minute10",
            mode = "SCALP",
            appliedUniverseLimit = 15,
            opportunities = listOf(
                GuidedAutopilotOpportunityView(
                    market = "KRW-BTC",
                    koreanName = "비트코인",
                    recommendedEntryWinRate1m = 63.2,
                    recommendedEntryWinRate10m = 60.1,
                    marketEntryWinRate1m = 58.4,
                    marketEntryWinRate10m = 56.0,
                    riskReward1m = 1.24,
                    entryGapPct1m = 0.22,
                    expectancyPct = 0.31,
                    score = 71.4,
                    stage = "AUTO_PASS",
                    reason = "score 71.4 / expectancy 0.310%"
                )
            )
        )
        whenever(guidedTradingService.getAutopilotOpportunities(any(), any(), any(), any(), any())).thenReturn(expected)

        val actual = controller.getAutopilotOpportunities(
            interval = "minute1",
            confirmInterval = "minute10",
            mode = "SCALP",
            universeLimit = 15,
            opportunityProfile = null
        )

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/opportunities는 opportunityProfile을 서비스로 전달한다")
    fun getAutopilotOpportunitiesPassesOpportunityProfile() {
        val expected = GuidedAutopilotOpportunitiesResponse(
            generatedAt = Instant.parse("2026-02-24T01:00:00Z"),
            primaryInterval = "minute1",
            confirmInterval = "minute10",
            mode = "SCALP",
            appliedUniverseLimit = 15,
            opportunityProfile = "CROWD_PRESSURE",
            opportunities = emptyList()
        )
        whenever(
            guidedTradingService.getAutopilotOpportunities(
                any(),
                any(),
                any(),
                any(),
                org.mockito.kotlin.eq(GuidedAutopilotOpportunityProfile.CROWD_PRESSURE)
            )
        ).thenReturn(expected)

        val actual = controller.getAutopilotOpportunities(
            interval = "minute1",
            confirmInterval = "minute10",
            mode = "SCALP",
            universeLimit = 15,
            opportunityProfile = "CROWD_PRESSURE"
        )

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("reconcile/closed는 서비스 결과를 그대로 반환한다")
    fun reconcileClosedReturnsServicePayload() {
        val expected = GuidedPnlReconcileResult(
            windowDays = 30,
            dryRun = true,
            scannedTrades = 12,
            updatedTrades = 6,
            unchangedTrades = 6,
            highConfidenceTrades = 8,
            lowConfidenceTrades = 4,
            sample = listOf(
                GuidedPnlReconcileItem(
                    tradeId = 1L,
                    market = "KRW-BTC",
                    confidence = "HIGH",
                    reason = "events 기반",
                    recalculated = true,
                    realizedPnl = 1234.56,
                    realizedPnlPercent = 1.23
                )
            )
        )
        whenever(guidedTradingService.reconcileClosedTrades(30, true)).thenReturn(expected)

        val actual = controller.reconcileClosedTrades(windowDays = 30, dryRun = true)

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/decisions는 서비스 응답을 반환한다")
    fun logAutopilotDecisionReturnsServicePayload() {
        val request = GuidedAutopilotDecisionLogRequest(
            runId = "run-1",
            market = "KRW-BTC",
            stage = "AUTO_PASS",
            approve = true,
            executed = false
        )
        val expected = GuidedAutopilotDecisionLogResponse(
            accepted = true,
            decisionId = 42L,
            createdAt = Instant.parse("2026-02-24T01:00:00Z")
        )
        whenever(guidedTradingService.logAutopilotDecision(any())).thenReturn(expected)

        val actual = controller.logAutopilotDecision(request)

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/performance는 서비스 응답을 그대로 반환한다")
    fun getAutopilotPerformanceReturnsServicePayload() {
        val expected = GuidedAutopilotPerformanceResponse(
            windowDays = 30,
            from = Instant.parse("2026-01-25T00:00:00Z"),
            to = Instant.parse("2026-02-24T00:00:00Z"),
            trades = 28,
            winRate = 57.1,
            netPnlKrw = 124000.0,
            netReturnPercent = 3.2,
            sharpe = 1.34,
            maxDrawdownPercent = 2.7,
            gateEligible = true,
            gateReason = "증액 게이트 통과"
        )
        whenever(guidedTradingService.getAutopilotPerformance(30, null)).thenReturn(expected)

        val actual = controller.getAutopilotPerformance(30, null)

        kotlin.test.assertEquals(expected, actual)
    }

    @Test
    @DisplayName("autopilot/performance는 strategyCodePrefix를 서비스로 전달한다")
    fun getAutopilotPerformancePassesStrategyCodePrefix() {
        val expected = GuidedAutopilotPerformanceResponse(
            windowDays = 30,
            from = Instant.parse("2026-01-25T00:00:00Z"),
            to = Instant.parse("2026-02-24T00:00:00Z"),
            trades = 7,
            winRate = 57.1,
            netPnlKrw = 24000.0,
            netReturnPercent = 1.2,
            sharpe = 0.8,
            maxDrawdownPercent = 1.9,
            gateEligible = false,
            gateReason = "샘플 부족"
        )
        whenever(guidedTradingService.getAutopilotPerformance(30, "GUIDED_AUTOPILOT_SCALP")).thenReturn(expected)

        val actual = controller.getAutopilotPerformance(30, "GUIDED_AUTOPILOT_SCALP")

        kotlin.test.assertEquals(expected, actual)
    }
}
