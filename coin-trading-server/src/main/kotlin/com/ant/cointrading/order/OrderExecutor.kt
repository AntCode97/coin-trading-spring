package com.ant.cointrading.order

import com.ant.cointrading.api.bithumb.BithumbApiException
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.model.*
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.risk.MarketConditionResult
import com.ant.cointrading.risk.PnlCalculator
import com.ant.cointrading.risk.RiskThrottleDecision
import com.ant.cointrading.risk.RiskThrottleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 주문 실행기 (완전 재작성)
 *
 * 20년차 퀀트의 교훈 (QUANT_RESEARCH.md 기반):
 * 1. 주문 응답만 믿으면 안 된다 - 반드시 상태 확인
 * 2. 부분 체결은 항상 발생한다 - 처리 로직 필수
 * 3. 슬리피지는 수익을 갉아먹는다 - 측정하고 기록해야 한다
 * 4. 실패는 즉시 CircuitBreaker에 알려야 한다
 *
 * 체크 항목:
 * - 주문 전: 시장 상태 검사 (MarketConditionChecker)
 * - 주문 후: 상태 확인 (최대 3회 재시도)
 * - 부분 체결 감지 및 처리
 * - 슬리피지 계산 및 경고
 * - 실제 수수료 기록 (하드코딩 X)
 * - CircuitBreaker 연동
 */
