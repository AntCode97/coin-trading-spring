package com.ant.cointrading.api.binance

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "binance.futures")
class BinanceFuturesProperties(
    var apiKey: String = "",
    var apiSecret: String = "",
    var baseUrl: String = "https://fapi.binance.com"
)
