package com.ant.cointrading.mcp.tool

import com.ant.cointrading.repository.DailyStatsRepository
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

// ========================================
// 응답 Data Classes
// ========================================

/** 거래 정보 */
data class TradeInfo(
    val id: Long?,
    val market: String,
    val side: String,
    val price: Double,
    val quantity: Double,
    val pnl: Double?,
    val pnlPercent: Double?,
    val strategy: String,
    val regime: String?,
    val reason: String,
    val createdAt: String
)

/** 전략별 통계 */
data class StrategyStats(
    val trades: Int,
    val pnl: Double,
    val winRate: Double
)

/** 성과 요약 */
data class PerformanceSummary(
    val period: String,
    val totalTrades: Int,
    val sellTrades: Int,
    val totalPnl: String,
    val winTrades: Int,
    val lossTrades: Int,
    val winRate: String,
    val avgWin: String,
    val avgLoss: String,
    val profitFactor: String,
    val strategyBreakdown: Map<String, StrategyStats>,
    val message: String? = null
)

/** 전략 성과 */
data class StrategyPerformance(
    val strategy: String,
    val period: String,
    val totalTrades: Int,
    val totalPnl: String,
    val winRate: String,
    val sharpeRatio: String,
    val maxDrawdown: String,
    val avgPnlPercent: String,
    val message: String? = null
)

/** 일별 성과 */
data class DailyPerformanceInfo(
    val date: String,
    val market: String,
    val totalTrades: Int,
    val winRate: String,
    val totalPnl: String,
    val maxDrawdown: String
)

/** 전략 비교 정보 */
data class StrategyComparison(
    val strategy: String,
    val trades30d: Int,
    val pnl30d: Double,
    val winRate30d: Double
)

/** 최적화 보고서 */
data class OptimizationReport(
    val generatedAt: String,
    val analysisPeriod: String,
    val totalTrades: Int,
    val strategyComparison: List<StrategyComparison>,
    val recentTrend7d: Map<String, Double>,
    val recommendedStrategy: String,
    val recommendations: List<String>
)

/**
 * 거래 성과 분석 MCP 도구
 *
 * LLM이 거래 성과를 분석하고 전략 조정 결정을 내릴 때 사용한다.
 */
