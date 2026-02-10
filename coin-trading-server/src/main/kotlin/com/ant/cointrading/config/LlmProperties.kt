package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LLM 최적화 설정
 *
 * API 키는 Spring AI 설정에서 관리:
 * - spring.ai.openai.api-key
 */
@ConfigurationProperties(prefix = "llm.optimizer")
data class LlmProperties(
    val enabled: Boolean = false,
    val cron: String = "0 0 0 * * *",  // 매일 자정
    val validation: ValidationProperties = ValidationProperties()
)

data class ValidationProperties(
    val enabled: Boolean = true,
    val market: String = "KRW-BTC",
    val cacheMinutes: Long = 180,
    val minValidWindows: Int = 6,
    val minOutOfSampleSharpe: Double = 0.5,
    val minOutOfSampleTrades: Double = 1.0,
    val maxSharpeDecayPercent: Double = 35.0,
    val maxOutOfSampleDrawdownPercent: Double = 25.0,
    val executionSlippageBps: Double = 8.0
)
