package com.ant.cointrading.notification

import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 실시간 성과 보고 (Jim Simons 스타일)
 *
 * Renaissance Technologies에서 Simons가 매일 아침 받던 보고서 형식.
 *
 * 보고 항목:
 * - 총 거래, 승률, 손익
 * - Sharpe Ratio
 * - Kelly Criterion f*
 * - 각 전략별 성과
 */
@Component
class SlackPerformanceReporter(
    private val slackNotifier: SlackNotifier,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))

    /**
     * 매일 정기 보고 (새벽 1시)
     */
    @Scheduled(cron = "0 0 1 * * *")
    fun sendDailyReport() {
        log.info("일일 성과 보고 전송 시작")

        val memeStats = calculateStrategyStats("MEME_SCALPER", memeScalperRepository)
        val volumeStats = calculateStrategyStats("VOLUME_SURGE", volumeSurgeRepository)

        val report = formatDailyReport(memeStats, volumeStats)
        slackNotifier.sendSystemNotification("일일 성과 보고", report)

        log.info("일일 성과 보고 전송 완료")
    }

    /**
     * 포지션 청산 시 즉시 알림
     */
    fun sendTradeCloseNotification(
        strategy: String,
        market: String,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        pnlAmount: Double,
        pnlPercent: Double,
        exitReason: String,
        holdingMinutes: Long
    ) {
        val pnlSign = if (pnlAmount >= 0) "+" else ""
        val holdingTime = formatHoldingTime(holdingMinutes)

        val message = """
            `$strategy` 포지션 청산

            | 항목 | 값 |
            |------|-----|
            | 마켓 | $market |
            | 진입가 | ${String.format("%.0f", entryPrice)}원 |
            | 청산가 | ${String.format("%.0f", exitPrice)}원 |
            | 수익률 | ${pnlSign}${String.format("%.2f", pnlPercent)}% |
            | 손익 | ${pnlSign}${String.format("%.0f", pnlAmount)}원 |
            | 사유 | $exitReason |
            | 보유 | $holdingTime |

            _${dateFormatter.format(Instant.now())}_
        """.trimIndent()

        slackNotifier.sendSystemNotification("포지션 청산", message)
    }

    /**
     * Kelly Criterion 상태 변화 알림
     */
    fun sendKellyStatusChange(
        strategy: String,
        oldKelly: Double?,
        newKelly: Double,
        recommendation: String
    ) {
        val status = when {
            newKelly < 0 -> "위험 (f* < 0)"
            newKelly < 0.01 -> "보수적 (f* < 1%)"
            newKelly < 0.02 -> "적정 (f* 1-2%)"
            else -> "공격적 (f* > 2%)"
        }

        val message = """
            `$strategy` Kelly 상태 변화

            | 항목 | 값 |
            |------|-----|
            | 기존 f* | ${oldKelly?.let { String.format("%.2f", it * 100) + "%" } ?: "N/A"} |
            | 새로운 f* | ${String.format("%.2f", newKelly * 100)}% |
            | 상태 | $status |
            | 권장사항 | $recommendation |

            _${dateFormatter.format(Instant.now())}_
        """.trimIndent()

        slackNotifier.sendSystemNotification("Kelly 상태 변화", message)
    }

    /**
     * 전략별 통계 계산
     */
    private fun calculateStrategyStats(
        strategyName: String,
        repository: Any
    ): StrategyStats {
        val trades = when (repository) {
            is MemeScalperTradeRepository -> repository.findByStatus("CLOSED")
            is VolumeSurgeTradeRepository -> repository.findByStatus("CLOSED")
            else -> emptyList()
        }

        if (trades.isEmpty()) {
            return StrategyStats(
                name = strategyName,
                totalTrades = 0,
                winningTrades = 0,
                winRate = 0.0,
                totalPnl = 0.0,
                avgPnl = 0.0,
                sharpeRatio = 0.0,
                kellyFraction = null
            )
        }

        val totalTrades = trades.size
        val winningTrades = trades.count { getPnlAmount(it) > 0 }
        val winRate = winningTrades.toDouble() / totalTrades

        val pnls = trades.map { getPnlAmount(it) }
        val totalPnl = pnls.sum()
        val avgPnl = pnls.average()

        // Sharpe Ratio (연율화 가정)
        val avgReturn = avgPnl / 10000.0  // 1만원 기준
        val stdReturn = if (pnls.size > 1) {
            val returns = pnls.map { it / 10000.0 }
            sqrt(returns.map { (it - avgReturn) * (it - avgReturn) }.average())
        } else {
            0.0
        }
        val sharpeRatio = if (stdReturn > 0) {
            avgReturn / stdReturn * sqrt(252.0)
        } else {
            0.0
        }

        // Kelly Criterion
        val avgWin = pnls.filter { it > 0 }.average()
        val avgLoss = abs(pnls.filter { it < 0 }.average())
        val kellyFraction = if (avgLoss > 0 && totalTrades >= 30) {
            val b = avgWin / avgLoss
            val p = winRate
            val q = 1.0 - winRate
            (b * p - q) / b
        } else {
            null
        }

        return StrategyStats(
            name = strategyName,
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            winRate = winRate,
            totalPnl = totalPnl,
            avgPnl = avgPnl,
            sharpeRatio = sharpeRatio,
            kellyFraction = kellyFraction
        )
    }

    /**
     * 일일 보고 포맷팅 (Simons 스타일)
     */
    private fun formatDailyReport(
        memeStats: StrategyStats,
        volumeStats: StrategyStats
    ): String {
        val totalPnl = memeStats.totalPnl + volumeStats.totalPnl
        val totalTrades = memeStats.totalTrades + volumeStats.totalTrades
        val totalWins = memeStats.winningTrades + volumeStats.winningTrades
        val winRate = if (totalTrades > 0) totalWins.toDouble() / totalTrades else 0.0

        val headerColor = if (totalPnl >= 0) "초록" else "빨강"

        return """
            📊 *일일 성과 보고* (_${dateFormatter.format(Instant.now())}_)

            ├─── 개 요 ───
            총 손익: ${String.format("%.0f", totalPnl)}원
            총 거래: ${totalTrades}건 | 승률: ${String.format("%.1f", winRate * 100)}%

            ├─── Meme Scalper ───
            거래: ${memeStats.totalTrades}건 (${memeStats.winningTrades}승)
            손익: ${String.format("%.0f", memeStats.totalPnl)}원
            Sharpe: ${String.format("%.2f", memeStats.sharpeRatio)}
            Kelly: ${memeStats.kellyFraction?.let { String.format("%.2f", it * 100) + "%" } ?: "N/A"}

            ├─── Volume Surge ───
            거래: ${volumeStats.totalTrades}건 (${volumeStats.winningTrades}승)
            손익: ${String.format("%.0f", volumeStats.totalPnl)}원
            Sharpe: ${String.format("%.2f", volumeStats.sharpeRatio)}
            Kelly: ${volumeStats.kellyFraction?.let { String.format("%.2f", it * 100) + "%" } ?: "N/A"}

            └─── Simons 기준 ───
            ${formatSimonsCriteria(memeStats, volumeStats)}
        """.trimIndent()
    }

    /**
     * Simons 기준 만족 여부
     */
    private fun formatSimonsCriteria(
        memeStats: StrategyStats,
        volumeStats: StrategyStats
    ): String {
        val criteria = mutableListOf<String>()

        // 최소 100건 거래
        val totalTrades = memeStats.totalTrades + volumeStats.totalTrades
        criteria.add(if (totalTrades >= 100) "✓ 최소 100건 (현재 $totalTrades 건)" else "○ 최소 100건 미달 (현재 $totalTrades 건)")

        // Sharpe Ratio > 1.0
        val avgSharpe = (memeStats.sharpeRatio + volumeStats.sharpeRatio) / 2
        criteria.add(if (avgSharpe > 1.0) "✓ Sharpe > 1.0 (현재 ${String.format("%.2f", avgSharpe)})" else "○ Sharpe 미달 (현재 ${String.format("%.2f", avgSharpe)})")

        // 승률 > 50%
        val winRate = (memeStats.winningTrades + volumeStats.winningTrades).toDouble() / totalTrades
        criteria.add(if (winRate > 0.5) "✓ 승률 50%+ (현재 ${String.format("%.1f", winRate * 100)}%)" else "○ 승률 50% 미만 (현재 ${String.format("%.1f", winRate * 100)}%)")

        return criteria.joinToString("\n")
    }

    private fun getPnlAmount(trade: Any): Double {
        return when (trade) {
            is com.ant.cointrading.repository.MemeScalperTradeEntity -> trade.pnlAmount ?: 0.0
            is com.ant.cointrading.repository.VolumeSurgeTradeEntity -> trade.pnlAmount ?: 0.0
            else -> 0.0
        }
    }

    private fun formatHoldingTime(minutes: Long): String {
        return when {
            minutes < 60 -> "${minutes}분"
            minutes < 1440 -> "${minutes / 60}시간 ${minutes % 60}분"
            else -> "${minutes / 1440}일 ${minutes % 1440 / 60}시간"
        }
    }

    data class StrategyStats(
        val name: String,
        val totalTrades: Int,
        val winningTrades: Int,
        val winRate: Double,
        val totalPnl: Double,
        val avgPnl: Double,
        val sharpeRatio: Double,
        val kellyFraction: Double?
    )
}
