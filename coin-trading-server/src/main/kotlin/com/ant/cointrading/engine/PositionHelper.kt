package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.Duration

/**
 * 포지션 엔티티 공통 인터페이스 (켄트백 스타일)
 *
 * VolumeSurgeTradeEntity, MemeScalperTradeEntity가 구현해야 할 공통 속성
 */
interface PositionEntity {
    val id: Long?
    val market: String
    val entryPrice: Double
    val exitPrice: Double?
    val quantity: Double
    val entryTime: Instant
    val exitTime: Instant?
    val status: String
    val closeOrderId: String?
    val lastCloseAttempt: Instant?
    val closeAttemptCount: Int
    val pnlAmount: Double?
    val pnlPercent: Double?
}

/**
 * 포지션 청산 관련 헬퍼 함수 (켄트백 스타일)
 *
 * 중복 제거를 위한 유틸리티 함수들
 */
object PositionHelper {
    private val log = LoggerFactory.getLogger(PositionHelper::class.java)

    /**
     * ABANDONED 사유 생성
     */
    fun abandonedReason(reason: String): String = "ABANDONED_$reason"

    /**
     * 청산 가능 여부 체크 (중복 청산 방지)
     */
    fun canClosePosition(
        status: String,
        lastCloseAttempt: Instant?,
        backoffSeconds: Long
    ): Boolean {
        if (status == "CLOSING") return false

        if (lastCloseAttempt != null) {
            val elapsed = Duration.between(lastCloseAttempt, Instant.now()).seconds
            if (elapsed < backoffSeconds) return false
        }

        return true
    }

    /**
     * 최대 시도 횟수 초과 여부
     */
    fun isMaxAttemptsExceeded(attemptCount: Int, maxAttempts: Int): Boolean {
        return attemptCount >= maxAttempts
    }

    /**
     * 최소 주문 금액 체크
     */
    fun isBelowMinAmount(amount: BigDecimal, minAmount: BigDecimal = BigDecimal("5100")): Boolean {
        return amount < minAmount
    }

    /**
     * 코인 심볼 추출
     */
    fun extractCoinSymbol(market: String): String = market.removePrefix("KRW-")

    /**
     * CLOSING 포지션 모니터링 (공통 로직)
     *
     * @return 청산 완료(true), 계속 대기 필요(false)
     */
    fun monitorClosingPosition(
        bithumbPrivateApi: BithumbPrivateApi,
        position: PositionEntity,
        waitTimeoutSeconds: Long = 30L,
        errorTimeoutMinutes: Long = 5L,
        onOrderDone: (actualPrice: Double) -> Unit,
        onOrderCancelled: () -> Unit
    ): Boolean {
        val market = position.market
        val closeOrderId = position.closeOrderId

        // 주문 ID가 없으면 OPEN으로 복원
        if (closeOrderId.isNullOrBlank()) {
            log.warn("[$market] 청산 주문 ID 없음, OPEN으로 복원")
            onOrderCancelled()
            return false
        }

        // 주문 상태 조회
        return try {
            val orderStatus = bithumbPrivateApi.getOrder(closeOrderId)

            when (orderStatus?.state) {
                "done" -> {
                    val actualPrice = orderStatus.price?.toDouble() ?: position.exitPrice ?: 0.0
                    onOrderDone(actualPrice)
                    true
                }
                "cancel" -> {
                    log.warn("[$market] 청산 주문 취소됨: $closeOrderId")
                    onOrderCancelled()
                    false
                }
                "wait" -> {
                    val elapsed = Duration.between(
                        position.lastCloseAttempt ?: Instant.now(),
                        Instant.now()
                    ).seconds

                    if (elapsed > waitTimeoutSeconds) {
                        log.warn("[$market] 청산 주문 ${waitTimeoutSeconds}초 미체결, 취소 시도: $closeOrderId")
                        try {
                            bithumbPrivateApi.cancelOrder(closeOrderId)
                            log.info("[$market] 청산 주문 취소 완료: $closeOrderId")
                        } catch (e: Exception) {
                            log.warn("[$market] 주문 취소 실패: ${e.message}")
                        }
                        onOrderCancelled()
                    }
                    false
                }
                else -> {
                    log.debug("[$market] 청산 주문 상태: ${orderStatus?.state}")
                    false
                }
            }
        } catch (e: Exception) {
            log.error("[$market] 청산 주문 상태 조회 실패: ${e.message}")
            val elapsed = Duration.between(
                position.lastCloseAttempt ?: Instant.now(),
                Instant.now()
            ).toMinutes()

            if (elapsed >= errorTimeoutMinutes) {
                log.warn("[$market] ${errorTimeoutMinutes}분 이상 CLOSING 상태, OPEN으로 복원")
                onOrderCancelled()
            }
            false
        }
    }
}

/**
 * 포지션 상태 확인 헬퍼
 */
object PositionStates {
    const val OPEN = "OPEN"
    const val CLOSING = "CLOSING"
    const val CLOSED = "CLOSED"
    const val ABANDONED = "ABANDONED"

    fun isOpen(status: String?) = status == OPEN
    fun isClosing(status: String?) = status == CLOSING
    fun isClosed(status: String?) = status == CLOSED || status == ABANDONED
    fun isTerminal(status: String?) = isClosed(status)
}
