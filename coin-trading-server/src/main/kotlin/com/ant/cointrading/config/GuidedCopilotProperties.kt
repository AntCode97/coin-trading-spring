package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "guided.copilot")
data class GuidedCopilotProperties(
    val enabled: Boolean = true,
    val defaultProvider: CopilotProviderType = CopilotProviderType.OPENAI,
    val openaiModel: String = "gpt-5-mini",
    val maxActions: Int = 4,
    val zai: ZaiProperties = ZaiProperties()
)

data class ZaiProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "https://api.z.ai/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-4.7"
)

enum class CopilotProviderType {
    OPENAI,
    ZAI
}
