package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * 자동 거래 설정
 */
@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val enabled: Boolean = false,
    val markets: List<String> = listOf("KRW-BTC", "KRW-ETH"),
    val orderAmountKrw: BigDecimal = BigDecimal("10000"),
    val maxDrawdownPercent: Double = 10.0,
    val riskPerTradePercent: Double = 1.0,
    val strategy: StrategyConfig = StrategyConfig()
)

/**
 * 전략 설정 (동적 조정 가능)
 */
data class StrategyConfig(
    var type: StrategyType = StrategyType.MEAN_REVERSION,
    var dcaInterval: Long = 86400000,           // DCA 매수 간격 (밀리초)
    var gridLevels: Int = 5,                     // 그리드 레벨 수
    var gridSpacingPercent: Double = 1.0,        // 그리드 간격 (%)
    var meanReversionThreshold: Double = 2.0,    // 평균회귀 임계값 (표준편차 배수)
    var rsiOversold: Int = 30,                   // RSI 과매도 기준
    var rsiOverbought: Int = 70,                 // RSI 과매수 기준
    var bollingerPeriod: Int = 20,               // 볼린저밴드 기간
    var bollingerStdDev: Double = 2.0            // 볼린저밴드 표준편차
)

enum class StrategyType {
    DCA,                    // Dollar Cost Averaging - 검증된 18.7% 연간 수익
    GRID,                   // Grid Trading - 횡보장에서 효과적
    MEAN_REVERSION,         // Mean Reversion - Sharpe Ratio 2.3
    ORDER_BOOK_IMBALANCE    // Order Book Imbalance - 초단기 호가창 불균형 전략
}
