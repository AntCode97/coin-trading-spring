package com.ant.cointrading.guided

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
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
    }

    fun getMarketBoard(): List<GuidedMarketItem> {
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
                accTradePrice = ticker.accTradePrice?.toDouble() ?: 0.0
            )
        }.sortedByDescending { it.accTradePrice }
    }

    fun getChartData(market: String, interval: String, count: Int): GuidedChartResponse {
        val candles = bithumbPublicApi.getOhlcv(market, interval, count.coerceIn(30, 300), null)
            ?.sortedBy { it.timestamp }
            ?: emptyList()

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

        return GuidedChartResponse(
            market = market,
            interval = interval,
            candles = candles.map {
                GuidedCandle(
                    timestamp = it.timestamp,
                    open = it.openingPrice.toDouble(),
                    high = it.highPrice.toDouble(),
                    low = it.lowPrice.toDouble(),
                    close = it.tradePrice.toDouble(),
                    volume = it.candleAccTradeVolume.toDouble()
                )
            },
            recommendation = recommendation,
            activePosition = position?.toView(currentPrice = candles.lastOrNull()?.tradePrice?.toDouble()),
            events = events
        )
    }

    fun getRecommendation(market: String, interval: String = "minute30", count: Int = 120): GuidedRecommendation {
        val candles = bithumbPublicApi.getOhlcv(market, interval, count.coerceIn(30, 300), null)
            ?.sortedBy { it.timestamp }
            ?: emptyList()
        return buildRecommendation(market, candles)
    }

    fun getActivePosition(market: String): GuidedTradeView? {
        val trade = getActiveTrade(market) ?: return null
        val current = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
        return trade.toView(current)
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
        val limitPrice = request.limitPrice?.let { BigDecimal.valueOf(it) }

        val submitted = when (orderType) {
            GuidedTradeEntity.ORDER_TYPE_LIMIT -> {
                val selectedPrice = limitPrice ?: BigDecimal.valueOf(recommendation.recommendedEntryPrice)
                val quantity = amountKrw.divide(selectedPrice, 8, RoundingMode.DOWN)
                bithumbPrivateApi.buyLimitOrder(market, selectedPrice, quantity)
            }
            else -> {
                bithumbPrivateApi.buyMarketOrder(market, amountKrw)
            }
        }

        val order = bithumbPrivateApi.getOrder(submitted.uuid) ?: submitted
        val executedQuantity = order.executedVolume ?: BigDecimal.ZERO
        val tickerPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice
        val referencePrice = when {
            order.price != null && order.price > BigDecimal.ZERO -> order.price
            limitPrice != null -> limitPrice
            tickerPrice != null -> tickerPrice
            else -> BigDecimal.valueOf(recommendation.recommendedEntryPrice)
        }

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
            trailingTriggerPercent = BigDecimal.valueOf(request.trailingTriggerPercent ?: 2.0),
            trailingOffsetPercent = BigDecimal.valueOf(request.trailingOffsetPercent ?: 1.0),
            trailingActive = false,
            dcaStepPercent = BigDecimal.valueOf(request.dcaStepPercent ?: 2.0),
            maxDcaCount = request.maxDcaCount ?: 2,
            halfTakeProfitRatio = BigDecimal.valueOf(request.halfTakeProfitRatio ?: 0.5),
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
        val trade = getActiveTrade(market) ?: error("진행 중인 포지션이 없습니다.")
        closeAllByMarket(trade, "MANUAL_STOP")
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
            val filledPrice = order.price ?: trade.averageEntryPrice
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

        if (trade.dcaCount < trade.maxDcaCount) {
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
        val addAmount = trade.targetAmountKrw.multiply(BigDecimal("0.5")).setScale(0, RoundingMode.DOWN)
        if (addAmount < BigDecimal(5100)) return

        val order = bithumbPrivateApi.buyMarketOrder(trade.market, addAmount)
        val orderInfo = bithumbPrivateApi.getOrder(order.uuid) ?: order
        val executedQty = orderInfo.executedVolume ?: BigDecimal.ZERO
        if (executedQty <= BigDecimal.ZERO) return

        val execPrice = orderInfo.price ?: currentPrice
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

    private fun closeAllByMarket(trade: GuidedTradeEntity, reason: String) {
        if (trade.remainingQuantity <= BigDecimal.ZERO) {
            trade.status = GuidedTradeEntity.STATUS_CLOSED
            trade.closedAt = Instant.now()
            trade.exitReason = reason
            trade.lastAction = "CLOSE_SKIPPED_EMPTY"
            guidedTradeRepository.save(trade)
            return
        }

        val requestedQty = trade.remainingQuantity
        val sell = bithumbPrivateApi.sellMarketOrder(trade.market, requestedQty)
        val sellInfo = bithumbPrivateApi.getOrder(sell.uuid) ?: sell
        val executedQty = (sellInfo.executedVolume ?: requestedQty).min(requestedQty)
        val execPrice = sellInfo.price ?: bithumbPublicApi.getCurrentPrice(trade.market)?.firstOrNull()?.tradePrice ?: trade.averageEntryPrice

        if (executedQty <= BigDecimal.ZERO) {
            trade.lastExitOrderId = sell.uuid
            trade.lastAction = "CLOSE_NO_FILL_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(trade.id!!, "CLOSE_NO_FILL", execPrice, BigDecimal.ZERO, "청산 주문 미체결: $reason")
            return
        }

        applyExitFill(trade, executedQty, execPrice, reason)

        trade.lastExitOrderId = sell.uuid
        if (trade.remainingQuantity > BigDecimal.ZERO) {
            trade.status = GuidedTradeEntity.STATUS_OPEN
            trade.lastAction = "PARTIAL_CLOSE_$reason"
            guidedTradeRepository.save(trade)
            appendEvent(
                trade.id!!,
                "PARTIAL_CLOSE",
                execPrice,
                executedQty,
                "부분 청산 (${executedQty.stripTrailingZeros().toPlainString()}/${requestedQty.stripTrailingZeros().toPlainString()}): $reason"
            )
            return
        }

        trade.status = GuidedTradeEntity.STATUS_CLOSED
        trade.closedAt = Instant.now()
        trade.exitReason = reason
        trade.lastAction = "CLOSED_$reason"
        guidedTradeRepository.save(trade)
        appendEvent(trade.id!!, "CLOSE_ALL", execPrice, executedQty, "자동 청산: $reason")
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

    private fun buildRecommendation(market: String, candles: List<CandleResponse>): GuidedRecommendation {
        if (candles.size < 30) {
            val current = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble() ?: 0.0
            return GuidedRecommendation(
                market = market,
                currentPrice = current,
                recommendedEntryPrice = current,
                stopLossPrice = current * 0.985,
                takeProfitPrice = current * 1.03,
                confidence = 0.45,
                suggestedOrderType = "MARKET",
                rationale = listOf("캔들 데이터 부족으로 보수적 추천")
            )
        }

        val closes = candles.map { it.tradePrice.toDouble() }
        val highs = candles.map { it.highPrice.toDouble() }
        val lows = candles.map { it.lowPrice.toDouble() }
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
        val stopLoss = recommended - max(recommended * 0.01, atr14 * 1.4)
        val takeProfit = recommended + (recommended - stopLoss) * 2.2
        val diffPct = kotlin.math.abs(current - recommended) / current
        val orderType = if (diffPct < 0.0025) "MARKET" else "LIMIT"

        val confidence = (0.45 + momentum * 0.35 + (if (diffPct < 0.01) 0.15 else 0.05)).coerceIn(0.2, 0.95)

        val reasons = buildList {
            add("SMA20=${formatPrice(sma20)}, SMA60=${formatPrice(sma60)}")
            add("지지선(20봉 저점)=${formatPrice(support20)}")
            add("ATR14=${formatPrice(atr14)} 기반 손절 폭 적용")
            add("RR 2.2 기준 익절/손절 자동 설정")
        }

        return GuidedRecommendation(
            market = market,
            currentPrice = current,
            recommendedEntryPrice = recommended,
            stopLossPrice = stopLoss,
            takeProfitPrice = takeProfit,
            confidence = confidence,
            suggestedOrderType = orderType,
            rationale = reasons
        )
    }

    private fun calcAtr(candles: List<CandleResponse>, period: Int): Double {
        if (candles.size < 2) return 0.0
        val trValues = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]
            val prevClose = candles[i - 1].tradePrice.toDouble()
            val high = cur.highPrice.toDouble()
            val low = cur.lowPrice.toDouble()
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
}

data class GuidedMarketItem(
    val market: String,
    val symbol: String,
    val koreanName: String,
    val englishName: String?,
    val tradePrice: Double,
    val changeRate: Double,
    val changePrice: Double,
    val accTradePrice: Double
)

data class GuidedCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class GuidedRecommendation(
    val market: String,
    val currentPrice: Double,
    val recommendedEntryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val confidence: Double,
    val suggestedOrderType: String,
    val rationale: List<String>
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
    val events: List<GuidedTradeEventView>
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
