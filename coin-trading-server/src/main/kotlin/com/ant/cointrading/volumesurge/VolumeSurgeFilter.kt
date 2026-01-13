package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.brave.BraveSearchApi
import com.ant.cointrading.api.cryptocompare.CryptoCompareApi
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.VolumeSurgeAlertEntity
import com.ant.cointrading.repository.VolumeSurgeAlertRepository
import com.ant.cointrading.service.ModelSelector
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * LLM 필터 결과
 */
data class FilterResult(
    val decision: String,      // APPROVED / REJECTED
    val confidence: Double,    // 0.0 ~ 1.0
    val reason: String,        // 판단 근거
    val newsSummary: String? = null,  // 뉴스 요약
    val riskFactors: List<String> = emptyList()  // 위험 요소
)

/**
 * Volume Surge LLM 필터
 *
 * 거래량 급등 종목을 웹검색을 통해 분석하고
 * 펌프앤덤프 여부를 판단한다.
 */
@Component
class VolumeSurgeFilter(
    private val properties: VolumeSurgeProperties,
    private val modelSelector: ModelSelector,
    private val filterTools: VolumeSurgeFilterTools,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val systemPrompt = """
        당신은 암호화폐 단기 트레이딩 분석가입니다. 거래량이 급등한 종목의 투자 적합성을 판단합니다.

        ## 핵심 원칙: 기술적 지표 우선

        **코인 시장의 특성:**
        - 암호화폐는 뉴스/호재 없이도 수급에 의해 급등할 수 있음
        - 거래량 급등 자체가 매수세 유입 신호
        - 뉴스 없음 ≠ 위험, 뉴스 없음 = 중립
        - 기술적 지표(거래량, 변동률)가 양호하면 진입 기회

        ## 판단 기준 (기술적 지표 중심)

        ### 1. 거래량 기준 (가장 중요)
        - 100억원 이상: 대형 유동성, APPROVED
        - 50억~100억원: 충분한 유동성, APPROVED
        - 10억~50억원: 중간 유동성, 변동률 확인 후 판단
        - 5억~10억원: 소형이지만 거래 가능, 변동률 15% 이하면 APPROVED
        - 5억원 미만: 유동성 부족, REJECTED

        ### 2. 변동률 기준
        - 10% 이하: 안정적, APPROVED 우선
        - 10~20%: 적정 수준, APPROVED 가능
        - 20~30%: 다소 높음, 거래량 50억 이상이면 APPROVED
        - 30% 이상: 이미 급등, 추격 매수 위험 → REJECTED

        ### 3. 통과 조건 (아래 중 하나 충족 시 APPROVED)
        - 거래량 50억원 이상 + 변동률 30% 미만
        - 거래량 10억원 이상 + 변동률 20% 미만
        - 거래량 5억원 이상 + 변동률 15% 미만 + 악재 뉴스 없음

        ### 4. 거부 조건 (아래 중 하나 해당 시 REJECTED)
        - 거래량 5억원 미만 (유동성 부족)
        - 변동률 30% 이상 (이미 급등)
        - 명확한 악재 뉴스 (해킹, 상폐, 사기 등)
        - 펌프앤덤프 명백한 정황 (SNS 조직적 홍보)

        ## 판단 흐름

        1. getCoinTradingVolume 도구로 거래량/변동률 확인 (필수)
        2. 거래량 50억 이상 + 변동률 20% 미만이면 뉴스 검색 없이 APPROVED 가능
        3. 그 외의 경우 searchCryptoNews로 악재 여부만 확인
        4. makeDecision 도구로 최종 판단

        ## 중요: 뉴스에 대한 관점

        - 뉴스는 "악재 확인용"으로만 사용 (호재 확인이 아님)
        - 뉴스가 없다 = 악재도 없다 = 중립 또는 긍정
        - 호재 뉴스가 없어도 기술적 지표가 좋으면 APPROVED
        - 오직 명확한 악재(해킹, 상폐, 러그풀 등)만 REJECTED 사유

        ## makeDecision 호출 시 주의사항

        - 반드시 market 파라미터 전달
        - 뉴스 없음을 이유로 REJECTED 하지 말 것
        - 거래량/변동률 기술적 지표 기준으로 판단
    """.trimIndent()

    /**
     * 종목 필터링
     *
     * 1. LLM 필터 비활성화 시 자동 승인
     * 2. 쿨다운 기간 내 기존 LLM 결과가 있으면 재사용 (DB 캐시)
     * 3. 없으면 LLM 호출
     */
    suspend fun filter(market: String, alert: VolumeSurgeAlertEntity): FilterResult {
        if (!properties.llmFilterEnabled) {
            log.info("[$market] LLM 필터 비활성화 - 자동 승인")
            return FilterResult(
                decision = "APPROVED",
                confidence = 0.5,
                reason = "LLM 필터 비활성화"
            )
        }

        // 쿨다운 기간 내 기존 LLM 결과가 있는지 확인 (DB 캐시)
        val cachedResult = getCachedFilterResult(market)
        if (cachedResult != null) {
            log.info("[$market] LLM 필터 캐시 사용 (${properties.llmCooldownMin}분 이내 결과)")
            return cachedResult
        }

        val coinName = extractCoinName(market)

        val userPrompt = """
            마켓: $market ($coinName)
            경보 유형: 거래량 급등 (TRADING_VOLUME_SUDDEN_FLUCTUATION)
            감지 시각: ${alert.detectedAt}

            이 종목이 단기 투자에 적합한지 웹검색을 통해 분석해주세요.
            반드시 searchCryptoNews 도구로 최근 뉴스를 검색한 후,
            makeDecision 도구로 최종 판단을 내려주세요.
        """.trimIndent()

        return try {
            val chatClient = modelSelector.getChatClient()
                .mutate()
                .defaultTools(filterTools)
                .build()

            log.info("[$market] LLM 필터 분석 시작")

            val response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()

            log.debug("[$market] LLM 응답: $response")

            // 결과 파싱 (market별로 저장된 결과에서 가져옴)
            filterTools.getLastDecision(market) ?: FilterResult(
                decision = "REJECTED",
                confidence = 0.3,
                reason = "LLM 응답 파싱 실패 (makeDecision 도구가 호출되지 않음)"
            )

        } catch (e: Exception) {
            log.error("[$market] LLM 필터 오류: ${e.message}", e)
            FilterResult(
                decision = "REJECTED",
                confidence = 0.0,
                reason = "LLM 필터 오류: ${e.message}"
            )
        }
    }

    /**
     * DB에서 캐시된 LLM 필터 결과 조회
     *
     * 쿨다운 기간 (기본 4시간) 내에 동일 마켓에 대한 LLM 필터 결과가 있으면 재사용
     */
    private fun getCachedFilterResult(market: String): FilterResult? {
        val cooldownMinutes = properties.llmCooldownMin.toLong()
        val cutoffTime = Instant.now().minus(cooldownMinutes, ChronoUnit.MINUTES)

        val cachedAlert = alertRepository
            .findTopByMarketAndLlmFilterResultIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(market, cutoffTime)

        return if (cachedAlert != null && cachedAlert.llmFilterResult != null) {
            val cachedDecision = cachedAlert.llmFilterResult!!
            val cachedConfidence = cachedAlert.llmConfidence ?: 0.5
            val cachedReason = cachedAlert.llmFilterReason ?: "캐시된 결과"

            log.debug("[$market] 캐시 적중: $cachedDecision (${cachedAlert.createdAt})")

            FilterResult(
                decision = cachedDecision,
                confidence = cachedConfidence,
                reason = "[캐시] $cachedReason"
            )
        } else {
            null
        }
    }

    private fun extractCoinName(market: String): String {
        // KRW-BTC -> BTC
        return market.substringAfter("-")
    }
}

