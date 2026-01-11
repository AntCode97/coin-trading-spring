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
import kotlinx.coroutines.delay
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
    private val slackNotifier: SlackNotifier
) {

    private val log = LoggerFactory.getLogger(OrderExecutor::class.java)

    companion object {
        // 주문 상태 확인 설정
        const val MAX_STATUS_CHECK_RETRIES = 3
        const val STATUS_CHECK_INITIAL_DELAY_MS = 500L
        const val STATUS_CHECK_MAX_DELAY_MS = 2000L

        // 슬리피지 경고 임계값
        const val SLIPPAGE_WARNING_PERCENT = 0.5
        const val SLIPPAGE_CRITICAL_PERCENT = 2.0

        // 부분 체결 임계값 (90% 이상 체결이면 성공으로 간주)
        const val PARTIAL_FILL_SUCCESS_THRESHOLD = 0.9

        // 주문 상태
        const val ORDER_STATE_DONE = "done"
        const val ORDER_STATE_WAIT = "wait"
        const val ORDER_STATE_CANCEL = "cancel"
    }

    /**
     * 주문 실행 (메인 진입점)
     */
    suspend fun execute(signal: TradingSignal, positionSize: BigDecimal): OrderResult {
        // 1. 시뮬레이션 모드 체크
        if (!tradingProperties.enabled) {
            return executeSimulated(signal, positionSize)
        }

        // 2. 시장 상태 검사 (실거래 전 필수)
        val marketCheck = marketConditionChecker.checkMarketCondition(signal.market, positionSize)
        if (!marketCheck.canTrade) {
            log.warn("[${signal.market}] 시장 상태 불량으로 주문 거부: ${marketCheck.issues}")
            return OrderResult(
                success = false,
                market = signal.market,
                side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL,
                message = "시장 상태 불량: ${marketCheck.issues.joinToString(", ")}",
                rejectionReason = OrderRejectionReason.MARKET_CONDITION
            )
        }

        // 3. 실제 주문 실행
        return executeReal(signal, positionSize, marketCheck.midPrice)
    }

    /**
     * 시뮬레이션 주문
     */
    private fun executeSimulated(signal: TradingSignal, positionSize: BigDecimal): OrderResult {
        val orderId = "SIM-${System.currentTimeMillis()}"
        val quantity = calculateQuantity(signal.price, positionSize)
        val estimatedFee = positionSize.multiply(BigDecimal("0.0004"))  // 빗썸 수수료 0.04%

        log.info("""
            [시뮬레이션] 주문 실행
            마켓: ${signal.market}
            행동: ${signal.action}
            가격: ${signal.price}
            금액: $positionSize
            신뢰도: ${signal.confidence}%
            전략: ${signal.strategy}
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
     */
    private suspend fun executeReal(
        signal: TradingSignal,
        positionSize: BigDecimal,
        midPrice: BigDecimal?
    ): OrderResult {
        val market = signal.market
        val apiMarket = convertToApiMarket(market)

        return try {
            // 1. 주문 제출
            val orderResponse = submitOrder(signal, apiMarket, positionSize)

            if (orderResponse == null) {
                circuitBreaker.recordExecutionFailure(market, "주문 응답 null")
                return OrderResult(
                    success = false,
                    market = market,
                    side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL,
                    message = "주문 응답 없음 - API 장애 의심",
                    rejectionReason = OrderRejectionReason.API_ERROR
                )
            }

            val orderId = orderResponse.uuid
            log.info("[$market] 주문 제출됨: orderId=$orderId")

            // 2. 주문 상태 확인 (재시도 포함)
            val verifiedOrder = verifyOrderExecution(orderId, market)

            if (verifiedOrder == null) {
                circuitBreaker.recordExecutionFailure(market, "주문 상태 확인 실패")
                return OrderResult(
                    success = false,
                    orderId = orderId,
                    market = market,
                    side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL,
                    message = "주문 상태 확인 실패 - 수동 확인 필요",
                    rejectionReason = OrderRejectionReason.VERIFICATION_FAILED
                )
            }

            // 3. 체결 결과 분석
            val executionResult = analyzeExecution(signal, verifiedOrder, positionSize, midPrice)

            // 4. CircuitBreaker에 결과 기록
            if (executionResult.success) {
                circuitBreaker.recordExecutionSuccess(market)
                if (executionResult.slippagePercent > 0) {
                    circuitBreaker.recordSlippage(market, executionResult.slippagePercent)
                }
            } else {
                circuitBreaker.recordExecutionFailure(market, executionResult.message)
            }

            // 5. 거래 기록 저장
            saveTradeRecord(
                signal = signal,
                positionSize = positionSize,
                orderId = orderId,
                executedPrice = executionResult.price,
                executedQuantity = executionResult.executedQuantity,
                fee = executionResult.fee ?: BigDecimal.ZERO,
                slippagePercent = executionResult.slippagePercent,
                isSimulated = false,
                isPartialFill = executionResult.isPartialFill
            )

            executionResult

        } catch (e: Exception) {
            log.error("[$market] 주문 실행 실패: ${e.message}", e)
            circuitBreaker.recordExecutionFailure(market, e.message ?: "Unknown error")

            OrderResult(
                success = false,
                market = market,
                side = if (signal.action == SignalAction.BUY) OrderSide.BUY else OrderSide.SELL,
                message = "주문 실행 예외: ${e.message}",
                rejectionReason = OrderRejectionReason.EXCEPTION
            )
        }
    }

    /**
     * 주문 제출
     */
    private fun submitOrder(
        signal: TradingSignal,
        apiMarket: String,
        positionSize: BigDecimal
    ): OrderResponse? {
        return when (signal.action) {
            SignalAction.BUY -> {
                // 시장가 매수 (KRW 금액 지정)
                bithumbPrivateApi.buyMarketOrder(apiMarket, positionSize)
            }
            SignalAction.SELL -> {
                // 시장가 매도 (수량 지정)
                val quantity = calculateQuantity(signal.price, positionSize)
                bithumbPrivateApi.sellMarketOrder(apiMarket, quantity)
            }
            SignalAction.HOLD -> null
        }
    }

    /**
     * 주문 상태 확인 (재시도 포함)
     */
    private suspend fun verifyOrderExecution(orderId: String, market: String): OrderResponse? {
        var lastResponse: OrderResponse? = null
        var delayMs = STATUS_CHECK_INITIAL_DELAY_MS

        for (attempt in 1..MAX_STATUS_CHECK_RETRIES) {
            delay(delayMs)

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
        midPrice: BigDecimal?
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
            val invested = order.locked ?: positionSize
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
            isSimulated = false
        )
    }

    /**
     * 거래 기록 저장
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
        isPartialFill: Boolean
    ) {
        try {
            val entity = TradeEntity(
                orderId = orderId,
                market = signal.market,
                side = if (signal.action == SignalAction.BUY) "BUY" else "SELL",
                type = "MARKET",
                price = (executedPrice ?: signal.price).toDouble(),
                quantity = (executedQuantity ?: calculateQuantity(signal.price, positionSize)).toDouble(),
                totalAmount = positionSize.toDouble(),
                fee = fee.toDouble(),
                slippage = slippagePercent,
                isPartialFill = isPartialFill,
                pnl = null,
                pnlPercent = null,
                strategy = signal.strategy,
                regime = null,
                confidence = signal.confidence,
                reason = signal.reason.take(500),
                simulated = isSimulated
            )

            tradeRepository.save(entity)
            log.debug("[${signal.market}] 거래 기록 저장: orderId=$orderId")

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
    val rejectionReason: OrderRejectionReason? = null,
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
    CIRCUIT_BREAKER         // 서킷 브레이커 발동
}
