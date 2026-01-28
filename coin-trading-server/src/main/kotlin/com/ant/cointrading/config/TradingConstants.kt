package com.ant.cointrading.config

import java.math.BigDecimal

/**
 * 거래 관련 공통 상수
 */
object TradingConstants {
    // 거래소
    const val EXCHANGE_BITHUMB = "BITHUMB"
    const val EXCHANGE_BINANCE = "BINANCE"

    // 빗썸 수수료 (VIP 등급에 따라 변경 가능)
    val BITHUMB_FEE_RATE = BigDecimal("0.0004")  // 0.04%

    // 주문 관련
    const val MIN_ORDER_AMOUNT_KRW = 5100  // 빗썸 최소 주문 금액
    const val ORDER_TIMEOUT_MS = 5000L
    const val MAX_ORDER_RETRIES = 3

    // 포지션 관리 (기본값)
    const val DEFAULT_STOP_LOSS_PERCENT = -2.0
    const val DEFAULT_TAKE_PROFIT_PERCENT = 5.0
    const val DEFAULT_TRAILING_STOP_TRIGGER = 2.0
    const val DEFAULT_TRAILING_STOP_OFFSET = 1.0

    // 쿨다운
    const val DEFAULT_COOLDOWN_MINUTES = 5L
}
