package com.ant.cointrading.indicator

import com.ant.cointrading.model.Candle
import kotlin.math.abs
import kotlin.math.sqrt

// Reuse common EMA calculator
private fun calculateEma(data: List<Double>, period: Int) = EmaCalculator.calculate(data, period)

/**
 * 컨플루언스 분석기 (4지표 복합, 85% 승률 목표)
 *
 * quant-trading 스킬 기준:
 * 1. RSI ≤ 30 또는 상향 다이버전스     [25점]
 * 2. MACD 시그널 상향 크로스            [25점]
 * 3. %B ≤ 0.2 또는 하단밴드 반등        [25점]
 * 4. 거래량 ≥ 20일 평균 × 150%         [25점]
 *
 * 진입 기준:
 * - 100점: 강한 신호 (포지션 1.5배)
 * - 75점: 보통 신호 (기본 포지션)
 * - 50점: 약한 신호 (포지션 0.5배)
 * - 50점 미만: 진입 불가
 */
class ConfluenceAnalyzer(
    private val rsiPeriod: Int = 14,
    private val macdFast: Int = 12,
    private val macdSlow: Int = 26,
    private val macdSignal: Int = 9,
    private val bollingerPeriod: Int = 20,
    private val bollingerStdDev: Double = 2.0,
    private val volumePeriod: Int = 20
) {

    /**
     * 컨플루언스 점수 계산
     *
     * @param candles 캔들 데이터 (최소 100개 권장)
     * @return 컨플루언스 분석 결과
     */
    fun analyze(candles: List<Candle>): ConfluenceResult {
        if (candles.size < 50) {
            return ConfluenceResult(
                score = 0,
                signal = SignalType.INSUFFICIENT_DATA,
                rsiScore = 0,
                macdScore = 0,
                bollingerScore = 0,
                volumeScore = 0,
                details = "데이터 부족 (최소 50개 캔들 필요)"
            )
        }

        val rsiScore = calculateRsiScore(candles)
        val macdScore = calculateMacdScore(candles)
        val bollingerScore = calculateBollingerScore(candles)
        val volumeScore = calculateVolumeScore(candles)

        val totalScore = rsiScore + macdScore + bollingerScore + volumeScore
        val signal = determineSignal(totalScore)

        return ConfluenceResult(
            score = totalScore,
            signal = signal,
            rsiScore = rsiScore,
            macdScore = macdScore,
            bollingerScore = bollingerScore,
            volumeScore = volumeScore,
            details = buildDetails(rsiScore, macdScore, bollingerScore, volumeScore)
        )
    }

    /**
     * RSI 점수 [25점]
     *
     * 조건:
     * - 25점: RSI ≤ 25 (강한 과매도)
     * - 20점: RSI ≤ 30 (과매도)
     * - 15점: RSI 상향 다이버전스
     * - 10점: RSI ≤ 40 (약간 과매도)
     * - 0점: 그 외
     */
    private fun calculateRsiScore(candles: List<Candle>): Int {
        val rsiValues = calculateRsiValues(candles)
        if (rsiValues.isEmpty()) return 0

        val currentRsi = rsiValues.last()

        return when {
            currentRsi <= 25 -> 25  // 강한 과매도
            currentRsi <= 30 -> 20  // 과매도
            hasBullishDivergence(rsiValues, candles) -> 15  // 상향 다이버전스
            currentRsi <= 40 -> 10  // 약간 과매도
            else -> 0
        }
    }

    /**
     * MACD 점수 [25점]
     *
     * 조건:
     * - 25점: 시그널 상향 크로스 + RSI 30-50
     * - 20점: 시그널 상향 크로스
     * - 15점: MACD 상향 반전 (히스토그램)
     * - 10점: MACD > 0 (제로라인 상단)
     * - 0점: 그 외
     */
    private fun calculateMacdScore(candles: List<Candle>): Int {
        val macdData = calculateMacdData(candles)
        if (macdData == null) return 0

        val (macdLine, signalLine, histogram) = macdData
        val rsiValues = calculateRsiValues(candles)
        val currentRsi = rsiValues.lastOrNull() ?: 50.0

        return when {
            // 시그널 상향 크로스 + RSI 30-50 = 가장 강한 신호
            isBullishCross(macdLine, signalLine) && currentRsi in 30.0..50.0 -> 25
            isBullishCross(macdLine, signalLine) -> 20  // 시그널 상향 크로스
            isHistogramBullishReversal(histogram) -> 15  // 히스토그램 상향 반전
            macdLine.last() > 0 -> 10  // 제로라인 상단
            else -> 0
        }
    }

    /**
     * 볼린저밴드 점수 [25점]
     *
     * 조건 (%B 기준):
     * - 25점: %B ≤ 0 + MACD 상승 반전
     * - 20점: %B ≤ 0.1 (하단밴드 근접)
     * - 15점: %B ≤ 0.2 (하단밴드 근처)
     * - 10점: W 바텀 패턴
     * - 0점: 그 외
     */
    private fun calculateBollingerScore(candles: List<Candle>): Int {
        val bbData = calculateBollingerBands(candles)
        if (bbData == null) return 0

        val upperBand = bbData.upperBand
        val lowerBand = bbData.lowerBand
        val middleBand = bbData.middleBand
        val currentPrice = bbData.currentPrice
        val percentB = calculatePercentB(currentPrice, upperBand, lowerBand)
        val macdData = calculateMacdData(candles)

        return when {
            // %B ≤ 0 + MACD 상승 반전 = 가장 강한 신호
            percentB <= 0.0 && macdData != null && isHistogramBullishReversal(macdData.third) -> 25
            percentB <= 0.1 -> 20  // 하단밴드 근접
            percentB <= 0.2 -> 15  // 하단밴드 근처
            hasWBottom(candles, lowerBand) -> 10  // W 바텀 패턴
            else -> 0
        }
    }

    /**
     * 거래량 점수 [25점]
     *
     * 조건:
     * - 25점: 거래량 ≥ 평균 × 200%
     * - 20점: 거래량 ≥ 평균 × 150%
     * - 15점: 거래량 ≥ 평균 × 120%
     * - 10점: 거래량 ≥ 평균 × 100%
     * - 0점: 그 외
     */
    private fun calculateVolumeScore(candles: List<Candle>): Int {
        if (candles.size < volumePeriod + 1) return 0

        val currentVolume = candles.last().volume.toDouble()
        val avgVolume = candles.takeLast(volumePeriod + 1).dropLast(1)
            .map { it.volume.toDouble() }
            .average()

        if (avgVolume <= 0) return 0

        val volumeRatio = currentVolume / avgVolume

        return when {
            volumeRatio >= 2.0 -> 25  // 200% 이상
            volumeRatio >= 1.5 -> 20  // 150% 이상
            volumeRatio >= 1.2 -> 15  // 120% 이상
            volumeRatio >= 1.0 -> 10  // 100% 이상
            else -> 0
        }
    }

    // ==================== Helper Methods ====================

    private fun calculateRsiValues(candles: List<Candle>): List<Double> {
        val closes = candles.map { it.close.toDouble() }
        val period = rsiPeriod

        if (closes.size < period + 1) return emptyList()

        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()

        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) {
                gains.add(diff)
                losses.add(0.0)
            } else {
                gains.add(0.0)
                losses.add(abs(diff))
            }
        }

        val rsiValues = mutableListOf<Double>()
        var avgGain = gains.take(period).average()
        var avgLoss = losses.take(period).average()

        // 초기 RSI
        rsiValues.add(calculateRsiFromAverages(avgGain, avgLoss))

        // 이후 RSI (Wilder's smoothing)
        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
            rsiValues.add(calculateRsiFromAverages(avgGain, avgLoss))
        }

        return rsiValues
    }

    private fun calculateRsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        return when {
            avgLoss == 0.0 && avgGain == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> {
                val rs = avgGain / avgLoss
                100.0 - (100.0 / (1 + rs))
            }
        }
    }

    private fun calculateMacdData(candles: List<Candle>): Triple<List<Double>, List<Double>, List<Double>>? {
        val closes = candles.map { it.close.toDouble() }

        val emaFast = calculateEma(closes, macdFast)
        val emaSlow = calculateEma(closes, macdSlow)

        if (emaFast.isEmpty() || emaSlow.isEmpty()) return null

        // 빠른 EMA는 더 일찍 시작하므로 느린 EMA 기준으로 정렬한다.
        val alignmentOffset = macdSlow - macdFast
        if (alignmentOffset < 0 || emaFast.size <= alignmentOffset) return null
        val alignedFastEma = emaFast.drop(alignmentOffset)
        if (alignedFastEma.size != emaSlow.size) return null

        val macdLine = mutableListOf<Double>()
        for (i in emaSlow.indices) {
            macdLine.add(alignedFastEma[i] - emaSlow[i])
        }

        val signalLine = calculateEma(macdLine, macdSignal)
        if (signalLine.isEmpty()) return null

        val histogram = mutableListOf<Double>()
        val macdOffset = macdLine.size - signalLine.size
        if (macdOffset < 0) return null
        for (i in signalLine.indices) {
            histogram.add(macdLine[i + macdOffset] - signalLine[i])
        }

        return Triple(macdLine, signalLine, histogram)
    }

    private fun calculateBollingerBands(candles: List<Candle>): BollingerBandsData? {
        val closes = candles.map { it.close.toDouble() }
        val period = bollingerPeriod

        if (closes.size < period) return null

        val recentCloses = closes.takeLast(period)
        val sma = recentCloses.average()
        val variance = recentCloses.map { (it - sma).let { diff -> diff * diff } }.average()
        val stdDev = sqrt(variance)

        val upperBand = sma + (bollingerStdDev * stdDev)
        val lowerBand = sma - (bollingerStdDev * stdDev)
        val currentPrice = closes.last()

        return BollingerBandsData(upperBand, lowerBand, sma, stdDev, currentPrice)
    }

    /**
     * 볼린저 밴드 데이터 (5개 값)
     */
    data class BollingerBandsData(
        val upperBand: Double,
        val lowerBand: Double,
        val middleBand: Double,
        val stdDev: Double,
        val currentPrice: Double
    )

    private fun calculatePercentB(price: Double, upper: Double, lower: Double): Double {
        val bandWidth = upper - lower
        return if (bandWidth > 0) {
            (price - lower) / bandWidth
        } else 0.5
    }

    private fun hasBullishDivergence(rsiValues: List<Double>, candles: List<Candle>): Boolean {
        if (rsiValues.size < 10) return false

        // 최근 10개 RSI의 저점 확인
        val recentRsi = rsiValues.takeLast(10)
        val recentPrices = candles.takeLast(10).map { it.low.toDouble() }

        val rsiMinIdx = recentRsi.indexOf(recentRsi.minOrNull() ?: return false)
        val priceMinIdx = recentPrices.indexOf(recentPrices.minOrNull() ?: return false)

        // RSI는 상승하지만 가격은 하락 = 상향 다이버전스
        val rsiTrend = recentRsi.last() - recentRsi.first()
        val priceTrend = recentPrices.last() - recentPrices.first()

        return rsiTrend > 0 && priceTrend < 0
    }

    private fun isBullishCross(macdLine: List<Double>, signalLine: List<Double>): Boolean {
        if (macdLine.size < 2 || signalLine.size < 2) return false

        // signalLine은 macdLine보다 길이가 짧음 (offset만큼)
        val offset = macdLine.size - signalLine.size

        if (offset < 0) return false

        // 최신 두 시점의 정렬된 MACD/Signal 비교로 크로스를 판단한다.
        val prevMacd = macdLine[macdLine.size - 2]
        val currMacd = macdLine.last()
        val prevSignal = signalLine[signalLine.size - 2]
        val currSignal = signalLine.last()

        // 이전: MACD < Signal, 현재: MACD > Signal = 상향 크로스
        return prevMacd <= prevSignal && currMacd > currSignal
    }

    private fun isHistogramBullishReversal(histogram: List<Double>): Boolean {
        if (histogram.size < 3) return false

        // 최소 3개 연속 음수 → 양수 전환
        val lastThree = histogram.takeLast(3)
        return lastThree[0] < 0 && lastThree[1] < 0 && lastThree[2] > 0
    }

    private fun hasWBottom(candles: List<Candle>, lowerBand: Double): Boolean {
        if (candles.size < 10) return false

        val lows = candles.takeLast(10).map { it.low.toDouble() }

        // W 패턴: 저가-고가-저가-고가-상승
        val w1 = lows[0]
        val w2 = lows[2]
        val w3 = lows[4]

        // 두 저점이 하단밴드 근처이고 비슷한 높이 (±1%)
        val nearLowerBand = w1 < lowerBand * 1.01 && w2 < lowerBand * 1.01 && w3 < lowerBand * 1.01
        val similarLows = abs(w1 - w2) / w1 < 0.01 && abs(w2 - w3) / w2 < 0.01

        return nearLowerBand && similarLows
    }

    private fun determineSignal(score: Int): SignalType {
        return when {
            score >= 100 -> SignalType.STRONG_BUY
            score >= 75 -> SignalType.BUY
            score >= 50 -> SignalType.WEAK_BUY
            else -> SignalType.NO_SIGNAL
        }
    }

    private fun buildDetails(
        rsiScore: Int,
        macdScore: Int,
        bollingerScore: Int,
        volumeScore: Int
    ): String {
        return buildString {
            append("컨플루언스 점수: $rsiScore+$macdScore+$bollingerScore+$volumeScore = ")
            append(rsiScore + macdScore + bollingerScore + volumeScore)
            append("\nRSI: ${getScoreText(rsiScore)}, ")
            append("MACD: ${getScoreText(macdScore)}, ")
            append("볼린저: ${getScoreText(bollingerScore)}, ")
            append("거래량: ${getScoreText(volumeScore)}")
        }
    }

    private fun getScoreText(score: Int): String {
        return when (score) {
            25 -> "최상"
            20 -> "우수"
            15 -> "양호"
            10 -> "보통"
            else -> "부족"
        }
    }
}

/**
 * 컨플루언스 분석 결과
 */
data class ConfluenceResult(
    val score: Int,                      // 총점 (0-100)
    val signal: SignalType,              // 신호 유형
    val rsiScore: Int,                   // RSI 점수 (0-25)
    val macdScore: Int,                  // MACD 점수 (0-25)
    val bollingerScore: Int,             // 볼린저 점수 (0-25)
    val volumeScore: Int,                // 거래량 점수 (0-25)
    val details: String                   // 상세 설명
)

/**
 * 신호 유형
 */
enum class SignalType {
    STRONG_BUY,      // 100점: 강한 매수 (포지션 1.5배)
    BUY,             // 75점: 매수 (기본 포지션)
    WEAK_BUY,        // 50점: 약한 매수 (포지션 0.5배)
    NO_SIGNAL,       // 50점 미만: 진입 불가
    INSUFFICIENT_DATA // 데이터 부족
}
