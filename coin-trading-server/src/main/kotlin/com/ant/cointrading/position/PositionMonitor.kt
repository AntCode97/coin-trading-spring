package com.ant.cointrading.position

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.config.TradingConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 포지션 모니터링 추상 클래스
 *
 * VolumeSurgeEngine과 MemeScalperEngine의 공통 모니터링 로직 추출
 */
abstract class PositionMonitor<T>(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val slackNotifier: com.ant.cointrading.notification.SlackNotifier
) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    companion object {
        const val ABANDONED_RETRY_CHECK_MINUTES = 10L
        const val MAX_ABANDONED_RETRIES = 3
    }

    abstract fun monitorOpenPosition(position: T)
    abstract fun monitorClosingPosition(position: T)
    abstract fun savePosition(position: T): T
    abstract fun getMarket(position: T): String
    abstract fun getEntryPrice(position: T): Double
    abstract fun getQuantity(position: T): Double
    abstract fun findOpenPositions(): List<T>
    abstract fun findClosingPositions(): List<T>
    abstract fun findAbandonedPositions(): List<T>
    abstract fun setStatus(position: T, status: String)
    abstract fun setExitReason(position: T, reason: String)
    abstract fun setExitTime(position: T, time: Instant)
    abstract fun setCloseAttemptCount(position: T, count: Int)
    abstract fun setCloseOrderId(position: T, orderId: String?)
    abstract fun getCloseAttemptCount(position: T): Int
    abstract fun getAbandonRetryCount(position: T): Int?
    abstract fun setAbandonRetryCount(position: T, count: Int)

    /**
     * 메인 모니터링 메서드
     *
     * OPEN, CLOSING, ABANDONED 포지션을 순차적으로 모니터링
     */
    fun monitorPositions(lastRetryCheckKey: String, cooldowns: MutableMap<String, Instant>) {
        findOpenPositions().forEach { position ->
            try {
                monitorOpenPosition(position)
            } catch (e: Exception) {
                log.error("[${getMarket(position)}] 포지션 모니터링 오류: ${e.message}")
            }
        }

        findClosingPositions().forEach { position ->
            try {
                monitorClosingPosition(position)
            } catch (e: Exception) {
                log.error("[${getMarket(position)}] 청산 모니터링 오류: ${e.message}")
            }
        }

        val now = Instant.now()
        val lastCheck = cooldowns[lastRetryCheckKey]
        if (lastCheck == null || ChronoUnit.MINUTES.between(lastCheck, now) >= ABANDONED_RETRY_CHECK_MINUTES) {
            cooldowns[lastRetryCheckKey] = now
            retryAbandonedPositions()
        }
    }

    private fun retryAbandonedPositions() {
        val abandonedPositions = findAbandonedPositions()
        if (abandonedPositions.isEmpty()) return

        log.info("ABANDONED 포지션 ${abandonedPositions.size}건 재시도 시작")

        abandonedPositions.forEach { position ->
            try {
                retryAbandonedPosition(position)
            } catch (e: Exception) {
                log.error("[${getMarket(position)}] ABANDONED 재시도 오류: ${e.message}")
            }
        }
    }

    private fun retryAbandonedPosition(position: T): Boolean {
        val market = getMarket(position)
        val totalAttempts = getCloseAttemptCount(position) + (getAbandonRetryCount(position) ?: 0)
        val maxTotalAttempts = TradingConstants.MAX_CLOSE_ATTEMPTS + MAX_ABANDONED_RETRIES

        if (totalAttempts >= maxTotalAttempts) {
            markAsFailed(position, market, totalAttempts)
            return false
        }

        if (checkBalanceAndCloseIfEmpty(position, market)) {
            return true
        }

        resetAndRetry(position, market, totalAttempts)
        return true
    }

    private fun markAsFailed(position: T, market: String, totalAttempts: Int) {
        log.warn("[$market] ABANDONED 재시도 ${totalAttempts}회 초과 - FAILED로 변경")
        slackNotifier.sendWarning(
            market,
            """
            ABANDONED 포지션 재시도 실패 (${totalAttempts}회 초과) - 최종 FAILED
            진입가: ${getEntryPrice(position)}원
            수량: ${getQuantity(position)}
            수동으로 빗썸에서 매도 필요 (DB 상태: FAILED)
            """.trimIndent()
        )
        setStatus(position, "FAILED")
        setExitReason(position, "ABANDONED_MAX_RETRIES")
        setExitTime(position, Instant.now())
        savePosition(position)
    }

    private fun checkBalanceAndCloseIfEmpty(position: T, market: String): Boolean {
        val coinSymbol = market.removePrefix("KRW-")
        val actualBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            return false
        }

        if (actualBalance <= BigDecimal.ZERO) {
            log.info("[$market] 잔고 없음 - ABANDONED -> CLOSED 변경")
            setStatus(position, "CLOSED")
            setExitTime(position, Instant.now())
            setExitReason(position, "ABANDONED_NO_BALANCE")
            savePosition(position)
            return true
        }
        return false
    }

    private fun resetAndRetry(position: T, market: String, totalAttempts: Int) {
        log.info("[$market] ABANDONED 포지션 재시도 #${totalAttempts + 1}")
        setCloseAttemptCount(position, 0)
        setAbandonRetryCount(position, (getAbandonRetryCount(position) ?: 0) + 1)
        setStatus(position, "OPEN")
        savePosition(position)
    }
}
