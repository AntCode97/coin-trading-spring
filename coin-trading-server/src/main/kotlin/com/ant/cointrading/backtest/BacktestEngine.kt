package com.ant.cointrading.backtest

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.strategy.TradingStrategy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.abs

/**
 * 백테스팅 엔진 (Jim Simons 스타일 과학적 검증)
 *
 * 목적:
 * 1. 과거 데이터로 전략 검증
 * 2. Sharpe Ratio, Max Drawdown 계산
 * 3. 최소 100건 거래 시뮬레이션
 * 4. Walk-forward 최적화
 *
 * Jim Simons의 원칙:
 * - "과거 데이터로 검증되지 않은 전략은 도박이다"
 * - "최소 100건 이상의 독립 시행이 필요하다"
 * - "Sharpe Ratio > 1.0이어야 실전에 투입한다"
 */
@Component
class BacktestEngine(
    private val bithumbPublicApi: BithumbPublicApi
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Jim Simons: 최소 샘플 수 (통계적 유의성)
        const val MIN_TRADES = 100

        // Simons: 최소 Sharpe Ratio (실전 투입 기준)
        const val MIN_SHARPE_RATIO = 1.0

        // 리스크 한도
        const val MAX_DRAWDOWN_PERCENT = 20.0
    }

    /**
     * 백테스팅 실행
     *
     * @param strategy 전략
     * @param market 마켓 (예: KRW-BTC)
     * @param candles 과거 캔들 데이터 (최소 100개)
     * @param initialCapital 초기 자본
     * @return 백테스트 결과
     */
    fun runBacktest(
        strategy: TradingStrategy,
        market: String,
        candles: List<Candle>,
        initialCapital: Double = 1000000.0
    ): BacktestResult {
        log.info("백테스팅 시작: ${strategy.name}, $market, ${candles.size} 캔들")

        val trades = mutableListOf<BacktestTrade>()
        var capital = initialCapital
        var position: BacktestPosition? = null
        val equityCurve = mutableListOf(initialCapital)
        var peakEquity = initialCapital
        var maxDrawdown = 0.0

        // 더미 RegimeAnalysis (백테스팅용)
        val dummyRegime = RegimeAnalysis(
            regime = MarketRegime.SIDEWAYS,
            confidence = 50.0,
            adx = 20.0,
            atr = 0.0,
            atrPercent = 0.0,
            trendDirection = 0,
            timestamp = Instant.now()
        )

        // 각 캔들을 순회하며 매매 시뮬레이션
        for (i in 50 until candles.size) {  // 50개는 지표 계산용 버퍼
            val currentCandle = candles[i]
            val historicalCandles = candles.take(i + 1)
            val currentPrice = currentCandle.close

            // 포지션 체크 (손절/익절)
            position?.let { pos ->
                val pnlPercent = ((currentPrice.toDouble() - pos.entryPrice) / pos.entryPrice) * 100

                // 손절
                if (pnlPercent <= pos.stopLossPercent) {
                    val exitPrice = currentPrice.toDouble()
                    val pnl = (exitPrice - pos.entryPrice) * pos.quantity
                    capital += pnl

                    trades.add(BacktestTrade(
                        entryTime = pos.entryTime,
                        exitTime = currentCandle.timestamp,
                        entryPrice = pos.entryPrice,
                        exitPrice = exitPrice,
                        quantity = pos.quantity,
                        pnl = pnl,
                        pnlPercent = pnlPercent,
                        exitReason = "STOP_LOSS"
                    ))

                    position = null
                }
                // 익절
                else if (pnlPercent >= pos.takeProfitPercent) {
                    val exitPrice = currentPrice.toDouble()
                    val pnl = (exitPrice - pos.entryPrice) * pos.quantity
                    capital += pnl

                    trades.add(BacktestTrade(
                        entryTime = pos.entryTime,
                        exitTime = currentCandle.timestamp,
                        entryPrice = pos.entryPrice,
                        exitPrice = exitPrice,
                        quantity = pos.quantity,
                        pnl = pnl,
                        pnlPercent = pnlPercent,
                        exitReason = "TAKE_PROFIT"
                    ))

                    position = null
                }
            }

            // 신호 생성 (포지션 없을 때만)
            if (position == null) {
                val signal = strategy.analyze(
                    market = market,
                    candles = historicalCandles,
                    currentPrice = currentPrice,
                    regime = dummyRegime
                )

                // 매수 신호
                if (signal.action == com.ant.cointrading.model.SignalAction.BUY) {
                    val positionSize = initialCapital * 0.01  // 1% 포지션 (백테스팅용)
                    val quantity = positionSize / currentPrice.toDouble()

                    position = BacktestPosition(
                        entryTime = currentCandle.timestamp,
                        entryPrice = currentPrice.toDouble(),
                        quantity = quantity,
                        stopLossPercent = -1.5,  // 기본값
                        takeProfitPercent = 2.0  // 기본값
                    )

                    capital -= positionSize
                }
            }

            // Equity curve 기록
            val currentEquity = capital + (position?.let { it.quantity * currentPrice.toDouble() } ?: 0.0)
            equityCurve.add(currentEquity)

            // Max Drawdown 계산
            if (currentEquity > peakEquity) {
                peakEquity = currentEquity
            }
            val drawdown = ((peakEquity - currentEquity) / peakEquity) * 100
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }
        }

        // 미청산 포지션 강제 청산
        position?.let { pos ->
            val lastCandle = candles.last()
            val exitPrice = lastCandle.close.toDouble()
            val pnl = (exitPrice - pos.entryPrice) * pos.quantity

            trades.add(BacktestTrade(
                entryTime = pos.entryTime,
                exitTime = lastCandle.timestamp,
                entryPrice = pos.entryPrice,
                exitPrice = exitPrice,
                quantity = pos.quantity,
                pnl = pnl,
                pnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100,
                exitReason = "FORCE_CLOSE"
            ))
        }

        // 결과 계산
        val result = calculateResult(trades, initialCapital, equityCurve, maxDrawdown)

        log.info("""
            백테스팅 완료: ${strategy.name}
            총 거래: ${result.totalTrades}건
            승률: ${String.format("%.1f", result.winRate)}%
            총 손익: ${String.format("%.0f", result.totalPnl)}원
            Sharpe Ratio: ${String.format("%.2f", result.sharpeRatio)}
            Max Drawdown: ${String.format("%.1f", result.maxDrawdown)}%
            실전 투입: ${if (result.isValidForLive) "가능" else "불가"}
        """.trimIndent())

        return result
    }

    /**
     * 백테스트 결과 계산
     */
    private fun calculateResult(
        trades: List<BacktestTrade>,
        initialCapital: Double,
        equityCurve: List<Double>,
        maxDrawdown: Double
    ): BacktestResult {
        val totalTrades = trades.size
        val winningTrades = trades.count { it.pnl > 0 }
        val losingTrades = trades.count { it.pnl < 0 }
        val winRate = if (totalTrades > 0) winningTrades.toDouble() / totalTrades else 0.0

        val totalPnl = trades.sumOf { it.pnl }
        val avgPnl = if (totalTrades > 0) totalPnl / totalTrades else 0.0

        val avgWin = trades.filter { it.pnl > 0 }.map { it.pnl }.average()
        val avgLoss = abs(trades.filter { it.pnl < 0 }.map { it.pnl }.average())

        // Sharpe Ratio 계산 (연율화, 무위해 이자율 0% 가정)
        val returns = mutableListOf<Double>()
        for (i in 1 until equityCurve.size) {
            returns.add((equityCurve[i] - equityCurve[i - 1]) / equityCurve[i - 1])
        }
        val avgReturn = returns.average()
        val stdReturn = if (returns.size > 1) {
            kotlin.math.sqrt(returns.map { (it - avgReturn) * (it - avgReturn) }.average())
        } else {
            0.0
        }
        val sharpeRatio = if (stdReturn > 0) {
            avgReturn / stdReturn * kotlin.math.sqrt(252.0)  // 연율화
        } else {
            0.0
        }

        // 실전 투입 가능 여부 (Simons 기준)
        val isValidForLive = totalTrades >= MIN_TRADES &&
            sharpeRatio >= MIN_SHARPE_RATIO &&
            maxDrawdown <= MAX_DRAWDOWN_PERCENT &&
            winRate >= 0.5

        return BacktestResult(
            strategyName = "BACKTEST",
            totalTrades = totalTrades,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRate = winRate,
            totalPnl = totalPnl,
            avgPnl = avgPnl,
            avgWin = avgWin,
            avgLoss = avgLoss,
            sharpeRatio = sharpeRatio,
            maxDrawdown = maxDrawdown,
            finalEquity = equityCurve.last(),
            isValidForLive = isValidForLive,
            trades = trades
        )
    }
}

/**
 * 백테스트 포지션
 */
data class BacktestPosition(
    val entryTime: Instant,
    val entryPrice: Double,
    val quantity: Double,
    val stopLossPercent: Double,
    val takeProfitPercent: Double
)

/**
 * 백테스트 거래
 */
data class BacktestTrade(
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val exitReason: String
)

/**
 * 백테스트 결과
 */
data class BacktestResult(
    val strategyName: String,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val totalPnl: Double,
    val avgPnl: Double,
    val avgWin: Double,
    val avgLoss: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val finalEquity: Double,
    val isValidForLive: Boolean,
    val trades: List<BacktestTrade>
)
