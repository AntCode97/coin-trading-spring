package com.ant.cointrading.controller

import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.stats.SharpeRatioCalculator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 실전 성과 대시보드 (Jim Simons 스타일 실시간 모니터링)
 *
 * 제공 지표:
 * - Sharpe Ratio (연율화)
 * - Kelly Criterion f*
 * - Max Drawdown
 * - 승률, 평균 손익
 */
@RestController
@RequestMapping("/api/performance")
class PerformanceDashboardController(
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository
) {
    private companion object {
        const val MEME_SCALPER = "meme_scalper"
        const val VOLUME_SURGE = "volume_surge"
        const val CLOSED = "CLOSED"
    }

    private data class ClosedTrade(
        val entryTime: Instant?,
        val createdAt: Instant?,
        val pnlAmount: Double
    )

    /**
     * 종합 대시보드
     */
    @GetMapping("/dashboard")
    fun getDashboard(): Map<String, Any?> {
        val memeStats = calculateStrategyStats(MEME_SCALPER, loadClosedMemeTrades())
        val volumeStats = calculateStrategyStats(VOLUME_SURGE, loadClosedVolumeSurgeTrades())

        return mapOf(
            "timestamp" to Instant.now(),
            "strategies" to mapOf(
                MEME_SCALPER to memeStats,
                VOLUME_SURGE to volumeStats
            ),
            "summary" to calculateSummary(memeStats, volumeStats)
        )
    }

    /**
     * 특정 전략 성과
     */
    @GetMapping("/strategy/{strategy}")
    fun getStrategyPerformance(@PathVariable strategy: String): Map<String, Any?> {
        return strategyStats(strategy) ?: mapOf("error" to "Unknown strategy")
    }

    /**
     * Kelly Criterion 계산 (Jim Simons)
     */
    @GetMapping("/kelly/{strategy}")
    fun getKellyCriterion(@PathVariable strategy: String): Map<String, Any?> {
        val stats = strategyStats(strategy) ?: return mapOf("error" to "Unknown strategy")

        val totalTrades = parseInt(stats["totalTrades"])
        val winningTrades = parseInt(stats["winningTrades"])
        val avgWin = parseDouble(stats["avgWin"])
        val avgLoss = parseDouble(stats["avgLoss"])

        if (totalTrades < 30 || avgLoss == 0.0) {
            return mapOf(
                "strategy" to strategy,
                "valid" to false,
                "reason" to if (totalTrades < 30) "최소 30건 거래 필요 (현재 $totalTrades 건)" else "손실 데이터 부족"
            )
        }

        val winRate = winningTrades.toDouble() / totalTrades
        val b = avgWin / avgLoss
        val p = winRate
        val q = 1.0 - winRate

        // Kelly Criterion: f* = (bp - q) / b
        val kellyFraction = (b * p - q) / b
        val halfKelly = kellyFraction * 0.5

        return mapOf(
            "strategy" to strategy,
            "valid" to true,
            "totalTrades" to totalTrades,
            "winRate" to String.format("%.1f", winRate * 100) + "%",
            "avgWin" to String.format("%.0f", avgWin),
            "avgLoss" to String.format("%.0f", avgLoss),
            "rewardRiskRatio" to String.format("%.2f", b),
            "kellyFraction" to String.format("%.2f", kellyFraction * 100) + "%",
            "halfKelly" to String.format("%.2f", halfKelly * 100) + "%",
            "recommendation" to when {
                winRate < 0.5 -> "승률 50% 미만, 베팅 중단 권장 (Simons)"
                kellyFraction < 0 -> "Kelly f* 음수, 베팅 중단"
                kellyFraction < 0.02 -> "Half-Kelly: " + String.format("%.1f", halfKelly * 100) + "% 베팅 권장"
                else -> "최대 포지션: " + String.format("%.1f", halfKelly * 100) + "%"
            }
        )
    }

    private fun strategyStats(strategy: String): Map<String, Any?>? {
        return when (strategy) {
            MEME_SCALPER -> calculateStrategyStats(strategy, loadClosedMemeTrades())
            VOLUME_SURGE -> calculateStrategyStats(strategy, loadClosedVolumeSurgeTrades())
            else -> null
        }
    }

    private fun loadClosedMemeTrades(): List<ClosedTrade> {
        return memeScalperRepository.findByStatus(CLOSED).map { it.toClosedTrade() }
    }

    private fun loadClosedVolumeSurgeTrades(): List<ClosedTrade> {
        return volumeSurgeRepository.findByStatus(CLOSED).map { it.toClosedTrade() }
    }

    private fun MemeScalperTradeEntity.toClosedTrade(): ClosedTrade {
        return ClosedTrade(
            entryTime = entryTime,
            createdAt = createdAt,
            pnlAmount = pnlAmount ?: 0.0
        )
    }

    private fun VolumeSurgeTradeEntity.toClosedTrade(): ClosedTrade {
        return ClosedTrade(
            entryTime = entryTime,
            createdAt = createdAt,
            pnlAmount = pnlAmount ?: 0.0
        )
    }

    /**
     * 전략별 통계 계산 (Jim Simons 스타일)
     */
    private fun calculateStrategyStats(
        strategyName: String,
        trades: List<ClosedTrade>
    ): Map<String, Any?> {
        if (trades.isEmpty()) {
            return mapOf(
                "strategy" to strategyName,
                "totalTrades" to 0,
                "message" to "거래 기록 없음"
            )
        }

        val totalTrades = trades.size
        val winningTrades = trades.count { it.pnlAmount > 0 }
        val losingTrades = trades.count { it.pnlAmount < 0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        val pnls = trades.map { it.pnlAmount }
        val totalPnl = pnls.sum()
        val avgPnl = if (totalTrades > 0) pnls.average() else 0.0

        val winningPnls = pnls.filter { it > 0 }
        val losingPnls = pnls.filter { it < 0 }.map { kotlin.math.abs(it) }
        val avgWin = if (winningPnls.isNotEmpty()) winningPnls.average() else 0.0
        val avgLoss = if (losingPnls.isNotEmpty()) losingPnls.average() else 0.0

        // Sharpe Ratio (암호화폐 적용: 자기상관 보정)
        val capital = 10000.0
        val periodDays = calculatePeriodDays(trades)
        val sharpeRatio = SharpeRatioCalculator.calculate(
            pnls = pnls,
            capital = capital,
            periodDays = periodDays
        )

        // 최근 7일 거래
        val sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 60 * 60)
        val recentTrades = trades.filter { it.createdAt?.isAfter(sevenDaysAgo) == true }
        val recentPnl = recentTrades.sumOf { it.pnlAmount }

        return mapOf(
            "strategy" to strategyName,
            "totalTrades" to totalTrades,
            "winningTrades" to winningTrades,
            "losingTrades" to losingTrades,
            "winRate" to String.format("%.1f", winRate * 100) + "%",
            "totalPnl" to String.format("%.0f", totalPnl),
            "avgPnl" to String.format("%.0f", avgPnl),
            "avgWin" to String.format("%.0f", avgWin),
            "avgLoss" to String.format("%.0f", avgLoss),
            "sharpeRatio" to String.format("%.2f", sharpeRatio),
            "sharpeGrade" to SharpeRatioCalculator.getGrade(sharpeRatio),
            "sharpeInterpretation" to SharpeRatioCalculator.interpret(sharpeRatio, totalTrades),
            "recent7DaysPnl" to String.format("%.0f", recentPnl),
            "recent7DaysTrades" to recentTrades.size,
            "isValidForLive" to (totalTrades >= 100 && sharpeRatio > 1.0 && winRate >= 0.5)
        )
    }

    /**
     * 거래 기간 계산 (일)
     */
    private fun calculatePeriodDays(trades: List<ClosedTrade>): Int {
        if (trades.isEmpty()) return 1

        val timestamps = trades.mapNotNull { it.entryTime }.sorted()

        if (timestamps.size < 2) return 1

        val first = timestamps.first()
        val last = timestamps.last()
        val days = ChronoUnit.DAYS.between(first, last).toInt()

        return maxOf(days, 1)
    }

    /**
     * 종합 요약
     */
    private fun calculateSummary(
        memeStats: Map<String, Any?>,
        volumeStats: Map<String, Any?>
    ): Map<String, Any?> {
        val totalTrades = parseInt(memeStats["totalTrades"]) + parseInt(volumeStats["totalTrades"])
        val totalPnl = parseDouble(memeStats["totalPnl"]) + parseDouble(volumeStats["totalPnl"])

        return mapOf(
            "totalTrades" to totalTrades,
            "totalPnl" to String.format("%.0f", totalPnl),
            "activeStrategies" to listOf(MEME_SCALPER, VOLUME_SURGE)
        )
    }

    private fun parseInt(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.replace("[^0-9-]".toRegex(), "").toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun parseDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.replace("[^0-9.-]".toRegex(), "").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
