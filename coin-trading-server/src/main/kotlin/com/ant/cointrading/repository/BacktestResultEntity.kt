package com.ant.cointrading.repository

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

/**
 * 백테스트 결과 Entity
 */
@Entity
@Table(name = "backtest_results", indexes = [
    Index(name = "idx_strategy_market", columnList = "strategy_name,market"),
    Index(name = "idx_date_range", columnList = "start_date,end_date"),
    Index(name = "idx_created_at", columnList = "created_at")
])
class BacktestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "strategy_name", nullable = false, length = 50)
    var strategyName: String = ""

    @Column(name = "market", nullable = false, length = 20)
    var market: String = ""

    @Column(name = "start_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    var startDate: LocalDate = LocalDate.now()

    @Column(name = "end_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    var endDate: LocalDate = LocalDate.now()

    @Column(name = "parameters", nullable = false, columnDefinition = "JSON")
    var parameters: String = "{}"

    @Column(name = "total_trades", nullable = false)
    var totalTrades: Int = 0

    @Column(name = "winning_trades", nullable = false)
    var winningTrades: Int = 0

    @Column(name = "losing_trades", nullable = false)
    var losingTrades: Int = 0

    @Column(name = "total_return", nullable = false)
    var totalReturn: Double = 0.0

    @Column(name = "max_drawdown", nullable = false)
    var maxDrawdown: Double = 0.0

    @Column(name = "sharpe_ratio", nullable = false)
    var sharpeRatio: Double = 0.0

    @Column(name = "profit_factor")
    var profitFactor: Double? = null

    @Column(name = "avg_win")
    var avgWin: Double? = null

    @Column(name = "avg_loss")
    var avgLoss: Double? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /**
     * 승률 계산
     */
    fun getWinRate(): Double {
        return if (totalTrades > 0) {
            (winningTrades.toDouble() / totalTrades.toDouble()) * 100
        } else 0.0
    }
}
