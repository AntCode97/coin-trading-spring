package com.ant.cointrading.indicator

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.model.Candle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 멀티 타임프레임 분석 결과
 */
data class MultiTimeFrameAnalysis(
    val market: String,
    val daily: TimeFrameAnalysis,
    val fourHour: TimeFrameAnalysis,
    val oneHour: TimeFrameAnalysis,
    val fifteenMinute: TimeFrameAnalysis,
    val alignmentScore: Int,          // 정렬 점수 (0 ~ 100)
    val alignmentBonus: Int,          // 정렬 보너스 (0 ~ 15)
    val recommendation: String        // 진입 권장사항
)

/**
 * 단일 타임프레임 분석 결과
 */
data class TimeFrameAnalysis(
    val timeframe: String,
    val ema200Direction: String,     // UP, DOWN, FLAT
    val trend: String,               // UPTREND, DOWNTREND, SIDEWAYS
    val strength: Int,               // 0 ~ 100 (추세 강도)
    val supportLevel: Double?,       // 지지선
    val resistanceLevel: Double?,    // 저항선
    val isBullish: Boolean           // 매수 우위 여부
)

/**
 * 타임프레임 정렬 결과
 */
data class TimeFrameAlignment(
    val alignedCount: Int,           // 정렬된 타임프레임 수
    val totalFrames: Int,            // 전체 타임프레임 수
    val isAligned: Boolean,          // 정렬 여부
    val bonus: Int,                  // 보너스 점수
    val description: String          // 설명
)

/**
 * 멀티 타임프레임 분석기
 *
 * quant-trading 스킬 기반:
 * - 일봉: 대추세 확인 (200 EMA 방향)
 * - 4시간: 중기 추세, 지지/저항
 * - 1시간: 진입 타이밍 (컨플루언스 대기)
 * - 15분: 정밀 진입 (캔들 패턴)
 *
 * 정렬 보너스:
 * - 3개 정렬: +15% 신뢰도
 * - 2개 정렬: +5%
 * - 역방향: 진입 불가
 */
