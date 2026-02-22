package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "desktop-access")
data class DesktopAccessProperties(
    val enabled: Boolean = true,
    val headerName: String = "X-Desktop-Token",
    val token: String = ""
)
