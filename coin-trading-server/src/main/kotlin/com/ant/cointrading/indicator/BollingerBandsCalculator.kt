package com.ant.cointrading.indicator

import com.ant.cointrading.model.Candle

/**
 * 볼린저밴드 계산 유틸리티 (켄트백 스타일)
 *
 * 중복 제거를 위한 공통 함수들
 */

/**
 * 볼린저밴드 계산
 * @return Triple(상단밴드, 하단밴드, 중간밴드)
 */
fun calculateBollingerBands(
    candles: List<Candle>,
    period: Int,
    stdDevMultiplier: Double
): Triple<Double, Double, Double> {
    val closes = candles.map { it.close.toDouble() }
    val effectivePeriod = minOf(period, closes.size)

    val recentCloses = closes.takeLast(effectivePeriod)
    val sma = recentCloses.average()
    val variance = recentCloses.map { (it - sma).let { diff -> diff * diff } }.average()
    val stdDev = kotlin.math.sqrt(variance)

    val upperBand = sma + (stdDevMultiplier * stdDev)
    val lowerBand = sma - (stdDevMultiplier * stdDev)

    return Triple(upperBand, lowerBand, sma)
}

/**
 * 거래량 비율 계산 (20일 평균 대비)
 */
fun calculateVolumeRatio(candles: List<Candle>, period: Int = 21): Double {
    if (candles.size < period) return 1.0

    val currentVolume = candles.last().volume.toDouble()
    val avgVolume = candles.takeLast(period).dropLast(1).map { it.volume.toDouble() }.average()

    return if (avgVolume > 0) {
        currentVolume / avgVolume
    } else 1.0
}
