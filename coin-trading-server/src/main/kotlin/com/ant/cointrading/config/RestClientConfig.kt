package com.ant.cointrading.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class RestClientConfig {

    @Bean
    fun bithumbRestClient(properties: BithumbProperties): RestClient {
        val requestFactory: ClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(10))
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
}
