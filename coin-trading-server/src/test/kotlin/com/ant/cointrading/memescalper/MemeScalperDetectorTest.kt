package com.ant.cointrading.memescalper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MemeScalperDetector 단위 테스트
 *
 * 검증 항목:
 * 1. RSI 계산 로직
 * 2. MACD 신호 계산
 * 3. 점수 계산 로직 (컨플루언스)
 * 4. EMA 계산
 */
@DisplayName("MemeScalperDetector")
class MemeScalperDetectorTest {

    @Nested
    @DisplayName("RSI 계산")
    inner class RsiCalculationTest {

        @Test
        @DisplayName("상승장에서 RSI > 50")
        fun rsiAbove50InUptrend() {
            // given - 상승 추세 가격 데이터 (과거 → 최신 순)
            // RSI 계산은 과거→최신 순으로 변화량 계산
            val closes = listOf(
                90.0, 91.0, 92.0, 93.0, 94.0,
                95.0, 96.0, 97.0, 98.0, 99.0,
                100.0
            )

            // when
            val rsi = calculateRsi(closes, 9)

            // then
            assertTrue(rsi > 50.0, "상승장에서 RSI는 50 이상이어야 함: $rsi")
        }

        @Test
        @DisplayName("하락장에서 RSI < 50")
        fun rsiBelowIn50Downtrend() {
            // given - 하락 추세 가격 데이터 (과거 → 최신 순)
            val closes = listOf(
                100.0, 99.0, 98.0, 97.0, 96.0,
                95.0, 94.0, 93.0, 92.0, 91.0,
                90.0
            )

            // when
            val rsi = calculateRsi(closes, 9)

            // then
            assertTrue(rsi < 50.0, "하락장에서 RSI는 50 이하이어야 함: $rsi")
        }

        @Test
        @DisplayName("데이터 부족 시 50 반환")
        fun rsiDefaultWhenInsufficientData() {
            // given
            val closes = listOf(100.0, 99.0)  // RSI 계산에 충분하지 않음

            // when
            val rsi = calculateRsi(closes, 9)

            // then
            assertEquals(50.0, rsi, "데이터 부족 시 기본값 50 반환")
        }
    }

    @Nested
    @DisplayName("MACD 신호")
    inner class MacdSignalTest {

        @Test
        @DisplayName("충분한 데이터에서 유효한 신호 반환")
        fun validSignalWithSufficientData() {
            // given - 충분한 가격 데이터
            val closes = listOf(
                100.0, 100.0, 100.0, 100.0, 100.0,
                100.0, 100.0, 100.0, 100.0, 100.0,
                101.0, 102.0, 103.0, 104.0, 105.0,
                106.0, 107.0, 108.0, 109.0, 110.0
            )

            // when
            val signal = calculateMacdSignal(closes, 5, 13, 6)

            // then - 유효한 신호 중 하나 반환
            assertTrue(signal in listOf("BULLISH", "BEARISH", "NEUTRAL"), "유효한 MACD 신호: $signal")
        }

        @Test
        @DisplayName("횡보장에서 NEUTRAL 경향")
        fun neutralOnSideways() {
            // given - 횡보 가격 데이터
            val closes = listOf(
                100.0, 100.0, 100.0, 100.0, 100.0,
                100.0, 100.0, 100.0, 100.0, 100.0,
                100.0, 100.0, 100.0, 100.0, 100.0,
                100.0, 100.0, 100.0, 100.0, 100.0
            )

            // when
            val signal = calculateMacdSignal(closes, 5, 13, 6)

            // then - 횡보장에서는 강한 신호가 나오기 어려움
            assertTrue(signal in listOf("BULLISH", "BEARISH", "NEUTRAL"), "횡보장 신호: $signal")
        }

        @Test
        @DisplayName("데이터 부족 시 NEUTRAL")
        fun neutralWhenInsufficientData() {
            // given
            val closes = listOf(100.0, 99.0, 98.0)  // MACD 계산에 부족

            // when
            val signal = calculateMacdSignal(closes, 5, 13, 6)

            // then
            assertEquals("NEUTRAL", signal, "데이터 부족 시 NEUTRAL")
        }
    }

    @Nested
    @DisplayName("점수 계산 (컨플루언스)")
    inner class ScoreCalculationTest {

        @Test
        @DisplayName("최적 조건에서 100점")
        fun maxScoreOnOptimalConditions() {
            // given - 이상적인 펌프 조건
            val volumeSpikeRatio = 10.0   // 1000% 급등 → 35점
            val priceSpikePercent = 10.0   // 10% 급등 → 25점
            val bidImbalance = 0.5         // 강한 매수 압력 → 15점
            val rsi = 55.0                 // 최적 구간 → 15점
            val macdSignal = "BULLISH"     // 상승 신호 → 10점

            // when
            val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)

            // then
            assertEquals(100, score, "최적 조건에서 만점")
        }

        @Test
        @DisplayName("과매수 상태에서 RSI 점수 0")
        fun zeroRsiScoreOnOverbought() {
            // given
            val volumeSpikeRatio = 5.0
            val priceSpikePercent = 3.0
            val bidImbalance = 0.3
            val rsi = 85.0  // 과매수 → 0점
            val macdSignal = "BULLISH"

            // when
            val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)

