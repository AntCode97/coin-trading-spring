package com.ant.cointrading.mcp.tool

import com.ant.cointrading.repository.DailyStatsRepository
import com.ant.cointrading.repository.TradeRepository
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

/**
 * 거래 성과 분석 MCP 도구
 *
 * LLM이 거래 성과를 분석하고 전략 조정 결정을 내릴 때 사용한다.
 */
@Component
class PerformanceTools(
    private val tradeRepository: TradeRepository,
    private val dailyStatsRepository: DailyStatsRepository
) {

    @McpTool(description = "최근 거래 기록을 조회합니다.")
    fun getRecentTrades(
        @McpToolParam(description = "조회할 거래 수 (최대 100)") limit: Int
    ): List<Map<String, Any?>> {
        val trades = tradeRepository.findTop100ByOrderByCreatedAtDesc()
            .take(limit.coerceAtMost(100))

        return trades.map { trade ->
            mapOf(
                "id" to trade.id,
                "market" to trade.market,
                "side" to trade.side,
                "price" to trade.price,
                "quantity" to trade.quantity,
                "pnl" to trade.pnl,
                "pnlPercent" to trade.pnlPercent,
                "strategy" to trade.strategy,
                "regime" to trade.regime,
                "reason" to trade.reason,
                "createdAt" to trade.createdAt.toString()
            )
        }
    }

    @McpTool(description = "전체 거래 성과 요약을 조회합니다.")
    fun getPerformanceSummary(
        @McpToolParam(description = "분석 기간 (일): 7, 30, 90 등") days: Int
    ): Map<String, Any> {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val trades = tradeRepository.findByCreatedAtBetween(since, Instant.now())

        if (trades.isEmpty()) {
            return mapOf(
                "period" to "${days}일",
                "totalTrades" to 0,
                "message" to "해당 기간에 거래 기록이 없습니다."
            )
        }

        val sellTrades = trades.filter { it.side == "SELL" && it.pnl != null }
        val totalPnl = sellTrades.sumOf { it.pnl ?: 0.0 }
        val winTrades = sellTrades.count { (it.pnl ?: 0.0) > 0 }
        val lossTrades = sellTrades.count { (it.pnl ?: 0.0) < 0 }
        val winRate = if (sellTrades.isNotEmpty()) winTrades.toDouble() / sellTrades.size * 100 else 0.0

        // 평균 수익/손실
        val avgWin = sellTrades.filter { (it.pnl ?: 0.0) > 0 }
            .takeIf { it.isNotEmpty() }
            ?.map { it.pnl!! }
            ?.average() ?: 0.0
        val avgLoss = sellTrades.filter { (it.pnl ?: 0.0) < 0 }
            .takeIf { it.isNotEmpty() }
            ?.map { it.pnl!! }
            ?.average() ?: 0.0

        // Profit Factor
        val grossProfit = sellTrades.filter { (it.pnl ?: 0.0) > 0 }.sumOf { it.pnl ?: 0.0 }
        val grossLoss = kotlin.math.abs(sellTrades.filter { (it.pnl ?: 0.0) < 0 }.sumOf { it.pnl ?: 0.0 })
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else 0.0

        // 전략별 통계
        val strategyStats = trades.groupBy { it.strategy }.mapValues { (_, strategyTrades) ->
            val strategySells = strategyTrades.filter { it.side == "SELL" && it.pnl != null }
            mapOf(
                "trades" to strategyTrades.size,
                "pnl" to strategySells.sumOf { it.pnl ?: 0.0 },
                "winRate" to if (strategySells.isNotEmpty())
                    strategySells.count { (it.pnl ?: 0.0) > 0 }.toDouble() / strategySells.size * 100
                else 0.0
            )
        }

        return mapOf(
            "period" to "${days}일",
            "totalTrades" to trades.size,
            "sellTrades" to sellTrades.size,
            "totalPnl" to String.format("%.0f", totalPnl),
            "winTrades" to winTrades,
            "lossTrades" to lossTrades,
            "winRate" to String.format("%.1f", winRate),
            "avgWin" to String.format("%.0f", avgWin),
            "avgLoss" to String.format("%.0f", avgLoss),
            "profitFactor" to String.format("%.2f", profitFactor),
            "strategyBreakdown" to strategyStats
        )
    }

    @McpTool(description = "특정 전략의 성과를 분석합니다.")
    fun getStrategyPerformance(
        @McpToolParam(description = "전략 이름: DCA, GRID, MEAN_REVERSION") strategy: String,
        @McpToolParam(description = "분석 기간 (일)") days: Int
    ): Map<String, Any> {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val trades = tradeRepository.findByStrategyAndCreatedAtBetween(strategy.uppercase(), since, Instant.now())

        if (trades.isEmpty()) {
            return mapOf(
                "strategy" to strategy,
                "period" to "${days}일",
                "message" to "해당 전략의 거래 기록이 없습니다."
            )
        }

        val sellTrades = trades.filter { it.side == "SELL" && it.pnl != null }
        val pnlList = sellTrades.mapNotNull { it.pnlPercent }

        // Sharpe Ratio (단순화)
        val avgReturn = if (pnlList.isNotEmpty()) pnlList.average() else 0.0
        val stdReturn = if (pnlList.size > 1) {
            sqrt(pnlList.map { (it - avgReturn) * (it - avgReturn) }.average())
        } else 0.0
        val sharpeRatio = if (stdReturn > 0) avgReturn / stdReturn * sqrt(252.0) else 0.0

        // 최대 낙폭 계산
        var peak = 0.0
        var maxDrawdown = 0.0
        var cumReturn = 0.0
        for (trade in sellTrades) {
            cumReturn += trade.pnl ?: 0.0
            if (cumReturn > peak) peak = cumReturn
            val drawdown = if (peak > 0) (peak - cumReturn) / peak * 100 else 0.0
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        return mapOf(
            "strategy" to strategy,
            "period" to "${days}일",
            "totalTrades" to trades.size,
            "totalPnl" to String.format("%.0f", sellTrades.sumOf { it.pnl ?: 0.0 }),
            "winRate" to String.format("%.1f",
                if (sellTrades.isNotEmpty())
                    sellTrades.count { (it.pnl ?: 0.0) > 0 }.toDouble() / sellTrades.size * 100
                else 0.0
            ),
            "sharpeRatio" to String.format("%.2f", sharpeRatio),
            "maxDrawdown" to String.format("%.1f", maxDrawdown),
            "avgPnlPercent" to String.format("%.2f", avgReturn)
        )
    }

    @McpTool(description = "일별 성과 추이를 조회합니다.")
    fun getDailyPerformance(
        @McpToolParam(description = "조회할 일수 (최대 30)") days: Int
    ): List<Map<String, Any>> {
        val stats = dailyStatsRepository.findTop30ByOrderByDateDesc()
            .take(days.coerceAtMost(30))

        return stats.map { stat ->
            mapOf(
                "date" to stat.date,
                "market" to stat.market,
                "totalTrades" to stat.totalTrades,
                "winRate" to String.format("%.1f", stat.winRate),
                "totalPnl" to String.format("%.0f", stat.totalPnl),
                "maxDrawdown" to String.format("%.1f", stat.maxDrawdown)
            )
        }
    }

    @McpTool(description = "전략 최적화를 위한 분석 보고서를 생성합니다.")
    fun getOptimizationReport(): Map<String, Any> {
        val last30Days = Instant.now().minus(30, ChronoUnit.DAYS)
        val last7Days = Instant.now().minus(7, ChronoUnit.DAYS)

        val trades30 = tradeRepository.findByCreatedAtBetween(last30Days, Instant.now())
        val trades7 = tradeRepository.findByCreatedAtBetween(last7Days, Instant.now())

        // 전략별 성과 비교
        val strategyComparison = listOf("DCA", "GRID", "MEAN_REVERSION").map { strategy ->
            val strategyTrades = trades30.filter { it.strategy == strategy }
            val sellTrades = strategyTrades.filter { it.side == "SELL" && it.pnl != null }

            mapOf(
                "strategy" to strategy,
                "trades30d" to strategyTrades.size,
                "pnl30d" to sellTrades.sumOf { it.pnl ?: 0.0 },
                "winRate30d" to if (sellTrades.isNotEmpty())
                    sellTrades.count { (it.pnl ?: 0.0) > 0 }.toDouble() / sellTrades.size * 100
                else 0.0
            )
        }

        // 최근 7일 트렌드
        val recentTrend = trades7.groupBy { it.strategy }.mapValues { (_, trades) ->
            trades.filter { it.side == "SELL" }.sumOf { it.pnl ?: 0.0 }
        }

        // 추천 사항 생성
        val bestStrategy = strategyComparison.maxByOrNull {
            (it["pnl30d"] as? Double) ?: 0.0
        }?.get("strategy") ?: "MEAN_REVERSION"

        val recommendations = mutableListOf<String>()

        strategyComparison.forEach { stat ->
            val winRate = stat["winRate30d"] as? Double ?: 0.0
            val pnl = stat["pnl30d"] as? Double ?: 0.0
            val strategy = stat["strategy"] as String

            if (winRate < 40 && pnl < 0) {
                recommendations.add("$strategy 전략의 승률이 낮음(${String.format("%.1f", winRate)}%). 파라미터 조정 권장.")
            }
            if (pnl < -50000) {
                recommendations.add("$strategy 전략의 손실이 큼. 해당 전략 비활성화 고려.")
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("현재 전략 성과가 양호합니다. 기존 설정 유지 권장.")
        }

        return mapOf(
            "generatedAt" to LocalDate.now().format(DateTimeFormatter.ISO_DATE),
            "analysisPeriod" to "최근 30일",
            "totalTrades" to trades30.size,
            "strategyComparison" to strategyComparison,
            "recentTrend7d" to recentTrend,
            "recommendedStrategy" to bestStrategy,
            "recommendations" to recommendations
        )
    }
}
