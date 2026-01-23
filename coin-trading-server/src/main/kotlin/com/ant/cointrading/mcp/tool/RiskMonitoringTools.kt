package com.ant.cointrading.mcp.tool

import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.DailyLossTracker
import org.springaicommunity.mcp.annotation.McpTool
import org.springframework.stereotype.Component

/**
 * 리스크 관리 상태 모니터링 MCP 도구
 *
 * 서킷브레이커, 일일 손실 한도, 포지션 리스크 등을 모니터링합니다.
 */
@Component
class RiskMonitoringTools(
    private val circuitBreaker: CircuitBreaker,
    private val dailyLossTracker: DailyLossTracker
) {

    // ========================================
    // 응답 Data Classes
    // ========================================

    /** 서킷브레이커 상태 */
    data class CircuitBreakerStatus(
        val globalOpen: Boolean,
        val globalReason: String,
        val marketStates: Map<String, MarketState>,
        val canTradeGlobally: Boolean
    )

    /** 마켓별 서킷브레이커 상태 */
    data class MarketState(
        val isOpen: Boolean,
        val consecutiveLosses: Int,
        val consecutiveExecutionFailures: Int,
        val consecutiveHighSlippage: Int,
        val reason: String
    )

    /** 일일 손실 추적 상태 */
    data class DailyLossStatus(
        val date: String,
        val totalPnl: Double,
        val totalPnlPercent: Double,
        val maxLossLimit: Double,
        val isLimitExceeded: Boolean,
        val remainingLossBudget: Double,
        val tradingAllowed: Boolean
    )

    /** 리스크 요약 */
    data class RiskSummary(
        val timestamp: String,
        val overallRisk: String,
        val circuitBreaker: CircuitBreakerStatus,
        val dailyLoss: DailyLossStatus,
        val recommendations: List<String>
    )

    // ========================================
    // 리스크 모니터링 도구
    // ========================================

    @McpTool(description = """
        서킷브레이커 상태를 조회합니다.

        전역 및 마켓별 서킷브레이커 상태, 차단된 마켓 목록 등을 반환합니다.
    """)
    fun getCircuitBreakerStatus(): CircuitBreakerStatus {
        val status = circuitBreaker.getStatus()
        val globalOpen = status["globalCircuitOpen"] as? Boolean ?: false
        val globalReason = status["globalCircuitReason"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val marketStatesMap = status["marketStates"] as? Map<String, Map<String, Any?>> ?: emptyMap()

        val marketStates = marketStatesMap.mapValues { (_, state) ->
            MarketState(
                isOpen = state["isOpen"] as? Boolean ?: false,
                consecutiveLosses = (state["consecutiveLosses"] as? Number)?.toInt() ?: 0,
                consecutiveExecutionFailures = (state["consecutiveExecutionFailures"] as? Number)?.toInt() ?: 0,
                consecutiveHighSlippage = (state["consecutiveHighSlippage"] as? Number)?.toInt() ?: 0,
                reason = state["reason"] as? String ?: ""
            )
        }

        val blockedMarkets = marketStates
            .filter { (_, state) -> state.isOpen }
            .keys
            .toList()

        return CircuitBreakerStatus(
            globalOpen = globalOpen,
            globalReason = globalReason,
            marketStates = marketStates,
            canTradeGlobally = !globalOpen
        )
    }

    @McpTool(description = """
        일일 손실 추적 상태를 조회합니다.

        현재 일일 손실, 한도 대비 사용률, 거래 차단 여부 등을 반환합니다.
    """)
    fun getDailyLossStatus(): DailyLossStatus {
        val status = dailyLossTracker.getDailyLossStatus()

        return DailyLossStatus(
            date = status.date.toString(),
            totalPnl = status.totalPnl.toDouble(),
            totalPnlPercent = status.totalPnlPercent,
            maxLossLimit = status.maxLossLimit,
            isLimitExceeded = status.isLimitExceeded,
            remainingLossBudget = status.remainingLossBudget,
            tradingAllowed = status.tradingAllowed
        )
    }

    @McpTool(description = """
        종합 리스크 요약을 조회합니다.

        서킷브레이커, 일일 손실, 포지션 리스크 등을 종합적으로 분석합니다.
    """)
    fun getRiskSummary(): RiskSummary {
        val now = java.time.Instant.now()

        // 서킷브레이커 상태
        val cbStatus = getCircuitBreakerStatus()

        // 일일 손실 상태
        val dlStatus = getDailyLossStatus()

        // 전체 리스크 평가
        val overallRisk = when {
            cbStatus.globalOpen -> "CRITICAL"
            dlStatus.isLimitExceeded -> "CRITICAL"
            cbStatus.marketStates.any { (_, state) -> state.isOpen } -> "HIGH"
            dlStatus.totalPnlPercent < -2.5 -> "MEDIUM"
            else -> "LOW"
        }

        // 권장사항
        val recommendations = mutableListOf<String>()

        when (overallRisk) {
            "CRITICAL" -> {
                recommendations.add("즉시 모든 거래를 중단하고 포지션을 청산하세요.")
                recommendations.add("서킷브레이커를 리셋하고 하루 이상 휴식하세요.")
            }
            "HIGH" -> {
                recommendations.add("새로운 진입을 중단하고 기존 포지션을 모니터링하세요.")
                recommendations.add("손실이 난 포지션부터 우선적으로 청산을 고려하세요.")
            }
            "MEDIUM" -> {
                recommendations.add("진입 빈도를 줄이고 포지션 사이즈를 축소하세요.")
                recommendations.add("손절가를 타이트하게 설정하여 추가 손실을 방지하세요.")
            }
            "LOW" -> {
                recommendations.add("현재 리스크 수준이 낮습니다. 정상적인 거래를 유지하세요.")
            }
        }

        // 서킷브레이커 관련 권장사항
        if (cbStatus.globalOpen) {
            recommendations.add("글로벌 서킷브레이커 발동: ${cbStatus.globalReason}")
        }

        return RiskSummary(
            timestamp = now.toString(),
            overallRisk = overallRisk,
            circuitBreaker = cbStatus,
            dailyLoss = dlStatus,
            recommendations = recommendations
        )
    }

    @McpTool(description = """
        특정 마켓의 리스크 수준을 평가합니다.

        각 마켓의 서킷브레이커 상태와 최근 성과를 바탕으로 리스크를 평가합니다.
    """)
    fun evaluateMarketRisk(
        markets: String
    ): Map<String, String> {
        val marketList = markets.split(",").map { it.trim().uppercase() }
        val riskLevels = mutableMapOf<String, String>()

        val cbStatus = getCircuitBreakerStatus()

        marketList.forEach { market ->
            val state = cbStatus.marketStates[market]
            val riskLevel = when {
                state == null || !state.isOpen -> {
                    // 서킷브레이커가 열리지 않은 마켓
                    "LOW"
                }
                state.consecutiveLosses >= 3 -> "HIGH"
                state.consecutiveLosses >= 2 -> "MEDIUM"
                else -> "LOW"
            }
            riskLevels[market] = riskLevel
        }

        return riskLevels
    }

    @McpTool(description = """
        리스크 관리 파라미터를 조회합니다.

        일일 손실 한도, 최대 연속 손실 횟수 등의 설정값을 반환합니다.
    """)
    fun getRiskParameters(): Map<String, Any> {
        val dlStatus = getDailyLossStatus()
        return mapOf(
            "dailyLossLimitPercent" to dlStatus.maxLossLimit,
            "maxConsecutiveLosses" to 3,
            "dailyLossExceeded" to dlStatus.isLimitExceeded,
            "tradingAllowed" to dlStatus.tradingAllowed
        )
    }
}
