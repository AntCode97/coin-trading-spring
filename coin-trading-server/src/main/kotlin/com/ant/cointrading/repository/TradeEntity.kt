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
            fee = amount.multiply(BigDecimal("0.0025")).toDouble(), // 0.25% 수수료 가정
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
 *
 * 20년차 퀀트의 교훈:
 * - 슬리피지 기록은 필수 (수익의 상당 부분이 여기서 사라진다)
 * - 부분 체결 여부 추적 (유동성 문제 조기 감지)
 */
@Entity
@Table(name = "trades", indexes = [
    Index(name = "idx_trades_market", columnList = "market"),
    Index(name = "idx_trades_created_at", columnList = "createdAt"),
    Index(name = "idx_trades_strategy", columnList = "strategy")
])
data class TradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 64)
    val orderId: String,

    @Column(nullable = false, length = 20)
    val market: String,

    @Column(nullable = false, length = 10)
    val side: String,        // BUY, SELL

    @Column(nullable = false, length = 10)
    val type: String,        // MARKET, LIMIT

    @Column(nullable = false)
    val price: Double,

    @Column(nullable = false)
    val quantity: Double,

    @Column(nullable = false)
    val totalAmount: Double,

    @Column(nullable = false)
    val fee: Double,

    @Column
    val slippage: Double? = null,      // 슬리피지 % (NEW)

    @Column
    val isPartialFill: Boolean? = null, // 부분 체결 여부 (NEW)

    @Column
    val pnl: Double?,        // 손익 (매도 시)

    @Column
    val pnlPercent: Double?, // 손익률

    @Column(nullable = false, length = 30)
    val strategy: String,    // 사용된 전략

    @Column(length = 30)
    val regime: String?,     // 시장 레짐

    @Column(nullable = false)
    val confidence: Double,  // 신뢰도

    @Column(nullable = false, length = 500)
    val reason: String,      // 거래 이유

    @Column(nullable = false)
    val simulated: Boolean,  // 시뮬레이션 여부

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * 일일 통계 엔티티
 */
@Entity
@Table(name = "daily_stats", indexes = [
    Index(name = "idx_daily_stats_date_market", columnList = "date, market", unique = true)
])
data class DailyStatsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 10)
    val date: String,        // YYYY-MM-DD

    @Column(nullable = false, length = 20)
    val market: String,

    @Column(nullable = false)
    val totalTrades: Int,

    @Column(nullable = false)
    val winTrades: Int,

    @Column(nullable = false)
    val lossTrades: Int,

    @Column(nullable = false)
    val totalPnl: Double,

    @Column(nullable = false)
    val winRate: Double,

    @Column(nullable = false)
    val avgPnlPercent: Double,

    @Column(nullable = false)
    val maxDrawdown: Double,

    @Column(length = 1000)
    val strategiesJson: String? = null,

    @Column(nullable = false)
    val updatedAt: Instant = Instant.now()
)
