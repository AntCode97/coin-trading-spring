package com.ant.cointrading.engine

import java.math.BigDecimal
import java.time.Instant

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
 * 포지션 청산 관련 헬퍼 함수
 *
 * 중복 제거를 위한 유틸리티 함수들
 */
object PositionHelper {

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
            val elapsed = java.time.Duration.between(lastCloseAttempt, Instant.now()).seconds
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
