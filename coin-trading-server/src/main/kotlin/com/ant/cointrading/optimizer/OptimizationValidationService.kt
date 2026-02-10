package com.ant.cointrading.optimizer

import com.ant.cointrading.backtesting.WalkForwardAnalysisResult
import com.ant.cointrading.backtesting.WalkForwardOptimizer
import com.ant.cointrading.config.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

data class OptimizationGateStatus(
    val canApplyChanges: Boolean,
    val reason: String,
    val checkedAt: Instant,
    val market: String,
    val totalWindows: Int,
    val validWindows: Int,
    val avgOutOfSampleSharpe: Double,
    val avgOutOfSampleReturn: Double,
    val avgOutOfSampleTrades: Double,
    val decayPercent: Double,
    val isOverfitted: Boolean,
    val minValidWindows: Int,
    val minOutOfSampleSharpe: Double,
    val minOutOfSampleTrades: Double,
    val maxSharpeDecayPercent: Double,
    val maxOutOfSampleDrawdownPercent: Double,
    val executionSlippageBps: Double,
    val cached: Boolean
)

@Component
class OptimizationValidationService(
    private val walkForwardOptimizer: WalkForwardOptimizer,
    private val llmProperties: LlmProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedStatus: OptimizationGateStatus? = null

    fun getGateStatus(forceRefresh: Boolean = false): OptimizationGateStatus {
        val validation = llmProperties.validation
        if (!validation.enabled) {
            return disabledGateStatus(validation.market)
        }

        val currentCached = cachedStatus
        if (!forceRefresh && currentCached != null && isCacheFresh(currentCached, validation.cacheMinutes)) {
            return currentCached.copy(cached = true)
        }

        return runCatching {
            evaluateGate(validation.market)
        }.getOrElse { e ->
            log.error("수익성 검증 게이트 평가 실패: ${e.message}", e)
            failClosedStatus(validation.market, "검증 실패(${e.message}), 변경 차단")
        }.also {
            cachedStatus = it.copy(cached = false)
        }
    }

    private fun evaluateGate(market: String): OptimizationGateStatus {
        val validation = llmProperties.validation
        val slippageRate = validation.executionSlippageBps / 10_000.0
        val analysis = walkForwardOptimizer.runWalkForwardAnalysis(
            market = market,
            slippageRate = slippageRate
        )
        return buildStatus(analysis, validation.executionSlippageBps, cached = false)
    }

    private fun buildStatus(
        analysis: WalkForwardAnalysisResult,
        executionSlippageBps: Double,
        cached: Boolean
    ): OptimizationGateStatus {
        val validation = llmProperties.validation
        val checks = listOf(
            validationCheck(
                passed = analysis.validWindows >= validation.minValidWindows,
                failMessage = "유효 윈도우 부족 (${analysis.validWindows}/${validation.minValidWindows})"
            ),
            validationCheck(
                passed = analysis.avgOutOfSampleSharpe >= validation.minOutOfSampleSharpe,
                failMessage = "OOS Sharpe 부족 (${format(analysis.avgOutOfSampleSharpe)} < ${format(validation.minOutOfSampleSharpe)})"
            ),
            validationCheck(
                passed = analysis.avgOutOfSampleTrades >= validation.minOutOfSampleTrades,
                failMessage = "OOS 거래 수 부족 (${format(analysis.avgOutOfSampleTrades)} < ${format(validation.minOutOfSampleTrades)})"
            ),
            validationCheck(
                passed = analysis.decayPercent <= validation.maxSharpeDecayPercent,
                failMessage = "Sharpe decay 과다 (${format(analysis.decayPercent)}% > ${format(validation.maxSharpeDecayPercent)}%)"
            ),
            validationCheck(
                passed = analysis.windowResults.none { it.outOfSampleReturn <= -validation.maxOutOfSampleDrawdownPercent },
                failMessage = "단일 OOS 윈도우 손실 과다 (-${format(validation.maxOutOfSampleDrawdownPercent)}% 이하 구간 존재)"
            ),
            validationCheck(
                passed = !analysis.isOverfitted,
                failMessage = "과적합 감지 (Renaissance 기준 20%+ decay)"
            )
        )

        val failMessage = checks.firstOrNull { !it.passed }?.failMessage
        val canApply = failMessage == null
        val reason = if (canApply) {
            "검증 통과: OOS Sharpe ${format(analysis.avgOutOfSampleSharpe)}, decay ${format(analysis.decayPercent)}%"
        } else {
            "검증 실패: $failMessage"
        }

        return OptimizationGateStatus(
            canApplyChanges = canApply,
            reason = reason,
            checkedAt = Instant.now(),
            market = analysis.market,
            totalWindows = analysis.totalWindows,
            validWindows = analysis.validWindows,
            avgOutOfSampleSharpe = analysis.avgOutOfSampleSharpe,
            avgOutOfSampleReturn = analysis.avgOutOfSampleReturn,
            avgOutOfSampleTrades = analysis.avgOutOfSampleTrades,
            decayPercent = analysis.decayPercent,
            isOverfitted = analysis.isOverfitted,
            minValidWindows = validation.minValidWindows,
            minOutOfSampleSharpe = validation.minOutOfSampleSharpe,
            minOutOfSampleTrades = validation.minOutOfSampleTrades,
            maxSharpeDecayPercent = validation.maxSharpeDecayPercent,
            maxOutOfSampleDrawdownPercent = validation.maxOutOfSampleDrawdownPercent,
            executionSlippageBps = executionSlippageBps,
            cached = cached
        )
    }

    private fun failClosedStatus(market: String, reason: String): OptimizationGateStatus {
        val validation = llmProperties.validation
        return OptimizationGateStatus(
            canApplyChanges = false,
            reason = reason,
            checkedAt = Instant.now(),
            market = market,
            totalWindows = 0,
            validWindows = 0,
            avgOutOfSampleSharpe = 0.0,
            avgOutOfSampleReturn = 0.0,
            avgOutOfSampleTrades = 0.0,
            decayPercent = 0.0,
            isOverfitted = false,
            minValidWindows = validation.minValidWindows,
            minOutOfSampleSharpe = validation.minOutOfSampleSharpe,
            minOutOfSampleTrades = validation.minOutOfSampleTrades,
            maxSharpeDecayPercent = validation.maxSharpeDecayPercent,
            maxOutOfSampleDrawdownPercent = validation.maxOutOfSampleDrawdownPercent,
            executionSlippageBps = validation.executionSlippageBps,
            cached = false
        )
    }

    private fun disabledGateStatus(market: String): OptimizationGateStatus {
        val validation = llmProperties.validation
        return OptimizationGateStatus(
            canApplyChanges = true,
            reason = "검증 게이트 비활성화",
            checkedAt = Instant.now(),
            market = market,
            totalWindows = 0,
            validWindows = 0,
            avgOutOfSampleSharpe = 0.0,
            avgOutOfSampleReturn = 0.0,
            avgOutOfSampleTrades = 0.0,
            decayPercent = 0.0,
            isOverfitted = false,
            minValidWindows = validation.minValidWindows,
            minOutOfSampleSharpe = validation.minOutOfSampleSharpe,
            minOutOfSampleTrades = validation.minOutOfSampleTrades,
            maxSharpeDecayPercent = validation.maxSharpeDecayPercent,
            maxOutOfSampleDrawdownPercent = validation.maxOutOfSampleDrawdownPercent,
            executionSlippageBps = validation.executionSlippageBps,
            cached = false
        )
    }

    private fun isCacheFresh(status: OptimizationGateStatus, cacheMinutes: Long): Boolean {
        return ChronoUnit.MINUTES.between(status.checkedAt, Instant.now()) < cacheMinutes
    }

    private fun validationCheck(passed: Boolean, failMessage: String): ValidationCheck {
        return ValidationCheck(passed = passed, failMessage = failMessage)
    }

    private fun format(value: Double): String = String.format("%.2f", value)

    private data class ValidationCheck(
        val passed: Boolean,
        val failMessage: String
    )
}
