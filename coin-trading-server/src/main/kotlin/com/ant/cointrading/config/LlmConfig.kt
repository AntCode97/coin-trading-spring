package com.ant.cointrading.config

import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * LLM ChatClient 설정
 *
 * Spring AI 1.1.2 + Spring Boot 3.5.x 호환.
 * ObjectProvider를 사용해서 ChatModel이 있을 때만 ChatClient 생성.
 */
@Configuration
class LlmConfig {

    private val log = LoggerFactory.getLogger(LlmConfig::class.java)

    @Bean("anthropicChatClient")
    fun anthropicChatClient(chatModelProvider: ObjectProvider<AnthropicChatModel>): ChatClient? {
        val chatModel = chatModelProvider.ifAvailable
        return if (chatModel != null) {
            log.info("Anthropic ChatClient 생성됨")
            ChatClient.create(chatModel)
        } else {
            log.info("AnthropicChatModel 없음 - ChatClient 생성 안 함")
            null
        }
    }

    @Bean("openAiChatClient")
    fun openAiChatClient(chatModelProvider: ObjectProvider<OpenAiChatModel>): ChatClient? {
        val chatModel = chatModelProvider.ifAvailable
        return if (chatModel != null) {
            log.info("OpenAI ChatClient 생성됨")
            ChatClient.create(chatModel)
        } else {
            log.info("OpenAiChatModel 없음 - ChatClient 생성 안 함")
            null
        }
    }
}
