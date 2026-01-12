package com.ant.cointrading.controller

import com.ant.cointrading.api.cryptocompare.CryptoCompareApi
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.VolumeSurgeAlertRepository
import com.ant.cointrading.repository.VolumeSurgeDailySummaryRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import com.ant.cointrading.volumesurge.VolumeSurgeReflector
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Volume Surge 전략 API 컨트롤러
 */
@RestController
@RequestMapping("/api/volume-surge")
class VolumeSurgeController(
    private val properties: VolumeSurgeProperties,
    private val volumeSurgeEngine: VolumeSurgeEngine,
    private val volumeSurgeReflector: VolumeSurgeReflector,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val summaryRepository: VolumeSurgeDailySummaryRepository,
    private val cryptoCompareApi: CryptoCompareApi
) {

    /**
     * 전략 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any?> {
        val engineStatus = volumeSurgeEngine.getStatus()
        val openPositions = tradeRepository.findByStatus("OPEN")

        return mapOf(
            "enabled" to properties.enabled,
            "config" to mapOf(
                "positionSizeKrw" to properties.positionSizeKrw,
                "maxPositions" to properties.maxPositions,
                "stopLossPercent" to properties.stopLossPercent,
                "takeProfitPercent" to properties.takeProfitPercent,
                "positionTimeoutMin" to properties.positionTimeoutMin
            ),
            "engine" to engineStatus,
            "openPositions" to openPositions.map { position ->
                mapOf(
                    "id" to position.id,
                    "market" to position.market,
                    "entryPrice" to position.entryPrice,
                    "entryTime" to position.entryTime.toString(),
                    "holdingMinutes" to ChronoUnit.MINUTES.between(position.entryTime, Instant.now()),
                    "trailingActive" to position.trailingActive,
                    "confluenceScore" to position.confluenceScore
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
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        val totalAlerts = alertRepository.countByDetectedAtBetween(startOfDay, endOfDay)
        val approvedAlerts = alertRepository.countApprovedBetween(startOfDay, endOfDay)
        val totalPnl = tradeRepository.sumPnlBetween(startOfDay, endOfDay)
        val winCount = tradeRepository.countWinningBetween(startOfDay, endOfDay)
        val loseCount = tradeRepository.countLosingBetween(startOfDay, endOfDay)
        val avgHolding = tradeRepository.avgHoldingMinutesBetween(startOfDay, endOfDay)

        val totalTrades = winCount + loseCount
        val winRate = if (totalTrades > 0) (winCount.toDouble() / totalTrades * 100) else 0.0

        return mapOf(
            "date" to today.toString(),
            "alerts" to mapOf(
                "total" to totalAlerts,
                "approved" to approvedAlerts,
                "approvalRate" to if (totalAlerts > 0) (approvedAlerts.toDouble() / totalAlerts * 100) else 0.0
            ),
            "trades" to mapOf(
                "total" to totalTrades,
                "wins" to winCount,
                "losses" to loseCount,
                "winRate" to winRate
            ),
            "pnl" to totalPnl,
            "avgHoldingMinutes" to avgHolding
        )
    }

    /**
     * 최근 경보 목록 조회
     */
    @GetMapping("/alerts")
    fun getAlerts(
        @RequestParam(defaultValue = "20") limit: Int
    ): List<Map<String, Any?>> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val startOfDay = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()

        return alertRepository.findByDetectedAtBetweenOrderByDetectedAtDesc(startOfDay, endOfDay)
            .take(limit)
            .map { alert ->
                mapOf(
                    "id" to alert.id,
                    "market" to alert.market,
                    "alertType" to alert.alertType,
                    "detectedAt" to alert.detectedAt.toString(),
                    "llmFilterResult" to alert.llmFilterResult,
                    "llmFilterReason" to alert.llmFilterReason,
                    "llmConfidence" to alert.llmConfidence,
                    "processed" to alert.processed
                )
            }
    }

    /**
     * 최근 트레이드 목록 조회
     */
    @GetMapping("/trades")
    fun getTrades(
        @RequestParam(defaultValue = "20") limit: Int
    ): List<Map<String, Any?>> {
        return tradeRepository.findTop20ByOrderByCreatedAtDesc()
            .take(limit)
            .map { trade ->
                mapOf(
                    "id" to trade.id,
                    "market" to trade.market,
                    "status" to trade.status,
                    "entryPrice" to trade.entryPrice,
                    "exitPrice" to trade.exitPrice,
                    "pnlAmount" to trade.pnlAmount,
                    "pnlPercent" to trade.pnlPercent,
                    "exitReason" to trade.exitReason,
                    "entryRsi" to trade.entryRsi,
                    "confluenceScore" to trade.confluenceScore,
                    "entryTime" to trade.entryTime.toString(),
                    "exitTime" to trade.exitTime?.toString(),
                    "llmConfidence" to trade.llmConfidence
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
        return summaryRepository.findTop30ByOrderByDateDesc()
            .take(days)
            .map { summary ->
                mapOf(
                    "date" to summary.date.toString(),
                    "totalAlerts" to summary.totalAlerts,
                    "approvedAlerts" to summary.approvedAlerts,
                    "totalTrades" to summary.totalTrades,
                    "winningTrades" to summary.winningTrades,
                    "losingTrades" to summary.losingTrades,
                    "totalPnl" to summary.totalPnl,
                    "winRate" to summary.winRate,
                    "avgHoldingMinutes" to summary.avgHoldingMinutes,
                    "reflectionSummary" to summary.reflectionSummary?.take(200)
                )
            }
    }

    /**
     * 수동 회고 실행
     */
    @PostMapping("/reflect")
    fun runReflection(): Map<String, Any?> {
        return try {
            val result = volumeSurgeReflector.runManualReflection()
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

    /**
     * 전략 활성화/비활성화 (런타임 변경은 불가, 설정 확인용)
     */
    @GetMapping("/config")
    fun getConfig(): VolumeSurgeProperties {
        return properties
    }

    /**
     * CryptoCompare 뉴스 검색 테스트 (디버깅용)
     */
    @GetMapping("/news/{symbol}")
    fun testNewsSearch(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "24") hours: Int
    ): Map<String, Any?> {
        return try {
            val news = cryptoCompareApi.getRecentNews(symbol.uppercase(), hours)
            mapOf(
                "success" to true,
                "symbol" to symbol.uppercase(),
                "hours" to hours,
                "count" to news.size,
                "news" to news.take(5).map { n ->
                    mapOf(
                        "title" to n.title,
                        "source" to n.source,
                        "publishedAgo" to n.getPublishedTimeAgo(),
                        "summary" to n.getSummary()
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
}
