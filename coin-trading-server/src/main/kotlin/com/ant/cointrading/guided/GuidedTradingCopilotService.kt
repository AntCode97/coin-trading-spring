package com.ant.cointrading.guided

import com.ant.cointrading.config.CopilotProviderType
import com.ant.cointrading.config.GuidedCopilotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant

@Service
class GuidedTradingCopilotService(
    private val guidedTradingService: GuidedTradingService,
    @Qualifier("openAiChatClient") private val openAiChatClient: ChatClient?,
    private val objectMapper: ObjectMapper,
    private val properties: GuidedCopilotProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun analyze(request: GuidedCopilotRequest): GuidedCopilotResponse {
        require(properties.enabled) { "AI Copilot이 비활성화되어 있습니다." }

        val market = request.market.trim().uppercase()
        val chart = guidedTradingService.getChartData(market, request.interval, request.count)
        val snapshot = chart.toSnapshot()
        val provider = resolveProvider(request.provider)

        val modelResponse = when (provider) {
            CopilotProviderType.OPENAI -> analyzeWithOpenAi(snapshot, request.userPrompt)
            CopilotProviderType.ZAI -> analyzeWithZai(snapshot, request.userPrompt)
        }

        return GuidedCopilotResponse(
            provider = provider.name,
            analysis = modelResponse.analysis,
            confidence = modelResponse.confidence.coerceIn(0, 100),
            actions = modelResponse.actions.take(properties.maxActions),
            generatedAt = Instant.now(),
            snapshot = snapshot
        )
    }

    private fun analyzeWithOpenAi(snapshot: CopilotSnapshot, userPrompt: String?): CopilotModelResponse {
        val client = openAiChatClient ?: throw IllegalStateException("OpenAI ChatClient가 설정되지 않았습니다.")
        val content = client.prompt()
            .options(
                OpenAiChatOptions.builder()
                    .model(properties.openaiModel)
                    .temperature(0.2)
                    .build()
            )
            .system(buildSystemPrompt())
            .user(buildUserPrompt(snapshot, userPrompt))
            .call()
            .content()
            .orEmpty()

        return parseModelResponse(content)
    }

    private fun analyzeWithZai(snapshot: CopilotSnapshot, userPrompt: String?): CopilotModelResponse {
        val zai = properties.zai
        require(zai.enabled) { "Z.AI provider가 비활성화되어 있습니다." }
        require(zai.apiKey.isNotBlank()) { "Z.AI API Key가 설정되지 않았습니다." }

        val payload = mapOf(
            "model" to zai.model,
            "temperature" to 0.2,
            "stream" to false,
            "messages" to listOf(
                mapOf("role" to "system", "content" to buildSystemPrompt()),
                mapOf("role" to "user", "content" to buildUserPrompt(snapshot, userPrompt))
            )
        )

        val restClient = RestClient.builder()
            .baseUrl(zai.baseUrl)
            .defaultHeader("Authorization", "Bearer ${zai.apiKey}")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build()

        val raw = restClient.post()
            .uri("/chat/completions")
            .body(payload)
            .retrieve()
            .body(String::class.java)
            .orEmpty()

        val content = extractZaiContent(raw)
        return parseModelResponse(content)
    }

    private fun extractZaiContent(rawJson: String): String {
        if (rawJson.isBlank()) return ""
        return try {
            val root = objectMapper.readTree(rawJson)
            root.path("choices").firstOrNull()
                ?.path("message")
                ?.path("content")
                ?.asText()
                .orEmpty()
        } catch (e: Exception) {
            log.warn("Z.AI 응답 파싱 실패: {}", e.message)
            ""
        }
    }

    private fun parseModelResponse(rawContent: String): CopilotModelResponse {
        val normalized = extractJsonText(rawContent)
        return try {
            val node = objectMapper.readTree(normalized)
            val analysis = node.path("analysis").asText(rawContent).ifBlank { rawContent }
            val confidence = node.path("confidence").asInt(55)
            val actions = node.path("actions")
                .takeIf { it.isArray }
                ?.mapNotNull { actionNode ->
                    parseActionNode(actionNode)
                }
                .orEmpty()

            CopilotModelResponse(
                analysis = analysis,
                confidence = confidence,
                actions = actions
            )
        } catch (_: Exception) {
            CopilotModelResponse(
                analysis = rawContent.ifBlank { "현재 데이터 기준으로 명확한 추가 행동 신호가 없습니다. 리스크 관리 우선 접근을 권장합니다." },
                confidence = 50,
                actions = emptyList()
            )
        }
    }

    private fun parseActionNode(node: JsonNode): CopilotAction {
        return CopilotAction(
            type = node.path("type").asText("HOLD"),
            title = node.path("title").asText("관망"),
            reason = node.path("reason").asText("시장 노이즈 구간"),
            targetPrice = node.path("targetPrice").takeIf { !it.isMissingNode && !it.isNull }?.asDouble(),
            sizePercent = node.path("sizePercent").takeIf { !it.isMissingNode && !it.isNull }?.asInt(),
            urgency = node.path("urgency").asText("MEDIUM")
        )
    }

    private fun extractJsonText(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1)
        }
        return content
    }

    private fun resolveProvider(raw: String?): CopilotProviderType {
        val normalized = raw?.trim()?.uppercase()
        if (normalized.isNullOrBlank()) {
            return properties.defaultProvider
        }
        return runCatching { CopilotProviderType.valueOf(normalized) }
            .getOrElse { properties.defaultProvider }
    }

    private fun buildSystemPrompt(): String {
        return """
            너는 한국 암호화폐 수동 트레이딩 코파일럿이다.
            목적은 사용자가 포지션을 안전하게 관리하도록 돕는 것이다.
            반드시 JSON만 반환해라. 스키마:
            {
              "analysis": "자연어 브리핑 (2~4문장)",
              "confidence": 0-100,
              "actions": [
                {
                  "type": "ADD|PARTIAL_TP|FULL_EXIT|HOLD|WAIT_RETEST",
                  "title": "짧은 제목",
                  "reason": "행동 근거",
                  "targetPrice": number|null,
                  "sizePercent": number|null,
                  "urgency": "LOW|MEDIUM|HIGH"
                }
              ]
            }
            규칙:
            1) 과도한 확신 금지. 확률/리스크 기반으로 조언.
            2) 포지션이 없으면 신규 진입 권고 대신 관망 또는 진입 트리거를 제시.
            3) 손절/익절/부분익절/물타기는 반드시 숫자 근거와 함께 제시.
            4) actions는 최대 4개.
        """.trimIndent()
    }

    private fun buildUserPrompt(snapshot: CopilotSnapshot, userPrompt: String?): String {
        return buildString {
            appendLine("시장: ${snapshot.market}")
            appendLine("현재가: ${snapshot.currentPrice}")
            appendLine("추천가: ${snapshot.recommendedEntryPrice}")
            appendLine("추천 손절/익절: ${snapshot.stopLossPrice} / ${snapshot.takeProfitPrice}")
            appendLine("추천가 승률: ${snapshot.recommendedEntryWinRate}")
            appendLine("현재가 승률: ${snapshot.marketEntryWinRate}")
            appendLine("RR: ${snapshot.riskRewardRatio}")
            appendLine("포지션 상태: ${snapshot.positionStatus ?: "NO_POSITION"}")
            appendLine("포지션 수익률: ${snapshot.positionPnlPercent ?: 0.0}")
            appendLine("물타기 횟수: ${snapshot.dcaCount ?: 0}/${snapshot.maxDcaCount ?: 0}")
            appendLine("절반익절 여부: ${snapshot.halfTakeProfitDone ?: false}")
            appendLine("최근 이벤트: ${snapshot.recentEvents.joinToString(" | ").ifBlank { "없음" }}")
            if (!userPrompt.isNullOrBlank()) {
                appendLine("사용자 요청: $userPrompt")
            }
            appendLine("위 데이터만 기준으로 현재 시점의 실행 우선순위를 제시해라.")
        }
    }

    private fun GuidedChartResponse.toSnapshot(): CopilotSnapshot {
        return CopilotSnapshot(
            market = market,
            currentPrice = recommendation.currentPrice,
            recommendedEntryPrice = recommendation.recommendedEntryPrice,
            stopLossPrice = recommendation.stopLossPrice,
            takeProfitPrice = recommendation.takeProfitPrice,
            recommendedEntryWinRate = recommendation.recommendedEntryWinRate,
            marketEntryWinRate = recommendation.marketEntryWinRate,
            riskRewardRatio = recommendation.riskRewardRatio,
            positionStatus = activePosition?.status,
            positionPnlPercent = activePosition?.unrealizedPnlPercent,
            dcaCount = activePosition?.dcaCount,
            maxDcaCount = activePosition?.maxDcaCount,
            halfTakeProfitDone = activePosition?.halfTakeProfitDone,
            recentEvents = events.takeLast(5).map { "${it.eventType}:${it.message ?: ""}" }
        )
    }
}

data class GuidedCopilotRequest(
    val market: String,
    val interval: String = "minute30",
    val count: Int = 120,
    val provider: String? = null,
    val userPrompt: String? = null
)

data class GuidedCopilotResponse(
    val provider: String,
    val analysis: String,
    val confidence: Int,
    val actions: List<CopilotAction>,
    val generatedAt: Instant,
    val snapshot: CopilotSnapshot
)

data class CopilotAction(
    val type: String,
    val title: String,
    val reason: String,
    val targetPrice: Double? = null,
    val sizePercent: Int? = null,
    val urgency: String = "MEDIUM"
)

data class CopilotSnapshot(
    val market: String,
    val currentPrice: Double,
    val recommendedEntryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val recommendedEntryWinRate: Double,
    val marketEntryWinRate: Double,
    val riskRewardRatio: Double,
    val positionStatus: String? = null,
    val positionPnlPercent: Double? = null,
    val dcaCount: Int? = null,
    val maxDcaCount: Int? = null,
    val halfTakeProfitDone: Boolean? = null,
    val recentEvents: List<String> = emptyList()
)

private data class CopilotModelResponse(
    val analysis: String,
    val confidence: Int,
    val actions: List<CopilotAction>
)
