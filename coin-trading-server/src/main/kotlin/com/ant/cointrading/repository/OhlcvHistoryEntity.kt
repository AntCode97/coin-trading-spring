package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant

/**
 * 과거 OHLCV 캔들 데이터 Entity
 */
@Entity
@Table(
    name = "ohlcv_history",
    indexes = [
        Index(name = "idx_market_interval", columnList = "market,interval"),
        Index(name = "idx_timestamp", columnList = "timestamp"),
        Index(name = "idx_market_interval_timestamp", columnList = "market,interval,timestamp")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_market_interval_time", columnNames = ["market", "interval", "timestamp"])
    ]
)
class OhlcvHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "market", nullable = false, length = 20)
    var market: String = ""

    @Column(name = "interval", nullable = false, length = 20)
    var interval: String = ""

    @Column(name = "timestamp", nullable = false)
    var timestamp: Long = 0L

    @Column(name = "open", nullable = false)
    var open: Double = 0.0

    @Column(name = "high", nullable = false)
    var high: Double = 0.0

    @Column(name = "low", nullable = false)
    var low: Double = 0.0

    @Column(name = "close", nullable = false)
    var close: Double = 0.0

    @Column(name = "volume", nullable = false)
    var volume: Double = 0.0

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /**
     * Candle 모델로 변환
     */
    fun toCandle() = com.ant.cointrading.model.Candle(
        timestamp = Instant.ofEpochMilli(timestamp),
        open = java.math.BigDecimal.valueOf(open),
        high = java.math.BigDecimal.valueOf(high),
        low = java.math.BigDecimal.valueOf(low),
        close = java.math.BigDecimal.valueOf(close),
        volume = java.math.BigDecimal.valueOf(volume)
    )
}
