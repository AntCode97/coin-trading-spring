package com.ant.cointrading.backtesting

import com.ant.cointrading.config.BreakoutProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.indicator.calculateBollingerBands
import com.ant.cointrading.indicator.calculateVolumeRatio
import com.ant.cointrading.model.Candle
import com.ant.cointrading.repository.BacktestResultEntity
import com.ant.cointrading.repository.BacktestResultRepository
import com.ant.cointrading.repository.OhlcvHistoryRepository
import com.ant.cointrading.strategy.BreakoutStrategy
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * Walk-Forward 파라미터 최적화 엔진 (Jim Simons / Cliff Asness 스타일)
 *
 * Renaissance Technologies 표준:
 * 1. In-sample vs Out-of-sample 철저한 분리
 * 2. Rolling Window 방식으로 파라미터 갱신
 * 3. Out-of-sample 성능만으로 전략 평가
 * 4. 과적합(Overfitting) 방지가 핵심
 *
 * Walk-Forward 절차:
 * Window 1: Train[1~6월] → Test[7월] → Out-of-Sample Result 1
 * Window 2: Train[2~7월] → Test[8월] → Out-of-Sample Result 2
 * ...
 * Average(Out-of-Sample Results) = 진짜 성능 (Look-ahead bias 없음)
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
        const val MIN_DATA_POINTS = 100
        const val TRAIN_PERIOD_MONTHS = 6  // 학습 기간: 6개월
        const val TEST_PERIOD_DAYS = 21
        const val STEP_DAYS = 7             // 스텝: 7일
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
        slippageRate: Double = 0.0,
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
                    initialCapital = 100_000.0,  // 실전 1만원의 10배 (백테스트 합리적 크기)
                    commissionRate = tradingProperties.feeRate.toDouble(),
                    slippageRate = slippageRate
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
            val (upperBand, lowerBand, _) = calculateBollingerBands(availableCandles, bollingerPeriod, bollingerStdDev)
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
    }

    // ============================================================
    // Jim Simons Walk-Forward Analysis (진짜 Walk-Forward)
    // ============================================================

    /**
     * 진짜 Walk-Forward 분석 실행 (Cliff Asnes / Renaissance 스타일)
     *
     * In-sample(학습)과 Out-of-sample(테스트)을 철저히 분리하여
     * Look-ahead bias를 완전히 제거합니다.
     *
     * @param market 마켓 (예: "KRW-BTC")
     * @param strategyName 전략 이름
     * @param parameterGrid 파라미터 그리드
     * @return Walk-Forward 결과
     */
    fun runWalkForwardAnalysis(
        market: String = "KRW-BTC",
        strategyName: String = "BREAKOUT",
        parameterGrid: Map<String, List<*>>? = null,
        slippageRate: Double = 0.0
    ): WalkForwardAnalysisResult {
        val strategyMode = resolveStrategyMode(strategyName)
        log.info("=== Walk-Forward Analysis 시작 ($market, $strategyMode) ===")

        // 1. 전체 데이터 로드
        val endDate = LocalDate.now()
        val startDate = endDate.minusMonths(12)  // 12개월 데이터

        val startTimestamp = startDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTimestamp = endDate.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val historyEntities = ohlcvHistoryRepository.findByMarketAndIntervalAndTimestampBetweenOrderByTimestampAsc(
            market = market,
            interval = "day",
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp
        )

        if (historyEntities.size < MIN_DATA_POINTS) {
            throw IllegalStateException("데이터 부족: ${historyEntities.size}개 (최소 ${MIN_DATA_POINTS}개 필요)")
        }

        val candles = historyEntities.map { it.toCandle() }

        // 2. 파라미터 그리드 설정 (기본값)
        val grid = parameterGrid ?: defaultParameterGrid(strategyMode)

        // 3. Walk-Forward 윈도우 생성
        val windows = createWalkForwardWindows(candles)
        log.info("생성된 윈도우: ${windows.size}개")

        val windowResults = mutableListOf<WalkForwardWindowResult>()
        var inSampleSharpeSum = 0.0
        var outOfSampleSharpeSum = 0.0
        var outOfSampleTradesSum = 0
        var outOfSampleReturnSum = 0.0

        // 4. 각 윈도우 분석
        for ((index, window) in windows.withIndex()) {
            log.info("--- Window ${index + 1}/${windows.size} ---")
            log.info("학습: ${window.trainStartDate} ~ ${window.trainEndDate} (${window.trainCandles.size}개)")
            log.info("테스트: ${window.testStartDate} ~ ${window.testEndDate} (${window.testCandles.size}개)")

            try {
                // In-sample 최적화
                val inSampleResult = optimizeInSample(
                    trainCandles = window.trainCandles,
                    strategyMode = strategyMode,
                    parameterGrid = grid,
                    slippageRate = slippageRate
                )

                // Out-of-sample 테스트
                val outOfSampleResult = testOutOfSample(
                    testCandles = window.testCandles,
                    strategyMode = strategyMode,
                    bestParams = inSampleResult.bestParams,
                    slippageRate = slippageRate
                )

                inSampleSharpeSum += inSampleResult.sharpeRatio
                outOfSampleSharpeSum += outOfSampleResult.sharpeRatio
                outOfSampleTradesSum += outOfSampleResult.totalTrades
                outOfSampleReturnSum += outOfSampleResult.totalReturn

                windowResults.add(
                    WalkForwardWindowResult(
                        windowIndex = index,
                        trainPeriod = Pair(window.trainStartDate, window.trainEndDate),
                        testPeriod = Pair(window.testStartDate, window.testEndDate),
                        inSampleSharpe = inSampleResult.sharpeRatio,
                        outOfSampleSharpe = outOfSampleResult.sharpeRatio,
                        outOfSampleReturn = outOfSampleResult.totalReturn,
                        outOfSampleTrades = outOfSampleResult.totalTrades,
                        bestParams = inSampleResult.bestParams
                    )
                )

                log.info("In-Sample Sharpe: ${String.format("%.3f", inSampleResult.sharpeRatio)}")
                log.info("Out-of-Sample Sharpe: ${String.format("%.3f", outOfSampleResult.sharpeRatio)}")
                log.info("Out-of-Sample Return: ${String.format("%.2f", outOfSampleResult.totalReturn)}%")
                log.info("Out-of-Sample Trades: ${outOfSampleResult.totalTrades}건")
                log.info("최적 파라미터: ${inSampleResult.bestParams}")

            } catch (e: Exception) {
                log.warn("Window ${index + 1} 분석 실패: ${e.message}")
            }
        }

        // 5. 종합 결과
        val validWindows = windowResults.size
        val avgInSampleSharpe = if (validWindows > 0) inSampleSharpeSum / validWindows else 0.0
        val avgOutOfSampleSharpe = if (validWindows > 0) outOfSampleSharpeSum / validWindows else 0.0
        val avgOutOfSampleReturn = if (validWindows > 0) outOfSampleReturnSum / validWindows else 0.0
        val avgOutOfSampleTrades = if (validWindows > 0) outOfSampleTradesSum.toDouble() / validWindows else 0.0

        // Sharpe Decay (과적합 지표)
        val sharpeDecay = avgInSampleSharpe - avgOutOfSampleSharpe
        val decayPercent = if (avgInSampleSharpe > 0) {
            (sharpeDecay / avgInSampleSharpe) * 100
        } else {
            0.0
        }

        val result = WalkForwardAnalysisResult(
            market = market,
            strategyName = strategyMode,
            totalWindows = windows.size,
            validWindows = validWindows,
            avgInSampleSharpe = avgInSampleSharpe,
            avgOutOfSampleSharpe = avgOutOfSampleSharpe,
            avgOutOfSampleReturn = avgOutOfSampleReturn,
            avgOutOfSampleTrades = avgOutOfSampleTrades,
            sharpeDecay = sharpeDecay,
            decayPercent = decayPercent,
            isOverfitted = decayPercent > 20.0,  // Renaissance 기준: 20% 이상 decay = 과적합
            windowResults = windowResults
        )

        log.info("""
            === Walk-Forward 결과 ===
            유효 윈도우: ${validWindows}/${windows.size}
            In-Sample 평균 Sharpe: ${String.format("%.3f", avgInSampleSharpe)}
            Out-of-Sample 평균 Sharpe: ${String.format("%.3f", avgOutOfSampleSharpe)}
            Out-of-Sample 평균 Return: ${String.format("%.2f", avgOutOfSampleReturn)}%
            Out-of-Sample 평균 Trades: ${String.format("%.1f", avgOutOfSampleTrades)}건
            Sharpe Decay: ${String.format("%.3f", sharpeDecay)} (${String.format("%.1f", decayPercent)}%)
            과적합 여부: ${if (result.isOverfitted) "⚠️ YES (20%+ decay, Renaissance 기준)" else "✓ NO"}
            ========================
        """.trimIndent())

        return result
    }

    /**
     * Walk-Forward 윈도우 생성
     */
    private fun createWalkForwardWindows(candles: List<Candle>): List<SimpleWalkForwardWindow> {
        if (candles.isEmpty()) return emptyList()

        val windows = mutableListOf<SimpleWalkForwardWindow>()
        val dates = candles.map {
            it.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        }.sorted()
        val firstDate = dates.first()
        val lastDate = dates.last()

        var currentTestStart = firstDate.plusMonths(TRAIN_PERIOD_MONTHS.toLong())

        while (currentTestStart.plusDays(TEST_PERIOD_DAYS.toLong()) <= lastDate) {
            val trainStart = currentTestStart.minusMonths(TRAIN_PERIOD_MONTHS.toLong())
            val trainEnd = currentTestStart.minusDays(1)
            val testEnd = currentTestStart.plusDays((TEST_PERIOD_DAYS - 1).toLong())

            // 필터링
            val trainCandles = candles.filter {
                val date = it.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
                date >= trainStart && date <= trainEnd
            }
            val testCandles = candles.filter {
                val date = it.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
                date >= currentTestStart && date <= testEnd
            }

            // Jim Simons 기준: 테스트 기간에 충분한 데이터 필요
            // 7일 테스트 = 최소 7개 캔들 (하루에 1개 이상)
            if (trainCandles.size >= MIN_DATA_POINTS && testCandles.size >= TEST_PERIOD_DAYS) {
                windows.add(
                    SimpleWalkForwardWindow(
                        trainStartDate = trainStart,
                        trainEndDate = trainEnd,
                        testStartDate = currentTestStart,
                        testEndDate = testEnd,
                        trainCandles = trainCandles,
                        testCandles = testCandles
                    )
                )
            }

            currentTestStart = currentTestStart.plusDays(STEP_DAYS.toLong())
        }

        return windows
    }

    /**
     * In-sample 최적화
     */
    private fun optimizeInSample(
        trainCandles: List<Candle>,
        strategyMode: String,
        parameterGrid: Map<String, List<*>>,
        slippageRate: Double
    ): InSampleOptimizationResult {
        val combinations = generateCombinations(parameterGrid)

        var bestSharpe = Double.NEGATIVE_INFINITY
        var bestParams: Map<String, Any> = emptyMap()

        for (params in combinations) {
            try {
                val strategy = createStrategy(strategyMode, params)

                val result = simulator.simulate(
                    strategy = strategy,
                    historicalData = trainCandles,
                    initialCapital = 100_000.0,  // 실전 1만원의 10배 (백테스트 합리적 크기)
                    commissionRate = tradingProperties.feeRate.toDouble(),
                    slippageRate = slippageRate
                )

                if (result.sharpeRatio > bestSharpe) {
                    bestSharpe = result.sharpeRatio
                    bestParams = params
                }
            } catch (e: Exception) {
                // 파라미터 조합 실패 시 스킵
            }
        }

        return InSampleOptimizationResult(
            bestParams = bestParams,
            sharpeRatio = bestSharpe
        )
    }

    /**
     * Out-of-sample 테스트
     */
    private fun testOutOfSample(
        testCandles: List<Candle>,
        strategyMode: String,
        bestParams: Map<String, Any>,
        slippageRate: Double
    ): OutOfSampleTestResult {
        val strategy = createStrategy(strategyMode, bestParams)

        val result = simulator.simulate(
            strategy = strategy,
            historicalData = testCandles,
            initialCapital = 100_000.0,  // 실전 1만원의 10배 (백테스트 합리적 크기)
            commissionRate = tradingProperties.feeRate.toDouble(),
            slippageRate = slippageRate
        )

        return OutOfSampleTestResult(
            sharpeRatio = result.sharpeRatio,
            totalReturn = result.totalReturn,
            totalTrades = result.totalTrades
        )
    }

    /**
     * Walk-Forward 윈도우 (간이)
     */
    private data class SimpleWalkForwardWindow(
        val trainStartDate: LocalDate,
        val trainEndDate: LocalDate,
        val testStartDate: LocalDate,
        val testEndDate: LocalDate,
        val trainCandles: List<Candle>,
        val testCandles: List<Candle>
    )

    private fun resolveStrategyMode(strategyName: String): String {
        val normalized = strategyName.uppercase()
        return if (normalized.contains("MEAN_REVERSION")) "MEAN_REVERSION" else "BREAKOUT"
    }

    private fun defaultParameterGrid(strategyMode: String): Map<String, List<*>> {
        return if (strategyMode == "MEAN_REVERSION") {
            mapOf(
                "bollingerPeriod" to listOf(14, 20, 30),
                "bollingerStdDev" to listOf(1.6, 1.8, 2.0),
                "rsiPeriod" to listOf(10, 14),
                "rsiOversold" to listOf(20, 25, 30, 35),
                "rsiOverbought" to listOf(65, 70, 75, 80),
                "minVolumeRatio" to listOf(0.8, 1.0, 1.2)
            )
        } else {
            mapOf(
                "bollingerPeriod" to listOf(20, 30, 50),
                "bollingerStdDev" to listOf(1.5, 2.0, 2.5),
                "minBreakoutVolumeRatio" to listOf(0.8, 1.0, 1.2, 1.5, 2.0)
            )
        }
    }

    private fun createStrategy(strategyMode: String, params: Map<String, Any>): BacktestableStrategy {
        return if (strategyMode == "MEAN_REVERSION") {
            DynamicMeanReversionStrategy(
                bollingerPeriod = (params["bollingerPeriod"] as Number).toInt(),
                bollingerStdDev = (params["bollingerStdDev"] as Number).toDouble(),
                rsiPeriod = (params["rsiPeriod"] as Number).toInt(),
                rsiOversold = (params["rsiOversold"] as Number).toDouble(),
                rsiOverbought = (params["rsiOverbought"] as Number).toDouble(),
                minVolumeRatio = (params["minVolumeRatio"] as Number).toDouble(),
                positionSizeKrw = tradingProperties.orderAmountKrw.toDouble()
            )
        } else {
            DynamicBreakoutStrategy(
                bollingerPeriod = (params["bollingerPeriod"] as Number).toInt(),
                bollingerStdDev = (params["bollingerStdDev"] as Number).toDouble(),
                minBreakoutVolumeRatio = (params["minBreakoutVolumeRatio"] as Number).toDouble(),
                positionSizeKrw = tradingProperties.orderAmountKrw.toDouble()
            )
        }
    }

    private class DynamicMeanReversionStrategy(
        private val bollingerPeriod: Int,
        private val bollingerStdDev: Double,
        private val rsiPeriod: Int,
        private val rsiOversold: Double,
        private val rsiOverbought: Double,
        private val minVolumeRatio: Double,
        private val positionSizeKrw: Double
    ) : BacktestableStrategy {

        override val name: String = "MEAN_REVERSION_DYNAMIC"

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
            val (upperBand, lowerBand, _) = calculateBollingerBands(availableCandles, bollingerPeriod, bollingerStdDev)
            val currentPriceDouble = currentPrice.toDouble()
            val volumeRatio = calculateVolumeRatio(availableCandles)
            val rsi = calculateRsi(availableCandles, rsiPeriod)

            val nearLowerBand = currentPriceDouble <= lowerBand * 1.005
            val nearUpperBand = currentPriceDouble >= upperBand * 0.995

            return when {
                currentPosition == 0.0 && nearLowerBand && rsi <= rsiOversold && volumeRatio >= minVolumeRatio -> {
                    BacktestSignal(
                        BacktestAction.BUY,
                        confidence = 75.0,
                        reason = "MeanReversion 진입 (RSI ${String.format("%.1f", rsi)}, 거래량 ${String.format("%.1f", volumeRatio)}x)"
                    )
                }
                currentPosition > 0 && (nearUpperBand || rsi >= rsiOverbought) -> {
                    BacktestSignal(
                        BacktestAction.SELL,
                        confidence = 75.0,
                        reason = "MeanReversion 청산 (RSI ${String.format("%.1f", rsi)})"
                    )
                }
                else -> BacktestSignal(BacktestAction.HOLD)
            }
        }

        private fun calculateRsi(candles: List<Candle>, period: Int): Double {
            if (candles.size <= period) {
                return 50.0
            }

            val closes = candles.takeLast(period + 1).map { it.close.toDouble() }
            var gains = 0.0
            var losses = 0.0

            for (i in 1 until closes.size) {
                val change = closes[i] - closes[i - 1]
                if (change > 0) {
                    gains += change
                } else {
                    losses += -change
                }
            }

            val avgGain = gains / period
            val avgLoss = losses / period
            if (avgLoss == 0.0) {
                return 100.0
            }

            val rs = avgGain / avgLoss
            return 100.0 - (100.0 / (1.0 + rs))
        }
    }
}

