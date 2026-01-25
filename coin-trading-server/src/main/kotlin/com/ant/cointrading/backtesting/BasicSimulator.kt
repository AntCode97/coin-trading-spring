package com.ant.cointrading.backtesting

import com.ant.cointrading.model.Candle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 기본 백테스팅 시뮬레이터 구현
 *
 * 주요 기능:
 * - 과거 데이터 순차 시뮬레이션
 * - 수수료/슬리피지 고려
 * - 포지션 관리 (진입/청산)
 * - 성과 지표 계산 (수익률, MDD, Sharpe Ratio)
 */
@Component
class BasicSimulator : Simulator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun simulate(
        strategy: BacktestableStrategy,
        historicalData: List<Candle>,
        initialCapital: Double,
        commissionRate: Double
    ): BacktestResult {
        log.info("백테스트 시작: ${strategy.name}, 데이터: ${historicalData.size}개 캔들")

        val trades = mutableListOf<BacktestTrade>()
        val equityCurve = mutableListOf(initialCapital)
        val capitalHistory = mutableListOf(initialCapital)

        var capital = initialCapital
        var position = 0.0  // 보유 코인 수량
        var entryPrice = 0.0
        var entryIndex = -1
        var entryReason = ""

        // 캔들 순차 순회하며 시뮬레이션
        historicalData.forEachIndexed { index, candle ->
            val currentPrice = candle.close.toDouble()

            // 1. 현재 포지션 확인
            val hasPosition = position > 0

            // 2. 전략 신호 생성
            val signal = strategy.analyzeForBacktest(
                candles = historicalData,
                currentIndex = index,
                initialCapital = initialCapital,
                currentPrice = BigDecimal.valueOf(currentPrice),
                currentPosition = position
            )

            // 3. 신호 처리
            when (signal.action) {
                BacktestAction.BUY -> {
                    // 포지션이 없을 때만 진입
                    if (!hasPosition) {
                        val maxQuantity = (capital * (1 - commissionRate)) / currentPrice
                        if (maxQuantity > 0) {
                            val quantity = maxQuantity
                            val fee = quantity * currentPrice * commissionRate

                            capital -= (quantity * currentPrice + fee)
                            position = quantity
                            entryPrice = currentPrice
                            entryIndex = index
                            entryReason = signal.reason

                            log.debug("[$index] 진입: 가격=$currentPrice, 수량=$quantity, 사유=${signal.reason}")
                        }
                    }
                }
                BacktestAction.SELL -> {
                    // 포지션이 있을 때만 청산
                    if (hasPosition) {
                        val revenue = position * currentPrice
                        val fee = revenue * commissionRate
                        val netRevenue = revenue - fee
                        val entryCost = position * entryPrice
                        val pnl = netRevenue - entryCost
                        val pnlPercent = (pnl / entryCost) * 100

                        capital += netRevenue

                        // 거래 기록
                        trades.add(
                            BacktestTrade(
                                entryIndex = entryIndex,
                                exitIndex = index,
                                entryPrice = entryPrice,
                                exitPrice = currentPrice,
                                quantity = position,
                                pnl = pnl,
                                pnlPercent = pnlPercent,
                                entryReason = entryReason,
                                exitReason = signal.reason.ifEmpty { "Signal Sell" },
                                holdingPeriod = index - entryIndex
                            )
                        )

                        log.debug(
                            "[$index] 청산: 가격=$currentPrice, 손익=$pnl (${String.format("%.2f", pnlPercent)}%), " +
                                    "보유=${index - entryIndex}캔들"
                        )

                        // 포지션 초기화
                        position = 0.0
                        entryPrice = 0.0
                        entryIndex = -1
                        entryReason = ""
                    }
                }
                BacktestAction.HOLD -> {
                    // 아무것도 하지 않음
                }
            }

            // 4. 자산 기록 (평가 금액 = 현금 + 포지션 가치)
            val currentEquity = capital + (position * currentPrice)
            equityCurve.add(currentEquity)
            capitalHistory.add(capital)
        }

        // 최종 정산 (남은 포지션이 있으면 마지막 가격으로 청산)
        if (position > 0) {
            val finalPrice = historicalData.last().close.toDouble()
            val revenue = position * finalPrice
            val fee = revenue * commissionRate
            val netRevenue = revenue - fee
            val entryCost = position * entryPrice
            val pnl = netRevenue - entryCost
            val pnlPercent = (pnl / entryCost) * 100

            capital += netRevenue

            trades.add(
                BacktestTrade(
                    entryIndex = entryIndex,
                    exitIndex = historicalData.size - 1,
                    entryPrice = entryPrice,
                    exitPrice = finalPrice,
                    quantity = position,
                    pnl = pnl,
                    pnlPercent = pnlPercent,
                    entryReason = entryReason,
                    exitReason = "End of Period",
                    holdingPeriod = historicalData.size - 1 - entryIndex
                )
            )

            log.debug("최종 정산: 가격=$finalPrice, 손익=$pnl (${String.format("%.2f", pnlPercent)}%)")
        }

        val finalCapital = capital
        val totalReturn = ((finalCapital - initialCapital) / initialCapital) * 100

        // 성과 지표 계산
        val result = calculateMetrics(
            trades,
            equityCurve,
            initialCapital,
            finalCapital,
            totalReturn,
            strategy.name
        )

        log.info(
            "백테스트 완료: ${strategy.name}, 수익률=${String.format("%.2f", totalReturn)}%, " +
                    "거래=${trades.size}건, 승률=${String.format("%.1f", result.winRate)}%, " +
                    "MDD=${String.format("%.2f", result.maxDrawdown)}%, Sharpe=${String.format("%.2f", result.sharpeRatio)}"
        )

        return result
    }

    /**
     * 성과 지표 계산
     */
    private fun calculateMetrics(
        trades: List<BacktestTrade>,
        equityCurve: List<Double>,
        initialCapital: Double,
        finalCapital: Double,
        totalReturn: Double,
        strategyName: String
    ): BacktestResult {
        val winningTrades = trades.count { it.pnl > 0 }
        val losingTrades = trades.count { it.pnl < 0 }
        val winRate = if (trades.isNotEmpty()) {
            (winningTrades.toDouble() / trades.size) * 100
        } else 0.0

        // 최대 낙폭 계산
        var peak = equityCurve.first()
        var maxDrawdown = 0.0
        for (equity in equityCurve) {
            if (equity > peak) peak = equity
            val drawdown = ((peak - equity) / peak) * 100
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        // Sharpe Ratio 계산
        val returns = mutableListOf<Double>()
        for (i in 1 until equityCurve.size) {
            val dailyReturn = (equityCurve[i] - equityCurve[i - 1]) / equityCurve[i - 1]
            returns.add(dailyReturn)
        }

        val sharpeRatio = if (returns.isNotEmpty()) {
            val avgReturn = returns.average()
            val variance = returns.map { (it - avgReturn) * (it - avgReturn) }.average()
            val stdDev = sqrt(variance)
            if (stdDev > 0) {
                // 연환산 (일봉 기준 252일)
                (avgReturn / stdDev) * sqrt(252.0)
            } else 0.0
        } else 0.0

        // Profit Factor, Avg Win/Loss 계산
        val profitFactor: Double?
        val avgWin: Double?
        val avgLoss: Double?

        if (trades.isNotEmpty()) {
            val totalWin = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
            val totalLoss = abs(trades.filter { it.pnl < 0 }.sumOf { it.pnl })

            profitFactor = if (totalLoss > 0) totalWin / totalLoss else null
            avgWin = if (winningTrades > 0) {
                trades.filter { it.pnl > 0 }.map { it.pnlPercent }.average()
            } else null
            avgLoss = if (losingTrades > 0) {
                trades.filter { it.pnl < 0 }.map { it.pnlPercent }.average()
            } else null
        } else {
            profitFactor = null
            avgWin = null
            avgLoss = null
        }

        return BacktestResult(
            strategyName = strategyName,
            initialCapital = initialCapital,
            finalCapital = finalCapital,
            totalReturn = totalReturn,
            totalTrades = trades.size,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRate = winRate,
            maxDrawdown = maxDrawdown,
            sharpeRatio = sharpeRatio,
            profitFactor = profitFactor,
            avgWin = avgWin,
            avgLoss = avgLoss,
            trades = trades,
            equityCurve = equityCurve
        )
    }
}