@Component
class MultiTimeFrameAnalyzer(
    private val bithumbPublicApi: BithumbPublicApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val EMA_PERIOD = 200
        const val MIN_CANDLES = 50
    }

    /**
     * 멀티 타임프레임 분석 수행
     */
    fun analyze(market: String): MultiTimeFrameAnalysis? {
        log.info("[$market] 멀티 타임프레임 분석 시작")

        try {
            // 각 타임프레임별 캔들 조회
            val daily = analyzeTimeFrame(market, "day", "일봉")
            val fourHour = analyzeTimeFrame(market, "minute240", "4시간봉")
            val oneHour = analyzeTimeFrame(market, "minute60", "1시간봉")
            val fifteenMinute = analyzeTimeFrame(market, "minute15", "15분봉")

            // 정렬 분석
            val alignment = analyzeAlignment(listOf(daily, fourHour, oneHour, fifteenMinute))

            // 진입 권장사항
            val recommendation = generateRecommendation(alignment)

            log.info("""
                [$market] MTF 분석:
                일봉: ${daily.trend} (${daily.ema200Direction})
                4시간: ${fourHour.trend} (${fourHour.ema200Direction})
                1시간: ${oneHour.trend} (${oneHour.ema200Direction})
                15분: ${fifteenMinute.trend} (${fifteenMinute.ema200Direction})
                정렬: ${alignment.description}
                보너스: +${alignment.bonus}점
            """.trimIndent())

            return MultiTimeFrameAnalysis(
                market = market,
                daily = daily,
                fourHour = fourHour,
                oneHour = oneHour,
                fifteenMinute = fifteenMinute,
                alignmentScore = alignment.alignedCount * 25,
                alignmentBonus = alignment.bonus,
                recommendation = recommendation
            )

        } catch (e: Exception) {
            log.error("[$market] MTF 분석 실패: ${e.message}", e)
            return null
        }
    }

    /**
     * 단일 타임프레임 분석
     */
    private fun analyzeTimeFrame(
        market: String,
        interval: String,
        name: String
    ): TimeFrameAnalysis {
        val candles = fetchCandles(market, interval, 200)

        if (candles.size < MIN_CANDLES) {
            return TimeFrameAnalysis(
                timeframe = name,
                ema200Direction = "FLAT",
                trend = "SIDEWAYS",
                strength = 0,
                supportLevel = null,
                resistanceLevel = null,
                isBullish = false
            )
        }

        val closes = candles.map { it.close.toDouble() }
        val highs = candles.map { it.high.toDouble() }
        val lows = candles.map { it.low.toDouble() }

        // 200 EMA 계산
        val ema200 = calculateEma(closes, EMA_PERIOD)
        val currentPrice = closes.last()

        // EMA 방향 판단
        val emaDirection = when {
            ema200.isEmpty() -> "FLAT"
            currentPrice > ema200.last() * 1.02 -> "UP"
            currentPrice < ema200.last() * 0.98 -> "DOWN"
            else -> "FLAT"
        }

        // 추세 판단 (EMA 기울기)
        val trend = when {
            ema200.size >= 10 -> {
                val recentEma = ema200.takeLast(10).average()
                val olderEma = ema200.dropLast(10).takeLast(10).average()
                val slope = (recentEma - olderEma) / olderEma * 100

                when {
                    slope > 0.5 -> "UPTREND"
                    slope < -0.5 -> "DOWNTREND"
                    else -> "SIDEWAYS"
                }
            }
            else -> "SIDEWAYS"
        }

        // 추세 강도 (EMA로부터의 거리)
        val strength = if (ema200.isNotEmpty()) {
            val distance = kotlin.math.abs(currentPrice - ema200.last()) / ema200.last() * 100
            (distance * 10).toInt().coerceIn(0, 100)
        } else {
            0
        }

        // 지지/저항선 (최근 50봉 기준)
        val recentHighs = highs.takeLast(50)
        val recentLows = lows.takeLast(50)
        val resistanceLevel = recentHighs.maxOrNull()
        val supportLevel = recentLows.minOrNull()

        // 매수 우위 여부
        val isBullish = emaDirection == "UP" && trend == "UPTREND"

        return TimeFrameAnalysis(
            timeframe = name,
            ema200Direction = emaDirection,
            trend = trend,
            strength = strength,
            supportLevel = supportLevel,
            resistanceLevel = resistanceLevel,
            isBullish = isBullish
        )
    }

    /**
     * 타임프레임 정렬 분석
     */
    fun analyzeAlignment(analyses: List<TimeFrameAnalysis>): TimeFrameAlignment {
        val bullishCount = analyses.count { it.isBullish }
        val bearishCount = analyses.count { !it.isBullish && it.trend != "SIDEWAYS" }
        val totalFrames = analyses.size

        val alignment = when {
            bullishCount >= 3 -> "강한 상승 정렬"
            bullishCount >= 2 -> "상승 정렬"
            bearishCount >= 3 -> "강한 하락 정렬"
            bearishCount >= 2 -> "하락 정렬"
            else -> "혼조"
        }

        val bonus = when {
            bullishCount >= 3 -> 15   // 3개 이상 상승 정렬
            bullishCount >= 2 -> 5    // 2개 상승 정렬
            bearishCount >= 2 -> -5   // 하락 정렬 (패널티)
            else -> 0
        }

        val isAligned = bullishCount >= 2

        return TimeFrameAlignment(
            alignedCount = bullishCount,
            totalFrames = totalFrames,
            isAligned = isAligned,
            bonus = bonus,
            description = "$alignment (${bullishCount}/${totalFrames} 상승)"
        )
    }

    /**
     * 진입 권장사항 생성
     */
    private fun generateRecommendation(alignment: TimeFrameAlignment): String {
        return when {
            alignment.bonus >= 15 -> "강력한 매수 신호: 3개 이상 타임프레임이 상승 정렬"
            alignment.bonus >= 5 -> "매수 고려: 2개 타임프레임이 상승 정렬"
            alignment.bonus <= -5 -> "매도 고려: 다중 타임프레임이 하락 정렬"
            alignment.alignedCount == 0 -> "대기: 타임프레임이 정렬되지 않음"
            else -> "관찰: 혼조 상태, 추가 확인 필요"
        }
    }

    /**
     * EMA 계산
     */
    private fun calculateEma(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val emaList = mutableListOf<Double>()

        var ema = data.take(period).average()
        emaList.add(ema)

        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
            emaList.add(ema)
        }

        return emaList
    }

    /**
     * 캔들 데이터 조회
     */
    private fun fetchCandles(market: String, interval: String, count: Int): List<Candle> {
        val response = bithumbPublicApi.getOhlcv(market, interval, count) ?: return emptyList()

        return response.map { candle ->
            Candle(
                timestamp = Instant.ofEpochMilli(candle.timestamp),
                open = candle.openingPrice,
                high = candle.highPrice,
                low = candle.lowPrice,
                close = candle.tradePrice,
                volume = candle.candleAccTradeVolume
            )
        }.reversed()
    }
}
