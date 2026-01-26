package com.ant.cointrading

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Coin Trading Server - 통합 트레이딩 서버
 *
 * 구성:
 * 1. 룰 기반 자동 매매 엔진 (실시간)
 * 2. MCP 도구 서버 (LLM 연동)
 * 3. LLM 기반 전략 최적화 (주기적)
 * 4. 에러 이벤트 발행 및 Slack 알림
 * 5. Virtual Thread 기반 스케줄링 (SchedulerConfig)
 *
 * 흐름:
 * - 평소: 룰 기반 전략이 시장 분석 및 자동 매매
 * - 주기적: LLM이 거래 성과 분석 및 전략 파라미터 튜닝
 * - 에러 발생 시: Slack으로 실시간 알림
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class CoinTradingApplication

fun main(args: Array<String>) {
    runApplication<CoinTradingApplication>(*args)
}
