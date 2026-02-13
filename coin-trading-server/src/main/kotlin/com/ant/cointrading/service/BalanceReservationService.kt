package com.ant.cointrading.service

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KRW 잔고 예약 서비스
 *
 * 3개 엔진(DCA, VolumeSurge, MemeScalper)이 동시에 잔고를 조회하고 주문하면
 * TOCTOU 레이스 컨디션으로 잔고 부족 실패가 발생한다.
 *
 * 해결: 잔고 조회 → 예약 합계 차감 → 가용 여부 판단을 원자적으로 수행.
 * 예약은 주문 완료 후 반드시 release() 해야 하며,
 * 5분 타임아웃으로 데드락을 방지한다.
 */
@Service
class BalanceReservationService(
    private val bithumbPrivateApi: BithumbPrivateApi
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private val reservations = ConcurrentHashMap<String, Reservation>()

    companion object {
        private const val RESERVATION_TIMEOUT_MINUTES = 5L
    }

    data class Reservation(
        val strategy: String,
        val market: String,
        val amount: BigDecimal,
        val createdAt: Instant = Instant.now()
    )

    /**
     * KRW 잔고 예약
     *
     * @return true면 예약 성공 (주문 진행 가능), false면 잔고 부족
     */
    fun reserve(strategy: String, market: String, amount: BigDecimal): Boolean = lock.withLock {
        val key = reservationKey(strategy, market)

        // 이미 예약된 경우 중복 방지
        if (reservations.containsKey(key)) {
            log.warn("[$market] 이미 예약 존재 ($strategy) - 중복 예약 거부")
            return false
        }

        val krwBalance = try {
            bithumbPrivateApi.getBalances()
                ?.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            return false
        }

        val totalReserved = reservations.values.sumOf { it.amount }
        val available = krwBalance - totalReserved
        val required = amount.multiply(BigDecimal("1.1")) // 10% 여유

        if (available < required) {
            log.warn("[$market] KRW 잔고 부족 ($strategy): 가용=${available}원, 필요=${required}원 (잔고=${krwBalance}, 예약=${totalReserved})")
            return false
        }

        reservations[key] = Reservation(strategy, market, amount)
        log.info("[$market] KRW 예약 완료 ($strategy): ${amount}원 (가용 잔고: ${available - amount}원)")
        return true
    }

    /**
     * 예약 해제 (주문 완료/실패 후 반드시 호출)
     */
    fun release(strategy: String, market: String) {
        val key = reservationKey(strategy, market)
        val removed = reservations.remove(key)
        if (removed != null) {
            log.debug("[$market] KRW 예약 해제 ($strategy): ${removed.amount}원")
        }
    }

    /**
     * 타임아웃된 예약 자동 해제 (데드락 방지)
     */
    @Scheduled(fixedDelay = 60_000)
    fun cleanupExpiredReservations() {
        val now = Instant.now()
        val expired = reservations.entries.filter { (_, reservation) ->
            ChronoUnit.MINUTES.between(reservation.createdAt, now) >= RESERVATION_TIMEOUT_MINUTES
        }
        expired.forEach { (key, reservation) ->
            reservations.remove(key)
            log.warn("[${reservation.market}] 타임아웃 예약 해제 (${reservation.strategy}): ${reservation.amount}원, 생성: ${reservation.createdAt}")
        }
    }

    /**
     * 현재 예약 현황 (디버그용)
     */
    fun getReservations(): Map<String, Reservation> = reservations.toMap()

    private fun reservationKey(strategy: String, market: String) = "${strategy}:${market}"
}
