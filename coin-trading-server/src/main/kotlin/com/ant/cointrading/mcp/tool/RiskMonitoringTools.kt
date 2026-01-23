package com.ant.cointrading.mcp.tool

import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.DailyLossTracker
import org.springaicommunity.mcp.annotation.McpTool
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        val globalStatus: String,           // GLOBAL_OPEN, GLOBAL_BLOCKED
        val marketStatuses: Map<String, MarketStatus>,
        val blockedMarkets: List<String>,
        val canTradeGlobally: Boolean
    )

    /** 마켓별 서킷브레이커 상태 */
    data class MarketStatus(
        val state: String,                  // OPEN, BLOCKED
        val consecutiveLosses: Int,
        val maxConsecutiveLosses: Int,
        val lastResetTime: String,
        val executionFailures: Int,
        val slippageEvents: Int
    )

    /** 일일 손실 추적 상태 */
    data class DailyLossStatus(
        val date: String,
        val totalLoss: Double,
        val maxLossLimit: Double,
        val lossPercent: Double,
        val isBlocked: Boolean,
        val remainingBudget: Double,
        val trades: Int,
        val warnings: List<String>
    )

    /** 리스크 요약 */
    data class RiskSummary(
        val timestamp: String,
        val overallRisk: String,           // LOW, MEDIUM, HIGH, CRITICAL
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
        val globalState = circuitBreaker.getGlobalState()
        val marketStates = circuitBreaker.getAllMarketStates()

        val marketStatuses = marketStates.mapValues { (_, state) ->
            MarketStatus(
                state = state.state.name,
                consecutiveLosses = state.consecutiveLosses,
                maxConsecutiveLosses = state.maxConsecutiveLosses,
                lastResetTime = state.lastResetTime.toString(),
                executionFailures = state.executionFailures,
                slippageEvents = state.slippageEvents
            )
        }

        val blockedMarkets = marketStates
            .filter { (_, state) -> state.state == CircuitBreaker.MarketState.BLOCKED }
            .keys
            .toList()

        return CircuitBreakerStatus(
            globalStatus = globalState.name,
            marketStatuses = marketStatuses,
            blockedMarkets = blockedMarkets,
            canTradeGlobally = globalState == CircuitBreaker.GlobalState.GLOBAL_OPEN
        )
    }

    @McpTool(description = """
        일일 손실 추적 상태를 조회합니다.

        현재 일일 손실, 한도 대비 사용률, 거래 차단 여부 등을 반환합니다.
    """)
    fun getDailyLossStatus(): DailyLossStatus {
        val now = Instant.now()
        val startOfDay = now.truncatedTo(ChronoUnit.DAYS)

        // DailyLossTracker에서 상태 조회
        val capital = dailyLossTracker.getCapital()
        val currentPnl = dailyLossTracker.getCurrentDailyPnl()

        // 일일 손실 한도 (자본의 5%)
        val maxLossLimit = capital.toDouble() * 0.05

        // 손실률 계산
        val lossPercent = if (maxLossLimit > 0) {
            kotlin.math.abs(currentPnl) / maxLossLimit * 100
        } else 0.0

        // 거래 차단 여부
        val isBlocked = dailyLossTracker.isDailyLossExceeded()

        // 남은 예산
        val remainingBudget = maxLossLimit - kotlin.math.abs(currentPnl)

        // 거래 횟수
        val trades = dailyLossTracker.getTradeCount()

        // 경고 메시지
        val warnings = mutableListOf<String>()
        if (lossPercent > 50) {
            warnings.add("일일 손실 한도의 50% 이상 사용됨. 새로운 진입을 중단하세요.")
        }
        if (lossPercent > 80) {
            warnings.add("일일 손실 한도의 80% 이상 사용됨. 즉시 모든 포지션을 청산하세요.")
        }
        if (trades > 30) {
            warnings.add("오늘 거래 횟수가 30회를 초과했습니다. 과도한 거래를 피하세요.")
        }

        return DailyLossStatus(
            date = startOfDay.toString().substring(0, 10),
            totalLoss = currentPnl,
            maxLossLimit = maxLossLimit,
            lossPercent = lossPercent,
            isBlocked = isBlocked,
            remainingBudget = remainingBudget,
            trades = trades,
            warnings = warnings
        )
    }

    @McpTool(description = """
        종합 리스크 요약을 조회합니다.

        서킷브레이커, 일일 손실, 포지션 리스크 등을 종합적으로 분석합니다.
    """)
    fun getRiskSummary(): RiskSummary {
        val now = Instant.now()

        // 서킷브레이커 상태
        val cbStatus = getCircuitBreakerStatus()

        // 일일 손실 상태
        val dlStatus = getDailyLossStatus()

        // 전체 리스크 평가
        val overallRisk = when {
            cbStatus.globalStatus == "GLOBAL_BLOCKED" -> "CRITICAL"
            dlStatus.lossPercent > 80 -> "CRITICAL"
            dlStatus.lossPercent > 50 -> "HIGH"
            cbStatus.blockedMarkets.isNotEmpty() -> "MEDIUM"
            dlStatus.lossPercent > 25 -> "MEDIUM"
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
        if (cbStatus.blockedMarkets.isNotEmpty()) {
            recommendations.add("차단된 마켓: ${cbStatus.blockedMarkets.joinToString(", ")}. 다른 마켓을 고려하세요.")
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
        마켓별 리스크 수준을 평가합니다.

        각 마켓의 서킷브레이커 상태와 최근 성과를 바탕으로 리스크를 평가합니다.
    """)
    fun evaluateMarketRisk(
        @McpToolParam(description = "마켓 목록 (쉼표로 구분, 예: KRW-BTC,KRW-ETH)") markets: String
    ): Map<String, String> {
        val marketList = markets.split(",").map { it.trim().uppercase() }
        val riskLevels = mutableMapOf<String, String>()

        val marketStates = circuitBreaker.getAllMarketStates()

        marketList.forEach { market ->
            val state = marketStates[market]
            val riskLevel = when {
                state == null || state.state == CircuitBreaker.MarketState.OPEN -> {
                    // 서킷브레이커가 없는 마켓
                    // TODO: 최근 성과 기반 리스크 평가
                    "LOW"
                }
                state.state == CircuitBreaker.MarketState.BLOCKED -> {
                    when {
                        state.consecutiveLosses >= 3 -> "HIGH"
                        state.consecutiveLosses >= 2 -> "MEDIUM"
                        else -> "LOW"
                    }
                }
                else -> "UNKNOWN"
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
        return mapOf(
            "dailyLossLimit" to "5% of capital",
            "maxConsecutiveLosses" to 3,
            "slippageWarningThreshold" to "0.5%",
            "slippageCriticalThreshold" to "2.0%",
            "capital" to dailyLossTracker.getCapital(),
            "circuitBreakerResetCooldownMinutes" to 60
        )
    }
}
