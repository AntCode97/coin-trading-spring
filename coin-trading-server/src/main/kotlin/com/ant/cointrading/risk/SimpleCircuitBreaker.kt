package com.ant.cointrading.risk

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 간단한 서킷브레이커 (공통 컴포넌트)
 *
 * VolumeSurgeEngine, MemeScalperEngine의 중복 제거용.
 * 연속 손실/일일 손실 감시하여 거래 중지.
 */
class SimpleCircuitBreaker(
    private val maxConsecutiveLosses: Int,
    private val dailyMaxLossKrw: Double,
    private val statePersistence: SimpleCircuitBreakerStatePersistence
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var consecutiveLosses = 0
    @Volatile private var dailyPnl = 0.0
    @Volatile private var lastResetDate = Instant.now()

    /**
     * 상태 복원 (서버 재시작 시)
     */
    fun restoreState() {
        val saved = statePersistence.load()
        if (saved != null) {
            consecutiveLosses = saved.consecutiveLosses
            dailyPnl = saved.dailyPnl
            lastResetDate = saved.lastResetDate
            log.info("서킷브레이커 상태 복원: 연속손실=$consecutiveLosses, 일일손익=$dailyPnl")
        }
    }

    /**
     * 거래 가능 여부 체크
     */
    fun canTrade(): Boolean {
        if (consecutiveLosses >= maxConsecutiveLosses) {
            log.warn("연속 손실 $consecutiveLosses 회 - 거래 중지")
            return false
        }
        if (dailyPnl <= -dailyMaxLossKrw) {
            log.warn("일일 손실 $dailyPnl 원 - 거래 중지")
            return false
        }
        return true
    }

    /**
     * 손익 기록
     */
    fun recordPnl(pnl: Double) {
        checkDailyReset()

        dailyPnl += pnl
        if (pnl < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }

        saveState()
    }

    /**
     * 일일 리셋 체크
     */
    private fun checkDailyReset() {
        val now = Instant.now()
        if (ChronoUnit.DAYS.between(lastResetDate, now) >= 1) {
            consecutiveLosses = 0
            dailyPnl = 0.0
            lastResetDate = now
            log.info("서킷브레이커 일일 리셋")
            saveState()
        }
    }

    /**
     * 상태 저장
     */
    private fun saveState() {
        statePersistence.save(SimpleCircuitBreakerState(consecutiveLosses, dailyPnl, lastResetDate))
    }

    /**
     * 리셋 (테스트용)
     */
    fun reset() {
        consecutiveLosses = 0
        dailyPnl = 0.0
        lastResetDate = Instant.now()
        saveState()
        log.info("서킷브레이커 리셋")
    }

    /**
     * 상태 조회
     */
    fun getState(): SimpleCircuitBreakerState {
        return SimpleCircuitBreakerState(consecutiveLosses, dailyPnl, lastResetDate)
    }
}

/**
 * 서킷브레이커 상태
 */
data class SimpleCircuitBreakerState(
    val consecutiveLosses: Int = 0,
    val dailyPnl: Double = 0.0,
    val lastResetDate: Instant = Instant.now()
)

/**
 * 서킷브레이커 상태 저장소 인터페이스
 */
interface SimpleCircuitBreakerStatePersistence {
    fun load(): SimpleCircuitBreakerState?
    fun save(state: SimpleCircuitBreakerState)
}

/**
 * 서킷브레이커 팩토리
 */
@Component
class SimpleCircuitBreakerFactory {
    fun create(
        maxConsecutiveLosses: Int,
        dailyMaxLossKrw: Double,
        statePersistence: SimpleCircuitBreakerStatePersistence
    ): SimpleCircuitBreaker {
        return SimpleCircuitBreaker(maxConsecutiveLosses, dailyMaxLossKrw, statePersistence)
    }
}
