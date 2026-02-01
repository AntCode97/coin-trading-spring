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
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.math.BigDecimal

@Controller
@RequestMapping("/dashboard")
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
    fun dashboard(model: Model): String {
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
                val value = balance.balance.toDouble() * currentPrice
                totalAssetKrw += value
                CoinAsset(
                    symbol = balance.currency,
                    quantity = balance.balance.toDouble(),
                    avgPrice = balance.avgBuyPrice?.toDouble() ?: 0.0,
                    currentPrice = currentPrice,
                    value = value,
                    pnl = (currentPrice - (balance.avgBuyPrice?.toDouble() ?: currentPrice)) * balance.balance.toDouble(),
                    pnlPercent = if (balance.avgBuyPrice != null && balance.avgBuyPrice!! > BigDecimal.ZERO) {
                        ((currentPrice - balance.avgBuyPrice.toDouble()) / balance.avgBuyPrice.toDouble()) * 100
                    } else 0.0
                )
            } else null
        }.filter { it.value >= 100.0 }  // 평가액 100원 미만 필터링

        // 열린 포지션 조회 (간단 버전)
        val openPositions = getOpenPositionsSimple()

        // 오늘 거래 내역
        val todayTrades = getTodayTrades()

        // 오늘 통계
        val todayStats = getTodayStats()

        // 전체 통계
        val totalStats = getTotalStats()

        model.addAttribute("krwBalance", krwBalance.toDouble())
        model.addAttribute("totalAssetKrw", totalAssetKrw)
        model.addAttribute("coinAssets", coinAssets)
        model.addAttribute("openPositions", openPositions)
        model.addAttribute("todayTrades", todayTrades)
        model.addAttribute("todayStats", todayStats)
        model.addAttribute("totalStats", totalStats)

        // 엔진 상태
        val memeOpenCount = memeScalperRepository.findByStatus("OPEN").size
        val volumeOpenCount = volumeSurgeRepository.findByStatus("OPEN").size
        val dcaOpenCount = dcaPositionRepository.findByStatus("OPEN").size

        model.addAttribute("memeScalperEnabled", if (memeScalperProperties.enabled) "true" else "false")
        model.addAttribute("memeScalperOpenPositions", memeOpenCount)
        model.addAttribute("volumeSurgeEnabled", if (volumeSurgeProperties.enabled) "true" else "false")
        model.addAttribute("volumeSurgeOpenPositions", volumeOpenCount)
        model.addAttribute("tradingEngineEnabled", if (tradingProperties.enabled) "true" else "false")
        model.addAttribute("tradingEngineMarkets", tradingProperties.markets.joinToString(", "))
        model.addAttribute("dcaEngineEnabled", if (tradingProperties.enabled) "true" else "false")
        model.addAttribute("dcaEngineOpenPositions", dcaOpenCount)

        return "dashboard"
    }

    private fun getOpenPositionsSimple(): List<PositionInfo> {
        val positions = mutableListOf<PositionInfo>()

        // Meme Scalper 포지션
        memeScalperRepository.findByStatus("OPEN").forEach { trade ->
            val currentPrice = try {
                bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice?.toDouble() ?: trade.entryPrice
            } catch (e: Exception) {
                trade.entryPrice
            }

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
                takeProfitPrice = 0.0,  // 간단화
                stopLossPrice = 0.0,   // 간단화
                entryTime = trade.entryTime,
                peakPrice = trade.peakPrice
            ))
        }

        // Volume Surge 포지션
        volumeSurgeRepository.findByStatus("OPEN").forEach { trade ->
            val currentPrice = try {
                bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice?.toDouble() ?: trade.entryPrice
            } catch (e: Exception) {
                trade.entryPrice
            }

            val pnl = (currentPrice - trade.entryPrice) * trade.quantity
            val pnlPercent = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100

            // 익절가/손절가 계산 (appliedTakeProfitPercent/appliedStopLossPercent 사용)
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
                entryTime = trade.entryTime,
                peakPrice = null
            ))
        }

        // DCA 포지션
        dcaPositionRepository.findByStatus("OPEN").forEach { pos ->
            val currentPrice = try {
                bithumbPublicApi.getCurrentPrice(pos.market)?.firstOrNull()?.tradePrice?.toDouble() ?: pos.averagePrice
            } catch (e: Exception) {
                pos.averagePrice
            }

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
                entryTime = pos.createdAt,
                peakPrice = null
            ))
        }

        return positions.sortedByDescending { it.entryTime }
    }

    /**
     * 오늘 체결된 거래 내역 조회
     */
    private fun getTodayTrades(): List<ClosedTradeInfo> {
        val trades = mutableListOf<ClosedTradeInfo>()
        val startOfDay = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()

        // Meme Scalper 체결 내역
        memeScalperRepository.findByStatus("CLOSED")
            .filter { it.exitTime != null && it.exitTime!!.isAfter(startOfDay) }
            .forEach { trade ->
                trades.add(ClosedTradeInfo(
                    market = trade.market,
                    strategy = "Meme Scalper",
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice ?: 0.0,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime,
                    exitTime = trade.exitTime!!,
                    holdingMinutes = java.time.Duration.between(trade.entryTime, trade.exitTime!!).toMinutes(),
                    pnlAmount = trade.pnlAmount ?: 0.0,
                    pnlPercent = trade.pnlPercent ?: 0.0,
                    exitReason = trade.exitReason ?: "UNKNOWN"
                ))
            }

        // Volume Surge 체결 내역
        volumeSurgeRepository.findByStatus("CLOSED")
            .filter { it.exitTime != null && it.exitTime!!.isAfter(startOfDay) }
            .forEach { trade ->
                trades.add(ClosedTradeInfo(
                    market = trade.market,
                    strategy = "Volume Surge",
                    entryPrice = trade.entryPrice,
                    exitPrice = trade.exitPrice ?: 0.0,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime,
                    exitTime = trade.exitTime!!,
                    holdingMinutes = java.time.Duration.between(trade.entryTime, trade.exitTime!!).toMinutes(),
                    pnlAmount = trade.pnlAmount ?: 0.0,
                    pnlPercent = trade.pnlPercent ?: 0.0,
                    exitReason = trade.exitReason ?: "UNKNOWN"
                ))
            }

        // DCA 체결 내역
        dcaPositionRepository.findByStatus("CLOSED")
            .filter { it.exitedAt != null && it.exitedAt!!.isAfter(startOfDay) }
            .forEach { pos ->
                trades.add(ClosedTradeInfo(
                    market = pos.market,
                    strategy = "DCA",
                    entryPrice = pos.averagePrice,
                    exitPrice = pos.exitPrice ?: 0.0,
                    quantity = pos.totalQuantity,
                    entryTime = pos.createdAt,
                    exitTime = pos.exitedAt!!,
                    holdingMinutes = java.time.Duration.between(pos.createdAt, pos.exitedAt!!).toMinutes(),
                    pnlAmount = pos.realizedPnl ?: 0.0,
                    pnlPercent = pos.realizedPnlPercent ?: 0.0,
                    exitReason = pos.exitReason ?: "UNKNOWN"
                ))
            }

        return trades.sortedByDescending { it.exitTime }
    }

    private fun getTodayStats(): StatsInfo {
        val now = java.time.Instant.now()
        val startOfDay = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()

        val memeTrades = memeScalperRepository.findByStatus("CLOSED").filter { it.exitTime != null && it.exitTime!!.isAfter(startOfDay) }
        val volumeTrades = volumeSurgeRepository.findByStatus("CLOSED").filter { it.exitTime != null && it.exitTime!!.isAfter(startOfDay) }
        val dcaTrades = dcaPositionRepository.findByStatus("CLOSED").filter { it.exitedAt != null && it.exitedAt!!.isAfter(startOfDay) }

        val allPnl = memeTrades.mapNotNull { it.pnlAmount }.filterNotNull() +
                     volumeTrades.mapNotNull { it.pnlAmount }.filterNotNull() +
                     dcaTrades.mapNotNull { it.realizedPnl }.filterNotNull()
        val totalPnl = allPnl.sum()
        val winCount = allPnl.count { it > 0 }
        val lossCount = allPnl.count { it < 0 }

        return StatsInfo(
            totalTrades = memeTrades.size + volumeTrades.size + dcaTrades.size,
            winCount = winCount,
            lossCount = lossCount,
            totalPnl = totalPnl,
            winRate = if (allPnl.isNotEmpty()) winCount.toDouble() / allPnl.size else 0.0
        )
    }

    private fun getTotalStats(): StatsInfo {
        val memeTrades = memeScalperRepository.findByStatus("CLOSED")
        val volumeTrades = volumeSurgeRepository.findByStatus("CLOSED")
        val dcaTrades = dcaPositionRepository.findByStatus("CLOSED")

        val allPnl = memeTrades.mapNotNull { it.pnlAmount }.filterNotNull() +
                     volumeTrades.mapNotNull { it.pnlAmount }.filterNotNull() +
                     dcaTrades.mapNotNull { it.realizedPnl }.filterNotNull()
        val totalPnl = allPnl.sum()
        val winCount = allPnl.count { it > 0 }
        val lossCount = allPnl.count { it < 0 }

        return StatsInfo(
            totalTrades = memeTrades.size + volumeTrades.size + dcaTrades.size,
            winCount = winCount,
            lossCount = lossCount,
            totalPnl = totalPnl,
            winRate = if (allPnl.isNotEmpty()) winCount.toDouble() / allPnl.size else 0.0
        )
    }

    /**
     * 수동 매도
     */
    @PostMapping("/manual-close")
    @ResponseBody
    fun manualClose(@RequestParam market: String, @RequestParam strategy: String): Map<String, Any?> {
        val result = when (strategy) {
            "Meme Scalper" -> memeScalperEngine.manualClose(market)
            "Volume Surge" -> volumeSurgeEngine.manualClose(market)
            "DCA" -> dcaEngine.manualClose(market)
            else -> mapOf("success" to false, "error" to "알 수 없는 전략: $strategy")
        }
        return result
    }
}

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
    val entryTime: java.time.Instant,
    val peakPrice: Double?
)

data class StatsInfo(
    val totalTrades: Int,
    val winCount: Int,
    val lossCount: Int,
    val totalPnl: Double,
    val winRate: Double
)

data class EngineStatusInfo(
    val enabled: Boolean,
    val openPositions: Int
)

data class ClosedTradeInfo(
    val market: String,
    val strategy: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val entryTime: java.time.Instant,
    val exitTime: java.time.Instant,
    val holdingMinutes: Long,
    val pnlAmount: Double,
    val pnlPercent: Double,
    val exitReason: String,
    val entryTimeFormatted: String = formatInstant(entryTime),
    val exitTimeFormatted: String = formatInstant(exitTime)
) {
    companion object {
        private val KST_ZONE = java.time.ZoneId.of("Asia/Seoul")
        private val DATETIME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun formatInstant(instant: java.time.Instant): String {
            return instant.atZone(KST_ZONE).format(DATETIME_FORMATTER)
        }
    }
}
