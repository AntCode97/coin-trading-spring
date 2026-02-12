package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import org.slf4j.LoggerFactory
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val STRATEGY_MEME_SCALPER = "Meme Scalper"
        const val STRATEGY_VOLUME_SURGE = "Volume Surge"
        const val STRATEGY_DCA = "DCA"
        const val STRATEGY_MEAN_REVERSION = "Mean Reversion"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_CLOSED = "CLOSED"
        const val KRW = "KRW"
        const val MIN_COIN_ASSET_VALUE_KRW = 100.0
        val SYSTEM_ZONE: ZoneId = ZoneId.systemDefault()
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val DASHBOARD_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd (E)")
        val TRADE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }

    private val manualCloseHandlers by lazy {
        mapOf<String, (String) -> Map<String, Any?>>(
            STRATEGY_MEME_SCALPER to memeScalperEngine::manualClose,
            STRATEGY_VOLUME_SURGE to volumeSurgeEngine::manualClose,
            STRATEGY_DCA to dcaEngine::manualClose
        )
    }

    @GetMapping
    fun getDashboard(@RequestParam date: String?): DashboardResponse {
        val targetDate = resolveTargetDate(date)
        val balances = loadBalancesSafely()

        // 총 자산 계산
        val krwBalance = balances.find { it.currency == KRW }?.balance ?: BigDecimal.ZERO
        var totalAssetKrw = krwBalance.toDouble()

        // 코인 잔고 필터링
        val coinBalances = balances.filter { it.currency != KRW && it.balance > BigDecimal.ZERO }

        // 가격 일괄 조회 (1회 API 호출)
        val priceMap = fetchPriceMap(coinBalances.map { "KRW-${it.currency}" })

        val coinAssets = coinBalances.mapNotNull { balance ->
            val market = "KRW-${balance.currency}"
            val currentPrice = priceMap[market]

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
            } else {
                log.warn("가격 조회 실패로 코인자산 제외: {}", market)
                null
            }
        }.filter { it.value >= MIN_COIN_ASSET_VALUE_KRW }

        // 열린 포지션 조회
        val openPositions = getOpenPositions()

        // 오늘 거래 내역
        val todayTrades = getClosedTrades(targetDate)

        // 오늘 통계
        val todayStats = getStats(targetDate)

        // 전체 통계
        val totalStats = getTotalStats()

        // 현재 조회 중인 날짜 표시
        val targetLocalDate = targetDate.atZone(SYSTEM_ZONE).toLocalDate()
        val currentDateStr = targetLocalDate.format(DASHBOARD_DATE_FORMATTER)
        val requestDate = targetLocalDate.toString()

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
            activeStrategies = buildActiveStrategies()
        )
    }

    private fun getOpenPositions(): List<PositionInfo> {
        val memePositions = memeScalperRepository.findByStatus(STATUS_OPEN)
        val volumePositions = volumeSurgeRepository.findByStatus(STATUS_OPEN)
        val dcaPositions = dcaPositionRepository.findByStatus(STATUS_OPEN)

        // 모든 열린 포지션의 마켓을 모아 가격 일괄 조회
        val allMarkets = mutableSetOf<String>()
        memePositions.forEach { allMarkets.add(it.market) }
        volumePositions.forEach { allMarkets.add(it.market) }
        dcaPositions.forEach { allMarkets.add(it.market) }

        val priceMap = fetchPriceMap(allMarkets.toList())

        val positions = mutableListOf<PositionInfo>()

        // Meme Scalper 포지션
        memePositions.forEach { trade ->
            positions.add(
                buildPositionInfo(
                    market = trade.market,
                    strategy = STRATEGY_MEME_SCALPER,
                    entryPrice = trade.entryPrice,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime,
                    peakPrice = trade.peakPrice,
                    priceMap = priceMap
                )
            )
        }

        // Volume Surge 포지션
        volumePositions.forEach { trade ->
            val takeProfitPrice = trade.entryPrice * (1 + (trade.appliedTakeProfitPercent ?: 6.0) / 100)
            val stopLossPrice = trade.entryPrice * (1 - (trade.appliedStopLossPercent ?: 3.0) / 100)

            positions.add(
                buildPositionInfo(
                    market = trade.market,
                    strategy = STRATEGY_VOLUME_SURGE,
                    entryPrice = trade.entryPrice,
                    quantity = trade.quantity,
                    entryTime = trade.entryTime,
                    takeProfitPrice = takeProfitPrice,
                    stopLossPrice = stopLossPrice,
                    priceMap = priceMap
                )
            )
        }

        // DCA 포지션
        dcaPositions.forEach { pos ->
            positions.add(
                buildPositionInfo(
                    market = pos.market,
                    strategy = STRATEGY_DCA,
                    entryPrice = pos.averagePrice,
                    quantity = pos.totalQuantity,
                    entryTime = pos.createdAt,
                    takeProfitPrice = pos.averagePrice * (1 + pos.takeProfitPercent / 100),
                    stopLossPrice = pos.averagePrice * (1 + pos.stopLossPercent / 100),
                    priceMap = priceMap
                )
            )
        }

        return positions.sortedByDescending { it.entryTime }
    }

    private fun getClosedTrades(targetDate: Instant): List<ClosedTradeInfo> {
        val trades = mutableListOf<ClosedTradeInfo>()
        val (startOfDay, endOfDay) = dayRange(targetDate)

        // Meme Scalper 체결 내역
        memeScalperRepository.findByStatus(STATUS_CLOSED)
            .forEach { trade ->
                val exitTime = trade.exitTime ?: return@forEach
                if (!isWithinRange(exitTime, startOfDay, endOfDay)) return@forEach

                trades.add(
                    buildClosedTradeInfo(
                        market = trade.market,
                        strategy = STRATEGY_MEME_SCALPER,
                        entryPrice = trade.entryPrice,
                        exitPrice = trade.exitPrice ?: 0.0,
                        quantity = trade.quantity,
                        entryTime = trade.entryTime,
                        exitTime = exitTime,
                        pnlAmount = trade.pnlAmount ?: 0.0,
                        pnlPercent = trade.pnlPercent ?: 0.0,
                        exitReason = trade.exitReason ?: "UNKNOWN"
                    )
                )
            }

        // Volume Surge 체결 내역
        volumeSurgeRepository.findByStatus(STATUS_CLOSED)
            .forEach { trade ->
                val exitTime = trade.exitTime ?: return@forEach
                if (!isWithinRange(exitTime, startOfDay, endOfDay)) return@forEach

                trades.add(
                    buildClosedTradeInfo(
                        market = trade.market,
                        strategy = STRATEGY_VOLUME_SURGE,
                        entryPrice = trade.entryPrice,
                        exitPrice = trade.exitPrice ?: 0.0,
                        quantity = trade.quantity,
                        entryTime = trade.entryTime,
                        exitTime = exitTime,
                        pnlAmount = trade.pnlAmount ?: 0.0,
                        pnlPercent = trade.pnlPercent ?: 0.0,
                        exitReason = trade.exitReason ?: "UNKNOWN"
                    )
                )
            }

        // DCA 체결 내역
        dcaPositionRepository.findByStatus(STATUS_CLOSED)
            .forEach { pos ->
                val exitTime = pos.exitedAt ?: return@forEach
                if (!isWithinRange(exitTime, startOfDay, endOfDay)) return@forEach

                trades.add(
                    buildClosedTradeInfo(
                        market = pos.market,
                        strategy = STRATEGY_DCA,
                        entryPrice = pos.averagePrice,
                        exitPrice = pos.exitPrice ?: 0.0,
                        quantity = pos.totalQuantity,
                        entryTime = pos.createdAt,
                        exitTime = exitTime,
                        pnlAmount = pos.realizedPnl ?: 0.0,
                        pnlPercent = pos.realizedPnlPercent ?: 0.0,
                        exitReason = pos.exitReason ?: "UNKNOWN"
                    )
                )
            }

        return trades.sortedByDescending { it.exitTime }
    }

    private fun getStats(targetDate: Instant): StatsInfo {
        val (startOfDay, endOfDay) = dayRange(targetDate)

        val memeTrades = memeScalperRepository.findByStatus(STATUS_CLOSED).filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
        val volumeTrades = volumeSurgeRepository.findByStatus(STATUS_CLOSED).filter { isWithinRange(it.exitTime, startOfDay, endOfDay) }
        val dcaTrades = dcaPositionRepository.findByStatus(STATUS_CLOSED).filter { isWithinRange(it.exitedAt, startOfDay, endOfDay) }

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
        val memeTrades = memeScalperRepository.findByStatus(STATUS_CLOSED)
        val volumeTrades = volumeSurgeRepository.findByStatus(STATUS_CLOSED)
        val dcaTrades = dcaPositionRepository.findByStatus(STATUS_CLOSED)

        return buildStatsInfo(
            totalTrades = memeTrades.size + volumeTrades.size + dcaTrades.size,
            pnlValues = collectPnlValues(memeTrades, volumeTrades, dcaTrades)
        )
    }

    @PostMapping("/manual-close")
    fun manualClose(@RequestParam market: String, @RequestParam strategy: String): Map<String, Any?> {
        return manualCloseHandlers[strategy]?.invoke(market)
            ?: apiFailure("알 수 없는 전략: $strategy")
    }

    /**
     * 마켓 코드 목록에 대해 현재가를 일괄 조회하여 Map으로 반환.
     * Bithumb /v1/ticker API는 쉼표 구분 다중 마켓을 지원하므로 1회 호출로 처리.
     */
    private fun fetchPriceMap(markets: List<String>): Map<String, Double> {
        if (markets.isEmpty()) return emptyMap()
        return try {
            val tickers = bithumbPublicApi.getCurrentPrice(markets.joinToString(","))
            tickers?.associate { it.market to it.tradePrice.toDouble() } ?: emptyMap()
        } catch (e: Exception) {
            log.error("가격 일괄 조회 실패: {}", e.message)
            emptyMap()
        }
    }

    private fun buildPositionInfo(
        market: String,
        strategy: String,
        entryPrice: Double,
        quantity: Double,
        entryTime: Instant,
        takeProfitPrice: Double = 0.0,
        stopLossPrice: Double = 0.0,
        peakPrice: Double? = null,
        priceMap: Map<String, Double> = emptyMap()
    ): PositionInfo {
        val currentPrice = priceMap[market] ?: entryPrice
        val pnl = (currentPrice - entryPrice) * quantity
        val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100
        return PositionInfo(
            market = market,
            strategy = strategy,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            quantity = quantity,
            value = entryPrice * quantity,
            pnl = pnl,
            pnlPercent = pnlPercent,
            takeProfitPrice = takeProfitPrice,
            stopLossPrice = stopLossPrice,
            entryTime = entryTime.toString(),
            peakPrice = peakPrice
        )
    }

    private fun buildClosedTradeInfo(
        market: String,
        strategy: String,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        entryTime: Instant,
        exitTime: Instant,
        pnlAmount: Double,
        pnlPercent: Double,
        exitReason: String
    ): ClosedTradeInfo {
        return ClosedTradeInfo(
            market = market,
            strategy = strategy,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            quantity = quantity,
            entryTime = entryTime.toString(),
            entryTimeFormatted = formatInstant(entryTime),
            exitTime = exitTime,
            exitTimeFormatted = formatInstant(exitTime),
            holdingMinutes = Duration.between(entryTime, exitTime).toMinutes(),
            pnlAmount = pnlAmount,
            pnlPercent = pnlPercent,
            exitReason = exitReason
        )
    }

    private fun resolveTargetDate(date: String?): Instant {
        val localDate = if (date.isNullOrBlank()) {
            LocalDate.now(SYSTEM_ZONE)
        } else {
            runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now(SYSTEM_ZONE) }
        }
        return localDate.atStartOfDay(SYSTEM_ZONE).toInstant()
    }

    private fun dayRange(targetDate: Instant): Pair<Instant, Instant> {
        val start = targetDate.atZone(SYSTEM_ZONE).toLocalDate().atStartOfDay(SYSTEM_ZONE).toInstant()
        return start to start.plus(Duration.ofDays(1))
    }

    private fun loadBalancesSafely() = runCatching {
        bithumbPrivateApi.getBalances() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun buildActiveStrategies(): List<StrategyInfo> {
        return buildList {
            if (memeScalperProperties.enabled) {
                add(StrategyInfo(STRATEGY_MEME_SCALPER, "단타 매매", "memescalper"))
            }
            if (volumeSurgeProperties.enabled) {
                add(StrategyInfo(STRATEGY_VOLUME_SURGE, "거래량 급등", "volumesurge"))
            }
            if (tradingProperties.enabled) {
                add(StrategyInfo(STRATEGY_MEAN_REVERSION, "평균 회귀", "meanreversion"))
            }
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
        return instant.atZone(SEOUL_ZONE).format(TRADE_TIME_FORMATTER)
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
