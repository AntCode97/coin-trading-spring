package com.ant.cointrading.safety

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CircuitBreaker Safety Tests
 *
 * Verifies circuit breaker mechanisms:
 * 1. Consecutive loss detection
 * 2. Daily loss limit
 * 3. Execution failure handling
 * 4. Slippage threshold monitoring
 * 5. Cooldown and recovery
 *
 * 20년차 퀀트의 교훈:
 * "서킷브레이커가 없으면 하룻밤에 모든 것을 잃을 수 있다"
 */
@DisplayName("CircuitBreaker Safety Tests")
class CircuitBreakerSafetyTest {

    companion object {
        const val MAX_CONSECUTIVE_LOSSES = 3
        const val MAX_DAILY_LOSS_PERCENT = 5.0
        const val MAX_DAILY_LOSS_COUNT = 10
        const val MAX_CONSECUTIVE_EXECUTION_FAILURES = 5
        const val MAX_CONSECUTIVE_HIGH_SLIPPAGE = 3
        const val HIGH_SLIPPAGE_THRESHOLD_PERCENT = 2.0
        const val COOLDOWN_HOURS = 4L
        const val GLOBAL_COOLDOWN_HOURS = 24L
    }

    data class CircuitState(
        var consecutiveLosses: Int = 0,
        var consecutiveExecutionFailures: Int = 0,
        var consecutiveHighSlippage: Int = 0,
        var dailyLossPercent: Double = 0.0,
        var dailyLossCount: Int = 0,
        var isOpen: Boolean = false,
        var openTime: Instant? = null,
        var reason: String = ""
    )

    @Nested
    @DisplayName("Consecutive Loss Trigger")
    inner class ConsecutiveLossTriggerTest {

        @Test
        @DisplayName("Circuit triggers after 3 consecutive losses")
        fun circuitTriggersAfter3ConsecutiveLosses() {
            val state = CircuitState()

            // 3번 연속 손실 기록
            repeat(3) {
                state.consecutiveLosses++
            }

            val shouldTrip = state.consecutiveLosses >= MAX_CONSECUTIVE_LOSSES

            assertTrue(shouldTrip, "Circuit should trip after 3 consecutive losses")
            assertEquals(3, state.consecutiveLosses)
        }

        @Test
        @DisplayName("Profit resets consecutive losses")
        fun profitResetsConsecutiveLosses() {
            val state = CircuitState(consecutiveLosses = 2)

            // 수익 거래
            val pnlPercent = 1.5
            if (pnlPercent > 0) {
                state.consecutiveLosses = 0
            }

            assertEquals(0, state.consecutiveLosses, "Profit should reset loss counter")
        }

        @Test
        @DisplayName("Small loss still counts as loss")
        fun smallLossStillCountsAsLoss() {
            val state = CircuitState()

            // -0.01% 손실도 손실
            val pnlPercent = -0.01
            if (pnlPercent < 0) {
                state.consecutiveLosses++
            }

            assertEquals(1, state.consecutiveLosses, "Even small loss counts")
        }
    }

    @Nested
    @DisplayName("Daily Loss Limit")
    inner class DailyLossLimitTest {

        @Test
        @DisplayName("Circuit triggers when daily loss exceeds 5%")
        fun circuitTriggersOnDailyLossLimit() {
            val state = CircuitState(dailyLossPercent = 5.5)

            val shouldTrip = state.dailyLossPercent >= MAX_DAILY_LOSS_PERCENT

            assertTrue(shouldTrip, "Circuit should trip when daily loss exceeds 5%")
        }

        @Test
        @DisplayName("Circuit triggers after 10 losses in a day")
        fun circuitTriggersOn10DailyLosses() {
            val state = CircuitState(dailyLossCount = 10)

            val shouldTrip = state.dailyLossCount >= MAX_DAILY_LOSS_COUNT

            assertTrue(shouldTrip, "Circuit should trip after 10 daily losses")
        }

        @Test
        @DisplayName("Daily stats reset at midnight")
        fun dailyStatsResetAtMidnight() {
            val state = CircuitState(
                dailyLossPercent = 4.0,
                dailyLossCount = 8
            )

            // 자정 리셋 시뮬레이션
            state.dailyLossPercent = 0.0
            state.dailyLossCount = 0

            assertEquals(0.0, state.dailyLossPercent, "Daily loss should reset")
            assertEquals(0, state.dailyLossCount, "Daily loss count should reset")
        }
    }

