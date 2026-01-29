package com.ant.cointrading.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 날짜/시간 유틸리티
 *
 * 한국 시간대(Asia/Seoul) 기반 날짜 범위 계산 공통화.
 */
object DateTimeUtils {

    /**
     * 한국 시간대
     */
    @JvmField
    val SEOUL_ZONE = ZoneId.of("Asia/Seoul")

    /**
     * 오늘 날짜 (한국 시간대)
     */
    fun today(): LocalDate = LocalDate.now(SEOUL_ZONE)

    /**
     * 오늘 날짜 문자열 (한국 시간대)
     */
    fun todayString(): String = today().toString()

    /**
     * 오늘 시작/종료 Instant 범위 (한국 시간대)
     * @return Pair<startOfDay, endOfDay>
     */
    fun todayRange(): Pair<Instant, Instant> {
        val today = today()
        val startOfDay = today.atStartOfDay(SEOUL_ZONE).toInstant()
        val endOfDay = today.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant()
        return startOfDay to endOfDay
    }

    /**
     * 지정된 날짜의 시작/종료 Instant 범위
     * @return Pair<startOfDay, endOfDay>
     */
    fun dayRange(date: LocalDate): Pair<Instant, Instant> {
        val startOfDay = date.atStartOfDay(SEOUL_ZONE).toInstant()
        val endOfDay = date.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant()
        return startOfDay to endOfDay
    }
}
