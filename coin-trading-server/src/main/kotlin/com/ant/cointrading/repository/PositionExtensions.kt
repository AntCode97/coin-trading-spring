package com.ant.cointrading.repository

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 포지션 기반 트레이드 공통 확장 함수
 *
 * VolumeSurgeTradeEntity, MemeScalperTradeEntity, BacktestTradeEntity
 * 모두 포지션 기반 엔티티로, 진입/청산과 PnL 추적을 공유한다.
 */

/**
 * 포지션 보유 기간 (초)
 */
fun positionHoldingSeconds(entryTime: Instant, exitTime: Instant?): Long? {
    return exitTime?.let { ChronoUnit.SECONDS.between(entryTime, it) }
}

/**
 * 포지션 보유 기간 (분)
 */
fun positionHoldingMinutes(entryTime: Instant, exitTime: Instant?): Double? {
    val seconds = positionHoldingSeconds(entryTime, exitTime) ?: return null
    return seconds / 60.0
}

/**
 * 안전한 PnL 계산 (%) - 진입가 0 방지
 */
fun safePnlPercent(entryPrice: Double, exitPrice: Double): Double {
    if (entryPrice <= 0) return 0.0
    val pnl = ((exitPrice - entryPrice) / entryPrice) * 100
    return if (pnl.isNaN() || pnl.isInfinite()) 0.0 else pnl
}

/**
 * 안전한 PnL 금액 계산 (KRW)
 */
fun safePnlAmount(entryPrice: Double, exitPrice: Double, quantity: Double): Double {
    val pnl = (exitPrice - entryPrice) * quantity
    return if (pnl.isNaN() || pnl.isInfinite()) 0.0 else pnl
}

/**
 * 수익 여부 (PnL > 0)
 */
fun isProfitable(pnlAmount: Double?): Boolean {
    return pnlAmount != null && pnlAmount > 0
}

/**
 * 손실 여부 (PnL <= 0)
 */
fun isLoss(pnlAmount: Double?): Boolean {
    return pnlAmount != null && pnlAmount <= 0
}

/**
 * 포지션 열림 여부
 */
fun isOpen(status: String?): Boolean {
    return status == "OPEN" || status == "CLOSING"
}

/**
 * 포지션 종료 여부
 */
fun isClosed(status: String?): Boolean {
    return status == "CLOSED" || status == "ABANDONED"
}

/**
 * 청산 사유가 손절인지
 */
fun isStopLoss(exitReason: String?): Boolean {
    return exitReason?.startsWith("STOP_LOSS") == true ||
           exitReason?.startsWith("ABANDONED") == true
}

/**
 * 청산 사유가 익절인지
 */
fun isTakeProfit(exitReason: String?): Boolean {
    return exitReason == "TAKE_PROFIT" || exitReason == "TRAILING_STOP"
}

/**
 * 포지션 문자열 포맷 (Slack/로깅용)
 */
fun formatPosition(
    market: String,
    entryPrice: Double,
    exitPrice: Double?,
    pnlAmount: Double?,
    pnlPercent: Double?,
    exitReason: String?,
    entryTime: Instant,
    exitTime: Instant?
): String {
    val emoji = if ((pnlAmount ?: 0.0) >= 0) "+" else ""
    val holdingSec = positionHoldingSeconds(entryTime, exitTime)
    return """
        [$market] 포지션
        진입: ${entryPrice}원 → 청산: ${exitPrice ?: "진행중"}원
        손익: ${emoji}${pnlAmount?.let { String.format("%.0f", it) } ?: "N/A"}원 (${pnlPercent?.let { String.format("%.2f", it) } ?: "N/A"}%)
        사유: ${exitReason ?: "N/A"}
        보유: ${holdingSec?.let { String.format("%.0f", it) } ?: "N/A"}초
    """.trimIndent()
}
