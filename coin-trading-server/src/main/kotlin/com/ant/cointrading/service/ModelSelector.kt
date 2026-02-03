package com.ant.cointrading.service

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

@Service
class ModelSelector(
    private val keyValueService: KeyValueService,
    @param:Qualifier("anthropicChatClient") private val anthropicClient: ChatClient? = null,
    @param:Qualifier("openAiChatClient") private val openAiClient: ChatClient? = null
) {
    private val log = LoggerFactory.getLogger(ModelSelector::class.java)
    private val clients = mutableMapOf<String, ChatClient>()

    companion object {
        const val KEY_PROVIDER = "llm.model.provider"
        const val KEY_MODEL_NAME = "llm.model.name"
        // 회고/최적화는 무조건 OpenAI 사용 (무료 토큰 제공)
        private const val REFLECTION_PROVIDER = "openai"
    }

    @PostConstruct
    fun init() {
        anthropicClient?.let { clients["anthropic"] = it }
        openAiClient?.let { clients["openai"] = it }
        log.info("ModelSelector 초기화: providers=${clients.keys}")
    }

    /**
     * 일반용 ChatClient (현재 설정된 provider 사용)
     */
    fun getChatClient(): ChatClient {
        val provider = getCurrentProvider()
        return clients[provider]
            ?: clients.values.firstOrNull()
            ?: throw IllegalStateException(
                "사용 가능한 ChatClient 없음. " +
                "SPRING_AI_ANTHROPIC_API_KEY 또는 SPRING_AI_OPENAI_API_KEY 환경변수를 설정하세요."
            )
    }

    /**
     * 회고/최적화용 ChatClient (무조건 OpenAI 사용)
     *
     * OpenAI가 무료 토큰을 더 많이 제공하여 회고/최적화에 적합
     */
    fun getChatClientForReflection(): ChatClient {
        return clients[REFLECTION_PROVIDER]
            ?: throw IllegalStateException(
                "회고용 OpenAI ChatClient 없음. " +
                "SPRING_AI_OPENAI_API_KEY 환경변수를 설정하세요."
            )
    }

    fun getCurrentProvider(): String = keyValueService.get(KEY_PROVIDER, "anthropic")

    fun getCurrentModelName(): String = keyValueService.get(KEY_MODEL_NAME, "claude-sonnet-4-20250514")

    fun isAvailable(): Boolean = clients.isNotEmpty()

    fun setProvider(provider: String): Boolean {
        if (provider !in clients.keys) {
            log.error("Provider '$provider' 없음 (가능: ${clients.keys})")
            return false
        }
        keyValueService.set(KEY_PROVIDER, provider, "llm", "LLM 제공자")
        log.info("Provider 변경: $provider")
        return true
    }

    fun setModelName(modelName: String): Boolean {
        keyValueService.set(KEY_MODEL_NAME, modelName, "llm", "LLM 모델명")
        log.info("모델명 변경: $modelName")
        return true
    }

    fun getAvailableProviders(): List<String> = clients.keys.toList()

    fun getStatus(): Map<String, Any?> = mapOf(
        "currentProvider" to getCurrentProvider(),
        "currentModelName" to getCurrentModelName(),
        "availableProviders" to getAvailableProviders(),
        "isAvailable" to clients.containsKey(getCurrentProvider())
    )

    fun getProviderModels(): Map<String, Map<String, String>> = mapOf(
        "anthropic" to mapOf(
            "default" to "claude-sonnet-4-20250514",
            "models" to "claude-sonnet-4-20250514, claude-opus-4-20250514, claude-3-5-haiku-latest"
        ),
        "openai" to mapOf(
            "default" to "gpt-5",
            "models" to "gpt-5, gpt-5-mini"
        )
    )
}
