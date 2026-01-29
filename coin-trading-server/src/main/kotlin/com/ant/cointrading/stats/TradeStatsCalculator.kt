package com.ant.cointrading.stats

import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeEntity
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 트레이드 통계 계산 공통 컴포넌트
 *
 * VolumeSurgeReflector, MemeScalperReflector의 중복 제거.
 */
object TradeStatsCalculator {

    /**
     * Volume Surge 트레이드 통계 계산
     */
    fun calculateVolumeSurge(trades: List<VolumeSurgeTradeEntity>): VolumeSurgeStats {
        val closedTrades = trades.filter { it.status == "CLOSED" }

        val totalTrades = closedTrades.size
        val winningTrades = closedTrades.count { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = closedTrades.count { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = closedTrades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        val avgHoldingMinutes = closedTrades
            .filter { it.exitTime != null }
            .map { ChronoUnit.MINUTES.between(it.entryTime, it.exitTime) }
            .average()
            .takeIf { !it.isNaN() }

        return VolumeSurgeStats(
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            totalPnl = totalPnl,
            winRate = winRate,
            avgHoldingMinutes = avgHoldingMinutes
        )
    }

    /**
     * Meme Scalper 트레이드 통계 계산
     */
    fun calculateMemeScalper(trades: List<MemeScalperTradeEntity>): MemeScalperStats {
        val completedTrades = trades.filter { it.status == "CLOSED" || it.status == "ABANDONED" }

        val totalTrades = completedTrades.size
        val winningTrades = completedTrades.count { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = completedTrades.count { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = completedTrades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        val avgHoldingSeconds = completedTrades
            .filter { it.exitTime != null }
            .map { ChronoUnit.SECONDS.between(it.entryTime, it.exitTime) }
            .average()
            .takeIf { !it.isNaN() }

        val exitReasonStats = completedTrades.groupBy { it.exitReason ?: "UNKNOWN" }
            .mapValues { it.value.size }

        return MemeScalperStats(
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            totalPnl = totalPnl,
            winRate = winRate,
            avgHoldingSeconds = avgHoldingSeconds,
            exitReasonStats = exitReasonStats
        )
    }

    /**
     * Volume Surge 통계 결과
     */
    data class VolumeSurgeStats(
        val totalTrades: Int,
        val winningTrades: Int,
        val losingTrades: Int,
        val totalPnl: Double,
        val winRate: Double,
        val avgHoldingMinutes: Double?
    )

    /**
     * Meme Scalper 통계 결과
     */
    data class MemeScalperStats(
        val totalTrades: Int,
        val winningTrades: Int,
        val losingTrades: Int,
        val totalPnl: Double,
        val winRate: Double,
        val avgHoldingSeconds: Double?,
        val exitReasonStats: Map<String, Int>
    )
}
