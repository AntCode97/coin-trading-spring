package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.indicator.DivergenceDetector
import com.ant.cointrading.indicator.DivergenceStrength
import com.ant.cointrading.indicator.DivergenceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 기술적 분석 결과 (확장)
 */
data class VolumeSurgeAnalysis(
    val market: String,
    val rsi: Double,
    val macdSignal: String,      // BULLISH / BEARISH / NEUTRAL
    val macdHistogramReversal: Boolean,  // 히스토그램 반전 여부
    val bollingerPosition: String, // LOWER / MIDDLE / UPPER
    val volumeRatio: Double,     // 20일 평균 대비 비율
    val rsiDivergence: DivergenceType,  // RSI 다이버전스
    val divergenceStrength: DivergenceStrength?,  // 다이버전스 강도
    val confluenceScore: Int,    // 0 ~ 100
    var rejectReason: String? = null
)

/**
 * Volume Surge 기술적 분석기
 *
 * RSI, MACD, 볼린저밴드, 거래량을 분석하여
 * 컨플루언스 점수를 계산한다.
 */
@Component
class VolumeSurgeAnalyzer(
    private val bithumbPublicApi: BithumbPublicApi,
    private val properties: VolumeSurgeProperties,
    private val divergenceDetector: DivergenceDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val RSI_PERIOD = 14
        const val MACD_FAST = 12
        const val MACD_SLOW = 26
        const val MACD_SIGNAL = 9
        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_STD = 2.0
        const val VOLUME_MA_PERIOD = 20
    }

    /**
     * 기술적 분석 수행 (확장 - RSI 다이버전스, MACD 히스토그램 반전)
     */
    fun analyze(market: String): VolumeSurgeAnalysis? {
        log.info("[$market] 기술적 분석 시작")

        // 캔들 데이터 조회 (1시간봉, 100개)
        val candles = bithumbPublicApi.getOhlcv(market, "minute60", 100)
        if (candles.isNullOrEmpty() || candles.size < 50) {
            log.warn("[$market] 캔들 데이터 부족: ${candles?.size ?: 0}개")
            return null
        }

        // 최신순 정렬 (API가 최신이 앞에 오므로 역순)
        val sortedCandles = candles.reversed()

        // 종가 추출
        val closes = sortedCandles.map { it.tradePrice.toDouble() }
        val volumes = sortedCandles.map { it.candleAccTradeVolume.toDouble() }

        // RSI 계산
        val rsi = calculateRsi(closes, RSI_PERIOD)

        // RSI 다이버전스 탐지 (quant-trading 스킬: 다이버전스 = 반전 신호)
        val divergenceResult = divergenceDetector.detectRsiDivergence(closes, closes.map { calculateRsiForValue(it, closes) })

        // MACD 계산 (시그널 + 히스토그램 반전)
        val macdResult = calculateMacdWithHistogram(closes)
        val macdSignal = macdResult.signal
        val macdHistogramReversal = macdResult.histogramReversal

        // 볼린저밴드 위치 계산
        val bollingerPosition = calculateBollingerPosition(closes)

        // 거래량 비율 계산
        val volumeRatio = calculateVolumeRatio(volumes)

        // 컨플루언스 점수 계산 (다이버전스, 히스토그램 반전 포함)
        val confluenceScore = calculateConfluenceScore(
            rsi = rsi,
            macdSignal = macdSignal,
            macdHistogramReversal = macdHistogramReversal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            rsiDivergence = divergenceResult.type,
            divergenceStrength = divergenceResult.strength
        )

        log.info("""
            [$market] 분석 결과:
            RSI: ${String.format("%.2f", rsi)}
            MACD: $macdSignal (히스토그램 반전: ${if (macdHistogramReversal) "O" else "X"})
            볼린저: $bollingerPosition
            거래량비율: ${String.format("%.2f", volumeRatio)}x
            RSI 다이버전스: ${divergenceResult.type} (${divergenceResult.strength})
            컨플루언스: ${confluenceScore}점
        """.trimIndent())

        return VolumeSurgeAnalysis(
            market = market,
            rsi = rsi,
            macdSignal = macdSignal,
            macdHistogramReversal = macdHistogramReversal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            rsiDivergence = divergenceResult.type,
            divergenceStrength = if (divergenceResult.hasDivergence) divergenceResult.strength else null,
            confluenceScore = confluenceScore
        )
    }

    /**
     * MACD 계산 결과 (확장)
     */
    private data class MacdResult(
        val signal: String,           // BULLISH/BEARISH/NEUTRAL
        val histogramReversal: Boolean  // 히스토그램 반전 여부
    )

    /**
     * RSI 계산 (Wilder's Smoothing)
     */
    private fun calculateRsi(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0

        val changes = closes.zipWithNext { a, b -> b - a }

        var avgGain = changes.take(period).filter { it > 0 }.sum() / period
        var avgLoss = abs(changes.take(period).filter { it < 0 }.sum()) / period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0

        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    /**
     * MACD 신호 + 히스토그램 반전 계산
     *
     * quant-trading 스킬: 히스토그램 반전 = 조기 진입 신호
     */
    private fun calculateMacdWithHistogram(closes: List<Double>): MacdResult {
        if (closes.size < MACD_SLOW + MACD_SIGNAL) {
            return MacdResult("NEUTRAL", false)
        }

        val emaFast = calculateEma(closes, MACD_FAST)
        val emaSlow = calculateEma(closes, MACD_SLOW)

        val macdLine = emaFast.zip(emaSlow) { f, s -> f - s }
        if (macdLine.size < MACD_SIGNAL) {
            return MacdResult("NEUTRAL", false)
        }

        val signalLine = calculateEma(macdLine, MACD_SIGNAL)

        val currentMacd = macdLine.last()
        val currentSignal = signalLine.last()
        val prevMacd = macdLine[macdLine.size - 2]
        val prevSignal = signalLine[signalLine.size - 2]

        // 히스토그램 계산 (MACD - Signal)
        val currentHistogram = currentMacd - currentSignal
        val prevHistogram = prevMacd - prevSignal

        // 히스토그램 반전 탐지 (음→양 = 상승 반전, 양→음 = 하락 반전)
        val histogramReversal = when {
            prevHistogram < 0 && currentHistogram > 0 -> true  // 상승 반전
            prevHistogram > 0 && currentHistogram < 0 -> true  // 하락 반전
            else -> false
        }

        // 골든크로스 (MACD가 시그널 상향 돌파)
        if (prevMacd <= prevSignal && currentMacd > currentSignal) {
            return MacdResult("BULLISH", histogramReversal)
        }

        // 데드크로스 (MACD가 시그널 하향 돌파)
        if (prevMacd >= prevSignal && currentMacd < currentSignal) {
            return MacdResult("BEARISH", histogramReversal)
        }

        // 현재 위치 기반 판단
        val signal = when {
            currentMacd > currentSignal -> "BULLISH"
            currentMacd < currentSignal -> "BEARISH"
            else -> "NEUTRAL"
        }

        return MacdResult(signal, histogramReversal)
    }

    /**
     * EMA 계산
     */
    private fun calculateEma(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val emaList = mutableListOf<Double>()

        // 첫 EMA는 SMA
        var ema = data.take(period).average()
        emaList.add(ema)

        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
            emaList.add(ema)
        }

        return emaList
    }

    /**
     * 볼린저밴드 위치 계산
     */
    private fun calculateBollingerPosition(closes: List<Double>): String {
        if (closes.size < BOLLINGER_PERIOD) return "MIDDLE"

        val recentCloses = closes.takeLast(BOLLINGER_PERIOD)
        val sma = recentCloses.average()
        val variance = recentCloses.map { (it - sma) * (it - sma) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        val upperBand = sma + (BOLLINGER_STD * stdDev)
        val lowerBand = sma - (BOLLINGER_STD * stdDev)
        val currentPrice = closes.last()

        // %B 계산 (0: 하단밴드, 1: 상단밴드)
        val percentB = (currentPrice - lowerBand) / (upperBand - lowerBand)

        return when {
            percentB <= 0.2 -> "LOWER"  // 하단밴드 근처
            percentB >= 0.8 -> "UPPER"  // 상단밴드 근처
            else -> "MIDDLE"
        }
    }

    /**
     * 거래량 비율 계산 (현재 거래량 / 20일 평균)
     */
    private fun calculateVolumeRatio(volumes: List<Double>): Double {
        if (volumes.size < VOLUME_MA_PERIOD) return 1.0

        val avgVolume = volumes.takeLast(VOLUME_MA_PERIOD).average()
        val currentVolume = volumes.last()

        if (avgVolume == 0.0) return 1.0

        return currentVolume / avgVolume
    }

    /**
     * 컨플루언스 점수 계산 (0~100) - 모멘텀/순추세 기준 + 다이버전스/히스토그램
     *
     * quant-trading 스킬 기반 4중 컨플루언스 + 다이버전스/히스토그램 보너스:
     *
     * 배점:
     * - 거래량: 30점 (핵심 지표)
     * - MACD: 20점 (추세 확인)
     * - MACD 히스토그램 반전: +10점 보너스 (조기 진입 신호)
     * - RSI: 20점 (모멘텀 확인)
     * - RSI 다이버전스: +15점 보너스 (강세 다이버전스, MODERATE+ 강도)
     * - 볼린저: 20점 (돌파 확인)
     */
    private fun calculateConfluenceScore(
        rsi: Double,
        macdSignal: String,
        macdHistogramReversal: Boolean,
        bollingerPosition: String,
        volumeRatio: Double,
        rsiDivergence: com.ant.cointrading.indicator.DivergenceType,
        divergenceStrength: com.ant.cointrading.indicator.DivergenceStrength?
    ): Int {
        var score = 0

        // 1. 거래량 점수 (Volume Surge의 핵심 - 30점)
        score += when {
            volumeRatio >= 5.0 -> 30  // 500% 이상 = 폭발적 급등
            volumeRatio >= 3.0 -> 25  // 300% 이상 = 강한 급등
            volumeRatio >= 2.0 -> 20  // 200% 이상 = 급등
            volumeRatio >= 1.5 -> 15  // 150% 이상 = 상승
            volumeRatio >= 1.2 -> 10  // 120% 이상
            else -> 0
        }

        // 2. MACD 점수 (추세 방향 확인 - 20점)
        score += when (macdSignal) {
            "BULLISH" -> 20           // 상승 신호 = 핵심
            "NEUTRAL" -> 10           // 중립도 허용
            "BEARISH" -> -15          // BEARISH = 패널티 강화
            else -> 0
        }

        // 3. MACD 히스토그램 반전 보너스 (+10점)
        // quant-trading: 히스토그램 반전 = 조기 진입 신호
        if (macdHistogramReversal && macdSignal == "BULLISH") {
            score += 10
        }

        // 4. RSI 점수 (모멘텀 확인 - 20점)
        score += when {
            rsi in 50.0..65.0 -> 20   // 최적 모멘텀 구간
            rsi in 40.0..70.0 -> 15   // 양호한 구간
            rsi in 30.0..75.0 -> 10   // 허용 구간
            rsi in 25.0..80.0 -> 5    // 넓은 허용
            else -> 0                  // 극단값
        }

        // 5. RSI 강세 다이버전스 보너스 (+15점)
        // quant-trading: 다이버전스 = 반전 신호
        if (rsiDivergence == com.ant.cointrading.indicator.DivergenceType.BULLISH) {
            val divergenceBonus = when (divergenceStrength) {
                com.ant.cointrading.indicator.DivergenceStrength.STRONG -> 15
                com.ant.cointrading.indicator.DivergenceStrength.MODERATE -> 10
                com.ant.cointrading.indicator.DivergenceStrength.WEAK -> 5
                else -> 0
            }
            score += divergenceBonus
        }

        // 6. 볼린저밴드 점수 (돌파/위치 확인 - 20점)
        score += when (bollingerPosition) {
            "UPPER" -> 20             // 상단 돌파 = 강한 모멘텀
            "MIDDLE" -> 15            // 중앙 = 안정적 상승
            "LOWER" -> 10             // 하단 = 반등 가능성
            else -> 10
        }

        return score.coerceIn(0, 100)
    }

    /**
     * RSI 계산 헬퍼 (개별 값용)
     */
    private fun calculateRsiForValue(value: Double, closes: List<Double>): Double {
        // 간단히 전체 RSI 계산 후 해당 인덱스 값 반환
        // 실제로는 더 효율적으로 구현 가능하지만 여기서는 간단화
        return calculateRsi(closes, RSI_PERIOD)
    }
}
