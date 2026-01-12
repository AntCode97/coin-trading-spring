package com.ant.cointrading.mcp.tool

import com.ant.cointrading.config.StrategyConfig
import com.ant.cointrading.config.StrategyType
import com.ant.cointrading.config.TradingProperties
import org.slf4j.LoggerFactory
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

// ========================================
// 응답 Data Classes
// ========================================

/** 전략 파라미터 */
data class StrategyParameters(
    val dcaInterval: Long,
    val dcaIntervalHours: Long,
    val gridLevels: Int,
    val gridSpacingPercent: Double,
    val meanReversionThreshold: Double,
    val rsiOversold: Int,
    val rsiOverbought: Int,
    val bollingerPeriod: Int,
    val bollingerStdDev: Double
)

/** 전략 설정 정보 */
data class StrategyConfigInfo(
    val currentStrategy: String,
    val parameters: StrategyParameters,
    val tradingEnabled: Boolean,
    val markets: List<String>,
    val orderAmountKrw: java.math.BigDecimal,
    val maxDrawdownPercent: Double,
    val riskPerTradePercent: Double
)

/** 전략 변경 결과 */
data class StrategyChangeResult(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val currentStrategy: String? = null
)

/** 파라미터 변경 결과 */
data class ParameterChangeResult(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)

/** 전략 정보 */
data class StrategyInfo(
    val type: String,
    val name: String,
    val description: String,
    val bestFor: String,
    val riskLevel: String,
    val keyParameters: String? = null,
    val historicalReturn: String? = null,
    val sharpeRatio: String? = null
)

