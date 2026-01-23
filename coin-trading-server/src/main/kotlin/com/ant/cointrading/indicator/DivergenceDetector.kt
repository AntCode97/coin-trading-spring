package com.ant.cointrading.indicator

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 다이버전스 탐지 결과
 */
data class DivergenceResult(
    val hasDivergence: Boolean,
    val type: DivergenceType,     // BULLISH, BEARISH, NONE
    val strength: DivergenceStrength,  // WEAK, MODERATE, STRONG
    val description: String
)

/**
 * 다이버전스 유형
 */
enum class DivergenceType {
    BULLISH,      // 강세 다이버전스 (가격 하락 + 지표 상승)
    BEARISH,      // 약세 다이버전스 (가격 상승 + 지표 하락)
    NONE
}

/**
 * 다이버전스 강도
 */
enum class DivergenceStrength {
    WEAK,         // 3~4봉 다이버전스
    MODERATE,     // 5~6봉 다이버전스
    STRONG        // 7봉 이상 다이버전스
}

/**
 * RSI 다이버전스 탐지기
 *
 * quant-trading 스킬 기반:
 * - RSI 다이버전스 = 반전 신호
 * - 강세 다이버전스: 가격 저점 하락 + RSI 저점 상승 = 매수 신호
 * - 약세 다이버전스: 가격 고점 상승 + RSI 고점 하락 = 매도 신호
 *
 * 다이버전스 강도:
 * - WEAK: 3~4봉 (약한 신호)
 * - MODERATE: 5~6봉 (확신 신호)
 * - STRONG: 7봉 이상 (강한 신호)
 */
