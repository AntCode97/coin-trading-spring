package com.ant.cointrading.api.exchangerate

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * USD/KRW 환율 조회 API
 *
 * 무료 API 사용: open.er-api.com (API 키 불필요)
 * 일일 호출 제한이 있으므로 캐싱 적용 (1시간)
 */
@Component
class ExchangeRateApi {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl("https://open.er-api.com")
        .build()

    // 캐시
    @Volatile
    private var cachedRate: BigDecimal? = null

    @Volatile
    private var lastFetchTime: Instant? = null

    // 캐시 유효 시간 (1시간)
    private val cacheDuration = Duration.ofHours(1)

    // 기본 환율 (API 실패 시 사용)
    private val defaultRate = BigDecimal("1450")

    /**
     * USD/KRW 환율 조회
     *
     * 1시간 캐싱 적용. 캐시가 유효하면 API 호출 없이 캐시 반환.
     * API 실패 시 기본 환율(1450원) 반환.
     *
     * @return USD/KRW 환율
     */
    fun getUsdKrwRate(): BigDecimal {
        // 캐시 유효성 확인
        val now = Instant.now()
        val cached = cachedRate
        val lastFetch = lastFetchTime

        if (cached != null && lastFetch != null) {
            val elapsed = Duration.between(lastFetch, now)
            if (elapsed < cacheDuration) {
                log.debug("캐시된 환율 사용: $cached (만료까지: ${cacheDuration.minus(elapsed).toMinutes()}분)")
                return cached
            }
        }

        // API 호출
        return try {
            val response = webClient.get()
                .uri("/v6/latest/USD")
                .retrieve()
                .bodyToMono(ExchangeRateResponse::class.java)
                .block()

            val rate = response?.rates?.get("KRW")
            if (rate != null) {
                cachedRate = rate
                lastFetchTime = now
                log.info("환율 조회 성공: 1 USD = $rate KRW")
                rate
            } else {
                log.warn("환율 응답에 KRW 없음. 기본 환율 사용: $defaultRate")
                defaultRate
            }
        } catch (e: Exception) {
            log.error("환율 조회 실패: ${e.message}. 기본 환율 사용: $defaultRate")
            cachedRate ?: defaultRate
        }
    }

    /**
     * 캐시 강제 무효화
     */
    fun invalidateCache() {
        cachedRate = null
        lastFetchTime = null
        log.info("환율 캐시 무효화")
    }

    /**
     * 캐시 상태 조회
     */
    fun getCacheStatus(): ExchangeRateCacheStatus {
        val cached = cachedRate
        val lastFetch = lastFetchTime
        val now = Instant.now()

        return if (cached != null && lastFetch != null) {
            val elapsed = Duration.between(lastFetch, now)
            val remainingMinutes = cacheDuration.minus(elapsed).toMinutes().coerceAtLeast(0)
            ExchangeRateCacheStatus(
                rate = cached,
                lastFetchTime = lastFetch,
                cacheValid = elapsed < cacheDuration,
                remainingMinutes = remainingMinutes
            )
        } else {
            ExchangeRateCacheStatus(
                rate = null,
                lastFetchTime = null,
                cacheValid = false,
                remainingMinutes = 0
            )
        }
    }
}

/**
 * open.er-api.com API 응답 모델
 */
data class ExchangeRateResponse(
    val result: String,
    val base_code: String,
    val rates: Map<String, BigDecimal>
)

/**
 * 캐시 상태 정보
 */
data class ExchangeRateCacheStatus(
    val rate: BigDecimal?,
    val lastFetchTime: Instant?,
    val cacheValid: Boolean,
    val remainingMinutes: Long
)
