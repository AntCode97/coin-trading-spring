package com.ant.cointrading.repository

/**
 * 포지션 기반 트레이드 공통 확장 함수
 *
 * VolumeSurgeTradeEntity, MemeScalperTradeEntity, BacktestTradeEntity
 * 모두 포지션 기반 엔티티로, 진입/청산과 PnL 추적을 공유한다.
 */

/**
 * 안전한 PnL 계산 (%) - 진입가 0 방지 + NaN/Infinity 체크
 */
fun safePnlPercent(entryPrice: Double, exitPrice: Double): Double {
    if (entryPrice <= 0) return 0.0
    val pnl = ((exitPrice - entryPrice) / entryPrice) * 100
    return if (pnl.isNaN() || pnl.isInfinite()) 0.0 else pnl
}

/**
 * 안전한 PnL 금액 계산 (KRW) - NaN/Infinity 체크
 */
fun safePnlAmount(entryPrice: Double, exitPrice: Double, quantity: Double): Double {
    val pnl = (exitPrice - entryPrice) * quantity
    return if (pnl.isNaN() || pnl.isInfinite()) 0.0 else pnl
}
