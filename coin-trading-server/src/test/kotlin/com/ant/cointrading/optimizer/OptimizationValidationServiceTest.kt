package com.ant.cointrading.optimizer

import com.ant.cointrading.backtesting.WalkForwardAnalysisResult
import com.ant.cointrading.backtesting.WalkForwardOptimizer
import com.ant.cointrading.backtesting.WalkForwardWindowResult
import com.ant.cointrading.config.LlmProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.ValidationProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
@DisplayName("OptimizationValidationService")
class OptimizationValidationServiceTest {

    @Mock
    private lateinit var walkForwardOptimizer: WalkForwardOptimizer

    @Test
    @DisplayName("검증 기준 통과 시 변경 허용")
    fun allowsChangesWhenValidationPasses() {
        val service = OptimizationValidationService(
            walkForwardOptimizer = walkForwardOptimizer,
            llmProperties = LlmProperties(
                enabled = true,
                validation = ValidationProperties(
                    enabled = true,
                    minValidWindows = 5,
                    minOutOfSampleSharpe = 0.5,
                    minOutOfSampleTrades = 1.0,
                    maxSharpeDecayPercent = 35.0,
                    maxOutOfSampleDrawdownPercent = 25.0
                )
            ),
            tradingProperties = TradingProperties()
        )

        whenever(
            walkForwardOptimizer.runWalkForwardAnalysis(
                any(),
                any(),
                anyOrNull(),
                any()
            )
        ).thenReturn(passResult())

        val status = service.getGateStatus(forceRefresh = true)

        assertTrue(status.canApplyChanges)
    }

    @Test
    @DisplayName("검증 기준 미달 시 변경 차단")
    fun blocksChangesWhenValidationFails() {
        val service = OptimizationValidationService(
            walkForwardOptimizer = walkForwardOptimizer,
            llmProperties = LlmProperties(
                enabled = true,
                validation = ValidationProperties(
                    enabled = true,
                    minValidWindows = 6,
                    minOutOfSampleSharpe = 0.5,
                    minOutOfSampleTrades = 1.0,
                    maxSharpeDecayPercent = 35.0,
                    maxOutOfSampleDrawdownPercent = 25.0
                )
            ),
            tradingProperties = TradingProperties()
        )

        whenever(
            walkForwardOptimizer.runWalkForwardAnalysis(
                any(),
                any(),
                anyOrNull(),
                any()
            )
        ).thenReturn(failResult())

        val status = service.getGateStatus(forceRefresh = true)

        assertFalse(status.canApplyChanges)
        assertTrue(status.reason.contains("검증 실패"))
    }

    private fun passResult(): WalkForwardAnalysisResult {
        return WalkForwardAnalysisResult(
            market = "KRW-BTC",
            strategyName = "BREAKOUT",
            totalWindows = 8,
            validWindows = 7,
            avgInSampleSharpe = 0.9,
            avgOutOfSampleSharpe = 0.65,
            avgOutOfSampleReturn = 2.1,
            avgOutOfSampleTrades = 1.8,
            sharpeDecay = 0.25,
            decayPercent = 27.8,
            isOverfitted = false,
            windowResults = listOf(
                WalkForwardWindowResult(
                    windowIndex = 0,
                    trainPeriod = LocalDate.parse("2025-01-01") to LocalDate.parse("2025-06-30"),
                    testPeriod = LocalDate.parse("2025-07-01") to LocalDate.parse("2025-07-07"),
                    inSampleSharpe = 0.85,
                    outOfSampleSharpe = 0.62,
                    outOfSampleReturn = 1.9,
                    outOfSampleTrades = 2,
                    bestParams = mapOf("bollingerPeriod" to 20)
                )
            )
        )
    }

    private fun failResult(): WalkForwardAnalysisResult {
        return WalkForwardAnalysisResult(
            market = "KRW-BTC",
            strategyName = "BREAKOUT",
            totalWindows = 8,
            validWindows = 3,
            avgInSampleSharpe = 0.7,
            avgOutOfSampleSharpe = 0.1,
            avgOutOfSampleReturn = -4.0,
            avgOutOfSampleTrades = 0.3,
            sharpeDecay = 0.6,
            decayPercent = 85.0,
            isOverfitted = true,
            windowResults = listOf(
                WalkForwardWindowResult(
                    windowIndex = 0,
                    trainPeriod = LocalDate.parse("2025-01-01") to LocalDate.parse("2025-06-30"),
                    testPeriod = LocalDate.parse("2025-07-01") to LocalDate.parse("2025-07-07"),
                    inSampleSharpe = 0.7,
                    outOfSampleSharpe = 0.1,
                    outOfSampleReturn = -30.0,
                    outOfSampleTrades = 0,
                    bestParams = mapOf("bollingerPeriod" to 20)
                )
            )
        )
    }
}
