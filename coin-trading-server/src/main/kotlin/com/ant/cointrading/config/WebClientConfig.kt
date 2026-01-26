package com.ant.cointrading.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors

@Configuration
class WebClientConfig {

    @Bean
    fun bithumbWebClient(properties: BithumbProperties): WebClient {
        // Virtual Thread 기반 HttpClient (Java 21+)
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        val httpClient = HttpClient.newBuilder()
            .executor(virtualThreadExecutor)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
