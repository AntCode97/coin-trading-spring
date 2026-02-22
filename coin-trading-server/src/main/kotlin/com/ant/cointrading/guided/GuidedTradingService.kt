package com.ant.cointrading.guided

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.BithumbApiException
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.api.bithumb.TradeResponse
import com.ant.cointrading.repository.GuidedTradeEntity
import com.ant.cointrading.repository.GuidedTradeEventEntity
import com.ant.cointrading.repository.GuidedTradeEventRepository
import com.ant.cointrading.repository.GuidedTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

@Service
class GuidedTradingService(
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val guidedTradeRepository: GuidedTradeRepository,
    private val guidedTradeEventRepository: GuidedTradeEventRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val OPEN_STATUSES = listOf(GuidedTradeEntity.STATUS_PENDING_ENTRY, GuidedTradeEntity.STATUS_OPEN)
        val D = BigDecimal.ZERO
        const val DCA_MIN_INTERVAL_SECONDS = 600L
    }

    fun getMarketBoard(
        sortBy: GuidedMarketSortBy = GuidedMarketSortBy.TURNOVER,
        sortDirection: GuidedSortDirection = GuidedSortDirection.DESC
    ): List<GuidedMarketItem> {
        val markets = bithumbPublicApi.getMarketAll()
            ?.filter { it.market.startsWith("KRW-") }
            ?: emptyList()

        if (markets.isEmpty()) return emptyList()

        val tickerMap = fetchTickerMap(markets.map { it.market })

        return markets.mapNotNull { market ->
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
        }.sortedWith(buildMarketComparator(sortBy, sortDirection))
    }

    fun getChartData(market: String, interval: String, count: Int): GuidedChartResponse {
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

        val recommendation = buildRecommendation(market, candles)
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

    fun getRecommendation(market: String, interval: String = "minute30", count: Int = 120): GuidedRecommendation {
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
        return buildRecommendation(market, candles)
    }

    fun getAgentContext(
        market: String,
        interval: String = "minute30",
        count: Int = 120,
        closedTradeLimit: Int = 20
    ): GuidedAgentContextResponse {
        val normalizedMarket = market.trim().uppercase()
        val chart = getChartData(normalizedMarket, interval, count)
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
            )
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
        val current = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
        return trade.toView(current)
    }

    fun getAllOpenPositions(): List<GuidedTradeView> {
        val trades = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        if (trades.isEmpty()) return emptyList()
        val markets = trades.map { it.market }.distinct()
        val tickerMap = fetchTickerMap(markets)
        return trades.map { trade ->
            val price = tickerMap[trade.market]?.tradePrice?.toDouble()
            trade.toView(price)
        }
    }

    @Transactional
    fun startAutoTrading(request: GuidedStartRequest): GuidedTradeView {
        val market = request.market.trim().uppercase()
        require(market.startsWith("KRW-")) { "market 형식 오류: KRW-BTC 형태를 사용하세요." }

        val existing = getActiveTrade(market)
        require(existing == null) { "이미 진행 중인 포지션이 있습니다." }

        val recommendation = getRecommendation(market)

        val orderType = (request.orderType ?: recommendation.suggestedOrderType).trim().uppercase()
        val amountKrw = BigDecimal.valueOf(request.amountKrw.coerceAtLeast(5100))
        val requestedLimitPrice = request.limitPrice?.let { BigDecimal.valueOf(it) }
        var selectedLimitPrice: BigDecimal? = null
        val trailingTriggerPercent = (request.trailingTriggerPercent ?: 2.0).coerceIn(0.5, 20.0)
        val trailingOffsetPercent = (request.trailingOffsetPercent ?: 1.0).coerceIn(0.2, 10.0)
        val dcaStepPercent = (request.dcaStepPercent ?: 2.0).coerceIn(0.5, 15.0)
        val maxDcaCount = (request.maxDcaCount ?: 2).coerceIn(0, 3)
        val halfTakeProfitRatio = (request.halfTakeProfitRatio ?: 0.5).coerceIn(0.2, 0.8)

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
            throw IllegalArgumentException(buildOrderErrorMessage(e))
        }

        val order = bithumbPrivateApi.getOrder(submitted.uuid) ?: submitted
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

        val actualQty = getActualHoldingQuantity(market)
        val currentPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice ?: allTrades.first().averageEntryPrice

        if (actualQty > BigDecimal.ZERO) {
            val estimatedValue = actualQty.multiply(currentPrice)
            if (estimatedValue >= BigDecimal("5000")) {
                try {
                    val sell = bithumbPrivateApi.sellMarketOrder(market, actualQty)
                    Thread.sleep(300)
                    val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
                    val execPrice = sellInfo.price ?: currentPrice
                    val executedQty = sellInfo.executedVolume ?: actualQty

                    for (trade in allTrades) {
                        val tradeShare = if (executedQty > BigDecimal.ZERO && trade.remainingQuantity > BigDecimal.ZERO) {
                            trade.remainingQuantity.min(executedQty)
                        } else {
                            trade.remainingQuantity
                        }
                        if (tradeShare > BigDecimal.ZERO) {
                            applyExitFill(trade, tradeShare, execPrice, "MANUAL_STOP")
                        }
                    }
                } catch (e: Exception) {
                    log.error("[$market] 전량 매도 실패: ${e.message}", e)
                    throw IllegalStateException("[$market] 매도 주문 실패 — 빗썸에서 직접 매도하세요. 원인: ${e.message}")
                }
            } else {
                log.warn("[$market] 잔여 수량 매도 불가 (추정금액 ${estimatedValue.toPlainString()}원 < 5,000원). DB 강제 종료.")
            }
        }

        for (trade in allTrades) {
            trade.remainingQuantity = BigDecimal.ZERO
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "MANUAL_STOP"
            trade.lastAction = "CLOSED_MANUAL_STOP"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_ALL", currentPrice, null, "수동 전체 청산")
        }

        val primary = allTrades.first()
        return primary.toView(currentPrice.toDouble())
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
        require(estimatedValue >= BigDecimal("5000")) {
            "부분 익절 금액 ${estimatedValue.toPlainString()}원이 빗썸 최소 주문금액(5,000원) 미달입니다. 전체 청산을 이용하세요."
        }

        val sell = bithumbPrivateApi.sellMarketOrder(trade.market, qty)
        val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
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
        val order = bithumbPrivateApi.getOrder(orderId) ?: return
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

        val ageSec = Instant.now().epochSecond - trade.createdAt.epochSecond
        if (ageSec > 900) {
            bithumbPrivateApi.cancelOrder(orderId)
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

        val actualHolding = getActualHoldingQuantity(trade.market)
        if (actualHolding <= BigDecimal.ZERO && trade.remainingQuantity > BigDecimal.ZERO) {
            trade.remainingQuantity = BigDecimal.ZERO
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "MANUAL_INTERVENTION"
            trade.lastAction = "AUTO_CLOSED_MANUAL_INTERVENTION"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "MANUAL_INTERVENTION_CLOSE", currentPrice, null, "수동 매도 감지로 자동매매 종료")
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
        val orderInfo = bithumbPrivateApi.getOrder(order.uuid) ?: order
        val executedQty = (orderInfo.executedVolume ?: qty).min(trade.remainingQuantity)
        if (executedQty <= BigDecimal.ZERO) {
            trade.lastAction = "HALF_TAKE_PROFIT_NO_FILL"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "HALF_TP_NO_FILL", currentPrice, BigDecimal.ZERO, "절반 익절 주문 미체결")
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
        val orderInfo = bithumbPrivateApi.getOrder(order.uuid) ?: order
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

    private fun getActualHoldingQuantity(market: String): BigDecimal {
        val currency = market.substringAfter("KRW-")
        val balance = bithumbPrivateApi.getBalances()
            ?.firstOrNull { it.currency.equals(currency, ignoreCase = true) }
            ?: return BigDecimal.ZERO
        return balance.balance.add(balance.locked ?: BigDecimal.ZERO)
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
        val actualQty = getActualHoldingQuantity(trade.market)
        val sellQty = if (actualQty > BigDecimal.ZERO) actualQty else trade.remainingQuantity

        val estimatedValue = sellQty.multiply(currentPrice)
        if (estimatedValue < BigDecimal("5000")) {
            log.warn("[${trade.market}] 잔여 수량 ${sellQty} 매도 불가 (추정금액 ${estimatedValue.toPlainString()}원 < 5,000원). DB 강제 종료.")
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = "${reason}_BELOW_MIN_ORDER"
            trade.lastAction = "CLOSE_BELOW_MIN_ORDER"
            trade.remainingQuantity = BigDecimal.ZERO
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_BELOW_MIN_ORDER", currentPrice, sellQty, "최소 주문금액 미달로 DB 종료 (${estimatedValue.toPlainString()}원)")
            return
        }

        val sell = try {
            bithumbPrivateApi.sellMarketOrder(trade.market, sellQty)
        } catch (e: Exception) {
            log.error("[${trade.market}] 매도 주문 실패: ${e.message}", e)
            trade.lastAction = "SELL_FAILED: ${e.message?.take(80)}"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "SELL_FAILED", currentPrice, sellQty, "매도 실패: ${e.message?.take(120)}")
            throw IllegalStateException("[${trade.market}] 매도 주문 실패 — 빗썸에서 직접 매도하세요. 원인: ${e.message}")
        }

        Thread.sleep(300)
        val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
        val executedQty = (sellInfo.executedVolume ?: sellQty).min(sellQty)
        val execPrice = sellInfo.price ?: currentPrice

        if (executedQty <= BigDecimal.ZERO) {
            trade.lastExitOrderId = sell.uuid
            trade.lastAction = "CLOSE_NO_FILL_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_NO_FILL", execPrice, BigDecimal.ZERO, "청산 주문 미체결: $reason")
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

    private fun buildRecommendation(market: String, candles: List<GuidedCandle>): GuidedRecommendation {
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
        val stopLoss = recommended - max(recommended * 0.007, atr14 * 1.0)
        val rawTakeProfit = recommended + (recommended - stopLoss) * 1.5
        val takeProfit = minOf(rawTakeProfit, recommended * 1.02) // 익절 상한 2%
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

        val marketStopLoss = current - max(current * 0.007, atr14 * 1.0)
        val rawMarketTakeProfit = current + (current - marketStopLoss) * 1.5
        val marketTakeProfit = minOf(rawMarketTakeProfit, current * 1.02)
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
        val totalInvested = openPositions.sumOf {
            it.averageEntryPrice.multiply(it.remainingQuantity).toDouble()
        }
        return GuidedDailyStats(
            totalTrades = closedToday.size,
            wins = wins,
            losses = losses,
            totalPnlKrw = totalPnl,
            avgPnlPercent = avgPnlPercent,
            winRate = if (closedToday.isNotEmpty()) wins.toDouble() / closedToday.size * 100.0 else 0.0,
            openPositionCount = openPositions.size,
            totalInvestedKrw = totalInvested,
            trades = closedToday.map { it.toClosedTradeView() }
        )
    }

    @Transactional
    fun syncPositions(): GuidedSyncResult {
        val actions = mutableListOf<GuidedSyncAction>()
        val openTrades = guidedTradeRepository.findByStatusIn(OPEN_STATUSES)
        var fixedCount = 0

        for (trade in openTrades) {
            val actualQty = getActualHoldingQuantity(trade.market)

            when {
                actualQty < BigDecimal("0.0000001") -> {
                    trade.remainingQuantity = BigDecimal.ZERO
                    trade.status = GuidedTradeEntity.STATUS_CLOSED
                    trade.closedAt = Instant.now()
                    trade.exitReason = "SYNC_MANUAL_SELL"
                    trade.lastAction = "SYNC_CLOSED"
                    guidedTradeRepository.save(trade)
                    appendEvent(trade.id!!, "SYNC_CLOSE", null, null, "잔고 없음 - 외부 매도 감지")
                    actions.add(GuidedSyncAction("CLOSED", trade.market, "잔고 없음 - 외부 매도 감지 (trade #${trade.id})"))
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

    private fun buildMarketComparator(
        sortBy: GuidedMarketSortBy,
        sortDirection: GuidedSortDirection
    ): Comparator<GuidedMarketItem> {
        val metricComparator = when (sortBy) {
            GuidedMarketSortBy.TURNOVER -> compareBy<GuidedMarketItem> { it.accTradePrice }
            GuidedMarketSortBy.CHANGE_RATE -> compareBy { it.changeRate }
            GuidedMarketSortBy.VOLUME -> compareBy { it.accTradeVolume }
            GuidedMarketSortBy.SURGE_RATE -> compareBy { it.surgeRate }
            GuidedMarketSortBy.MARKET_CAP_FLOW -> compareBy { it.marketCapFlowScore }
        }
        return if (sortDirection == GuidedSortDirection.DESC) {
            metricComparator.reversed().thenBy { it.market }
        } else {
            metricComparator.thenBy { it.market }
        }
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
                order.locked != null && order.locked > BigDecimal.ZERO -> order.locked
                investedAmount > BigDecimal.ZERO -> investedAmount
                order.price != null && order.price > BigDecimal.ZERO -> order.price
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
    val surgeRate: Double
) {
    val marketCapFlowScore: Double
        get() {
            val liquidityFactor = ln(max(accTradePrice, 1.0))
            return abs(changeRate) * liquidityFactor
        }
}

enum class GuidedMarketSortBy {
    TURNOVER,
    CHANGE_RATE,
    VOLUME,
    SURGE_RATE,
    MARKET_CAP_FLOW
}

enum class GuidedSortDirection {
    ASC,
    DESC
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
    val performance: GuidedPerformanceSnapshot
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
    val halfTakeProfitRatio: Double? = null
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
