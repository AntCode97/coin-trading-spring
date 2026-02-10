package com.ant.cointrading.controller

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.engine.TradingEngine
import com.ant.cointrading.model.*
import com.ant.cointrading.risk.RiskManager
import com.ant.cointrading.strategy.StrategySelector
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * 트레이딩 REST API
 */
@RestController
@RequestMapping("/api/trading")
class TradingController(
    private val tradingEngine: TradingEngine,
    private val strategySelector: StrategySelector,
    private val riskManager: RiskManager,
    private val tradingProperties: TradingProperties
) {

    /**
     * 전체 마켓 상태 조회
     */
    @GetMapping("/status")
    fun getAllStatus(): Map<String, MarketStatus> = tradingEngine.getAllMarketStatuses()

    /**
     * 특정 마켓 상태 조회
     */
    @GetMapping("/status/{market}")
    fun getStatus(@PathVariable market: String): MarketStatus? = tradingEngine.getMarketStatus(market)

    /**
     * 리스크 통계 조회
     */
    @GetMapping("/risk/{market}")
    fun getRiskStats(
        @PathVariable market: String,
        @RequestParam(defaultValue = "1000000") balance: BigDecimal
    ): RiskStats = riskManager.getRiskStats(market, balance)

    /**
     * 사용 가능한 전략 목록
     */
    @GetMapping("/strategies")
    fun getStrategies(): List<Map<String, Any>> =
        strategySelector.getAllStrategies().map { strategy ->
            mapOf(
                "name" to strategy.name,
                "description" to strategy.getDescription(),
                "isCurrent" to (strategy.name == tradingProperties.strategy.type.name)
            )
        }

    /**
     * 현재 전략 설정 조회
     */
    @GetMapping("/strategy/config")
    fun getStrategyConfig(): StrategyConfigResponse {
        val config = tradingProperties.strategy
        return StrategyConfigResponse(
            type = config.type.name,
            dcaInterval = config.dcaInterval,
            gridLevels = config.gridLevels,
            gridSpacingPercent = config.gridSpacingPercent,
            meanReversionThreshold = config.meanReversionThreshold,
            rsiOversold = config.rsiOversold,
            rsiOverbought = config.rsiOverbought,
            bollingerPeriod = config.bollingerPeriod,
            bollingerStdDev = config.bollingerStdDev
        )
    }

    /**
     * 수동 분석 트리거
     */
    @PostMapping("/analyze/{market}")
    fun triggerAnalysis(@PathVariable market: String): TradingSignal? = tradingEngine.triggerAnalysis(market)

    /**
     * 거래 대상 마켓 목록
     */
    @GetMapping("/markets")
    fun getMarkets(): List<String> = tradingProperties.markets

    /**
     * 거래 활성화 상태
     */
    @GetMapping("/enabled")
    fun isEnabled(): Map<String, Any> = mapOf(
        "tradingEnabled" to tradingProperties.enabled,
        "orderAmountKrw" to tradingProperties.orderAmountKrw,
        "maxDrawdownPercent" to tradingProperties.maxDrawdownPercent
    )

    /**
     * 헬스 체크
     */
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf(
        "status" to "UP",
        "tradingEnabled" to tradingProperties.enabled.toString(),
        "markets" to tradingProperties.markets.joinToString(",")
    )
}

data class StrategyConfigResponse(
    val type: String,
    val dcaInterval: Long,
    val gridLevels: Int,
    val gridSpacingPercent: Double,
    val meanReversionThreshold: Double,
    val rsiOversold: Int,
    val rsiOverbought: Int,
    val bollingerPeriod: Int,
    val bollingerStdDev: Double
)
