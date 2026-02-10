package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.dca.DcaEngine
import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.util.apiFailure
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val bithumbPublicApi: BithumbPublicApi,
    private val tradingProperties: TradingProperties,
    private val memeScalperProperties: MemeScalperProperties,
    private val volumeSurgeProperties: VolumeSurgeProperties,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository,
    private val dcaPositionRepository: DcaPositionRepository,
    private val dcaEngine: DcaEngine,
    private val memeScalperEngine: MemeScalperEngine,
    private val volumeSurgeEngine: VolumeSurgeEngine
) {

    @GetMapping
    fun getDashboard(@RequestParam date: String?): DashboardResponse {
        // 날짜 파싱 (YYYY-MM-DD), null이면 오늘
        val targetDate = if (date.isNullOrBlank()) {
            java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        } else {
            try {
                java.time.LocalDate.parse(date)
            } catch (e: Exception) {
                // 파싱 실패하면 오늘 사용
                java.time.LocalDate.now(java.time.ZoneId.systemDefault())
            }
        }.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()

        // 잔고 조회
        val balances = try {
            bithumbPrivateApi.getBalances() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 총 자산 계산
        val krwBalance = balances.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO
        var totalAssetKrw = krwBalance.toDouble()

        val coinAssets = balances.mapNotNull { balance ->
            if (balance.currency == "KRW" || balance.balance <= BigDecimal.ZERO) return@mapNotNull null

            val market = "KRW-${balance.currency}"
            val currentPrice = try {
                bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
            } catch (e: Exception) {
                null
            }

            if (currentPrice != null) {
                val avgBuyPrice = balance.avgBuyPrice
                val value = balance.balance.toDouble() * currentPrice
                totalAssetKrw += value
                CoinAsset(
                    symbol = balance.currency,
                    quantity = balance.balance.toDouble(),
                    avgPrice = avgBuyPrice?.toDouble() ?: 0.0,
                    currentPrice = currentPrice,
                    value = value,
                    pnl = (currentPrice - (avgBuyPrice?.toDouble() ?: currentPrice)) * balance.balance.toDouble(),
                    pnlPercent = if (avgBuyPrice != null && avgBuyPrice > BigDecimal.ZERO) {
                        ((currentPrice - avgBuyPrice.toDouble()) / avgBuyPrice.toDouble()) * 100
                    } else 0.0
                )
            } else null
        }.filter { it.value >= 100.0 }

        // 열린 포지션 조회
        val openPositions = getOpenPositions()

        // 오늘 거래 내역
        val todayTrades = getClosedTrades(targetDate)

        // 오늘 통계
        val todayStats = getStats(targetDate)

        // 전체 통계
        val totalStats = getTotalStats()

        // 현재 조회 중인 날짜 표시
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd (E)")
        val currentDateStr = targetDate.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
        val requestDate = targetDate.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()  // YYYY-MM-DD

        // 활성화된 전략 목록
        val activeStrategies = mutableListOf<StrategyInfo>()
        if (memeScalperProperties.enabled) {
            activeStrategies.add(StrategyInfo("Meme Scalper", "단타 매매", "memescalper"))
        }
        if (volumeSurgeProperties.enabled) {
            activeStrategies.add(StrategyInfo("Volume Surge", "거래량 급등", "volumesurge"))
        }
        if (tradingProperties.enabled) {
            activeStrategies.add(StrategyInfo("Mean Reversion", "평균 회귀", "meanreversion"))
        }

        return DashboardResponse(
            krwBalance = krwBalance.toDouble(),
            totalAssetKrw = totalAssetKrw,
            coinAssets = coinAssets,
            openPositions = openPositions,
            todayTrades = todayTrades,
            todayStats = todayStats,
            totalStats = totalStats,
            currentDateStr = currentDateStr,
            requestDate = requestDate,
            activeStrategies = activeStrategies
        )
    }

    private fun getOpenPositions(): List<PositionInfo> {
        val positions = mutableListOf<PositionInfo>()

        // Meme Scalper 포지션
        memeScalperRepository.findByStatus("OPEN").forEach { trade ->
            val currentPrice = resolveCurrentPriceOrFallback(trade.market, trade.entryPrice)

            val pnl = (currentPrice - trade.entryPrice) * trade.quantity
            val pnlPercent = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100

            positions.add(PositionInfo(
                market = trade.market,
                strategy = "Meme Scalper",
                entryPrice = trade.entryPrice,
                currentPrice = currentPrice,
                quantity = trade.quantity,
                value = trade.entryPrice * trade.quantity,
                pnl = pnl,
                pnlPercent = pnlPercent,
                takeProfitPrice = 0.0,
                stopLossPrice = 0.0,
                entryTime = trade.entryTime.toString(),
                peakPrice = trade.peakPrice
            ))
        }

        // Volume Surge 포지션
        volumeSurgeRepository.findByStatus("OPEN").forEach { trade ->
            val currentPrice = resolveCurrentPriceOrFallback(trade.market, trade.entryPrice)

            val pnl = (currentPrice - trade.entryPrice) * trade.quantity
            val pnlPercent = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100

            val takeProfitPrice = trade.entryPrice * (1 + (trade.appliedTakeProfitPercent ?: 6.0) / 100)
            val stopLossPrice = trade.entryPrice * (1 - (trade.appliedStopLossPercent ?: 3.0) / 100)

            positions.add(PositionInfo(
                market = trade.market,
                strategy = "Volume Surge",
                entryPrice = trade.entryPrice,
                currentPrice = currentPrice,
                quantity = trade.quantity,
                value = trade.entryPrice * trade.quantity,
                pnl = pnl,
                pnlPercent = pnlPercent,
                takeProfitPrice = takeProfitPrice,
                stopLossPrice = stopLossPrice,
                entryTime = trade.entryTime.toString(),
                peakPrice = null
            ))
        }

        // DCA 포지션
        dcaPositionRepository.findByStatus("OPEN").forEach { pos ->
            val currentPrice = resolveCurrentPriceOrFallback(pos.market, pos.averagePrice)

            val pnl = (currentPrice - pos.averagePrice) * pos.totalQuantity
            val pnlPercent = ((currentPrice - pos.averagePrice) / pos.averagePrice) * 100

            positions.add(PositionInfo(
                market = pos.market,
                strategy = "DCA",
                entryPrice = pos.averagePrice,
                currentPrice = currentPrice,
                quantity = pos.totalQuantity,
                value = pos.averagePrice * pos.totalQuantity,
                pnl = pnl,
                pnlPercent = pnlPercent,
                takeProfitPrice = pos.averagePrice * (1 + pos.takeProfitPercent / 100),
                stopLossPrice = pos.averagePrice * (1 + pos.stopLossPercent / 100),
                entryTime = pos.createdAt.toString(),
                peakPrice = null
            ))
        }

        return positions.sortedByDescending { it.entryTime }
    }

    private fun getClosedTrades(targetDate: Instant): List<ClosedTradeInfo> {
        val trades = mutableListOf<ClosedTradeInfo>()
        val startOfDay = targetDate.atZone(java.time.ZoneId.systemDefault()).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        val endOfDay = startOfDay.plus(java.time.Duration.ofDays(1))

        // Meme Scalper 체결 내역
        memeScalperRepository.findByStatus("CLOSED")
            .filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
            .forEach { trade ->
                trades.add(ClosedTradeInfo(
                    market = trade.market,
                    strategy = "Meme Scalper",
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice ?: 0.0,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime.toString(),
                    entryTimeFormatted = formatInstant(trade.entryTime),
                    exitTime = trade.exitTime!!,
                    exitTimeFormatted = formatInstant(trade.exitTime!!),
                    holdingMinutes = java.time.Duration.between(trade.entryTime, trade.exitTime!!).toMinutes(),
                    pnlAmount = trade.pnlAmount ?: 0.0,
                    pnlPercent = trade.pnlPercent ?: 0.0,
                    exitReason = trade.exitReason ?: "UNKNOWN"
                ))
            }

        // Volume Surge 체결 내역
        volumeSurgeRepository.findByStatus("CLOSED")
            .filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
            .forEach { trade ->
                trades.add(ClosedTradeInfo(
                    market = trade.market,
                    strategy = "Volume Surge",
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice ?: 0.0,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime.toString(),
                    entryTimeFormatted = formatInstant(trade.entryTime),
                    exitTime = trade.exitTime!!,
                    exitTimeFormatted = formatInstant(trade.exitTime!!),
                    holdingMinutes = java.time.Duration.between(trade.entryTime, trade.exitTime!!).toMinutes(),
                    pnlAmount = trade.pnlAmount ?: 0.0,
                    pnlPercent = trade.pnlPercent ?: 0.0,
                    exitReason = trade.exitReason ?: "UNKNOWN"
                ))
            }

        // DCA 체결 내역
        dcaPositionRepository.findByStatus("CLOSED")
            .filter { isWithinRange(it.exitedAt, startOfDay, endOfDay) }
            .forEach { pos ->
                trades.add(ClosedTradeInfo(
                    market = pos.market,
                    strategy = "DCA",
                    entryPrice = pos.averagePrice,
                    exitPrice = pos.exitPrice ?: 0.0,
                    quantity = pos.totalQuantity,
                    entryTime = pos.createdAt.toString(),
                    entryTimeFormatted = formatInstant(pos.createdAt),
                    exitTime = pos.exitedAt!!,
                    exitTimeFormatted = formatInstant(pos.exitedAt!!),
                    holdingMinutes = java.time.Duration.between(pos.createdAt, pos.exitedAt!!).toMinutes(),
                    pnlAmount = pos.realizedPnl ?: 0.0,
                    pnlPercent = pos.realizedPnlPercent ?: 0.0,
                    exitReason = pos.exitReason ?: "UNKNOWN"
                ))
            }

        return trades.sortedByDescending { it.exitTime }
    }

    private fun getStats(targetDate: Instant): StatsInfo {
        val startOfDay = targetDate.atZone(java.time.ZoneId.systemDefault()).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        val endOfDay = startOfDay.plus(java.time.Duration.ofDays(1))

        val memeTrades = memeScalperRepository.findByStatus("CLOSED").filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
        val volumeTrades = volumeSurgeRepository.findByStatus("CLOSED").filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
        val dcaTrades = dcaPositionRepository.findByStatus("CLOSED").filter { isWithinRange(it.exitedAt, startOfDay, endOfDay) }

        val allPnl = collectPnlValues(memeTrades, volumeTrades, dcaTrades)

        // 트레이딩에 사용한 총 금액 (매수 금액 합계)
        val totalInvested = memeTrades.sumOf { it.entryPrice * it.quantity } +
                           volumeTrades.sumOf { it.entryPrice * it.quantity } +
                           dcaTrades.sumOf { it.averagePrice * it.totalQuantity }

        return buildStatsInfo(
            totalTrades = memeTrades.size + volumeTrades.size + dcaTrades.size,
            pnlValues = allPnl,
            totalInvested = totalInvested
        )
    }

    private fun getTotalStats(): StatsInfo {
        val memeTrades = memeScalperRepository.findByStatus("CLOSED")
        val volumeTrades = volumeSurgeRepository.findByStatus("CLOSED")
        val dcaTrades = dcaPositionRepository.findByStatus("CLOSED")

        return buildStatsInfo(
            totalTrades = memeTrades.size + volumeTrades.size + dcaTrades.size,
            pnlValues = collectPnlValues(memeTrades, volumeTrades, dcaTrades)
        )
    }

    @PostMapping("/manual-close")
    fun manualClose(@RequestParam market: String, @RequestParam strategy: String): Map<String, Any?> {
        val result = when (strategy) {
            "Meme Scalper" -> memeScalperEngine.manualClose(market)
            "Volume Surge" -> volumeSurgeEngine.manualClose(market)
            "DCA" -> dcaEngine.manualClose(market)
            else -> apiFailure("알 수 없는 전략: $strategy")
        }
        return result
    }

    private fun resolveCurrentPriceOrFallback(market: String, fallback: Double): Double {
        return try {
            bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble() ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun isWithinRange(time: Instant?, start: Instant, end: Instant): Boolean {
        return time != null && time.isAfter(start) && time.isBefore(end)
    }

    private fun collectPnlValues(
        memeTrades: List<com.ant.cointrading.repository.MemeScalperTradeEntity>,
        volumeTrades: List<com.ant.cointrading.repository.VolumeSurgeTradeEntity>,
        dcaTrades: List<com.ant.cointrading.repository.DcaPositionEntity>
    ): List<Double> {
        return memeTrades.mapNotNull { it.pnlAmount } +
            volumeTrades.mapNotNull { it.pnlAmount } +
            dcaTrades.mapNotNull { it.realizedPnl }
    }

    private fun buildStatsInfo(
        totalTrades: Int,
        pnlValues: List<Double>,
        totalInvested: Double = 0.0
    ): StatsInfo {
        val totalPnl = pnlValues.sum()
        val winCount = pnlValues.count { it > 0 }
        val lossCount = pnlValues.count { it < 0 }
        val winRate = if (pnlValues.isNotEmpty()) winCount.toDouble() / pnlValues.size else 0.0
        val roi = if (totalInvested > 0) (totalPnl / totalInvested) * 100 else 0.0
        return StatsInfo(
            totalTrades = totalTrades,
            winCount = winCount,
            lossCount = lossCount,
            totalPnl = totalPnl,
            winRate = winRate,
            totalInvested = totalInvested,
            roi = roi
        )
    }

    private fun formatInstant(instant: Instant): String {
        val kstZone = java.time.ZoneId.of("Asia/Seoul")
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return instant.atZone(kstZone).format(formatter)
    }
}

// Response DTOs
data class DashboardResponse(
    val krwBalance: Double,
    val totalAssetKrw: Double,
    val coinAssets: List<CoinAsset>,
    val openPositions: List<PositionInfo>,
    val todayTrades: List<ClosedTradeInfo>,
    val todayStats: StatsInfo,
    val totalStats: StatsInfo,
    val currentDateStr: String,
    val requestDate: String,  // YYYY-MM-DD format
    val activeStrategies: List<StrategyInfo> = emptyList()
)

data class StrategyInfo(
    val name: String,           // 전략 이름 (예: "Meme Scalper")
    val description: String,    // 설명 (예: "단타 매매")
    val code: String            // 코드 (CSS class용)
)

data class CoinAsset(
    val symbol: String,
    val quantity: Double,
    val avgPrice: Double,
    val currentPrice: Double,
    val value: Double,
    val pnl: Double,
    val pnlPercent: Double
)

data class PositionInfo(
    val market: String,
    val strategy: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val quantity: Double,
    val value: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val takeProfitPrice: Double,
    val stopLossPrice: Double,
    val entryTime: String,
    val peakPrice: Double?
)

data class ClosedTradeInfo(
    val market: String,
    val strategy: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val entryTime: String,
    val entryTimeFormatted: String,
    val exitTime: Instant,
    val exitTimeFormatted: String,
    val holdingMinutes: Long,
    val pnlAmount: Double,
    val pnlPercent: Double,
    val exitReason: String
)

data class StatsInfo(
    val totalTrades: Int,
    val winCount: Int,
    val lossCount: Int,
    val totalPnl: Double,
    val winRate: Double,
    val totalInvested: Double = 0.0,  // 트레이딩에 사용한 총 금액
    val roi: Double = 0.0            // 트레이딩 금액 대비 수익률 (%)
)