@Component
class DivergenceDetector {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MIN_PIVOTS_FOR_DIVERGENCE = 3   // 최소 3개 피벗 필요
        const val MIN_DIVERGENCE_BARS = 3         // 최소 3봉 연속
    }

    /**
     * RSI 다이버전스 탐지
     *
     * @param prices 가격 시계열 (오래된 순)
     * @param rsiValues RSI 시계열 (오래된 순)
     * @return 다이버전스 탐지 결과
     */
    fun detectRsiDivergence(
        prices: List<Double>,
        rsiValues: List<Double>
    ): DivergenceResult {
        if (prices.size < 20 || rsiValues.size < 20) {
            return DivergenceResult(false, DivergenceType.NONE, DivergenceStrength.WEAK, "데이터 부족")
        }

        // 피벗 포인트 추출 (국소적 고점/저점)
        val pricePivots = findPivots(prices, pivotSize = 5)
        val rsiPivots = findPivots(rsiValues, pivotSize = 5)

        if (pricePivots.size < MIN_PIVOTS_FOR_DIVERGENCE || rsiPivots.size < MIN_PIVOTS_FOR_DIVERGENCE) {
            return DivergenceResult(false, DivergenceType.NONE, DivergenceStrength.WEAK, "피벗 부족")
        }

        // 강세 다이버전스 확인 (가격 저점 하락 + RSI 저점 상승)
        val bullishDivergence = checkBullishDivergence(pricePivots, rsiPivots)
        if (bullishDivergence.first) {
            val strength = calculateDivergenceStrength(bullishDivergence.second)
            val desc = "강세 다이버전스: 가격 하락 + RSI 상승 (${strength.name} 신호)"
            log.info(desc)
            return DivergenceResult(true, DivergenceType.BULLISH, strength, desc)
        }

        // 약세 다이버전스 확인 (가격 고점 상승 + RSI 고점 하락)
        val bearishDivergence = checkBearishDivergence(pricePivots, rsiPivots)
        if (bearishDivergence.first) {
            val strength = calculateDivergenceStrength(bearishDivergence.second)
            val desc = "약세 다이버전스: 가격 상승 + RSI 하락 (${strength.name} 신호)"
            log.info(desc)
            return DivergenceResult(true, DivergenceType.BEARISH, strength, desc)
        }

        return DivergenceResult(false, DivergenceType.NONE, DivergenceStrength.WEAK, "다이버전스 없음")
    }

    /**
     * 피벗 포인트 추출 (국소적 고점/저점)
     *
     * @param data 시계열 데이터
     * @param pivotSize 피벗 윈도우 크기 (양쪽 pivotSize만큼 확인)
     * @return 피벗 포인트 리스트 (인덱스, 값, 타입)
     */
    private fun findPivots(data: List<Double>, pivotSize: Int): List<Pivot> {
        val pivots = mutableListOf<Pivot>()

        for (i in pivotSize until (data.size - pivotSize)) {
            val current = data[i]

            // 주변 값들
            val left = data.subList(i - pivotSize, i)
            val right = data.subList(i + 1, i + pivotSize + 1)

            // 고점 확인 (주변보다 높음)
            val isHigh = left.all { it < current } && right.all { it < current }

            // 저점 확인 (주변보다 낮음)
            val isLow = left.all { it > current } && right.all { it > current }

            when {
                isHigh -> pivots.add(Pivot(i, current, PivotType.HIGH))
                isLow -> pivots.add(Pivot(i, current, PivotType.LOW))
            }
        }

        return pivots
    }

    /**
     * 강세 다이버전스 확인
     *
     * 조건: 가격 저점이 하락하는데 RSI 저점이 상승
     */
    private fun checkBullishDivergence(
        pricePivots: List<Pivot>,
        rsiPivots: List<Pivot>
    ): Pair<Boolean, Int> {
        // 가격 저점 추출
        val priceLows = pricePivots.filter { it.type == PivotType.LOW }.takeLast(5)
        val rsiLows = rsiPivots.filter { it.type == PivotType.LOW }.takeLast(5)

        if (priceLows.size < 2 || rsiLows.size < 2) {
            return false to 0
        }

        // 최근 저점 비교
        val recentPriceLow = priceLows.last()
        val prevPriceLow = priceLows[priceLows.size - 2]
        val recentRsiLow = rsiLows.last()
        val prevRsiLow = rsiLows[rsiLows.size - 2]

        // 가격은 더 낮아지는데 RSI는 더 높아지면 강세 다이버전스
        val isPriceLower = recentPriceLow.value < prevPriceLow.value
        val isRsiHigher = recentRsiLow.value > prevRsiLow.value

        if (isPriceLower && isRsiHigher) {
            // 연속된 다이버전스 봉 수 계산
            val divergenceBars = countDivergenceBars(priceLows, rsiLows, isBullish = true)
            return true to divergenceBars
        }

        return false to 0
    }

    /**
     * 약세 다이버전스 확인
     *
     * 조건: 가격 고점이 상승하는데 RSI 고점이 하락
     */
    private fun checkBearishDivergence(
        pricePivots: List<Pivot>,
        rsiPivots: List<Pivot>
    ): Pair<Boolean, Int> {
        // 가격 고점 추출
        val priceHighs = pricePivots.filter { it.type == PivotType.HIGH }.takeLast(5)
        val rsiHighs = rsiPivots.filter { it.type == PivotType.HIGH }.takeLast(5)

        if (priceHighs.size < 2 || rsiHighs.size < 2) {
            return false to 0
        }

        // 최근 고점 비교
        val recentPriceHigh = priceHighs.last()
        val prevPriceHigh = priceHighs[priceHighs.size - 2]
        val recentRsiHigh = rsiHighs.last()
        val prevRsiHigh = rsiHighs[rsiHighs.size - 2]

        // 가격은 더 높아지는데 RSI는 더 낮아지면 약세 다이버전스
        val isPriceHigher = recentPriceHigh.value > prevPriceHigh.value
        val isRsiLower = recentRsiHigh.value < prevRsiHigh.value

        if (isPriceHigher && isRsiLower) {
            // 연속된 다이버전스 봉 수 계산
            val divergenceBars = countDivergenceBars(priceHighs, rsiHighs, isBullish = false)
            return true to divergenceBars
        }

        return false to 0
    }

    /**
     * 다이버전스 강도 계산
     */
    private fun calculateDivergenceStrength(bars: Int): DivergenceStrength {
        return when {
            bars >= 7 -> DivergenceStrength.STRONG
            bars >= 5 -> DivergenceStrength.MODERATE
            else -> DivergenceStrength.WEAK
        }
    }

    /**
     * 연속 다이버전스 봉 수 계산
     */
    private fun countDivergenceBars(
        pivots1: List<Pivot>,
        pivots2: List<Pivot>,
        isBullish: Boolean
    ): Int {
        var count = 0

        val minSize = minOf(pivots1.size, pivots2.size)

        for (i in 0 until minSize - 1) {
            val current1 = pivots1[pivots1.size - 1 - i]
            val prev1 = pivots1[pivots1.size - 2 - i]
            val current2 = pivots2[pivots2.size - 1 - i]
            val prev2 = pivots2[pivots2.size - 2 - i]

            val diverging = if (isBullish) {
                current1.value < prev1.value && current2.value > prev2.value
            } else {
                current1.value > prev1.value && current2.value < prev2.value
            }

            if (diverging) {
                count++
            } else {
                break
            }
        }

        return count
    }
}

/**
 * 피벗 포인트
 */
data class Pivot(
    val index: Int,
    val value: Double,
    val type: PivotType
)

/**
 * 피벗 타입
 */
enum class PivotType {
    HIGH,   // 고점
    LOW     // 저점
}
