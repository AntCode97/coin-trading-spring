package com.ant.cointrading.fundingarb

import com.ant.cointrading.api.binance.BinanceFuturesApi
import com.ant.cointrading.api.binance.BinanceFuturesPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.config.FundingArbitrageProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.FundingArbPositionEntity
import com.ant.cointrading.repository.FundingArbPositionRepository
import com.ant.cointrading.repository.FundingPaymentEntity
import com.ant.cointrading.repository.FundingPaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 펀딩 차익거래 포지션 관리자
 *
 * 역할:
 * - 델타 뉴트럴 포지션 진입 (현물 매수 + 선물 매도)
 * - 실시간 포지션 모니터링
 * - 펀딩 수수료 수령/지불 추적
 * - 포지션 청산
 * - 리스크 모니터링 (마진 청산, 델타 불균형)
 */
@Component
class FundingPositionManager(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val bithumbPublicApi: com.ant.cointrading.api.bithumb.BithumbPublicApi,
    private val binanceFuturesApi: BinanceFuturesApi,
    private val binanceFuturesPrivateApi: BinanceFuturesPrivateApi,
    private val positionRepository: FundingArbPositionRepository,
    private val paymentRepository: FundingPaymentRepository,
    private val properties: FundingArbitrageProperties,
    private val slackNotifier: SlackNotifier,
    private val riskChecker: FundingRiskChecker
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val activePositions = ConcurrentHashMap<Long, FundingArbPositionEntity>()
    private val consecutiveFailures = AtomicInteger(0)
    private val maxConsecutiveFailures = 3
    @Volatile
    private var circuitBreakerTrippedUntil: Instant? = null

    @Scheduled(fixedDelay = 30_000)
    fun monitorPositions() {
        if (!properties.enabled) return

        try {
            val openPositions = positionRepository.findByStatus("OPEN")

            openPositions.forEach { position ->
                updatePositionMetrics(position)

                val riskEvents = riskChecker.checkPosition(position)
                riskEvents.forEach { event ->
                    log.warn("[${position.symbol}] 리스크 감지: ${event.type} - ${event.severity}")
                }

                if (riskChecker.shouldClosePosition(position)) {
                    log.warn("[${position.symbol}] 리스크 기반 자동 청산")
                    closePosition(position)
                }
            }
        } catch (e: Exception) {
            log.error("포지션 모니터링 실패: ${e.message}", e)
        }
    }

    fun enterPosition(position: FundingArbPositionEntity): PositionResult {
        val symbol = position.symbol

        if (isCircuitBreakerOpen()) {
            return PositionResult.failure(symbol, "서킷 브레이커 발동 중")
        }

        val bithumbMarket = symbol.replace("USDT", "_KRW")

        log.info("[$symbol] 델타 뉴트럴 포지션 진입 시작")
        log.info("  - 현물: $bithumbMarket @ ${position.spotEntryPrice}")
        log.info("  - 선물: $symbol @ ${position.perpEntryPrice}")

        val perpOrderResult = try {
            openPerpShortPosition(symbol, position.perpQuantity ?: position.spotQuantity, position.perpEntryPrice!!)
        } catch (e: Exception) {
            log.error("[$symbol] 선물 포지션 진입 실패: ${e.message}")
            return PositionResult.failure(symbol, "선물 포지션 진입 실패: ${e.message}")
        }

        if (!perpOrderResult.success) {
            recordFailure("선물 진입 실패: ${perpOrderResult.errorMessage}")
            return PositionResult.failure(symbol, perpOrderResult.errorMessage ?: "알 수 없는 오류")
        }

        val spotOrderResult = try {
            val price = java.math.BigDecimal.valueOf(position.spotEntryPrice ?: 0.0)
            val quantity = java.math.BigDecimal.valueOf(position.spotQuantity)
                .setScale(8, java.math.RoundingMode.DOWN)

            val order = bithumbPrivateApi.buyLimitOrder(bithumbMarket, price, quantity)
            log.info("[$bithumbMarket] 현물 매수 주문 완료: ${order.uuid}")

            PositionResult.success(bithumbMarket, order.uuid)
        } catch (e: Exception) {
            log.error("[$bithumbMarket] 현물 매수 주문 실패: ${e.message}", e)

            log.warn("[$symbol] 선물 포지션 롤백 중...")
            closePerpPosition(symbol, position.perpQuantity ?: position.spotQuantity)

            recordFailure("현물 매수 실패: ${e.message}")
            return PositionResult.failure(symbol, "현물 매수 실패: ${e.message}")
        }

        val savedPosition = positionRepository.save(position)
        activePositions[savedPosition.id!!] = savedPosition
        recordSuccess()

        log.info("[$symbol] 델타 뉴트럴 포지션 진입 완료")
        slackNotifier.sendSystemNotification(
            "Funding Arbitrage 포지션 진입",
            "[$symbol] 델타 뉴트럴 포지션 진입\n" +
            "현물: $bithumbMarket ${spotOrderResult.orderId}\n" +
            "선물: $symbol ${perpOrderResult.orderId}"
        )

        return PositionResult.success(symbol, savedPosition.id.toString())
    }

    fun closePosition(position: FundingArbPositionEntity): CloseResult {
        val symbol = position.symbol
        val bithumbMarket = symbol.replace("USDT", "_KRW")

        log.info("[$symbol] 포지션 청산 시작")

        val perpCloseResult = try {
            closePerpPosition(symbol, position.perpQuantity ?: position.spotQuantity)
        } catch (e: Exception) {
            log.error("[$symbol] 선물 포지션 청산 실패: ${e.message}")
            recordFailure("선물 청산 실패: ${e.message}")
            return CloseResult.failure(symbol, "선물 청산 실패: ${e.message}")
        }

        val spotCloseResult = try {
            val currentPrice = java.math.BigDecimal.valueOf(getCurrentSpotPrice(bithumbMarket))
            val quantity = java.math.BigDecimal.valueOf(position.spotQuantity)
                .setScale(8, java.math.RoundingMode.DOWN)

            val order = bithumbPrivateApi.sellLimitOrder(
                bithumbMarket,
                currentPrice,
                quantity
            )
            log.info("[$bithumbMarket] 현물 매도 주문 완료: ${order.uuid}")
            OrderExecutionResult.success(bithumbMarket, order.uuid)
        } catch (e: Exception) {
            log.error("[$bithumbMarket] 현물 매도 주문 실패: ${e.message}", e)
            OrderExecutionResult.failure(bithumbMarket, "현물 청산 실패: ${e.message}")
        }

        val currentPerpPrice = java.math.BigDecimal.valueOf(getPerpPrice(symbol))
        val spotPnl = (currentPerpPrice.toDouble() - position.spotEntryPrice!!) * position.spotQuantity
        val perpPnl = (position.perpEntryPrice!! - currentPerpPrice.toDouble()) * (position.perpQuantity ?: position.spotQuantity)
        val fundingPnl = position.totalFundingReceived
        val totalPnL = spotPnl + perpPnl + fundingPnl

        position.exitTime = Instant.now()
        position.netPnl = totalPnL
        position.totalTradingCost = kotlin.math.abs(spotPnl) + kotlin.math.abs(perpPnl)
        position.status = "CLOSED"
        position.exitReason = if (totalPnL > 0) "PROFIT" else "LOSS"
        position.updatedAt = Instant.now()

        positionRepository.save(position)
        activePositions.remove(position.id)
        recordSuccess()

        val pnlPercent = if (position.totalTradingCost != 0.0) (totalPnL / position.totalTradingCost) * 100 else 0.0

        log.info("[$symbol] 포지션 청산 완료")
        log.info("  - 현물 PnL: $spotPnl")
        log.info("  - 선물 PnL: $perpPnl")
        log.info("  - 펀딩 PnL: $fundingPnl")
        log.info("  - 총 PnL: $totalPnL ($pnlPercent%)")

        slackNotifier.sendSystemNotification(
            "Funding Arbitrage 포지션 청산",
            "[$symbol] 포지션 청산\n" +
            "현물 PnL: $spotPnl\n" +
            "선물 PnL: $perpPnl\n" +
            "펀딩 PnL: $fundingPnl\n" +
            "총 PnL: $totalPnL ($pnlPercent%)"
        )

        return CloseResult.success(symbol, totalPnL, pnlPercent)
    }

    fun recordFundingPayment(
        positionId: Long,
        symbol: String,
        amount: Double,
        fundingRate: Double,
        exchange: String
    ) {
        val payment = FundingPaymentEntity(
            positionId = positionId,
            exchange = exchange,
            symbol = symbol,
            paymentAmount = amount,
            fundingRate = fundingRate,
            paymentTime = Instant.now()
        )

        paymentRepository.save(payment)

        log.info("[$symbol] 펀딩 수수료 기록: $exchange $amount (rate: $fundingRate)")

        positionRepository.findById(positionId).ifPresent { position ->
            position.totalFundingReceived += amount
            position.updatedAt = Instant.now()
            positionRepository.save(position)
        }
    }

    private fun updatePositionMetrics(position: FundingArbPositionEntity) {
        val symbol = position.symbol
        val bithumbMarket = symbol.replace("USDT", "_KRW")

        val currentSpotPrice = getCurrentSpotPrice(bithumbMarket)
        val currentPerpPrice = getPerpPrice(symbol)

        val spotPnl = (currentSpotPrice - position.spotEntryPrice!!) * position.spotQuantity
        val perpPnl = (position.perpEntryPrice!! - currentPerpPrice) * (position.perpQuantity ?: position.spotQuantity)
        val deltaPnl = spotPnl + perpPnl

        val hoursHeld = Duration.between(position.entryTime, Instant.now()).toHours()

        val deltaImbalance = if (spotPnl != 0.0 && perpPnl != 0.0) {
            kotlin.math.abs(spotPnl + perpPnl) / kotlin.math.max(kotlin.math.abs(spotPnl), kotlin.math.abs(perpPnl))
        } else 0.0

        if (deltaImbalance > 0.1) {
            log.warn("[$symbol] 델타 불균형 감지: ${(deltaImbalance * 100).toInt()}% (held ${hoursHeld}h)")
        }
    }

    private fun checkRisk(position: FundingArbPositionEntity) {
        val hoursHeld = Duration.between(position.entryTime, Instant.now()).toHours()

        if (hoursHeld >= properties.maxHoldingPeriodHours) {
            log.warn("[$position.symbol] 최대 보유 기간 도달: ${hoursHeld}h")
            slackNotifier.sendSystemNotification(
                "Funding Arbitrage 리스크 경고",
                "[$position.symbol] 최대 보유 기간 도달 (${hoursHeld}h)"
            )
        }

        val currentPerpPrice = getPerpPrice(position.symbol)
        val perpEntryPrice = position.perpEntryPrice ?: return
        val perpQuantity = position.perpQuantity ?: position.spotQuantity

        val perpPnl = (perpEntryPrice - currentPerpPrice) * perpQuantity
        val positionValue = perpEntryPrice * perpQuantity
        val pnlPercent = if (positionValue != 0.0) (perpPnl / positionValue) * 100 else 0.0

        if (pnlPercent < -10) {
            log.error("[$position.symbol] 마진 청산 리스크: 선물 PnL ${pnlPercent}%")
            slackNotifier.sendSystemNotification(
                "Funding Arbitrage 리스크 경고",
                "[$position.symbol] 마진 청산 리스크: 선물 PnL ${pnlPercent}%"
            )
        }
    }

    private fun openPerpShortPosition(symbol: String, quantity: Double, price: Double): OrderExecutionResult {
        log.info("[$symbol] 선물 숏 포지션 진입: 수량=$quantity, 가격=$price")

        if (!checkSlippage(symbol, price)) {
            return OrderExecutionResult.failure(symbol, "슬리피지 초과")
        }

        val qty = BigDecimal.valueOf(quantity).setScale(4, java.math.RoundingMode.DOWN)
        val result = binanceFuturesPrivateApi.placeOrderWithPartialFill(
            symbol = symbol,
            side = "SELL",
            quantity = qty,
            orderType = "MARKET"
        )

        if (result == null || !result.success) {
            return OrderExecutionResult.failure(symbol, result?.errorMessage ?: "주문 실패")
        }

        log.info("[$symbol] 선물 숏 진입 완료: executedQty=${result.totalExecutedQty}, orderIds=${result.orderIds}")
        return OrderExecutionResult.success(symbol, result.orderIds.firstOrNull()?.toString() ?: "N/A")
    }

    private fun closePerpPosition(symbol: String, quantity: Double): OrderExecutionResult {
        log.info("[$symbol] 선물 숏 포지션 청산: 수량=$quantity")

        val qty = BigDecimal.valueOf(quantity).setScale(4, java.math.RoundingMode.DOWN)
        val result = binanceFuturesPrivateApi.placeOrderWithPartialFill(
            symbol = symbol,
            side = "BUY",
            quantity = qty,
            orderType = "MARKET"
        )

        if (result == null || !result.success) {
            return OrderExecutionResult.failure(symbol, result?.errorMessage ?: "청산 실패")
        }

        log.info("[$symbol] 선물 숏 청산 완료: executedQty=${result.totalExecutedQty}")
        return OrderExecutionResult.success(symbol, result.orderIds.firstOrNull()?.toString() ?: "N/A")
    }

    private fun getCurrentSpotPrice(market: String): Double {
        val tickers = bithumbPublicApi.getCurrentPrice(market)
        return tickers?.firstOrNull()?.tradePrice?.toDouble() ?: 0.0
    }

    private fun getPerpPrice(symbol: String): Double {
        val premiumIndex = binanceFuturesApi.getPremiumIndex(symbol)
        return premiumIndex?.indexPrice?.toDouble() ?: 0.0
    }

    private fun checkSlippage(symbol: String, expectedPrice: Double): Boolean {
        val currentPrice = getPerpPrice(symbol)
        if (currentPrice == 0.0) return false

        val slippagePercent = kotlin.math.abs(currentPrice - expectedPrice) / expectedPrice * 100
        val maxSlippage = properties.slippageBuffer

        if (slippagePercent > maxSlippage) {
            log.warn("[$symbol] 슬리피지 초과: ${slippagePercent}% > ${maxSlippage}% (expected=$expectedPrice, current=$currentPrice)")
            return false
        }
        return true
    }

    private fun isCircuitBreakerOpen(): Boolean {
        val trippedUntil = circuitBreakerTrippedUntil ?: return false
        if (Instant.now().isAfter(trippedUntil)) {
            circuitBreakerTrippedUntil = null
            consecutiveFailures.set(0)
            log.info("Funding Arbitrage 서킷 브레이커 해제")
            return false
        }
        return true
    }

    private fun recordSuccess() {
        consecutiveFailures.set(0)
    }

    private fun recordFailure(reason: String) {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= maxConsecutiveFailures) {
            circuitBreakerTrippedUntil = Instant.now().plus(Duration.ofHours(1))
            log.error("Funding Arbitrage 서킷 브레이커 발동: 연속 ${count}회 실패 ($reason). 1시간 거래 중단")
            slackNotifier.sendSystemNotification(
                "Funding Arbitrage 서킷 브레이커",
                "연속 ${count}회 실패로 1시간 거래 중단\n사유: $reason"
            )
        }
    }

    @Scheduled(fixedDelay = 300_000)
    fun reconcilePositions() {
        if (!properties.enabled) return

        val openPositions = positionRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) return

        openPositions.forEach { position ->
            try {
                val currency = position.symbol.replace("USDT", "")

                val spotBalance = bithumbPrivateApi.getBalance(currency)
                val expectedSpotQty = BigDecimal.valueOf(position.spotQuantity)
                val spotDiff = spotBalance.subtract(expectedSpotQty).abs()
                val spotDiffPercent = if (expectedSpotQty > BigDecimal.ZERO) {
                    spotDiff.divide(expectedSpotQty, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100)).toDouble()
                } else 0.0

                if (spotDiffPercent > 5.0) {
                    log.warn("[${position.symbol}] 포지션 불일치: DB=${position.spotQuantity}, 실제=$spotBalance (${spotDiffPercent}%)")
                    slackNotifier.sendSystemNotification(
                        "Funding Arbitrage 포지션 불일치",
                        "[${position.symbol}] 현물 수량 불일치\nDB: ${position.spotQuantity}\n실제: $spotBalance\n차이: ${spotDiffPercent}%"
                    )
                }
            } catch (e: Exception) {
                log.error("[${position.symbol}] 리콘실리에이션 실패: ${e.message}")
            }
        }
    }
}

