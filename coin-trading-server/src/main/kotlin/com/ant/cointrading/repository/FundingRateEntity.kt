package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

/**
 * 펀딩 비율 히스토리 Entity
 *
 * Binance/Bybit 등 거래소에서 수집한 펀딩 비율 데이터 저장.
 */
@Entity
@Table(
    name = "funding_rates",
    indexes = [
        Index(name = "idx_funding_symbol_time", columnList = "symbol, fundingTime")
    ]
)
data class FundingRateEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 20)
    val exchange: String,           // "BINANCE", "BYBIT"

    @Column(nullable = false, length = 20)
    val symbol: String,             // "BTCUSDT"

    @Column(nullable = false)
    val fundingRate: Double,        // 0.0001 = 0.01%

    @Column(nullable = false)
    val fundingTime: Instant,       // 펀딩 시간

    @Column
    val annualizedRate: Double? = null,  // 연환산 수익률 (%)

    @Column
    val markPrice: Double? = null,

    @Column
    val indexPrice: Double? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * 펀딩 차익거래 포지션 Entity
 */
@Entity
@Table(
    name = "funding_arb_positions",
    indexes = [
        Index(name = "idx_arb_status", columnList = "status")
    ]
)
data class FundingArbPositionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 20)
    val symbol: String,             // "BTCUSDT"

    @Column(nullable = false, length = 20)
    val spotExchange: String,       // "BITHUMB"

    @Column(nullable = false, length = 20)
    val perpExchange: String,       // "BINANCE"

    @Column(nullable = false)
    val spotQuantity: Double,       // 현물 수량

    @Column(nullable = false)
    val perpQuantity: Double,       // 선물 수량

    @Column
    val spotEntryPrice: Double? = null,

    @Column
    val perpEntryPrice: Double? = null,

    @Column
    val entryFundingRate: Double? = null,

    @Column(nullable = false)
    val entryTime: Instant,

    @Column
    var exitTime: Instant? = null,

    @Column
    var totalFundingReceived: Double = 0.0,

    @Column
    var totalTradingCost: Double = 0.0,

    @Column
    var netPnl: Double? = null,

    @Column(nullable = false, length = 20)
    var status: String = "OPEN",    // OPEN, CLOSED, LIQUIDATED

    @Column
    var exitReason: String? = null, // FUNDING_COLLECTED, RATE_REVERSAL, DELTA_IMBALANCE, MANUAL

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column
    var updatedAt: Instant = Instant.now()
)

/**
 * 펀딩 수령 기록 Entity
 */
@Entity
@Table(name = "funding_payments")
data class FundingPaymentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val positionId: Long,           // FK to funding_arb_positions

    @Column(nullable = false, length = 20)
    val exchange: String,

    @Column(nullable = false, length = 20)
    val symbol: String,

    @Column(nullable = false)
    val paymentAmount: Double,      // 수령/지불 금액 (양수=수령, 음수=지불)

    @Column(nullable = false)
    val fundingRate: Double,

    @Column(nullable = false)
    val paymentTime: Instant,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * 펀딩 일일 통계 Entity
 */
@Entity
@Table(
    name = "funding_daily_stats",
    indexes = [
        Index(name = "idx_funding_stats_date", columnList = "date", unique = true)
    ]
)
data class FundingDailyStatsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val date: LocalDate,

    @Column
    val totalPositions: Int = 0,

    @Column
    val activePositions: Int = 0,

    @Column
    val fundingCollected: Double = 0.0,

    @Column
    val tradingCosts: Double = 0.0,

    @Column
    val netPnl: Double = 0.0,

    @Column
    val avgFundingRate: Double? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
