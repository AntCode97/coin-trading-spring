package com.ant.cointrading.volumesurge

import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.VolumeSurgeAlertEntity
import com.ant.cointrading.service.ModelSelector
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

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
        3. 시가총액 및 유동성 (500억원 이상 선호)
        4. 과거 가격 조작 이력

        반드시 도구를 사용해 웹검색으로 최근 뉴스를 확인하세요.
        분석 후 makeDecision 도구를 호출하여 최종 판단을 내려주세요.

        주의:
        - 뉴스 없이 급등하는 경우 대부분 펌프앤덤프입니다
        - 소형 토큰(시가총액 500억 미만)은 조작 가능성이 높습니다
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
 */
@Component
class VolumeSurgeFilterTools {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastDecision: FilterResult? = null

    fun getLastDecision(): FilterResult? = lastDecision

    @Tool(description = "특정 암호화폐의 최근 뉴스를 웹검색합니다. 반드시 이 도구를 먼저 호출하여 뉴스를 확인하세요.")
    fun searchCryptoNews(
        @ToolParam(description = "코인 이름 (예: BTC, ETH, XRP)") coinSymbol: String,
        @ToolParam(description = "검색할 기간 (시간 단위, 기본 24)") hours: Int = 24
    ): String {
        log.info("[Tool] searchCryptoNews: $coinSymbol, ${hours}시간")

        // 실제 구현에서는 WebSearch 또는 뉴스 API 호출
        // 여기서는 시뮬레이션 응답 반환
        return """
            검색 결과 (${coinSymbol}, 최근 ${hours}시간):

            뉴스 검색 기능은 추후 구현 예정입니다.
            현재는 보수적으로 판단하여 뉴스 없는 급등으로 처리합니다.

            참고: 실제 뉴스 검색 API 연동 시 이 메시지가 교체됩니다.
        """.trimIndent()
    }

    @Tool(description = "암호화폐의 시가총액과 기본 정보를 조회합니다")
    fun getCoinMarketCap(
        @ToolParam(description = "코인 심볼 (예: BTC)") coinSymbol: String
    ): String {
        log.info("[Tool] getCoinMarketCap: $coinSymbol")

        // 실제 구현에서는 CoinGecko API 호출
        return """
            $coinSymbol 정보:
            - 시가총액: 조회 기능 구현 예정
            - 24시간 거래량: 조회 기능 구현 예정

            참고: CoinGecko API 연동 시 실제 데이터로 교체됩니다.
        """.trimIndent()
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