/** 전략 가이드 */
data class StrategyGuide(
    val strategies: List<StrategyInfo>,
    val recommendations: Map<String, String>
)

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
    fun getStrategyConfig(): StrategyConfigInfo {
        val config = tradingProperties.strategy
        return StrategyConfigInfo(
            currentStrategy = config.type.name,
            parameters = StrategyParameters(
                dcaInterval = config.dcaInterval,
                dcaIntervalHours = config.dcaInterval / 3600000,
                gridLevels = config.gridLevels,
                gridSpacingPercent = config.gridSpacingPercent,
                meanReversionThreshold = config.meanReversionThreshold,
                rsiOversold = config.rsiOversold,
                rsiOverbought = config.rsiOverbought,
                bollingerPeriod = config.bollingerPeriod,
                bollingerStdDev = config.bollingerStdDev
            ),
            tradingEnabled = tradingProperties.enabled,
            markets = tradingProperties.markets,
            orderAmountKrw = tradingProperties.orderAmountKrw,
            maxDrawdownPercent = tradingProperties.maxDrawdownPercent,
            riskPerTradePercent = tradingProperties.riskPerTradePercent
        )
    }

    @McpTool(description = "활성 전략을 변경합니다. 사용 가능: DCA, GRID, MEAN_REVERSION")
    fun setStrategy(
        @McpToolParam(description = "전략 유형: DCA, GRID, MEAN_REVERSION") strategyType: String
    ): StrategyChangeResult {
        return try {
            val type = StrategyType.valueOf(strategyType.uppercase())
            tradingProperties.strategy.type = type
            log.info("전략 변경: {} -> {}", tradingProperties.strategy.type, type)
            StrategyChangeResult(
                success = true,
                message = "전략이 ${type}(으)로 변경되었습니다.",
                currentStrategy = type.name
            )
        } catch (e: Exception) {
            StrategyChangeResult(
                success = false,
                error = "잘못된 전략 유형: $strategyType. 사용 가능: DCA, GRID, MEAN_REVERSION"
            )
        }
    }

    @McpTool(description = "DCA 전략의 매수 간격을 변경합니다 (시간 단위).")
    fun setDcaInterval(
        @McpToolParam(description = "DCA 매수 간격 (시간 단위, 예: 24 = 하루에 한번)") intervalHours: Long
    ): ParameterChangeResult {
        if (intervalHours < 1 || intervalHours > 720) {
            return ParameterChangeResult(success = false, error = "간격은 1~720시간 사이여야 합니다")
        }
        val intervalMs = intervalHours * 3600000
        tradingProperties.strategy.dcaInterval = intervalMs
        log.info("DCA 간격 변경: {}시간 ({}ms)", intervalHours, intervalMs)
        return ParameterChangeResult(
            success = true,
            message = "DCA 간격이 ${intervalHours}시간으로 변경되었습니다."
        )
    }

    @McpTool(description = "Grid 전략의 그리드 레벨 수를 변경합니다.")
    fun setGridLevels(
        @McpToolParam(description = "그리드 레벨 수 (3~20)") levels: Int
    ): ParameterChangeResult {
        if (levels < 3 || levels > 20) {
            return ParameterChangeResult(success = false, error = "그리드 레벨은 3~20 사이여야 합니다")
        }
        tradingProperties.strategy.gridLevels = levels
        log.info("그리드 레벨 변경: {}", levels)
        return ParameterChangeResult(
            success = true,
            message = "그리드 레벨이 ${levels}개로 변경되었습니다."
        )
    }

    @McpTool(description = "Grid 전략의 그리드 간격(%)을 변경합니다.")
    fun setGridSpacing(
        @McpToolParam(description = "그리드 간격 (%, 0.5~5.0)") spacingPercent: Double
    ): ParameterChangeResult {
        if (spacingPercent < 0.5 || spacingPercent > 5.0) {
            return ParameterChangeResult(success = false, error = "그리드 간격은 0.5~5.0% 사이여야 합니다")
        }
        tradingProperties.strategy.gridSpacingPercent = spacingPercent
        log.info("그리드 간격 변경: {}%", spacingPercent)
        return ParameterChangeResult(
            success = true,
            message = "그리드 간격이 ${spacingPercent}%로 변경되었습니다."
        )
    }

    @McpTool(description = "Mean Reversion 전략의 임계값(표준편차 배수)을 변경합니다.")
    fun setMeanReversionThreshold(
        @McpToolParam(description = "평균회귀 임계값 (표준편차 배수, 1.0~3.0)") threshold: Double
    ): ParameterChangeResult {
        if (threshold < 1.0 || threshold > 3.0) {
            return ParameterChangeResult(success = false, error = "임계값은 1.0~3.0 사이여야 합니다")
        }
        tradingProperties.strategy.meanReversionThreshold = threshold
        log.info("평균회귀 임계값 변경: {}σ", threshold)
        return ParameterChangeResult(
            success = true,
            message = "평균회귀 임계값이 ${threshold}σ로 변경되었습니다."
        )
    }

    @McpTool(description = "RSI 과매도/과매수 기준을 변경합니다.")
    fun setRsiThresholds(
        @McpToolParam(description = "RSI 과매도 기준 (10~40)") oversold: Int,
        @McpToolParam(description = "RSI 과매수 기준 (60~90)") overbought: Int
    ): ParameterChangeResult {
        if (oversold < 10 || oversold > 40) {
            return ParameterChangeResult(success = false, error = "과매도 기준은 10~40 사이여야 합니다")
        }
        if (overbought < 60 || overbought > 90) {
            return ParameterChangeResult(success = false, error = "과매수 기준은 60~90 사이여야 합니다")
        }
        if (oversold >= overbought) {
            return ParameterChangeResult(success = false, error = "과매도 기준이 과매수 기준보다 작아야 합니다")
        }

        tradingProperties.strategy.rsiOversold = oversold
        tradingProperties.strategy.rsiOverbought = overbought
        log.info("RSI 기준 변경: 과매도={}, 과매수={}", oversold, overbought)
        return ParameterChangeResult(
            success = true,
            message = "RSI 기준이 과매도=$oversold, 과매수=$overbought(으)로 변경되었습니다."
        )
    }

    @McpTool(description = "볼린저 밴드 파라미터를 변경합니다.")
    fun setBollingerParams(
        @McpToolParam(description = "볼린저 밴드 기간 (10~50)") period: Int,
        @McpToolParam(description = "표준편차 배수 (1.0~3.0)") stdDev: Double
    ): ParameterChangeResult {
        if (period < 10 || period > 50) {
            return ParameterChangeResult(success = false, error = "기간은 10~50 사이여야 합니다")
        }
        if (stdDev < 1.0 || stdDev > 3.0) {
            return ParameterChangeResult(success = false, error = "표준편차는 1.0~3.0 사이여야 합니다")
        }

        tradingProperties.strategy.bollingerPeriod = period
        tradingProperties.strategy.bollingerStdDev = stdDev
        log.info("볼린저 밴드 변경: 기간={}, 표준편차={}σ", period, stdDev)
        return ParameterChangeResult(
            success = true,
            message = "볼린저 밴드가 기간=$period, 표준편차=${stdDev}σ로 변경되었습니다."
        )
    }

    @McpTool(description = "각 전략에 대한 설명과 권장 사용 시나리오를 제공합니다.")
    fun getStrategyGuide(): StrategyGuide {
        return StrategyGuide(
            strategies = listOf(
                StrategyInfo(
                    type = "DCA",
                    name = "Dollar Cost Averaging",
                    description = "일정 간격으로 정해진 금액을 매수하는 전략",
                    bestFor = "장기 투자, 변동성 높은 시장",
                    historicalReturn = "연평균 18.7% (BTC 10년 백테스트)",
                    riskLevel = "낮음",
                    keyParameters = "dcaInterval (매수 간격)"
                ),
                StrategyInfo(
                    type = "GRID",
                    name = "Grid Trading",
                    description = "가격 범위를 그리드로 나누어 자동 매수/매도",
                    bestFor = "횡보장, 박스권 시장",
                    historicalReturn = "월평균 2-5% (횡보장 기준)",
                    riskLevel = "중간",
                    keyParameters = "gridLevels, gridSpacingPercent"
                ),
                StrategyInfo(
                    type = "MEAN_REVERSION",
                    name = "Mean Reversion",
                    description = "가격이 평균에서 벗어났을 때 회귀를 기대하고 거래",
                    bestFor = "과매수/과매도 구간, 단기 트레이딩",
                    sharpeRatio = "2.3 (백테스트 기준)",
                    riskLevel = "중간~높음",
                    keyParameters = "meanReversionThreshold, rsiOversold, rsiOverbought"
                )
            ),
            recommendations = mapOf(
                "bullMarket" to "DCA - 꾸준한 매수로 상승장 수익 극대화",
                "bearMarket" to "DCA (소액) - 저점 매수 기회, 또는 현금 보유",
                "sidewaysMarket" to "GRID - 박스권에서 스윙 트레이딩으로 수익",
                "highVolatility" to "MEAN_REVERSION - 급등/급락 시 역추세 매매"
            )
        )
    }
}
