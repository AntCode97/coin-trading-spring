package com.ant.cointrading.service

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct

/**
 * 동적 LLM 모델 선택기
 *
 * KeyValueService에 저장된 설정에 따라 런타임에 LLM 모델을 선택.
 * API를 통해 모델을 변경하면 다음 요청부터 새 모델 사용.
 *
 * 지원 Provider:
 * - anthropic: Claude 모델
 * - openai: GPT 모델
 * - google: Gemini 모델
 *
 * 설정 키:
 * - llm.model.provider: 사용할 제공자 (anthropic, openai, google)
 * - llm.model.name: 사용할 모델명 (선택적, provider 기본 모델 사용)
 */
@Service
class ModelSelector(
    private val keyValueService: KeyValueService,
    // Optional 의존성으로 주입 (설정된 것만 사용 가능)
    private val anthropicChatModel: ObjectProvider<ChatModel>,
    private val openAiChatModel: ObjectProvider<ChatModel>,
    private val googleChatModel: ObjectProvider<ChatModel>
) {

    private val log = LoggerFactory.getLogger(ModelSelector::class.java)

    // Provider별 ChatModel 캐시
    private val modelCache = mutableMapOf<String, ChatModel>()

    companion object {
        const val KEY_PROVIDER = "llm.model.provider"
        const val KEY_MODEL_NAME = "llm.model.name"

        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GOOGLE = "google"

        val AVAILABLE_PROVIDERS = listOf(PROVIDER_ANTHROPIC, PROVIDER_OPENAI, PROVIDER_GOOGLE)
    }

    @PostConstruct
    fun init() {
        log.info("=== ModelSelector 초기화 ===")

        // 사용 가능한 모델 확인 및 캐시
        detectAvailableModels()

        val currentProvider = getCurrentProvider()
        val currentModel = getCurrentModelName()
        log.info("현재 설정: provider=$currentProvider, model=$currentModel")
        log.info("사용 가능한 providers: ${modelCache.keys}")
    }

    /**
     * 사용 가능한 모델 감지
     */
    private fun detectAvailableModels() {
        // Spring 컨텍스트에서 각 provider별 ChatModel bean 확인
        // ObjectProvider는 bean이 없으면 null 반환

        try {
            anthropicChatModel.ifAvailable { model ->
                modelCache[PROVIDER_ANTHROPIC] = model
                log.info("Anthropic ChatModel 감지됨: ${model.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            log.debug("Anthropic ChatModel 없음: ${e.message}")
        }

        try {
            openAiChatModel.ifAvailable { model ->
                // Anthropic과 같은 bean이 아닌 경우에만 추가
                if (!modelCache.containsValue(model)) {
                    modelCache[PROVIDER_OPENAI] = model
                    log.info("OpenAI ChatModel 감지됨: ${model.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            log.debug("OpenAI ChatModel 없음: ${e.message}")
        }

        try {
            googleChatModel.ifAvailable { model ->
                if (!modelCache.containsValue(model)) {
                    modelCache[PROVIDER_GOOGLE] = model
                    log.info("Google ChatModel 감지됨: ${model.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            log.debug("Google ChatModel 없음: ${e.message}")
        }

        if (modelCache.isEmpty()) {
            log.warn("사용 가능한 ChatModel이 없습니다. API 키를 확인하세요.")
        }
    }

    /**
     * 현재 설정된 provider 조회
     */
    fun getCurrentProvider(): String {
        return keyValueService.get(KEY_PROVIDER, PROVIDER_OPENAI)
    }

    /**
     * 현재 설정된 모델명 조회
     */
    fun getCurrentModelName(): String {
        return keyValueService.get(KEY_MODEL_NAME, "claude-sonnet-4-20250514")
    }

    /**
     * 현재 설정에 맞는 ChatModel 반환
     */
    fun getChatModel(): ChatModel {
        val provider = getCurrentProvider()

        // 설정된 provider의 모델 반환
        val model = modelCache[provider]
        if (model != null) {
            return model
        }

        // 설정된 provider가 없으면 사용 가능한 첫 번째 모델 사용
        log.warn("Provider '$provider'의 ChatModel이 없음. 대체 모델 사용")

        return modelCache.values.firstOrNull()
            ?: throw IllegalStateException("사용 가능한 ChatModel이 없습니다. API 키를 확인하세요.")
    }

    /**
     * ChatClient 생성 (도구 포함)
     */
    fun createChatClient(tools: Any? = null): ChatClient {
        val model = getChatModel()

        val builder = ChatClient.builder(model)
        if (tools != null) {
            builder.defaultTools(tools)
        }

        return builder.build()
    }

    /**
     * Provider 변경
     */
    fun setProvider(provider: String): Boolean {
        if (provider !in AVAILABLE_PROVIDERS) {
            log.error("지원하지 않는 provider: $provider (가능: $AVAILABLE_PROVIDERS)")
            return false
        }

        if (!modelCache.containsKey(provider)) {
            log.error("Provider '$provider'의 ChatModel이 설정되지 않음 (API 키 확인)")
            return false
        }

        keyValueService.set(KEY_PROVIDER, provider, "llm", "LLM 제공자")
        log.info("Provider 변경: $provider")
        return true
    }

    /**
     * 모델명 변경
     */
    fun setModelName(modelName: String): Boolean {
        keyValueService.set(KEY_MODEL_NAME, modelName, "llm", "LLM 모델명")
        log.info("모델명 변경: $modelName")
        return true
    }

    /**
     * 사용 가능한 provider 목록
     */
    fun getAvailableProviders(): List<String> {
        return modelCache.keys.toList()
    }

    /**
     * 현재 상태 조회
     */
    fun getStatus(): Map<String, Any?> {
        val currentProvider = getCurrentProvider()
        val currentModel = getCurrentModelName()
        val actualModel = try {
            getChatModel().javaClass.simpleName
        } catch (e: Exception) {
            "N/A (${e.message})"
        }

        return mapOf(
            "currentProvider" to currentProvider,
            "currentModelName" to currentModel,
            "actualModelClass" to actualModel,
            "availableProviders" to getAvailableProviders(),
            "allProviders" to AVAILABLE_PROVIDERS,
            "isModelAvailable" to modelCache.containsKey(currentProvider)
        )
    }

    /**
     * Provider별 모델 정보
     */
    fun getProviderModels(): Map<String, Map<String, String>> {
        return mapOf(
            PROVIDER_ANTHROPIC to mapOf(
                "default" to "claude-sonnet-4-20250514",
                "models" to "claude-sonnet-4-20250514, claude-3-5-sonnet-20241022, claude-3-opus-20240229"
            ),
            PROVIDER_OPENAI to mapOf(
                "default" to "gpt-5-mini",
                "models" to "gpt-5-mini, gpt-4o, gpt-4-turbo"
            ),
            PROVIDER_GOOGLE to mapOf(
                "default" to "gemini-3-flash-preview",
                "models" to "gemini-3-flash-preview, gemini-1.5-pro, gemini-1.5-flash"
            )
        )
    }
}
