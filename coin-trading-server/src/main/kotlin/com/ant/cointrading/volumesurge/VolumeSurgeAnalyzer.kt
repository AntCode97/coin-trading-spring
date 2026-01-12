package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.config.VolumeSurgeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 기술적 분석 결과
 */
data class VolumeSurgeAnalysis(
    val market: String,
    val rsi: Double,
    val macdSignal: String,      // BULLISH / BEARISH / NEUTRAL
    val bollingerPosition: String, // LOWER / MIDDLE / UPPER
    val volumeRatio: Double,     // 20일 평균 대비 비율
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
    private val properties: VolumeSurgeProperties
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
     * 기술적 분석 수행
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

        // MACD 계산
        val macdSignal = calculateMacdSignal(closes)

        // 볼린저밴드 위치 계산
        val bollingerPosition = calculateBollingerPosition(closes)

        // 거래량 비율 계산
        val volumeRatio = calculateVolumeRatio(volumes)

        // 컨플루언스 점수 계산
        val confluenceScore = calculateConfluenceScore(
            rsi = rsi,
            macdSignal = macdSignal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio
        )

        log.info("""
            [$market] 분석 결과:
            RSI: $rsi
            MACD: $macdSignal
            Bollinger: $bollingerPosition
            VolumeRatio: $volumeRatio
            Confluence: $confluenceScore
        """.trimIndent())

        return VolumeSurgeAnalysis(
            market = market,
            rsi = rsi,
            macdSignal = macdSignal,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            confluenceScore = confluenceScore
        )
    }

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
     * MACD 신호 계산
     */
    private fun calculateMacdSignal(closes: List<Double>): String {
        if (closes.size < MACD_SLOW + MACD_SIGNAL) return "NEUTRAL"

        val emaFast = calculateEma(closes, MACD_FAST)
        val emaSlow = calculateEma(closes, MACD_SLOW)

        val macdLine = emaFast.zip(emaSlow) { f, s -> f - s }
        if (macdLine.size < MACD_SIGNAL) return "NEUTRAL"

        val signalLine = calculateEma(macdLine, MACD_SIGNAL)

        val currentMacd = macdLine.last()
        val currentSignal = signalLine.last()
        val prevMacd = macdLine[macdLine.size - 2]
        val prevSignal = signalLine[signalLine.size - 2]

        // 골든크로스 (MACD가 시그널 상향 돌파)
        if (prevMacd <= prevSignal && currentMacd > currentSignal) {
            return "BULLISH"
        }

        // 데드크로스 (MACD가 시그널 하향 돌파)
        if (prevMacd >= prevSignal && currentMacd < currentSignal) {
            return "BEARISH"
        }

        // 현재 위치 기반 판단
        return when {
            currentMacd > currentSignal -> "BULLISH"
            currentMacd < currentSignal -> "BEARISH"
            else -> "NEUTRAL"
        }
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
     * 컨플루언스 점수 계산 (0~100)
     *
     * 각 지표별 25점씩 배점:
     * - RSI: 과매도(30 이하) = 25점
     * - MACD: 상승 신호 = 25점
     * - 볼린저: 하단밴드 = 25점
     * - 거래량: 1.5배 이상 = 25점
     */
    private fun calculateConfluenceScore(
        rsi: Double,
        macdSignal: String,
        bollingerPosition: String,
        volumeRatio: Double
    ): Int {
        var score = 0

        // RSI 점수 (과매도 영역에서 높은 점수)
        score += when {
            rsi <= 30 -> 25
            rsi <= 40 -> 15
            rsi <= 50 -> 10
            rsi <= 60 -> 5
            else -> 0
        }

        // MACD 점수
        score += when (macdSignal) {
            "BULLISH" -> 25
            "NEUTRAL" -> 10
            else -> 0
        }

        // 볼린저밴드 점수 (하단밴드에서 높은 점수)
        score += when (bollingerPosition) {
            "LOWER" -> 25
            "MIDDLE" -> 10
            else -> 0
        }

        // 거래량 점수
        score += when {
            volumeRatio >= 3.0 -> 25  // 300% 이상
            volumeRatio >= 2.0 -> 20  // 200% 이상
            volumeRatio >= 1.5 -> 15  // 150% 이상
            volumeRatio >= 1.2 -> 10  // 120% 이상
            else -> 0
        }

        return score
    }
}
