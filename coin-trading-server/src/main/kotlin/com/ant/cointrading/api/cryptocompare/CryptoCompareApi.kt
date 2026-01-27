package com.ant.cointrading.api.cryptocompare

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

/**
 * CryptoCompare News API
 *
 * 암호화폐 뉴스 검색용 무료 API.
 * https://min-api.cryptocompare.com/documentation
 *
 * Rate Limit: 시간당 100,000 호출 (API 키 없이)
 */
@Component
class CryptoCompareApi(
    private val cryptoCompareRestClient: RestClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 특정 코인 관련 뉴스 검색
     *
     * @param symbol 코인 심볼 (예: BTC, ETH)
     * @param limit 가져올 뉴스 수 (기본 10개)
     * @return 뉴스 목록
     */
    fun getNews(symbol: String, limit: Int = 10): List<CryptoNews> {
        return try {
            val response = cryptoCompareRestClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/data/v2/news/")
                        .queryParam("categories", symbol.uppercase())
                        .queryParam("sortOrder", "latest")
                        .queryParam("extraParams", "coin-trading-spring")
                        .build()
                }
                .retrieve()
                .body(CryptoNewsResponse::class.java)

            response?.data?.take(limit) ?: emptyList()

        } catch (e: Exception) {
            log.error("CryptoCompare 뉴스 조회 실패 [$symbol]: ${e.message}")
            emptyList()
        }
    }

    /**
     * 최신 전체 뉴스 검색
     */
    fun getLatestNews(limit: Int = 20): List<CryptoNews> {
        return try {
            val response = cryptoCompareRestClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/data/v2/news/")
                        .queryParam("lang", "EN")
                        .queryParam("sortOrder", "latest")
                        .queryParam("extraParams", "coin-trading-spring")
                        .build()
                }
                .retrieve()
                .body(CryptoNewsResponse::class.java)

            response?.data?.take(limit) ?: emptyList()

        } catch (e: Exception) {
            log.error("CryptoCompare 최신 뉴스 조회 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * 특정 시간 이내의 뉴스만 필터링
     *
     * @param symbol 코인 심볼
     * @param hoursAgo 몇 시간 이내
     */
    fun getRecentNews(symbol: String, hoursAgo: Int = 24): List<CryptoNews> {
        val cutoffTime = Instant.now().minusSeconds(hoursAgo * 3600L).epochSecond
        return getNews(symbol, 20).filter { it.publishedOn >= cutoffTime }
    }
}

/**
 * CryptoCompare 뉴스 응답
 */
data class CryptoNewsResponse(
    @JsonProperty("Type") val type: Int?,
    @JsonProperty("Message") val message: String?,
    @JsonProperty("Data") val data: List<CryptoNews>?
)

/**
 * 뉴스 아이템
 */
data class CryptoNews(
    @JsonProperty("id") val id: String?,
    @JsonProperty("guid") val guid: String?,
    @JsonProperty("published_on") val publishedOn: Long = 0,
    @JsonProperty("imageurl") val imageUrl: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("body") val body: String?,
    @JsonProperty("tags") val tags: String?,
    @JsonProperty("categories") val categories: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("source_info") val sourceInfo: SourceInfo?
) {
    /**
     * 발행 시간 (한국어)
     */
    fun getPublishedTimeAgo(): String {
        val now = Instant.now().epochSecond
        val diff = now - publishedOn
        return when {
            diff < 3600 -> "${diff / 60}분 전"
            diff < 86400 -> "${diff / 3600}시간 전"
            else -> "${diff / 86400}일 전"
        }
    }

    /**
     * 요약 (200자)
     */
    fun getSummary(): String {
        return body?.take(200)?.plus("...") ?: ""
    }
}

data class SourceInfo(
    @JsonProperty("name") val name: String?,
    @JsonProperty("img") val img: String?,
    @JsonProperty("lang") val lang: String?
)