/**
 * Walk-Forward 분석 결과
 */
data class WalkForwardAnalysisResult(
    val market: String,
    val strategyName: String,
    val totalWindows: Int,
    val validWindows: Int,
    val avgInSampleSharpe: Double,
    val avgOutOfSampleSharpe: Double,
    val avgOutOfSampleReturn: Double,
    val avgOutOfSampleTrades: Double,
    val sharpeDecay: Double,
    val decayPercent: Double,
    val isOverfitted: Boolean,
    val windowResults: List<WalkForwardWindowResult>
)

/**
 * Walk-Forward 윈도우 결과
 */
data class WalkForwardWindowResult(
    val windowIndex: Int,
    val trainPeriod: Pair<LocalDate, LocalDate>,
    val testPeriod: Pair<LocalDate, LocalDate>,
    val inSampleSharpe: Double,
    val outOfSampleSharpe: Double,
    val outOfSampleReturn: Double,
    val outOfSampleTrades: Int,
    val bestParams: Map<String, Any>
)

/**
 * In-sample 최적화 결과
 */
data class InSampleOptimizationResult(
    val bestParams: Map<String, Any>,
    val sharpeRatio: Double
)

/**
 * Out-of-sample 테스트 결과
 */
data class OutOfSampleTestResult(
    val sharpeRatio: Double,
    val totalReturn: Double,
    val totalTrades: Int
)
