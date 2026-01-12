package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.cryptocompare.CryptoCompareApi
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.VolumeSurgeAlertEntity
import com.ant.cointrading.service.ModelSelector
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

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
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val systemPrompt = """
        당신은 암호화폐 투자 분석가입니다. 거래량이 급등한 종목이 투자하기 적합한지 판단합니다.

        판단 기준:
        1. 펌프앤덤프 의심 여부 (소형 토큰, 가짜뉴스, 소셜미디어 조작)
        2. 실제 호재 존재 여부 (프로젝트 업데이트, 파트너십, 상장 등)
        3. 24시간 거래량 (50억원 이상 선호, 10억원 미만 거부)
        4. 과거 가격 조작 이력

        반드시 도구를 사용하여:
        1. getCoinTradingVolume 도구로 24시간 거래량 확인
        2. searchCryptoNews 도구로 최근 뉴스 검색
        3. makeDecision 도구로 최종 판단

        주의:
        - 뉴스 없이 급등하는 경우 대부분 펌프앤덤프입니다
        - 24시간 거래량 10억원 미만 소형 토큰은 거부하세요
        - SNS 언급 급증과 함께 급등하는 경우 주의가 필요합니다
    """.trimIndent()

    /**
     * 종목 필터링
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

            // 결과 파싱 (filterTools.lastDecision에서 가져옴)
            filterTools.getLastDecision() ?: FilterResult(
                decision = "REJECTED",
                confidence = 0.3,
                reason = "LLM 응답 파싱 실패"
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
 */
@Component
class VolumeSurgeFilterTools(
    private val bithumbPublicApi: BithumbPublicApi,
    private val cryptoCompareApi: CryptoCompareApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastDecision: FilterResult? = null

    fun getLastDecision(): FilterResult? = lastDecision

    @Tool(description = """
        특정 암호화폐의 최근 뉴스를 검색합니다 (CryptoCompare API).

        뉴스가 있으면 급등 원인을 파악할 수 있습니다.
        뉴스가 없는 급등은 펌프앤덤프 가능성이 높습니다.

        반드시 이 도구로 뉴스를 확인한 후 투자 결정을 내리세요.
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

                    주의: 뉴스 없이 거래량이 급등한 경우 펌프앤덤프 가능성이 높습니다.
                    소셜미디어 조작이나 내부자 매집일 수 있으니 투자에 주의하세요.
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

                    분석: 위 뉴스들이 실제 호재(상장, 파트너십, 업데이트 등)인지,
                    단순 루머나 SNS 홍보성 기사인지 판단하세요.
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

        거래량 판단 기준:
        - 50억원 이상: 유동성 충분, 투자 적합
        - 10억~50억원: 중간 규모, 주의 필요
        - 10억원 미만: 소형 토큰, 조작 가능성 높음

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
                tradingVolume >= BigDecimal("5000000000") -> "충분 (50억원 이상)"
                tradingVolume >= BigDecimal("1000000000") -> "보통 (10억~50억원)"
                else -> "부족 (10억원 미만, 거부 권장)"
            }

            val isSmallCap = tradingVolume < BigDecimal("1000000000")

            """
                $market 정보 (Bithumb):
                - 24시간 거래량: ${tradingVolumeFormatted}원
                - 거래량 등급: $volumeGrade
                - 현재가: ${formatKrw(currentPrice)}원
                - 24시간 변동률: ${changeRate}%

                판단: ${if (isSmallCap) "10억원 미만 소형 토큰, 투자 거부 권장" else "거래량 기준 통과"}
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

        decision: APPROVED (투자 적합) 또는 REJECTED (투자 부적합)
        confidence: 판단 신뢰도 (0.0 ~ 1.0)
        reason: 판단 근거 (구체적으로 작성)
    """)
    fun makeDecision(
        @ToolParam(description = "APPROVED 또는 REJECTED") decision: String,
        @ToolParam(description = "신뢰도 0.0-1.0") confidence: Double,
        @ToolParam(description = "판단 근거") reason: String,
        @ToolParam(description = "뉴스 요약 (있는 경우)") newsSummary: String? = null,
        @ToolParam(description = "위험 요소 (쉼표로 구분)") riskFactors: String? = null
    ): String {
        log.info("[Tool] makeDecision: $decision, confidence=$confidence")

        val normalizedDecision = decision.uppercase()
        if (normalizedDecision !in listOf("APPROVED", "REJECTED")) {
            return "오류: decision은 APPROVED 또는 REJECTED여야 합니다"
        }

        val normalizedConfidence = confidence.coerceIn(0.0, 1.0)

        lastDecision = FilterResult(
            decision = normalizedDecision,
            confidence = normalizedConfidence,
            reason = reason,
            newsSummary = newsSummary,
            riskFactors = riskFactors?.split(",")?.map { it.trim() } ?: emptyList()
        )

        return "결정이 기록되었습니다: $normalizedDecision (신뢰도: $normalizedConfidence)"
    }
}
