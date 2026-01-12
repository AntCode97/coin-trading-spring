package com.ant.cointrading.mcp.tool

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

// ========================================
// 응답 Data Classes
// ========================================

/** RSI 분석 결과 */
data class RsiResult(
    val market: String,
    val period: Int,
    val rsi: BigDecimal,
    val signal: String,  // OVERBOUGHT, OVERSOLD, NEUTRAL
    val error: String? = null
)

/** MACD 분석 결과 */
data class MacdResult(
    val market: String,
    val macdLine: BigDecimal,
    val signalLine: BigDecimal,
    val histogram: BigDecimal,
    val signal: String,  // BULLISH, BEARISH, NEUTRAL
    val error: String? = null
)

/** 볼린저밴드 분석 결과 */
data class BollingerBandsResult(
    val market: String,
    val currentPrice: BigDecimal,
    val upperBand: BigDecimal,
    val middleBand: BigDecimal,
    val lowerBand: BigDecimal,
    val position: String,  // UPPER, LOWER, MIDDLE
    val bandWidth: BigDecimal? = null,  // 밴드폭 (%)
    val percentB: BigDecimal? = null,   // %B 지표
    val error: String? = null
)

/** 이동평균선 분석 결과 */
data class MovingAveragesResult(
    val market: String,
    val currentPrice: BigDecimal,
    val sma: Map<String, BigDecimal>,
    val ema: Map<String, BigDecimal>,
    val trend: String? = null,  // BULLISH, BEARISH, NEUTRAL
    val error: String? = null
)

/**
 * 기술적 분석 지표 계산 MCP 도구
 */
