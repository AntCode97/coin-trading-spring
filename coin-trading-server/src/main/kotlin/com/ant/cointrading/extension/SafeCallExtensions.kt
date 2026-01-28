package com.ant.cointrading.extension

import java.math.BigDecimal

/**
 * 안전한 호출 헬퍼 함수 (켄트백 스타일)
 *
 * null 체크와 기본값 처리를 간결하게 수행한다.
 */

/**
 * BigDecimal이 null이거나 0 이하인 경우 기본값 반환
 */
fun BigDecimal?.orZero(): BigDecimal = this ?: BigDecimal.ZERO

/**
 * BigDecimal이 null이거나 0 이하인 경우 지정한 기본값 반환
 */
fun BigDecimal?.orDefault(default: BigDecimal): BigDecimal =
    if (this == null || this <= BigDecimal.ZERO) default else this

/**
 * BigDecimal이 양수인지 확인 (null은 false)
 */
fun BigDecimal?.isPositive(): Boolean = this != null && this > BigDecimal.ZERO

/**
 * BigDecimal이 음수인지 확인 (null은 false)
 */
fun BigDecimal?.isNegative(): Boolean = this != null && this < BigDecimal.ZERO

/**
 * Double이 null이거나 0 이하인 경우 기본값 반환
 */
fun Double?.orZero(): Double = this ?: 0.0

/**
 * Double이 null인 경우 지정한 기본값 반환
 */
fun Double?.orDefault(default: Double): Double = this ?: default

/**
 * Double이 양수인지 확인 (null은 false)
 */
fun Double?.isPositive(): Boolean = this != null && this > 0.0

/**
 * Int가 null인 경우 지정한 기본값 반환
 */
fun Int?.orDefault(default: Int): Int = this ?: default

/**
 * Int가 양수인지 확인 (null은 false)
 */
fun Int?.isPositive(): Boolean = this != null && this > 0

/**
 * Long이 null인 경우 지정한 기본값 반환
 */
fun Long?.orDefault(default: Long): Long = this ?: default

/**
 * String이 비어있거나 null인 경우 지정한 기본값 반환
 */
fun String?.orDefault(default: String): String = if (this.isNullOrBlank()) default else this

/**
 * List가 비어있거나 null인 경우 빈 리스트 반환
 */
fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
