package com.ant.cointrading.order

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.risk.MarketConditionResult
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
    private val slackNotifier: SlackNotifier,
    private val pendingOrderManager: PendingOrderManager
) {

    private val log = LoggerFactory.getLogger(OrderExecutor::class.java)

    companion object {
        // 주문 상태 확인 설정
        const val MAX_STATUS_CHECK_RETRIES = 3
        const val STATUS_CHECK_INITIAL_DELAY_MS = 500L
        const val STATUS_CHECK_MAX_DELAY_MS = 2000L

        // 지정가 주문 설정
        const val QUICK_FILL_CHECK_MS = 500L            // 빠른 체결 확인 대기 시간
        const val QUICK_FILL_CHECK_RETRIES = 2          // 빠른 확인 재시도 횟수

        // 슬리피지 경고 임계값
        const val SLIPPAGE_WARNING_PERCENT = 0.5
        const val SLIPPAGE_CRITICAL_PERCENT = 2.0

        // 부분 체결 임계값 (90% 이상 체결이면 성공으로 간주)
        const val PARTIAL_FILL_SUCCESS_THRESHOLD = 0.9

        // 주문 상태
        const val ORDER_STATE_DONE = "done"
        const val ORDER_STATE_WAIT = "wait"
        const val ORDER_STATE_CANCEL = "cancel"

        // 시장가 주문 사용 조건 (퀀트 지식 기반)
        const val HIGH_VOLATILITY_THRESHOLD = 1.5      // 1분 변동성 1.5% 초과 시 시장가
        const val HIGH_CONFIDENCE_THRESHOLD = 85.0     // 신뢰도 85% 초과 시 시장가
        const val THIN_LIQUIDITY_THRESHOLD = 5.0       // 유동성 배율 5 미만 시 시장가

        // 시장가를 선호하는 전략들 (빠른 체결이 중요)
        val MARKET_ORDER_STRATEGIES = setOf(
            "ORDER_BOOK_IMBALANCE",  // 초단기 전략
            "MOMENTUM",              // 모멘텀 전략
            "BREAKOUT",              // 돌파 전략
            "MEME_SCALPER"           // 세력 코인 초단타
        )

        // 빗썸 최소 주문 금액 (KRW) - 수수료(0.04%) 고려하여 여유 있게 설정
        val MIN_ORDER_AMOUNT_KRW = BigDecimal("5100")
    }

    /**
     * 주문 실행 (메인 진입점)
     */
    fun execute(signal: TradingSignal, positionSize: BigDecimal): OrderResult {
        val side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL

        // 0. 최소 주문 금액 검증 (빗썸 5000원)
        if (positionSize < MIN_ORDER_AMOUNT_KRW) {
            log.warn("[${signal.market}] 최소 주문 금액 미달: ${positionSize}원 < ${MIN_ORDER_AMOUNT_KRW}원")
            return OrderResult(
                success = false,
                market = signal.market,
                side = side,
                message = "최소 주문 금액(${MIN_ORDER_AMOUNT_KRW}원) 미달: ${positionSize}원",
                rejectionReason = OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT
            )
        }

        // 1. 시뮬레이션 모드 체크
        if (!tradingProperties.enabled) {
            return executeSimulated(signal, positionSize)
        }

        // 2. 시장 상태 검사 (실거래 전 필수)
        val marketCondition = marketConditionChecker.checkMarketCondition(signal.market, positionSize)
        if (!marketCondition.canTrade) {
            log.warn("[${signal.market}] 시장 상태 불량으로 주문 거부: ${marketCondition.issues}")
            return OrderResult(
                success = false,
                market = signal.market,
                side = side,
                message = "시장 상태 불량: ${marketCondition.issues.joinToString(", ")}",
                rejectionReason = OrderRejectionReason.MARKET_CONDITION
            )
        }

        // 3. 실제 주문 실행 (시장 상태 기반 주문 유형 결정)
        return executeReal(signal, positionSize, marketCondition)
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
        val apiMarket = convertToApiMarket(market)
        val midPrice = marketCondition.midPrice
        val side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL

        return try {
            // 1. 주문 유형 결정 (퀀트 지식 기반)
            val orderType = determineOrderType(signal, marketCondition)

            // 2. 주문 제출
            val submitResult = submitOrder(signal, apiMarket, positionSize, orderType, marketCondition)

            if (submitResult.orderResponse == null) {
                circuitBreaker.recordExecutionFailure(market, "주문 응답 null")
                return OrderResult(
                    success = false,
                    market = market,
                    side = side,
                    message = "주문 응답 없음 - API 장애 의심",
                    rejectionReason = OrderRejectionReason.API_ERROR
                )
            }

            val orderId = submitResult.orderResponse.uuid
            val actualOrderType = submitResult.orderType
            log.info("[$market] 주문 제출됨: orderId=$orderId, type=$actualOrderType")

            // 3. 미체결 상태로 PendingOrderManager에 위임된 경우
            if (submitResult.isPending) {
                log.info("[$market] 미체결 주문 PendingOrderManager에 위임됨: orderId=$orderId")

                // 현재까지의 부분 체결 정보 반환
                val response = submitResult.orderResponse
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
                    orderType = actualOrderType
                )
            }

            // 4. 시장가 또는 즉시 체결된 지정가 → 상태 확인
            val verifiedOrder = verifyOrderExecution(orderId, market)

            if (verifiedOrder == null) {
                circuitBreaker.recordExecutionFailure(market, "주문 상태 확인 실패")
                return OrderResult(
                    success = false,
                    orderId = orderId,
                    market = market,
                    side = side,
                    message = "주문 상태 확인 실패 - 수동 확인 필요",
                    rejectionReason = OrderRejectionReason.VERIFICATION_FAILED,
                    orderType = actualOrderType
                )
            }

            // 5. 체결 결과 분석
            val executionResult = analyzeExecution(signal, verifiedOrder, positionSize, midPrice, actualOrderType)

            // 6. CircuitBreaker에 결과 기록
            if (executionResult.success) {
                circuitBreaker.recordExecutionSuccess(market)
                if (executionResult.slippagePercent > 0) {
                    circuitBreaker.recordSlippage(market, executionResult.slippagePercent)
                }
            } else {
                circuitBreaker.recordExecutionFailure(market, executionResult.message)
            }

            // 7. 거래 기록 저장
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

            executionResult

        } catch (e: Exception) {
            log.error("[$market] 주문 실행 실패: ${e.message}", e)
            circuitBreaker.recordExecutionFailure(market, e.message ?: "Unknown error")

            OrderResult(
                success = false,
                market = market,
                side = side,
                message = "주문 실행 예외: ${e.message}",
                rejectionReason = OrderRejectionReason.EXCEPTION
            )
        }
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
        if (signal.strategy.uppercase() in MARKET_ORDER_STRATEGIES) {
            reasons.add("전략(${signal.strategy})")
            log.info("[${signal.market}] 시장가 선택: ${reasons.joinToString(", ")}")
            return OrderType.MARKET
        }

        // 2. 고변동성 시장 - 빠른 체결 필요
        val volatility = marketCondition.volatility1Min ?: 0.0
        if (volatility > HIGH_VOLATILITY_THRESHOLD) {
            reasons.add("고변동성(${String.format("%.2f", volatility)}%)")
        }

        // 3. 강한 신뢰도 신호 - 기회를 놓치면 안 됨
        if (signal.confidence > HIGH_CONFIDENCE_THRESHOLD) {
            reasons.add("고신뢰도(${String.format("%.1f", signal.confidence)}%)")
        }

        // 4. 얇은 호가창 - 지정가로 해도 슬리피지 발생
        val liquidity = marketCondition.liquidityRatio ?: Double.MAX_VALUE
        if (liquidity < THIN_LIQUIDITY_THRESHOLD) {
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
            OrderType.MARKET -> submitMarketOrder(signal, apiMarket, positionSize)
        }
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
        val bestAsk = marketCondition.bestAsk
        val bestBid = marketCondition.bestBid

        if (bestAsk == null || bestBid == null) {
            log.warn("[${signal.market}] 호가 정보 없음, 시장가로 전환")
            return submitMarketOrder(signal, apiMarket, positionSize)
        }

        // 1. 지정가 주문 제출
        val (orderResponse, limitPrice, quantity) = when (signal.action) {
            SignalAction.BUY -> {
                val limitPrice = bestAsk
                val quantity = positionSize.divide(limitPrice, 8, RoundingMode.DOWN)
                log.info("[${signal.market}] 지정가 매수: 가격=$limitPrice, 수량=$quantity")
                Triple(bithumbPrivateApi.buyLimitOrder(apiMarket, limitPrice, quantity), limitPrice, quantity)
            }
            SignalAction.SELL -> {
                val limitPrice = bestBid
                val quantity = calculateQuantity(signal.price, positionSize)
                log.info("[${signal.market}] 지정가 매도: 가격=$limitPrice, 수량=$quantity")
                Triple(bithumbPrivateApi.sellLimitOrder(apiMarket, limitPrice, quantity), limitPrice, quantity)
            }
            SignalAction.HOLD -> return OrderSubmitResult(null, OrderType.LIMIT, null, null)
        }

        if (orderResponse == null) {
            return OrderSubmitResult(null, OrderType.LIMIT, limitPrice, quantity)
        }

        // 2. 빠른 체결 확인 (500ms x 2회)
        val quickFillResult = checkQuickFill(signal.market, orderResponse.uuid)

        if (quickFillResult != null && quickFillResult.state == ORDER_STATE_DONE) {
            log.info("[${signal.market}] 지정가 주문 즉시 체결됨")
            return OrderSubmitResult(quickFillResult, OrderType.LIMIT, limitPrice, quantity)
        }

        // 3. 미체결 → PendingOrderManager에 등록 (비동기 처리)
        log.info("[${signal.market}] 지정가 주문 미체결, PendingOrderManager에 등록")

        val pendingOrder = pendingOrderManager.registerPendingOrder(
            orderId = orderResponse.uuid,
            signal = signal,
            orderPrice = limitPrice,
            orderQuantity = quantity,
            orderAmountKrw = positionSize,
            marketCondition = marketCondition
        )

        // 현재까지의 체결 상태를 반영한 결과 반환
        val executedVolume = quickFillResult?.executedVolume ?: BigDecimal.ZERO
        return OrderSubmitResult(
            orderResponse = quickFillResult ?: orderResponse,
            orderType = OrderType.LIMIT,
            limitPrice = limitPrice,
            quantity = quantity,
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
                    response.state == ORDER_STATE_DONE -> {
                        log.info("[$market] 빠른 체결 확인: 완전 체결")
                        return response
                    }
                    fillRate >= PARTIAL_FILL_SUCCESS_THRESHOLD -> {
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
     * 체결률 계산
     */
    private fun calculateFillRate(response: OrderResponse): Double {
        val executedVolume = response.executedVolume ?: BigDecimal.ZERO
        val totalVolume = response.volume ?: BigDecimal.ONE
        return if (totalVolume > BigDecimal.ZERO) {
            executedVolume.divide(totalVolume, 4, RoundingMode.HALF_UP).toDouble()
        } else 0.0
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
        val response = when (signal.action) {
            SignalAction.BUY -> {
                log.info("[${signal.market}] 시장가 매수: $positionSize KRW")
                bithumbPrivateApi.buyMarketOrder(apiMarket, positionSize)
            }
            SignalAction.SELL -> {
                // 실제 잔고 확인 후 매도 수량 결정
                val requestedQuantity = calculateQuantity(signal.price, positionSize)
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

        return OrderSubmitResult(response, OrderType.MARKET, null, null)
    }

    /**
     * 실제 매도 가능 수량 확인
     *
     * 잔고가 요청 수량보다 적으면 전량 매도, 잔고가 없으면 0 반환
     */
    private fun getActualSellQuantity(market: String, requestedQuantity: BigDecimal): BigDecimal {
        return try {
            val coinSymbol = extractCoinSymbol(market)
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
     * 마켓 문자열에서 코인 심볼 추출
     *
     * 마켓 형식 지원:
     * - "KRW-BTC" → "BTC"
     * - "BTC_KRW" → "BTC"
     */
    private fun extractCoinSymbol(market: String): String {
        return when {
            market.contains("-") -> market.split("-").lastOrNull() ?: market
            market.contains("_") -> market.split("_").firstOrNull() ?: market
            else -> market
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
                    delayMs = minOf(delayMs * 2, STATUS_CHECK_MAX_DELAY_MS)
                    continue
                }

                lastResponse = response
                val state = response.state

                when (state) {
                    ORDER_STATE_DONE -> {
                        log.info("[$market] 주문 완료 확인: orderId=$orderId")
                        return response
                    }
                    ORDER_STATE_CANCEL -> {
                        log.warn("[$market] 주문 취소됨: orderId=$orderId")
                        return response
                    }
                    ORDER_STATE_WAIT -> {
                        // 대기 중 - 부분 체결 가능성
                        val executedVolume = response.executedVolume ?: BigDecimal.ZERO
                        val totalVolume = response.volume ?: BigDecimal.ONE

                        if (executedVolume > BigDecimal.ZERO) {
                            val fillRate = executedVolume.divide(totalVolume, 4, RoundingMode.HALF_UP)
                            log.info("[$market] 부분 체결 중: ${fillRate.multiply(BigDecimal(100))}% (시도 $attempt/$MAX_STATUS_CHECK_RETRIES)")

                            // 90% 이상 체결이면 성공으로 간주
                            if (fillRate.toDouble() >= PARTIAL_FILL_SUCCESS_THRESHOLD) {
                                log.info("[$market] 부분 체결 임계값 초과 - 성공으로 처리")
                                return response
                            }
                        }
                    }
                    else -> {
                        log.warn("[$market] 알 수 없는 주문 상태: $state")
                    }
                }

                delayMs = minOf(delayMs * 2, STATUS_CHECK_MAX_DELAY_MS)

            } catch (e: Exception) {
                log.error("[$market] 주문 조회 예외 (시도 $attempt/$MAX_STATUS_CHECK_RETRIES): ${e.message}")
                delayMs = minOf(delayMs * 2, STATUS_CHECK_MAX_DELAY_MS)
            }
        }

        // 최대 재시도 후에도 완료 확인 안 됨
        log.warn("[$market] 주문 상태 확인 타임아웃: orderId=$orderId, lastState=${lastResponse?.state}")

        // 부분 체결이라도 있으면 해당 응답 반환
        return lastResponse
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
        val side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL

        val executedVolume = order.executedVolume ?: BigDecimal.ZERO
        val totalVolume = order.volume ?: BigDecimal.ONE
        val paidFee = order.paidFee ?: BigDecimal.ZERO

        // 체결 여부 확인
        if (executedVolume <= BigDecimal.ZERO) {
            return OrderResult(
                success = false,
                orderId = order.uuid,
                market = market,
                side = side,
                message = "체결 없음: state=${order.state}",
                rejectionReason = OrderRejectionReason.NO_FILL
            )
        }

        // 부분 체결 여부
        val isPartialFill = executedVolume < totalVolume
        val fillRatePercent = executedVolume.divide(totalVolume, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()

        // 평균 체결가 계산
        // 시장가 주문이므로 체결된 금액 / 체결 수량으로 역산
        val executedPrice = if (signal.action == SignalAction.BUY) {
            // 매수: locked(투입금) / executedVolume
            // 주의: 체결 완료 후 API 조회 시 locked가 0이 되므로 positionSize로 대체
            val locked = order.locked
            val invested = if (locked == null || locked <= BigDecimal.ZERO) {
                log.debug("[${signal.market}] locked가 0 또는 null - positionSize($positionSize) 사용")
                positionSize
            } else {
                locked
            }
            invested.divide(executedVolume, 8, RoundingMode.HALF_UP)
        } else {
            // 매도: (locked + paidFee) / executedVolume 또는 order.price 사용
            order.price ?: signal.price
        }

        // 슬리피지 계산
        val referencePrice = midPrice ?: signal.price
        val slippagePercent = if (referencePrice > BigDecimal.ZERO) {
            val slippage = when (signal.action) {
                SignalAction.BUY -> executedPrice.subtract(referencePrice)  // 매수: 비싸게 사면 손해
                SignalAction.SELL -> referencePrice.subtract(executedPrice)  // 매도: 싸게 팔면 손해
                SignalAction.HOLD -> BigDecimal.ZERO
            }
            slippage.divide(referencePrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()
        } else {
            0.0
        }

        // 슬리피지 경고
        if (slippagePercent > SLIPPAGE_CRITICAL_PERCENT) {
            log.error("[$market] 크리티컬 슬리피지: ${String.format("%.3f", slippagePercent)}%")
            slackNotifier.sendError(market, """
                슬리피지 경고 (CRITICAL)

                슬리피지: ${String.format("%.3f", slippagePercent)}%
                기준가: $referencePrice
                체결가: $executedPrice

                시장 유동성 확인 필요
            """.trimIndent())
        } else if (slippagePercent > SLIPPAGE_WARNING_PERCENT) {
            log.warn("[$market] 슬리피지 경고: ${String.format("%.3f", slippagePercent)}%")
        }

        // 부분 체결 경고
        if (isPartialFill) {
            log.warn("[$market] 부분 체결: ${String.format("%.1f", fillRatePercent)}% 체결됨")
        }

        val message = buildString {
            append("체결 완료")
            if (isPartialFill) {
                append(" (부분: ${String.format("%.1f", fillRatePercent)}%)")
            }
            if (slippagePercent > SLIPPAGE_WARNING_PERCENT) {
                append(" [슬리피지: ${String.format("%.2f", slippagePercent)}%]")
            }
        }

        log.info("""
            [$market] 체결 분석 완료
            주문ID: ${order.uuid}
            체결가: $executedPrice
            체결량: $executedVolume / $totalVolume
            수수료: $paidFee
            슬리피지: ${String.format("%.3f", slippagePercent)}%
        """.trimIndent())

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
            var tradePrice = (executedPrice ?: signal.price).toDouble()

            // [버그 수정] 가격이 0 이하면 marketConditionChecker를 통해 현재가 조회
            if (tradePrice <= 0) {
                log.warn("[${signal.market}] 거래 가격이 0 - executedPrice=${executedPrice}, signal.price=${signal.price}")
                try {
                    val marketCondition = marketConditionChecker.checkMarketCondition(signal.market, positionSize)
                    val midPrice = marketCondition.midPrice
                    if (midPrice != null && midPrice > BigDecimal.ZERO) {
                        tradePrice = midPrice.toDouble()
                        log.info("[${signal.market}] 시장 중간가로 대체: ${tradePrice}")
                    }
                } catch (e: Exception) {
                    log.error("[${signal.market}] 시장 상태 조회 실패: ${e.message}")
                }

                // 여전히 0이면 저장 스킵 (잘못된 데이터가 DB에 들어가는 것 방지)
                if (tradePrice <= 0) {
                    log.error("[${signal.market}] 유효하지 않은 거래 가격 - orderId=$orderId, strategy=${signal.strategy}")
                    log.error("[${signal.market}] 거래 기록 저장 스킵 (price=0 방지)")
                    return
                }
            }

            val sellQuantity = (executedQuantity ?: calculateQuantity(signal.price, positionSize)).toDouble()
            val sellFee = fee.toDouble()

            // SELL 시 PnL 계산
            var pnl: Double? = null
            var pnlPercent: Double? = null

            if (isSell) {
                val lastBuy = tradeRepository.findLastBuyByMarket(signal.market)
                if (lastBuy != null && lastBuy.price > 0) {
                    val buyPrice = lastBuy.price
                    val buyFee = lastBuy.fee

                    // PnL = (매도가 - 매수가) * 수량 - 매수수수료 - 매도수수료
                    pnl = (tradePrice - buyPrice) * sellQuantity - buyFee - sellFee

                    // PnL% = ((매도가 - 매수가) / 매수가) * 100
                    pnlPercent = ((tradePrice - buyPrice) / buyPrice) * 100

                    log.info("[${signal.market}] PnL 계산 완료: 매수가=${buyPrice}, 매도가=${tradePrice}, " +
                            "PnL=${String.format("%.0f", pnl)}원 (${String.format("%.2f", pnlPercent)}%)")

                    // CircuitBreaker에 거래 결과 기록
                    circuitBreaker.recordTradeResult(signal.market, pnlPercent)
                } else {
                    log.warn("[${signal.market}] 매칭되는 BUY 거래를 찾을 수 없음 - PnL 계산 불가")
                }
            }

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
                pnl = pnl,
                pnlPercent = pnlPercent,
                strategy = signal.strategy,
                regime = signal.regime,
                confidence = signal.confidence,
                reason = signal.reason.take(500),
                simulated = isSimulated
            )

            tradeRepository.save(entity)
            log.debug("[${signal.market}] 거래 기록 저장: orderId=$orderId, type=$orderType, pnl=$pnl")

        } catch (e: Exception) {
            log.error("[${signal.market}] 거래 기록 저장 실패: ${e.message}", e)
            // 기록 실패는 거래 실패로 처리하지 않음 (best effort)
        }
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
     * 마켓 형식 변환 (BTC_KRW -> KRW-BTC)
     */
    private fun convertToApiMarket(market: String): String {
        return market.split("_").let { parts ->
            if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
        }
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
    BELOW_MIN_ORDER_AMOUNT  // 최소 주문 금액 미달 (빗썸 5000원)
}