@Component
class PerformanceTools(
    private val tradeRepository: TradeRepository,
    private val dailyStatsRepository: DailyStatsRepository,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository
) {

    @McpTool(description = "최근 거래 기록을 조회합니다.")
    fun getRecentTrades(
        @McpToolParam(description = "조회할 거래 수 (최대 100)") limit: Int
    ): List<TradeInfo> {
        val trades = tradeRepository.findTop100ByOrderByCreatedAtDesc()
            .take(limit.coerceAtMost(100))

        return trades.map { trade ->
            TradeInfo(
                id = trade.id,
                market = trade.market,
                side = trade.side,
                price = trade.price,
                quantity = trade.quantity,
                pnl = trade.pnl,
                pnlPercent = trade.pnlPercent,
                strategy = trade.strategy,
                regime = trade.regime,
                reason = trade.reason,
                createdAt = trade.createdAt.toString()
            )
        }
    }

    @McpTool(description = "전체 거래 성과 요약을 조회합니다.")
    fun getPerformanceSummary(
        @McpToolParam(description = "분석 기간 (일): 7, 30, 90 등") days: Int
    ): PerformanceSummary {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val trades = tradeRepository.findByCreatedAtBetween(since, Instant.now())

        if (trades.isEmpty()) {
            return PerformanceSummary(
                period = "${days}일",
                totalTrades = 0,
                sellTrades = 0,
                totalPnl = "0",
                winTrades = 0,
                lossTrades = 0,
                winRate = "0.0",
                avgWin = "0",
                avgLoss = "0",
                profitFactor = "0.00",
                strategyBreakdown = emptyMap(),
                message = "해당 기간에 거래 기록이 없습니다."
            )
        }

        val sellTrades = trades.filter { it.side == "SELL" && it.pnl != null }
        val totalPnl = sellTrades.sumOf { it.pnl ?: 0.0 }
        val winTrades = sellTrades.count { (it.pnl ?: 0.0) > 0 }
        val lossTrades = sellTrades.count { (it.pnl ?: 0.0) < 0 }
        val winRate = if (sellTrades.isNotEmpty()) winTrades.toDouble() / sellTrades.size * 100 else 0.0

        val avgWin = sellTrades.filter { (it.pnl ?: 0.0) > 0 }
            .takeIf { it.isNotEmpty() }
            ?.map { it.pnl!! }
            ?.average() ?: 0.0
        val avgLoss = sellTrades.filter { (it.pnl ?: 0.0) < 0 }
            .takeIf { it.isNotEmpty() }
            ?.map { it.pnl!! }
            ?.average() ?: 0.0

        val grossProfit = sellTrades.filter { (it.pnl ?: 0.0) > 0 }.sumOf { it.pnl ?: 0.0 }
        val grossLoss = kotlin.math.abs(sellTrades.filter { (it.pnl ?: 0.0) < 0 }.sumOf { it.pnl ?: 0.0 })
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else 0.0

        val strategyStats = trades.groupBy { it.strategy }.mapValues { (_, strategyTrades) ->
            val strategySells = strategyTrades.filter { it.side == "SELL" && it.pnl != null }
            StrategyStats(
                trades = strategyTrades.size,
                pnl = strategySells.sumOf { it.pnl ?: 0.0 },
                winRate = if (strategySells.isNotEmpty())
                    strategySells.count { (it.pnl ?: 0.0) > 0 }.toDouble() / strategySells.size * 100
                else 0.0
            )
        }

        return PerformanceSummary(
            period = "${days}일",
            totalTrades = trades.size,
            sellTrades = sellTrades.size,
            totalPnl = String.format("%.0f", totalPnl),
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = String.format("%.1f", winRate),
            avgWin = String.format("%.0f", avgWin),
            avgLoss = String.format("%.0f", avgLoss),
            profitFactor = String.format("%.2f", profitFactor),
            strategyBreakdown = strategyStats
        )
    }

    @McpTool(description = "특정 전략의 성과를 분석합니다.")
    fun getStrategyPerformance(
        @McpToolParam(description = "전략 이름: DCA, GRID, MEAN_REVERSION, MEME_SCALPER, VOLUME_SURGE") strategy: String,
        @McpToolParam(description = "분석 기간 (일)") days: Int
    ): StrategyPerformance {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)

        // 전략별 전용 테이블에서 조회
        return when (strategy.uppercase()) {
            "MEME_SCALPER" -> getMemeScalperPerformance(since, days)
            "VOLUME_SURGE" -> getVolumeSurgePerformance(since, days)
            else -> getGeneralStrategyPerformance(strategy, since, days)
        }
    }

    private fun getMemeScalperPerformance(since: Instant, days: Int): StrategyPerformance {
        val trades = memeScalperRepository.findByCreatedAtAfter(since)
            .filter { it.status == "CLOSED" }

        return buildPerformance("MEME_SCALPER", days, trades.size,
            trades.mapNotNull { it.pnlAmount },
            trades.mapNotNull { it.pnlPercent })
    }

    private fun getVolumeSurgePerformance(since: Instant, days: Int): StrategyPerformance {
        val trades = volumeSurgeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(since, Instant.now())
            .filter { it.status == "CLOSED" }

        return buildPerformance("VOLUME_SURGE", days, trades.size,
            trades.mapNotNull { it.pnlAmount },
            trades.mapNotNull { it.pnlPercent })
    }

    private fun getGeneralStrategyPerformance(strategy: String, since: Instant, days: Int): StrategyPerformance {
        val trades = tradeRepository.findByStrategyAndCreatedAtBetween(strategy.uppercase(), since, Instant.now())
        val sellTrades = trades.filter { it.side == "SELL" && it.pnl != null }

        return buildPerformance(strategy.uppercase(), days, trades.size,
            sellTrades.mapNotNull { it.pnl },
            sellTrades.mapNotNull { it.pnlPercent })
    }

    private fun buildPerformance(
        strategy: String,
        days: Int,
        totalTrades: Int,
        pnlAmounts: List<Double>,
        pnlPercents: List<Double>
    ): StrategyPerformance {
        if (pnlAmounts.isEmpty()) {
            return StrategyPerformance(
                strategy = strategy, period = "${days}일", totalTrades = totalTrades,
                totalPnl = "0", winRate = "0.0", sharpeRatio = "0.00",
                maxDrawdown = "0.0", avgPnlPercent = "0.00",
                message = if (totalTrades == 0) "거래 기록 없음" else "청산된 거래 없음"
            )
        }

        val avgReturn = pnlPercents.average()
        val stdReturn = if (pnlPercents.size > 1) {
            sqrt(pnlPercents.map { (it - avgReturn) * (it - avgReturn) }.average())
        } else 0.0
        val sharpeRatio = if (stdReturn > 0) avgReturn / stdReturn * sqrt(252.0) else 0.0

        var peak = 0.0
        var maxDrawdown = 0.0
        var cumReturn = 0.0
        for (pnl in pnlAmounts) {
            cumReturn += pnl
            if (cumReturn > peak) peak = cumReturn
            val drawdown = if (peak > 0) (peak - cumReturn) / peak * 100 else 0.0
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        val winCount = pnlAmounts.count { it > 0 }
        return StrategyPerformance(
            strategy = strategy,
            period = "${days}일",
            totalTrades = totalTrades,
            totalPnl = String.format("%.0f", pnlAmounts.sum()),
            winRate = String.format("%.1f", winCount.toDouble() / pnlAmounts.size * 100),
            sharpeRatio = String.format("%.2f", sharpeRatio),
            maxDrawdown = String.format("%.1f", maxDrawdown),
            avgPnlPercent = String.format("%.2f", avgReturn)
        )
    }

    @McpTool(description = "일별 성과 추이를 조회합니다.")
    fun getDailyPerformance(
        @McpToolParam(description = "조회할 일수 (최대 30)") days: Int
    ): List<DailyPerformanceInfo> {
        val stats = dailyStatsRepository.findTop30ByOrderByDateDesc()
            .take(days.coerceAtMost(30))

        return stats.map { stat ->
            DailyPerformanceInfo(
                date = stat.date,
                market = stat.market,
                totalTrades = stat.totalTrades,
                winRate = String.format("%.1f", stat.winRate),
                totalPnl = String.format("%.0f", stat.totalPnl),
                maxDrawdown = String.format("%.1f", stat.maxDrawdown)
            )
        }
    }

    @McpTool(description = "전략 최적화를 위한 분석 보고서를 생성합니다.")
    fun getOptimizationReport(): OptimizationReport {
        val last30Days = Instant.now().minus(30, ChronoUnit.DAYS)
        val last7Days = Instant.now().minus(7, ChronoUnit.DAYS)

        val trades30 = tradeRepository.findByCreatedAtBetween(last30Days, Instant.now())
        val trades7 = tradeRepository.findByCreatedAtBetween(last7Days, Instant.now())

        val strategyComparison = listOf("DCA", "GRID", "MEAN_REVERSION").map { strategy ->
            val strategyTrades = trades30.filter { it.strategy == strategy }
            val sellTrades = strategyTrades.filter { it.side == "SELL" && it.pnl != null }

            StrategyComparison(
                strategy = strategy,
                trades30d = strategyTrades.size,
                pnl30d = sellTrades.sumOf { it.pnl ?: 0.0 },
                winRate30d = if (sellTrades.isNotEmpty())
                    sellTrades.count { (it.pnl ?: 0.0) > 0 }.toDouble() / sellTrades.size * 100
                else 0.0
            )
        }

        val recentTrend = trades7.groupBy { it.strategy }.mapValues { (_, trades) ->
            trades.filter { it.side == "SELL" }.sumOf { it.pnl ?: 0.0 }
        }

        val bestStrategy = strategyComparison.maxByOrNull { it.pnl30d }?.strategy ?: "MEAN_REVERSION"

        val recommendations = mutableListOf<String>()
        strategyComparison.forEach { stat ->
            if (stat.winRate30d < 40 && stat.pnl30d < 0) {
                recommendations.add("${stat.strategy} 전략의 승률이 낮음(${String.format("%.1f", stat.winRate30d)}%). 파라미터 조정 권장.")
            }
            if (stat.pnl30d < -50000) {
                recommendations.add("${stat.strategy} 전략의 손실이 큼. 해당 전략 비활성화 고려.")
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("현재 전략 성과가 양호합니다. 기존 설정 유지 권장.")
        }

        return OptimizationReport(
            generatedAt = LocalDate.now().format(DateTimeFormatter.ISO_DATE),
            analysisPeriod = "최근 30일",
            totalTrades = trades30.size,
            strategyComparison = strategyComparison,
            recentTrend7d = recentTrend,
            recommendedStrategy = bestStrategy,
            recommendations = recommendations
        )
    }
}