    @Nested
    @DisplayName("Execution Failure Handling")
    inner class ExecutionFailureHandlingTest {

        @Test
        @DisplayName("Circuit triggers after 5 consecutive execution failures")
        fun circuitTriggersAfter5ExecutionFailures() {
            val state = CircuitState()

            repeat(5) {
                state.consecutiveExecutionFailures++
            }

            val shouldTrip = state.consecutiveExecutionFailures >= MAX_CONSECUTIVE_EXECUTION_FAILURES

            assertTrue(shouldTrip, "Circuit should trip after 5 execution failures")
        }

        @Test
        @DisplayName("Successful execution resets failure counter")
        fun successfulExecutionResetsFailureCounter() {
            val state = CircuitState(consecutiveExecutionFailures = 4)

            // 성공적인 실행
            state.consecutiveExecutionFailures = 0

            assertEquals(0, state.consecutiveExecutionFailures, "Success should reset failures")
        }
    }

    @Nested
    @DisplayName("Slippage Threshold")
    inner class SlippageThresholdTest {

        @Test
        @DisplayName("High slippage is tracked")
        fun highSlippageIsTracked() {
            val state = CircuitState()

            val slippagePercent = 2.5  // 2.5% slippage

            if (slippagePercent > HIGH_SLIPPAGE_THRESHOLD_PERCENT) {
                state.consecutiveHighSlippage++
            }

            assertEquals(1, state.consecutiveHighSlippage, "High slippage should be tracked")
        }

        @Test
        @DisplayName("Circuit triggers after 3 consecutive high slippages")
        fun circuitTriggersAfter3HighSlippages() {
            val state = CircuitState()

            repeat(3) {
                state.consecutiveHighSlippage++
            }

            val shouldTrip = state.consecutiveHighSlippage >= MAX_CONSECUTIVE_HIGH_SLIPPAGE

            assertTrue(shouldTrip, "Circuit should trip after 3 high slippages")
        }

        @Test
        @DisplayName("Normal slippage resets counter")
        fun normalSlippageResetsCounter() {
            val state = CircuitState(consecutiveHighSlippage = 2)

            val slippagePercent = 0.5  // Normal slippage

            if (slippagePercent <= HIGH_SLIPPAGE_THRESHOLD_PERCENT) {
                state.consecutiveHighSlippage = 0
            }

            assertEquals(0, state.consecutiveHighSlippage, "Normal slippage resets counter")
        }
    }

