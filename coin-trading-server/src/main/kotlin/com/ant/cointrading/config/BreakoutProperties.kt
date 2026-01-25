package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Breakout 전략 설정
 */
@Component
@ConfigurationProperties(prefix = "breakout")
data class BreakoutProperties(
    var enabled: Boolean = false,
    var positionSizeKrw: Double = 10000.0,
    var bollingerPeriod: Int = 50,
    var bollingerStdDev: Double = 2.0,
    var minBreakoutVolumeRatio: Double = 1.5,
    var stopLossPercent: Double = -2.0,
    var takeProfitPercent: Double = 8.0,
    var trailingStopTrigger: Double = 3.0,
    var trailingStopOffset: Double = 1.0,
    var cooldownMinutes: Int = 60
) {
    companion object {
        const val DEFAULT_POSITION_SIZE_KRW = 10000.0
        const val DEFAULT_BB_PERIOD = 50
        const val DEFAULT_BB_STD_DEV = 2.0
        const val DEFAULT_MIN_VOLUME_RATIO = 1.5
        const val DEFAULT_STOP_LOSS = -2.0
        const val DEFAULT_TAKE_PROFIT = 8.0
    }

    /**
     * 설정 유효성 검증
     */
    fun validate(): Boolean {
        return bollingerPeriod in 10..200 &&
                bollingerStdDev in 0.5..5.0 &&
                minBreakoutVolumeRatio >= 1.0 &&
                positionSizeKrw > 0 &&
                stopLossPercent < 0 &&
                takeProfitPercent > 0
    }
}
