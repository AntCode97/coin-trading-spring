package com.ant.cointrading.optimizer

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.StrategyType
import com.ant.cointrading.mcp.tool.PerformanceTools
import com.ant.cointrading.mcp.tool.StrategyTools
import com.ant.cointrading.repository.TradeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * LLM Optimizer 전용 도구
 *
 * Spring AI Tool Calling을 통해 LLM이 직접 호출할 수 있는 함수들.
 * PerformanceTools와 StrategyTools를 래핑하여 LLM에게 제공.
 *
 * 사용 가능한 도구:
 * 1. 성과 분석 도구 - 거래 성과 조회 및 분석
 * 2. 전략 조회 도구 - 현재 전략 설정 확인
 * 3. 전략 변경 도구 - 파라미터 조정 (안전장치 적용)
 */
@Component
class OptimizerTools(
    private val performanceTools: PerformanceTools,
    private val strategyTools: StrategyTools,
    private val tradingProperties: TradingProperties,
    private val tradeRepository: TradeRepository,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(OptimizerTools::class.java)

    // ===========================================
    // 성과 분석 도구
    // ===========================================

    @Tool(description = "최근 N일간의 거래 성과 요약을 조회합니다. 총 거래 수, 승률, 수익률, 최대 손실 등을 반환합니다.")
    fun getPerformanceSummary(
        @ToolParam(description = "분석 기간 (일 단위, 기본값: 30)") days: Int = 30
    ): String {
        log.info("[Tool] getPerformanceSummary 호출: days=$days")
        val result = performanceTools.getPerformanceSummary(days)
        return objectMapper.writeValueAsString(result)
    }

    @Tool(description = "전략별 성과를 분석합니다. DCA, Grid, Mean Reversion 각 전략의 수익률과 승률을 비교합니다.")
    fun getStrategyPerformance(
        @ToolParam(description = "분석 기간 (일 단위, 기본값: 30)") days: Int = 30
    ): String {
        log.info("[Tool] getStrategyPerformance 호출: days=$days")
        val strategies = listOf("DCA", "GRID", "MEAN_REVERSION")
        val result = strategies.associate { strategy ->
            strategy to performanceTools.getStrategyPerformance(strategy, days)
        }
        return objectMapper.writeValueAsString(result)
    }

    @Tool(description = "일별 거래 성과를 조회합니다. 날짜별 수익률 추이를 확인할 수 있습니다.")
    fun getDailyPerformance(
        @ToolParam(description = "조회 기간 (일 단위, 기본값: 30)") days: Int = 30
    ): String {
        log.info("[Tool] getDailyPerformance 호출: days=$days")
        val result = performanceTools.getDailyPerformance(days)
        return objectMapper.writeValueAsString(result)
    }

    @Tool(description = "시스템 최적화 리포트를 조회합니다. 현재 전략의 문제점과 개선 방향을 제안합니다.")
    fun getOptimizationReport(): String {
        log.info("[Tool] getOptimizationReport 호출")
        val result = performanceTools.getOptimizationReport()
        return objectMapper.writeValueAsString(result)
    }

    @Tool(description = "최근 거래 내역을 조회합니다. 개별 거래의 상세 정보를 확인할 수 있습니다.")
    fun getRecentTrades(
        @ToolParam(description = "조회할 거래 수 (기본값: 20)") limit: Int = 20
    ): String {
        log.info("[Tool] getRecentTrades 호출: limit=$limit")
        val result = performanceTools.getRecentTrades(limit)
        return objectMapper.writeValueAsString(result)
    }

    // ===========================================
    // 전략 조회 도구
    // ===========================================

    @Tool(description = "현재 전략 설정을 조회합니다. 전략 유형, RSI 임계값, 볼린저 밴드 파라미터 등을 반환합니다.")
    fun getStrategyConfig(): String {
        log.info("[Tool] getStrategyConfig 호출")
        val result = strategyTools.getStrategyConfig()
        return objectMapper.writeValueAsString(result)
    }

    @Tool(description = "전략별 사용 가이드를 조회합니다. 각 전략의 특징과 적합한 시장 상황을 설명합니다.")
    fun getStrategyGuide(): String {
        log.info("[Tool] getStrategyGuide 호출")
        val result = strategyTools.getStrategyGuide()
        return objectMapper.writeValueAsString(result)
    }

    // ===========================================
    // 전략 변경 도구 (안전장치 적용)
    // ===========================================

    @Tool(description = """
        전략 유형을 변경합니다.
        가능한 값: DCA, GRID, MEAN_REVERSION
        주의: 전략 변경은 신중하게 결정해야 합니다.
    """)
    fun setStrategy(
        @ToolParam(description = "새로운 전략 유형 (DCA, GRID, MEAN_REVERSION)") strategyType: String
    ): String {
        log.info("[Tool] setStrategy 호출: strategyType=$strategyType")
        return try {
            val type = StrategyType.valueOf(strategyType.uppercase())
            strategyTools.setStrategy(type.name)
            "전략이 ${type.name}(으)로 변경되었습니다."
        } catch (e: Exception) {
            "전략 변경 실패: ${e.message}. 가능한 값: DCA, GRID, MEAN_REVERSION"
        }
    }

    @Tool(description = """
        Mean Reversion 전략의 임계값을 변경합니다.
        값이 높을수록 더 극단적인 상황에서만 진입합니다.
        권장 범위: 1.5 ~ 3.0 (기본값: 2.0)
    """)
    fun setMeanReversionThreshold(
        @ToolParam(description = "새로운 임계값 (1.0 ~ 4.0 범위)") threshold: Double
    ): String {
        log.info("[Tool] setMeanReversionThreshold 호출: threshold=$threshold")
        if (threshold !in 1.0..4.0) {
            return "임계값은 1.0 ~ 4.0 범위여야 합니다. 입력값: $threshold"
        }
        strategyTools.setMeanReversionThreshold(threshold)
        return "Mean Reversion 임계값이 $threshold(으)로 변경되었습니다."
    }

    @Tool(description = """
        RSI 과매수/과매도 임계값을 변경합니다.
        과매도 값 이하에서 매수, 과매수 값 이상에서 매도 신호가 발생합니다.
        권장 범위: 과매도 20~35, 과매수 65~80
    """)
    fun setRsiThresholds(
        @ToolParam(description = "RSI 과매도 임계값 (15 ~ 40 범위)") oversold: Int,
        @ToolParam(description = "RSI 과매수 임계값 (60 ~ 85 범위)") overbought: Int
    ): String {
        log.info("[Tool] setRsiThresholds 호출: oversold=$oversold, overbought=$overbought")
        if (oversold !in 15..40) {
            return "과매도 임계값은 15 ~ 40 범위여야 합니다. 입력값: $oversold"
        }
        if (overbought !in 60..85) {
            return "과매수 임계값은 60 ~ 85 범위여야 합니다. 입력값: $overbought"
        }
        if (oversold >= overbought) {
            return "과매도 값($oversold)은 과매수 값($overbought)보다 작아야 합니다."
        }
        strategyTools.setRsiThresholds(oversold, overbought)
        return "RSI 임계값이 과매도=$oversold, 과매수=$overbought(으)로 변경되었습니다."
    }

    @Tool(description = """
        볼린저 밴드 파라미터를 변경합니다.
        기간이 길수록 평균이 부드러워지고, 표준편차가 클수록 밴드가 넓어집니다.
        권장 범위: 기간 15~30, 표준편차 1.5~2.5
    """)
    fun setBollingerParams(
        @ToolParam(description = "볼린저 밴드 기간 (10 ~ 50 범위)") period: Int,
        @ToolParam(description = "볼린저 밴드 표준편차 배수 (1.5 ~ 3.0 범위)") stdDev: Double
    ): String {
        log.info("[Tool] setBollingerParams 호출: period=$period, stdDev=$stdDev")
        if (period !in 10..50) {
            return "볼린저 기간은 10 ~ 50 범위여야 합니다. 입력값: $period"
        }
        if (stdDev !in 1.5..3.0) {
            return "표준편차 배수는 1.5 ~ 3.0 범위여야 합니다. 입력값: $stdDev"
        }
        strategyTools.setBollingerParams(period, stdDev)
        return "볼린저 밴드 파라미터가 기간=$period, 표준편차=$stdDev(으)로 변경되었습니다."
    }

    @Tool(description = """
        Grid 전략의 그리드 레벨 수를 변경합니다.
        레벨이 많을수록 더 촘촘한 그리드가 형성됩니다.
        권장 범위: 3 ~ 10
    """)
    fun setGridLevels(
        @ToolParam(description = "그리드 레벨 수 (3 ~ 10 범위)") levels: Int
    ): String {
        log.info("[Tool] setGridLevels 호출: levels=$levels")
        if (levels !in 3..10) {
            return "그리드 레벨은 3 ~ 10 범위여야 합니다. 입력값: $levels"
        }
        strategyTools.setGridLevels(levels)
        return "그리드 레벨이 ${levels}개로 변경되었습니다."
    }

    @Tool(description = """
        Grid 전략의 그리드 간격을 변경합니다.
        간격이 클수록 더 넓은 범위를 커버합니다.
        권장 범위: 0.5% ~ 3.0%
    """)
    fun setGridSpacing(
        @ToolParam(description = "그리드 간격 (0.5 ~ 3.0 % 범위)") spacingPercent: Double
    ): String {
        log.info("[Tool] setGridSpacing 호출: spacingPercent=$spacingPercent")
        if (spacingPercent !in 0.5..3.0) {
            return "그리드 간격은 0.5 ~ 3.0% 범위여야 합니다. 입력값: $spacingPercent"
        }
        strategyTools.setGridSpacing(spacingPercent)
        return "그리드 간격이 ${spacingPercent}%로 변경되었습니다."
    }

    @Tool(description = """
        DCA 전략의 매수 간격을 변경합니다.
        밀리초 단위로 입력합니다.
        권장 범위: 1시간(3600000) ~ 7일(604800000)
    """)
    fun setDcaInterval(
        @ToolParam(description = "DCA 매수 간격 (밀리초 단위, 3600000 ~ 604800000 범위)") intervalMs: Long
    ): String {
        log.info("[Tool] setDcaInterval 호출: intervalMs=$intervalMs")
        if (intervalMs !in 3600000..604800000) {
            return "DCA 간격은 1시간(3600000ms) ~ 7일(604800000ms) 범위여야 합니다. 입력값: $intervalMs"
        }
        strategyTools.setDcaInterval(intervalMs)
        val hours = intervalMs / 3600000.0
        return "DCA 매수 간격이 ${String.format("%.1f", hours)}시간으로 변경되었습니다."
    }
}
