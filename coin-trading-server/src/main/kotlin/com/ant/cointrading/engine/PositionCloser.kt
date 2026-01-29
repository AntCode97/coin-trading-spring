package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.order.OrderResult
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

// PositionEntity import is already available in the same package

/**
 * 포지션 청산 관리자 (공통 로직)
 *
 * VolumeSurgeEngine, MemeScalperEngine에서 공통으로 사용하는 청산 로직
 */
class PositionCloser(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val orderExecutor: OrderExecutor,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(PositionCloser::class.java)

    /**
     * 포지션 청산 실행 (제네릭 버전)
     */
    fun <T : PositionEntity> executeClose(
        position: T,
        exitPrice: Double,
        reason: String,
        strategyName: String,
        maxAttempts: Int,
        backoffSeconds: Long,
        updatePosition: (T, String, Double, Double, String?) -> Unit,
        onComplete: (T, Double, String, OrderResult) -> Unit,
        onAbandoned: ((T, Double, String) -> Unit)? = null
    ) {
        val market = position.market

        // 최대 시도 횟수 초과 체크 (먼저 체크해야 canClosePosition 무한 루프 방지)
        if (position.closeAttemptCount >= maxAttempts) {
            log.error("[$market] 청산 시도 ${maxAttempts}회 초과, ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "MAX_ATTEMPTS")
            } else {
                handleAbandoned(position, exitPrice, "MAX_ATTEMPTS", updatePosition)
            }
            return
        }

        // 중복 청산 방지 (최대 시도 횟수 체크 이후에 실행)
        if (!canClosePosition(position.status, position.lastCloseAttempt, backoffSeconds)) {
            return
        }

        log.info("[$market] 포지션 청산 시도 #${position.closeAttemptCount + 1}: $reason, 가격=$exitPrice")

        // 실제 잔고 확인
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val actualBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, DB 수량 사용: ${e.message}")
            BigDecimal(position.quantity)
        }

        // 실제 잔고 없으면 이미 청산됨
        if (actualBalance <= BigDecimal.ZERO) {
            log.warn("[$market] 실제 잔고 없음 - ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "NO_BALANCE")
            } else {
                handleAbandoned(position, exitPrice, "NO_BALANCE", updatePosition)
            }
            return
        }

        // 매도 수량 결정
        val sellQuantity = actualBalance.coerceAtMost(BigDecimal(position.quantity))
        val positionAmount = sellQuantity.multiply(BigDecimal(exitPrice))

        // 최소 금액 미달 체크
        if (positionAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 최소 주문 금액 미달 - ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "MIN_AMOUNT")
            } else {
                handleAbandoned(position, exitPrice, "MIN_AMOUNT", updatePosition)
            }
            return
        }

        // 상태 변경 및 저장 (orderId는 주문 후 업데이트)
        updatePosition(position, "CLOSING", exitPrice, sellQuantity.toDouble(), null)

        // 주문 실행
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "$strategyName 청산: $reason",
            strategy = strategyName
        )

        val orderResult = orderExecutor.execute(sellSignal, positionAmount)

        // closeOrderId 업데이트
        updatePosition(position, "CLOSING", exitPrice, sellQuantity.toDouble(), orderResult.orderId)

        if (orderResult.success) {
            val actualPrice = orderResult.price?.toDouble() ?: exitPrice
            if (orderResult.executedQuantity != null && orderResult.executedQuantity!! > BigDecimal.ZERO) {
                onComplete(position, actualPrice, reason, orderResult)
            } else {
                log.info("[$market] 청산 주문 접수됨, 체결 대기: ${orderResult.orderId}")
            }
        } else {
            val errorMessage = orderResult.message ?: ""

            when {
                errorMessage.contains("insufficient", ignoreCase = true) ||
                errorMessage.contains("부족") ||
                errorMessage.contains("최소") -> {
                    if (onAbandoned != null) {
                        onAbandoned(position, exitPrice, "API_ERROR")
                    } else {
                        handleAbandoned(position, exitPrice, "API_ERROR", updatePosition)
                    }
                }
                else -> {
                    updatePosition(position, "OPEN", exitPrice, position.quantity, null)
                }
            }
        }
    }

    /**
     * ABANDONED 처리 (제네릭 버전)
     */
    private fun <T : PositionEntity> handleAbandoned(
        position: T,
        exitPrice: Double,
        abandonReason: String,
        updatePosition: (T, String, Double, Double, String?) -> Unit
    ) {
        val market = position.market

        updatePosition(position, "ABANDONED", exitPrice, position.quantity, null)

        // 트레일링 추적 제거 (외부에서 관리됨)
        highestPrices.remove(position.id)

        slackNotifier.sendWarning(
            market,
            """
            포지션 청산 불가 ($abandonReason)
            금액: ${String.format("%.0f", position.quantity * exitPrice)}원
            진입가: ${position.entryPrice}원
            현재가: ${exitPrice}원

            해당 포지션은 ABANDONED 상태로 변경됨.
            수동으로 빗썸에서 확인/처리 필요.
            """.trimIndent()
        )

        log.info("[$market] 포지션 ABANDONED 처리 완료 ($abandonReason)")
    }

    companion object {
        // 트레일링 고점 추적 (외부에서 관리됨)
        val highestPrices = ConcurrentHashMap<Long, Double>()

        /**
         * 중복 청산 방지 체크
         *
         * @return true면 청산 가능, false면 대기 필요
         */
        fun canClosePosition(
            status: String,
            lastAttempt: Instant?,
            backoffSeconds: Long
        ): Boolean {
            if (status == "CLOSING") {
                val lastAttempt = lastAttempt ?: return false
                val elapsed = ChronoUnit.SECONDS.between(lastAttempt, Instant.now())
                return elapsed >= backoffSeconds
            }
            return true
        }
    }
}
