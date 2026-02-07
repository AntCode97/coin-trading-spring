package com.ant.cointrading.position

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 쿨다운 추적 클래스
 *
 * VolumeSurgeEngine과 MemeScalperEngine의 공통 쿨다운 추적 로직 추출
 */
class CooldownTracker {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val cooldowns = ConcurrentHashMap<String, Instant>()

    fun setCooldown(key: String, expireAt: Instant) {
        cooldowns[key] = expireAt
        log.debug("[$key] 쿨다운 설정: ${expireAt}")
    }

    fun isCooldown(key: String): Boolean {
        val cooldownEnd = cooldowns[key] ?: return false
        if (Instant.now().isBefore(cooldownEnd)) {
            return true
        }

        removeCooldown(key)
        return false
    }

    fun removeCooldown(key: String) {
        cooldowns.remove(key)
    }

    fun clearAll() {
        cooldowns.clear()
    }

    fun getCooldown(key: String): Instant? = cooldowns[key]

    fun getAllCooldowns(): Map<String, Instant> = cooldowns.toMap()
}
