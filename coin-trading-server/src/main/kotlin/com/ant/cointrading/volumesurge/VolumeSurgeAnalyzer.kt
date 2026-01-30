package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.indicator.DivergenceDetector
import com.ant.cointrading.indicator.DivergenceStrength
import com.ant.cointrading.indicator.DivergenceType
import com.ant.cointrading.indicator.EmaCalculator
import com.ant.cointrading.indicator.MultiTimeFrameAnalyzer
import com.ant.cointrading.indicator.RsiCalculator
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
    val mtfAlignmentBonus: Int,  // 멀티 타임프레임 정렬 보너스 (0 ~ 15)
    val mtfRecommendation: String, // MTF 진입 권장사항
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
    private val divergenceDetector: DivergenceDetector,
    private val multiTimeFrameAnalyzer: MultiTimeFrameAnalyzer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 기술적 지표 상수 (표준값은 TradingConstants 사용)
        const val RSI_PERIOD = TradingConstants.RSI_PERIOD_STANDARD
        const val MACD_FAST = TradingConstants.MACD_FAST_STANDARD
        const val MACD_SLOW = TradingConstants.MACD_SLOW_STANDARD
        const val MACD_SIGNAL = TradingConstants.MACD_SIGNAL_STANDARD
        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_STD = 2.0
        const val VOLUME_MA_PERIOD = 20
    }

    /**
     * 기술적 분석 수행 (확장 - MTF 정렬 포함)
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
        val rsi = RsiCalculator.calculate(closes, RSI_PERIOD)

        // RSI 다이버전스 탐지 (quant-trading 스킬: 다이버전스 = 반전 신호)
        val rsiValues = RsiCalculator.calculateAll(closes)
        val divergenceResult = divergenceDetector.detectRsiDivergence(closes, rsiValues)

        // MACD 계산 (시그널 + 히스토그램 반전)
        val macdResult = calculateMacdWithHistogram(closes)
        val macdSignal = macdResult.signal
        val macdHistogramReversal = macdResult.histogramReversal

        // 볼린저밴드 위치 계산
        val bollingerPosition = calculateBollingerPosition(closes)

        // 거래량 비율 계산
        val volumeRatio = calculateVolumeRatio(volumes)

        // 멀티 타임프레임 분석 (quant-trading 스킬 기반)
        val mtfAnalysis = multiTimeFrameAnalyzer.analyze(market)
        val mtfAlignmentBonus = mtfAnalysis?.alignmentBonus ?: 0
        val mtfRecommendation = mtfAnalysis?.recommendation ?: "MTF 분석 불가"

        // Volume Surge는 모멘텀 전략이므로 MTF 완화:
        // - 3개 이상 하락 정렬 (bonus <= -15)만 차단
        // - -5 (2개 하락)은 허용 (거래량이 핵심)
        // - 0 (혼조)도 허용
        if (mtfAlignmentBonus <= -15) {
            log.warn("[$market] MTF 강한 하락 정렬 차단: $mtfRecommendation")
            return VolumeSurgeAnalysis(
                market = market,
                rsi = rsi,
                macdSignal = macdSignal,
                macdHistogramReversal = macdHistogramReversal,
                bollingerPosition = bollingerPosition,
                volumeRatio = volumeRatio,
                rsiDivergence = divergenceResult.type,
                divergenceStrength = if (divergenceResult.hasDivergence) divergenceResult.strength else null,
                confluenceScore = 0,
                mtfAlignmentBonus = mtfAlignmentBonus,
                mtfRecommendation = mtfRecommendation,
                rejectReason = "MTF 강한 하락 정렬: $mtfRecommendation"
            )
        }

        // MTF 약한 하락/혼조는 거래량 보고 판단 (로그만 출력)
        if (mtfAlignmentBonus < 0) {
            log.info("[$market] MTF 약한 하락/혼조지만 거래량 중심 진입 검토: $mtfRecommendation")
        }

        // 컨플루언스 점수 계산 (MTF 보너스 포함)
        val confluenceScore = calculateConfluenceScore(
            rsi = rsi,
            macdSignal = macdSignal,
            macdHistogramReversal = macdHistogramReversal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            rsiDivergence = divergenceResult.type,
            divergenceStrength = divergenceResult.strength,
            mtfAlignmentBonus = mtfAlignmentBonus
        )

        log.info("""
            [$market] 분석 결과:
            RSI: ${String.format("%.2f", rsi)}
            MACD: $macdSignal (히스토그램 반전: ${if (macdHistogramReversal) "O" else "X"})
            볼린저: $bollingerPosition
            거래량비율: ${String.format("%.2f", volumeRatio)}x
            RSI 다이버전스: ${divergenceResult.type} (${divergenceResult.strength})
            MTF 정렬: +${mtfAlignmentBonus}점 ($mtfRecommendation)
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
            confluenceScore = confluenceScore,
            mtfAlignmentBonus = mtfAlignmentBonus,
            mtfRecommendation = mtfRecommendation
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
     * MACD 신호 + 히스토그램 반전 계산
     *
     * quant-trading 스킬: 히스토그램 반전 = 조기 진입 신호
     */
    private fun calculateMacdWithHistogram(closes: List<Double>): MacdResult {
        if (closes.size < MACD_SLOW + MACD_SIGNAL) {
            return MacdResult("NEUTRAL", false)
        }

        val (emaFast, emaSlow) = EmaCalculator.calculateMacdEmas(closes, MACD_FAST, MACD_SLOW)

        val macdLine = emaFast.zip(emaSlow) { f, s -> f - s }
        if (macdLine.size < MACD_SIGNAL) {
            return MacdResult("NEUTRAL", false)
        }

        val signalLine = EmaCalculator.calculate(macdLine, MACD_SIGNAL)

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
     * 컨플루언스 점수 계산 (ConfluenceScoreCalculator 위임)
     */
    private fun calculateConfluenceScore(
        rsi: Double,
        macdSignal: String,
        macdHistogramReversal: Boolean,
        bollingerPosition: String,
        volumeRatio: Double,
        rsiDivergence: com.ant.cointrading.indicator.DivergenceType,
        divergenceStrength: com.ant.cointrading.indicator.DivergenceStrength?,
        mtfAlignmentBonus: Int = 0
    ): Int {
        return ConfluenceScoreCalculator.calculate(
            rsi = rsi,
            macdSignal = macdSignal,
            macdHistogramReversal = macdHistogramReversal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            rsiDivergence = rsiDivergence,
            divergenceStrength = divergenceStrength,
            mtfAlignmentBonus = mtfAlignmentBonus
        )
    }
}
