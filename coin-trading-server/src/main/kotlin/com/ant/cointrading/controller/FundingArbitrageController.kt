package com.ant.cointrading.controller

import com.ant.cointrading.api.binance.FundingOpportunity
import com.ant.cointrading.config.FundingArbitrageProperties
import com.ant.cointrading.fundingarb.FundingMonitorStatus
import com.ant.cointrading.fundingarb.FundingRateMonitor
import com.ant.cointrading.repository.FundingArbPositionRepository
import com.ant.cointrading.repository.FundingDailyStatsRepository
import com.ant.cointrading.repository.FundingPaymentRepository
import com.ant.cointrading.repository.FundingRateRepository
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Funding Rate Arbitrage API 컨트롤러
 *
 * Phase 1: 모니터링 전용 API
 * - 펀딩 비율 조회
 * - 수동 스캔
 * - 기회 목록 조회
 */
@RestController
@RequestMapping("/api/funding")
class FundingArbitrageController(
    private val properties: FundingArbitrageProperties,
    private val fundingRateMonitor: FundingRateMonitor,
    private val fundingRateRepository: FundingRateRepository,
    private val positionRepository: FundingArbPositionRepository,
    private val paymentRepository: FundingPaymentRepository,
    private val dailyStatsRepository: FundingDailyStatsRepository
) {

    /**
     * 모니터링 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): FundingMonitorStatus {
        return fundingRateMonitor.getStatus()
    }

    /**
     * 현재 펀딩 기회 조회 (수동 스캔)
     */
    @GetMapping("/opportunities")
    fun getOpportunities(): List<OpportunityDto> {
        val opportunities = fundingRateMonitor.manualScan()
        return opportunities.map { it.toDto() }
    }

    /**
     * 수동 스캔 실행
     */
    @PostMapping("/scan")
    fun runScan(): ScanResultDto {
        val opportunities = fundingRateMonitor.manualScan()
        return ScanResultDto(
            scanTime = Instant.now().toString(),
            totalOpportunities = opportunities.size,
            opportunities = opportunities.map { it.toDto() }
        )
    }

    /**
     * 특정 심볼 펀딩 정보 조회
     */
    @GetMapping("/info/{symbol}")
    fun getFundingInfo(@PathVariable symbol: String): Map<String, Any?> {
        val info = fundingRateMonitor.getFundingInfo(symbol.uppercase())

        return if (info != null) {
            mapOf(
                "symbol" to info.symbol,
                "currentFundingRate" to info.currentFundingRate,
                "currentAnnualizedRate" to String.format("%.2f%%", info.currentAnnualizedRate),
                "avgFundingRate" to info.avgFundingRate,
                "avgAnnualizedRate" to String.format("%.2f%%", info.avgAnnualizedRate),
                "nextFundingTime" to info.nextFundingTime.toString(),
                "minutesUntilFunding" to ChronoUnit.MINUTES.between(Instant.now(), info.nextFundingTime),
                "markPrice" to info.markPrice,
                "indexPrice" to info.indexPrice,
                "isStable" to info.isStable(),
                "recentHistory" to info.recentHistory.take(5).map {
                    mapOf(
                        "fundingRate" to it.fundingRate,
                        "fundingTime" to it.fundingTime.toString()
                    )
                }
            )
        } else {
            mapOf(
                "error" to "Failed to fetch funding info for $symbol"
            )
        }
    }

    /**
     * 펀딩 비율 히스토리 조회
     */
    @GetMapping("/history/{symbol}")
    fun getFundingHistory(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "30") limit: Int
    ): List<Map<String, Any?>> {
        val history = fundingRateRepository.findBySymbolOrderByFundingTimeDesc(symbol.uppercase())
            .take(limit.coerceAtMost(100))

        return history.map {
            mapOf(
                "id" to it.id,
                "exchange" to it.exchange,
                "symbol" to it.symbol,
                "fundingRate" to it.fundingRate,
                "fundingRatePercent" to String.format("%.4f%%", it.fundingRate * 100),
                "annualizedRate" to it.annualizedRate?.let { rate -> String.format("%.2f%%", rate) },
                "fundingTime" to it.fundingTime.toString(),
                "markPrice" to it.markPrice
            )
        }
    }

    /**
     * 전략 설정 조회
     */
    @GetMapping("/config")
    fun getConfig(): Map<String, Any> {
        return mapOf(
            "enabled" to properties.enabled,
            "autoTradingEnabled" to properties.autoTradingEnabled,
            "monitoringIntervalMs" to properties.monitoringIntervalMs,
            "minAnnualizedRate" to properties.minAnnualizedRate,
            "maxMinutesUntilFunding" to properties.maxMinutesUntilFunding,
            "maxCapitalRatio" to properties.maxCapitalRatio,
            "maxSinglePositionKrw" to properties.maxSinglePositionKrw,
            "maxPositions" to properties.maxPositions,
            "maxLeverage" to properties.maxLeverage,
            "highRateAlertThreshold" to properties.highRateAlertThreshold,
            "symbols" to properties.symbols
        )
    }

    /**
     * 최신 펀딩 비율 (심볼별)
     */
    @GetMapping("/rates/latest")
    fun getLatestRates(): List<Map<String, Any?>> {
        return fundingRateRepository.findLatestBySymbol().map {
            mapOf(
                "symbol" to it.symbol,
                "exchange" to it.exchange,
                "fundingRate" to it.fundingRate,
                "fundingRatePercent" to String.format("%.4f%%", it.fundingRate * 100),
                "annualizedRate" to it.annualizedRate?.let { rate -> String.format("%.2f%%", rate) },
                "fundingTime" to it.fundingTime.toString(),
                "markPrice" to it.markPrice
            )
        }
    }

    /**
     * 포지션 목록 조회
     */
    @GetMapping("/positions")
    fun getPositions(
        @RequestParam(defaultValue = "OPEN") status: String
    ): List<Map<String, Any?>> {
        val positions = if (status == "ALL") {
            positionRepository.findTop20ByOrderByCreatedAtDesc()
        } else {
            positionRepository.findByStatus(status)
        }

        return positions.map {
            mapOf(
                "id" to it.id,
                "symbol" to it.symbol,
                "spotExchange" to it.spotExchange,
                "perpExchange" to it.perpExchange,
                "spotQuantity" to it.spotQuantity,
                "perpQuantity" to it.perpQuantity,
                "spotEntryPrice" to it.spotEntryPrice,
                "perpEntryPrice" to it.perpEntryPrice,
                "entryFundingRate" to it.entryFundingRate,
                "entryTime" to it.entryTime.toString(),
                "exitTime" to it.exitTime?.toString(),
                "totalFundingReceived" to it.totalFundingReceived,
                "totalTradingCost" to it.totalTradingCost,
                "netPnl" to it.netPnl,
                "status" to it.status,
                "exitReason" to it.exitReason
            )
        }
    }

    /**
     * 일일 통계 조회
     */
    @GetMapping("/stats/daily")
    fun getDailyStats(
        @RequestParam(defaultValue = "30") days: Int
    ): List<Map<String, Any?>> {
        return dailyStatsRepository.findTop30ByOrderByDateDesc()
            .take(days.coerceAtMost(30))
            .map {
                mapOf(
                    "date" to it.date.toString(),
                    "totalPositions" to it.totalPositions,
                    "activePositions" to it.activePositions,
                    "fundingCollected" to it.fundingCollected,
                    "tradingCosts" to it.tradingCosts,
                    "netPnl" to it.netPnl,
                    "avgFundingRate" to it.avgFundingRate
                )
            }
    }

    /**
     * 성과 요약
     */
    @GetMapping("/performance")
    fun getPerformance(
        @RequestParam(defaultValue = "30") days: Int
    ): Map<String, Any?> {
        val since = LocalDate.now(ZoneId.of("UTC")).minusDays(days.toLong())
        val sinceInstant = since.atStartOfDay(ZoneId.of("UTC")).toInstant()

        val closedPositions = positionRepository.findClosedSince(sinceInstant)
        val openPositions = positionRepository.countOpenPositions()
        val totalNetPnl = positionRepository.sumNetPnl() ?: 0.0
        val totalFundingPayments = paymentRepository.sumPaymentsSince(sinceInstant) ?: 0.0

        val profitablePositions = closedPositions.count { (it.netPnl ?: 0.0) > 0 }
        val winRate = if (closedPositions.isNotEmpty()) {
            profitablePositions.toDouble() / closedPositions.size * 100
        } else 0.0

        return mapOf(
            "period" to "${days}일",
            "openPositions" to openPositions,
            "closedPositions" to closedPositions.size,
            "profitablePositions" to profitablePositions,
            "winRate" to String.format("%.1f%%", winRate),
            "totalNetPnl" to totalNetPnl,
            "totalFundingReceived" to totalFundingPayments,
            "avgPnlPerPosition" to if (closedPositions.isNotEmpty()) {
                closedPositions.mapNotNull { it.netPnl }.average()
            } else 0.0
        )
    }
}

/**
 * 펀딩 기회 DTO
 */
data class OpportunityDto(
    val exchange: String,
    val symbol: String,
    val fundingRate: String,
    val annualizedRate: String,
    val nextFundingTime: String,
    val minutesUntilFunding: Long,
    val markPrice: Double,
    val indexPrice: Double,
    val isRecommendedEntry: Boolean
)

/**
 * 스캔 결과 DTO
 */
data class ScanResultDto(
    val scanTime: String,
    val totalOpportunities: Int,
    val opportunities: List<OpportunityDto>
)

/**
 * FundingOpportunity 확장 함수
 */
private fun FundingOpportunity.toDto() = OpportunityDto(
    exchange = exchange,
    symbol = symbol,
    fundingRate = fundingRatePercent(),
    annualizedRate = annualizedRateFormatted(),
    nextFundingTime = nextFundingTime.toString(),
    minutesUntilFunding = minutesUntilFunding(),
    markPrice = markPrice,
    indexPrice = indexPrice,
    isRecommendedEntry = isRecommendedEntry()
)
