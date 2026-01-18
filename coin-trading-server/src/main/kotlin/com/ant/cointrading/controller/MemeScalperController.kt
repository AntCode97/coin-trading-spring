package com.ant.cointrading.controller

import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.memescalper.MemeScalperDetector
import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.memescalper.MemeScalperReflector
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Meme Scalper 전략 API 컨트롤러
 */
@RestController
@RequestMapping("/api/meme-scalper")
class MemeScalperController(
    private val properties: MemeScalperProperties,
    private val memeScalperEngine: MemeScalperEngine,
    private val memeScalperDetector: MemeScalperDetector,
    private val memeScalperReflector: MemeScalperReflector,
    private val tradeRepository: MemeScalperTradeRepository,
    private val statsRepository: MemeScalperDailyStatsRepository
) {

    /**
     * 전략 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any?> {
        val engineStatus = memeScalperEngine.getStatus()
        val openPositions = tradeRepository.findByStatus("OPEN")

        return mapOf(
            "enabled" to properties.enabled,
            "config" to mapOf(
                "positionSizeKrw" to properties.positionSizeKrw,
                "maxPositions" to properties.maxPositions,
                "stopLossPercent" to properties.stopLossPercent,
                "takeProfitPercent" to properties.takeProfitPercent,
                "positionTimeoutMin" to properties.positionTimeoutMin,
                "volumeSpikeRatio" to properties.volumeSpikeRatio,
                "priceSpikePercent" to properties.priceSpikePercent,
                "minBidImbalance" to properties.minBidImbalance
            ),
            "engine" to engineStatus,
            "openPositions" to openPositions.map { position ->
                mapOf(
                    "id" to position.id,
                    "market" to position.market,
                    "entryPrice" to position.entryPrice,
                    "entryTime" to position.entryTime.toString(),
                    "holdingSeconds" to ChronoUnit.SECONDS.between(position.entryTime, Instant.now()),
                    "volumeSpikeRatio" to position.entryVolumeSpikeRatio,
                    "priceSpikePercent" to position.entryPriceSpikePercent,
                    "imbalance" to position.entryImbalance,
                    "rsi" to position.entryRsi
                )
            }
        )
    }

    /**
     * 오늘 통계 조회
     */
    @GetMapping("/stats/today")
    fun getTodayStats(): Map<String, Any?> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val todayTrades = tradeRepository.findTodayClosedTrades(startOfDay)
        val totalPnl = tradeRepository.sumTodayPnl(startOfDay)
        val winCount = todayTrades.count { (it.pnlAmount ?: 0.0) > 0 }
        val loseCount = todayTrades.count { (it.pnlAmount ?: 0.0) < 0 }

        val totalTrades = winCount + loseCount
        val winRate = if (totalTrades > 0) (winCount.toDouble() / totalTrades * 100) else 0.0

        val avgHolding = todayTrades
            .mapNotNull { trade ->
                trade.exitTime?.let { ChronoUnit.SECONDS.between(trade.entryTime, it).toDouble() }
            }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        return mapOf(
            "date" to today.toString(),
            "trades" to mapOf(
                "total" to totalTrades,
                "wins" to winCount,
                "losses" to loseCount,
                "winRate" to String.format("%.1f%%", winRate)
            ),
            "pnl" to mapOf(
                "total" to String.format("%.0f원", totalPnl),
                "maxProfit" to todayTrades.maxOfOrNull { it.pnlAmount ?: 0.0 }?.let { String.format("%.0f원", it) },
                "maxLoss" to todayTrades.minOfOrNull { it.pnlAmount ?: 0.0 }?.let { String.format("%.0f원", it) }
            ),
            "avgHoldingSeconds" to String.format("%.0f초", avgHolding)
        )
    }

    /**
     * 최근 트레이드 목록 조회
     */
    @GetMapping("/trades")
    fun getTrades(
        @RequestParam(defaultValue = "20") limit: Int
    ): List<Map<String, Any?>> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        return tradeRepository.findByCreatedAtAfter(startOfDay)
            .sortedByDescending { it.createdAt }
            .take(limit)
            .map { trade ->
                mapOf(
                    "id" to trade.id,
                    "market" to trade.market,
                    "status" to trade.status,
                    "entryPrice" to trade.entryPrice,
                    "exitPrice" to trade.exitPrice,
                    "pnlAmount" to trade.pnlAmount?.let { String.format("%.0f원", it) },
                    "pnlPercent" to trade.pnlPercent?.let { String.format("%.2f%%", it) },
                    "exitReason" to trade.exitReason,
                    "volumeSpikeRatio" to trade.entryVolumeSpikeRatio?.let { String.format("%.1fx", it) },
                    "priceSpikePercent" to trade.entryPriceSpikePercent?.let { String.format("%.1f%%", it) },
                    "imbalance" to trade.entryImbalance?.let { String.format("%.2f", it) },
                    "rsi" to trade.entryRsi?.let { String.format("%.1f", it) },
                    "entryTime" to trade.entryTime.toString(),
                    "exitTime" to trade.exitTime?.toString(),
                    "holdingSeconds" to trade.exitTime?.let {
                        ChronoUnit.SECONDS.between(trade.entryTime, it)
                    }
                )
            }
    }

    /**
     * 일일 요약 목록 조회
     */
    @GetMapping("/summaries")
    fun getSummaries(
        @RequestParam(defaultValue = "30") days: Int
    ): List<Map<String, Any?>> {
        return statsRepository.findTop30ByOrderByDateDesc()
            .take(days)
            .map { stats ->
                mapOf(
                    "date" to stats.date.toString(),
                    "totalTrades" to stats.totalTrades,
                    "winningTrades" to stats.winningTrades,
                    "losingTrades" to stats.losingTrades,
                    "totalPnl" to String.format("%.0f원", stats.totalPnl),
                    "winRate" to String.format("%.1f%%", stats.winRate * 100),
                    "avgHoldingSeconds" to stats.avgHoldingSeconds?.let { String.format("%.0f초", it) },
                    "maxSingleLoss" to stats.maxSingleLoss?.let { String.format("%.0f원", it) },
                    "maxSingleProfit" to stats.maxSingleProfit?.let { String.format("%.0f원", it) }
                )
            }
    }

    /**
     * 펌프 스캔 (테스트용)
     */
    @GetMapping("/scan")
    fun scanPumps(): Map<String, Any?> {
        return try {
            val signals = memeScalperDetector.scanForPumps()
            mapOf(
                "success" to true,
                "count" to signals.size,
                "signals" to signals.take(10).map { signal ->
                    mapOf(
                        "market" to signal.market,
                        "score" to signal.score,
                        "volumeSpikeRatio" to String.format("%.1fx", signal.volumeSpikeRatio),
                        "priceSpikePercent" to String.format("%.1f%%", signal.priceSpikePercent),
                        "bidImbalance" to String.format("%.2f", signal.bidImbalance),
                        "rsi" to String.format("%.1f", signal.rsi),
                        "currentPrice" to signal.currentPrice,
                        "tradingValue" to signal.tradingValue
                    )
                }
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }

    /**
     * 수동 청산 - 특정 코인의 열린 포지션 청산
     */
    @PostMapping("/close/{market}")
    fun manualClose(@PathVariable market: String): Map<String, Any?> {
        return memeScalperEngine.manualClose(market)
    }

    /**
     * 서킷 브레이커 리셋
     */
    @PostMapping("/reset")
    fun resetCircuitBreaker(): Map<String, Any> {
        return memeScalperEngine.resetCircuitBreaker()
    }

    /**
     * 전략 설정 조회
     */
    @GetMapping("/config")
    fun getConfig(): Map<String, Any> {
        return mapOf(
            "enabled" to properties.enabled,
            "pollingIntervalMs" to properties.pollingIntervalMs,
            "positionSizeKrw" to properties.positionSizeKrw,
            "maxPositions" to properties.maxPositions,
            "riskManagement" to mapOf(
                "stopLossPercent" to properties.stopLossPercent,
                "takeProfitPercent" to properties.takeProfitPercent,
                "positionTimeoutMin" to properties.positionTimeoutMin,
                "trailingStopTrigger" to properties.trailingStopTrigger,
                "trailingStopOffset" to properties.trailingStopOffset
            ),
            "pumpDetection" to mapOf(
                "volumeSpikeRatio" to properties.volumeSpikeRatio,
                "priceSpikePercent" to properties.priceSpikePercent,
                "minBidImbalance" to properties.minBidImbalance,
                "maxRsi" to properties.maxRsi
            ),
            "exitSignals" to mapOf(
                "volumeDropRatio" to properties.volumeDropRatio,
                "imbalanceExitThreshold" to properties.imbalanceExitThreshold
            ),
            "limits" to mapOf(
                "cooldownSec" to properties.cooldownSec,
                "maxConsecutiveLosses" to properties.maxConsecutiveLosses,
                "dailyMaxLossKrw" to properties.dailyMaxLossKrw,
                "dailyMaxTrades" to properties.dailyMaxTrades
            ),
            "filters" to mapOf(
                "excludeMarkets" to properties.excludeMarkets,
                "minTradingValueKrw" to properties.minTradingValueKrw,
                "maxTradingValueKrw" to properties.maxTradingValueKrw
            )
        )
    }

    /**
     * 수동 회고 실행
     */
    @PostMapping("/reflect")
    fun runReflection(): Map<String, Any> {
        return try {
            val result = memeScalperReflector.runManualReflection()
            mapOf(
                "success" to true,
                "result" to result
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
}
