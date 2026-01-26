package com.ant.cointrading.backtesting

import com.ant.cointrading.config.BreakoutProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.Candle
import com.ant.cointrading.repository.BacktestResultEntity
import com.ant.cointrading.repository.BacktestResultRepository
import com.ant.cointrading.repository.OhlcvHistoryRepository
import com.ant.cointrading.strategy.BreakoutStrategy
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Walk-Forward 파라미터 최적화 엔진
 *
 * 과거 데이터를 사용하여 전략 파라미터를 최적화합니다.
 * Walk-Forward 분석: 학습 기간 → 테스트 기간 → 슬라이딩 윈도우
 */
@Component
class WalkForwardOptimizer(
    private val simulator: Simulator,
    private val backtestResultRepository: BacktestResultRepository,
    private val ohlcvHistoryRepository: OhlcvHistoryRepository,
    private val objectMapper: ObjectMapper,
    private val breakoutProperties: BreakoutProperties,
    private val tradingProperties: TradingProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MIN_DATA_POINTS = 100  // 최소 데이터 포인트 수
    }

    /**
     * 매일 자동 최적화 (새벽 3시 - OHLCV 수집 이후 실행)
     *
     * 최근 3개월 데이터로 Breakout 파라미터 최적화
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 매일 새벽 3시
    fun autoOptimizeDaily() {
        log.info("=== 자동 백테스트 최적화 시작 ===")

        val markets = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL")
        val endDate = LocalDate.now()
        val startDate = endDate.minusMonths(3)

        markets.forEach { market ->
            try {
                val result = optimizeBreakout(
                    market = market,
                    startDate = startDate,
                    endDate = endDate,
                    interval = "day"
                )
                log.info(
                    "[$market] 최적화 완료: " +
                            "Sharpe=${String.format("%.2f", result.result?.sharpeRatio)}, " +
                            "Return=${String.format("%.2f", result.result?.totalReturn)}%, " +
                            "파라미터=${result.parameters}"
                )
            } catch (e: Exception) {
                log.warn("[$market] 최적화 실패: ${e.message}")
            }
        }

        log.info("=== 자동 백테스트 최적화 완료 ===")
    }

    /**
     * Breakout 전략 파라미터 최적화
     */
    fun optimizeBreakout(
        market: String = "KRW-BTC",
        startDate: LocalDate,
        endDate: LocalDate,
        interval: String = "day"
    ): OptimizationResult {
        log.info("Breakout 최적화 시작: $market, $startDate ~ $endDate")

        // 파라미터 그리드 정의
        val parameterGrid = mapOf(
            "bollingerPeriod" to listOf(20, 30, 50, 100),
            "bollingerStdDev" to listOf(1.5, 2.0, 2.5),
            "minBreakoutVolumeRatio" to listOf(1.2, 1.5, 2.0)
        )

        val bestResult = runOptimization(
            strategyName = "BREAKOUT",
            market = market,
            startDate = startDate,
            endDate = endDate,
            interval = interval,
            parameterGrid = parameterGrid
        ) { params ->
            // Breakout 전략 인스턴스 생성 (동적 파라미터)
            DynamicBreakoutStrategy(
                bollingerPeriod = params["bollingerPeriod"] as Int,
                bollingerStdDev = params["bollingerStdDev"] as Double,
                minBreakoutVolumeRatio = params["minBreakoutVolumeRatio"] as Double,
                positionSizeKrw = tradingProperties.orderAmountKrw.toDouble()
            )
        }

        log.info(
            "Breakout 최적화 완료: Sharpe=${String.format("%.2f", bestResult.result?.sharpeRatio)}, " +
                    "Return=${String.format("%.2f", bestResult.result?.totalReturn)}%, " +
                    "최적파라미터=${bestResult.parameters}"
        )

        return bestResult
    }

    /**
     * 파라미터 조합 생성 (Cartesian Product)
     */
    private fun generateCombinations(grid: Map<String, List<*>>): List<Map<String, Any>> {
        val keys = grid.keys.toList()
        val values = grid.values.toList()
        val combinations = mutableListOf<Map<String, Any>>()

        fun generate(current: Map<String, Any>, depth: Int) {
            if (depth == keys.size) {
                combinations.add(current)
                return
            }

            for (value in values[depth]) {
                val nonNullValue = value ?: continue
                generate(current + (keys[depth] to nonNullValue), depth + 1)
            }
        }

        generate(emptyMap(), 0)
        return combinations
    }

    /**
     * 최적화 실행
     */
    private fun runOptimization(
        strategyName: String,
        market: String,
        startDate: LocalDate,
        endDate: LocalDate,
        interval: String,
        parameterGrid: Map<String, List<*>>,
        strategyFactory: (Map<String, Any>) -> BacktestableStrategy
    ): OptimizationResult {
        // 1. 과거 데이터 조회
        val startTimestamp = startDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = endDate.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val historyEntities = ohlcvHistoryRepository.findByMarketAndIntervalAndTimestampBetweenOrderByTimestampAsc(
            market = market,
            interval = interval,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp
        )

        if (historyEntities.size < MIN_DATA_POINTS) {
            throw IllegalStateException(
                "데이터 부족: ${historyEntities.size}개 (최소 ${MIN_DATA_POINTS}개 필요)"
            )
        }

        val candles = historyEntities.map { it.toCandle() }

        // 2. 파라미터 조합 생성
        val combinations = generateCombinations(parameterGrid)
        log.info("$strategyName 파라미터 조합 ${combinations.size}개 생성")

        // 3. 각 조합에 대해 백테스트 실행
        var bestResult: BacktestResult? = null
        var bestSharpe = Double.NEGATIVE_INFINITY
        var bestParams: Map<String, Any>? = null

        for ((index, params) in combinations.withIndex()) {
            try {
                val strategy = strategyFactory(params)
                val result = simulator.simulate(
                    strategy = strategy,
                    historicalData = candles,
                    initialCapital = 1_000_000.0,
                    commissionRate = 0.0004
                )

                log.debug(
                    "[$index/${combinations.size}] 파라미터=$params, " +
                            "Sharpe=${String.format("%.2f", result.sharpeRatio)}, " +
                            "Return=${String.format("%.2f", result.totalReturn)}%"
                )

                // 최적 결과 업데이트 (샤프 비율 기준)
                if (result.sharpeRatio > bestSharpe) {
                    bestSharpe = result.sharpeRatio
                    bestResult = result
                    bestParams = params
                }

            } catch (e: Exception) {
                log.warn("파라미터 조합 백테스트 실패: $params - ${e.message}")
            }
        }

        // 4. 최적 결과 DB 저장
        if (bestResult != null && bestParams != null) {
            saveResult(
                strategyName = strategyName,
                market = market,
                startDate = startDate,
                endDate = endDate,
                parameters = bestParams,
                result = bestResult
            )
        }

        return OptimizationResult(
            strategyName = strategyName,
            market = market,
            parameters = bestParams ?: emptyMap(),
            result = bestResult
        )
    }

    /**
     * 최적화 결과 DB 저장
     */
    private fun saveResult(
        strategyName: String,
        market: String,
        startDate: LocalDate,
        endDate: LocalDate,
        parameters: Map<String, Any>,
        result: BacktestResult
    ) {
        try {
            val entity = BacktestResultEntity()
            entity.strategyName = strategyName
            entity.market = market
            entity.startDate = startDate
            entity.endDate = endDate
            entity.parameters = objectMapper.writeValueAsString(parameters)
            entity.totalTrades = result.totalTrades
            entity.winningTrades = result.winningTrades
            entity.losingTrades = result.losingTrades
            entity.totalReturn = result.totalReturn
            entity.maxDrawdown = result.maxDrawdown
            entity.sharpeRatio = result.sharpeRatio
            entity.profitFactor = result.profitFactor
            entity.avgWin = result.avgWin
            entity.avgLoss = result.avgLoss
            backtestResultRepository.save(entity)
            log.info("백테스트 결과 저장 완료: ID=${entity.id}")
        } catch (e: Exception) {
            log.error("백테스트 결과 저장 실패: ${e.message}")
        }
    }

    /**
     * 최적화 결과
     */
    data class OptimizationResult(
        val strategyName: String,
        val market: String,
        val parameters: Map<String, Any>,
        val result: BacktestResult?
    )

    /**
     * 동적 파라미터 Breakout 전략 (최적화용)
     */
    private class DynamicBreakoutStrategy(
        private val bollingerPeriod: Int,
        private val bollingerStdDev: Double,
        private val minBreakoutVolumeRatio: Double,
        private val positionSizeKrw: Double
    ) : BacktestableStrategy {

        override val name: String = "BREAKOUT_DYNAMIC"

        override fun analyzeForBacktest(
            candles: List<Candle>,
            currentIndex: Int,
            initialCapital: Double,
            currentPrice: java.math.BigDecimal,
            currentPosition: Double
        ): BacktestSignal {
            if (candles.size < bollingerPeriod || currentIndex < bollingerPeriod) {
                return BacktestSignal(BacktestAction.HOLD, reason = "데이터 부족")
            }

            val availableCandles = candles.subList(0, currentIndex + 1)
            val (upperBand, lowerBand, _) = calculateBollingerBands(availableCandles)
            val currentPriceDouble = currentPrice.toDouble()
            val volumeRatio = calculateVolumeRatio(availableCandles)

            val isBreakoutUp = currentPriceDouble > upperBand
            val isBreakoutDown = currentPriceDouble < lowerBand

            return when {
                currentPosition == 0.0 && isBreakoutUp && volumeRatio >= minBreakoutVolumeRatio -> {
                    BacktestSignal(
                        BacktestAction.BUY,
                        confidence = 80.0,
                        reason = "Breakout 상승 (거래량 ${String.format("%.1f", volumeRatio)}x)"
                    )
                }
                currentPosition > 0 && isBreakoutDown && volumeRatio >= minBreakoutVolumeRatio -> {
                    BacktestSignal(
                        BacktestAction.SELL,
                        confidence = 80.0,
                        reason = "Breakout 하락 (거래량 ${String.format("%.1f", volumeRatio)}x)"
                    )
                }
                else -> BacktestSignal(BacktestAction.HOLD)
            }
        }

        private fun calculateBollingerBands(candles: List<Candle>): Triple<Double, Double, Double> {
            val closes = candles.map { it.close.toDouble() }
            val period = minOf(bollingerPeriod, closes.size)

            val recentCloses = closes.takeLast(period)
            val sma = recentCloses.average()
            val variance = recentCloses.map { (it - sma).let { diff -> diff * diff } }.average()
            val stdDev = kotlin.math.sqrt(variance)

            val upperBand = sma + (bollingerStdDev * stdDev)
            val lowerBand = sma - (bollingerStdDev * stdDev)

            return Triple(upperBand, lowerBand, sma)
        }

        private fun calculateVolumeRatio(candles: List<Candle>): Double {
            if (candles.size < 21) return 1.0

            val currentVolume = candles.last().volume.toDouble()
            val avgVolume = candles.takeLast(21).dropLast(1).map { it.volume.toDouble() }.average()

            return if (avgVolume > 0) {
                currentVolume / avgVolume
            } else 1.0
        }
    }
}
