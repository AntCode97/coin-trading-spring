package com.ant.cointrading.repository

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 거래 상세 Entity
 */
@Entity
@Table(name = "backtest_trades", indexes = [
    Index(name = "idx_backtest_result", columnList = "backtest_result_id"),
    Index(name = "idx_market", columnList = "market"),
    Index(name = "idx_entry_time", columnList = "entry_time")
])
class BacktestTradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "backtest_result_id", nullable = false)
    var backtestResultId: Long = 0L

    @Column(name = "market", nullable = false, length = 20)
    var market: String = ""

    @Column(name = "entry_time", nullable = false)
    var entryTime: Instant = Instant.now()

    @Column(name = "exit_time")
    var exitTime: Instant? = null

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    var entryPrice: Double = 0.0

    @Column(name = "exit_price", precision = 20, scale = 8)
    var exitPrice: Double? = null

    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    var quantity: Double = 0.0

    @Column(name = "pnl", precision = 20, scale = 8)
    var pnl: Double? = null

    @Column(name = "pnl_percent", precision = 10, scale = 4)
    var pnlPercent: Double? = null

    @Column(name = "entry_reason", length = 500)
    var entryReason: String? = null

    @Column(name = "exit_reason", length = 100)
    var exitReason: String? = null

    @Column(name = "holding_period_minutes")
    var holdingPeriodMinutes: Int? = null

    /**
     * 보유 기간 계산 (분)
     */
    fun calculateHoldingPeriod(): Int? {
        return if (exitTime != null) {
            java.time.Duration.between(entryTime, exitTime).toMinutes().toInt()
        } else null
    }
}
