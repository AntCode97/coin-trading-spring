package com.ant.cointrading.api.brave

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Brave Search API
 *
 * 웹 검색을 통해 암호화폐 관련 커뮤니티 반응, 뉴스를 수집한다.
 *
 * Rate Limit:
 * - Free API: 1초당 1회, 월 2000회
 * - Base API: 유료 (폴백용)
 *
 * Free API 쿼터 소진 또는 Rate Limit 초과 시 Base API로 폴백한다.
 */
@Component
class BraveSearchApi(
    private val braveSearchRestClient: RestClient,
    @Value("\${BRAVE_SEARCH_FREE_API_KEY:}") private val freeApiKey: String,
    @Value("\${BRAVE_SEARCH_BASE_API_KEY:}") private val baseApiKey: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Free API Rate Limit 관리 (1초당 1회)
    private val lastFreeApiCall = AtomicLong(0)
    private val freeApiCallCount = AtomicInteger(0)

    // 월간 쿼터 (2000회)
    private val monthlyQuota = 2000
    private val quotaResetMonth = AtomicInteger(Instant.now().atZone(java.time.ZoneId.systemDefault()).monthValue)

    companion object {
        const val FREE_API_RATE_LIMIT_MS = 1100L  // 1.1초 (여유 있게)
    }

    /**
     * 웹 검색 실행
     *
     * @param query 검색 쿼리
     * @param count 결과 수 (기본 10)
     * @return 검색 결과 목록
     */
    fun search(query: String, count: Int = 10): List<BraveSearchResult> {
        if (freeApiKey.isBlank() && baseApiKey.isBlank()) {
            log.warn("Brave Search API 키가 설정되지 않음")
            return emptyList()
        }

        // 월간 쿼터 리셋 체크
        checkQuotaReset()

        // Free API 우선 시도
        if (canUseFreeApi()) {
            val results = searchWithKey(query, count, freeApiKey, isFreeApi = true)
            if (results != null) {
                return results
            }
            // Free API 실패 시 Base API로 폴백
            log.info("Free API 실패, Base API로 폴백")
        }

        // Base API 사용
        if (baseApiKey.isNotBlank()) {
            return searchWithKey(query, count, baseApiKey, isFreeApi = false) ?: emptyList()
        }

        return emptyList()
    }

    /**
     * 특정 API 키로 검색 실행
     */
    private fun searchWithKey(
        query: String,
        count: Int,
        apiKey: String,
        isFreeApi: Boolean
    ): List<BraveSearchResult>? {
        // Free API Rate Limit 대기
        if (isFreeApi) {
            waitForFreeApiRateLimit()
        }

        return try {
            val response = braveSearchRestClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/res/v1/web/search")
                        .queryParam("q", query)
                        .queryParam("count", count)
                        .queryParam("search_lang", "ko")
                        .queryParam("safesearch", "moderate")
                        .build()
                }
                .header("X-Subscription-Token", apiKey)
                .retrieve()
                .body(BraveSearchResponse::class.java)

            if (isFreeApi) {
                lastFreeApiCall.set(System.currentTimeMillis())
                freeApiCallCount.incrementAndGet()
                log.debug("Free API 호출 성공 (사용량: ${freeApiCallCount.get()}/$monthlyQuota)")
            }

            response?.web?.results?.map { result ->
                BraveSearchResult(
                    title = result.title ?: "",
                    url = result.url ?: "",
                    description = result.description ?: "",
                    publishedTime = result.pageAge
                )
            } ?: emptyList()

        } catch (e: HttpClientErrorException) {
            when (e.statusCode) {
                HttpStatus.TOO_MANY_REQUESTS -> {
                    log.warn("Brave Search Rate Limit 초과 (${if (isFreeApi) "Free" else "Base"} API)")
                    null  // 폴백 유도
                }
                HttpStatus.UNAUTHORIZED -> {
                    log.error("Brave Search API 키 인증 실패")
                    null
                }
                else -> {
                    log.error("Brave Search 클라이언트 오류: ${e.statusCode} - ${e.message}")
                    null
                }
            }
        } catch (e: HttpServerErrorException) {
            log.error("Brave Search 서버 오류: ${e.statusCode} - ${e.message}")
            null
        } catch (e: Exception) {
            log.error("Brave Search 실패: ${e.message}", e)
            null
        }
    }

    /**
     * Free API 사용 가능 여부 확인
     */
    private fun canUseFreeApi(): Boolean {
        if (freeApiKey.isBlank()) return false
        if (freeApiCallCount.get() >= monthlyQuota) {
            log.warn("Free API 월간 쿼터 소진 (${freeApiCallCount.get()}/$monthlyQuota)")
            return false
        }
        return true
    }

    /**
     * Free API Rate Limit 대기
     */
    private fun waitForFreeApiRateLimit() {
        val elapsed = System.currentTimeMillis() - lastFreeApiCall.get()
        if (elapsed < FREE_API_RATE_LIMIT_MS) {
            val waitTime = FREE_API_RATE_LIMIT_MS - elapsed
            log.debug("Free API Rate Limit 대기: ${waitTime}ms")
            Thread.sleep(waitTime)
        }
    }

    /**
     * 월간 쿼터 리셋 체크
     */
    private fun checkQuotaReset() {
        val currentMonth = Instant.now().atZone(java.time.ZoneId.systemDefault()).monthValue
        if (currentMonth != quotaResetMonth.get()) {
            log.info("월간 쿼터 리셋 (이전: ${freeApiCallCount.get()} 호출)")
            freeApiCallCount.set(0)
            quotaResetMonth.set(currentMonth)
        }
    }

    /**
     * 암호화폐 커뮤니티 검색
     *
     * 특정 코인에 대한 커뮤니티 반응을 검색한다.
     * 검색어에 Reddit, Twitter, 디시인사이드, 코인판 등을 포함한다.
     */
    fun searchCryptoCommunity(coinSymbol: String, coinName: String? = null): List<BraveSearchResult> {
        val searchQuery = buildString {
            append("$coinSymbol 코인")
            if (coinName != null) {
                append(" $coinName")
            }
            append(" (site:reddit.com OR site:twitter.com OR site:dcinside.com OR site:coinpan.com OR site:fmkorea.com)")
        }
        return search(searchQuery, 10)
    }

    /**
     * 암호화폐 뉴스 검색
     */
    fun searchCryptoNews(coinSymbol: String): List<BraveSearchResult> {
        val searchQuery = "$coinSymbol cryptocurrency news 2026"
        return search(searchQuery, 10)
    }

    /**
     * API 상태 정보
     */
    fun getApiStatus(): BraveApiStatus {
        return BraveApiStatus(
            freeApiEnabled = freeApiKey.isNotBlank(),
            baseApiEnabled = baseApiKey.isNotBlank(),
            freeApiCallCount = freeApiCallCount.get(),
            freeApiQuota = monthlyQuota,
            freeApiQuotaRemaining = monthlyQuota - freeApiCallCount.get()
        )
    }
}

/**
 * Brave Search API 응답
 */
data class BraveSearchResponse(
    @JsonProperty("web") val web: BraveWebResults?
)

data class BraveWebResults(
    @JsonProperty("results") val results: List<BraveWebResult>?
)

data class BraveWebResult(
    @JsonProperty("title") val title: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("page_age") val pageAge: String?,
    @JsonProperty("language") val language: String?
)

/**
 * 검색 결과
 */
data class BraveSearchResult(
    val title: String,
    val url: String,
    val description: String,
    val publishedTime: String? = null
) {
    /**
     * 결과 요약
     */
    fun getSummary(): String {
        return description.take(150)
    }

    /**
     * 출처 도메인
     */
    fun getDomain(): String {
        return try {
            java.net.URI(url).host ?: "알 수 없음"
        } catch (e: Exception) {
            "알 수 없음"
        }
    }
}

/**
 * API 상태 정보
 */
data class BraveApiStatus(
    val freeApiEnabled: Boolean,
    val baseApiEnabled: Boolean,
    val freeApiCallCount: Int,
    val freeApiQuota: Int,
    val freeApiQuotaRemaining: Int
)