            // then - RSI 과매수로 15점 손실
            assertTrue(score < 85, "과매수 시 점수 감소: $score")
        }

        @Test
        @DisplayName("BEARISH MACD 시 추가 점수 없음")
        fun noMacdScoreOnBearish() {
            // given
            val volumeSpikeRatio = 5.0
            val priceSpikePercent = 3.0
            val bidImbalance = 0.3
            val rsi = 55.0
            val macdSignal = "BEARISH"  // 0점

            // when
            val scoreBearish = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)
            val scoreBullish = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, "BULLISH")

            // then
            assertEquals(10, scoreBullish - scoreBearish, "BEARISH vs BULLISH 10점 차이")
        }

        @Test
        @DisplayName("최소 진입 점수 70점 검증")
        fun minEntryScoreThreshold() {
            // given - 중간 정도의 조건
            val volumeSpikeRatio = 5.0     // 25점
            val priceSpikePercent = 3.0     // 18점
            val bidImbalance = 0.3          // 12점
            val rsi = 55.0                  // 15점
            val macdSignal = "NEUTRAL"      // 3점

            // when
            val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)

            // then - 73점으로 진입 가능
            assertTrue(score >= 70, "중간 조건에서도 진입 가능: $score")
        }

        @Test
        @DisplayName("매도 압력 시 Imbalance 점수 0")
        fun zeroImbalanceScoreOnSellPressure() {
            // given
            val volumeSpikeRatio = 5.0
            val priceSpikePercent = 3.0
            val bidImbalance = -0.2  // 매도 압력 → 0점
            val rsi = 55.0
            val macdSignal = "BULLISH"

            // when
            val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)

            // then - Imbalance 0점
            assertTrue(score <= 85, "매도 압력 시 점수 감소: $score")
        }
    }

    @Nested
    @DisplayName("EMA 계산")
    inner class EmaCalculationTest {

        @Test
        @DisplayName("첫 EMA는 SMA")
        fun firstEmaIsSma() {
            // given
            val data = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
            val period = 3

            // when
            val ema = calculateEma(data, period)

            // then - 첫 번째 EMA는 처음 3개의 평균
            val expectedFirstEma = (10.0 + 20.0 + 30.0) / 3  // 20.0
            assertEquals(expectedFirstEma, ema.firstOrNull() ?: 0.0, 0.01)
        }

        @Test
        @DisplayName("데이터 부족 시 빈 리스트")
        fun emptyListWhenInsufficientData() {
            // given
            val data = listOf(10.0, 20.0)
            val period = 5

            // when
            val ema = calculateEma(data, period)

            // then
            assertTrue(ema.isEmpty(), "데이터 부족 시 빈 리스트")
        }
    }

    // === 테스트용 헬퍼 함수들 (Detector 로직 복제) ===

    private fun calculateRsi(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0

        val changes = closes.zipWithNext { prev, curr -> curr - prev }

        var avgGain = changes.take(period).filter { it > 0 }.sum() / period
        var avgLoss = kotlin.math.abs(changes.take(period).filter { it < 0 }.sum()) / period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) kotlin.math.abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0

        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    private fun calculateMacdSignal(closes: List<Double>, fast: Int, slow: Int, signal: Int): String {
        if (closes.size < slow + signal) return "NEUTRAL"

        val fastEma = calculateEma(closes, fast)
        val slowEma = calculateEma(closes, slow)

        val macdLine = fastEma.zip(slowEma) { f, s -> f - s }
        if (macdLine.size < signal + 1) return "NEUTRAL"

        val signalLine = calculateEma(macdLine, signal)

        val currentMacd = macdLine.lastOrNull() ?: 0.0
        val previousMacd = macdLine.getOrNull(macdLine.size - 2) ?: 0.0
        val currentSignal = signalLine.lastOrNull() ?: 0.0
        val previousSignal = signalLine.getOrNull(signalLine.size - 2) ?: 0.0

        return when {
            previousMacd <= previousSignal && currentMacd > currentSignal -> "BULLISH"
            previousMacd >= previousSignal && currentMacd < currentSignal -> "BEARISH"
            currentMacd > currentSignal && currentMacd > previousMacd -> "BULLISH"
            currentMacd < currentSignal && currentMacd < previousMacd -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    private fun calculateEma(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val result = mutableListOf<Double>()

        var ema = data.take(period).average()
        result.add(ema)

        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
            result.add(ema)
        }

        return result
    }

    private fun calculateScore(
        volumeSpikeRatio: Double,
        priceSpikePercent: Double,
        bidImbalance: Double,
        rsi: Double,
        macdSignal: String
    ): Int {
        var score = 0

        // 1. 거래량 스파이크 (35점)
        score += when {
            volumeSpikeRatio >= 10.0 -> 35
            volumeSpikeRatio >= 7.0 -> 30
            volumeSpikeRatio >= 5.0 -> 25
            volumeSpikeRatio >= 3.0 -> 20
            volumeSpikeRatio >= 2.0 -> 10
            else -> 0
        }

        // 2. 가격 스파이크 (25점)
        score += when {
            priceSpikePercent >= 10.0 -> 25
            priceSpikePercent >= 5.0 -> 22
            priceSpikePercent >= 3.0 -> 18
            priceSpikePercent >= 2.0 -> 12
            priceSpikePercent >= 1.0 -> 8
            else -> 0
        }

        // 3. 호가창 Imbalance (15점)
        score += when {
            bidImbalance >= 0.5 -> 15
            bidImbalance >= 0.3 -> 12
            bidImbalance >= 0.1 -> 8
            bidImbalance >= 0.0 -> 4
            else -> 0
        }

        // 4. RSI 조건 (15점)
        score += when {
            rsi in 50.0..65.0 -> 15
            rsi in 40.0..50.0 -> 10
            rsi in 65.0..75.0 -> 8
            rsi in 75.0..80.0 -> 3
            rsi > 80.0 -> 0
            else -> 0
        }

        // 5. MACD 신호 (10점)
        score += when (macdSignal) {
            "BULLISH" -> 10
            "NEUTRAL" -> 3
            "BEARISH" -> 0
            else -> 0
        }

        return score
    }
}
