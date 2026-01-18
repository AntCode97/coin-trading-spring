package com.ant.cointrading.mcp.tool

import com.ant.cointrading.repository.DailyStatsRepository
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
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

/** MemeScalper 거래 정보 */
data class MemeScalperTradeInfo(
    val id: Long?,
    val market: String,
    val entryPrice: Double,
    val exitPrice: Double?,
    val quantity: Double,
    val pnlAmount: Double?,
    val pnlPercent: Double?,
    val exitReason: String?,
    val status: String,
    val entryTime: String,
    val exitTime: String?
)

/** VolumeSurge 거래 정보 */
data class VolumeSurgeTradeInfo(
    val id: Long?,
    val market: String,
    val entryPrice: Double,
    val exitPrice: Double?,
    val quantity: Double,
    val pnlAmount: Double?,
    val pnlPercent: Double?,
    val exitReason: String?,
    val status: String,
    val entryTime: String,
    val exitTime: String?
)

/** 열린 포지션 정보 */
data class OpenPositionInfo(
    val strategy: String,
    val market: String,
    val entryPrice: Double,
    val quantity: Double,
    val entryTime: String,
    val holdingMinutes: Long
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
    private val memeScalperStatsRepository: MemeScalperDailyStatsRepository,
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

    @McpTool(description = "MemeScalper 거래 기록을 조회합니다.")
    fun getMemeScalperTrades(
        @McpToolParam(description = "조회할 거래 수 (최대 50)") limit: Int
    ): List<MemeScalperTradeInfo> {
        val since = Instant.now().minus(30, ChronoUnit.DAYS)
        return memeScalperRepository.findByCreatedAtAfter(since)
            .sortedByDescending { it.createdAt }
            .take(limit.coerceAtMost(50))
            .map { trade ->
                MemeScalperTradeInfo(
                    id = trade.id,
                    market = trade.market,
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice,
                    quantity = trade.quantity,
                    pnlAmount = trade.pnlAmount,
                    pnlPercent = trade.pnlPercent,
                    exitReason = trade.exitReason,
                    status = trade.status,
                    entryTime = trade.entryTime.toString(),
                    exitTime = trade.exitTime?.toString()
                )
            }
    }

    @McpTool(description = "VolumeSurge 거래 기록을 조회합니다.")
    fun getVolumeSurgeTrades(
        @McpToolParam(description = "조회할 거래 수 (최대 50)") limit: Int
    ): List<VolumeSurgeTradeInfo> {
        val since = Instant.now().minus(30, ChronoUnit.DAYS)
        val now = Instant.now()
        return volumeSurgeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(since, now)
            .take(limit.coerceAtMost(50))
            .map { trade ->
                VolumeSurgeTradeInfo(
                    id = trade.id,
                    market = trade.market,
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice,
                    quantity = trade.quantity,
                    pnlAmount = trade.pnlAmount,
                    pnlPercent = trade.pnlPercent,
                    exitReason = trade.exitReason,
                    status = trade.status,
                    entryTime = trade.entryTime.toString(),
                    exitTime = trade.exitTime?.toString()
                )
            }
    }

    @McpTool(description = "현재 열린 포지션을 조회합니다.")
    fun getOpenPositions(): List<OpenPositionInfo> {
        val now = Instant.now()
        val positions = mutableListOf<OpenPositionInfo>()

        // MemeScalper 열린 포지션
        memeScalperRepository.findByStatus("OPEN").forEach { trade ->
            positions.add(OpenPositionInfo(
                strategy = "MEME_SCALPER",
                market = trade.market,
                entryPrice = trade.entryPrice,
                quantity = trade.quantity,
                entryTime = trade.entryTime.toString(),
                holdingMinutes = ChronoUnit.MINUTES.between(trade.entryTime, now)
            ))
        }

        // VolumeSurge 열린 포지션
        volumeSurgeRepository.findByStatus("OPEN").forEach { trade ->
            positions.add(OpenPositionInfo(
                strategy = "VOLUME_SURGE",
                market = trade.market,
                entryPrice = trade.entryPrice,
                quantity = trade.quantity,
                entryTime = trade.entryTime.toString(),
                holdingMinutes = ChronoUnit.MINUTES.between(trade.entryTime, now)
            ))
        }

        return positions
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
        val results = mutableListOf<DailyPerformanceInfo>()

        // 일반 TradeEntity 기반 통계 (DailyStatsEntity)
        dailyStatsRepository.findTop30ByOrderByDateDesc()
            .take(days.coerceAtMost(30))
            .forEach { stat ->
                results.add(DailyPerformanceInfo(
                    date = stat.date,
                    market = stat.market,
                    totalTrades = stat.totalTrades,
                    winRate = String.format("%.1f", stat.winRate * 100),
                    totalPnl = String.format("%.0f", stat.totalPnl),
                    maxDrawdown = String.format("%.1f", stat.maxDrawdown)
                ))
            }

        // MemeScalper 전용 일일 통계
        memeScalperStatsRepository.findTop30ByOrderByDateDesc()
            .take(days.coerceAtMost(30))
            .forEach { stat ->
                results.add(DailyPerformanceInfo(
                    date = stat.date.toString(),
                    market = "MEME_SCALPER",
                    totalTrades = stat.totalTrades,
                    winRate = String.format("%.1f", stat.winRate * 100),
                    totalPnl = String.format("%.0f", stat.totalPnl),
                    maxDrawdown = "0.0"
                ))
            }

        // 날짜순 정렬 후 반환
        return results.sortedByDescending { it.date }.take(days.coerceAtMost(30))
    }

    @McpTool(description = "전략 최적화를 위한 분석 보고서를 생성합니다.")
    fun getOptimizationReport(): OptimizationReport {
        val last30Days = Instant.now().minus(30, ChronoUnit.DAYS)
        val last7Days = Instant.now().minus(7, ChronoUnit.DAYS)
        val now = Instant.now()

        // 일반 전략 (TradeEntity)
        val trades30 = tradeRepository.findByCreatedAtBetween(last30Days, now)
        val trades7 = tradeRepository.findByCreatedAtBetween(last7Days, now)

        // MEME_SCALPER (전용 테이블)
        val memeScalper30 = memeScalperRepository.findByCreatedAtAfter(last30Days)
            .filter { it.status == "CLOSED" }
        val memeScalper7 = memeScalperRepository.findByCreatedAtAfter(last7Days)
            .filter { it.status == "CLOSED" }

        // VOLUME_SURGE (전용 테이블)
        val volumeSurge30 = volumeSurgeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(last30Days, now)
            .filter { it.status == "CLOSED" }
        val volumeSurge7 = volumeSurgeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(last7Days, now)
            .filter { it.status == "CLOSED" }

        // 일반 전략 비교
        val generalComparison = listOf("DCA", "GRID", "MEAN_REVERSION").map { strategy ->
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

        // MEME_SCALPER 비교
        val memeScalperComparison = StrategyComparison(
            strategy = "MEME_SCALPER",
            trades30d = memeScalper30.size,
            pnl30d = memeScalper30.sumOf { it.pnlAmount ?: 0.0 },
            winRate30d = if (memeScalper30.isNotEmpty())
                memeScalper30.count { (it.pnlAmount ?: 0.0) > 0 }.toDouble() / memeScalper30.size * 100
            else 0.0
        )

        // VOLUME_SURGE 비교
        val volumeSurgeComparison = StrategyComparison(
            strategy = "VOLUME_SURGE",
            trades30d = volumeSurge30.size,
            pnl30d = volumeSurge30.sumOf { it.pnlAmount ?: 0.0 },
            winRate30d = if (volumeSurge30.isNotEmpty())
                volumeSurge30.count { (it.pnlAmount ?: 0.0) > 0 }.toDouble() / volumeSurge30.size * 100
            else 0.0
        )

        val strategyComparison = generalComparison + listOf(memeScalperComparison, volumeSurgeComparison)

        // 7일 추세
        val generalTrend = trades7.groupBy { it.strategy }.mapValues { (_, trades) ->
            trades.filter { it.side == "SELL" }.sumOf { it.pnl ?: 0.0 }
        }.toMutableMap()
        generalTrend["MEME_SCALPER"] = memeScalper7.sumOf { it.pnlAmount ?: 0.0 }
        generalTrend["VOLUME_SURGE"] = volumeSurge7.sumOf { it.pnlAmount ?: 0.0 }

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

        val totalTrades = trades30.size + memeScalper30.size + volumeSurge30.size

        return OptimizationReport(
            generatedAt = LocalDate.now().format(DateTimeFormatter.ISO_DATE),
            analysisPeriod = "최근 30일",
            totalTrades = totalTrades,
            strategyComparison = strategyComparison,
            recentTrend7d = generalTrend,
            recommendedStrategy = bestStrategy,
            recommendations = recommendations
        )
    }
}
