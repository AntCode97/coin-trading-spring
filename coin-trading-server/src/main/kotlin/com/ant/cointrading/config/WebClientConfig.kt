package com.ant.cointrading.config

import io.netty.channel.ChannelOption
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.Executors

@Configuration
class WebClientConfig {

    @Bean
    fun bithumbWebClient(properties: BithumbProperties): WebClient {
        // Virtual Thread 기반 Reactor Netty HttpClient
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        val connectionProvider = ConnectionProvider.builder("bithumb")
            .maxConnections(500)
            .maxIdleTime(Duration.ofSeconds(20))
            .maxLifeTime(Duration.ofSeconds(60))
            .pendingAcquireTimeout(Duration.ofSeconds(60))
            .evictInBackground(Duration.ofSeconds(120))
            .build()

        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(10))
            .doOnConnected {
                // Virtual Thread에서 I/O 작업 실행
            }

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
