package com.ant.cointrading.order

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.*
import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.risk.MarketConditionResult
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * 미체결 주문 관리자
 *
 * 20년차 퀀트의 미체결 주문 관리 원칙:
 *
 * 1. 모든 주문은 반드시 추적한다 (DB 영속화)
 * 2. 주문 제출 시점의 시장 상황을 기록한다 (사후 분석용)
 * 3. 타임아웃은 시장 상황에 따라 동적으로 조정한다
 * 4. 가격이 불리하게 움직이면 빠르게 손절/재주문 결정
 * 5. 부분 체결은 별도 로직으로 처리한다
 * 6. 모든 취소 사유를 기록한다 (개선 피드백용)
 *
 * 엣지 케이스 처리:
 * - 취소 요청 중 체결됨 → 체결 우선 처리
 * - API 에러로 상태 확인 실패 → 재시도 후 수동 확인 요청
 * - 서버 재시작 시 미체결 주문 복구 → @PostConstruct
 * - 동일 마켓 중복 주문 방지 → 마켓별 락
 */
@Component
class PendingOrderManager(
    private val pendingOrderRepository: PendingOrderRepository,
    private val tradeRepository: TradeRepository,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val bithumbPublicApi: BithumbPublicApi,
    private val marketConditionChecker: MarketConditionChecker,
    private val circuitBreaker: CircuitBreaker,
    private val slackNotifier: SlackNotifier,
    private val tradingProperties: TradingProperties
) {
    private val log = LoggerFactory.getLogger(PendingOrderManager::class.java)

    companion object {
        // === 타임아웃 설정 ===
        const val DEFAULT_TIMEOUT_SECONDS = 30L       // 기본 타임아웃
        const val MIN_TIMEOUT_SECONDS = 10L           // 최소 타임아웃
        const val MAX_TIMEOUT_SECONDS = 120L          // 최대 타임아웃

        // === 가격 이탈 임계값 (퀀트 최적화) ===
        // 기존 1.0%는 너무 보수적 - 암호화폐 시장에서 1% 이탈은 상당한 손실
        // 0.3%: 즉시 취소 (빠른 대응)
        // 0.5%: 긴급 취소 + 시장가 전환
        const val PRICE_DEVIATION_CANCEL_PERCENT = 0.3  // 0.3% 이탈 시 취소 검토
        const val PRICE_DEVIATION_URGENT_PERCENT = 0.5  // 0.5% 이탈 시 즉시 취소

        // === 스프레드 임계값 ===
        const val SPREAD_WIDEN_RATIO = 2.0            // 스프레드 2배 이상 확대 시 재평가

        // === 체결 확인 주기 ===
        const val CHECK_INTERVAL_MS = 1000L           // 1초마다 확인
        const val MAX_CHECK_RETRIES = 3               // API 에러 시 최대 재시도

        // === 부분 체결 임계값 (퀀트 최적화) ===
        // 95%는 너무 높음 - 90% 이상이면 체결 완료로 간주
        // 50% 미만 부분 체결 후 취소 시 별도 Slack 경고 (수동 청산 유도)
        const val PARTIAL_FILL_COMPLETE_THRESHOLD = 0.90  // 90% 이상이면 완료 처리
        const val PARTIAL_FILL_WARNING_THRESHOLD = 0.50   // 50% 미만 부분 체결 시 경고
    }

    // 마켓별 락 (동시 주문 방지)
    private val marketLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    @PostConstruct
    fun init() {
        log.info("=== PendingOrderManager 초기화 ===")
        recoverPendingOrders()
    }

    /**
     * 서버 재시작 시 미체결 주문 복구
     */
    private fun recoverPendingOrders() {
        val pendingOrders = pendingOrderRepository.findActivePendingOrders()

        if (pendingOrders.isEmpty()) {
            log.info("복구할 미체결 주문 없음")
            return
        }

        log.warn("미체결 주문 ${pendingOrders.size}건 복구 시작")

        pendingOrders.forEach { order ->
            try {
                // 만료 시간 재설정 (복구 시점부터 30초)
                order.expiresAt = Instant.now().plusSeconds(DEFAULT_TIMEOUT_SECONDS)
                order.lastCheckedAt = Instant.now()
                order.note = (order.note ?: "") + " [서버 재시작으로 복구]"
                pendingOrderRepository.save(order)

                log.info("[${order.market}] 미체결 주문 복구: orderId=${order.orderId}, 상태=${order.status}")

            } catch (e: Exception) {
                log.error("[${order.market}] 미체결 주문 복구 실패: ${e.message}")
            }
        }

        slackNotifier.sendSystemNotification(
            "미체결 주문 복구",
            "${pendingOrders.size}건의 미체결 주문이 복구되었습니다."
        )
    }

    /**
     * 미체결 주문 등록
     */
    @Transactional
    fun registerPendingOrder(
        orderId: String,
        signal: TradingSignal,
        orderPrice: BigDecimal,
        orderQuantity: BigDecimal,
        orderAmountKrw: BigDecimal,
        marketCondition: MarketConditionResult,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): PendingOrderEntity {
        val entity = PendingOrderEntity(
            orderId = orderId,
            market = signal.market,
            side = if (signal.action == SignalAction.BUY) "BUY" else "SELL",
            orderType = "LIMIT",
            orderPrice = orderPrice,
            orderQuantity = orderQuantity,
            orderAmountKrw = orderAmountKrw,
            strategy = signal.strategy,
            signalConfidence = signal.confidence,
            signalReason = signal.reason.take(500),
            snapshotMidPrice = marketCondition.midPrice,
            snapshotSpread = marketCondition.spread,
            snapshotVolatility = marketCondition.volatility1Min,
            snapshotImbalance = marketCondition.orderBookImbalance,
            expiresAt = Instant.now().plusSeconds(timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS))
        )

        val saved = pendingOrderRepository.save(entity)
        log.info("[${signal.market}] 미체결 주문 등록: orderId=$orderId, 가격=$orderPrice, 만료=${entity.expiresAt}")

        return saved
    }

    /**
     * 주기적 미체결 주문 확인 (1초마다)
     */
    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    fun checkPendingOrders() {
        val pendingOrders = pendingOrderRepository.findActivePendingOrders()

        if (pendingOrders.isEmpty()) {
            return
        }

        pendingOrders.forEach { order ->
            try {
                checkAndProcessOrder(order)
            } catch (e: Exception) {
                log.error("[${order.market}] 미체결 주문 처리 실패: orderId=${order.orderId}, error=${e.message}")
            }
        }
    }

    /**
     * 개별 주문 확인 및 처리
     */
    @Transactional
    fun checkAndProcessOrder(order: PendingOrderEntity) {
        val market = order.market
        val lock = marketLocks.getOrPut(market) { Any() }

        synchronized(lock) {
            // 1. API로 주문 상태 확인
            val orderStatus = fetchOrderStatus(order)

            if (orderStatus == null) {
                handleApiError(order)
                return
            }

            // 2. 체결 상태 업데이트
            updateFillStatus(order, orderStatus)

            // 3. 완료 여부 확인
            if (isOrderComplete(order)) {
                finalizeOrder(order)
                return
            }

            // 4. 취소 필요 여부 판단
            val cancelDecision = shouldCancelOrder(order)

            if (cancelDecision.shouldCancel) {
                cancelAndReplace(order, cancelDecision)
                return
            }

            // 5. 상태 업데이트
            order.lastCheckedAt = Instant.now()
            order.checkCount++
            pendingOrderRepository.save(order)
        }
    }

    /**
     * 주문 상태 조회 (재시도 포함)
     */
    private fun fetchOrderStatus(order: PendingOrderEntity): OrderResponse? {
        repeat(MAX_CHECK_RETRIES) { attempt ->
            try {
                val response = bithumbPrivateApi.getOrder(order.orderId)
                if (response != null) {
                    return response
                }
                Thread.sleep(100 * (attempt + 1).toLong())
            } catch (e: Exception) {
                log.warn("[${order.market}] 주문 조회 실패 (시도 ${attempt + 1}): ${e.message}")
            }
        }
        return null
    }

    /**
     * API 에러 처리
     */
    private fun handleApiError(order: PendingOrderEntity) {
        order.checkCount++
        order.lastCheckedAt = Instant.now()

        // 연속 실패 시 수동 확인 요청
        if (order.checkCount >= MAX_CHECK_RETRIES * 3) {
            order.note = (order.note ?: "") + " [API 에러 다수 발생 - 수동 확인 필요]"
            slackNotifier.sendError(order.market, """
                미체결 주문 상태 확인 실패

                주문ID: ${order.orderId}
                마켓: ${order.market}
                연속 실패: ${order.checkCount}회

                수동 확인이 필요합니다.
            """.trimIndent())
        }

        pendingOrderRepository.save(order)
    }

    /**
     * 체결 상태 업데이트
     */
    private fun updateFillStatus(order: PendingOrderEntity, response: OrderResponse) {
        val executedVolume = response.executedVolume ?: BigDecimal.ZERO
        val previousFilled = order.filledQuantity

        // 새로운 체결 발생
        if (executedVolume > previousFilled) {
            order.filledQuantity = executedVolume

            // 평균 체결가 계산 (locked 금액 기반)
            if (executedVolume > BigDecimal.ZERO) {
                val executedAmount = response.locked ?: order.orderAmountKrw
                order.avgFilledPrice = executedAmount.divide(executedVolume, 8, RoundingMode.HALF_UP)
            }

            // 상태 업데이트
            val fillRate = order.fillRate()
            if (fillRate >= PARTIAL_FILL_COMPLETE_THRESHOLD) {
                // 거의 완료
                order.status = PendingOrderStatus.PARTIALLY_FILLED
            } else if (executedVolume > BigDecimal.ZERO) {
                order.status = PendingOrderStatus.PARTIALLY_FILLED
            }

            log.info("[${order.market}] 체결 업데이트: orderId=${order.orderId}, 체결률=${String.format("%.1f", fillRate * 100)}%")
        }
    }

    /**
     * 주문 완료 여부 확인
     */
    private fun isOrderComplete(order: PendingOrderEntity): Boolean {
        return order.fillRate() >= PARTIAL_FILL_COMPLETE_THRESHOLD ||
                order.status == PendingOrderStatus.FILLED
    }

    /**
     * 주문 완료 처리
     */
    @Transactional
    fun finalizeOrder(order: PendingOrderEntity) {
        val fillDuration = Duration.between(order.createdAt, Instant.now()).toMillis()

        order.status = PendingOrderStatus.FILLED
        order.statusChangedAt = Instant.now()
        order.fillDurationMs = fillDuration

        // 슬리피지 계산
        if (order.avgFilledPrice != null && order.orderPrice > BigDecimal.ZERO) {
            val slippage = order.avgFilledPrice!!.subtract(order.orderPrice)
                .divide(order.orderPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()

            // 매도의 경우 부호 반전 (낮은 가격에 팔면 손해)
            order.slippage = if (order.side == "SELL") -slippage else slippage
        }

        // 현재 중간가 저장 (분석용)
        val apiMarket = PositionHelper.convertToApiMarket(order.market)
        try {
            val orderbook = bithumbPublicApi.getOrderbook(apiMarket)?.firstOrNull()
            if (orderbook?.orderbookUnits?.isNotEmpty() == true) {
                val unit = orderbook.orderbookUnits.first()
                order.fillMidPrice = (unit.askPrice + unit.bidPrice).divide(BigDecimal(2), 8, RoundingMode.HALF_UP)
            }
        } catch (e: Exception) {
            log.warn("[${order.market}] 체결 시점 중간가 조회 실패")
        }

        pendingOrderRepository.save(order)

        log.info("""
            [${order.market}] 주문 체결 완료
            주문ID: ${order.orderId}
            체결가: ${order.avgFilledPrice}
            체결량: ${order.filledQuantity}
            소요시간: ${fillDuration}ms
            슬리피지: ${String.format("%.3f", order.slippage ?: 0.0)}%
        """.trimIndent())

        // CircuitBreaker에 성공 기록
        circuitBreaker.recordExecutionSuccess(order.market)

        // 슬리피지 기록 (BUY: 양수, SELL: 음수가 불리한 슬리피지)
        val slippage = order.slippage ?: 0.0
        val isBadSlippage = when (order.side) {
            "BUY" -> slippage > 0      // 매수: 예상가보다 비싸게 체결
            "SELL" -> slippage < 0     // 매도: 예상가보다 싸게 체결
            else -> false
        }
        if (isBadSlippage) {
            circuitBreaker.recordSlippage(order.market, kotlin.math.abs(slippage))
        }
    }

    /**
     * 취소 여부 판단
     */
    private fun shouldCancelOrder(order: PendingOrderEntity): CancelDecision {
        // 1. 타임아웃 체크
        if (order.isExpired()) {
            return CancelDecision(
                shouldCancel = true,
                reason = CancelReason.TIMEOUT,
                shouldReplace = shouldReplaceOnTimeout(order),
                message = "주문 타임아웃 (${Duration.between(order.createdAt, Instant.now()).seconds}초)"
            )
        }

        // 2. 현재 시장 상황 확인
        val marketCondition = try {
            marketConditionChecker.checkMarketCondition(order.market, order.orderAmountKrw)
        } catch (e: Exception) {
            log.warn("[${order.market}] 시장 상황 조회 실패, 취소 판단 스킵")
            return CancelDecision(shouldCancel = false)
        }

        val currentMidPrice = marketCondition.midPrice ?: return CancelDecision(shouldCancel = false)

        // 3. 가격 이탈 체크
        val priceDeviation = order.priceDeviation(currentMidPrice)
        val isUnfavorable = when (order.side) {
            "BUY" -> priceDeviation > 0   // 매수인데 가격이 올라감 (체결 어려움)
            "SELL" -> priceDeviation < 0  // 매도인데 가격이 내려감 (체결 어려움)
            else -> false
        }

        if (isUnfavorable && kotlin.math.abs(priceDeviation) > PRICE_DEVIATION_URGENT_PERCENT) {
            return CancelDecision(
                shouldCancel = true,
                reason = CancelReason.PRICE_MOVED,
                shouldReplace = true,
                message = "가격 급이탈 (${String.format("%.2f", priceDeviation)}%)"
            )
        }

        // 4. 스프레드 급등 체크
        val currentSpread = marketCondition.spread ?: 0.0
        val originalSpread = order.snapshotSpread ?: 0.0

        if (originalSpread > 0 && currentSpread > originalSpread * SPREAD_WIDEN_RATIO) {
            return CancelDecision(
                shouldCancel = true,
                reason = CancelReason.SPREAD_WIDENED,
                shouldReplace = false,  // 스프레드 정상화 후 재시도
                message = "스프레드 급등 (${String.format("%.3f", originalSpread)}% → ${String.format("%.3f", currentSpread)}%)"
            )
        }

        // 5. 시장 상태 악화 체크
        if (!marketCondition.canTrade) {
            return CancelDecision(
                shouldCancel = true,
                reason = CancelReason.MARKET_CLOSE,
                shouldReplace = false,
                message = "시장 상태 악화: ${marketCondition.issues.joinToString(", ")}"
            )
        }

        return CancelDecision(shouldCancel = false)
    }

    /**
     * 타임아웃 시 재주문 여부 결정
     */
    private fun shouldReplaceOnTimeout(order: PendingOrderEntity): Boolean {
        // 부분 체결이 있으면 재주문하지 않음 (이미 일부 체결됨)
        if (order.isPartiallyFilled()) {
            return false
        }

        // 신뢰도가 높은 신호였으면 재주문
        return order.signalConfidence > 70.0
    }

    /**
     * 주문 취소 및 대체 주문
     */
    @Transactional
    fun cancelAndReplace(order: PendingOrderEntity, decision: CancelDecision) {
        val market = order.market

        log.info("[${market}] 주문 취소 시작: orderId=${order.orderId}, 사유=${decision.reason}")

        // 1. 주문 취소 요청
        val cancelResult = try {
            bithumbPrivateApi.cancelOrder(order.orderId)
        } catch (e: Exception) {
            log.error("[${market}] 주문 취소 API 실패: ${e.message}")
            null
        }

        // 2. 취소 결과 확인
        if (cancelResult == null) {
            // 취소 실패 - 이미 체결됐을 수 있음
            val currentStatus = fetchOrderStatus(order)

            if (currentStatus?.state == "done") {
                // 이미 체결됨
                log.info("[${market}] 취소 시도 중 체결됨: orderId=${order.orderId}")
                updateFillStatus(order, currentStatus)
                finalizeOrder(order)
                return
            }

            // 취소 실패로 기록
            order.note = (order.note ?: "") + " [취소 실패: API 에러]"
            pendingOrderRepository.save(order)
            return
        }

        // 3. 주문 상태 업데이트
        order.status = if (decision.shouldReplace) PendingOrderStatus.REPLACED else PendingOrderStatus.CANCELLED
        order.cancelReason = decision.reason
        order.statusChangedAt = Instant.now()
        order.note = (order.note ?: "") + " [취소: ${decision.message}]"

        // 부분 체결이 있었다면 기록
        val filledAmount = order.filledQuantity.multiply(order.avgFilledPrice ?: order.orderPrice)
        if (order.isPartiallyFilled()) {
            val fillRate = order.fillRate()
            log.info("[${market}] 부분 체결 후 취소: 체결률=${String.format("%.1f", fillRate * 100)}%")
            savePartialFillTrade(order)

            // 50% 미만 부분 체결 시 특별 경고 (포지션 수동 관리 필요)
            if (fillRate < PARTIAL_FILL_WARNING_THRESHOLD) {
                slackNotifier.sendWarning(market, """
                    부분 체결 포지션 경고

                    주문ID: ${order.orderId}
                    체결률: ${String.format("%.1f", fillRate * 100)}%
                    체결 수량: ${order.filledQuantity}
                    체결 금액: ${String.format("%.0f", filledAmount.toDouble())}원

                    낮은 체결률로 포지션이 생성됨.
                    수동으로 손절/익절 관리 필요.
                """.trimIndent())
            }
        }

        pendingOrderRepository.save(order)

        // 4. 대체 주문 여부 결정
        if (decision.shouldReplace) {
            log.info("[${market}] 미체결분 재주문 필요: ${order.remainingQuantity()}")
            // 재주문은 TradingEngine에서 새 신호로 처리하도록 알림
            slackNotifier.sendSystemNotification(
                "미체결 주문 취소",
                """
                    마켓: $market
                    사유: ${decision.message}
                    체결률: ${String.format("%.1f", order.fillRate() * 100)}%
                    미체결분: ${order.remainingQuantity()}

                    새로운 신호 대기 중
                """.trimIndent()
            )
        }

        log.info("[${market}] 주문 취소 완료: orderId=${order.orderId}, 상태=${order.status}")
    }

    /**
     * 부분 체결 거래 기록 저장
     */
    private fun savePartialFillTrade(order: PendingOrderEntity) {
        if (order.filledQuantity <= BigDecimal.ZERO) return

        val filledAmount = order.filledQuantity.multiply(order.avgFilledPrice ?: order.orderPrice)
        val fee = filledAmount.multiply(tradingProperties.feeRate)  // 설정된 수수료

        val trade = TradeEntity(
            orderId = order.orderId,
            market = order.market,
            side = order.side,
            type = "LIMIT",
            price = (order.avgFilledPrice ?: order.orderPrice).toDouble(),
            quantity = order.filledQuantity.toDouble(),
            totalAmount = filledAmount.toDouble(),
            fee = fee.toDouble(),
            slippage = order.slippage,
            isPartialFill = true,
            strategy = order.strategy,
            confidence = order.signalConfidence,
            reason = "${order.signalReason ?: ""} [부분체결: ${String.format("%.1f", order.fillRate() * 100)}%]",
            simulated = false
        )

        tradeRepository.save(trade)
        log.info("[${order.market}] 부분 체결 기록 저장: ${order.filledQuantity} (수수료: ${tradingProperties.feeRate.multiply(BigDecimal(100))}%)")
    }

    /**
     * 수동 주문 취소
     */
    @Transactional
    fun cancelOrder(orderId: String, reason: String = "수동 취소"): Boolean {
        val order = pendingOrderRepository.findByOrderId(orderId)
            ?: return false

        cancelAndReplace(order, CancelDecision(
            shouldCancel = true,
            reason = CancelReason.MANUAL,
            shouldReplace = false,
            message = reason
        ))

        return true
    }

    /**
     * 특정 마켓의 모든 미체결 주문 취소
     */
    @Transactional
    fun cancelAllOrdersForMarket(market: String, reason: String = "마켓 전체 취소"): Int {
        val orders = pendingOrderRepository.findActivePendingOrdersByMarket(market)

        orders.forEach { order ->
            cancelAndReplace(order, CancelDecision(
                shouldCancel = true,
                reason = CancelReason.MANUAL,
                shouldReplace = false,
                message = reason
            ))
        }

        return orders.size
    }

    /**
     * 미체결 주문 상태 조회
     */
    fun getStatus(): Map<String, Any> {
        val activeOrders = pendingOrderRepository.findActivePendingOrders()

        return mapOf(
            "activePendingOrders" to activeOrders.size,
            "orders" to activeOrders.map { order ->
                mapOf(
                    "orderId" to order.orderId,
                    "market" to order.market,
                    "side" to order.side,
                    "status" to order.status.name,
                    "fillRate" to String.format("%.1f%%", order.fillRate() * 100),
                    "orderPrice" to order.orderPrice,
                    "filledQuantity" to order.filledQuantity,
                    "remainingQuantity" to order.remainingQuantity(),
                    "createdAt" to order.createdAt.toString(),
                    "expiresAt" to order.expiresAt.toString(),
                    "checkCount" to order.checkCount
                )
            }
        )
    }

}

/**
 * 취소 결정
 */
data class CancelDecision(
    val shouldCancel: Boolean,
    val reason: CancelReason? = null,
    val shouldReplace: Boolean = false,
    val message: String = ""
)
