package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bithumb API 설정
 */
@ConfigurationProperties(prefix = "bithumb")
data class BithumbProperties(
    val baseUrl: String = "https://api.bithumb.com",
    val accessKey: String = "",
    val secretKey: String = "",
    val websocket: WebSocketProperties = WebSocketProperties()
)

data class WebSocketProperties(
    val enabled: Boolean = true,
    val url: String = "wss://ws-api.bithumb.com/websocket/v1",
    val reconnectDelayMs: Long = 5000,
    val staleThresholdMs: Long = 15000,
    val maxCodesPerSubscribe: Int = 70
)
