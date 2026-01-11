package com.ant.cointrading.repository

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 거래 기록 저장용 데이터 클래스
 */
data class TradeRecord(
    val market: String,
    val side: String,
    val orderType: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val amount: BigDecimal,
    val orderId: String,
    val status: String,
    val strategy: String,
    val signalConfidence: Double,
    val signalReason: String,
    val isSimulated: Boolean,
    val realizedPnl: BigDecimal? = null
) {
    fun toEntity(): TradeEntity {
        return TradeEntity(
            orderId = orderId,
            market = market,
            side = side,
            type = orderType,
            price = price.toDouble(),
            quantity = quantity.toDouble(),
            totalAmount = amount.toDouble(),
            fee = amount.multiply(BigDecimal("0.0004")).toDouble(),  // 빗썸 수수료 0.04%
            pnl = realizedPnl?.toDouble(),
            pnlPercent = null,
            strategy = strategy,
            regime = null,
            confidence = signalConfidence,
            reason = signalReason,
            simulated = isSimulated
        )
    }
}

/**
 * 거래 기록 엔티티
 * 모든 매수/매도 거래 내역을 저장
 */
@Entity
@Table(name = "trades", indexes = [
    Index(name = "idx_trades_market", columnList = "market"),
    Index(name = "idx_trades_created_at", columnList = "createdAt"),
    Index(name = "idx_trades_strategy", columnList = "strategy")
])
class TradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** Bithumb 주문 ID */
    @Column(nullable = false, length = 64)
    var orderId: String = "",

    /** 거래 마켓 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 거래 방향 (BUY/SELL) */
    @Column(nullable = false, length = 10)
    var side: String = "",

    /** 주문 유형 (LIMIT/MARKET) */
    @Column(nullable = false, length = 10)
    var type: String = "",

    /** 체결 가격 (KRW) */
    @Column(nullable = false)
    var price: Double = 0.0,

    /** 체결 수량 */
    @Column(nullable = false)
    var quantity: Double = 0.0,

    /** 총 거래 금액 (KRW) */
    @Column(nullable = false)
    var totalAmount: Double = 0.0,

    /** 거래 수수료 (KRW) */
    @Column(nullable = false)
    var fee: Double = 0.0,

    /** 슬리피지 (%, 기준가 대비 체결가 차이) */
    @Column
    var slippage: Double? = null,

    /** 부분 체결 여부 */
    @Column
    var isPartialFill: Boolean? = null,

    /** 실현 손익 (KRW) */
    @Column
    var pnl: Double? = null,

    /** 실현 손익률 (%) */
    @Column
    var pnlPercent: Double? = null,

    /** 사용 전략 (DCA/GRID/MEAN_REVERSION/ORDER_BOOK) */
    @Column(nullable = false, length = 30)
    var strategy: String = "",

    /** 시장 레짐 (SIDEWAYS/BULL_TREND/BEAR_TREND/HIGH_VOLATILITY) */
    @Column(length = 30)
    var regime: String? = null,

    /** 신호 신뢰도 (0.0~1.0) */
    @Column(nullable = false)
    var confidence: Double = 0.0,

    /** 거래 사유 */
    @Column(nullable = false, length = 500)
    var reason: String = "",

    /** 시뮬레이션 여부 (true=모의거래) */
    @Column(nullable = false)
    var simulated: Boolean = false,

    /** 거래 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
)

/**
 * 일일 거래 통계 엔티티
 * 날짜/마켓별 거래 실적 집계
 */
@Entity
@Table(name = "daily_stats", indexes = [
    Index(name = "idx_daily_stats_date_market", columnList = "date, market", unique = true)
])
class DailyStatsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 날짜 (YYYY-MM-DD) */
    @Column(nullable = false, length = 10)
    var date: String = "",

    /** 거래 마켓 */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 총 거래 횟수 */
    @Column(nullable = false)
    var totalTrades: Int = 0,

    /** 수익 거래 횟수 */
    @Column(nullable = false)
    var winTrades: Int = 0,

    /** 손실 거래 횟수 */
    @Column(nullable = false)
    var lossTrades: Int = 0,

    /** 총 손익 (KRW) */
    @Column(nullable = false)
    var totalPnl: Double = 0.0,

    /** 승률 (0.0~1.0) */
    @Column(nullable = false)
    var winRate: Double = 0.0,

    /** 평균 손익률 (%) */
    @Column(nullable = false)
    var avgPnlPercent: Double = 0.0,

    /** 최대 낙폭 (%) */
    @Column(nullable = false)
    var maxDrawdown: Double = 0.0,

    /** 전략별 통계 (JSON) */
    @Column(length = 1000)
    var strategiesJson: String? = null,

    /** 마지막 갱신 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
