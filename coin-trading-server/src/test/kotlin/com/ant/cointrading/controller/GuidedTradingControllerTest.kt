package com.ant.cointrading.controller

import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedAdoptPositionRequest
import com.ant.cointrading.guided.GuidedAutopilotCandidateView
import com.ant.cointrading.guided.GuidedAutopilotEvent
import com.ant.cointrading.guided.GuidedAutopilotLiveResponse
import com.ant.cointrading.guided.GuidedAutopilotDecisionStats
import com.ant.cointrading.guided.GuidedStrategyCodeSummary
import com.ant.cointrading.guided.GuidedPnlReconcileItem
import com.ant.cointrading.guided.GuidedPnlReconcileResult
import com.ant.cointrading.guided.GuidedTradingService
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
        whenever(guidedTradingService.getAutopilotLive(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(expected)

        val actual = controller.getAutopilotLive(
            interval = "minute30",
            mode = "SWING",
            thresholdMode = null,
            minMarketWinRate = null,
            minRecommendedWinRate = null
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
}