/**
 * 포지션 진입 결과
 */
data class PositionResult(
    val success: Boolean,
    val symbol: String,
    val orderId: String?,
    val errorMessage: String?
) {
    companion object {
        fun success(symbol: String, orderId: String) = PositionResult(true, symbol, orderId, null)
        fun failure(symbol: String, errorMessage: String) = PositionResult(false, symbol, null, errorMessage)
    }
}

/**
 * 포지션 청산 결과
 */
data class CloseResult(
    val success: Boolean,
    val symbol: String,
    val totalPnl: Double,
    val pnlPercent: Double,
    val errorMessage: String?
) {
    companion object {
        fun success(symbol: String, totalPnl: Double, pnlPercent: Double) =
            CloseResult(true, symbol, totalPnl, pnlPercent, null)

        fun failure(symbol: String, errorMessage: String) =
            CloseResult(false, symbol, 0.0, 0.0, errorMessage)
    }
}

/**
 * 주문 실행 결과
 */
data class OrderExecutionResult(
    val success: Boolean,
    val market: String,
    val orderId: String?,
    val errorMessage: String?
) {
    companion object {
        fun success(market: String, orderId: String) = OrderExecutionResult(true, market, orderId, null)
        fun failure(market: String, errorMessage: String) = OrderExecutionResult(false, market, null, errorMessage)
    }
}