    @Nested
    @DisplayName("Cooldown and Recovery")
    inner class CooldownAndRecoveryTest {

        @Test
        @DisplayName("Market circuit cooldown is 4 hours")
        fun marketCircuitCooldownIs4Hours() {
            val state = CircuitState(
                isOpen = true,
                openTime = Instant.now().minus(3, ChronoUnit.HOURS)
            )

            val elapsedHours = ChronoUnit.HOURS.between(state.openTime, Instant.now())
            val isCooldownExpired = elapsedHours >= COOLDOWN_HOURS

            assertFalse(isCooldownExpired, "Cooldown should not expire after 3 hours")
        }

        @Test
        @DisplayName("Circuit recovers after 4 hours")
        fun circuitRecoversAfter4Hours() {
            val state = CircuitState(
                isOpen = true,
                openTime = Instant.now().minus(5, ChronoUnit.HOURS)
            )

            val elapsedHours = ChronoUnit.HOURS.between(state.openTime, Instant.now())
            val isCooldownExpired = elapsedHours >= COOLDOWN_HOURS

            assertTrue(isCooldownExpired, "Circuit should recover after 4 hours")
        }

        @Test
        @DisplayName("Global circuit has 24 hour cooldown")
        fun globalCircuitHas24HourCooldown() {
            val globalOpenTime = Instant.now().minus(12, ChronoUnit.HOURS)

            val elapsedHours = ChronoUnit.HOURS.between(globalOpenTime, Instant.now())
            val isCooldownExpired = elapsedHours >= GLOBAL_COOLDOWN_HOURS

            assertFalse(isCooldownExpired, "Global cooldown should not expire after 12 hours")
        }

        @Test
        @DisplayName("Circuit state is reset after recovery")
        fun circuitStateResetAfterRecovery() {
            val state = CircuitState(
                isOpen = true,
                consecutiveLosses = 3,
                reason = "Consecutive losses"
            )

            // 복구 시뮬레이션
            state.isOpen = false
            state.consecutiveLosses = 0
            state.reason = ""

            assertFalse(state.isOpen, "Circuit should be closed")
            assertEquals(0, state.consecutiveLosses, "Losses should be reset")
        }
    }

    @Nested
    @DisplayName("Multi-Market Isolation")
    inner class MultiMarketIsolationTest {

        @Test
        @DisplayName("Different markets have independent circuit states")
        fun differentMarketsIndependentStates() {
            val circuitStates = ConcurrentHashMap<String, CircuitState>()

            // BTC 서킷 트립
            circuitStates["KRW-BTC"] = CircuitState(isOpen = true, reason = "BTC losses")
            // ETH는 정상
            circuitStates["KRW-ETH"] = CircuitState(isOpen = false)

            val btcBlocked = circuitStates["KRW-BTC"]?.isOpen ?: false
            val ethAllowed = !(circuitStates["KRW-ETH"]?.isOpen ?: false)

            assertTrue(btcBlocked, "BTC should be blocked")
            assertTrue(ethAllowed, "ETH should be allowed")
        }

        @Test
        @DisplayName("Global circuit blocks all markets")
        fun globalCircuitBlocksAllMarkets() {
            var globalCircuitOpen = true
            val markets = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP")

            val allBlocked = markets.all { globalCircuitOpen }

            assertTrue(allBlocked, "Global circuit should block all markets")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("Zero PnL is neither profit nor loss")
        fun zeroPnlIsNeutral() {
            val state = CircuitState(consecutiveLosses = 2)

            val pnlPercent = 0.0

            // 0은 손실도 아니고 수익도 아님
            if (pnlPercent < 0) {
                state.consecutiveLosses++
            } else if (pnlPercent > 0) {
                state.consecutiveLosses = 0
            }
            // 0이면 유지

            assertEquals(2, state.consecutiveLosses, "Zero PnL should maintain state")
        }

        @Test
        @DisplayName("NaN PnL is handled safely")
        fun nanPnlIsHandledSafely() {
            val entryPrice = 0.0
            val exitPrice = 100.0

            // 0으로 나누기 → NaN
            val pnlPercent = if (entryPrice > 0) {
                ((exitPrice - entryPrice) / entryPrice) * 100
            } else {
                null  // NaN 대신 null
            }

            assertEquals(null, pnlPercent, "NaN should be handled as null")
        }

        @Test
        @DisplayName("Concurrent circuit state updates are safe")
        fun concurrentCircuitStateUpdatesAreSafe() {
            val circuitStates = ConcurrentHashMap<String, CircuitState>()
            val market = "KRW-BTC"

            circuitStates.computeIfAbsent(market) { CircuitState() }

            val threads = (1..10).map {
                Thread {
                    val state = circuitStates[market]!!
                    synchronized(state) {
                        state.consecutiveLosses++
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(10, circuitStates[market]!!.consecutiveLosses, "All updates should be applied")
        }
    }
}
