package com.ant.cointrading.engine

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 리스크 경고 추적 클래스 (TradingEngine 리팩토링)
 */
class RiskAlertTracker {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val lastRiskAlertTime = ConcurrentHashMap<String, Instant>()

    companion object {
        const val ALERT_COOLDOWN_MINUTES = 10L
    }

    fun recordAlert(market: String) {
        lastRiskAlertTime[market] = Instant.now()
        log.warn("[$market] 리스크 경고 기록됨")
    }

    fun isInCooldown(market: String): Boolean {
        val lastAlert = lastRiskAlertTime[market] ?: return false
        val minutesSinceAlert = java.time.Duration.between(lastAlert, Instant.now()).toMinutes()
        return minutesSinceAlert < ALERT_COOLDOWN_MINUTES
    }

    fun getAllAlerts(): Map<String, Instant> = lastRiskAlertTime.toMap()

    fun getLastAlert(market: String): Instant? = lastRiskAlertTime[market]

    fun clearCooldown(market: String) {
        lastRiskAlertTime.remove(market)
    }

    fun clearAll() {
        lastRiskAlertTime.clear()
    }
}
