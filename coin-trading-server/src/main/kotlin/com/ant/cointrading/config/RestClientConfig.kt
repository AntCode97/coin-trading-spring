package com.ant.cointrading.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class RestClientConfig {

    companion object {
        // 빗썸 API 타임아웃 설정 (연결 지연 대응)
        const val BITHUMB_CONNECT_TIMEOUT_SECONDS = 30L
        const val BITHUMB_READ_TIMEOUT_SECONDS = 30L

        // 일반 API 타임아웃 설정
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 10L
    }

    @Bean
    fun bithumbRestClient(properties: BithumbProperties): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(BITHUMB_CONNECT_TIMEOUT_SECONDS))
            setReadTimeout(Duration.ofSeconds(BITHUMB_READ_TIMEOUT_SECONDS))
        }

        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun binanceRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://fapi.binance.com")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun binancePublicRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://api.binance.com")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun cryptoCompareRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://min-api.cryptocompare.com")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun exchangeRateRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://open.er-api.com")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun braveSearchRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://api.search.brave.com")
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Accept-Encoding", "gzip")
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun slackRestClient(): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
        }

        return RestClient.builder()
            .baseUrl("https://slack.com/api")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }
}