@Component
class OrderExecutor(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val tradingProperties: TradingProperties,
    private val tradeRepository: TradeRepository,
    private val circuitBreaker: CircuitBreaker,
    private val marketConditionChecker: MarketConditionChecker,
    private val riskThrottleService: RiskThrottleService,
    private val slackNotifier: SlackNotifier,
    private val pendingOrderManager: PendingOrderManager
) {

    private val log = LoggerFactory.getLogger(OrderExecutor::class.java)

    private data class OpenLot(
        val price: Double,
        var quantity: Double,
        val feePerUnit: Double
    )

    private data class MatchedLotSummary(
        val matchedQty: Double,
        val entryAmount: Double,
        val entryFee: Double
    )

    private data class TradePnlSnapshot(
        val pnlAmount: Double?,
        val pnlPercent: Double?
    )

    companion object {
        // 주문 상태 확인 설정 (OrderExecutor 전용)
        const val MAX_STATUS_CHECK_RETRIES = 3
        const val STATUS_CHECK_INITIAL_DELAY_MS = 500L
        const val STATUS_CHECK_MAX_DELAY_MS = 2000L

        // 지정가 주문 설정 (OrderExecutor 전용)
        const val QUICK_FILL_CHECK_MS = 500L            // 빠른 체결 확인 대기 시간
        const val QUICK_FILL_CHECK_RETRIES = 2          // 빠른 확인 재시도 횟수
    }

    /**
     * 주문 실행 (메인 진입점)
     */
    fun execute(signal: TradingSignal, positionSize: BigDecimal): OrderResult {
        val side = signal.toOrderSide()
        val throttleDecision = if (side == OrderSide.BUY) {
            riskThrottleService.getDecision(signal.market, signal.strategy)
        } else {
            null
        }
        val effectivePositionSize = applyRiskThrottle(positionSize, throttleDecision, signal.market, signal.strategy)

        validateMinimumOrderAmount(signal, side, effectivePositionSize)?.let { return it }

        if (!tradingProperties.enabled) {
            return executeSimulated(signal, effectivePositionSize)
        }

        val marketCondition = marketConditionChecker.checkMarketCondition(signal.market, effectivePositionSize)
        if (!marketCondition.canTrade) {
            return createMarketConditionFailure(signal, side, marketCondition)
        }

        return executeReal(signal, effectivePositionSize, marketCondition)
    }

    private fun applyRiskThrottle(
        requestedPositionSize: BigDecimal,
        throttleDecision: RiskThrottleDecision?,
        market: String,
        strategy: String
    ): BigDecimal {
        if (throttleDecision == null || throttleDecision.multiplier >= 0.999) {
            return requestedPositionSize
        }

        val reduced = requestedPositionSize
            .multiply(BigDecimal.valueOf(throttleDecision.multiplier))
            .setScale(0, RoundingMode.DOWN)

        val adjusted = when {
            requestedPositionSize < TradingConstants.MIN_ORDER_AMOUNT_KRW -> requestedPositionSize
            reduced < TradingConstants.MIN_ORDER_AMOUNT_KRW -> TradingConstants.MIN_ORDER_AMOUNT_KRW
            else -> reduced
        }

        log.warn(
            "[{}][{}] 주문 금액 스로틀: {}원 -> {}원 (x{}, sample={}, reason={})",
            market,
            strategy,
            requestedPositionSize,
            adjusted,
            String.format("%.2f", throttleDecision.multiplier),
            throttleDecision.sampleSize,
            throttleDecision.reason
        )

        return adjusted
    }

    private fun validateMinimumOrderAmount(
        signal: TradingSignal,
        side: OrderSide,
        positionSize: BigDecimal
    ): OrderResult? {
        if (positionSize >= TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            return null
        }
        log.warn("[${signal.market}] 최소 주문 금액 미달: ${positionSize}원 < ${TradingConstants.MIN_ORDER_AMOUNT_KRW}원")
        return createFailureResult(
            signal = signal,
            side = side,
            message = "최소 주문 금액(${TradingConstants.MIN_ORDER_AMOUNT_KRW}원) 미달: ${positionSize}원",
            rejectionReason = OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT
        )
    }

    private fun createMarketConditionFailure(
        signal: TradingSignal,
        side: OrderSide,
        marketCondition: MarketConditionResult
    ): OrderResult {
        log.warn("[${signal.market}] 시장 상태 불량으로 주문 거부: ${marketCondition.issues}")
        return createFailureResult(
            signal = signal,
            side = side,
            message = "시장 상태 불량: ${marketCondition.issues.joinToString(", ")}",
            rejectionReason = OrderRejectionReason.MARKET_CONDITION
        )
    }

    /**
     * 시뮬레이션 주문
     */
    private fun executeSimulated(signal: TradingSignal, positionSize: BigDecimal): OrderResult {
        val orderId = "SIM-${System.currentTimeMillis()}"
        val quantity = calculateQuantity(signal.price, positionSize)
        val estimatedFee = positionSize.multiply(tradingProperties.feeRate)  // 설정된 수수료

        log.info("""
            [시뮬레이션] 주문 실행
            마켓: ${signal.market}
            행동: ${signal.action}
            가격: ${signal.price}
            금액: $positionSize
            신뢰도: ${signal.confidence}%
            전략: ${signal.strategy}
            수수료: ${tradingProperties.feeRate.multiply(BigDecimal(100))}%
        """.trimIndent())

        // 거래 기록 저장
        saveTradeRecord(
            signal = signal,
            positionSize = positionSize,
            orderId = orderId,
            executedPrice = signal.price,
            executedQuantity = quantity,
            fee = estimatedFee,
            slippagePercent = 0.0,
            isSimulated = true,
            isPartialFill = false
        )

        return OrderResult(
            success = true,
            orderId = orderId,
            market = signal.market,
            side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL,
            price = signal.price,
            quantity = quantity,
            executedQuantity = quantity,
            fee = estimatedFee,
            slippagePercent = 0.0,
            message = "[시뮬레이션] 주문 완료",
            isSimulated = true
        )
    }

    /**
     * 실제 주문 실행
     *
     * 20년차 퀀트의 주문 실행 전략:
     * 1. 지정가 주문 → 빠른 체결 확인 → 성공 or PendingOrderManager 위임
     * 2. 시장가 주문 → 즉시 체결 확인
     * 3. 미체결 주문은 비동기로 관리 (PendingOrderManager)
     */
    private fun executeReal(
        signal: TradingSignal,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult
    ): OrderResult {
        val market = signal.market
        val apiMarket = PositionHelper.convertToApiMarket(market)
        val midPrice = marketCondition.midPrice
        val side = signal.toOrderSide()

        return try {
            processRealOrderExecution(signal, positionSize, marketCondition, apiMarket, midPrice, side)

        } catch (e: MarketSuspendedException) {
            log.error("[$market] 거래 정지 코인: ${e.message}")
            return recordFailureAndBuildResult(
                signal = signal,
                side = side,
                market = market,
                failureDetail = "거래 정지: ${e.message}",
                userMessage = "거래 정지/상장 폐지 코인: ${e.message}",
                rejectionReason = OrderRejectionReason.MARKET_SUSPENDED
            )
        } catch (e: Exception) {
            log.error("[$market] 주문 실행 실패: ${e.message}", e)
            return recordFailureAndBuildResult(
                signal = signal,
                side = side,
                market = market,
                failureDetail = e.message ?: "Unknown error",
                userMessage = "주문 실행 예외: ${e.message}",
                rejectionReason = OrderRejectionReason.EXCEPTION
            )
        }
    }

    private fun processRealOrderExecution(
        signal: TradingSignal,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult,
        apiMarket: String,
        midPrice: BigDecimal?,
        side: OrderSide
    ): OrderResult {
        val market = signal.market
        val submitResult = submitOrderWithDecision(signal, apiMarket, positionSize, marketCondition)
        val orderResponse = submitResult.orderResponse
            ?: return recordFailureAndBuildResult(
                signal = signal,
                side = side,
                market = market,
                failureDetail = "주문 응답 null",
                userMessage = "주문 응답 없음 - API 장애 의심",
                rejectionReason = OrderRejectionReason.API_ERROR
            )

        val orderId = orderResponse.uuid
        val actualOrderType = submitResult.orderType
        log.info("[$market] 주문 제출됨: orderId=$orderId, type=$actualOrderType")

        if (submitResult.isPending) {
            return createPendingOrderResult(market, orderId, side, submitResult, orderResponse)
        }

        val verifiedOrder = verifyOrderExecution(orderId, market)
            ?: return recordFailureAndBuildResult(
                signal = signal,
                side = side,
                market = market,
                failureDetail = "주문 상태 확인 실패",
                userMessage = "주문 상태 확인 실패 - 수동 확인 필요",
                rejectionReason = OrderRejectionReason.VERIFICATION_FAILED,
                orderId = orderId,
                orderType = actualOrderType
            )

        val executionResult = analyzeExecution(signal, verifiedOrder, positionSize, midPrice, actualOrderType)
        recordExecutionOutcome(market, executionResult)
        saveExecutionRecord(signal, positionSize, orderId, executionResult, actualOrderType)
        return executionResult
    }

    private fun submitOrderWithDecision(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult
    ): OrderSubmitResult {
        val orderType = determineOrderType(signal, marketCondition)
        return submitOrder(signal, apiMarket, positionSize, orderType, marketCondition)
    }

    private fun recordFailureAndBuildResult(
        signal: TradingSignal,
        side: OrderSide,
        market: String,
        failureDetail: String,
        userMessage: String,
        rejectionReason: OrderRejectionReason,
        orderId: String? = null,
        orderType: OrderType = OrderType.MARKET
    ): OrderResult {
        circuitBreaker.recordExecutionFailure(market, failureDetail)
        return createFailureResult(
            signal = signal,
            side = side,
            message = userMessage,
            rejectionReason = rejectionReason,
            orderId = orderId,
            orderType = orderType
        )
    }

    private fun TradingSignal.toOrderSide(): OrderSide {
        return if (action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL
    }

    private fun createFailureResult(
        signal: TradingSignal,
        side: OrderSide,
        message: String,
        rejectionReason: OrderRejectionReason,
        orderId: String? = null,
        orderType: OrderType = OrderType.MARKET
    ): OrderResult {
        return OrderResult(
            success = false,
            orderId = orderId,
            market = signal.market,
            side = side,
            message = message,
            rejectionReason = rejectionReason,
            orderType = orderType
        )
    }

    private fun createPendingOrderResult(
        market: String,
        orderId: String,
        side: OrderSide,
        submitResult: OrderSubmitResult,
        response: OrderResponse
    ): OrderResult {
        log.info("[$market] 미체결 주문 PendingOrderManager에 위임됨: orderId=$orderId")

        val executedVolume = response.executedVolume ?: BigDecimal.ZERO
        val fillRate = calculateFillRate(response)

        return OrderResult(
            success = true,
            orderId = orderId,
            market = market,
            side = side,
            price = submitResult.limitPrice,
            quantity = submitResult.quantity,
            executedQuantity = executedVolume,
            fee = response.paidFee,
            slippagePercent = 0.0,
            isPartialFill = fillRate > 0 && fillRate < 1.0,
            fillRatePercent = fillRate * 100,
            message = "주문 제출됨 (PendingOrderManager에서 체결 관리 중)",
            isPending = true,
            pendingOrderId = submitResult.pendingOrderId,
            orderType = submitResult.orderType
        )
    }

    private fun recordExecutionOutcome(market: String, executionResult: OrderResult) {
        if (executionResult.success) {
            circuitBreaker.recordExecutionSuccess(market)
            if (executionResult.slippagePercent > 0) {
                circuitBreaker.recordSlippage(market, executionResult.slippagePercent)
            }
            return
        }

        circuitBreaker.recordExecutionFailure(market, executionResult.message)
    }

    private fun saveExecutionRecord(
        signal: TradingSignal,
        positionSize: BigDecimal,
        orderId: String,
        executionResult: OrderResult,
        actualOrderType: OrderType
    ) {
        saveTradeRecord(
            signal = signal,
            positionSize = positionSize,
            orderId = orderId,
            executedPrice = executionResult.price,
            executedQuantity = executionResult.executedQuantity,
            fee = executionResult.fee ?: BigDecimal.ZERO,
            slippagePercent = executionResult.slippagePercent,
            isSimulated = false,
            isPartialFill = executionResult.isPartialFill,
            orderType = actualOrderType
        )
    }

    /**
     * 주문 유형 결정 (퀀트 지식 기반)
     *
     * 기본: 지정가 (최유리 호가) - 슬리피지 최소화
     * 시장가 사용 조건:
     * 1. 고변동성: 1분 변동성 > 1.5% (빠른 체결 필요)
     * 2. 강한 신호: 신뢰도 > 85% (확실한 기회)
     * 3. 얇은 호가창: 유동성 배율 < 5 (어차피 슬리피지 발생)
     * 4. 초단기 전략: ORDER_BOOK_IMBALANCE, MOMENTUM 등 (속도가 생명)
     * 5. 호가창 불균형이 주문 방향과 일치 (퀀트 최적화)
     */
    private fun determineOrderType(
        signal: TradingSignal,
        marketCondition: MarketConditionResult
    ): OrderType {
        val reasons = mutableListOf<String>()

        // 1. 초단기/모멘텀 전략은 시장가 사용
        if (signal.strategy.uppercase() in TradingConstants.MARKET_ORDER_STRATEGIES) {
            reasons.add("전략(${signal.strategy})")
            log.info("[${signal.market}] 시장가 선택: ${reasons.joinToString(", ")}")
            return OrderType.MARKET
        }

        // 2. 고변동성 시장 - 빠른 체결 필요
        val volatility = marketCondition.volatility1Min ?: 0.0
        if (volatility > TradingConstants.HIGH_VOLATILITY_THRESHOLD) {
            reasons.add("고변동성(${String.format("%.2f", volatility)}%)")
        }

        // 3. 강한 신뢰도 신호 - 기회를 놓치면 안 됨
        if (signal.confidence > TradingConstants.HIGH_CONFIDENCE_THRESHOLD) {
            reasons.add("고신뢰도(${String.format("%.1f", signal.confidence)}%)")
        }

        // 4. 얇은 호가창 - 지정가로 해도 슬리피지 발생
        val liquidity = marketCondition.liquidityRatio ?: Double.MAX_VALUE
        if (liquidity < TradingConstants.THIN_LIQUIDITY_THRESHOLD) {
            reasons.add("얇은호가(${String.format("%.1f", liquidity)}x)")
        }

        // 5. 호가창 불균형이 주문 방향과 일치 (퀀트 최적화)
        // - 매수 시 Imbalance > 0.3: 매수 압력 강함 → 시장가로 빠르게 체결
        // - 매도 시 Imbalance < -0.3: 매도 압력 강함 → 시장가로 빠르게 체결
        val imbalance = marketCondition.orderBookImbalance
        val isImbalanceFavorable = when (signal.action) {
            SignalAction.BUY -> imbalance > 0.3   // 매수 압력 강함 = 가격 상승 예상
            SignalAction.SELL -> imbalance < -0.3 // 매도 압력 강함 = 가격 하락 예상
            else -> false
        }
        if (isImbalanceFavorable) {
            reasons.add("호가불균형(${String.format("%.2f", imbalance)})")
        }

        // 2개 이상 조건 충족 시 시장가
        if (reasons.size >= 2) {
            log.info("[${signal.market}] 시장가 선택: ${reasons.joinToString(", ")}")
            return OrderType.MARKET
        }

        // 기본: 지정가 (최유리 호가)
        log.info("[${signal.market}] 지정가 선택: 기본 전략 (슬리피지 최소화)")
        return OrderType.LIMIT
    }

    /**
     * 주문 제출 (시장가/지정가 분기)
     *
     * 급등 시장 방어: 시장가 실패 시 지정가로 폴백
     * [버그 수정] 매수/매도 모두 폴백 적용, 폴백 결과 누락 수정
     */
    private fun submitOrder(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        orderType: OrderType,
        marketCondition: MarketConditionResult
    ): OrderSubmitResult {
        return when (orderType) {
            OrderType.LIMIT -> submitLimitOrder(signal, apiMarket, positionSize, marketCondition)
            OrderType.MARKET -> submitMarketOrderWithLimitFallback(signal, apiMarket, positionSize, marketCondition)
        }
    }

    private fun submitMarketOrderWithLimitFallback(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult
    ): OrderSubmitResult {
        val result = submitMarketOrder(signal, apiMarket, positionSize)
        if (result.orderResponse != null) {
            return result
        }

        log.warn("[${signal.market}] 시장가 ${signal.action} 실패, 지정가로 폴백")
        return submitLimitOrder(signal, apiMarket, positionSize, marketCondition)
    }

    /**
     * 지정가 주문 제출 (최유리 호가)
     *
     * 20년차 퀀트의 지정가 주문 전략:
     * 1. 매수: 최우선 매도호가(ask)로 지정가 → 즉시 체결 기대
     * 2. 매도: 최우선 매수호가(bid)로 지정가 → 즉시 체결 기대
     * 3. 빠른 체결 확인 (500ms x 2회)
     * 4. 미체결 시 PendingOrderManager에 등록 → 비동기 처리
     */
    private fun submitLimitOrder(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult
    ): OrderSubmitResult {
        val bestQuote = resolveBestQuoteForLimit(marketCondition)
        if (bestQuote == null) {
            log.warn("[${signal.market}] 호가 정보 없음, 시장가로 전환")
            return submitMarketOrder(signal, apiMarket, positionSize)
        }

        val placement = placeLimitOrder(signal, apiMarket, positionSize, bestQuote)
            ?: return OrderSubmitResult(null, OrderType.LIMIT, null, null)

        val quickFillResult = checkQuickFill(signal.market, placement.orderResponse.uuid)

        if (quickFillResult != null && quickFillResult.state == TradingConstants.ORDER_STATE_DONE) {
            log.info("[${signal.market}] 지정가 주문 즉시 체결됨")
            return OrderSubmitResult(quickFillResult, OrderType.LIMIT, placement.limitPrice, placement.quantity)
        }

        return registerPendingLimitOrder(
            signal = signal,
            positionSize = positionSize,
            marketCondition = marketCondition,
            placement = placement,
            quickFillResult = quickFillResult
        )
    }

    private data class LimitOrderPlacement(
        val orderResponse: OrderResponse,
        val limitPrice: BigDecimal,
        val quantity: BigDecimal
    )

    private fun resolveBestQuoteForLimit(marketCondition: MarketConditionResult): Pair<BigDecimal, BigDecimal>? {
        val bestAsk = marketCondition.bestAsk ?: return null
        val bestBid = marketCondition.bestBid ?: return null
        return bestAsk to bestBid
    }

    private fun placeLimitOrder(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        bestQuote: Pair<BigDecimal, BigDecimal>
    ): LimitOrderPlacement? {
        val (bestAsk, bestBid) = bestQuote
        return withMarketUnavailableGuard(signal) {
            when (signal.action) {
                SignalAction.BUY -> placeLimitBuy(signal, apiMarket, positionSize, bestAsk)
                SignalAction.SELL -> placeLimitSell(signal, apiMarket, positionSize, bestBid)
                SignalAction.HOLD -> null
            }
        }
    }

    private fun placeLimitBuy(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        bestAsk: BigDecimal
    ): LimitOrderPlacement {
        val limitPrice = bestAsk
        val quantity = positionSize.divide(limitPrice, 8, RoundingMode.DOWN)
        log.info("[${signal.market}] 지정가 매수: 가격=$limitPrice, 수량=$quantity")
        val orderResponse = bithumbPrivateApi.buyLimitOrder(apiMarket, limitPrice, quantity)
        return LimitOrderPlacement(orderResponse, limitPrice, quantity)
    }

    private fun placeLimitSell(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal,
        bestBid: BigDecimal
    ): LimitOrderPlacement {
        val limitPrice = bestBid
        val quantity = calculateQuantity(limitPrice, positionSize)
        log.info("[${signal.market}] 지정가 매도: 가격=$limitPrice, 수량=$quantity")
        val orderResponse = bithumbPrivateApi.sellLimitOrder(apiMarket, limitPrice, quantity)
        return LimitOrderPlacement(orderResponse, limitPrice, quantity)
    }

    private fun registerPendingLimitOrder(
        signal: TradingSignal,
        positionSize: BigDecimal,
        marketCondition: MarketConditionResult,
        placement: LimitOrderPlacement,
        quickFillResult: OrderResponse?
    ): OrderSubmitResult {
        log.info("[${signal.market}] 지정가 주문 미체결, PendingOrderManager에 등록")

        val pendingOrder = pendingOrderManager.registerPendingOrder(
            orderId = placement.orderResponse.uuid,
            signal = signal,
            orderPrice = placement.limitPrice,
            orderQuantity = placement.quantity,
            orderAmountKrw = positionSize,
            marketCondition = marketCondition
        )

        return OrderSubmitResult(
            orderResponse = quickFillResult ?: placement.orderResponse,
            orderType = OrderType.LIMIT,
            limitPrice = placement.limitPrice,
            quantity = placement.quantity,
            isPending = true,
            pendingOrderId = pendingOrder.id
        )
    }

    /**
     * 빠른 체결 확인 (지정가 주문 직후)
     *
     * 최유리 호가로 주문했으므로 대부분 즉시 체결됨.
     * 체결되지 않았다면 시장이 움직인 것이므로 PendingOrderManager에 위임.
     */
    private fun checkQuickFill(market: String, orderId: String): OrderResponse? {
        repeat(QUICK_FILL_CHECK_RETRIES) { attempt ->
            Thread.sleep(QUICK_FILL_CHECK_MS)

            try {
                val response = bithumbPrivateApi.getOrder(orderId)

                if (response == null) {
                    log.warn("[$market] 빠른 체결 확인 실패 (시도 ${attempt + 1})")
                    return@repeat
                }

                val fillRate = calculateFillRate(response)

                when {
                    response.state == TradingConstants.ORDER_STATE_DONE -> {
                        log.info("[$market] 빠른 체결 확인: 완전 체결")
                        return response
                    }
                    fillRate >= TradingConstants.PARTIAL_FILL_SUCCESS_THRESHOLD -> {
                        log.info("[$market] 빠른 체결 확인: ${String.format("%.1f", fillRate * 100)}% 체결 (충분)")
                        return response
                    }
                    fillRate > 0 -> {
                        log.info("[$market] 빠른 체결 확인: ${String.format("%.1f", fillRate * 100)}% 부분 체결 중")
                    }
                }
            } catch (e: Exception) {
                log.warn("[$market] 빠른 체결 확인 예외 (시도 ${attempt + 1}): ${e.message}")
            }
        }

        return null
    }

    /**
     * 시장가 주문 제출
     *
     * 매도 시 실제 잔고를 확인하여 잔고 부족 에러 방지
     */
    private fun submitMarketOrder(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal
    ): OrderSubmitResult {
        val response = withMarketUnavailableGuard(signal) {
            when (signal.action) {
                SignalAction.BUY -> {
                    log.info("[${signal.market}] 시장가 매수: $positionSize KRW")
                    bithumbPrivateApi.buyMarketOrder(apiMarket, positionSize)
                }
                SignalAction.SELL -> {
                    // 실제 잔고 확인 후 매도 수량 결정
                    val priceForSizing = resolveSellSizingPrice(signal, positionSize)
                    val requestedQuantity = calculateQuantity(priceForSizing, positionSize)
                    val actualQuantity = getActualSellQuantity(signal.market, requestedQuantity)

                    if (actualQuantity <= BigDecimal.ZERO) {
                        log.warn("[${signal.market}] 매도 가능 잔고 없음")
                        return OrderSubmitResult(null, OrderType.MARKET, null, null)
                    }

                    log.info("[${signal.market}] 시장가 매도: 요청수량=$requestedQuantity, 실제수량=$actualQuantity")
                    bithumbPrivateApi.sellMarketOrder(apiMarket, actualQuantity)
                }
                SignalAction.HOLD -> null
            }
        }

        return OrderSubmitResult(response, OrderType.MARKET, null, null)
    }

    private inline fun <T> withMarketUnavailableGuard(signal: TradingSignal, block: () -> T): T {
        return try {
            block()
        } catch (e: BithumbApiException) {
            if (e.isMarketUnavailable()) {
                log.error("[${signal.market}] 거래 정지 코인 감지: ${e.errorMessage}")
                throw MarketSuspendedException(signal.market, e.errorMessage ?: "거래가 지원되지 않습니다")
            }
            throw e
        }
    }

    private fun resolveSellSizingPrice(signal: TradingSignal, positionSize: BigDecimal): BigDecimal {
        if (signal.price > BigDecimal.ZERO) return signal.price

        val midPrice = resolveMidPriceOrNull(signal, positionSize) { e ->
            log.warn("[${signal.market}] 매도 수량 산정용 가격 조회 실패: ${e.message}")
        }
        return midPrice ?: BigDecimal.ONE
    }

    /**
     * 실제 매도 가능 수량 확인
     *
     * 잔고가 요청 수량보다 적으면 전량 매도, 잔고가 없으면 0 반환
     */
    private fun getActualSellQuantity(market: String, requestedQuantity: BigDecimal): BigDecimal {
        return try {
            val coinSymbol = PositionHelper.extractCoinSymbol(market)
            val balances = bithumbPrivateApi.getBalances() ?: return requestedQuantity

            val coinBalance = balances.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO

            when {
                coinBalance <= BigDecimal.ZERO -> {
                    log.warn("[$market] 코인 잔고 없음: $coinSymbol = 0")
                    BigDecimal.ZERO
                }
                coinBalance < requestedQuantity -> {
                    log.info("[$market] 잔고 부족, 전량 매도: 요청=${requestedQuantity}, 잔고=${coinBalance}")
                    coinBalance
                }
                else -> requestedQuantity
            }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, 요청 수량 그대로 사용: ${e.message}")
            requestedQuantity
        }
    }

    /**
     * 주문 상태 확인 (재시도 포함)
     */
    private fun verifyOrderExecution(orderId: String, market: String): OrderResponse? {
        var lastResponse: OrderResponse? = null
        var delayMs = STATUS_CHECK_INITIAL_DELAY_MS

        for (attempt in 1..MAX_STATUS_CHECK_RETRIES) {
            Thread.sleep(delayMs)

            try {
                val response = bithumbPrivateApi.getOrder(orderId)

                if (response == null) {
                    log.warn("[$market] 주문 조회 실패 (시도 $attempt/$MAX_STATUS_CHECK_RETRIES)")
                    delayMs = nextStatusCheckDelay(delayMs)
                    continue
                }

                lastResponse = response
                if (shouldReturnVerifiedOrder(market, orderId, response, attempt)) {
                    return response
                }
                delayMs = nextStatusCheckDelay(delayMs)

            } catch (e: Exception) {
                log.error("[$market] 주문 조회 예외 (시도 $attempt/$MAX_STATUS_CHECK_RETRIES): ${e.message}")
                delayMs = nextStatusCheckDelay(delayMs)
            }
        }

        // 최대 재시도 후에도 완료 확인 안 됨
        log.warn("[$market] 주문 상태 확인 타임아웃: orderId=$orderId, lastState=${lastResponse?.state}")

        // 부분 체결이라도 있으면 해당 응답 반환
        return lastResponse
    }

    private fun shouldReturnVerifiedOrder(
        market: String,
        orderId: String,
        response: OrderResponse,
        attempt: Int
    ): Boolean {
        return when (response.state) {
            TradingConstants.ORDER_STATE_DONE -> {
                log.info("[$market] 주문 완료 확인: orderId=$orderId")
                true
            }
            TradingConstants.ORDER_STATE_CANCEL -> {
                log.warn("[$market] 주문 취소됨: orderId=$orderId")
                true
            }
            TradingConstants.ORDER_STATE_WAIT -> {
                handleWaitingOrderState(market, response, attempt)
            }
            else -> {
                log.warn("[$market] 알 수 없는 주문 상태: ${response.state}")
                false
            }
        }
    }

    private fun handleWaitingOrderState(
        market: String,
        response: OrderResponse,
        attempt: Int
    ): Boolean {
        val fillRate = calculateFillRate(response)
        if (fillRate <= 0.0) {
            return false
        }

        log.info(
            "[$market] 부분 체결 중: ${String.format("%.1f", fillRate * 100)}% " +
                "(시도 $attempt/$MAX_STATUS_CHECK_RETRIES)"
        )
        if (fillRate >= TradingConstants.PARTIAL_FILL_SUCCESS_THRESHOLD) {
            log.info("[$market] 부분 체결 임계값 초과 - 성공으로 처리")
            return true
        }
        return false
    }

    private fun nextStatusCheckDelay(currentDelayMs: Long): Long {
        return minOf(currentDelayMs * 2, STATUS_CHECK_MAX_DELAY_MS)
    }

    /**
     * 체결 결과 분석
     */
    private fun analyzeExecution(
        signal: TradingSignal,
        order: OrderResponse,
        positionSize: BigDecimal,
        midPrice: BigDecimal?,
        orderType: OrderType = OrderType.MARKET
    ): OrderResult {
        val market = signal.market
        val side = signal.toOrderSide()

        val executedVolume = order.executedVolume ?: BigDecimal.ZERO
        val totalVolume = order.volume ?: BigDecimal.ONE
        val paidFee = order.paidFee ?: BigDecimal.ZERO

        if (executedVolume <= BigDecimal.ZERO) {
            return createNoFillResult(order, market, side)
        }

        val isPartialFill = executedVolume < totalVolume
        val fillRatePercent = calculateFillRatePercent(executedVolume, totalVolume)
        val executedPrice = resolveExecutedPrice(signal, order, positionSize, executedVolume)
        val referencePrice = midPrice ?: signal.price
        val slippagePercent = calculateSlippagePercent(signal, executedPrice, referencePrice)

        notifySlippageIfNeeded(market, slippagePercent, referencePrice, executedPrice)
        warnPartialFillIfNeeded(market, isPartialFill, fillRatePercent)

        val message = buildExecutionSuccessMessage(isPartialFill, fillRatePercent, slippagePercent)
        logExecutionAnalysis(market, order.uuid, executedPrice, executedVolume, totalVolume, paidFee, slippagePercent)

        return OrderResult(
            success = true,
            orderId = order.uuid,
            market = market,
            side = side,
            price = executedPrice,
            quantity = totalVolume,
            executedQuantity = executedVolume,
            fee = paidFee,
            slippagePercent = slippagePercent,
            isPartialFill = isPartialFill,
            fillRatePercent = fillRatePercent,
            message = message,
            isSimulated = false,
            orderType = orderType
        )
    }

    private fun createNoFillResult(order: OrderResponse, market: String, side: OrderSide): OrderResult {
        return OrderResult(
            success = false,
            orderId = order.uuid,
            market = market,
            side = side,
            message = "체결 없음: state=${order.state}",
            rejectionReason = OrderRejectionReason.NO_FILL
        )
    }

    private fun calculateFillRatePercent(
        executedVolume: BigDecimal,
        totalVolume: BigDecimal
    ): Double {
        return calculateFillRate(executedVolume, totalVolume) * 100
    }

    private fun resolveExecutedPrice(
        signal: TradingSignal,
        order: OrderResponse,
        positionSize: BigDecimal,
        executedVolume: BigDecimal
    ): BigDecimal {
        if (signal.action != SignalAction.BUY) {
            return order.price ?: signal.price
        }

        val invested = resolveInvestedAmount(signal, order, positionSize)
        return invested.divide(executedVolume, 8, RoundingMode.HALF_UP)
    }

    private fun resolveInvestedAmount(
        signal: TradingSignal,
        order: OrderResponse,
        positionSize: BigDecimal
    ): BigDecimal {
        val locked = order.locked
        if (locked != null && locked > BigDecimal.ZERO) {
            return locked
        }
        log.debug("[${signal.market}] locked가 0 또는 null - positionSize($positionSize) 사용")
        return positionSize
    }

    private fun calculateSlippagePercent(
        signal: TradingSignal,
        executedPrice: BigDecimal,
        referencePrice: BigDecimal
    ): Double {
        if (referencePrice <= BigDecimal.ZERO) {
            return 0.0
        }

        val slippage = when (signal.action) {
            SignalAction.BUY -> executedPrice.subtract(referencePrice)
            SignalAction.SELL -> referencePrice.subtract(executedPrice)
            SignalAction.HOLD -> BigDecimal.ZERO
        }
        return slippage.divide(referencePrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()
    }

    private fun notifySlippageIfNeeded(
        market: String,
        slippagePercent: Double,
        referencePrice: BigDecimal,
        executedPrice: BigDecimal
    ) {
        if (slippagePercent > TradingConstants.SLIPPAGE_CRITICAL_PERCENT) {
            log.error("[$market] 크리티컬 슬리피지: ${String.format("%.3f", slippagePercent)}%")
            slackNotifier.sendError(market, """
                슬리피지 경고 (CRITICAL)

                슬리피지: ${String.format("%.3f", slippagePercent)}%
                기준가: $referencePrice
                체결가: $executedPrice

                시장 유동성 확인 필요
            """.trimIndent())
            return
        }
        if (slippagePercent > TradingConstants.SLIPPAGE_WARNING_PERCENT) {
            log.warn("[$market] 슬리피지 경고: ${String.format("%.3f", slippagePercent)}%")
        }
    }

    private fun warnPartialFillIfNeeded(
        market: String,
        isPartialFill: Boolean,
        fillRatePercent: Double
    ) {
        if (!isPartialFill) {
            return
        }
        log.warn("[$market] 부분 체결: ${String.format("%.1f", fillRatePercent)}% 체결됨")
    }

    private fun buildExecutionSuccessMessage(
        isPartialFill: Boolean,
        fillRatePercent: Double,
        slippagePercent: Double
    ): String {
        return buildString {
            append("체결 완료")
            if (isPartialFill) {
                append(" (부분: ${String.format("%.1f", fillRatePercent)}%)")
            }
            if (slippagePercent > TradingConstants.SLIPPAGE_WARNING_PERCENT) {
                append(" [슬리피지: ${String.format("%.2f", slippagePercent)}%]")
            }
        }
    }

    private fun logExecutionAnalysis(
        market: String,
        orderId: String,
        executedPrice: BigDecimal,
        executedVolume: BigDecimal,
        totalVolume: BigDecimal,
        paidFee: BigDecimal,
        slippagePercent: Double
    ) {
        log.info("""
            [$market] 체결 분석 완료
            주문ID: $orderId
            체결가: $executedPrice
            체결량: $executedVolume / $totalVolume
            수수료: $paidFee
            슬리피지: ${String.format("%.3f", slippagePercent)}%
        """.trimIndent())
    }

    /**
     * 거래 기록 저장
     *
     * SELL 거래 시 마지막 BUY 거래를 찾아 PnL 계산
     */
    private fun saveTradeRecord(
        signal: TradingSignal,
        positionSize: BigDecimal,
        orderId: String,
        executedPrice: BigDecimal?,
        executedQuantity: BigDecimal?,
        fee: BigDecimal,
        slippagePercent: Double,
        isSimulated: Boolean,
        isPartialFill: Boolean,
        orderType: OrderType = OrderType.MARKET
    ) {
        try {
            val isSell = signal.action == SignalAction.SELL
            val tradePrice = resolveTradePriceOrNull(signal, positionSize, executedPrice, orderId) ?: return
            val sellQuantity = (executedQuantity ?: calculateQuantity(signal.price, positionSize)).toDouble()
            val sellFee = fee.toDouble()
            val pnlSnapshot = calculateTradePnlSnapshot(
                signal = signal,
                isSell = isSell,
                tradePrice = tradePrice,
                sellQuantity = sellQuantity,
                sellFee = sellFee,
                isSimulated = isSimulated
            )

            val entity = TradeEntity(
                orderId = orderId,
                market = signal.market,
                side = if (isSell) "SELL" else "BUY",
                type = orderType.name,
                price = tradePrice,
                quantity = sellQuantity,
                totalAmount = positionSize.toDouble(),
                fee = sellFee,
                slippage = slippagePercent,
                isPartialFill = isPartialFill,
                pnl = pnlSnapshot.pnlAmount,
                pnlPercent = pnlSnapshot.pnlPercent,
                strategy = signal.strategy,
                regime = signal.regime,
                confidence = signal.confidence,
                reason = signal.reason.take(500),
                simulated = isSimulated
            )

            tradeRepository.save(entity)
            log.debug(
                "[${signal.market}] 거래 기록 저장: orderId=$orderId, type=$orderType, pnl=${pnlSnapshot.pnlAmount}"
            )

        } catch (e: Exception) {
            log.error("[${signal.market}] 거래 기록 저장 실패: ${e.message}", e)
            // 기록 실패는 거래 실패로 처리하지 않음 (best effort)
        }
    }

    private fun resolveTradePriceOrNull(
        signal: TradingSignal,
        positionSize: BigDecimal,
        executedPrice: BigDecimal?,
        orderId: String
    ): Double? {
        var tradePrice = (executedPrice ?: signal.price).toDouble()
        if (tradePrice > 0) {
            return tradePrice
        }

        log.warn("[${signal.market}] 거래 가격이 0 - executedPrice=${executedPrice}, signal.price=${signal.price}")
        tradePrice = resolveFallbackTradePrice(signal, positionSize)
        if (tradePrice > 0) {
            return tradePrice
        }

        log.error("[${signal.market}] 유효하지 않은 거래 가격 - orderId=$orderId, strategy=${signal.strategy}")
        log.error("[${signal.market}] 거래 기록 저장 스킵 (price=0 방지)")
        return null
    }

    private fun resolveFallbackTradePrice(signal: TradingSignal, positionSize: BigDecimal): Double {
        val midPrice = resolveMidPriceOrNull(signal, positionSize) { e ->
            log.error("[${signal.market}] 시장 상태 조회 실패: ${e.message}")
        }
        return midPrice?.toDouble()?.also { resolvedPrice ->
            log.info("[${signal.market}] 시장 중간가로 대체: ${resolvedPrice}")
        } ?: 0.0
    }

    private fun resolveMidPriceOrNull(
        signal: TradingSignal,
        positionSize: BigDecimal,
        onFailure: (Exception) -> Unit
    ): BigDecimal? {
        return try {
            marketConditionChecker.checkMarketCondition(signal.market, positionSize)
                .midPrice
                ?.takeIf { it > BigDecimal.ZERO }
        } catch (e: Exception) {
            onFailure(e)
            null
        }
    }

    private fun calculateTradePnlSnapshot(
        signal: TradingSignal,
        isSell: Boolean,
        tradePrice: Double,
        sellQuantity: Double,
        sellFee: Double,
        isSimulated: Boolean
    ): TradePnlSnapshot {
        if (!isSell) {
            return TradePnlSnapshot(pnlAmount = null, pnlPercent = null)
        }

        val pnlResult = calculateSellPnlFromOpenLots(
            market = signal.market,
            sellPrice = tradePrice,
            sellQuantity = sellQuantity,
            sellFee = sellFee,
            isSimulated = isSimulated
        ) ?: run {
            log.warn("[${signal.market}] 오픈 BUY 수량 없음 - PnL 계산 불가")
            return TradePnlSnapshot(pnlAmount = null, pnlPercent = null)
        }

        log.info(
            "[${signal.market}] PnL 계산 완료(FIFO): " +
                "매도가=${tradePrice}, PnL=${String.format("%.0f", pnlResult.pnlAmount)}원 " +
                "(${String.format("%.2f", pnlResult.pnlPercent)}%)"
        )
        circuitBreaker.recordTradeResult(signal.market, pnlResult.pnlPercent)
        return TradePnlSnapshot(
            pnlAmount = pnlResult.pnlAmount,
            pnlPercent = pnlResult.pnlPercent
        )
    }

    /**
     * 수량 계산
     */
    private fun calculateQuantity(price: BigDecimal, amount: BigDecimal): BigDecimal {
        return if (price > BigDecimal.ZERO) {
            amount.divide(price, 8, RoundingMode.DOWN)
        } else {
            BigDecimal.ZERO
        }
    }

    /**
     * 체결률 계산
     */
    private fun calculateFillRate(response: OrderResponse): Double {
        return calculateFillRate(
            executedVolume = response.executedVolume ?: BigDecimal.ZERO,
            totalVolume = response.volume ?: BigDecimal.ONE
        )
    }

    private fun calculateFillRate(executedVolume: BigDecimal, totalVolume: BigDecimal): Double {
        if (totalVolume <= BigDecimal.ZERO) return 0.0
        return executedVolume.divide(totalVolume, 4, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * SELL 체결의 실현손익 계산 (FIFO 원가 기준)
     */
    private fun calculateSellPnlFromOpenLots(
        market: String,
        sellPrice: Double,
        sellQuantity: Double,
        sellFee: Double,
        isSimulated: Boolean
    ): PnlCalculator.PnlResult? {
        if (sellQuantity <= 0.0 || sellPrice <= 0.0) return null

        val openLots = buildOpenLotsFromTradeHistory(market, isSimulated)
        val matchedSummary = matchCurrentSellAgainstOpenLots(openLots, sellQuantity)
        if (matchedSummary.matchedQty <= 0.0) return null

        val weightedEntryPrice = matchedSummary.entryAmount / matchedSummary.matchedQty
        val matchedExitFee = if (sellQuantity > 0.0) sellFee * (matchedSummary.matchedQty / sellQuantity) else 0.0
        return PnlCalculator.calculate(
            entryPrice = weightedEntryPrice,
            exitPrice = sellPrice,
            quantity = matchedSummary.matchedQty,
            entryFee = matchedSummary.entryFee,
            exitFee = matchedExitFee
        )
    }

    private fun buildOpenLotsFromTradeHistory(
        market: String,
        isSimulated: Boolean
    ): MutableList<OpenLot> {
        val records = tradeRepository.findByMarketAndSimulatedOrderByCreatedAtDesc(market, isSimulated).asReversed()
        val openLots = mutableListOf<OpenLot>()

        records.forEach { trade ->
            val qty = trade.quantity
            if (qty <= 0.0) return@forEach

            when (trade.side.uppercase()) {
                "BUY" -> openLots.add(toOpenLot(trade.price, qty, trade.fee))
                "SELL" -> applyHistoricalSellToOpenLots(openLots, qty)
            }
        }

        return openLots
    }

    private fun toOpenLot(price: Double, quantity: Double, fee: Double): OpenLot {
        val feePerUnit = if (quantity > 0.0) fee / quantity else 0.0
        return OpenLot(price = price, quantity = quantity, feePerUnit = feePerUnit)
    }

    private fun applyHistoricalSellToOpenLots(openLots: MutableList<OpenLot>, sellQuantity: Double) {
        var remainToMatch = sellQuantity
        var lotIndex = 0
        while (remainToMatch > 0.0 && lotIndex < openLots.size) {
            val lot = openLots[lotIndex]
            val consumed = minOf(lot.quantity, remainToMatch)
            lot.quantity -= consumed
            remainToMatch -= consumed
            if (lot.quantity <= 0.0) {
                openLots.removeAt(lotIndex)
            } else {
                lotIndex++
            }
        }
    }

    private fun matchCurrentSellAgainstOpenLots(
        openLots: List<OpenLot>,
        sellQuantity: Double
    ): MatchedLotSummary {
        var remainSellQty = sellQuantity
        var matchedQty = 0.0
        var entryAmount = 0.0
        var entryFee = 0.0
        var lotIndex = 0

        while (remainSellQty > 0.0 && lotIndex < openLots.size) {
            val lot = openLots[lotIndex]
            if (lot.quantity <= 0.0) {
                lotIndex++
                continue
            }

            val consumed = minOf(lot.quantity, remainSellQty)
            matchedQty += consumed
            entryAmount += lot.price * consumed
            entryFee += lot.feePerUnit * consumed
            remainSellQty -= consumed
            lotIndex++
        }

        return MatchedLotSummary(
            matchedQty = matchedQty,
            entryAmount = entryAmount,
            entryFee = entryFee
        )
    }

}

/**
 * 주문 제출 결과 (내부용)
 */
data class OrderSubmitResult(
    val orderResponse: OrderResponse?,
    val orderType: OrderType,
    val limitPrice: BigDecimal?,
    val quantity: BigDecimal?,
    val isPending: Boolean = false,
    val pendingOrderId: Long? = null
)

/**
 * 주문 결과
 */
data class OrderResult(
    val success: Boolean,
    val orderId: String? = null,
    val market: String,
    val side: OrderSide,
    val price: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val executedQuantity: BigDecimal? = null,
    val fee: BigDecimal? = null,
    val slippagePercent: Double = 0.0,
    val isPartialFill: Boolean = false,
    val fillRatePercent: Double = 100.0,
    val message: String,
    val isSimulated: Boolean = false,
    val isPending: Boolean = false,
    val pendingOrderId: Long? = null,
    val rejectionReason: OrderRejectionReason? = null,
    val orderType: OrderType = OrderType.MARKET,
    val timestamp: Instant = Instant.now()
) {
    /**
     * 실제 투입 금액 (체결가 * 체결량 + 수수료)
     */
    val totalCost: BigDecimal?
        get() = if (price != null && executedQuantity != null) {
            price.multiply(executedQuantity).add(fee ?: BigDecimal.ZERO)
        } else null

    /**
     * 슬리피지로 인한 예상 손실 (KRW)
     */
    val slippageCostKrw: BigDecimal?
        get() = if (totalCost != null && slippagePercent > 0) {
            totalCost!!.multiply(BigDecimal(slippagePercent / 100))
        } else null
}

/**
 * 주문 거부 사유
 */
enum class OrderRejectionReason {
    MARKET_CONDITION,       // 시장 상태 불량 (스프레드, 유동성, 변동성)
    API_ERROR,              // API 응답 없음
    VERIFICATION_FAILED,    // 주문 상태 확인 실패
    NO_FILL,                // 체결 없음
    EXCEPTION,              // 예외 발생
    CIRCUIT_BREAKER,        // 서킷 브레이커 발동
    BELOW_MIN_ORDER_AMOUNT, // 최소 주문 금액 미달 (빗썸 5000원)
    MARKET_SUSPENDED        // 거래 정지/상장 폐지 코인
}

/**
 * 거래 정지 코인 예외
 */
class MarketSuspendedException(
    val market: String,
    message: String
) : RuntimeException("[$market] $message")
