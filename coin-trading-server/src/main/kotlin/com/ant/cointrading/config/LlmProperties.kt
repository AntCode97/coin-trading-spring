package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LLM 최적화 설정
 *
 * API 키는 Spring AI 설정에서 관리:
 * - spring.ai.anthropic.api-key
 * - spring.ai.openai.api-key
 */
@ConfigurationProperties(prefix = "llm.optimizer")
data class LlmProperties(
    val enabled: Boolean = false,
    val cron: String = "0 0 0 * * *"  // 매일 자정
)
