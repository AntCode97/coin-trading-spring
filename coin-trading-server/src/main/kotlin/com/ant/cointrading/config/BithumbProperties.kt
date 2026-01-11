package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bithumb API 설정
 */
@ConfigurationProperties(prefix = "bithumb")
data class BithumbProperties(
    val baseUrl: String = "https://api.bithumb.com",
    val accessKey: String = "",
    val secretKey: String = ""
)
