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
     * 진입가 무결성 검증
     */
    fun isInvalidEntryPrice(entryPrice: Double): Boolean = entryPrice <= 0

    /**
     * 최소 보유 시간 경과 여부
     */
    fun hasMinHoldingPeriodElapsed(entryTime: Instant, minSeconds: Long): Boolean {
        val holdingSeconds = java.time.Duration.between(entryTime, Instant.now()).seconds
        return holdingSeconds >= minSeconds
    }

    /**
     * 손절 도달 여부
     */
    fun isStopLossTriggered(pnlPercent: Double, stopLossPercent: Double): Boolean {
        return pnlPercent <= stopLossPercent
    }

    /**
     * 익절 도달 여부
     */
    fun isTakeProfitTriggered(pnlPercent: Double, takeProfitPercent: Double): Boolean {
        return pnlPercent >= takeProfitPercent
    }

    /**
     * 타임아웃 도달 여부
     */
    fun isTimeout(entryTime: Instant, timeoutMinutes: Int): Boolean {
        val holdingMinutes = java.time.Duration.between(entryTime, Instant.now()).toMinutes()
        return holdingMinutes >= timeoutMinutes
    }

    /**
     * ABANDONED 사유 생성
     */
    fun abandonedReason(reason: String): String = "ABANDONED_$reason"

    /**
     * 청산 가능 여부 체크 (중복 청산 방지)
     *
     * [버그 수정] CLOSING 상태에서도 백오프 경과 후 재시도 허용
     * 기존: CLOSING 상태면 항상 false 반환 (재시도 불가능)
     * 수정: 백오프 시간 경과 시 true 반환 (재시도 가능)
     */
    fun canClosePosition(
        status: String,
        lastCloseAttempt: Instant?,
        backoffSeconds: Long
    ): Boolean {
        if (status == "CLOSING") {
            val lastAttempt = lastCloseAttempt ?: return false
            val elapsed = Duration.between(lastAttempt, Instant.now()).seconds
            return elapsed >= backoffSeconds
        }

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
     * 마켓 형식 변환 (BTC_KRW -> KRW-BTC)
     */
    fun convertToApiMarket(market: String): String {
        return market.split("_").let { parts ->
            if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
        }
    }

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
                    // 체결 수량 확인 - 0이면 실제 체결 안 됨
                    val executedVolume = orderStatus.executedVolume?.toDouble() ?: 0.0
                    if (executedVolume <= 0) {
                        log.warn("[$market] 주문 done이지만 체결 수량 0 - 실제 미체결, 잔고 확인 후 재매도 필요")

                        // 잔고 확인 - 여전히 코인이 있으면 재매도 필요
                        val coinSymbol = extractCoinSymbol(market)
                        val actualBalance = try {
                            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
                        } catch (e: Exception) {
                            log.warn("[$market] 잔고 조회 실패: ${e.message}")
                            BigDecimal.ZERO
                        }

                        if (actualBalance > BigDecimal.ZERO) {
                            log.info("[$market] 잔고 여전히 존재 (${actualBalance}) - 재매도 필요")
                            onOrderCancelled()  // OPEN으로 복원하여 재매도 유도
                            return false
                        }

                        // 잔고도 없으면 실제로 체결된 것으로 간주
                        log.info("[$market] 잔고 0 - 체결 완료로 간주")
                        val actualPrice = orderStatus.price?.toDouble() ?: position.exitPrice ?: position.entryPrice
                        onOrderDone(actualPrice)
                        return true
                    }

                    // 체결 수량이 있으면 정상 체결
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
