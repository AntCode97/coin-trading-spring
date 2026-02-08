package com.ant.cointrading.risk

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.repository.TradeEntity
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * 리스크 관리자
 *
 * - Kelly Criterion 기반 포지션 사이징
 * - 최대 낙폭 (Max Drawdown) 제한
 * - 일일 손실 한도
 * - 연속 손실 제한
 */
@Component
class RiskManager(
    private val tradingProperties: TradingProperties,
    private val tradeRepository: TradeRepository
) {

    // 마켓별 거래 통계 캐시
    private val statsCache = ConcurrentHashMap<String, TradeStats>()

    // 초기 자본 (최초 잔고 기록용)
    private val initialCapital = ConcurrentHashMap<String, BigDecimal>()

    // 최고 자본 (Drawdown 계산용)
    private val peakCapital = ConcurrentHashMap<String, BigDecimal>()

    data class TradeStats(
        var totalTrades: Int = 0,
        var winCount: Int = 0,
        var lossCount: Int = 0,
        var totalProfit: BigDecimal = BigDecimal.ZERO,
        var totalLoss: BigDecimal = BigDecimal.ZERO,
        var consecutiveLosses: Int = 0,
        var maxConsecutiveLosses: Int = 0,
        var lastUpdated: Instant = Instant.now()
    )

    /**
     * 거래 가능 여부 체크
     */
    fun canTrade(market: String, currentBalance: BigDecimal): RiskCheckResult {
        val stats = getStats(market)
        val config = tradingProperties

        // 1. 최대 낙폭 체크
        val peak = peakCapital.getOrPut(market) { currentBalance }
        if (currentBalance > peak) {
            peakCapital[market] = currentBalance
        }

        val drawdown = if (peak > BigDecimal.ZERO) {
            ((peak - currentBalance) / peak * BigDecimal(100)).toDouble()
        } else 0.0

        if (drawdown >= config.maxDrawdownPercent) {
            return RiskCheckResult(
                canTrade = false,
                reason = "최대 낙폭 초과 (${String.format("%.1f", drawdown)}% >= ${config.maxDrawdownPercent}%)",
                currentDrawdown = drawdown
            )
        }

        // 2. 일일 손실 한도 체크
        val dailyPnl = calculateDailyPnl(market)
        val dailyLossPercent = if (currentBalance > BigDecimal.ZERO) {
            (dailyPnl / currentBalance * BigDecimal(100)).toDouble()
        } else 0.0

        if (dailyLossPercent <= -3.0) {  // 일일 3% 손실 한도
            return RiskCheckResult(
                canTrade = false,
                reason = "일일 손실 한도 초과 (${String.format("%.1f", dailyLossPercent)}%)",
                dailyPnlPercent = dailyLossPercent
            )
        }

        // 3. 연속 손실 체크
        if (stats.consecutiveLosses >= 3) {
            return RiskCheckResult(
                canTrade = false,
                reason = "연속 손실 ${stats.consecutiveLosses}회 - 쿨다운 필요",
                consecutiveLosses = stats.consecutiveLosses
            )
        }

        return RiskCheckResult(
            canTrade = true,
            reason = "거래 가능",
            currentDrawdown = drawdown,
            dailyPnlPercent = dailyLossPercent,
            consecutiveLosses = stats.consecutiveLosses
        )
    }

    /**
     * Kelly Criterion 기반 포지션 사이징
     *
     * Kelly% = W - [(1-W) / R]
     * W = 승률
     * R = 평균 이익 / 평균 손실
     *
     * Half Kelly 사용 (보수적)
     */
    fun calculatePositionSize(
        market: String,
        currentBalance: BigDecimal,
        signalConfidence: Double
    ): BigDecimal {
        val stats = getStats(market)
        val config = tradingProperties

        // 기본 주문 금액
        val baseAmount = config.orderAmountKrw

        // 충분한 거래 기록이 없으면 기본 금액 사용
        if (stats.totalTrades < 10) {
            val size = baseAmount.multiply(BigDecimal(signalConfidence / 100))
                .setScale(0, RoundingMode.DOWN)
                .coerceAtLeast(BigDecimal(5100))  // 최소 주문 금액 보장
                .coerceAtMost(baseAmount)
                .coerceAtMost(currentBalance)     // 잔고 초과 방지
            return size
        }

        // 승률 계산
        val winRate = if (stats.totalTrades > 0) {
            stats.winCount.toDouble() / stats.totalTrades
        } else 0.5

        // 손익비 계산
        val avgWin = if (stats.winCount > 0) {
            stats.totalProfit / BigDecimal(stats.winCount)
        } else BigDecimal.ZERO

        val avgLoss = if (stats.lossCount > 0) {
            stats.totalLoss.abs() / BigDecimal(stats.lossCount)
        } else BigDecimal.ONE

        val profitRatio = if (avgLoss > BigDecimal.ZERO) {
            (avgWin / avgLoss).toDouble()
        } else 1.0

        // Kelly 공식
        var kelly = winRate - ((1 - winRate) / profitRatio)
        kelly = max(0.0, min(kelly, 0.25))  // 0 ~ 25% 제한

        // Half Kelly (보수적)
        val halfKelly = kelly / 2

        // 신뢰도 반영
        val adjustedKelly = halfKelly * (signalConfidence / 100)

        // 최종 포지션 크기
        val positionSize = currentBalance.multiply(BigDecimal(adjustedKelly))
            .setScale(0, RoundingMode.DOWN)

        // 최소/최대 제한 (수수료 0.04% 고려하여 5100원)
        return positionSize
            .coerceAtLeast(BigDecimal(5100))  // 최소 5100원 (수수료 여유)
            .coerceAtMost(baseAmount.multiply(BigDecimal(2)))  // 최대 기본 금액의 2배
            .coerceAtMost(currentBalance)     // 잔고 초과 방지
    }

    /**
     * 거래 결과 기록
     */
    fun recordTrade(market: String, profit: BigDecimal) {
        val stats = statsCache.getOrPut(market) { TradeStats() }

        stats.totalTrades++

        if (profit >= BigDecimal.ZERO) {
            stats.winCount++
            stats.totalProfit += profit
            stats.consecutiveLosses = 0
        } else {
            stats.lossCount++
            stats.totalLoss += profit.abs()
            stats.consecutiveLosses++
            stats.maxConsecutiveLosses = max(stats.maxConsecutiveLosses, stats.consecutiveLosses)
        }

        stats.lastUpdated = Instant.now()
    }

    /**
     * 연속 손실 카운터 리셋
     */
    fun resetConsecutiveLosses(market: String) {
        statsCache[market]?.consecutiveLosses = 0
    }

    /**
     * 리스크 통계 조회
     */
    fun getRiskStats(market: String, currentBalance: BigDecimal): RiskStats {
        val stats = getStats(market)
        val peak = peakCapital[market] ?: currentBalance

        val currentDrawdown = if (peak > BigDecimal.ZERO) {
            ((peak - currentBalance) / peak * BigDecimal(100)).toDouble()
        } else 0.0

        val winRate = if (stats.totalTrades > 0) {
            stats.winCount.toDouble() / stats.totalTrades * 100
        } else 0.0

        val avgWin = if (stats.winCount > 0) {
            stats.totalProfit / BigDecimal(stats.winCount)
        } else BigDecimal.ZERO

        // [버그 수정] totalLoss는 음수이므로 절댈값 처리 필요
        val avgLoss = if (stats.lossCount > 0) {
            stats.totalLoss.abs() / BigDecimal(stats.lossCount)
        } else BigDecimal.ZERO

        val profitFactor = if (avgLoss > BigDecimal.ZERO) {
            (avgWin / avgLoss).toDouble()
        } else if (stats.totalProfit > BigDecimal.ZERO) {
            Double.MAX_VALUE
        } else {
            0.0
        }

        // Kelly Fraction 계산
        val kellyFraction = calculateKellyFraction(stats)

        // 제안 포지션 크기
        val suggestedSize = calculatePositionSize(market, currentBalance, 75.0)

        return RiskStats(
            market = market,
            totalTrades = stats.totalTrades,
            winRate = winRate,
            avgWin = avgWin,
            avgLoss = avgLoss,
            profitFactor = profitFactor,
            maxDrawdown = tradingProperties.maxDrawdownPercent,
            currentDrawdown = currentDrawdown,
            consecutiveLosses = stats.consecutiveLosses,
            kellyFraction = kellyFraction,
            suggestedPositionSize = suggestedSize
        )
    }

    private fun getStats(market: String): TradeStats {
        return statsCache.getOrPut(market) {
            // DB에서 통계 로드
            loadStatsFromDb(market)
        }
    }

    private fun loadStatsFromDb(market: String): TradeStats {
        val stats = TradeStats()

        try {
            val records = tradeRepository.findByMarketOrderByCreatedAtDesc(market)

            records.forEach { record ->
                stats.totalTrades++
                val profit = BigDecimal(record.pnl ?: 0.0)

                if (profit >= BigDecimal.ZERO) {
                    stats.winCount++
                    stats.totalProfit += profit
                } else {
                    stats.lossCount++
                    stats.totalLoss += profit.abs()
                }
            }
        } catch (e: Exception) {
            // DB 연결 실패 시 빈 통계 반환
        }

        return stats
    }

    private fun calculateDailyPnl(market: String): BigDecimal {
        val today = LocalDate.now(SEOUL_ZONE).atStartOfDay(SEOUL_ZONE).toInstant()

        return try {
            val todayRecords = tradeRepository.findByMarketAndCreatedAtAfter(market, today)
            todayRecords.map { BigDecimal(it.pnl ?: 0.0) }.fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl }
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    private fun calculateKellyFraction(stats: TradeStats): Double {
        if (stats.totalTrades < 10) return 0.0

        val winRate = stats.winCount.toDouble() / stats.totalTrades

        val avgWin = if (stats.winCount > 0) {
            stats.totalProfit.toDouble() / stats.winCount
        } else 0.0

        val avgLoss = if (stats.lossCount > 0) {
            stats.totalLoss.toDouble() / stats.lossCount
        } else 1.0

        val profitRatio = if (avgLoss > 0) avgWin / avgLoss else 1.0

        val kelly = winRate - ((1 - winRate) / profitRatio)
        return (kelly / 2).coerceIn(0.0, 0.25)  // Half Kelly
    }
}

data class RiskCheckResult(
    val canTrade: Boolean,
    val reason: String,
    val currentDrawdown: Double = 0.0,
    val dailyPnlPercent: Double = 0.0,
    val consecutiveLosses: Int = 0
)