@Component
class TechnicalAnalysisTools(
    private val publicApi: BithumbPublicApi
) {

    @McpTool(description = "RSI(상대강도지수)를 계산합니다. 과매수(>70)/과매도(<30) 판단에 사용됩니다.")
    fun calculateRsi(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "RSI 계산 기간 (기본값: 14)") period: Int,
        @McpToolParam(description = "시간 간격: day, minute60 등") interval: String
    ): RsiResult {
        val ohlcv = publicApi.getOhlcv(market, interval, period + 50, null)
        if (ohlcv == null || ohlcv.size < period + 1) {
            return RsiResult(
                market = market,
                period = period,
                rsi = BigDecimal.ZERO,
                signal = "UNKNOWN",
                error = "Not enough data to calculate RSI"
            )
        }

        // 종가 추출 (시간순 정렬)
        val closes = ohlcv
            .sortedBy { it.candleDateTimeKst }
            .map { it.tradePrice }

        // RSI 계산
        val gains = mutableListOf<BigDecimal>()
        val losses = mutableListOf<BigDecimal>()

        for (i in 1 until closes.size) {
            val diff = closes[i].subtract(closes[i - 1])
            if (diff > BigDecimal.ZERO) {
                gains.add(diff)
                losses.add(BigDecimal.ZERO)
            } else {
                gains.add(BigDecimal.ZERO)
                losses.add(diff.abs())
            }
        }

        // 최근 period 기간의 평균 이익/손실
        val avgGain = gains.takeLast(period)
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)

        val avgLoss = losses.takeLast(period)
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)

        val rsi = if (avgLoss == BigDecimal.ZERO) {
            BigDecimal.valueOf(100)
        } else {
            val rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP)
            BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP)
            )
        }

        val signal = when {
            rsi > BigDecimal.valueOf(70) -> "OVERBOUGHT"
            rsi < BigDecimal.valueOf(30) -> "OVERSOLD"
            else -> "NEUTRAL"
        }

        return RsiResult(
            market = market,
            period = period,
            rsi = rsi.setScale(2, RoundingMode.HALF_UP),
            signal = signal
        )
    }

    @McpTool(description = "MACD(이동평균수렴확산)를 계산합니다. 추세 전환 신호 감지에 사용됩니다.")
    fun calculateMacd(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "빠른 이동평균 기간 (기본값: 12)") fastPeriod: Int,
        @McpToolParam(description = "느린 이동평균 기간 (기본값: 26)") slowPeriod: Int,
        @McpToolParam(description = "시그널 라인 기간 (기본값: 9)") signalPeriod: Int,
        @McpToolParam(description = "시간 간격: day, minute60 등") interval: String
    ): MacdResult {
        val requiredData = slowPeriod + signalPeriod + 50
        val ohlcv = publicApi.getOhlcv(market, interval, requiredData, null)
        if (ohlcv == null || ohlcv.size < slowPeriod) {
            return MacdResult(
                market = market,
                macdLine = BigDecimal.ZERO,
                signalLine = BigDecimal.ZERO,
                histogram = BigDecimal.ZERO,
                signal = "UNKNOWN",
                error = "Not enough data to calculate MACD"
            )
        }

        val closes = ohlcv
            .sortedBy { it.candleDateTimeKst }
            .map { it.tradePrice }

        val emaFast = calculateEma(closes, fastPeriod)
        val emaSlow = calculateEma(closes, slowPeriod)

        val macdLine = mutableListOf<BigDecimal>()
        for (i in emaSlow.indices) {
            val fastIdx = i - (closes.size - emaFast.size) + (closes.size - emaSlow.size)
            if (fastIdx in emaFast.indices) {
                macdLine.add(emaFast[fastIdx].subtract(emaSlow[i]))
            }
        }

        val signalLine = calculateEma(macdLine, signalPeriod)

        val currentMacd = macdLine.lastOrNull() ?: BigDecimal.ZERO
        val currentSignal = signalLine.lastOrNull() ?: BigDecimal.ZERO
        val histogram = currentMacd.subtract(currentSignal)

        val signal = when {
            histogram > BigDecimal.ZERO -> "BULLISH"
            histogram < BigDecimal.ZERO -> "BEARISH"
            else -> "NEUTRAL"
        }

        return MacdResult(
            market = market,
            macdLine = currentMacd.setScale(2, RoundingMode.HALF_UP),
            signalLine = currentSignal.setScale(2, RoundingMode.HALF_UP),
            histogram = histogram.setScale(2, RoundingMode.HALF_UP),
            signal = signal
        )
    }

    @McpTool(description = "볼린저 밴드를 계산합니다. 변동성과 가격 범위 분석에 사용됩니다.")
    fun calculateBollingerBands(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "이동평균 기간 (기본값: 20)") period: Int,
        @McpToolParam(description = "표준편차 배수 (기본값: 2)") stdDev: Double,
        @McpToolParam(description = "시간 간격: day, minute60 등") interval: String
    ): BollingerBandsResult {
        val ohlcv = publicApi.getOhlcv(market, interval, period + 10, null)
        if (ohlcv == null || ohlcv.size < period) {
            return BollingerBandsResult(
                market = market,
                currentPrice = BigDecimal.ZERO,
                upperBand = BigDecimal.ZERO,
                middleBand = BigDecimal.ZERO,
                lowerBand = BigDecimal.ZERO,
                position = "UNKNOWN",
                error = "Not enough data to calculate Bollinger Bands"
            )
        }

        val closes = ohlcv
            .sortedBy { it.candleDateTimeKst }
            .map { it.tradePrice }

        val recentCloses = closes.takeLast(period)

        // 중심선 (SMA)
        val middleBand = recentCloses
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)

        // 표준편차 계산
        val variance = recentCloses
            .map { it.subtract(middleBand).pow(2) }
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)

        val stdDeviation = BigDecimal.valueOf(sqrt(variance.toDouble()))

        val upperBand = middleBand.add(stdDeviation.multiply(BigDecimal.valueOf(stdDev)))
        val lowerBand = middleBand.subtract(stdDeviation.multiply(BigDecimal.valueOf(stdDev)))

        val currentPrice = closes.last()
        val position = when {
            currentPrice >= upperBand -> "UPPER"
            currentPrice <= lowerBand -> "LOWER"
            else -> "MIDDLE"
        }

        // 밴드폭 (%) = (상단밴드 - 하단밴드) / 중심선 * 100
        val bandWidth = upperBand.subtract(lowerBand)
            .divide(middleBand, 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP)

        // %B = (현재가 - 하단밴드) / (상단밴드 - 하단밴드)
        val bandRange = upperBand.subtract(lowerBand)
        val percentB = if (bandRange > BigDecimal.ZERO) {
            currentPrice.subtract(lowerBand)
                .divide(bandRange, 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        return BollingerBandsResult(
            market = market,
            currentPrice = currentPrice.setScale(0, RoundingMode.HALF_UP),
            upperBand = upperBand.setScale(0, RoundingMode.HALF_UP),
            middleBand = middleBand.setScale(0, RoundingMode.HALF_UP),
            lowerBand = lowerBand.setScale(0, RoundingMode.HALF_UP),
            position = position,
            bandWidth = bandWidth,
            percentB = percentB
        )
    }

    @McpTool(description = "이동평균선(SMA, EMA)을 계산합니다. 추세 분석에 사용됩니다.")
    fun calculateMovingAverages(
        @McpToolParam(description = "마켓 ID (예: KRW-BTC)") market: String,
        @McpToolParam(description = "이동평균 기간들 (쉼표로 구분, 예: 5,10,20,50,200)") periods: String,
        @McpToolParam(description = "시간 간격: day, minute60 등") interval: String
    ): MovingAveragesResult {
        val periodList = periods.split(",").map { it.trim().toInt() }
        val maxPeriod = periodList.maxOrNull() ?: 200

        val ohlcv = publicApi.getOhlcv(market, interval, maxPeriod + 10, null)
        if (ohlcv.isNullOrEmpty()) {
            return MovingAveragesResult(
                market = market,
                currentPrice = BigDecimal.ZERO,
                sma = emptyMap(),
                ema = emptyMap(),
                error = "Failed to get OHLCV data"
            )
        }

        val closes = ohlcv
            .sortedBy { it.candleDateTimeKst }
            .map { it.tradePrice }

        val smaValues = mutableMapOf<String, BigDecimal>()
        val emaValues = mutableMapOf<String, BigDecimal>()

        for (period in periodList) {
            if (closes.size >= period) {
                // SMA
                val recentCloses = closes.takeLast(period)
                val sma = recentCloses
                    .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                    .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)
                smaValues["sma$period"] = sma.setScale(0, RoundingMode.HALF_UP)

                // EMA
                val ema = calculateEma(closes, period)
                if (ema.isNotEmpty()) {
                    emaValues["ema$period"] = ema.last().setScale(0, RoundingMode.HALF_UP)
                }
            }
        }

        val currentPrice = closes.last()

        // 추세 판단: 단기 EMA > 장기 EMA → BULLISH
        val shortEma = emaValues["ema${periodList.minOrNull()}"]
        val longEma = emaValues["ema${periodList.maxOrNull()}"]
        val trend = when {
            shortEma != null && longEma != null && shortEma > longEma -> "BULLISH"
            shortEma != null && longEma != null && shortEma < longEma -> "BEARISH"
            else -> "NEUTRAL"
        }

        return MovingAveragesResult(
            market = market,
            currentPrice = currentPrice.setScale(0, RoundingMode.HALF_UP),
            sma = smaValues,
            ema = emaValues,
            trend = trend
        )
    }

    /**
     * EMA (지수이동평균) 계산 헬퍼 메서드
     */
    private fun calculateEma(data: List<BigDecimal>, period: Int): List<BigDecimal> {
        if (data.size < period) return emptyList()

        val ema = mutableListOf<BigDecimal>()
        val multiplier = BigDecimal.valueOf(2.0 / (period + 1))

        // 첫 번째 EMA는 SMA로 시작
        val firstSma = data.take(period)
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .divide(BigDecimal.valueOf(period.toLong()), 10, RoundingMode.HALF_UP)
        ema.add(firstSma)

        // 이후 EMA 계산
        for (i in period until data.size) {
            val currentPrice = data[i]
            val previousEma = ema.last()
            val currentEma = currentPrice.subtract(previousEma)
                .multiply(multiplier)
                .add(previousEma)
            ema.add(currentEma)
        }

        return ema
    }
}
