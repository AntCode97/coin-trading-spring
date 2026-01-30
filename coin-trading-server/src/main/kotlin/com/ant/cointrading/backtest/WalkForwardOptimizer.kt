package com.ant.cointrading.backtest

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Walk-forward 최적화 (Jim Simons 스타일)
 *
 * 과거 데이터를 사용한 파라미터 최적화:
 * 1. Look-ahead bias 방지
 * 2. Overfitting 방지 (교차 검증)
 * 3. 실전 환경 시뮬레이션
 *
 * Jim Simons의 원칙:
 * - "미래를 보고 과거를 최적화하지 마라"
 * - "최소 1년 데이터로 검증하라"
 * - "Walk-forward로만 신뢰할 수 있다"
 */
@Component
class WalkForwardOptimizer {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Walk-forward 윈도우 설정
        const val TRAIN_WINDOW_DAYS = 90   // 학습 윈도우: 90일
        const val TEST_WINDOW_DAYS = 30    // 테스트 윈도우: 30일
        const val STEP_DAYS = 30           // 슬라이드 간격: 30일

        // 최소 데이터 요구사항
        const val MIN_DATA_DAYS = TRAIN_WINDOW_DAYS + TEST_WINDOW_DAYS  // 최소 120일
    }

    /**
     * Walk-forward 최적화 실행
     *
     * @param allCandles 전체 캔들 데이터 (최소 120일)
     * @param paramGrid 파라미터 그리드
     * @return Walk-forward 결과
     */
    fun <T> optimize(
        allCandles: List<CandlePrice>,
        paramGrid: List<T>,
        backtestFn: (List<CandlePrice>, T) -> BacktestResult
    ): WalkForwardResult<T> where T : TradingParams {

        log.info("Walk-forward 최적화 시작: ${allCandles.size} 캔들, ${paramGrid.size} 파라미터 조합")

        if (allCandles.size < MIN_DATA_DAYS) {
            throw IllegalArgumentException("최소 $MIN_DATA_DAYS 일 데이터 필요 (현재: ${allCandles.size})")
        }

        val windows = createWindows(allCandles)
        log.info("Walk-forward 윈도우: ${windows.size}개 생성")

        val results = mutableListOf<WalkForwardWindowResult<T>>()

        for ((index, window) in windows.withIndex()) {
            log.info("윈도우 ${index + 1}/${windows.size}: 학습 ${window.trainCandles.size}, 테스트 ${window.testCandles.size}")

            // 1. Train 윈도우로 파라미터 최적화
            val bestParam = optimizeTrainWindow(window.trainCandles, paramGrid, backtestFn)

            // 2. Test 윈도우로 검증
            val testResult = backtestFn(window.testCandles, bestParam.param)

            results.add(WalkForwardWindowResult(
                windowIndex = index,
                bestParam = bestParam.param,
                trainResult = bestParam.result,
                testResult = testResult
            ))

            log.info("윈도우 ${index + 1} 완료: 최적 파라미터=${bestParam.param}, " +
                    "Test 손익=${String.format("%.0f", testResult.totalPnl)}")
        }

        // 결과 집계
        val avgTestPnl = results.map { it.testResult.totalPnl }.average()
        val avgTestSharpe = results.map { it.testResult.sharpeRatio }.average()
        val totalTestPnl = results.sumOf { it.testResult.totalPnl }

        val walkForwardResult = WalkForwardResult(
            totalWindows = windows.size,
            avgTestPnl = avgTestPnl,
            avgTestSharpe = avgTestSharpe,
            totalTestPnl = totalTestPnl,
            windowResults = results
        )

        log.info("""
            Walk-forward 최적화 완료:
            - 총 윈도우: ${windows.size}
            - 평균 Test 손익: ${String.format("%.0f", avgTestPnl)}원
            - 평균 Test Sharpe: ${String.format("%.2f", avgTestSharpe)}
            - 총 Test 손익: ${String.format("%.0f", totalTestPnl)}원
        """.trimIndent())

        return walkForwardResult
    }

    /**
     * Walk-forward 윈도우 생성
     */
    private fun createWindows(allCandles: List<CandlePrice>): List<WalkForwardWindow> {
        val windows = mutableListOf<WalkForwardWindow>()

        var startIndex = 0
        while (true) {
            val trainEnd = startIndex + TRAIN_WINDOW_DAYS
            val testEnd = trainEnd + TEST_WINDOW_DAYS

            if (testEnd > allCandles.size) break

            val trainCandles = allCandles.subList(startIndex, trainEnd)
            val testCandles = allCandles.subList(trainEnd, testEnd)

            windows.add(WalkForwardWindow(trainCandles, testCandles))

            startIndex += STEP_DAYS
        }

        return windows
    }

    /**
     * Train 윈도우로 파라미터 최적화
     */
    private fun <T> optimizeTrainWindow(
        trainCandles: List<CandlePrice>,
        paramGrid: List<T>,
        backtestFn: (List<CandlePrice>, T) -> BacktestResult
    ): ParamResult<T> where T : TradingParams {

        var bestResult: BacktestResult? = null
        var bestParam: T? = null

        for (param in paramGrid) {
            val result = backtestFn(trainCandles, param)

            if (bestResult == null || result.sharpeRatio > bestResult.sharpeRatio) {
                bestResult = result
                bestParam = param
            }
        }

        return ParamResult(bestParam!!, bestResult!!)
    }
}

/**
 * Walk-forward 윈도우
 */
data class WalkForwardWindow(
    val trainCandles: List<CandlePrice>,
    val testCandles: List<CandlePrice>
)

/**
 * Walk-forward 윈도우 결과
 */
data class WalkForwardWindowResult<T>(
    val windowIndex: Int,
    val bestParam: T,
    val trainResult: BacktestResult,
    val testResult: BacktestResult
) where T : TradingParams

/**
 * 파라미터 최적화 결과
 */
data class ParamResult<T>(
    val param: T,
    val result: BacktestResult
) where T : TradingParams

/**
 * Walk-forward 최적화 결과
 */
data class WalkForwardResult<T>(
    val totalWindows: Int,
    val avgTestPnl: Double,
    val avgTestSharpe: Double,
    val totalTestPnl: Double,
    val windowResults: List<WalkForwardWindowResult<T>>
) where T : TradingParams {
    /**
     * 실전 투입 가능 여부 (Simons 기준)
     */
    val isValidForLive: Boolean
        get() = avgTestSharpe > 1.0 &&
                windowResults.count { it.testResult.totalPnl > 0 } > totalWindows / 2

    /**
     * 추천 파라미터 (가장 자주 등장한 파라미터)
     */
    val recommendedParam: T?
        get() = windowResults
            .map { it.bestParam }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}

/**
 * 트레이딩 파라미터 인터페이스
 */
interface TradingParams {
    /**
     * 파라미터 설명 (로그용)
     */
    fun describe(): String
}

/**
 * 캔들 가격 데이터 (백테스팅용)
 */
data class CandlePrice(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
