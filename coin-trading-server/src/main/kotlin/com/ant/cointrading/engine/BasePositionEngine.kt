package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.isClosed
import com.ant.cointrading.repository.isOpen
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 포지션 관리 엔진 기반 클래스 (켄트백 스타일)
 *
 * VolumeSurgeEngine, MemeScalperEngine의 공통 로직 추출.
 * 중복 제거와 일관성 확보.
 */
abstract class BasePositionEngine<T : Any>(
    protected val bithumbPublicApi: BithumbPublicApi,
    protected val bithumbPrivateApi: BithumbPrivateApi,
    protected val slackNotifier: SlackNotifier
) {
    protected val log = LoggerFactory.getLogger(this.javaClass)

    // === 포지션 모니터링 공통 로직 ===

    /**
     * 진입가 무결성 검증
     * @return 진입가가 유효하지 않으면 true
     */
    protected fun isInvalidEntryPrice(entryPrice: Double): Boolean = entryPrice <= 0

    /**
     * 최소 보유 시간 경과 여부
     */
    protected fun hasMinHoldingPeriodElapsed(entryTime: Instant, minSeconds: Long): Boolean {
        val holdingSeconds = ChronoUnit.SECONDS.between(entryTime, Instant.now())
        return holdingSeconds >= minSeconds
    }

    /**
     * 손절 도달 여부
     */
    protected fun isStopLossTriggered(pnlPercent: Double, stopLossPercent: Double): Boolean {
        return pnlPercent <= stopLossPercent
    }

    /**
     * 익절 도달 여부
     */
    protected fun isTakeProfitTriggered(pnlPercent: Double, takeProfitPercent: Double): Boolean {
        return pnlPercent >= takeProfitPercent
    }

    /**
     * 타임아웃 도달 여부
     */
    protected fun isTimeout(entryTime: Instant, timeoutMinutes: Int): Boolean {
        val holdingMinutes = ChronoUnit.MINUTES.between(entryTime, Instant.now())
        return holdingMinutes >= timeoutMinutes
    }

    // === 포지션 청산 공통 로직 ===

    /**
     * 청산 가능 여부 체크
     * @return 청산 가능하면 true, 이미 진행 중이면 false
     */
    protected fun canClosePosition(status: String, lastCloseAttempt: Instant?): Boolean {
        if (status == TradingConstants.POSITION_STATUS_CLOSING) {
            return false
        }

        // 백오프 체크
        if (lastCloseAttempt != null) {
            val elapsed = java.time.Duration.between(lastCloseAttempt, Instant.now()).seconds
            if (elapsed < getCloseRetryBackoffSeconds()) {
                return false
            }
        }

        return true
    }

    /**
     * 최대 시도 횟수 초과 여부
     */
    protected fun isMaxAttemptsExceeded(closeAttemptCount: Int): Boolean {
        return closeAttemptCount >= getMaxCloseAttempts()
    }

    /**
     * 실제 잔고 조회 (예외 처리 포함)
     */
    protected fun getActualBalance(market: String, dbQuantity: Double): BigDecimal {
        val coinSymbol = market.removePrefix("KRW-")
        return try {
            bithumbPrivateApi.getBalances()
                ?.find { it.currency == coinSymbol }
                ?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, DB 수량 사용: ${e.message}")
            BigDecimal(dbQuantity)
        }
    }

    /**
     * 최소 주문 금액 미달 여부
     */
    protected fun isBelowMinOrderAmount(amount: BigDecimal): Boolean {
        return amount < TradingConstants.MIN_ORDER_AMOUNT_KRW
    }

    // === 청산 완료 처리 공통 로직 ===

    /**
     * CLOSING 포지션 상태 모니터링
     * @return 청산 완료되면 true, 계속 대기해야 하면 false
     */
    protected fun monitorClosingPosition(
        market: String,
        status: String,
        closeOrderId: String?,
        lastCloseAttempt: Instant?,
        onOrderDone: (actualPrice: Double) -> Unit,
        onOrderCancelled: () -> Unit,
        onOrderWaitTimeout: () -> Unit
    ): Boolean {
        if (closeOrderId.isNullOrBlank()) {
            onOrderCancelled()
            return false
        }

        try {
            val orderStatus = bithumbPrivateApi.getOrder(closeOrderId)

            when (orderStatus?.state) {
                TradingConstants.ORDER_STATE_DONE -> {
                    val actualPrice = orderStatus.price?.toDouble() ?: 0.0
                    onOrderDone(actualPrice)
                    return true
                }
                TradingConstants.ORDER_STATE_CANCEL -> {
                    onOrderCancelled()
                    return false
                }
                TradingConstants.ORDER_STATE_WAIT -> {
                    val elapsed = java.time.Duration.between(
                        lastCloseAttempt ?: Instant.now(),
                        Instant.now()
                    ).seconds

                    if (elapsed > 15) {  // 15초 대기 후 취소
                        try {
                            bithumbPrivateApi.cancelOrder(closeOrderId)
                        } catch (e: Exception) {
                            log.warn("[$market] 주문 취소 실패: ${e.message}")
                        }
                        onOrderCancelled()
                    }
                    return false
                }
                else -> return false
            }
        } catch (e: Exception) {
            log.error("[$market] 청산 상태 조회 실패: ${e.message}")
            val elapsed = java.time.Duration.between(
                lastCloseAttempt ?: Instant.now(),
                Instant.now()
            ).toMinutes()

            if (elapsed >= 2) {
                onOrderCancelled()
            }
            return false
        }
    }

    // === 하위 클래스에서 구현해야 할 메서드 ===

    /** 청산 재시도 백오프 시간 (초) */
    protected abstract fun getCloseRetryBackoffSeconds(): Long

    /** 최대 청산 시도 횟수 */
    protected abstract fun getMaxCloseAttempts(): Long

    /** 포지션 타임아웃 (분) */
    protected abstract fun getPositionTimeoutMinutes(): Int
}
