package com.ant.cointrading.guided

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.BithumbApiException
import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.api.bithumb.TradeResponse
import com.ant.cointrading.repository.GuidedAutopilotDecisionEntity
import com.ant.cointrading.repository.GuidedAutopilotDecisionRepository
import com.ant.cointrading.repository.OrderLifecycleStrategyGroup
import com.ant.cointrading.repository.GuidedTradeEntity
import com.ant.cointrading.repository.GuidedTradeEventEntity
import com.ant.cointrading.repository.GuidedTradeEventRepository
import com.ant.cointrading.repository.GuidedTradeRepository
import com.ant.cointrading.service.OrderLifecycleEvent
import com.ant.cointrading.service.OrderLifecycleSummary
import com.ant.cointrading.service.OrderLifecycleTelemetryService
import com.ant.cointrading.stats.SharpeRatioCalculator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Service
class GuidedTradingService(
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val guidedTradeRepository: GuidedTradeRepository,
    private val guidedTradeEventRepository: GuidedTradeEventRepository,
    private val orderLifecycleTelemetryService: OrderLifecycleTelemetryService,
    private val guidedAutopilotDecisionRepository: GuidedAutopilotDecisionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()
    private val marketWinRateExecutor: ExecutorService = Executors.newFixedThreadPool(WIN_RATE_CALC_CONCURRENCY)
    private val opportunityRecommendationCache = ConcurrentHashMap<OpportunityRecommendationCacheKey, CachedOpportunityRecommendation>()

    private data class OpportunityRecommendationCacheKey(
        val market: String,
        val interval: String,
        val mode: TradingMode
    )

    private data class CachedOpportunityRecommendation(
        val atMillis: Long,
        val recommendation: GuidedRecommendation
    )

    private companion object {
        val OPEN_STATUSES = listOf(GuidedTradeEntity.STATUS_PENDING_ENTRY, GuidedTradeEntity.STATUS_OPEN)
        val D = BigDecimal.ZERO
        const val DCA_MIN_INTERVAL_SECONDS = 600L
        const val WIN_RATE_SAMPLE_MARKET_LIMIT = 60
        const val WIN_RATE_CANDLE_COUNT = 120
        const val WIN_RATE_CALC_CONCURRENCY = 8
        const val WIN_RATE_CALC_TIMEOUT_MILLIS = 2500L
        const val GUIDED_STRATEGY_CODE = "GUIDED_TRADING"
        const val GUIDED_AUTOPILOT_STRATEGY_CODE = "GUIDED_AUTOPILOT"
        const val AUTOPILOT_OPPORTUNITY_UNIVERSE_LIMIT = 15
        const val AUTOPILOT_OPPORTUNITY_CACHE_TTL_MILLIS = 30_000L
        const val AUTOPILOT_PROMOTION_SHARPE_GATE = 1.2
        const val AUTOPILOT_PROMOTION_MAX_DD_GATE = 4.0
        const val AUTOPILOT_PROMOTION_WIN_RATE_GATE = 52.0
        val MIN_EFFECTIVE_HOLDING_KRW: BigDecimal = BigDecimal("5000")
    }

    @PreDestroy
    fun shutdown() {
        marketWinRateExecutor.shutdownNow()
    }

    fun getMarketBoard(
        sortBy: GuidedMarketSortBy = GuidedMarketSortBy.TURNOVER,
        sortDirection: GuidedSortDirection = GuidedSortDirection.DESC,
        interval: String = "minute30",
        mode: TradingMode = TradingMode.SWING
    ): List<GuidedMarketItem> {
        val markets = bithumbPublicApi.getMarketAll()
            ?.filter { it.market.startsWith("KRW-") }
            ?: emptyList()

        if (markets.isEmpty()) return emptyList()

        val tickerMap = fetchTickerMap(markets.map { it.market })

        val marketBoard = markets.mapNotNull { market ->
            val ticker = tickerMap[market.market] ?: return@mapNotNull null
            GuidedMarketItem(
                market = market.market,
                symbol = market.market.removePrefix("KRW-"),
                koreanName = market.koreanName ?: market.market,
                englishName = market.englishName,
                tradePrice = ticker.tradePrice.toDouble(),
                changeRate = ticker.changeRate?.toDouble()?.times(100.0) ?: 0.0,
                changePrice = ticker.changePrice?.toDouble() ?: 0.0,
                accTradePrice = (ticker.accTradePrice24h ?: ticker.accTradePrice)?.toDouble() ?: 0.0,
                accTradeVolume = (ticker.accTradeVolume24h ?: ticker.accTradeVolume)?.toDouble() ?: 0.0,
                surgeRate = calculateSurgeRate(
                    openingPrice = ticker.openingPrice?.toDouble(),
                    highPrice = ticker.highPrice?.toDouble(),
                    tradePrice = ticker.tradePrice.toDouble()
                )
            )
        }

        val withWinRates = if (sortBy.isWinRateSort()) {
            enrichMarketBoardWithWinRates(marketBoard, interval, mode)
        } else {
            marketBoard
        }

        return withWinRates.sortedWith(buildMarketComparator(sortBy, sortDirection))
    }

    fun getChartData(market: String, interval: String, count: Int, mode: TradingMode = TradingMode.SWING): GuidedChartResponse {
        val normalizedInterval = interval.lowercase()
        val candles = if (normalizedInterval == "tick") {
            buildTickCandles(market, count.coerceIn(100, 500))
        } else {
            bithumbPublicApi.getOhlcv(market, interval, count.coerceIn(30, 300), null)
                ?.sortedBy { it.timestamp }
                ?.map {
                    GuidedCandle(
                        timestamp = it.timestamp,
                        open = it.openingPrice.toDouble(),
                        high = it.highPrice.toDouble(),
                        low = it.lowPrice.toDouble(),
                        close = it.tradePrice.toDouble(),
                        volume = it.candleAccTradeVolume.toDouble()
                    )
                }
                ?: emptyList()
        }

        val recommendation = buildRecommendation(market, candles, mode)
        val position = getActiveTrade(market)
        val events = position?.id?.let { id ->
            guidedTradeEventRepository.findByTradeIdOrderByCreatedAtAsc(id).map {
                GuidedTradeEventView(
                    id = it.id ?: 0,
                    tradeId = it.tradeId,
                    eventType = it.eventType,
                    price = it.price?.toDouble(),
                    quantity = it.quantity?.toDouble(),
                    message = it.message,
                    createdAt = it.createdAt
                )
            }
        } ?: emptyList()

        val orderbook = bithumbPublicApi.getOrderbook(market)?.firstOrNull()?.toView()
        val pendingOrders = bithumbPrivateApi.getOrders(market, "wait", 1, 30).orEmpty()
            .map { it.toOrderView() }
        val completedOrders = (
            bithumbPrivateApi.getOrders(market, "done", 1, 30).orEmpty() +
                bithumbPrivateApi.getOrders(market, "cancel", 1, 30).orEmpty()
            )
            .sortedByDescending { it.createdAt ?: "" }
            .take(30)
            .map { it.toOrderView() }
        val currentOrder = resolveCurrentOrder(position)

        return GuidedChartResponse(
            market = market,
            interval = normalizedInterval,
            candles = candles,
            recommendation = recommendation,
            activePosition = position?.toView(currentPrice = candles.lastOrNull()?.close),
            events = events,
            orderbook = orderbook,
            orderSnapshot = GuidedOrderSnapshotView(
                currentOrder = currentOrder,
                pendingOrders = pendingOrders,
                completedOrders = completedOrders
            )
        )
    }

    fun getRecommendation(market: String, interval: String = "minute30", count: Int = 120, mode: TradingMode = TradingMode.SWING): GuidedRecommendation {
        val candles = if (interval.lowercase() == "tick") {
            buildTickCandles(market, count.coerceIn(100, 500))
        } else {
            bithumbPublicApi.getOhlcv(market, interval, count.coerceIn(30, 300), null)
                ?.sortedBy { it.timestamp }
                ?.map {
                    GuidedCandle(
                        timestamp = it.timestamp,
                        open = it.openingPrice.toDouble(),
                        high = it.highPrice.toDouble(),
                        low = it.lowPrice.toDouble(),
                        close = it.tradePrice.toDouble(),
                        volume = it.candleAccTradeVolume.toDouble()
                    )
                }
                ?: emptyList()
        }
        return buildRecommendation(market, candles, mode)
    }

    fun getAgentContext(
        market: String,
        interval: String = "minute30",
        count: Int = 120,
        closedTradeLimit: Int = 20,
        mode: TradingMode = TradingMode.SWING
    ): GuidedAgentContextResponse {
        val normalizedMarket = market.trim().uppercase()
        val chart = getChartData(normalizedMarket, interval, count, mode)
        val recentClosedTrades = guidedTradeRepository
            .findTop80ByMarketAndStatusOrderByCreatedAtDesc(normalizedMarket, GuidedTradeEntity.STATUS_CLOSED)
            .take(closedTradeLimit.coerceIn(5, 80))
            .map { it.toClosedTradeView() }

        val closedPnls = recentClosedTrades.map { it.realizedPnlPercent }
        val winCount = closedPnls.count { it > 0.0 }
        val lossCount = closedPnls.count { it < 0.0 }
        val avgPnlPercent = if (closedPnls.isNotEmpty()) closedPnls.average() else 0.0
        val winRate = if (closedPnls.isNotEmpty()) winCount.toDouble() / closedPnls.size.toDouble() * 100.0 else 0.0

        return GuidedAgentContextResponse(
            market = normalizedMarket,
            generatedAt = Instant.now(),
            chart = chart,
            recentClosedTrades = recentClosedTrades,
            performance = GuidedPerformanceSnapshot(
                sampleSize = closedPnls.size,
                winCount = winCount,
                lossCount = lossCount,
                winRate = winRate,
                avgPnlPercent = avgPnlPercent
            ),
            featurePack = buildAgentFeaturePack(chart)
        )
    }

    fun getRealtimeTicker(market: String): GuidedRealtimeTickerView? {
        val ticker = bithumbPublicApi.getCurrentPrice(market).orEmpty().firstOrNull() ?: return null
        return GuidedRealtimeTickerView(
            market = ticker.market,
            tradePrice = ticker.tradePrice.toDouble(),
            changeRate = ticker.changeRate?.toDouble()?.times(100.0),
            tradeVolume = ticker.tradeVolume?.toDouble(),
            timestamp = ticker.timestamp
        )
    }

    fun getActivePosition(market: String): GuidedTradeView? {
        val trade = getActiveTrade(market) ?: return null
        val currentPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice
        if (
            trade.status == GuidedTradeEntity.STATUS_OPEN &&
            getActualHoldingQuantity(market, currentPrice) <= BigDecimal.ZERO
        ) {
            return null
        }
        val current = currentPrice?.toDouble()
        return trade.toView(current)
    }

    fun getAllOpenPositions(): List<GuidedTradeView> {
        val trades = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        if (trades.isEmpty()) return emptyList()
        val markets = trades.map { it.market }.distinct()
        val tickerMap = fetchTickerMap(markets)
        val balancesByCurrency = fetchBalancesByCurrency()
        return trades.mapNotNull { trade ->
            val marketPrice = tickerMap[trade.market]?.tradePrice
            if (
                trade.status == GuidedTradeEntity.STATUS_OPEN &&
                getActualHoldingQuantity(trade.market, marketPrice, balancesByCurrency) <= BigDecimal.ZERO
            ) {
                return@mapNotNull null
            }
            trade.toView(marketPrice?.toDouble())
        }
    }

    @Transactional
    fun adoptExternalPosition(request: GuidedAdoptPositionRequest): GuidedAdoptPositionResponse {
        val market = request.market.trim().uppercase()
        require(market.startsWith("KRW-")) { "market 형식 오류: KRW-BTC 형태를 사용하세요." }

        val existing = getActiveTrade(market)
        if (existing != null) {
            return GuidedAdoptPositionResponse(
                positionId = existing.id ?: 0L,
                adopted = false,
                avgEntryPrice = existing.averageEntryPrice.toDouble(),
                quantity = existing.remainingQuantity.toDouble()
            )
        }

        val tickerPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice
        require(tickerPrice != null && tickerPrice > BigDecimal.ZERO) { "외부 포지션 편입 실패: 현재가를 조회할 수 없습니다." }
        val actualQty = getActualHoldingQuantity(market, tickerPrice)
        require(actualQty > BigDecimal.ZERO) { "외부 포지션 편입 실패: 실효 보유 수량이 없습니다(미세 잔고 제외)." }

        val mode = TradingMode.fromString(request.mode ?: TradingMode.SWING.name)
        val interval = request.interval?.ifBlank { null } ?: "minute30"
        val recommendation = runCatching { getRecommendation(market, interval, WIN_RATE_CANDLE_COUNT, mode) }.getOrNull()

        val defaultStopLoss = tickerPrice
            .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(mode.slPercent)))
            .max(BigDecimal.ZERO)
        val defaultTakeProfit = tickerPrice
            .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(mode.tpCapPercent)))
            .max(tickerPrice)

        val stopLoss = recommendation?.stopLossPrice
            ?.let { BigDecimal.valueOf(it) }
            ?.max(BigDecimal.ZERO)
            ?: defaultStopLoss
        val takeProfit = recommendation?.takeProfitPrice
            ?.let { BigDecimal.valueOf(it) }
            ?.max(tickerPrice)
            ?: defaultTakeProfit

        val estimatedAmount = tickerPrice.multiply(actualQty).setScale(0, RoundingMode.HALF_UP)
        val entrySource = request.entrySource
            ?.trim()
            ?.uppercase()
            ?.ifBlank { null }
            ?: "EXTERNAL"
        val strategyCode = if (entrySource.contains("AUTOPILOT") || entrySource.contains("MCP")) {
            GUIDED_AUTOPILOT_STRATEGY_CODE
        } else {
            GUIDED_STRATEGY_CODE
        }
        val note = request.notes?.takeIf { it.isNotBlank() }
        val reasonParts = buildList {
            add("entrySource=$entrySource")
            add("mode=${mode.name}")
            add("interval=$interval")
            if (!note.isNullOrBlank()) add("notes=$note")
        }

        val trade = GuidedTradeEntity(
            market = market,
            status = GuidedTradeEntity.STATUS_OPEN,
            entryOrderType = GuidedTradeEntity.ORDER_TYPE_MARKET,
            entryOrderId = null,
            targetAmountKrw = estimatedAmount.max(BigDecimal(5100)),
            averageEntryPrice = tickerPrice,
            entryQuantity = actualQty,
            remainingQuantity = actualQty,
            stopLossPrice = stopLoss,
            takeProfitPrice = takeProfit,
            trailingTriggerPercent = BigDecimal.valueOf(2.0),
            trailingOffsetPercent = BigDecimal.valueOf(1.0),
            trailingActive = false,
            dcaStepPercent = BigDecimal.valueOf(2.0),
            maxDcaCount = 2,
            halfTakeProfitRatio = BigDecimal.valueOf(0.5),
            entrySource = entrySource,
            strategyCode = strategyCode,
            recommendationReason = reasonParts.joinToString(" | "),
            lastAction = "ADOPTED_EXTERNAL_ENTRY"
        )

        val saved = guidedTradeRepository.save(trade)
        appendEvent(saved.id!!, "ADOPTED_EXTERNAL_ENTRY", tickerPrice, actualQty, "외부 포지션 편입: $entrySource")

        return GuidedAdoptPositionResponse(
            positionId = saved.id!!,
            adopted = true,
            avgEntryPrice = saved.averageEntryPrice.toDouble(),
            quantity = saved.remainingQuantity.toDouble()
        )
    }

    @Transactional
    fun startAutoTrading(request: GuidedStartRequest): GuidedTradeView {
        val market = request.market.trim().uppercase()
        require(market.startsWith("KRW-")) { "market 형식 오류: KRW-BTC 형태를 사용하세요." }

        val existing = getActiveTrade(market)
        require(existing == null) { "이미 진행 중인 포지션이 있습니다." }

        val interval = request.interval?.trim()?.ifBlank { "minute30" } ?: "minute30"
        val mode = TradingMode.fromString(request.mode ?: TradingMode.SWING.name)
        val recommendation = getRecommendation(
            market = market,
            interval = interval,
            count = if (interval.equals("tick", ignoreCase = true)) 300 else 120,
            mode = mode
        )

        val orderType = (request.orderType ?: recommendation.suggestedOrderType).trim().uppercase()
        val amountKrw = BigDecimal.valueOf(request.amountKrw.coerceAtLeast(5100))
        val requestedLimitPrice = request.limitPrice?.let { BigDecimal.valueOf(it) }
        var selectedLimitPrice: BigDecimal? = null
        val trailingTriggerPercent = (request.trailingTriggerPercent ?: 2.0).coerceIn(0.5, 20.0)
        val trailingOffsetPercent = (request.trailingOffsetPercent ?: 1.0).coerceIn(0.2, 10.0)
        val dcaStepPercent = (request.dcaStepPercent ?: 2.0).coerceIn(0.5, 15.0)
        val maxDcaCount = (request.maxDcaCount ?: 2).coerceIn(0, 3)
        val halfTakeProfitRatio = (request.halfTakeProfitRatio ?: 0.5).coerceIn(0.2, 0.8)
        val entrySource = request.entrySource?.trim()?.uppercase()?.ifBlank { null } ?: "MANUAL"
        val strategyCode = request.strategyCode
            ?.trim()
            ?.uppercase()
            ?.ifBlank { null }
            ?: if (entrySource == "AUTOPILOT") GUIDED_AUTOPILOT_STRATEGY_CODE else GUIDED_STRATEGY_CODE
        val requestedMessage = if (entrySource == "AUTOPILOT") "autopilot_entry_requested" else "guided_entry_requested"

        val submitted = try {
            when (orderType) {
                GuidedTradeEntity.ORDER_TYPE_LIMIT -> {
                    val selectedPrice = normalizeLimitPrice(
                        market,
                        requestedLimitPrice ?: BigDecimal.valueOf(recommendation.recommendedEntryPrice)
                    )
                    selectedLimitPrice = selectedPrice
                    require(selectedPrice > BigDecimal.ZERO) { "지정가가 유효하지 않습니다." }
                    if (requestedLimitPrice != null && selectedPrice != requestedLimitPrice) {
                        log.info("[$market] 지정가 보정 적용: requested=${requestedLimitPrice.toPlainString()} -> normalized=${selectedPrice.toPlainString()}")
                    }

                    val quantity = amountKrw.divide(selectedPrice, 8, RoundingMode.DOWN)
                    require(quantity > BigDecimal.ZERO) { "주문 수량이 0입니다. 주문금액 또는 지정가를 확인하세요." }
                    bithumbPrivateApi.buyLimitOrder(market, selectedPrice, quantity)
                }
                else -> {
                    bithumbPrivateApi.buyMarketOrder(market, amountKrw)
                }
            }
        } catch (e: BithumbApiException) {
            orderLifecycleTelemetryService.recordFailed(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = market,
                side = "BUY",
                orderId = null,
                strategyCode = strategyCode,
                message = e.message
            )
            throw IllegalArgumentException(buildOrderErrorMessage(e))
        }

        orderLifecycleTelemetryService.recordRequested(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = market,
            side = "BUY",
            orderId = submitted.uuid,
            strategyCode = strategyCode,
            price = if (orderType == GuidedTradeEntity.ORDER_TYPE_LIMIT) selectedLimitPrice else amountKrw,
            quantity = submitted.volume,
            message = requestedMessage
        )

        val order = bithumbPrivateApi.getOrder(submitted.uuid) ?: submitted
        orderLifecycleTelemetryService.reconcileOrderState(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            order = order,
            fallbackMarket = market,
            strategyCode = strategyCode
        )
        val executedQuantity = order.executedVolume ?: BigDecimal.ZERO
        val tickerPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice
        val referencePrice = resolveEntryPrice(
            order = order,
            orderType = orderType,
            investedAmount = amountKrw,
            selectedLimitPrice = selectedLimitPrice,
            fallbackPrice = tickerPrice ?: BigDecimal.valueOf(recommendation.recommendedEntryPrice)
        )

        val trade = GuidedTradeEntity(
            market = market,
            status = if (executedQuantity > BigDecimal.ZERO) GuidedTradeEntity.STATUS_OPEN else GuidedTradeEntity.STATUS_PENDING_ENTRY,
            entryOrderType = orderType,
            entryOrderId = submitted.uuid,
            targetAmountKrw = amountKrw,
            averageEntryPrice = referencePrice,
            entryQuantity = executedQuantity,
            remainingQuantity = executedQuantity,
            stopLossPrice = BigDecimal.valueOf(request.stopLossPrice ?: recommendation.stopLossPrice),
            takeProfitPrice = BigDecimal.valueOf(request.takeProfitPrice ?: recommendation.takeProfitPrice),
            trailingTriggerPercent = BigDecimal.valueOf(trailingTriggerPercent),
            trailingOffsetPercent = BigDecimal.valueOf(trailingOffsetPercent),
            trailingActive = false,
            dcaStepPercent = BigDecimal.valueOf(dcaStepPercent),
            maxDcaCount = maxDcaCount,
            halfTakeProfitRatio = BigDecimal.valueOf(halfTakeProfitRatio),
            entrySource = entrySource,
            strategyCode = strategyCode,
            recommendationReason = recommendation.rationale.joinToString(" | "),
            lastAction = "ENTRY_SUBMITTED"
        )

        val saved = guidedTradeRepository.save(trade)
        appendEvent(saved.id!!, "ENTRY_SUBMITTED", referencePrice, executedQuantity, "주문 접수: ${saved.entryOrderType}")

        if (saved.status == GuidedTradeEntity.STATUS_OPEN) {
            appendEvent(saved.id!!, "ENTRY_FILLED", referencePrice, executedQuantity, "진입 체결")
        }

        return saved.toView(referencePrice.toDouble())
    }

    @Transactional
    fun stopAutoTrading(market: String): GuidedTradeView {
        val allTrades = guidedTradeRepository.findByMarketAndStatusIn(market, OPEN_STATUSES)
        require(allTrades.isNotEmpty()) { "진행 중인 포지션이 없습니다." }

        val currentPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice ?: allTrades.first().averageEntryPrice
        val actualQty = getActualHoldingQuantity(market, currentPrice)
        var executedQty = BigDecimal.ZERO
        var executedPrice = currentPrice
        var exitOrderId: String? = null
        var exitAttempted = false

        if (actualQty > BigDecimal.ZERO) {
            val estimatedValue = actualQty.multiply(currentPrice)
            if (estimatedValue >= MIN_EFFECTIVE_HOLDING_KRW) {
                try {
                    val sell = bithumbPrivateApi.sellMarketOrder(market, actualQty)
                    exitAttempted = true
                    exitOrderId = sell.uuid
                    orderLifecycleTelemetryService.recordRequested(
                        strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                        market = market,
                        side = "SELL",
                        orderId = sell.uuid,
                        strategyCode = GUIDED_STRATEGY_CODE,
                        quantity = actualQty,
                        message = "guided_stop_requested"
                    )
                    Thread.sleep(300)
                    val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
                    orderLifecycleTelemetryService.reconcileOrderState(
                        strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                        order = sellInfo,
                        fallbackMarket = market,
                        strategyCode = GUIDED_STRATEGY_CODE
                    )
                    executedPrice = sellInfo.price ?: currentPrice
                    executedQty = (sellInfo.executedVolume ?: BigDecimal.ZERO).min(actualQty)

                    if (executedQty > BigDecimal.ZERO) {
                        var remainingToAllocate = executedQty
                        for (trade in allTrades) {
                            if (remainingToAllocate <= BigDecimal.ZERO) break
                            val tradeShare = trade.remainingQuantity.min(remainingToAllocate)
                            if (tradeShare <= BigDecimal.ZERO) continue
                            applyExitFill(trade, tradeShare, executedPrice, "MANUAL_STOP")
                            remainingToAllocate = remainingToAllocate.subtract(tradeShare).max(BigDecimal.ZERO)
                        }
                    }
                } catch (e: Exception) {
                    log.error("[$market] 전량 매도 실패: ${e.message}", e)
                    orderLifecycleTelemetryService.recordFailed(
                        strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                        market = market,
                        side = "SELL",
                        orderId = null,
                        strategyCode = GUIDED_STRATEGY_CODE,
                        message = e.message
                    )
                    throw IllegalStateException("[$market] 매도 주문 실패 — 빗썸에서 직접 매도하세요. 원인: ${e.message}")
                }
            } else {
                log.warn("[$market] 잔여 수량 매도 불가 (추정금액 ${estimatedValue.toPlainString()}원 < ${MIN_EFFECTIVE_HOLDING_KRW.toPlainString()}원). 자동 종료 생략.")
            }
        }

        var hasEffectiveBalance = getActualHoldingQuantity(market, executedPrice) > BigDecimal.ZERO

        if (executedQty <= BigDecimal.ZERO && !hasEffectiveBalance) {
            val estimatedPrice = executedPrice.takeIf { it > BigDecimal.ZERO } ?: currentPrice
            for (trade in allTrades) {
                val remaining = trade.remainingQuantity
                if (remaining > BigDecimal.ZERO) {
                    applyExitFill(trade, remaining, estimatedPrice, "MANUAL_STOP_ESTIMATED")
                }
                trade.remainingQuantity = BigDecimal.ZERO
                trade.status = GuidedTradeEntity.STATUS_CLOSED
                trade.closedAt = Instant.now()
                trade.exitReason = "MANUAL_STOP_ESTIMATED"
                trade.lastAction = "CLOSED_MANUAL_STOP_ESTIMATED"
                trade.pnlConfidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
                if (trade.averageExitPrice <= BigDecimal.ZERO) {
                    trade.averageExitPrice = estimatedPrice
                }
                guidedTradeRepository.save(trade)
                appendEvent(trade.id!!, "CLOSE_ESTIMATED", estimatedPrice, null, "수동 청산 추정 종료(체결 조회 불가)")
            }
            hasEffectiveBalance = false
        }

        for (trade in allTrades) {
            if (trade.status == GuidedTradeEntity.STATUS_CLOSED) {
                continue
            }
            trade.lastExitOrderId = exitOrderId ?: trade.lastExitOrderId
            if (!hasEffectiveBalance || trade.remainingQuantity <= BigDecimal.ZERO) {
                trade.remainingQuantity = BigDecimal.ZERO
                trade.status = GuidedTradeEntity.STATUS_CLOSED
                trade.closedAt = Instant.now()
                trade.exitReason = "MANUAL_STOP"
                trade.lastAction = "CLOSED_MANUAL_STOP"
                if (trade.averageExitPrice <= BigDecimal.ZERO && executedPrice > BigDecimal.ZERO) {
                    trade.averageExitPrice = executedPrice
                }
                guidedTradeRepository.save(trade)
                appendEvent(trade.id!!, "CLOSE_ALL", executedPrice, executedQty.takeIf { it > BigDecimal.ZERO }, "수동 전체 청산")
                continue
            }

            trade.status = GuidedTradeEntity.STATUS_OPEN
            trade.lastAction = if (exitAttempted) {
                if (executedQty > BigDecimal.ZERO) "MANUAL_STOP_PARTIAL"
                else "MANUAL_STOP_PENDING_EXIT"
            } else {
                "MANUAL_STOP_SKIPPED"
            }
            guidedTradeRepository.save(trade)

            if (exitAttempted && executedQty <= BigDecimal.ZERO) {
                appendEvent(trade.id!!, "CLOSE_PENDING", executedPrice, BigDecimal.ZERO, "수동 청산 주문 미체결 - 포지션 유지")
            } else if (exitAttempted && executedQty > BigDecimal.ZERO) {
                appendEvent(trade.id!!, "PARTIAL_CLOSE", executedPrice, executedQty, "수동 청산 부분 체결 - 잔여 포지션 유지")
            }
        }

        val primary = allTrades.first()
        return primary.toView(executedPrice.toDouble())
    }

    @Transactional
    fun cancelPendingEntry(market: String): GuidedTradeView? {
        val trade = guidedTradeRepository.findTopByMarketAndStatusInOrderByCreatedAtDesc(
            market, listOf(GuidedTradeEntity.STATUS_PENDING_ENTRY)
        ) ?: return null

        trade.entryOrderId?.let { orderId ->
            orderLifecycleTelemetryService.recordCancelRequested(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = market,
                side = "BUY",
                orderId = orderId,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = "guided_pending_cancel_requested"
            )
            runCatching { bithumbPrivateApi.cancelOrder(orderId) }
                .onSuccess {
                    orderLifecycleTelemetryService.recordCancelledIfFirst(
                        strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                        market = market,
                        side = it.side,
                        orderId = orderId,
                        strategyCode = GUIDED_STRATEGY_CODE,
                        message = "guided_pending_cancelled"
                    )
                }
                .onFailure {
                    log.warn("[$market] 미체결 주문 취소 실패 (orderId=$orderId): ${it.message}")
                    orderLifecycleTelemetryService.recordFailed(
                        strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                        market = market,
                        side = "BUY",
                        orderId = orderId,
                        strategyCode = GUIDED_STRATEGY_CODE,
                        message = it.message
                    )
                }
        }

        trade.status = GuidedTradeEntity.STATUS_CANCELLED
        trade.closedAt = Instant.now()
        trade.exitReason = "PLAN_CANCEL_PENDING"
        trade.lastAction = "CANCELLED_PENDING"
        guidedTradeRepository.save(trade)
        appendEvent(trade.id!!, "ENTRY_CANCELLED", null, null, "플랜 실행: 미체결 진입 주문 취소")

        val currentPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble() ?: 0.0
        return trade.toView(currentPrice)
    }

    @Transactional
    fun partialTakeProfit(market: String, ratio: Double = 0.5): GuidedTradeView {
        val trade = getActiveTrade(market) ?: error("진행 중인 포지션이 없습니다.")
        require(trade.remainingQuantity > BigDecimal.ZERO) { "청산 가능한 수량이 없습니다." }

        val boundedRatio = ratio.coerceIn(0.1, 0.9)
        val qty = trade.remainingQuantity
            .multiply(BigDecimal.valueOf(boundedRatio))
            .setScale(8, RoundingMode.DOWN)
            .max(BigDecimal.ZERO)
        require(qty > BigDecimal.ZERO) { "부분 청산 수량이 0입니다." }

        val currentPrice = bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice ?: trade.averageEntryPrice
        val estimatedValue = qty.multiply(currentPrice)
        require(estimatedValue >= MIN_EFFECTIVE_HOLDING_KRW) {
            "부분 익절 금액 ${estimatedValue.toPlainString()}원이 빗썸 최소 주문금액(${MIN_EFFECTIVE_HOLDING_KRW.toPlainString()}원) 미달입니다. 전체 청산을 이용하세요."
        }

        val sell = bithumbPrivateApi.sellMarketOrder(trade.market, qty)
        orderLifecycleTelemetryService.recordRequested(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = trade.market,
            side = "SELL",
            orderId = sell.uuid,
            strategyCode = GUIDED_STRATEGY_CODE,
            quantity = qty,
            message = "guided_manual_partial_tp_requested"
        )
        val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
        orderLifecycleTelemetryService.reconcileOrderState(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            order = sellInfo,
            fallbackMarket = trade.market,
            strategyCode = GUIDED_STRATEGY_CODE
        )
        val executedQty = (sellInfo.executedVolume ?: qty).min(trade.remainingQuantity)
        require(executedQty > BigDecimal.ZERO) { "부분 익절 주문이 체결되지 않았습니다." }

        val execPrice = sellInfo.price ?: bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice ?: trade.averageEntryPrice
        applyExitFill(trade, executedQty, execPrice, "MANUAL_PARTIAL_TP")

        trade.lastExitOrderId = sell.uuid
        if (!trade.halfTakeProfitDone) {
            trade.halfTakeProfitDone = true
            trade.stopLossPrice = trade.averageEntryPrice
        }

        if (trade.remainingQuantity > BigDecimal.ZERO) {
            trade.status = GuidedTradeEntity.STATUS_OPEN
            trade.lastAction = "MANUAL_PARTIAL_TP"
            guidedTradeRepository.save(trade)
            appendEvent(
                trade.id!!,
                "MANUAL_PARTIAL_TP",
                execPrice,
                executedQty,
                "수동 부분익절 ${String.format("%.0f", boundedRatio * 100)}%"
            )
        } else {
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "MANUAL_PARTIAL_TP"
            trade.lastAction = "MANUAL_CLOSE_BY_PARTIAL_TP"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_ALL", execPrice, executedQty, "수동 부분익절로 전량 청산")
        }

        val current = bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice?.toDouble()
        return trade.toView(current)
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    fun monitorGuidedTrades() {
        val trades = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        trades.forEach { trade ->
            try {
                when (trade.status) {
                    GuidedTradeEntity.STATUS_PENDING_ENTRY -> reconcilePendingEntry(trade)
                    GuidedTradeEntity.STATUS_OPEN -> manageOpenTrade(trade)
                }
            } catch (e: Exception) {
                log.error("[${trade.market}] guided monitor error: ${e.message}", e)
                trade.lastAction = "MONITOR_ERROR:${e.message}"
                guidedTradeRepository.save(trade)
            }
        }
    }

    private fun reconcilePendingEntry(trade: GuidedTradeEntity) {
        val orderId = trade.entryOrderId ?: return
        val order = bithumbPrivateApi.getOrder(orderId)
        if (order != null) {
            orderLifecycleTelemetryService.reconcileOrderState(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                order = order,
                fallbackMarket = trade.market,
                strategyCode = GUIDED_STRATEGY_CODE
            )
            val executed = order.executedVolume ?: BigDecimal.ZERO

            if (executed > BigDecimal.ZERO) {
                val filledPrice = resolveEntryPrice(
                    order = order,
                    orderType = trade.entryOrderType,
                    investedAmount = trade.targetAmountKrw,
                    selectedLimitPrice = trade.averageEntryPrice,
                    fallbackPrice = trade.averageEntryPrice
                )
                trade.averageEntryPrice = filledPrice
                trade.entryQuantity = executed
                trade.remainingQuantity = executed
                trade.status = GuidedTradeEntity.STATUS_OPEN
                trade.lastAction = "ENTRY_FILLED"
                guidedTradeRepository.save(trade)
                appendEvent(trade.id!!, "ENTRY_FILLED", filledPrice, executed, "지정가 진입 체결")
                return
            }
        }

        val ageSec = Instant.now().epochSecond - trade.createdAt.epochSecond
        if (ageSec > 900) {
            orderLifecycleTelemetryService.recordCancelRequested(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = trade.market,
                side = "BUY",
                orderId = orderId,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = "guided_pending_timeout_cancel_requested"
            )
            val cancelled = bithumbPrivateApi.cancelOrder(orderId)
            orderLifecycleTelemetryService.recordCancelledIfFirst(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = trade.market,
                side = cancelled.side,
                orderId = orderId,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = "guided_pending_timeout_cancelled"
            )
            trade.status = GuidedTradeEntity.STATUS_CANCELLED
            trade.closedAt = Instant.now()
            trade.exitReason = "ENTRY_TIMEOUT"
            trade.lastAction = "ENTRY_CANCELLED"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "ENTRY_CANCELLED", null, null, "진입 대기 15분 초과로 취소")
        }
    }

    private fun manageOpenTrade(trade: GuidedTradeEntity) {
        val currentPrice = bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice ?: return

        val actualHolding = getActualHoldingQuantity(trade.market, currentPrice)
        if (actualHolding <= BigDecimal.ZERO && trade.remainingQuantity > BigDecimal.ZERO) {
            applyExitFill(trade, trade.remainingQuantity, currentPrice, "NO_EFFECTIVE_BALANCE")
            trade.remainingQuantity = BigDecimal.ZERO
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "NO_EFFECTIVE_BALANCE"
            trade.lastAction = "AUTO_CLOSED_NO_EFFECTIVE_BALANCE"
            trade.pnlConfidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
            if (trade.averageExitPrice <= BigDecimal.ZERO) {
                trade.averageExitPrice = currentPrice
            }
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "MANUAL_INTERVENTION_CLOSE", currentPrice, null, "실효 잔고 없음(미세 잔고 제외) 추정 종료")
            return
        }

        if (actualHolding > BigDecimal.ZERO && actualHolding < trade.remainingQuantity) {
            val shrinkRatio = if (trade.remainingQuantity > BigDecimal.ZERO) {
                actualHolding.divide(trade.remainingQuantity, 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ONE
            }
            if (shrinkRatio < BigDecimal("0.95")) {
                trade.remainingQuantity = actualHolding
                trade.lastAction = "BALANCE_SYNC"
                guidedTradeRepository.save(trade)
                appendEvent(trade.id!!, "BALANCE_SYNC", currentPrice, actualHolding, "수동 체결 감지로 보유 수량 동기화")
            }
        }

        if (trade.remainingQuantity <= BigDecimal.ZERO) {
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = trade.exitReason ?: "NO_REMAINING"
            trade.lastAction = "AUTO_CLOSE_FINALIZED"
            guidedTradeRepository.save(trade)
            return
        }

        val pnlPercent = calculatePnlPercent(trade.averageEntryPrice, currentPrice)

        if (!trade.trailingActive && pnlPercent >= trade.trailingTriggerPercent.toDouble()) {
            trade.trailingActive = true
            trade.trailingPeakPrice = currentPrice
            trade.trailingStopPrice = currentPrice.multiply(BigDecimal.ONE.subtract(trade.trailingOffsetPercent.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)))
            trade.lastAction = "TRAILING_ACTIVATED"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "TRAILING_ACTIVATED", currentPrice, null, "트레일링 스탑 활성화")
        }

        if (trade.trailingActive) {
            val peak = trade.trailingPeakPrice ?: currentPrice
            if (currentPrice > peak) {
                trade.trailingPeakPrice = currentPrice
                trade.trailingStopPrice = currentPrice.multiply(BigDecimal.ONE.subtract(trade.trailingOffsetPercent.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)))
                guidedTradeRepository.save(trade)
            }
            val trailStop = trade.trailingStopPrice
            if (trailStop != null && currentPrice <= trailStop) {
                closeAllByMarket(trade, "TRAILING_STOP")
                return
            }
        }

        if (!trade.halfTakeProfitDone && currentPrice >= trade.takeProfitPrice) {
            executeHalfTakeProfit(trade, currentPrice)
        }

        if (currentPrice <= trade.stopLossPrice) {
            closeAllByMarket(trade, "STOP_LOSS")
            return
        }

        if (trade.dcaCount < trade.maxDcaCount && canExecuteDcaNow(trade)) {
            val nextTrigger = trade.averageEntryPrice.multiply(
                BigDecimal.ONE.subtract(
                    trade.dcaStepPercent.multiply(BigDecimal.valueOf((trade.dcaCount + 1).toLong()))
                        .divide(BigDecimal(100), 8, RoundingMode.HALF_UP)
                )
            )
            if (currentPrice <= nextTrigger) {
                executeDcaBuy(trade, currentPrice)
            }
        }
    }

    private fun executeHalfTakeProfit(trade: GuidedTradeEntity, currentPrice: BigDecimal) {
        val qty = trade.remainingQuantity.multiply(trade.halfTakeProfitRatio).setScale(8, RoundingMode.DOWN)
        if (qty <= BigDecimal.ZERO) return

        val order = bithumbPrivateApi.sellMarketOrder(trade.market, qty)
        orderLifecycleTelemetryService.recordRequested(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = trade.market,
            side = "SELL",
            orderId = order.uuid,
            strategyCode = GUIDED_STRATEGY_CODE,
            quantity = qty,
            message = "guided_half_tp_requested"
        )
        val orderInfo = bithumbPrivateApi.getOrder(order.uuid) ?: order
        orderLifecycleTelemetryService.reconcileOrderState(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            order = orderInfo,
            fallbackMarket = trade.market,
            strategyCode = GUIDED_STRATEGY_CODE
        )
        val executedQty = (orderInfo.executedVolume ?: qty).min(trade.remainingQuantity)
        if (executedQty <= BigDecimal.ZERO) {
            trade.lastAction = "HALF_TAKE_PROFIT_NO_FILL"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "HALF_TP_NO_FILL", currentPrice, BigDecimal.ZERO, "절반 익절 주문 미체결")
            orderLifecycleTelemetryService.recordFailed(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = trade.market,
                side = "SELL",
                orderId = order.uuid,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = "guided_half_tp_no_fill"
            )
            return
        }
        val execPrice = orderInfo.price ?: currentPrice

        applyExitFill(trade, executedQty, execPrice, "HALF_TP")
        trade.halfTakeProfitDone = true
        trade.stopLossPrice = trade.averageEntryPrice
        trade.lastAction = "HALF_TAKE_PROFIT"
        guidedTradeRepository.save(trade)
        appendEvent(trade.id!!, "HALF_TAKE_PROFIT", execPrice, executedQty, "절반 익절 + 손절가를 본절로 상향")
    }

    private fun executeDcaBuy(trade: GuidedTradeEntity, currentPrice: BigDecimal) {
        val maxExposure = trade.targetAmountKrw.multiply(BigDecimal("2.0"))
        val currentExposure = trade.averageEntryPrice.multiply(trade.entryQuantity)
        val remainingBudget = maxExposure.subtract(currentExposure)
        if (remainingBudget <= BigDecimal.ZERO) {
            trade.lastAction = "DCA_BUDGET_LIMIT"
            guidedTradeRepository.save(trade)
            return
        }

        val addAmount = minOf(
            trade.targetAmountKrw.multiply(BigDecimal("0.5")),
            remainingBudget
        ).setScale(0, RoundingMode.DOWN)
        if (addAmount < BigDecimal(5100)) return

        val order = bithumbPrivateApi.buyMarketOrder(trade.market, addAmount)
        orderLifecycleTelemetryService.recordRequested(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = trade.market,
            side = "BUY",
            orderId = order.uuid,
            strategyCode = GUIDED_STRATEGY_CODE,
            price = addAmount,
            message = "guided_dca_requested"
        )
        val orderInfo = bithumbPrivateApi.getOrder(order.uuid) ?: order
        orderLifecycleTelemetryService.reconcileOrderState(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            order = orderInfo,
            fallbackMarket = trade.market,
            strategyCode = GUIDED_STRATEGY_CODE
        )
        val executedQty = orderInfo.executedVolume ?: BigDecimal.ZERO
        if (executedQty <= BigDecimal.ZERO) return

        val execPrice = resolveEntryPrice(
            order = orderInfo,
            orderType = GuidedTradeEntity.ORDER_TYPE_MARKET,
            investedAmount = addAmount,
            selectedLimitPrice = null,
            fallbackPrice = currentPrice
        )
        val prevValue = trade.averageEntryPrice.multiply(trade.entryQuantity)
        val addValue = execPrice.multiply(executedQty)
        val newQty = trade.entryQuantity.add(executedQty)
        val newAvg = if (newQty > BigDecimal.ZERO) {
            prevValue.add(addValue).divide(newQty, 8, RoundingMode.HALF_UP)
        } else {
            trade.averageEntryPrice
        }

        trade.entryQuantity = newQty
        trade.remainingQuantity = trade.remainingQuantity.add(executedQty)
        trade.averageEntryPrice = newAvg
        trade.dcaCount += 1
        trade.lastAction = "DCA_${trade.dcaCount}"
        guidedTradeRepository.save(trade)
        appendEvent(trade.id!!, "DCA_BUY", execPrice, executedQty, "물타기 ${trade.dcaCount}/${trade.maxDcaCount}")
    }

    private fun canExecuteDcaNow(trade: GuidedTradeEntity): Boolean {
        val lastDca = guidedTradeEventRepository
            .findTopByTradeIdAndEventTypeOrderByCreatedAtDesc(trade.id ?: return true, "DCA_BUY")
            ?: return true
        val elapsed = Instant.now().epochSecond - lastDca.createdAt.epochSecond
        return elapsed >= DCA_MIN_INTERVAL_SECONDS
    }

    private fun getActualHoldingQuantity(
        market: String,
        referencePrice: BigDecimal? = null,
        balancesByCurrency: Map<String, Balance>? = null
    ): BigDecimal {
        val currency = market.substringAfter("KRW-")
        val balance = balancesByCurrency?.get(currency.uppercase())
            ?: bithumbPrivateApi.getBalances()
                ?.firstOrNull { it.currency.equals(currency, ignoreCase = true) }
            ?: return BigDecimal.ZERO
        val quantity = balance.balance.add(balance.locked ?: BigDecimal.ZERO)
        if (quantity <= BigDecimal.ZERO) return BigDecimal.ZERO

        val marketPrice = referencePrice
            ?.takeIf { it > BigDecimal.ZERO }
            ?: bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice

        if (marketPrice != null && marketPrice > BigDecimal.ZERO) {
            val estimatedKrw = quantity.multiply(marketPrice)
            if (estimatedKrw < MIN_EFFECTIVE_HOLDING_KRW) {
                return BigDecimal.ZERO
            }
        }
        return quantity
    }

    private fun fetchBalancesByCurrency(): Map<String, Balance> {
        return bithumbPrivateApi.getBalances()
            .orEmpty()
            .associateBy { it.currency.uppercase() }
    }

    private fun closeAllByMarket(trade: GuidedTradeEntity, reason: String) {
        if (trade.remainingQuantity <= BigDecimal.ZERO) {
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = reason
            trade.lastAction = "CLOSE_SKIPPED_EMPTY"
            guidedTradeRepository.save(trade)
            return
        }

        val currentPrice = bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice ?: trade.averageEntryPrice
        val actualQty = getActualHoldingQuantity(trade.market, currentPrice)
        if (actualQty <= BigDecimal.ZERO) {
            applyExitFill(trade, trade.remainingQuantity, currentPrice, "${reason}_NO_EFFECTIVE_BALANCE")
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "${reason}_NO_EFFECTIVE_BALANCE"
            trade.lastAction = "CLOSE_NO_EFFECTIVE_BALANCE"
            trade.remainingQuantity = BigDecimal.ZERO
            trade.pnlConfidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
            if (trade.averageExitPrice <= BigDecimal.ZERO) {
                trade.averageExitPrice = currentPrice
            }
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_NO_EFFECTIVE_BALANCE", currentPrice, null, "실효 잔고 없음(미세 잔고 제외) 추정 종료")
            return
        }

        val sellQty = actualQty

        val estimatedValue = sellQty.multiply(currentPrice)
        if (estimatedValue < MIN_EFFECTIVE_HOLDING_KRW) {
            log.warn("[${trade.market}] 잔여 수량 ${sellQty} 매도 불가 (추정금액 ${estimatedValue.toPlainString()}원 < ${MIN_EFFECTIVE_HOLDING_KRW.toPlainString()}원). DB 강제 종료.")
            applyExitFill(trade, trade.remainingQuantity, currentPrice, "${reason}_BELOW_MIN_ORDER")
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "${reason}_BELOW_MIN_ORDER"
            trade.lastAction = "CLOSE_BELOW_MIN_ORDER"
            trade.remainingQuantity = BigDecimal.ZERO
            trade.pnlConfidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
            if (trade.averageExitPrice <= BigDecimal.ZERO) {
                trade.averageExitPrice = currentPrice
            }
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_BELOW_MIN_ORDER", currentPrice, sellQty, "최소 주문금액 미달 추정 종료 (${estimatedValue.toPlainString()}원)")
            return
        }

        val sell = try {
            bithumbPrivateApi.sellMarketOrder(trade.market, sellQty)
        } catch (e: Exception) {
            log.error("[${trade.market}] 매도 주문 실패: ${e.message}", e)
            trade.lastAction = "SELL_FAILED: ${e.message?.take(80)}"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "SELL_FAILED", currentPrice, sellQty, "매도 실패: ${e.message?.take(120)}")
            orderLifecycleTelemetryService.recordFailed(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = trade.market,
                side = "SELL",
                orderId = null,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = e.message
            )
            throw IllegalStateException("[${trade.market}] 매도 주문 실패 — 빗썸에서 직접 매도하세요. 원인: ${e.message}")
        }

        orderLifecycleTelemetryService.recordRequested(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            market = trade.market,
            side = "SELL",
            orderId = sell.uuid,
            strategyCode = GUIDED_STRATEGY_CODE,
            quantity = sellQty,
            message = "guided_close_requested:$reason"
        )

        Thread.sleep(300)
        val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
        orderLifecycleTelemetryService.reconcileOrderState(
            strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
            order = sellInfo,
            fallbackMarket = trade.market,
            strategyCode = GUIDED_STRATEGY_CODE
        )
        val executedQty = (sellInfo.executedVolume ?: sellQty).min(sellQty)
        val execPrice = sellInfo.price ?: currentPrice

        if (executedQty <= BigDecimal.ZERO) {
            trade.lastExitOrderId = sell.uuid
            trade.lastAction = "CLOSE_NO_FILL_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_NO_FILL", execPrice, BigDecimal.ZERO, "청산 주문 미체결: $reason")
            orderLifecycleTelemetryService.recordFailed(
                strategyGroup = OrderLifecycleStrategyGroup.GUIDED,
                market = trade.market,
                side = "SELL",
                orderId = sell.uuid,
                strategyCode = GUIDED_STRATEGY_CODE,
                message = "guided_close_no_fill:$reason"
            )
            return
        }

        applyExitFill(trade, executedQty.min(trade.remainingQuantity), execPrice, reason)
        trade.lastExitOrderId = sell.uuid

        // 매도 후 실제 잔고 재확인하여 DB 동기화
        val remainingActual = getActualHoldingQuantity(trade.market)
        if (remainingActual <= BigDecimal.ZERO || trade.remainingQuantity <= BigDecimal.ZERO) {
            trade.remainingQuantity = BigDecimal.ZERO
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = reason
            trade.lastAction = "CLOSED_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_ALL", execPrice, executedQty, "자동 청산: $reason")
        } else {
            trade.remainingQuantity = remainingActual
            trade.status = GuidedTradeEntity.STATUS_OPEN
            trade.lastAction = "PARTIAL_CLOSE_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "PARTIAL_CLOSE", execPrice, executedQty, "부분 청산: $reason (잔여 잔고 동기화)")
        }
    }

    private fun applyExitFill(trade: GuidedTradeEntity, quantity: BigDecimal, price: BigDecimal, reason: String) {
        val effectiveQty = quantity.min(trade.remainingQuantity)
        if (effectiveQty <= BigDecimal.ZERO) return

        val prevQty = trade.cumulativeExitQuantity
        val newQty = prevQty.add(effectiveQty)
        val prevValue = trade.averageExitPrice.multiply(prevQty)
        val newValue = prevValue.add(price.multiply(effectiveQty))
        trade.averageExitPrice = if (newQty > BigDecimal.ZERO) newValue.divide(newQty, 8, RoundingMode.HALF_UP) else trade.averageExitPrice
        trade.cumulativeExitQuantity = newQty
        trade.remainingQuantity = trade.remainingQuantity.subtract(effectiveQty).max(BigDecimal.ZERO)

        val pnl = price.subtract(trade.averageEntryPrice).multiply(effectiveQty)
        trade.realizedPnl = trade.realizedPnl.add(pnl)

        val invested = trade.averageEntryPrice.multiply(newQty)
        trade.realizedPnlPercent = if (invested > BigDecimal.ZERO) {
            trade.realizedPnl.divide(invested, 8, RoundingMode.HALF_UP).multiply(BigDecimal(100))
        } else {
            BigDecimal.ZERO
        }
        trade.exitReason = reason
    }

    private fun getActiveTrade(market: String): GuidedTradeEntity? {
        return guidedTradeRepository.findTopByMarketAndStatusInOrderByCreatedAtDesc(market, OPEN_STATUSES)
    }

    private fun appendEvent(
        tradeId: Long,
        eventType: String,
        price: BigDecimal?,
        quantity: BigDecimal?,
        message: String
    ) {
        guidedTradeEventRepository.save(
            GuidedTradeEventEntity(
                tradeId = tradeId,
                eventType = eventType,
                price = price,
                quantity = quantity,
                message = message
            )
        )
    }

    private fun buildRecommendation(market: String, candles: List<GuidedCandle>, mode: TradingMode = TradingMode.SWING): GuidedRecommendation {
        if (candles.size < 30) {
            val current = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble() ?: 0.0
            val fallbackWinRate = 38.0
            val calibration = calibratePredictedWinRate(market, fallbackWinRate)
            return GuidedRecommendation(
                market = market,
                currentPrice = current,
                recommendedEntryPrice = current,
                stopLossPrice = current * 0.99,
                takeProfitPrice = current * 1.015,
                confidence = 0.45,
                predictedWinRate = calibration.calibrated,
                recommendedEntryWinRate = calibration.calibrated,
                marketEntryWinRate = calibration.calibrated,
                riskRewardRatio = 2.0,
                winRateBreakdown = GuidedWinRateBreakdown(
                    trend = 0.4,
                    pullback = 0.35,
                    volatility = 0.5,
                    riskReward = 0.55
                ),
                suggestedOrderType = "MARKET",
                rationale = listOfNotNull("캔들 데이터 부족으로 보수적 추천", calibration.note)
            )
        }

        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val current = closes.last()
        val sma20 = closes.takeLast(20).average()
        val sma60 = closes.takeLast(60.coerceAtMost(closes.size)).average()
        val support20 = lows.takeLast(20).minOrNull() ?: current
        val atr14 = calcAtr(candles.takeLast(20), 14)
        val momentum = if (current > sma20 && sma20 > sma60) 1.0 else if (current > sma20) 0.7 else 0.4

        val candidate = when {
            current > sma20 -> sma20 * 0.998
            else -> current * 0.997
        }
        val recommended = max(candidate, support20 * 1.001)
        val stopLoss = recommended - max(recommended * mode.slPercent, atr14 * mode.atrMultiplier)
        val rawTakeProfit = recommended + (recommended - stopLoss) * mode.rrRatio
        val takeProfit = minOf(rawTakeProfit, recommended * (1.0 + mode.tpCapPercent))
        val riskRewardRatio = ((takeProfit - recommended) / max(recommended - stopLoss, 1.0)).coerceIn(0.5, 3.0)
        val diffPct = kotlin.math.abs(current - recommended) / current
        val orderType = if (diffPct < 0.0005) "MARKET" else "LIMIT" // 0.05% 미만일 때만 시장가

        val trendScore = when {
            current > sma20 && sma20 > sma60 -> 0.82
            current > sma20 -> 0.65
            current > sma60 -> 0.52
            else -> 0.36
        }
        val pullbackDepth = ((current - recommended) / current).coerceIn(0.0, 0.04)
        val pullbackScore = (0.9 - (pullbackDepth / 0.04) * 0.45).coerceIn(0.35, 0.9)
        val atrPercent = (atr14 / current * 100.0).coerceIn(0.1, 5.0)
        val volatilityScore = (0.92 - (atrPercent / 5.0) * 0.55).coerceIn(0.3, 0.92)
        val rrScore = ((riskRewardRatio - 0.8) / 1.2).coerceIn(0.0, 1.0) * 0.6 + 0.35
        val weighted = trendScore * 0.34 + pullbackScore * 0.24 + volatilityScore * 0.20 + rrScore * 0.22

        val recommendedBaseWinRate = (35.0 + weighted * 47.0).coerceIn(35.0, 82.0)

        val marketStopLoss = current - max(current * mode.slPercent, atr14 * mode.atrMultiplier)
        val rawMarketTakeProfit = current + (current - marketStopLoss) * mode.rrRatio
        val marketTakeProfit = minOf(rawMarketTakeProfit, current * (1.0 + mode.tpCapPercent))
        val marketRiskRewardRatio = ((marketTakeProfit - current) / max(current - marketStopLoss, 1.0)).coerceIn(0.5, 3.0)
        val marketPullbackScore = 0.46
        val marketRrScore = ((marketRiskRewardRatio - 0.8) / 1.2).coerceIn(0.0, 1.0) * 0.6 + 0.35
        val marketWeighted = trendScore * 0.34 + marketPullbackScore * 0.24 + volatilityScore * 0.20 + marketRrScore * 0.22
        val marketEntryPenalty = ((current - recommended) / current * 100.0 * 1.6).coerceIn(-6.0, 10.0)
        val marketBaseWinRate = (35.0 + marketWeighted * 47.0 - marketEntryPenalty).coerceIn(30.0, 82.0)

        val calibration = calibratePredictedWinRate(market, recommendedBaseWinRate)
        val calibratedRecommendedWinRate = calibration.calibrated
        val calibratedMarketWinRate = (marketBaseWinRate + (calibration.calibrated - recommendedBaseWinRate)).coerceIn(28.0, 84.0)

        val confidence = (0.45 + momentum * 0.35 + (if (diffPct < 0.01) 0.15 else 0.05)).coerceIn(0.2, 0.95)

        val reasons = buildList {
            add("SMA20=${formatPrice(sma20)}, SMA60=${formatPrice(sma60)}")
            add("지지선(20봉 저점)=${formatPrice(support20)}")
            add("ATR14=${formatPrice(atr14)} 기반 손절 폭 적용")
            add("RR 2.2 기준 익절/손절 자동 설정")
            calibration.note?.let { add(it) }
        }

        val keyLevels = buildList {
            add(KeyLevel(sma20, "SMA20", "INDICATOR"))
            if (closes.size >= 60) add(KeyLevel(sma60, "SMA60", "INDICATOR"))
            add(KeyLevel(support20, "지지선(20봉)", "SUPPORT"))
        }

        return GuidedRecommendation(
            market = market,
            currentPrice = current,
            recommendedEntryPrice = recommended,
            stopLossPrice = stopLoss,
            takeProfitPrice = takeProfit,
            confidence = confidence,
            predictedWinRate = calibratedRecommendedWinRate,
            recommendedEntryWinRate = calibratedRecommendedWinRate,
            marketEntryWinRate = calibratedMarketWinRate,
            riskRewardRatio = riskRewardRatio,
            winRateBreakdown = GuidedWinRateBreakdown(
                trend = trendScore,
                pullback = pullbackScore,
                volatility = volatilityScore,
                riskReward = rrScore
            ),
            suggestedOrderType = orderType,
            rationale = reasons,
            keyLevels = keyLevels
        )
    }

    private fun buildAgentFeaturePack(chart: GuidedChartResponse): GuidedAgentFeaturePack {
        val recommendation = chart.recommendation
        val closes = chart.candles.map { it.close }
        val current = recommendation.currentPrice
        val recommended = recommendation.recommendedEntryPrice
        val entryGapPct = if (recommended > 0.0 && current > 0.0) {
            ((current - recommended) / recommended * 100.0).coerceIn(-30.0, 30.0)
        } else {
            0.0
        }

        val atr14 = calcAtr(chart.candles.takeLast(20), 14)
        val atrPercent = if (current > 0.0) (atr14 / current * 100.0).coerceIn(0.0, 20.0) else 0.0
        val momentum6 = closes.percentChange(6)
        val momentum24 = closes.percentChange(24)
        val stopDistancePct = if (recommended > 0.0) {
            ((recommended - recommendation.stopLossPrice) / recommended * 100.0).coerceIn(0.0, 30.0)
        } else {
            0.0
        }
        val takeProfitDistancePct = if (recommended > 0.0) {
            ((recommendation.takeProfitPrice - recommended) / recommended * 100.0).coerceIn(0.0, 50.0)
        } else {
            0.0
        }

        val orderbook = chart.orderbook
        val spreadPercent = orderbook?.spreadPercent ?: 0.0
        val totalAsk = orderbook?.totalAskSize ?: 0.0
        val totalBid = orderbook?.totalBidSize ?: 0.0
        val imbalance = if (totalAsk + totalBid > 0.0) {
            ((totalBid - totalAsk) / (totalBid + totalAsk)).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val top5 = orderbook?.units.orEmpty().take(5)
        val top5Ask = top5.sumOf { it.askSize }
        val top5Bid = top5.sumOf { it.bidSize }
        val top5Imbalance = if (top5Ask + top5Bid > 0.0) {
            ((top5Bid - top5Ask) / (top5Bid + top5Ask)).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }

        val chasingRiskScore = (entryGapPct / 2.0 + spreadPercent * 5.0).coerceIn(0.0, 100.0)
        val pendingRiskScore = (spreadPercent * 6.0 + (1.0 - recommendation.confidence).coerceIn(0.0, 1.0) * 40.0).coerceIn(0.0, 100.0)

        return GuidedAgentFeaturePack(
            generatedAt = Instant.now(),
            technical = GuidedTechnicalFeaturePack(
                trendScore = recommendation.winRateBreakdown.trend,
                pullbackScore = recommendation.winRateBreakdown.pullback,
                volatilityScore = recommendation.winRateBreakdown.volatility,
                riskRewardScore = recommendation.winRateBreakdown.riskReward,
                riskRewardRatio = recommendation.riskRewardRatio,
                recommendedEntryWinRate = recommendation.recommendedEntryWinRate,
                marketEntryWinRate = recommendation.marketEntryWinRate,
                atrPercent = atrPercent,
                momentum6 = momentum6,
                momentum24 = momentum24,
                entryGapPct = entryGapPct
            ),
            microstructure = GuidedMicrostructureFeaturePack(
                spreadPercent = spreadPercent,
                totalBidSize = totalBid,
                totalAskSize = totalAsk,
                bidAskImbalance = imbalance,
                top5BidAskImbalance = top5Imbalance,
                orderbookTimestamp = orderbook?.timestamp
            ),
            executionRisk = GuidedExecutionRiskFeaturePack(
                suggestedOrderType = recommendation.suggestedOrderType,
                chasingRiskScore = chasingRiskScore,
                pendingFillRiskScore = pendingRiskScore,
                stopDistancePercent = stopDistancePct,
                takeProfitDistancePercent = takeProfitDistancePct,
                confidence = recommendation.confidence
            )
        )
    }

    private fun List<Double>.percentChange(lookback: Int): Double {
        if (size <= lookback) return 0.0
        val now = this[size - 1]
        val prev = this[size - 1 - lookback]
        if (prev == 0.0) return 0.0
        return ((now - prev) / prev * 100.0).coerceIn(-30.0, 30.0)
    }

    fun getTodayStats(): GuidedDailyStats {
        val todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant()
        val closedToday = guidedTradeRepository.findByStatusAndClosedAtAfter(
            GuidedTradeEntity.STATUS_CLOSED, todayStart
        )
        val wins = closedToday.count { it.realizedPnl > BigDecimal.ZERO }
        val losses = closedToday.size - wins
        val totalPnl = closedToday.sumOf { it.realizedPnl.toDouble() }
        val avgPnlPercent = if (closedToday.isNotEmpty())
            closedToday.map { it.realizedPnlPercent.toDouble() }.average() else 0.0
        val openPositions = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        val tickerMap = fetchTickerMap(openPositions.map { it.market }.distinct())
        val balancesByCurrency = fetchBalancesByCurrency()
        val effectiveOpenPositions = openPositions.filter { trade ->
            if (trade.status == GuidedTradeEntity.STATUS_PENDING_ENTRY) return@filter true
            val marketPrice = tickerMap[trade.market]?.tradePrice
            getActualHoldingQuantity(trade.market, marketPrice, balancesByCurrency) > BigDecimal.ZERO
        }
        val totalInvested = effectiveOpenPositions.sumOf {
            it.averageEntryPrice.multiply(it.remainingQuantity).toDouble()
        }
        return GuidedDailyStats(
            totalTrades = closedToday.size,
            wins = wins,
            losses = losses,
            totalPnlKrw = totalPnl,
            avgPnlPercent = avgPnlPercent,
            winRate = if (closedToday.isNotEmpty()) wins.toDouble() / closedToday.size * 100.0 else 0.0,
            openPositionCount = effectiveOpenPositions.size,
            totalInvestedKrw = totalInvested,
            trades = closedToday.map { it.toClosedTradeView() }
        )
    }

    @Transactional
    fun syncPositions(): GuidedSyncResult {
        val actions = mutableListOf<GuidedSyncAction>()
        val openTrades = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        val tickerMap = fetchTickerMap(openTrades.map { it.market }.distinct())
        val balancesByCurrency = fetchBalancesByCurrency()
        var fixedCount = 0

        for (trade in openTrades) {
            val marketPrice = tickerMap[trade.market]?.tradePrice
            val actualQty = getActualHoldingQuantity(trade.market, marketPrice, balancesByCurrency)

            when {
                actualQty <= BigDecimal.ZERO -> {
                    trade.remainingQuantity = BigDecimal.ZERO
                    trade.status = GuidedTradeEntity.STATUS_CLOSED
                    trade.closedAt = Instant.now()
                    trade.exitReason = "SYNC_MANUAL_SELL"
                    trade.lastAction = "SYNC_CLOSED"
                    guidedTradeRepository.save(trade)
                    appendEvent(trade.id!!, "SYNC_CLOSE", null, null, "실효 잔고 없음(미세 잔고 제외) - 외부 매도 감지")
                    actions.add(GuidedSyncAction("CLOSED", trade.market, "실효 잔고 없음(미세 잔고 제외) - 외부 매도 감지 (trade #${trade.id})"))
                    fixedCount++
                }
                (actualQty.subtract(trade.remainingQuantity)).abs() > BigDecimal("0.0001") -> {
                    val oldQty = trade.remainingQuantity
                    trade.remainingQuantity = actualQty
                    trade.lastAction = "SYNC_ADJUSTED"
                    guidedTradeRepository.save(trade)
                    appendEvent(trade.id!!, "SYNC_ADJUST", null, actualQty, "수량 보정: $oldQty -> $actualQty")
                    actions.add(GuidedSyncAction("ADJUSTED", trade.market, "수량 보정: $oldQty -> $actualQty (trade #${trade.id})"))
                    fixedCount++
                }
                else -> {
                    actions.add(GuidedSyncAction("OK", trade.market, "정상 (trade #${trade.id})"))
                }
            }
        }

        val message = if (fixedCount > 0) "${fixedCount}건 동기화 완료" else "모든 포지션이 정상입니다"
        return GuidedSyncResult(success = true, message = message, actions = actions, fixedCount = fixedCount)
    }

    fun getClosedTrades(limit: Int): List<GuidedClosedTradeView> {
        return guidedTradeRepository.findByStatusOrderByClosedAtDesc(GuidedTradeEntity.STATUS_CLOSED)
            .take(limit)
            .map { it.toClosedTradeView() }
    }

    @Transactional
    fun reconcileClosedTrades(windowDays: Long = 30, dryRun: Boolean = true): GuidedPnlReconcileResult {
        val effectiveWindowDays = windowDays.coerceIn(1, 30)
        val now = Instant.now()
        val since = now.minus(effectiveWindowDays, ChronoUnit.DAYS)
        val trades = guidedTradeRepository.findByStatusAndClosedAtAfter(GuidedTradeEntity.STATUS_CLOSED, since)
            .sortedByDescending { it.closedAt ?: it.updatedAt }

        var updatedCount = 0
        var unchangedCount = 0
        var highConfidenceCount = 0
        var lowConfidenceCount = 0
        val sample = mutableListOf<GuidedPnlReconcileItem>()

        for (trade in trades) {
            val recalculated = recalculateClosedTradeMetrics(trade)

            if (recalculated.confidence == GuidedTradeEntity.PNL_CONFIDENCE_HIGH) {
                highConfidenceCount++
            } else {
                lowConfidenceCount++
            }

            val changed = recalculated.canApply && (
                trade.averageEntryPrice.compareTo(recalculated.averageEntryPrice) != 0 ||
                    trade.entryQuantity.compareTo(recalculated.entryQuantity) != 0 ||
                    trade.cumulativeExitQuantity.compareTo(recalculated.cumulativeExitQuantity) != 0 ||
                    trade.averageExitPrice.compareTo(recalculated.averageExitPrice) != 0 ||
                    trade.realizedPnl.setScale(2, RoundingMode.HALF_UP).compareTo(recalculated.realizedPnl) != 0 ||
                    trade.realizedPnlPercent.setScale(4, RoundingMode.HALF_UP).compareTo(recalculated.realizedPnlPercent) != 0 ||
                    trade.pnlConfidence != recalculated.confidence
                )
            if (changed) {
                updatedCount++
            } else {
                unchangedCount++
            }

            if (!dryRun) {
                trade.pnlConfidence = recalculated.confidence
                trade.pnlReconciledAt = now
                if (recalculated.canApply) {
                    trade.averageEntryPrice = recalculated.averageEntryPrice
                    trade.entryQuantity = recalculated.entryQuantity
                    trade.cumulativeExitQuantity = recalculated.cumulativeExitQuantity
                    trade.averageExitPrice = recalculated.averageExitPrice
                    trade.realizedPnl = recalculated.realizedPnl
                    trade.realizedPnlPercent = recalculated.realizedPnlPercent
                }
                val saved = guidedTradeRepository.save(trade)
                appendEvent(
                    tradeId = saved.id ?: continue,
                    eventType = "PNL_RECONCILE",
                    price = recalculated.averageExitPrice.takeIf { it > BigDecimal.ZERO },
                    quantity = recalculated.cumulativeExitQuantity.takeIf { it > BigDecimal.ZERO },
                    message = "최근 ${effectiveWindowDays}일 손익 보정(${recalculated.confidence}) - ${recalculated.reason}"
                )
            }

            if (sample.size < 20) {
                sample.add(
                    GuidedPnlReconcileItem(
                        tradeId = trade.id ?: 0L,
                        market = trade.market,
                        confidence = recalculated.confidence,
                        reason = recalculated.reason,
                        recalculated = recalculated.canApply,
                        realizedPnl = recalculated.realizedPnl.toDouble(),
                        realizedPnlPercent = recalculated.realizedPnlPercent.toDouble()
                    )
                )
            }
        }

        return GuidedPnlReconcileResult(
            windowDays = effectiveWindowDays,
            dryRun = dryRun,
            scannedTrades = trades.size,
            updatedTrades = updatedCount,
            unchangedTrades = unchangedCount,
            highConfidenceTrades = highConfidenceCount,
            lowConfidenceTrades = lowConfidenceCount,
            sample = sample
        )
    }

    fun getAutopilotOpportunities(
        interval: String = "minute1",
        confirmInterval: String = "minute10",
        mode: TradingMode = TradingMode.SCALP,
        universeLimit: Int = AUTOPILOT_OPPORTUNITY_UNIVERSE_LIMIT
    ): GuidedAutopilotOpportunitiesResponse {
        val effectiveUniverseLimit = universeLimit.coerceIn(5, 50)
        val universe = getMarketBoard(
            sortBy = GuidedMarketSortBy.TURNOVER,
            sortDirection = GuidedSortDirection.DESC,
            interval = interval,
            mode = mode
        ).take(effectiveUniverseLimit)

        if (universe.isEmpty()) {
            return GuidedAutopilotOpportunitiesResponse(
                generatedAt = Instant.now(),
                primaryInterval = interval,
                confirmInterval = confirmInterval,
                mode = mode.name,
                appliedUniverseLimit = effectiveUniverseLimit,
                opportunities = emptyList()
            )
        }

        val markets = universe.map { it.market }
        val primaryRecommendations = fetchRecommendationsWithCache(markets, interval, mode)
        val confirmRecommendations = fetchRecommendationsWithCache(markets, confirmInterval, mode)

        val opportunities = universe
            .map { marketItem ->
                buildAutopilotOpportunity(
                    marketItem = marketItem,
                    primaryRecommendation = primaryRecommendations[marketItem.market],
                    confirmRecommendation = confirmRecommendations[marketItem.market]
                )
            }
            .sortedByDescending { it.score }

        return GuidedAutopilotOpportunitiesResponse(
            generatedAt = Instant.now(),
            primaryInterval = interval,
            confirmInterval = confirmInterval,
            mode = mode.name,
            appliedUniverseLimit = effectiveUniverseLimit,
            opportunities = opportunities
        )
    }

    fun getAutopilotLive(
        interval: String = "minute30",
        mode: TradingMode = TradingMode.SWING,
        thresholdMode: GuidedWinRateThresholdMode = GuidedWinRateThresholdMode.DYNAMIC_P70,
        minMarketWinRate: Double? = null,
        minRecommendedWinRate: Double? = null,
        strategyCodePrefix: String? = null
    ): GuidedAutopilotLiveResponse {
        val normalizedStrategyCodePrefix = strategyCodePrefix
            ?.trim()
            ?.uppercase()
            ?.ifBlank { null }
        val telemetry = orderLifecycleTelemetryService.getLiveSnapshot(
            strategyCodePrefix = normalizedStrategyCodePrefix
        )
        val sortedMarkets = getMarketBoard(
            sortBy = GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE,
            sortDirection = GuidedSortDirection.DESC,
            interval = interval,
            mode = mode
        )
        val topCandidates = sortedMarkets.take(10)
        val requestedMin = (minMarketWinRate ?: minRecommendedWinRate)?.coerceIn(50.0, 80.0)
        val recommendedRates = sortedMarkets.mapNotNull { it.recommendedEntryWinRate }
        val appliedThreshold = when (thresholdMode) {
            GuidedWinRateThresholdMode.FIXED -> requestedMin ?: 60.0
            GuidedWinRateThresholdMode.DYNAMIC_P70 -> resolveDynamicRecommendedThreshold(recommendedRates)
        }

        val candidates = topCandidates.map { market ->
            val recommendedWinRate = market.recommendedEntryWinRate
            val marketWinRate = market.marketEntryWinRate
            val thresholdValue = if (thresholdMode == GuidedWinRateThresholdMode.FIXED) {
                marketWinRate
            } else {
                recommendedWinRate
            }
            val thresholdLabel = if (thresholdMode == GuidedWinRateThresholdMode.FIXED) {
                "현재가 승률"
            } else {
                "추천가 승률"
            }
            val pass = (thresholdValue ?: 0.0) >= appliedThreshold
            val stage = when {
                !pass -> "RULE_FAIL"
                (thresholdValue ?: 0.0) >= appliedThreshold + 2.0 -> "AUTO_PASS"
                else -> "BORDERLINE"
            }
            GuidedAutopilotCandidateView(
                market = market.market,
                koreanName = market.koreanName,
                recommendedEntryWinRate = recommendedWinRate,
                marketEntryWinRate = marketWinRate,
                stage = stage,
                reason = if (pass) {
                    "$thresholdLabel ${String.format("%.1f", thresholdValue ?: 0.0)}% >= ${String.format("%.1f", appliedThreshold)}% ($stage)"
                } else {
                    "$thresholdLabel ${String.format("%.1f", thresholdValue ?: 0.0)}% < ${String.format("%.1f", appliedThreshold)}%"
                }
            )
        }

        val autopilotEvents = telemetry.orderEvents
            .filter { it.strategyGroup == OrderLifecycleStrategyGroup.AUTOPILOT_MCP.name }
            .take(80)
            .map { event ->
                GuidedAutopilotEvent(
                    market = event.market,
                    eventType = event.eventType,
                    level = if (event.eventType == "FAILED") "WARN" else "INFO",
                    message = event.message ?: "${event.eventType}(${event.market})",
                    createdAt = event.createdAt
                )
            }

        val oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS)
        val recentAutopilotOrderEvents = telemetry.orderEvents.filter { event ->
            event.createdAt.isAfter(oneHourAgo) &&
                (event.strategyCode?.uppercase()?.contains("AUTOPILOT") == true)
        }
        val decisionStats = GuidedAutopilotDecisionStats(
            rulePass = candidates.count { it.stage != "RULE_FAIL" },
            ruleFail = candidates.count { it.stage == "RULE_FAIL" },
            llmReject = recentAutopilotOrderEvents.count {
                it.eventType == "FAILED" && (it.message?.contains("llm", ignoreCase = true) == true)
            },
            entered = recentAutopilotOrderEvents.count { it.eventType == "BUY_REQUESTED" },
            pendingTimeout = recentAutopilotOrderEvents.count {
                it.eventType == "CANCEL_REQUESTED" &&
                    (
                        it.message?.contains("pending", ignoreCase = true) == true ||
                            it.message?.contains("timeout", ignoreCase = true) == true
                        )
            }
        )
        val strategyCodeSummary = telemetry.orderEvents
            .groupBy { event ->
                event.strategyCode
                    ?.trim()
                    ?.ifBlank { null }
                    ?: GUIDED_STRATEGY_CODE
            }
            .mapValues { (_, events) ->
                GuidedStrategyCodeSummary(
                    buyRequested = events.count { it.eventType == "BUY_REQUESTED" },
                    buyFilled = events.count { it.eventType == "BUY_FILLED" },
                    sellRequested = events.count { it.eventType == "SELL_REQUESTED" },
                    sellFilled = events.count { it.eventType == "SELL_FILLED" },
                    failed = events.count { it.eventType == "FAILED" },
                    cancelled = events.count { it.eventType == "CANCELLED" || it.eventType == "CANCEL_REQUESTED" },
                )
            }

        return GuidedAutopilotLiveResponse(
            orderSummary = telemetry.orderSummary,
            orderEvents = telemetry.orderEvents,
            autopilotEvents = autopilotEvents,
            candidates = candidates,
            thresholdMode = thresholdMode.name,
            appliedRecommendedWinRateThreshold = appliedThreshold,
            requestedMinMarketWinRate = requestedMin,
            requestedMinRecommendedWinRate = requestedMin,
            decisionStats = decisionStats,
            strategyCodeSummary = strategyCodeSummary,
        )
    }

    @Transactional
    fun logAutopilotDecision(request: GuidedAutopilotDecisionLogRequest): GuidedAutopilotDecisionLogResponse {
        require(request.runId.isNotBlank()) { "runId는 필수입니다." }
        require(request.market.startsWith("KRW-")) { "market 형식 오류: KRW-BTC 형태를 사용하세요." }
        require(request.stage.isNotBlank()) { "stage는 필수입니다." }

        val payloadJson = runCatching {
            objectMapper.writeValueAsString(
                mapOf(
                    "featurePack" to request.featurePack,
                    "specialistOutputs" to request.specialistOutputs,
                    "synthOutput" to request.synthOutput,
                    "pmOutput" to request.pmOutput,
                    "orderPlan" to request.orderPlan
                )
            )
        }.getOrElse {
            "{}"
        }

        val saved = guidedAutopilotDecisionRepository.save(
            GuidedAutopilotDecisionEntity(
                runId = request.runId.trim().take(80),
                market = request.market.trim().uppercase(),
                intervalValue = request.interval.trim().ifBlank { "minute30" }.take(20),
                mode = request.mode.trim().ifBlank { "SWING" }.uppercase().take(20),
                stage = request.stage.trim().uppercase().take(30),
                approve = request.approve,
                confidence = request.confidence?.let { BigDecimal.valueOf(it) },
                score = request.score?.let { BigDecimal.valueOf(it) },
                entryAmountKrw = request.entryAmountKrw?.let { BigDecimal.valueOf(it) },
                executed = request.executed,
                reason = request.reason?.take(600),
                payloadJson = payloadJson,
                createdAt = Instant.now()
            )
        )

        return GuidedAutopilotDecisionLogResponse(
            accepted = true,
            decisionId = saved.id ?: 0L,
            createdAt = saved.createdAt
        )
    }

    fun getAutopilotPerformance(
        windowDays: Int = 30,
        strategyCodePrefix: String? = null
    ): GuidedAutopilotPerformanceResponse {
        val effectiveWindowDays = windowDays.coerceIn(1, 120)
        val normalizedStrategyCodePrefix = strategyCodePrefix
            ?.trim()
            ?.uppercase()
            ?.ifBlank { null }
        val to = Instant.now()
        val from = to.minus(effectiveWindowDays.toLong(), ChronoUnit.DAYS)
        val closedTrades = guidedTradeRepository.findByStatusOrderByClosedAtDesc(GuidedTradeEntity.STATUS_CLOSED)
            .asSequence()
            .filter { trade -> trade.closedAt != null && trade.closedAt!!.isAfter(from) }
            .filter { trade ->
                val source = trade.entrySource?.uppercase().orEmpty()
                val strategy = trade.strategyCode?.uppercase().orEmpty()
                if (normalizedStrategyCodePrefix != null) {
                    strategy.startsWith(normalizedStrategyCodePrefix)
                } else {
                    source.contains("AUTOPILOT") || source.contains("MCP_DIRECT") || strategy.contains("AUTOPILOT")
                }
            }
            .sortedBy { it.closedAt }
            .toList()

        if (closedTrades.isEmpty()) {
            return GuidedAutopilotPerformanceResponse(
                windowDays = effectiveWindowDays,
                from = from,
                to = to,
                trades = 0,
                winRate = 0.0,
                netPnlKrw = 0.0,
                netReturnPercent = 0.0,
                sharpe = 0.0,
                maxDrawdownPercent = 0.0,
                gateEligible = false,
                gateReason = "평가 구간 내 오토파일럿 청산 거래가 없습니다."
            )
        }

        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        val perTradeReturns = mutableListOf<Double>()

        closedTrades.forEach { trade ->
            val invested = trade.averageEntryPrice.multiply(trade.entryQuantity)
            val tradeReturn = if (invested > BigDecimal.ZERO) {
                trade.realizedPnl.divide(invested, 8, RoundingMode.HALF_UP).toDouble()
            } else {
                0.0
            }
            perTradeReturns += tradeReturn
            equity *= (1.0 + tradeReturn)
            if (equity > peak) peak = equity
            val drawdown = if (peak > 0.0) ((peak - equity) / peak) * 100.0 else 0.0
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        val wins = closedTrades.count { it.realizedPnl > BigDecimal.ZERO }
        val netPnl = closedTrades.sumOf { it.realizedPnl.toDouble() }
        val totalInvested = closedTrades.sumOf {
            it.averageEntryPrice.multiply(it.entryQuantity).toDouble()
        }
        val netReturnPercent = if (totalInvested > 0.0) {
            netPnl / totalInvested * 100.0
        } else {
            0.0
        }
        val sharpe = SharpeRatioCalculator.calculateTradeBased(perTradeReturns, 1.0)
        val winRate = wins.toDouble() / closedTrades.size.toDouble() * 100.0

        val gateChecks = buildList {
            if (sharpe <= AUTOPILOT_PROMOTION_SHARPE_GATE) {
                add("Sharpe ${String.format("%.2f", sharpe)} <= ${String.format("%.2f", AUTOPILOT_PROMOTION_SHARPE_GATE)}")
            }
            if (maxDrawdown >= AUTOPILOT_PROMOTION_MAX_DD_GATE) {
                add("MaxDD ${String.format("%.2f", maxDrawdown)}% >= ${String.format("%.2f", AUTOPILOT_PROMOTION_MAX_DD_GATE)}%")
            }
            if (winRate <= AUTOPILOT_PROMOTION_WIN_RATE_GATE) {
                add("승률 ${String.format("%.1f", winRate)}% <= ${String.format("%.1f", AUTOPILOT_PROMOTION_WIN_RATE_GATE)}%")
            }
            if (netPnl <= 0.0) {
                add("순손익 ${String.format("%.0f", netPnl)}원 <= 0")
            }
        }
        val gateEligible = gateChecks.isEmpty()
        val gateReason = if (gateEligible) {
            "증액 게이트 통과"
        } else {
            gateChecks.joinToString(" | ")
        }

        return GuidedAutopilotPerformanceResponse(
            windowDays = effectiveWindowDays,
            from = from,
            to = to,
            trades = closedTrades.size,
            winRate = winRate,
            netPnlKrw = netPnl,
            netReturnPercent = netReturnPercent,
            sharpe = sharpe,
            maxDrawdownPercent = maxDrawdown,
            gateEligible = gateEligible,
            gateReason = gateReason
        )
    }

    private fun fetchRecommendationsWithCache(
        markets: List<String>,
        interval: String,
        mode: TradingMode
    ): Map<String, GuidedRecommendation?> {
        if (markets.isEmpty()) return emptyMap()
        val normalizedInterval = interval.lowercase()
        val now = System.currentTimeMillis()
        val candleCount = if (normalizedInterval == "tick") 300 else 120

        val staleMarkets = markets.filter { market ->
            val key = OpportunityRecommendationCacheKey(market = market, interval = normalizedInterval, mode = mode)
            val cached = opportunityRecommendationCache[key]
            cached == null || (now - cached.atMillis) >= AUTOPILOT_OPPORTUNITY_CACHE_TTL_MILLIS
        }

        if (staleMarkets.isNotEmpty()) {
            val futures = staleMarkets.associateWith { market ->
                CompletableFuture.supplyAsync(
                    {
                        runCatching {
                            getRecommendation(market, normalizedInterval, candleCount, mode)
                        }.getOrNull()
                    },
                    marketWinRateExecutor
                )
            }

            futures.forEach { (market, future) ->
                try {
                    val recommendation = future.get(WIN_RATE_CALC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: return@forEach
                    val key = OpportunityRecommendationCacheKey(market = market, interval = normalizedInterval, mode = mode)
                    opportunityRecommendationCache[key] = CachedOpportunityRecommendation(
                        atMillis = now,
                        recommendation = recommendation
                    )
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    log.warn(
                        "오토파일럿 추천 캐시 갱신 타임아웃: market={}, interval={}, mode={}",
                        market,
                        normalizedInterval,
                        mode.name
                    )
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    log.warn(
                        "오토파일럿 추천 캐시 갱신 실패: market={}, interval={}, mode={}, error={}",
                        market,
                        normalizedInterval,
                        mode.name,
                        e.message
                    )
                }
            }
        }

        return markets.associateWith { market ->
            val key = OpportunityRecommendationCacheKey(market = market, interval = normalizedInterval, mode = mode)
            opportunityRecommendationCache[key]?.recommendation
        }
    }

    private fun buildAutopilotOpportunity(
        marketItem: GuidedMarketItem,
        primaryRecommendation: GuidedRecommendation?,
        confirmRecommendation: GuidedRecommendation?
    ): GuidedAutopilotOpportunityView {
        val recWin1m = primaryRecommendation?.recommendedEntryWinRate?.takeIf { it.isFinite() }
        val recWin10m = confirmRecommendation?.recommendedEntryWinRate?.takeIf { it.isFinite() }
        val marketWin1m = primaryRecommendation?.marketEntryWinRate?.takeIf { it.isFinite() }
        val marketWin10m = confirmRecommendation?.marketEntryWinRate?.takeIf { it.isFinite() }

        val rr1m = primaryRecommendation?.riskRewardRatio?.takeIf { it.isFinite() } ?: 0.0
        val rr10m = confirmRecommendation?.riskRewardRatio?.takeIf { it.isFinite() } ?: 0.0
        val entryPrice = primaryRecommendation?.recommendedEntryPrice ?: 0.0
        val currentPrice = primaryRecommendation?.currentPrice ?: 0.0
        val stopLoss = primaryRecommendation?.stopLossPrice ?: 0.0
        val takeProfit = primaryRecommendation?.takeProfitPrice ?: 0.0

        val entryGapPct = if (entryPrice > 0.0 && currentPrice > 0.0) {
            max(0.0, (currentPrice - entryPrice) / entryPrice * 100.0)
        } else {
            0.0
        }

        if (
            recWin1m == null ||
            recWin10m == null ||
            marketWin1m == null ||
            marketWin10m == null ||
            !rr1m.isFinite() ||
            !rr10m.isFinite()
        ) {
            return GuidedAutopilotOpportunityView(
                market = marketItem.market,
                koreanName = marketItem.koreanName,
                recommendedEntryWinRate1m = recWin1m,
                recommendedEntryWinRate10m = recWin10m,
                marketEntryWinRate1m = marketWin1m,
                marketEntryWinRate10m = marketWin10m,
                riskReward1m = rr1m,
                entryGapPct1m = entryGapPct,
                expectancyPct = 0.0,
                score = 0.0,
                stage = "RULE_FAIL",
                reason = "승률 또는 RR 데이터 부족"
            )
        }

        val profitPct = if (entryPrice > 0.0 && takeProfit > 0.0) {
            (takeProfit - entryPrice) / entryPrice * 100.0
        } else {
            0.0
        }
        val lossPct = if (entryPrice > 0.0 && stopLoss > 0.0) {
            max(0.0, (entryPrice - stopLoss) / entryPrice * 100.0)
        } else {
            0.0
        }
        val p = 0.6 * (recWin1m / 100.0) + 0.4 * (recWin10m / 100.0)
        val expectancyPct = p * profitPct - (1.0 - p) * lossPct
        val rawScore =
            50.0 +
                22.0 * expectancyPct +
                12.0 * (rr1m - 1.0) +
                0.35 * (recWin1m - 50.0) +
                0.25 * (recWin10m - 50.0) -
                8.0 * max(0.0, entryGapPct - 0.35)
        val score = rawScore.coerceIn(0.0, 100.0)

        val isRuleFail = rr1m < 1.02 || rr10m < 1.08 || entryGapPct > 1.4
        val stage = when {
            isRuleFail -> "RULE_FAIL"
            score >= 64.0 && expectancyPct >= 0.12 -> "AUTO_PASS"
            score >= 56.0 && expectancyPct >= 0.02 -> "BORDERLINE"
            else -> "RULE_FAIL"
        }
        val reason = when (stage) {
            "RULE_FAIL" -> "RR1m ${String.format("%.2f", rr1m)}, RR10m ${String.format("%.2f", rr10m)}, 괴리 ${String.format("%.2f", entryGapPct)}%"
            "AUTO_PASS" -> "score ${String.format("%.1f", score)} / expectancy ${String.format("%.3f", expectancyPct)}%"
            else -> "경계구간 score ${String.format("%.1f", score)} / expectancy ${String.format("%.3f", expectancyPct)}%"
        }

        return GuidedAutopilotOpportunityView(
            market = marketItem.market,
            koreanName = marketItem.koreanName,
            recommendedEntryWinRate1m = recWin1m,
            recommendedEntryWinRate10m = recWin10m,
            marketEntryWinRate1m = marketWin1m,
            marketEntryWinRate10m = marketWin10m,
            riskReward1m = rr1m,
            entryGapPct1m = entryGapPct,
            expectancyPct = expectancyPct,
            score = score,
            stage = stage,
            reason = reason
        )
    }

    private fun resolveDynamicRecommendedThreshold(rates: List<Double>): Double {
        if (rates.isEmpty()) return 55.0
        val sorted = rates.sorted()
        val percentileIndex = kotlin.math.ceil(sorted.size * 0.7).toInt().coerceIn(1, sorted.size) - 1
        var threshold = sorted[percentileIndex].coerceIn(58.0, 65.0)
        while (threshold > 55.0 && sorted.count { it >= threshold } < 2) {
            threshold -= 1.0
        }
        if (threshold < 55.0) threshold = 55.0
        return (threshold * 10.0).toInt() / 10.0
    }

    private fun recalculateClosedTradeMetrics(trade: GuidedTradeEntity): ClosedTradeRecalculation {
        val tradeId = trade.id ?: return ClosedTradeRecalculation(
            canApply = false,
            confidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW,
            averageEntryPrice = trade.averageEntryPrice,
            entryQuantity = trade.entryQuantity,
            cumulativeExitQuantity = trade.cumulativeExitQuantity,
            averageExitPrice = trade.averageExitPrice,
            realizedPnl = trade.realizedPnl.setScale(2, RoundingMode.HALF_UP),
            realizedPnlPercent = trade.realizedPnlPercent.setScale(4, RoundingMode.HALF_UP),
            reason = "trade id 없음"
        )

        val events = guidedTradeEventRepository.findByTradeIdOrderByCreatedAtAsc(tradeId)
        val entryFills = events.filter { event ->
            isEntryFillEvent(event.eventType) &&
                (event.quantity ?: BigDecimal.ZERO) > BigDecimal.ZERO &&
                (event.price ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }
        val exitFills = events.filter { event ->
            isExitFillEvent(event.eventType) &&
                (event.quantity ?: BigDecimal.ZERO) > BigDecimal.ZERO &&
                (event.price ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }

        var confidence = if (entryFills.isNotEmpty() && exitFills.isNotEmpty()) {
            GuidedTradeEntity.PNL_CONFIDENCE_HIGH
        } else {
            GuidedTradeEntity.PNL_CONFIDENCE_LOW
        }

        var entryQty = entryFills.fold(BigDecimal.ZERO) { acc, event -> acc.add(event.quantity ?: BigDecimal.ZERO) }
        var entryValue = entryFills.fold(BigDecimal.ZERO) { acc, event ->
            val qty = event.quantity ?: BigDecimal.ZERO
            val price = event.price ?: BigDecimal.ZERO
            acc.add(price.multiply(qty))
        }

        if (entryQty <= BigDecimal.ZERO || entryValue <= BigDecimal.ZERO) {
            confidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
            entryQty = trade.entryQuantity
            entryValue = trade.averageEntryPrice.multiply(entryQty)
        }

        var exitQty = exitFills.fold(BigDecimal.ZERO) { acc, event -> acc.add(event.quantity ?: BigDecimal.ZERO) }
        var exitValue = exitFills.fold(BigDecimal.ZERO) { acc, event ->
            val qty = event.quantity ?: BigDecimal.ZERO
            val price = event.price ?: BigDecimal.ZERO
            acc.add(price.multiply(qty))
        }

        if (exitQty <= BigDecimal.ZERO || exitValue <= BigDecimal.ZERO) {
            confidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW
            val exchangeFallback = trade.lastExitOrderId
                ?.let { orderId -> runCatching { bithumbPrivateApi.getOrder(orderId) }.getOrNull() }
                ?.let { order ->
                    val qty = order.executedVolume ?: BigDecimal.ZERO
                    val price = order.price ?: BigDecimal.ZERO
                    if (qty > BigDecimal.ZERO && price > BigDecimal.ZERO) qty to price else null
                }

            if (exchangeFallback != null) {
                exitQty = exchangeFallback.first
                exitValue = exchangeFallback.second.multiply(exitQty)
            } else {
                exitQty = trade.cumulativeExitQuantity
                exitValue = trade.averageExitPrice.multiply(exitQty)
            }
        }

        val normalizedEntryQty = entryQty.max(BigDecimal.ZERO)
        val normalizedExitQty = exitQty.max(BigDecimal.ZERO)
        val effectiveClosedQty = normalizedEntryQty.min(normalizedExitQty)
        if (effectiveClosedQty <= BigDecimal.ZERO || entryValue <= BigDecimal.ZERO || exitValue <= BigDecimal.ZERO) {
            return ClosedTradeRecalculation(
                canApply = false,
                confidence = GuidedTradeEntity.PNL_CONFIDENCE_LOW,
                averageEntryPrice = trade.averageEntryPrice,
                entryQuantity = trade.entryQuantity,
                cumulativeExitQuantity = trade.cumulativeExitQuantity,
                averageExitPrice = trade.averageExitPrice,
                realizedPnl = trade.realizedPnl.setScale(2, RoundingMode.HALF_UP),
                realizedPnlPercent = trade.realizedPnlPercent.setScale(4, RoundingMode.HALF_UP),
                reason = "체결 데이터 부족"
            )
        }

        val averageEntryPrice = entryValue.divide(normalizedEntryQty, 8, RoundingMode.HALF_UP)
        val averageExitPrice = exitValue.divide(normalizedExitQty, 8, RoundingMode.HALF_UP)
        val invested = averageEntryPrice.multiply(effectiveClosedQty)
        val realizedPnl = averageExitPrice.subtract(averageEntryPrice)
            .multiply(effectiveClosedQty)
            .setScale(2, RoundingMode.HALF_UP)
        val realizedPnlPercent = if (invested > BigDecimal.ZERO) {
            realizedPnl.divide(invested, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).setScale(4, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
        }

        val sourceReason = if (confidence == GuidedTradeEntity.PNL_CONFIDENCE_HIGH) {
            "events 기반"
        } else {
            "events 부족 - fallback 사용"
        }

        return ClosedTradeRecalculation(
            canApply = true,
            confidence = confidence,
            averageEntryPrice = averageEntryPrice,
            entryQuantity = normalizedEntryQty,
            cumulativeExitQuantity = effectiveClosedQty,
            averageExitPrice = averageExitPrice,
            realizedPnl = realizedPnl,
            realizedPnlPercent = realizedPnlPercent,
            reason = sourceReason
        )
    }

    private fun isEntryFillEvent(eventType: String): Boolean {
        return eventType == "ENTRY_FILLED" || eventType == "DCA_BUY"
    }

    private fun isExitFillEvent(eventType: String): Boolean {
        return eventType == "HALF_TAKE_PROFIT" ||
            eventType == "PARTIAL_CLOSE" ||
            eventType == "CLOSE_ALL"
    }

    private fun calibratePredictedWinRate(market: String, baseWinRate: Double): WinRateCalibration {
        val marketSamples = guidedTradeRepository
            .findTop80ByMarketAndStatusOrderByCreatedAtDesc(market, GuidedTradeEntity.STATUS_CLOSED)
        val globalSamples = guidedTradeRepository
            .findTop200ByStatusOrderByCreatedAtDesc(GuidedTradeEntity.STATUS_CLOSED)

        val samples = when {
            marketSamples.size >= 12 -> marketSamples
            globalSamples.isNotEmpty() -> globalSamples
            else -> marketSamples
        }

        if (samples.size < 8) {
            return WinRateCalibration(baseWinRate.coerceIn(30.0, 86.0), null)
        }

        val winRate = samples.count {
            it.realizedPnl > BigDecimal.ZERO || it.realizedPnlPercent > BigDecimal.ZERO
        }.toDouble() / samples.size.toDouble() * 100.0
        val avgPnlPercent = samples.map { it.realizedPnlPercent.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0

        val sampleWeight = (samples.size / 40.0).coerceIn(0.25, 1.0)
        val adjustByWinRate = ((winRate - 50.0) * 0.35).coerceIn(-11.0, 11.0)
        val adjustByPnl = (avgPnlPercent * 0.9).coerceIn(-8.0, 8.0)
        val totalAdjust = ((adjustByWinRate + adjustByPnl) * sampleWeight).coerceIn(-9.0, 9.0)

        val calibrated = (baseWinRate + totalAdjust).coerceIn(30.0, 86.0)
        val note = "최근 실거래 보정: ${if (totalAdjust >= 0) "+" else ""}${String.format("%.1f", totalAdjust)}%p (표본 ${samples.size}건)"
        return WinRateCalibration(calibrated, note)
    }

    private fun GuidedMarketSortBy.isWinRateSort(): Boolean {
        return this == GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE ||
            this == GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE
    }

    private data class MarketWinRate(
        val recommendedEntryWinRate: Double,
        val marketEntryWinRate: Double
    )

    private fun fetchMarketWinRate(market: String, interval: String, mode: TradingMode): MarketWinRate? {
        return runCatching {
            val recommendation = getRecommendation(market, interval, WIN_RATE_CANDLE_COUNT, mode)
            val recommendedEntryWinRate = recommendation.recommendedEntryWinRate.takeIf { it.isFinite() } ?: return@runCatching null
            val marketEntryWinRate = recommendation.marketEntryWinRate.takeIf { it.isFinite() } ?: return@runCatching null
            MarketWinRate(
                recommendedEntryWinRate = recommendedEntryWinRate,
                marketEntryWinRate = marketEntryWinRate
            )
        }.onFailure { ex ->
            log.warn(
                "마켓 승률 계산 실패: market={}, interval={}, mode={}, error={}",
                market,
                interval,
                mode.name,
                ex.message
            )
        }.getOrNull()
    }

    private fun enrichMarketBoardWithWinRates(
        marketBoard: List<GuidedMarketItem>,
        interval: String,
        mode: TradingMode
    ): List<GuidedMarketItem> {
        val targets = marketBoard
            .sortedByDescending { it.accTradePrice }
            .take(WIN_RATE_SAMPLE_MARKET_LIMIT)
            .map { it.market }

        if (targets.isEmpty()) return marketBoard

        val futures = targets.associateWith { market ->
            CompletableFuture.supplyAsync(
                { fetchMarketWinRate(market, interval, mode) },
                marketWinRateExecutor
            )
        }

        val winRateMap = futures.mapValues { (market, future) ->
            try {
                future.get(WIN_RATE_CALC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                log.warn(
                    "마켓 승률 계산 타임아웃: market={}, interval={}, mode={}",
                    market,
                    interval,
                    mode.name
                )
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn(
                    "마켓 승률 계산 인터럽트: market={}, interval={}, mode={}",
                    market,
                    interval,
                    mode.name
                )
                null
            } catch (e: Exception) {
                log.warn(
                    "마켓 승률 계산 실패: market={}, interval={}, mode={}, error={}",
                    market,
                    interval,
                    mode.name,
                    e.message
                )
                null
            }
        }

        return marketBoard.map { market ->
            val winRate = winRateMap[market.market]
            market.copy(
                recommendedEntryWinRate = winRate?.recommendedEntryWinRate,
                marketEntryWinRate = winRate?.marketEntryWinRate
            )
        }
    }

    private fun buildMarketComparator(
        sortBy: GuidedMarketSortBy,
        sortDirection: GuidedSortDirection
    ): Comparator<GuidedMarketItem> {
        if (sortBy.isWinRateSort()) {
            return buildWinRateComparator(sortBy, sortDirection)
        }

        val metricComparator = when (sortBy) {
            GuidedMarketSortBy.TURNOVER -> compareBy<GuidedMarketItem> { it.accTradePrice }
            GuidedMarketSortBy.CHANGE_RATE -> compareBy { it.changeRate }
            GuidedMarketSortBy.VOLUME -> compareBy { it.accTradeVolume }
            GuidedMarketSortBy.SURGE_RATE -> compareBy { it.surgeRate }
            GuidedMarketSortBy.MARKET_CAP_FLOW -> compareBy { it.marketCapFlowScore }
            GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE -> compareBy { it.recommendedEntryWinRate ?: Double.NEGATIVE_INFINITY }
            GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE -> compareBy { it.marketEntryWinRate ?: Double.NEGATIVE_INFINITY }
        }
        return if (sortDirection == GuidedSortDirection.DESC) {
            metricComparator.reversed().thenBy { it.market }
        } else {
            metricComparator.thenBy { it.market }
        }
    }

    private fun buildWinRateComparator(
        sortBy: GuidedMarketSortBy,
        sortDirection: GuidedSortDirection
    ): Comparator<GuidedMarketItem> {
        val metricSelector: (GuidedMarketItem) -> Double? = when (sortBy) {
            GuidedMarketSortBy.RECOMMENDED_ENTRY_WIN_RATE -> { item -> item.recommendedEntryWinRate }
            GuidedMarketSortBy.MARKET_ENTRY_WIN_RATE -> { item -> item.marketEntryWinRate }
            else -> { _ -> null }
        }

        return Comparator { left, right ->
            val leftValue = metricSelector(left)
            val rightValue = metricSelector(right)

            when {
                leftValue == null && rightValue == null -> compareByTurnoverThenMarket(left, right)
                leftValue == null -> 1
                rightValue == null -> -1
                else -> {
                    val metricCompare = if (sortDirection == GuidedSortDirection.DESC) {
                        rightValue.compareTo(leftValue)
                    } else {
                        leftValue.compareTo(rightValue)
                    }
                    if (metricCompare != 0) {
                        metricCompare
                    } else {
                        compareByTurnoverThenMarket(left, right)
                    }
                }
            }
        }
    }

    private fun compareByTurnoverThenMarket(left: GuidedMarketItem, right: GuidedMarketItem): Int {
        val turnoverCompare = right.accTradePrice.compareTo(left.accTradePrice)
        if (turnoverCompare != 0) return turnoverCompare
        return left.market.compareTo(right.market)
    }

    private fun calculateSurgeRate(
        openingPrice: Double?,
        highPrice: Double?,
        tradePrice: Double
    ): Double {
        if (openingPrice == null || openingPrice <= 0.0) return 0.0
        val peak = max(highPrice ?: tradePrice, tradePrice)
        return ((peak - openingPrice) / openingPrice * 100.0).coerceIn(-99.0, 500.0)
    }

    private fun buildTickCandles(market: String, count: Int): List<GuidedCandle> {
        val ticks = bithumbPublicApi.getTradesTicks(market, count)
            ?.sortedBy { it.timestamp }
            ?: return emptyList()

        if (ticks.isEmpty()) return emptyList()

        val grouped = ticks.groupBy { it.timestamp / 1000L }
        return grouped.entries.sortedBy { it.key }.map { (sec, secTicks) ->
            secTicks.toGuidedCandle(sec)
        }
    }

    private fun List<TradeResponse>.toGuidedCandle(secondTs: Long): GuidedCandle {
        val open = first().tradePrice.toDouble()
        val close = last().tradePrice.toDouble()
        val high = maxOf { it.tradePrice.toDouble() }
        val low = minOf { it.tradePrice.toDouble() }
        val volume = sumOf { it.tradeVolume.toDouble() }
        return GuidedCandle(
            timestamp = secondTs,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    private fun calcAtr(candles: List<GuidedCandle>, period: Int): Double {
        if (candles.size < 2) return 0.0
        val trValues = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prevClose = candles[i - 1].close
            val high = cur.high
            val low = cur.low
            val tr = max(
                high - low,
                max(kotlin.math.abs(high - prevClose), kotlin.math.abs(low - prevClose))
            )
            trValues.add(tr)
        }
        return trValues.takeLast(period.coerceAtMost(trValues.size)).average()
    }

    private fun fetchTickerMap(markets: List<String>): Map<String, com.ant.cointrading.api.bithumb.TickerInfo> {
        if (markets.isEmpty()) return emptyMap()
        return markets.chunked(60).flatMap { chunk ->
            bithumbPublicApi.getCurrentPrice(chunk.joinToString(",")) ?: emptyList()
        }.associateBy { it.market }
    }

    private fun calculatePnlPercent(entry: BigDecimal, current: BigDecimal): Double {
        if (entry <= BigDecimal.ZERO) return 0.0
        return current.subtract(entry)
            .divide(entry, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()
    }

    private fun formatPrice(price: Double): String = String.format("%.0f", price)

    private fun normalizeLimitPrice(market: String, requestedPrice: BigDecimal): BigDecimal {
        val orderbook = bithumbPublicApi.getOrderbook(market)?.firstOrNull()
        val units = orderbook?.orderbookUnits.orEmpty()

        val step = units
            .map { it.askPrice }
            .zipWithNext { a, b -> a.subtract(b).abs() }
            .filter { it > BigDecimal.ZERO }
            .minOrNull()

        if (step != null && step > BigDecimal.ZERO) {
            val steps = requestedPrice.divide(step, 0, RoundingMode.HALF_UP)
            return step.multiply(steps).stripTrailingZeros()
        }

        val referencePrice = units.firstOrNull()?.askPrice ?: requestedPrice
        val scale = referencePrice.stripTrailingZeros().scale().coerceIn(0, 10)
        return requestedPrice.setScale(scale, RoundingMode.HALF_UP)
    }

    private fun resolveEntryPrice(
        order: OrderResponse,
        orderType: String,
        investedAmount: BigDecimal,
        selectedLimitPrice: BigDecimal?,
        fallbackPrice: BigDecimal
    ): BigDecimal {
        if (orderType == GuidedTradeEntity.ORDER_TYPE_LIMIT) {
            return when {
                order.price != null && order.price > BigDecimal.ZERO -> order.price
                selectedLimitPrice != null && selectedLimitPrice > BigDecimal.ZERO -> selectedLimitPrice
                else -> fallbackPrice
            }
        }

        val executedQty = order.executedVolume ?: BigDecimal.ZERO
        if (executedQty > BigDecimal.ZERO) {
            val invested = when {
                investedAmount > BigDecimal.ZERO -> investedAmount
                order.price != null && order.price > BigDecimal.ZERO -> order.price.multiply(executedQty)
                else -> BigDecimal.ZERO
            }
            if (invested > BigDecimal.ZERO) {
                return invested.divide(executedQty, 8, RoundingMode.HALF_UP)
            }
        }

        return when {
            order.price != null && order.price > BigDecimal.ZERO -> order.price
            selectedLimitPrice != null && selectedLimitPrice > BigDecimal.ZERO -> selectedLimitPrice
            else -> fallbackPrice
        }
    }

    private fun buildOrderErrorMessage(e: BithumbApiException): String {
        return when {
            e.isInsufficientFunds() -> "주문 실패: 잔고가 부족합니다. 주문금액을 낮추거나 잔고를 확인하세요."
            e.isInvalidOrder() -> "주문 실패: 가격/수량 형식이 유효하지 않습니다. 지정가 소수점 또는 최소 주문금액을 확인하세요."
            e.isMarketUnavailable() -> "주문 실패: 현재 해당 마켓은 거래가 불가능합니다."
            else -> e.errorMessage ?: "주문 실패: 거래소 응답 오류"
        }
    }

    private fun resolveCurrentOrder(position: GuidedTradeEntity?): GuidedOrderView? {
        val orderId = when {
            position?.lastExitOrderId?.isNotBlank() == true -> position.lastExitOrderId
            position?.entryOrderId?.isNotBlank() == true -> position.entryOrderId
            else -> null
        } ?: return null

        return bithumbPrivateApi.getOrder(orderId)?.toOrderView()
    }

    private fun BigDecimal.max(other: BigDecimal): BigDecimal {
        return if (this > other) this else other
    }

    private fun BigDecimal.min(other: BigDecimal): BigDecimal {
        return if (this < other) this else other
    }

    private fun GuidedTradeEntity.toView(currentPrice: Double?): GuidedTradeView {
        val nowPrice = currentPrice ?: this.averageEntryPrice.toDouble()
        val pnlPercent = if (averageEntryPrice > BigDecimal.ZERO) {
            BigDecimal.valueOf(nowPrice)
                .subtract(averageEntryPrice)
                .divide(averageEntryPrice, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()
        } else 0.0

        return GuidedTradeView(
            tradeId = id ?: 0,
            market = market,
            status = status,
            entryOrderType = entryOrderType,
            entryOrderId = entryOrderId,
            averageEntryPrice = averageEntryPrice.toDouble(),
            currentPrice = nowPrice,
            entryQuantity = entryQuantity.toDouble(),
            remainingQuantity = remainingQuantity.toDouble(),
            stopLossPrice = stopLossPrice.toDouble(),
            takeProfitPrice = takeProfitPrice.toDouble(),
            trailingActive = trailingActive,
            trailingPeakPrice = trailingPeakPrice?.toDouble(),
            trailingStopPrice = trailingStopPrice?.toDouble(),
            dcaCount = dcaCount,
            maxDcaCount = maxDcaCount,
            halfTakeProfitDone = halfTakeProfitDone,
            unrealizedPnlPercent = pnlPercent,
            realizedPnl = realizedPnl.toDouble(),
            realizedPnlPercent = realizedPnlPercent.toDouble(),
            lastAction = lastAction,
            recommendationReason = recommendationReason,
            entrySource = entrySource,
            strategyCode = strategyCode,
            createdAt = createdAt,
            updatedAt = updatedAt,
            closedAt = closedAt,
            exitReason = exitReason
        )
    }

    private fun com.ant.cointrading.api.bithumb.OrderbookInfo.toView(): GuidedOrderbookView {
        val units = this.orderbookUnits.orEmpty().take(10).map {
            GuidedOrderbookUnitView(
                askPrice = it.askPrice.toDouble(),
                askSize = it.askSize.toDouble(),
                bidPrice = it.bidPrice.toDouble(),
                bidSize = it.bidSize.toDouble()
            )
        }
        val best = units.firstOrNull()
        val spread = if (best != null) (best.askPrice - best.bidPrice) else null
        val spreadPercent = if (best != null && best.askPrice > 0.0) {
            (spread ?: 0.0) / best.askPrice * 100.0
        } else {
            null
        }
        return GuidedOrderbookView(
            market = this.market,
            timestamp = this.timestamp,
            bestAsk = best?.askPrice,
            bestBid = best?.bidPrice,
            spread = spread,
            spreadPercent = spreadPercent,
            totalAskSize = this.totalAskSize?.toDouble(),
            totalBidSize = this.totalBidSize?.toDouble(),
            units = units
        )
    }

    private fun OrderResponse.toOrderView(): GuidedOrderView {
        return GuidedOrderView(
            uuid = uuid,
            side = side,
            ordType = ordType,
            state = state,
            market = market,
            price = price?.toDouble(),
            volume = volume?.toDouble(),
            remainingVolume = remainingVolume?.toDouble(),
            executedVolume = executedVolume?.toDouble(),
            createdAt = createdAt
        )
    }
}

data class GuidedMarketItem(
    val market: String,
    val symbol: String,
    val koreanName: String,
    val englishName: String?,
    val tradePrice: Double,
    val changeRate: Double,
    val changePrice: Double,
    val accTradePrice: Double,
    val accTradeVolume: Double,
    val surgeRate: Double,
    val recommendedEntryWinRate: Double? = null,
    val marketEntryWinRate: Double? = null
) {
    val marketCapFlowScore: Double
        get() {
            val liquidityFactor = ln(max(accTradePrice, 1.0))
            return abs(changeRate) * liquidityFactor
        }
}

enum class TradingMode(
    val slPercent: Double,
    val tpCapPercent: Double,
    val rrRatio: Double,
    val atrMultiplier: Double
) {
    SCALP(0.003, 0.008, 1.5, 0.5),
    SWING(0.007, 0.02, 1.5, 1.0),
    POSITION(0.02, 0.05, 2.0, 2.0);

    companion object {
        fun fromString(value: String): TradingMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SWING
    }
}

enum class GuidedMarketSortBy {
    TURNOVER,
    CHANGE_RATE,
    VOLUME,
    SURGE_RATE,
    MARKET_CAP_FLOW,
    RECOMMENDED_ENTRY_WIN_RATE,
    MARKET_ENTRY_WIN_RATE
}

enum class GuidedSortDirection {
    ASC,
    DESC
}

enum class GuidedWinRateThresholdMode {
    DYNAMIC_P70,
    FIXED;

    companion object {
        fun fromString(value: String?): GuidedWinRateThresholdMode {
            val normalized = value?.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: DYNAMIC_P70
        }
    }
}

data class GuidedCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class KeyLevel(
    val price: Double,
    val label: String,
    val type: String
)

data class GuidedRecommendation(
    val market: String,
    val currentPrice: Double,
    val recommendedEntryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val confidence: Double,
    val predictedWinRate: Double,
    val recommendedEntryWinRate: Double,
    val marketEntryWinRate: Double,
    val riskRewardRatio: Double,
    val winRateBreakdown: GuidedWinRateBreakdown,
    val suggestedOrderType: String,
    val rationale: List<String>,
    val keyLevels: List<KeyLevel> = emptyList()
)

data class GuidedWinRateBreakdown(
    val trend: Double,
    val pullback: Double,
    val volatility: Double,
    val riskReward: Double
)

private data class WinRateCalibration(
    val calibrated: Double,
    val note: String?
)

private data class ClosedTradeRecalculation(
    val canApply: Boolean,
    val confidence: String,
    val averageEntryPrice: BigDecimal,
    val entryQuantity: BigDecimal,
    val cumulativeExitQuantity: BigDecimal,
    val averageExitPrice: BigDecimal,
    val realizedPnl: BigDecimal,
    val realizedPnlPercent: BigDecimal,
    val reason: String
)

data class GuidedTradeEventView(
    val id: Long,
    val tradeId: Long,
    val eventType: String,
    val price: Double?,
    val quantity: Double?,
    val message: String?,
    val createdAt: Instant
)

data class GuidedTradeView(
    val tradeId: Long,
    val market: String,
    val status: String,
    val entryOrderType: String,
    val entryOrderId: String?,
    val averageEntryPrice: Double,
    val currentPrice: Double,
    val entryQuantity: Double,
    val remainingQuantity: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val trailingActive: Boolean,
    val trailingPeakPrice: Double?,
    val trailingStopPrice: Double?,
    val dcaCount: Int,
    val maxDcaCount: Int,
    val halfTakeProfitDone: Boolean,
    val unrealizedPnlPercent: Double,
    val realizedPnl: Double,
    val realizedPnlPercent: Double,
    val lastAction: String?,
    val recommendationReason: String?,
    val entrySource: String?,
    val strategyCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant?,
    val exitReason: String?
)

data class GuidedChartResponse(
    val market: String,
    val interval: String,
    val candles: List<GuidedCandle>,
    val recommendation: GuidedRecommendation,
    val activePosition: GuidedTradeView?,
    val events: List<GuidedTradeEventView>,
    val orderbook: GuidedOrderbookView?,
    val orderSnapshot: GuidedOrderSnapshotView
)

data class GuidedAgentContextResponse(
    val market: String,
    val generatedAt: Instant,
    val chart: GuidedChartResponse,
    val recentClosedTrades: List<GuidedClosedTradeView>,
    val performance: GuidedPerformanceSnapshot,
    val featurePack: GuidedAgentFeaturePack? = null
)

data class GuidedAgentFeaturePack(
    val generatedAt: Instant,
    val technical: GuidedTechnicalFeaturePack,
    val microstructure: GuidedMicrostructureFeaturePack,
    val executionRisk: GuidedExecutionRiskFeaturePack
)

data class GuidedTechnicalFeaturePack(
    val trendScore: Double,
    val pullbackScore: Double,
    val volatilityScore: Double,
    val riskRewardScore: Double,
    val riskRewardRatio: Double,
    val recommendedEntryWinRate: Double,
    val marketEntryWinRate: Double,
    val atrPercent: Double,
    val momentum6: Double,
    val momentum24: Double,
    val entryGapPct: Double
)

data class GuidedMicrostructureFeaturePack(
    val spreadPercent: Double,
    val totalBidSize: Double,
    val totalAskSize: Double,
    val bidAskImbalance: Double,
    val top5BidAskImbalance: Double,
    val orderbookTimestamp: Long?
)

data class GuidedExecutionRiskFeaturePack(
    val suggestedOrderType: String,
    val chasingRiskScore: Double,
    val pendingFillRiskScore: Double,
    val stopDistancePercent: Double,
    val takeProfitDistancePercent: Double,
    val confidence: Double
)

data class GuidedAutopilotLiveResponse(
    val orderSummary: OrderLifecycleSummary,
    val orderEvents: List<OrderLifecycleEvent>,
    val autopilotEvents: List<GuidedAutopilotEvent>,
    val candidates: List<GuidedAutopilotCandidateView>,
    val thresholdMode: String,
    val appliedRecommendedWinRateThreshold: Double,
    val requestedMinMarketWinRate: Double? = null,
    val requestedMinRecommendedWinRate: Double?,
    val decisionStats: GuidedAutopilotDecisionStats,
    val strategyCodeSummary: Map<String, GuidedStrategyCodeSummary>,
)

data class GuidedAutopilotDecisionStats(
    val rulePass: Int,
    val ruleFail: Int,
    val llmReject: Int,
    val entered: Int,
    val pendingTimeout: Int,
)

data class GuidedStrategyCodeSummary(
    val buyRequested: Int,
    val buyFilled: Int,
    val sellRequested: Int,
    val sellFilled: Int,
    val failed: Int,
    val cancelled: Int,
)

data class GuidedAutopilotEvent(
    val market: String,
    val eventType: String,
    val level: String,
    val message: String,
    val createdAt: Instant
)

data class GuidedAutopilotCandidateView(
    val market: String,
    val koreanName: String,
    val recommendedEntryWinRate: Double?,
    val marketEntryWinRate: Double?,
    val stage: String,
    val reason: String
)

data class GuidedAutopilotOpportunitiesResponse(
    val generatedAt: Instant,
    val primaryInterval: String,
    val confirmInterval: String,
    val mode: String,
    val appliedUniverseLimit: Int,
    val opportunities: List<GuidedAutopilotOpportunityView>
)

data class GuidedAutopilotOpportunityView(
    val market: String,
    val koreanName: String,
    val recommendedEntryWinRate1m: Double?,
    val recommendedEntryWinRate10m: Double?,
    val marketEntryWinRate1m: Double?,
    val marketEntryWinRate10m: Double?,
    val riskReward1m: Double,
    val entryGapPct1m: Double,
    val expectancyPct: Double,
    val score: Double,
    val stage: String,
    val reason: String
)

data class GuidedAutopilotDecisionLogRequest(
    val runId: String,
    val market: String,
    val interval: String = "minute30",
    val mode: String = "SWING",
    val stage: String,
    val approve: Boolean,
    val confidence: Double? = null,
    val score: Double? = null,
    val reason: String? = null,
    val executed: Boolean = false,
    val entryAmountKrw: Double? = null,
    val featurePack: Map<String, Any?>? = null,
    val specialistOutputs: List<Map<String, Any?>> = emptyList(),
    val synthOutput: Map<String, Any?>? = null,
    val pmOutput: Map<String, Any?>? = null,
    val orderPlan: Map<String, Any?>? = null,
)

data class GuidedAutopilotDecisionLogResponse(
    val accepted: Boolean,
    val decisionId: Long,
    val createdAt: Instant
)

data class GuidedAutopilotPerformanceResponse(
    val windowDays: Int,
    val from: Instant,
    val to: Instant,
    val trades: Int,
    val winRate: Double,
    val netPnlKrw: Double,
    val netReturnPercent: Double,
    val sharpe: Double,
    val maxDrawdownPercent: Double,
    val gateEligible: Boolean,
    val gateReason: String
)

data class GuidedClosedTradeView(
    val tradeId: Long,
    val market: String,
    val averageEntryPrice: Double,
    val averageExitPrice: Double,
    val entryQuantity: Double,
    val realizedPnl: Double,
    val realizedPnlPercent: Double,
    val dcaCount: Int,
    val halfTakeProfitDone: Boolean,
    val createdAt: Instant,
    val closedAt: Instant?,
    val exitReason: String?
)

data class GuidedPerformanceSnapshot(
    val sampleSize: Int,
    val winCount: Int,
    val lossCount: Int,
    val winRate: Double,
    val avgPnlPercent: Double
)

data class GuidedOrderbookView(
    val market: String,
    val timestamp: Long?,
    val bestAsk: Double?,
    val bestBid: Double?,
    val spread: Double?,
    val spreadPercent: Double?,
    val totalAskSize: Double?,
    val totalBidSize: Double?,
    val units: List<GuidedOrderbookUnitView>
)

data class GuidedOrderbookUnitView(
    val askPrice: Double,
    val askSize: Double,
    val bidPrice: Double,
    val bidSize: Double
)

data class GuidedOrderSnapshotView(
    val currentOrder: GuidedOrderView?,
    val pendingOrders: List<GuidedOrderView>,
    val completedOrders: List<GuidedOrderView>
)

data class GuidedOrderView(
    val uuid: String,
    val side: String,
    val ordType: String,
    val state: String?,
    val market: String,
    val price: Double?,
    val volume: Double?,
    val remainingVolume: Double?,
    val executedVolume: Double?,
    val createdAt: String?
)

data class GuidedRealtimeTickerView(
    val market: String,
    val tradePrice: Double,
    val changeRate: Double?,
    val tradeVolume: Double?,
    val timestamp: Long?
)

data class GuidedDailyStats(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val totalPnlKrw: Double,
    val avgPnlPercent: Double,
    val winRate: Double,
    val openPositionCount: Int,
    val totalInvestedKrw: Double,
    val trades: List<GuidedClosedTradeView>
)

data class GuidedStartRequest(
    val market: String,
    val amountKrw: Long,
    val orderType: String? = null,
    val limitPrice: Double? = null,
    val stopLossPrice: Double? = null,
    val takeProfitPrice: Double? = null,
    val trailingTriggerPercent: Double? = null,
    val trailingOffsetPercent: Double? = null,
    val maxDcaCount: Int? = null,
    val dcaStepPercent: Double? = null,
    val halfTakeProfitRatio: Double? = null,
    val interval: String? = null,
    val mode: String? = null,
    val entrySource: String? = null,
    val strategyCode: String? = null,
)

data class GuidedAdoptPositionRequest(
    val market: String,
    val mode: String? = null,
    val interval: String? = null,
    val entrySource: String? = null,
    val notes: String? = null
)

data class GuidedAdoptPositionResponse(
    val positionId: Long,
    val adopted: Boolean,
    val avgEntryPrice: Double,
    val quantity: Double
)

data class GuidedSyncResult(
    val success: Boolean,
    val message: String,
    val actions: List<GuidedSyncAction>,
    val fixedCount: Int
)

data class GuidedSyncAction(
    val type: String,
    val market: String,
    val detail: String
)

data class GuidedPnlReconcileResult(
    val windowDays: Long,
    val dryRun: Boolean,
    val scannedTrades: Int,
    val updatedTrades: Int,
    val unchangedTrades: Int,
    val highConfidenceTrades: Int,
    val lowConfidenceTrades: Int,
    val sample: List<GuidedPnlReconcileItem>
)

data class GuidedPnlReconcileItem(
    val tradeId: Long,
    val market: String,
    val confidence: String,
    val reason: String,
    val recalculated: Boolean,
    val realizedPnl: Double,
    val realizedPnlPercent: Double
)

private fun GuidedTradeEntity.toClosedTradeView(): GuidedClosedTradeView {
    return GuidedClosedTradeView(
        tradeId = id ?: 0L,
        market = market,
        averageEntryPrice = averageEntryPrice.toDouble(),
        averageExitPrice = averageExitPrice.toDouble(),
        entryQuantity = entryQuantity.toDouble(),
        realizedPnl = realizedPnl.toDouble(),
        realizedPnlPercent = realizedPnlPercent.toDouble(),
        dcaCount = dcaCount,
        halfTakeProfitDone = halfTakeProfitDone,
        createdAt = createdAt,
        closedAt = closedAt,
        exitReason = exitReason
    )
}
