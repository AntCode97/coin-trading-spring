package com.ant.cointrading.config

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LlmConfig {

    @Bean("anthropicChatClient")
    @ConditionalOnBean(AnthropicChatModel::class)
    fun anthropicChatClient(chatModel: AnthropicChatModel): ChatClient {
        return ChatClient.create(chatModel)
    }

    @Bean("openAiChatClient")
    @ConditionalOnBean(OpenAiChatModel::class)
    fun openAiChatClient(chatModel: OpenAiChatModel): ChatClient {
        return ChatClient.create(chatModel)
    }
}
