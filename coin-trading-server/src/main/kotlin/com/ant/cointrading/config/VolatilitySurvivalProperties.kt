package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 급락/급등 장세 전용 전략 설정
 */
@Component
@ConfigurationProperties(prefix = "volatility-survival")
data class VolatilitySurvivalProperties(
    var enabled: Boolean = true,
    var minCandles: Int = 50,
    var rsiPeriod: Int = 14,
    var rsiEntryThreshold: Double = 33.0,
    var rsiExitThreshold: Double = 67.0,
    var emaFastPeriod: Int = 7,
    var emaSlowPeriod: Int = 21,
    var bollingerPeriod: Int = 20,
    var bollingerStdDev: Double = 2.2,
    var volumePeriod: Int = 20,
    var minVolumeRatio: Double = 1.4,
    var minAtrPercent: Double = 1.8,
    var panicLookbackCandles: Int = 8,
    var minPanicDropPercent: Double = 2.0,
    var minReboundPercent: Double = 0.7,
    var lowerBandTolerancePercent: Double = 1.0,
    var stopLossPercent: Double = 2.2,
    var takeProfitPercent: Double = 3.8,
    var trailingActivationPercent: Double = 1.2,
    var trailingOffsetPercent: Double = 1.1,
    var maxHoldingMinutes: Long = 240,
    var stopLossCooldownMinutes: Long = 45
) {
    fun validate(): Boolean {
        return minCandles >= 30 &&
            rsiPeriod in 5..30 &&
            rsiEntryThreshold in 10.0..50.0 &&
            rsiExitThreshold in 50.0..90.0 &&
            emaFastPeriod in 3..20 &&
            emaSlowPeriod in 10..60 &&
            emaFastPeriod < emaSlowPeriod &&
            bollingerPeriod in 10..60 &&
            bollingerStdDev in 1.0..4.0 &&
            minVolumeRatio >= 1.0 &&
            minAtrPercent > 0 &&
            panicLookbackCandles in 3..30 &&
            minPanicDropPercent > 0 &&
            minReboundPercent > 0 &&
            stopLossPercent > 0 &&
            takeProfitPercent > 0 &&
            trailingActivationPercent > 0 &&
            trailingOffsetPercent > 0 &&
            maxHoldingMinutes > 0 &&
            stopLossCooldownMinutes >= 0
    }
}
