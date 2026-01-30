package com.ant.cointrading.risk

import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 포트폴리오 리스크 관리자 (Jim Simons/Renaissance 스타일)
 *
 * Renaissance Medallion Fund의 핵심:
 * - 상관관계 고려한 포지션 사이징
 * - 포트폴리오 수준 리스크 최적화
 * - 최대 낙폭(Max Drawdown) 제어
 */
@Component
class PortfolioRiskManager(
    private val memeRepository: MemeScalperTradeRepository,
    private val volumeRepository: VolumeSurgeTradeRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 포트폴리오 최적화
     *
     * @param capital 총 자본
     * @param memeKelly Meme Scalper Kelly f*
     * @param volumeKelly Volume Surge Kelly f*
     * @return 최적화된 포트폴리오 배분
     */
    fun optimizePortfolio(
        capital: Double,
        memeKelly: Double,
        volumeKelly: Double
    ): PortfolioAllocation {
        val correlation = calculateCorrelation()

        // 상관관계 보정: 높은 상관관계 시 Kelly 감축
        val adjustedMemeKelly = if (correlation > 0.5) {
            memeKelly * (1.0 - correlation) * 0.5
        } else memeKelly

        val adjustedVolumeKelly = if (correlation > 0.5) {
            volumeKelly * (1.0 - correlation) * 0.5
        } else volumeKelly

        val memePosition = capital * adjustedMemeKelly.coerceAtLeast(0.0)
        val volumePosition = capital * adjustedVolumeKelly.coerceAtLeast(0.0)

        val totalExposed = memePosition + volumePosition
        val cashPosition = capital - totalExposed

        return PortfolioAllocation(
            memePosition = memePosition,
            volumePosition = volumePosition,
            cashPosition = cashPosition.coerceAtLeast(0.0),
            correlation = correlation,
            totalExposure = totalExposed / capital
        )
    }

    /**
     * 전략간 수익률 상관관계 계산
     */
    fun calculateCorrelation(): Double {
        val memeTrades = memeRepository.findByStatus("CLOSED")
        val volumeTrades = volumeRepository.findByStatus("CLOSED")

        if (memeTrades.isEmpty() || volumeTrades.isEmpty()) {
            return 0.0
        }

        // 일별 수익률 계산
        val memeDailyReturns = calculateDailyReturns(memeTrades)
        val volumeDailyReturns = calculateDailyReturns(volumeTrades)

        if (memeDailyReturns.size < 10 || volumeDailyReturns.size < 10) {
            return 0.0
        }

        // 날짜 매칭
        val commonDates: Set<LocalDate> = memeDailyReturns.keys.intersect(volumeDailyReturns.keys)
        if (commonDates.size < 10) {
            return 0.0
        }

        val n = commonDates.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0

        for (date in commonDates) {
            val x = memeDailyReturns[date] ?: 0.0
            val y = volumeDailyReturns[date] ?: 0.0
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
            sumY2 += y * y
        }

        val numerator = n * sumXY - sumX * sumY
        val denominator = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))

        return if (denominator > 0) numerator / denominator else 0.0
    }

    /**
     * 일별 수익률 계산
     */
    private fun calculateDailyReturns(trades: List<Any>): Map<LocalDate, Double> {
        val dailyPnl = mutableMapOf<LocalDate, Double>()

        for (trade in trades) {
            val pnl = getPnlAmount(trade)
            val date = getExitTime(trade)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
                ?: continue

            dailyPnl[date] = dailyPnl.getOrDefault(date, 0.0) + pnl
        }

        // 정규화 (1만원 기준)
        val capital = 10000.0
        return dailyPnl.mapValues { it.value / capital }
    }

    /**
     * 공분산 행렬 계산
     */
    fun calculateCovarianceMatrix(): CovarianceMatrix {
        val correlation = calculateCorrelation()
        val memeStd = calculateStandardDeviation(memeRepository.findByStatus("CLOSED"))
        val volumeStd = calculateStandardDeviation(volumeRepository.findByStatus("CLOSED"))

        val covariance = correlation * memeStd * volumeStd

        return CovarianceMatrix(
            memeVariance = memeStd * memeStd,
            volumeVariance = volumeStd * volumeStd,
            covariance = covariance
        )
    }

    /**
     * 표준편차 계산
     */
    private fun calculateStandardDeviation(trades: List<Any>): Double {
        if (trades.isEmpty()) return 0.0

        val pnls = trades.map { getPnlAmount(it) }
        val mean = pnls.average()
        val variance = pnls.map { (it - mean) * (it - mean) }.average()

        return sqrt(variance)
    }

    /**
     * 최대 낙폭(Max Drawdown) 계산
     *
     * Jim Simons: "최대 낙폭은 포트폴리오 리스크의 핵심 지표다"
     *
     * MDD = (Peak - Trough) / Peak
     * - Peak: 역대 최고 자산
     * - Trough: Peak 이후 최저 자산
     */
    fun calculateMaxDrawdown(): DrawdownMetrics {
        val allTrades = (memeRepository.findByStatus("CLOSED") + volumeRepository.findByStatus("CLOSED"))
            .sortedBy { getCreatedAt(it) }

        if (allTrades.isEmpty()) {
            return DrawdownMetrics(0.0, 0.0, 0)
        }

        var currentEquity = 0.0
        var peakEquity = 0.0
        var maxDrawdown = 0.0

        // 일별 집계 (drawdownDays 계산용)
        val dailyEquity = mutableMapOf<LocalDate, Double>()

        for (trade in allTrades) {
            currentEquity += getPnlAmount(trade)
            peakEquity = maxOf(peakEquity, currentEquity)

            val drawdown = if (peakEquity > 0) {
                (peakEquity - currentEquity) / peakEquity
            } else {
                .0
            }

            maxDrawdown = maxOf(maxDrawdown, drawdown)

            // 날짜 기준 집계
            val date = getExitTime(trade)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
            if (date != null) {
                dailyEquity[date] = currentEquity
            }
        }

        // 최대 drawdown 기간 계산 (날짜 기준)
        var drawdownDays = 0
        var currentDrawdownDays = 0
        var inDrawdown = false

        val sortedDates = dailyEquity.keys.sorted()
        var datePeak = 0.0

        for (date in sortedDates) {
            val equity = dailyEquity[date] ?: 0.0
            datePeak = maxOf(datePeak, equity)

            val dd = if (datePeak > 0) (datePeak - equity) / datePeak else 0.0

            if (dd > 0.001) {  // 0.1% 이상 하락 시 drawdown으로 간주
                if (!inDrawdown) {
                    inDrawdown = true
                    currentDrawdownDays = 0
                }
                currentDrawdownDays++
            } else {
                if (inDrawdown) {
                    drawdownDays = maxOf(drawdownDays, currentDrawdownDays)
                    inDrawdown = false
                }
            }
        }

        val currentDrawdown = if (peakEquity > 0) {
            (peakEquity - currentEquity) / peakEquity
        } else {
            .0
        }.coerceAtLeast(0.0)

        return DrawdownMetrics(
            maxDrawdown = maxDrawdown,
            currentDrawdown = currentDrawdown,
            drawdownDays = drawdownDays
        )
    }

    private fun getPnlAmount(trade: Any): Double {
        return when (trade) {
            is com.ant.cointrading.repository.MemeScalperTradeEntity -> trade.pnlAmount ?: 0.0
            is com.ant.cointrading.repository.VolumeSurgeTradeEntity -> trade.pnlAmount ?: 0.0
            else -> 0.0
        }
    }

    private fun getExitTime(trade: Any): Instant? {
        return when (trade) {
            is com.ant.cointrading.repository.MemeScalperTradeEntity -> trade.exitTime
            is com.ant.cointrading.repository.VolumeSurgeTradeEntity -> trade.exitTime
            else -> null
        }
    }

    private fun getCreatedAt(trade: Any): Instant {
        return when (trade) {
            is com.ant.cointrading.repository.MemeScalperTradeEntity -> trade.createdAt
            is com.ant.cointrading.repository.VolumeSurgeTradeEntity -> trade.createdAt
            else -> Instant.now()
        }
    }
}

data class PortfolioAllocation(
    val memePosition: Double,
    val volumePosition: Double,
    val cashPosition: Double,
    val correlation: Double,
    val totalExposure: Double
)

data class CovarianceMatrix(
    val memeVariance: Double,
    val volumeVariance: Double,
    val covariance: Double
)

data class DrawdownMetrics(
    val maxDrawdown: Double,
    val currentDrawdown: Double,
    val drawdownDays: Int
)
