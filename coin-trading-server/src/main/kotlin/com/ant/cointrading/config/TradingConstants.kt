package com.ant.cointrading.config

import java.math.BigDecimal

/**
 * 거래 관련 공통 상수 (켄트백 스타일: 단일 진공의 원칙)
 */
object TradingConstants {
    // === 거래소 ===
    const val EXCHANGE_BITHUMB = "BITHUMB"
    const val EXCHANGE_BINANCE = "BINANCE"

    // === 수수료 ===
    val BITHUMB_FEE_RATE = BigDecimal("0.0004")  // 0.04% (VIP 등급에 따라 변경 가능)

    // === 주문 관련 ===
    val MIN_ORDER_AMOUNT_KRW = BigDecimal("5100")  // 빗썸 최소 주문 금액 (수수료 0.08% 완화 고려)
    const val ORDER_TIMEOUT_MS = 5000L
    const val MAX_ORDER_RETRIES = 3

    // === 포지션 관리 (기본값) ===
    const val DEFAULT_STOP_LOSS_PERCENT = -2.0
    const val DEFAULT_TAKE_PROFIT_PERCENT = 5.0
    const val DEFAULT_TRAILING_STOP_TRIGGER = 2.0
    const val DEFAULT_TRAILING_STOP_OFFSET = 1.0

    // === 포지션 청산 관련 ===
    const val CLOSE_RETRY_BACKOFF_SECONDS = 10L      // 청산 재시도 백오프
    const val MAX_CLOSE_ATTEMPTS = 5                 // 최대 청산 시도 횟수
    const val MIN_HOLDING_SECONDS = 10L              // 최소 보유 시간 (수수료 0.08% 고려)

    // === 시장가 주문 사용 조건 (퀀트 지식 기반) ===
    const val HIGH_VOLATILITY_THRESHOLD = 1.5       // 1분 변동성 1.5% 초과 시 시장가
    const val HIGH_CONFIDENCE_THRESHOLD = 85.0      // 신뢰도 85% 초과 시 시장가
    const val THIN_LIQUIDITY_THRESHOLD = 5.0        // 유동성 배율 5 미만 시 시장가

    // === 시장가를 선호하는 전략들 (빠른 체결이 중요) ===
    val MARKET_ORDER_STRATEGIES = setOf(
        "ORDER_BOOK_IMBALANCE",  // 초단기 전략
        "MOMENTUM",              // 모멘텀 전략
        "BREAKOUT",              // 돌파 전략
        "MEME_SCALPER"           // 세력 코인 초단타
    )

    // === 슬리피지 임계값 ===
    const val SLIPPAGE_WARNING_PERCENT = 0.5        // 0.5% 초과 시 경고
    const val SLIPPAGE_CRITICAL_PERCENT = 2.0       // 2.0% 초과 시 중요 경고

    // === 부분 체결 임계값 ===
    const val PARTIAL_FILL_SUCCESS_THRESHOLD = 0.9  // 90% 이상 체결이면 성공으로 간주

    // === 주문 상태 ===
    const val ORDER_STATE_DONE = "done"
    const val ORDER_STATE_WAIT = "wait"
    const val ORDER_STATE_CANCEL = "cancel"

    // === 포지션 상태 ===
    const val POSITION_STATUS_OPEN = "OPEN"
    const val POSITION_STATUS_CLOSING = "CLOSING"
    const val POSITION_STATUS_CLOSED = "CLOSED"
    const val POSITION_STATUS_ABANDONED = "ABANDONED"

    // === 쿨다운 ===
    const val DEFAULT_COOLDOWN_MINUTES = 5L
    const val DEFAULT_COOLDOWN_SECONDS = 30L       // 진입 실패 후 쿨다운

    // === 서킷 브레이커 ===
    const val DEFAULT_MAX_CONSECUTIVE_LOSSES = 3
    const val DEFAULT_DAILY_MAX_LOSS_KRW = 20000
}
