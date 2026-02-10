package com.ant.cointrading.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class ModelSelector(
    private val keyValueService: KeyValueService,
    @param:Qualifier("openAiChatClient") private val openAiClient: ChatClient? = null
) {
    private val log = LoggerFactory.getLogger(ModelSelector::class.java)

    companion object {
        const val KEY_PROVIDER = "llm.model.provider"
        const val KEY_MODEL_NAME = "llm.model.name"
        private const val PROVIDER_OPENAI = "openai"
        private const val DEFAULT_MODEL = "gpt-5-mini"
    }

    @PostConstruct
    fun init() {
        migrateLegacyProviderSettings()
        log.info("ModelSelector 초기화: providers=${getAvailableProviders()}")
    }

    /**
     * 일반용 ChatClient (OpenAI 고정)
     */
    fun getChatClient(): ChatClient = requireOpenAiClient()

    /**
     * 회고/최적화용 ChatClient (OpenAI 고정)
     */
    fun getChatClientForReflection(): ChatClient = requireOpenAiClient()

    fun getCurrentProvider(): String = PROVIDER_OPENAI

    fun getCurrentModelName(): String = keyValueService.get(KEY_MODEL_NAME, DEFAULT_MODEL)

    fun isAvailable(): Boolean = openAiClient != null

    fun setProvider(provider: String): Boolean {
        val normalized = provider.trim().lowercase()
        if (normalized != PROVIDER_OPENAI) {
            log.error("Provider '$provider' 지원 안 함 (OpenAI 전용)")
            return false
        }

        keyValueService.set(KEY_PROVIDER, PROVIDER_OPENAI, "llm", "LLM 제공자 (OpenAI 고정)")
        log.info("Provider 고정: $PROVIDER_OPENAI")
        return true
    }

    fun setModelName(modelName: String): Boolean {
        val normalized = modelName.trim()
        if (normalized.isEmpty()) {
            log.error("모델명 변경 실패: 빈 값")
            return false
        }

        keyValueService.set(KEY_MODEL_NAME, normalized, "llm", "LLM 모델명")
        log.info("모델명 변경: $normalized")
        return true
    }

    fun getAvailableProviders(): List<String> {
        return if (openAiClient != null) listOf(PROVIDER_OPENAI) else emptyList()
    }

    fun getStatus(): Map<String, Any?> = mapOf(
        "currentProvider" to getCurrentProvider(),
        "currentModelName" to getCurrentModelName(),
        "availableProviders" to getAvailableProviders(),
        "isAvailable" to isAvailable()
    )

    fun getProviderModels(): Map<String, Map<String, String>> = mapOf(
        "openai" to mapOf(
            "default" to DEFAULT_MODEL,
            "models" to "gpt-5-mini, gpt-5"
        )
    )

    private fun requireOpenAiClient(): ChatClient {
        return openAiClient ?: throw IllegalStateException(
            "사용 가능한 OpenAI ChatClient 없음. SPRING_AI_OPENAI_API_KEY 환경변수를 설정하세요."
        )
    }

    private fun migrateLegacyProviderSettings() {
        val provider = keyValueService.get(KEY_PROVIDER)
        if (provider?.lowercase() != PROVIDER_OPENAI) {
            keyValueService.set(KEY_PROVIDER, PROVIDER_OPENAI, "llm", "LLM 제공자 (OpenAI 고정)")
            log.warn("기존 provider 설정을 OpenAI로 마이그레이션: {} -> {}", provider, PROVIDER_OPENAI)
        }

        val modelName = keyValueService.get(KEY_MODEL_NAME)
        if (modelName.isNullOrBlank() || modelName.contains("claude", ignoreCase = true)) {
            keyValueService.set(KEY_MODEL_NAME, DEFAULT_MODEL, "llm", "LLM 모델명")
            log.warn("기존 모델 설정을 OpenAI 기본 모델로 마이그레이션: {} -> {}", modelName, DEFAULT_MODEL)
        }
    }
}