/**
 * Volume Surge 필터용 LLM 도구
 *
 * - CryptoCompare API: 뉴스 검색 (무료)
 * - Bithumb API: 24시간 거래량 조회 (무료)
 * - Brave Search API: 웹/커뮤니티 검색 (무료 우선, 유료 폴백)
 */
@Component
class VolumeSurgeFilterTools(
    private val bithumbPublicApi: BithumbPublicApi,
    private val cryptoCompareApi: CryptoCompareApi,
    private val braveSearchApi: BraveSearchApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // market별로 결과 저장 (동시성 문제 해결)
    private val decisionsByMarket = java.util.concurrent.ConcurrentHashMap<String, FilterResult>()

    fun getLastDecision(market: String): FilterResult? = decisionsByMarket.remove(market)

    fun clearDecision(market: String) = decisionsByMarket.remove(market)

    @Tool(description = """
        특정 암호화폐의 최근 뉴스를 검색합니다 (CryptoCompare API).

        뉴스 검색 목적: 악재 확인용 (호재 확인이 아님!)
        - 뉴스 없음 = 악재도 없음 = 중립 또는 긍정
        - 악재(해킹, 상폐, 사기, 러그풀)만 REJECTED 사유
        - 호재가 없어도 기술적 지표가 좋으면 APPROVED
    """)
    fun searchCryptoNews(
        @ToolParam(description = "코인 심볼 (예: BTC, ETH, XRP)") coinSymbol: String,
        @ToolParam(description = "검색할 기간 (시간 단위, 기본 24)") hours: Int = 24
    ): String {
        log.info("[Tool] searchCryptoNews: $coinSymbol, ${hours}시간")

        return try {
            val news = cryptoCompareApi.getRecentNews(coinSymbol.uppercase(), hours)

            if (news.isEmpty()) {
                """
                    검색 결과 (${coinSymbol}, 최근 ${hours}시간):

                    관련 뉴스 없음.

                    결론: 악재 뉴스 없음 = 긍정적 신호
                    기술적 지표(거래량, 변동률)가 APPROVED 조건을 충족하면 진입 가능.
                """.trimIndent()
            } else {
                val newsText = news.take(5).mapIndexed { idx, n ->
                    """
                    ${idx + 1}. ${n.title}
                       출처: ${n.source ?: "알 수 없음"}
                       시간: ${n.getPublishedTimeAgo()}
                       요약: ${n.getSummary()}
                    """.trimIndent()
                }.joinToString("\n\n")

                """
                    검색 결과 (${coinSymbol}, 최근 ${hours}시간):

                    총 ${news.size}건의 뉴스 발견.

                    $newsText

                    분석 포인트 (악재 확인용):
                    - 해킹, 보안 사고 언급?
                    - 상폐, 거래 중단 언급?
                    - 사기, 러그풀, 스캠 의심?
                    - 펌프앤덤프 조직적 홍보?

                    위 악재가 없으면 기술적 지표 기준으로 APPROVED.
                    호재가 없어도 악재가 없으면 문제없음.
                """.trimIndent()
            }

        } catch (e: Exception) {
            log.error("[Tool] searchCryptoNews 오류: ${e.message}", e)
            """
                검색 결과 (${coinSymbol}, 최근 ${hours}시간):

                뉴스 검색 중 오류 발생: ${e.message}

                주의: 뉴스를 확인할 수 없으니 보수적으로 판단하세요.
            """.trimIndent()
        }
    }

    @Tool(description = """
        암호화폐의 24시간 거래량을 조회합니다 (Bithumb 기준).

        거래량 + 변동률 복합 판단 기준:
        - 거래량 50억원 이상 + 변동률 30% 미만: APPROVED
        - 거래량 10억원 이상 + 변동률 20% 미만: APPROVED
        - 거래량 5억원 이상 + 변동률 15% 미만: APPROVED
        - 거래량 5억원 미만: REJECTED (유동성 부족)
        - 변동률 30% 이상: REJECTED (이미 급등)

        반드시 이 도구로 거래량을 확인한 후 투자 결정을 내리세요.
    """)
    fun getCoinTradingVolume(
        @ToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String
    ): String {
        log.info("[Tool] getCoinTradingVolume: $market")

        return try {
            val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()

            if (ticker == null) {
                return """
                    $market 정보:
                    - 상태: 조회 실패
                    - 이유: Bithumb API 응답 없음

                    주의: 거래량 확인 불가 시 투자 거부 권장
                """.trimIndent()
            }

            val tradingVolume = ticker.accTradePrice ?: BigDecimal.ZERO
            val tradingVolumeFormatted = formatKrw(tradingVolume)
            val currentPrice = ticker.tradePrice
            val changeRate = ticker.changeRate?.multiply(BigDecimal(100))
                ?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO

            // 거래량 등급 판정
            val volumeGrade = when {
                tradingVolume >= BigDecimal("10000000000") -> "대형 유동성 (100억원 이상)"
                tradingVolume >= BigDecimal("5000000000") -> "충분한 유동성 (50억~100억원)"
                tradingVolume >= BigDecimal("1000000000") -> "중간 유동성 (10억~50억원)"
                tradingVolume >= BigDecimal("500000000") -> "소형 유동성 (5억~10억원)"
                else -> "부족 (5억원 미만)"
            }

            val absChangeRate = changeRate.abs()
            val isTooSmall = tradingVolume < BigDecimal("500000000")  // 5억 미만
            val isAlreadySurged = absChangeRate > BigDecimal("30")   // 30% 이상

            // 복합 조건으로 판단
            val recommendation = when {
                isTooSmall -> "거래량 5억원 미만, 유동성 부족 → REJECTED"
                isAlreadySurged -> "변동률 30% 이상, 이미 급등 → REJECTED"
                tradingVolume >= BigDecimal("5000000000") && absChangeRate < BigDecimal("30") ->
                    "거래량 50억+ & 변동률 30% 미만 → APPROVED"
                tradingVolume >= BigDecimal("1000000000") && absChangeRate < BigDecimal("20") ->
                    "거래량 10억+ & 변동률 20% 미만 → APPROVED"
                tradingVolume >= BigDecimal("500000000") && absChangeRate < BigDecimal("15") ->
                    "거래량 5억+ & 변동률 15% 미만 → APPROVED"
                else -> "조건 미충족, 악재 뉴스 확인 후 판단"
            }

            """
                $market 정보 (Bithumb):
                - 24시간 거래량: ${tradingVolumeFormatted}원
                - 거래량 등급: $volumeGrade
                - 현재가: ${formatKrw(currentPrice)}원
                - 24시간 변동률: ${changeRate}%

                판단: $recommendation
            """.trimIndent()

        } catch (e: Exception) {
            log.error("[Tool] getCoinTradingVolume 오류: ${e.message}", e)
            """
                $market 정보:
                - 상태: 조회 오류
                - 이유: ${e.message}

                주의: 거래량 확인 불가 시 투자 거부 권장
            """.trimIndent()
        }
    }

    private fun formatKrw(amount: BigDecimal): String {
        return when {
            amount >= BigDecimal("1000000000000") -> {
                val trillion = amount.divide(BigDecimal("1000000000000"), 2, RoundingMode.HALF_UP)
                "${trillion}조"
            }
            amount >= BigDecimal("100000000") -> {
                val billion = amount.divide(BigDecimal("100000000"), 2, RoundingMode.HALF_UP)
                "${billion}억"
            }
            amount >= BigDecimal("10000") -> {
                val tenThousand = amount.divide(BigDecimal("10000"), 0, RoundingMode.HALF_UP)
                "${tenThousand}만"
            }
            else -> amount.setScale(0, RoundingMode.HALF_UP).toString()
        }
    }

    @Tool(description = """
        최종 투자 결정을 내립니다. 반드시 뉴스 검색 후 호출하세요.

        market: 분석 중인 마켓 코드 (예: KRW-BTC) - 반드시 입력
        decision: APPROVED (투자 적합) 또는 REJECTED (투자 부적합)
        confidence: 판단 신뢰도 (0.0 ~ 1.0)
        reason: 판단 근거 (구체적으로 작성)
    """)
    fun makeDecision(
        @ToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String,
        @ToolParam(description = "APPROVED 또는 REJECTED") decision: String,
        @ToolParam(description = "신뢰도 0.0-1.0") confidence: Double,
        @ToolParam(description = "판단 근거") reason: String,
        @ToolParam(description = "뉴스 요약 (있는 경우)") newsSummary: String? = null,
        @ToolParam(description = "위험 요소 (쉼표로 구분)") riskFactors: String? = null
    ): String {
        log.info("[Tool] makeDecision: $market -> $decision, confidence=$confidence")

        val normalizedDecision = decision.uppercase()
        if (normalizedDecision !in listOf("APPROVED", "REJECTED")) {
            return "오류: decision은 APPROVED 또는 REJECTED여야 합니다"
        }

        val normalizedConfidence = confidence.coerceIn(0.0, 1.0)

        val result = FilterResult(
            decision = normalizedDecision,
            confidence = normalizedConfidence,
            reason = reason,
            newsSummary = newsSummary,
            riskFactors = riskFactors?.split(",")?.map { it.trim() } ?: emptyList()
        )

        // market별로 결과 저장
        decisionsByMarket[market] = result

        return "결정이 기록되었습니다: $market -> $normalizedDecision (신뢰도: $normalizedConfidence)"
    }

    @Tool(description = """
        웹 검색을 통해 암호화폐에 대한 커뮤니티 반응과 시장 분위기를 파악합니다.

        Reddit, Twitter, 디시인사이드, 코인판 등 커뮤니티의 실시간 반응을 검색합니다.
        공식 뉴스에서 찾을 수 없는 시장 심리를 파악하는 데 유용합니다.

        검색 결과로 다음을 판단할 수 있습니다:
        - 커뮤니티에서 긍정적/부정적 반응
        - 펌프앤덤프 의심 여부 (조작 의심 게시글)
        - 실제 사용자들의 관심도
    """)
    fun searchWebForCrypto(
        @ToolParam(description = "코인 심볼 (예: BTC, ETH, XRP)") coinSymbol: String,
        @ToolParam(description = "검색 유형: community(커뮤니티) 또는 news(뉴스)") searchType: String = "community"
    ): String {
        log.info("[Tool] searchWebForCrypto: $coinSymbol, type=$searchType")

        return try {
            val results = when (searchType.lowercase()) {
                "community" -> braveSearchApi.searchCryptoCommunity(coinSymbol.uppercase())
                "news" -> braveSearchApi.searchCryptoNews(coinSymbol.uppercase())
                else -> braveSearchApi.search("$coinSymbol cryptocurrency", 10)
            }

            if (results.isEmpty()) {
                """
                    웹 검색 결과 (${coinSymbol}, ${searchType}):

                    검색 결과 없음.

                    참고: 검색 결과가 없다고 해서 반드시 위험한 것은 아닙니다.
                    거래량과 변동률을 함께 고려하여 판단하세요.
                """.trimIndent()
            } else {
                val resultsText = results.take(5).mapIndexed { idx, r ->
                    """
                    ${idx + 1}. ${r.title}
                       출처: ${r.getDomain()}
                       요약: ${r.getSummary()}
                       URL: ${r.url}
                    """.trimIndent()
                }.joinToString("\n\n")

                val apiStatus = braveSearchApi.getApiStatus()

                """
                    웹 검색 결과 (${coinSymbol}, ${searchType}):

                    총 ${results.size}건 발견.

                    $resultsText

                    분석 포인트:
                    - 긍정적 반응이 많은가? (호재, 기대감)
                    - 부정적 반응이 많은가? (펌프앤덤프 의심, 사기 경고)
                    - 최근 언급이 급증했는가? (관심도 상승)

                    [API 상태: Free ${apiStatus.freeApiCallCount}/${apiStatus.freeApiQuota} 사용]
                """.trimIndent()
            }

        } catch (e: Exception) {
            log.error("[Tool] searchWebForCrypto 오류: ${e.message}", e)
            """
                웹 검색 결과 (${coinSymbol}, ${searchType}):

                검색 중 오류 발생: ${e.message}

                뉴스 검색(searchCryptoNews)과 거래량 조회(getCoinTradingVolume)로 판단하세요.
            """.trimIndent()
        }
    }
}
