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
import java.util.concurrent.locks.ReentrantLock

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

    // 청산 시 마켓별 동시성 제어 (진입 락과 분리)
    private val closeLocks = ConcurrentHashMap<String, ReentrantLock>()

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
        val lock = closeLocks.computeIfAbsent(market) { ReentrantLock() }

        if (!lock.tryLock()) {
            log.debug("[$market] 청산 진행 중 - 건너뜀")
            return
        }

        try {
            executeCloseInternal(
                position, exitPrice, reason, strategyName,
                maxAttempts, backoffSeconds,
                updatePosition, onComplete, onAbandoned
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * 포지션 청산 실행 (내부 로직)
     */
    private fun <T : PositionEntity> executeCloseInternal(
        position: T,
        exitPrice: Double,
        reason: String,
        strategyName: String,
        maxAttempts: Int,
        backoffSeconds: Long,
        updatePosition: (T, String, Double, Double, String?) -> Unit,
        onComplete: (T, Double, String, OrderResult) -> Unit,
        onAbandoned: ((T, Double, String) -> Unit)?
    ) {
        val market = position.market

        // 0. 진입가/수량 무결성 체크
        if (position.entryPrice <= 0 || position.quantity <= 0) {
            log.error("[$market] 유효하지 않은 포지션 - 진입가: ${position.entryPrice}, 수량: ${position.quantity}")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "INVALID_POSITION")
            } else {
                handleAbandoned(position, exitPrice, "INVALID_POSITION", updatePosition)
            }
            return
        }

        // 1. 최대 시도 횟수 초과 체크
        if (position.closeAttemptCount >= maxAttempts) {
            log.error("[$market] 청산 시도 ${maxAttempts}회 초과, ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "MAX_ATTEMPTS")
            } else {
                handleAbandoned(position, exitPrice, "MAX_ATTEMPTS", updatePosition)
            }
            return
        }

        // 2. 중복 청산 방지
        if (!canClosePosition(position.status, position.lastCloseAttempt, backoffSeconds)) {
            return
        }

        log.info("[$market] 포지션 청산 시도 #${position.closeAttemptCount + 1}: $reason, 가격=$exitPrice")

        // 3. 실제 잔고 확인 (available + locked)
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val coinBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}, 재시도 유도")
            updatePosition(position, "OPEN", exitPrice, position.quantity, null)
            slackNotifier.sendWarning(market, "잔고 조회 실패로 청산 지연: ${e.message}")
            return
        }

        val available = coinBalance?.balance ?: BigDecimal.ZERO
        val locked = coinBalance?.locked ?: BigDecimal.ZERO
        val totalBalance = available + locked

        // 4. 실제 잔고 없으면 청산 완료로 간주
        if (totalBalance <= BigDecimal.ZERO) {
            log.info("[$market] 실제 잔고 없음 (available=0, locked=0) - 청산 완료로 간주")
            updatePosition(position, "CLOSED", exitPrice, 0.0, null)
            return
        }

        // 4-1. available=0이지만 locked에 잔고 있으면 pending 주문 존재 → 대기
        if (available <= BigDecimal.ZERO && locked > BigDecimal.ZERO) {
            log.info("[$market] 코인이 locked 상태 (pending 주문 존재, locked=$locked) - 청산 대기")
            return
        }

        // 5. 매도 수량 결정 (available 기준)
        val sellQuantity = available.coerceAtMost(BigDecimal(position.quantity))

        // 6. 0 수량 체크
        if (sellQuantity <= BigDecimal.ZERO) {
            log.warn("[$market] 매도 수량 0 - ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "ZERO_QUANTITY")
            } else {
                handleAbandoned(position, exitPrice, "ZERO_QUANTITY", updatePosition)
            }
            return
        }

        val positionAmount = sellQuantity.multiply(BigDecimal(exitPrice))

        // 7. 최소 금액 미달 체크
        if (positionAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 최소 주문 금액 미달 (${positionAmount}원) - ABANDONED 처리")
            if (onAbandoned != null) {
                onAbandoned(position, exitPrice, "MIN_AMOUNT")
            } else {
                handleAbandoned(position, exitPrice, "MIN_AMOUNT", updatePosition)
            }
            return
        }

        // 8. 상태 변경 및 저장
        updatePosition(position, "CLOSING", exitPrice, sellQuantity.toDouble(), null)

        // 9. 주문 실행
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "$strategyName 청산: $reason",
            strategy = strategyName
        )

        val orderResult = orderExecutor.execute(sellSignal, positionAmount)

        // 10. 주문 결과 처리
        if (!orderResult.success) {
            log.error("[$market] 매도 주문 실패: ${orderResult.message}")
            updatePosition(position, "OPEN", exitPrice, position.quantity, null)
            return
        }

        // 11. 주문 ID 업데이트 (null safety)
        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            log.warn("[$market] 주문 ID 없음 - OPEN으로 복원")
            updatePosition(position, "OPEN", exitPrice, position.quantity, null)
            return
        }
        updatePosition(position, "CLOSING", exitPrice, sellQuantity.toDouble(), orderId)

        // 12. executedVolume 0 체크 (엣지 케이스: API는 done인데 체결 수량 0)
        val executedQty = orderResult.executedQuantity
        if (executedQty != null && executedQty > BigDecimal.ZERO) {
            // 전체 또는 부분 체결됨
            val actualPrice = orderResult.price?.toDouble() ?: exitPrice
            onComplete(position, actualPrice, reason, orderResult)
        } else if (executedQty != null && executedQty <= BigDecimal.ZERO) {
            // 주문은 성공했지만 체결 수량 0 - API 엣지 케이스
            log.warn("[$market] 주문 완료지만 체결 수량 0 - 잔고 재확인 필요")
            // 잔고 재확인 후 남아있으면 OPEN으로 복원 (available + locked)
            val recheckCoin = try {
                bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }
            } catch (e: Exception) {
                log.warn("[$market] 재확인 실패: ${e.message}")
                null
            }
            val recheckBalance = (recheckCoin?.balance ?: BigDecimal.ZERO) +
                (recheckCoin?.locked ?: BigDecimal.ZERO)
            if (recheckBalance > BigDecimal.ZERO) {
                log.info("[$market] 잔고 여전히 존재 - OPEN으로 복원")
                updatePosition(position, "OPEN", exitPrice, recheckBalance.toDouble(), null)
            } else {
                log.info("[$market] 잔고 0 - 체결 완료로 간주")
                updatePosition(position, "CLOSED", exitPrice, sellQuantity.toDouble(), orderId)
            }
        } else {
            // executedQuantity가 null - 체결 대기
            log.info("[$market] 청산 주문 접수됨, 체결 대기: $orderId")
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

        log.warn("[$market] 포지션 ABANDONED 처리 완료 ($abandonReason)")
    }

    companion object {
        // 트레일링 고점 추적 공유 맵
        val highestPrices = ConcurrentHashMap<Long, Double>()

        /**
         * 중복 청산 방지 체크
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
