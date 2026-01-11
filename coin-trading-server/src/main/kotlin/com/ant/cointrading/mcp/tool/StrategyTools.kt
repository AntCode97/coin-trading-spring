package com.ant.cointrading.mcp.tool

import com.ant.cointrading.config.StrategyConfig
import com.ant.cointrading.config.StrategyType
import com.ant.cointrading.config.TradingProperties
import org.slf4j.LoggerFactory
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * 전략 파라미터 조회/수정 MCP 도구
 *
 * LLM이 거래 성과를 분석한 후 전략 파라미터를 조정할 때 사용한다.
 */
@Component
class StrategyTools(
    private val tradingProperties: TradingProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @McpTool(description = "현재 활성화된 전략과 모든 파라미터를 조회합니다.")
    fun getStrategyConfig(): Map<String, Any> {
        val config = tradingProperties.strategy
        return mapOf(
            "currentStrategy" to config.type.name,
            "parameters" to mapOf(
                "dcaInterval" to config.dcaInterval,
                "dcaIntervalHours" to config.dcaInterval / 3600000,
                "gridLevels" to config.gridLevels,
                "gridSpacingPercent" to config.gridSpacingPercent,
                "meanReversionThreshold" to config.meanReversionThreshold,
                "rsiOversold" to config.rsiOversold,
                "rsiOverbought" to config.rsiOverbought,
                "bollingerPeriod" to config.bollingerPeriod,
                "bollingerStdDev" to config.bollingerStdDev
            ),
            "tradingEnabled" to tradingProperties.enabled,
            "markets" to tradingProperties.markets,
            "orderAmountKrw" to tradingProperties.orderAmountKrw,
            "maxDrawdownPercent" to tradingProperties.maxDrawdownPercent,
            "riskPerTradePercent" to tradingProperties.riskPerTradePercent
        )
    }

    @McpTool(description = "활성 전략을 변경합니다. 사용 가능: DCA, GRID, MEAN_REVERSION")
    fun setStrategy(
        @McpToolParam(description = "전략 유형: DCA, GRID, MEAN_REVERSION") strategyType: String
    ): Map<String, Any> {
        return try {
            val type = StrategyType.valueOf(strategyType.uppercase())
            tradingProperties.strategy.type = type
            log.info("전략 변경: {} -> {}", tradingProperties.strategy.type, type)
            mapOf(
                "success" to true,
                "message" to "전략이 ${type}(으)로 변경되었습니다.",
                "currentStrategy" to type.name
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to "잘못된 전략 유형: $strategyType. 사용 가능: DCA, GRID, MEAN_REVERSION"
            )
        }
    }

    @McpTool(description = "DCA 전략의 매수 간격을 변경합니다 (시간 단위).")
    fun setDcaInterval(
        @McpToolParam(description = "DCA 매수 간격 (시간 단위, 예: 24 = 하루에 한번)") intervalHours: Long
    ): Map<String, Any> {
        if (intervalHours < 1 || intervalHours > 720) {
            return mapOf("success" to false, "error" to "간격은 1~720시간 사이여야 합니다")
        }
        val intervalMs = intervalHours * 3600000
        tradingProperties.strategy.dcaInterval = intervalMs
        log.info("DCA 간격 변경: {}시간 ({}ms)", intervalHours, intervalMs)
        return mapOf(
            "success" to true,
            "message" to "DCA 간격이 ${intervalHours}시간으로 변경되었습니다.",
            "dcaIntervalHours" to intervalHours
        )
    }

    @McpTool(description = "Grid 전략의 그리드 레벨 수를 변경합니다.")
    fun setGridLevels(
        @McpToolParam(description = "그리드 레벨 수 (3~20)") levels: Int
    ): Map<String, Any> {
        if (levels < 3 || levels > 20) {
            return mapOf("success" to false, "error" to "그리드 레벨은 3~20 사이여야 합니다")
        }
        tradingProperties.strategy.gridLevels = levels
        log.info("그리드 레벨 변경: {}", levels)
        return mapOf(
            "success" to true,
            "message" to "그리드 레벨이 ${levels}개로 변경되었습니다.",
            "gridLevels" to levels
        )
    }

    @McpTool(description = "Grid 전략의 그리드 간격(%)을 변경합니다.")
    fun setGridSpacing(
        @McpToolParam(description = "그리드 간격 (%, 0.5~5.0)") spacingPercent: Double
    ): Map<String, Any> {
        if (spacingPercent < 0.5 || spacingPercent > 5.0) {
            return mapOf("success" to false, "error" to "그리드 간격은 0.5~5.0% 사이여야 합니다")
        }
        tradingProperties.strategy.gridSpacingPercent = spacingPercent
        log.info("그리드 간격 변경: {}%", spacingPercent)
        return mapOf(
            "success" to true,
            "message" to "그리드 간격이 ${spacingPercent}%로 변경되었습니다.",
            "gridSpacingPercent" to spacingPercent
        )
    }

    @McpTool(description = "Mean Reversion 전략의 임계값(표준편차 배수)을 변경합니다.")
    fun setMeanReversionThreshold(
        @McpToolParam(description = "평균회귀 임계값 (표준편차 배수, 1.0~3.0)") threshold: Double
    ): Map<String, Any> {
        if (threshold < 1.0 || threshold > 3.0) {
            return mapOf("success" to false, "error" to "임계값은 1.0~3.0 사이여야 합니다")
        }
        tradingProperties.strategy.meanReversionThreshold = threshold
        log.info("평균회귀 임계값 변경: {}σ", threshold)
        return mapOf(
            "success" to true,
            "message" to "평균회귀 임계값이 ${threshold}σ로 변경되었습니다.",
            "meanReversionThreshold" to threshold
        )
    }

    @McpTool(description = "RSI 과매도/과매수 기준을 변경합니다.")
    fun setRsiThresholds(
        @McpToolParam(description = "RSI 과매도 기준 (10~40)") oversold: Int,
        @McpToolParam(description = "RSI 과매수 기준 (60~90)") overbought: Int
    ): Map<String, Any> {
        if (oversold < 10 || oversold > 40) {
            return mapOf("success" to false, "error" to "과매도 기준은 10~40 사이여야 합니다")
        }
        if (overbought < 60 || overbought > 90) {
            return mapOf("success" to false, "error" to "과매수 기준은 60~90 사이여야 합니다")
        }
        if (oversold >= overbought) {
            return mapOf("success" to false, "error" to "과매도 기준이 과매수 기준보다 작아야 합니다")
        }

        tradingProperties.strategy.rsiOversold = oversold
        tradingProperties.strategy.rsiOverbought = overbought
        log.info("RSI 기준 변경: 과매도={}, 과매수={}", oversold, overbought)
        return mapOf(
            "success" to true,
            "message" to "RSI 기준이 과매도=$oversold, 과매수=$overbought(으)로 변경되었습니다.",
            "rsiOversold" to oversold,
            "rsiOverbought" to overbought
        )
    }

    @McpTool(description = "볼린저 밴드 파라미터를 변경합니다.")
    fun setBollingerParams(
        @McpToolParam(description = "볼린저 밴드 기간 (10~50)") period: Int,
        @McpToolParam(description = "표준편차 배수 (1.0~3.0)") stdDev: Double
    ): Map<String, Any> {
        if (period < 10 || period > 50) {
            return mapOf("success" to false, "error" to "기간은 10~50 사이여야 합니다")
        }
        if (stdDev < 1.0 || stdDev > 3.0) {
            return mapOf("success" to false, "error" to "표준편차는 1.0~3.0 사이여야 합니다")
        }

        tradingProperties.strategy.bollingerPeriod = period
        tradingProperties.strategy.bollingerStdDev = stdDev
        log.info("볼린저 밴드 변경: 기간={}, 표준편차={}σ", period, stdDev)
        return mapOf(
            "success" to true,
            "message" to "볼린저 밴드가 기간=$period, 표준편차=${stdDev}σ로 변경되었습니다.",
            "bollingerPeriod" to period,
            "bollingerStdDev" to stdDev
        )
    }

    @McpTool(description = "각 전략에 대한 설명과 권장 사용 시나리오를 제공합니다.")
    fun getStrategyGuide(): Map<String, Any> {
        return mapOf(
            "strategies" to listOf(
                mapOf(
                    "type" to "DCA",
                    "name" to "Dollar Cost Averaging",
                    "description" to "일정 간격으로 정해진 금액을 매수하는 전략",
                    "bestFor" to "장기 투자, 변동성 높은 시장",
                    "historicalReturn" to "연평균 18.7% (BTC 10년 백테스트)",
                    "riskLevel" to "낮음",
                    "keyParameter" to "dcaInterval (매수 간격)"
                ),
                mapOf(
                    "type" to "GRID",
                    "name" to "Grid Trading",
                    "description" to "가격 범위를 그리드로 나누어 자동 매수/매도",
                    "bestFor" to "횡보장, 박스권 시장",
                    "historicalReturn" to "월평균 2-5% (횡보장 기준)",
                    "riskLevel" to "중간",
                    "keyParameters" to "gridLevels, gridSpacingPercent"
                ),
                mapOf(
                    "type" to "MEAN_REVERSION",
                    "name" to "Mean Reversion",
                    "description" to "가격이 평균에서 벗어났을 때 회귀를 기대하고 거래",
                    "bestFor" to "과매수/과매도 구간, 단기 트레이딩",
                    "sharpeRatio" to "2.3 (백테스트 기준)",
                    "riskLevel" to "중간~높음",
                    "keyParameters" to "meanReversionThreshold, rsiOversold, rsiOverbought"
                )
            ),
            "recommendations" to mapOf(
                "bullMarket" to "DCA - 꾸준한 매수로 상승장 수익 극대화",
                "bearMarket" to "DCA (소액) - 저점 매수 기회, 또는 현금 보유",
                "sidewaysMarket" to "GRID - 박스권에서 스윙 트레이딩으로 수익",
                "highVolatility" to "MEAN_REVERSION - 급등/급락 시 역추세 매매"
            )
        )
    }
}
