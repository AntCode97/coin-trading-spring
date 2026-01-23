package com.ant.cointrading.model

import java.math.BigDecimal
import java.time.Instant

/**
 * 캔들 데이터
 */
data class Candle(
    val timestamp: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal
)

/**
 * 실시간 틱 데이터
 */
data class Tick(
    val market: String,
    val price: BigDecimal,
    val volume: BigDecimal,
    val timestamp: Instant,
    val change: String,           // RISE, EVEN, FALL
    val changeRate: BigDecimal,
    val tradeVolume24h: BigDecimal
)

/**
 * 호가 데이터
 */
data class OrderBookUnit(
    val price: BigDecimal,
    val quantity: BigDecimal
)

data class OrderBook(
    val market: String,
    val timestamp: Instant,
    val asks: List<OrderBookUnit>,  // 매도 호가
    val bids: List<OrderBookUnit>   // 매수 호가
)

/**
 * 트레이딩 신호
 */
enum class SignalAction {
    BUY, SELL, HOLD
}

data class TradingSignal(
    val market: String,
    val action: SignalAction,
    val confidence: Double,         // 0.0 ~ 100.0
    val price: BigDecimal,
    val reason: String,
    val strategy: String,
    val regime: String? = null,     // MarketRegime name (BULL_TREND, BEAR_TREND, SIDEWAYS, HIGH_VOLATILITY)
    val timestamp: Instant = Instant.now()
)

/**
 * 주문 정보
 */
enum class OrderType {
    LIMIT, MARKET
}

enum class OrderSide {
    BUY, SELL
}

enum class OrderStatus {
    PENDING, FILLED, PARTIALLY_FILLED, CANCELLED, FAILED
}

data class Order(
    val id: String? = null,
    val market: String,
    val side: OrderSide,
    val type: OrderType,
    val price: BigDecimal?,         // 시장가 주문 시 null
    val quantity: BigDecimal,
    val filledQuantity: BigDecimal = BigDecimal.ZERO,
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 포지션 정보
 */
data class Position(
    val market: String,
    val quantity: BigDecimal,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: Double
)

/**
 * 잔고 정보
 */
data class Balance(
    val currency: String,
    val available: BigDecimal,
    val locked: BigDecimal,
    val total: BigDecimal
)

/**
 * 시장 레짐
 */
enum class MarketRegime {
    BULL_TREND,      // 상승 추세
    BEAR_TREND,      // 하락 추세
    SIDEWAYS,        // 횡보
    HIGH_VOLATILITY  // 고변동성
}

data class RegimeAnalysis(
    val regime: MarketRegime,
    val confidence: Double,
    val adx: Double,
    val atr: Double,
    val atrPercent: Double,
    val trendDirection: Int,  // 1: 상승, -1: 하락, 0: 중립
    val timestamp: Instant = Instant.now()
)

/**
 * 마켓 상태 (종합)
 */
data class MarketStatus(
    val market: String,
    val currentPrice: BigDecimal,
    val regime: RegimeAnalysis,
    val strategy: String,
    val lastSignal: TradingSignal?,
    val position: Position?,
    val dailyPnl: BigDecimal,
    val dailyPnlPercent: Double,
    val timestamp: Instant = Instant.now()
)

/**
 * 리스크 통계
 */
data class RiskStats(
    val market: String,
    val totalTrades: Int,
    val winRate: Double,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val profitFactor: Double,
    val maxDrawdown: Double,
    val currentDrawdown: Double,
    val consecutiveLosses: Int,
    val kellyFraction: Double,
    val suggestedPositionSize: BigDecimal
)
