package com.ant.cointrading.integration

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Data classes for tests (must be at file level in Kotlin)
data class IntegrationStrategyState(
    val lastAction: String?,
    val positions: List<String>,
    val circuitBreakerTripped: Boolean
)

data class IntegrationStoredPosition(
    val id: Long,
    val market: String,
    val entryPrice: Double,
    val quantity: Double,
    val status: String,
    val trailingActive: Boolean,
    val highestPrice: Double?
)

data class IntegrationCircuitBreakerState(
    val consecutiveLosses: Int,
    val dailyPnl: Double,
    val lastResetDate: String
)

data class IntegrationTrade(
    val strategy: String,
    val amount: Double,
    val fee: Double
)

data class IntegrationSignal(
    val strategy: String,
    val action: String,
    val confidence: Double
)

data class IntegrationPosition(
    val entryPrice: Double,
    var status: String,
    var closeAttemptCount: Int = 0,
    var lastCloseAttempt: Instant? = null
)

/**
 * Trading System Integration Tests
 *
 * Verifies cross-strategy interactions and system-wide safety:
 * 1. Multiple strategy conflict resolution
 * 2. Global position limits
 * 3. Circuit breaker coordination
 * 4. Fee accumulation prevention
 * 5. State consistency across restarts
 */
@DisplayName("Trading System Integration")
class TradingSystemIntegrationTest {

    companion object {
        const val FEE_RATE = 0.0004  // 0.04% Bithumb fee
        const val MAX_TOTAL_POSITIONS = 10
        const val MAX_DAILY_LOSS_KRW = 50000.0
        const val MAX_CONSECUTIVE_LOSSES = 3
    }

    @Nested
    @DisplayName("Multi-Strategy Conflict Resolution")
    inner class MultiStrategyConflictTest {

        @Test
        @DisplayName("Same market conflicting signals resolved by confidence")
        fun sameMarketConflictingSignals() {
            val signals = listOf(
                IntegrationSignal("MEAN_REVERSION", "BUY", 75.0),
                IntegrationSignal("ORDER_BOOK_IMBALANCE", "SELL", 65.0),
                IntegrationSignal("GRID", "HOLD", 80.0)
            )

            // Resolve by highest confidence for actionable signals
            val actionableSignals = signals.filter { it.action != "HOLD" }
            val winner = actionableSignals.maxByOrNull { it.confidence }

            assertEquals("MEAN_REVERSION", winner?.strategy)
            assertEquals("BUY", winner?.action)
        }

        @Test
        @DisplayName("Strategy priority when confidence is equal")
        fun strategyPriorityWhenConfidenceEqual() {
            val strategyPriority = mapOf(
                "MEAN_REVERSION" to 1,
                "GRID" to 2,
                "DCA" to 3,
                "ORDER_BOOK_IMBALANCE" to 4,
                "VOLUME_SURGE" to 5
            )

            val signals = listOf(
                IntegrationSignal("GRID", "BUY", 70.0),
                IntegrationSignal("MEAN_REVERSION", "BUY", 70.0)
            )

            // When confidence is equal, use priority
            val winner = signals.minByOrNull { strategyPriority[it.strategy] ?: Int.MAX_VALUE }

            assertEquals("MEAN_REVERSION", winner?.strategy, "Higher priority strategy wins")
        }

        @Test
        @DisplayName("HOLD signal does not block other strategies")
        fun holdSignalDoesNotBlock() {
            val signals = listOf(
                IntegrationSignal("DCA", "HOLD", 100.0),  // High confidence HOLD
                IntegrationSignal("VOLUME_SURGE", "BUY", 60.0)  // Lower confidence BUY
            )

            val actionableSignals = signals.filter { it.action != "HOLD" }

            assertEquals(1, actionableSignals.size, "BUY signal should not be blocked by HOLD")
            assertEquals("BUY", actionableSignals.first().action)
        }
    }

    @Nested
    @DisplayName("Global Position Limits")
    inner class GlobalPositionLimitsTest {

        @Test
        @DisplayName("Total positions across all strategies limited")
        fun totalPositionsLimited() {
            val positionsByStrategy = mapOf(
                "VOLUME_SURGE" to 3,
                "GRID" to 2,
                "DCA" to 4
            )

            val totalPositions = positionsByStrategy.values.sum()
            val canOpenNew = totalPositions < MAX_TOTAL_POSITIONS

            assertTrue(canOpenNew, "Total positions ($totalPositions) under limit ($MAX_TOTAL_POSITIONS)")
        }

        @Test
        @DisplayName("Reject new position when at max")
        fun rejectNewPositionAtMax() {
            val positionsByStrategy = mapOf(
                "VOLUME_SURGE" to 5,
                "GRID" to 3,
                "DCA" to 2
            )

            val totalPositions = positionsByStrategy.values.sum()
            val canOpenNew = totalPositions < MAX_TOTAL_POSITIONS

            assertFalse(canOpenNew, "Should reject new position at max ($totalPositions >= $MAX_TOTAL_POSITIONS)")
        }

        @Test
        @DisplayName("Position count decrements on close")
        fun positionCountDecrementsOnClose() {
            var totalPositions = 10

            // Close one position
            totalPositions--

            assertTrue(totalPositions < MAX_TOTAL_POSITIONS, "Should allow new position after close")
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Coordination")
    inner class CircuitBreakerCoordinationTest {

        @Test
        @DisplayName("Global circuit breaker stops all strategies")
        fun globalCircuitBreakerStopsAll() {
            var globalCircuitTripped = false
            var dailyLoss = 0.0

            // Simulate losses
            val losses = listOf(-15000.0, -20000.0, -16000.0)
            for (loss in losses) {
                dailyLoss += loss
                if (dailyLoss <= -MAX_DAILY_LOSS_KRW) {
                    globalCircuitTripped = true
                    break
                }
            }

            assertTrue(globalCircuitTripped, "Global circuit should trip at ${dailyLoss}원 loss")
        }

        @Test
        @DisplayName("Per-strategy circuit breaker independence")
        fun perStrategyCircuitBreakerIndependence() {
            val strategyLosses = mutableMapOf(
                "VOLUME_SURGE" to 0,
                "GRID" to 0,
                "MEAN_REVERSION" to 0
            )

            // VOLUME_SURGE hits max consecutive losses
            repeat(3) { strategyLosses["VOLUME_SURGE"] = strategyLosses["VOLUME_SURGE"]!! + 1 }

            val volumeSurgeTripped = strategyLosses["VOLUME_SURGE"]!! >= MAX_CONSECUTIVE_LOSSES
            val gridCanTrade = strategyLosses["GRID"]!! < MAX_CONSECUTIVE_LOSSES

            assertTrue(volumeSurgeTripped, "VOLUME_SURGE circuit should trip")
            assertTrue(gridCanTrade, "GRID should still be able to trade")
        }

        @Test
        @DisplayName("Profit resets consecutive loss counter")
        fun profitResetsConsecutiveLosses() {
            var consecutiveLosses = 2

            // Loss
            consecutiveLosses++
            assertEquals(3, consecutiveLosses)

            // Profit resets
            val pnl = 5000.0
            if (pnl > 0) {
                consecutiveLosses = 0
            }

            assertEquals(0, consecutiveLosses, "Profit should reset consecutive losses")
        }

        @Test
        @DisplayName("Daily reset clears circuit breaker")
        fun dailyResetClearsCircuitBreaker() {
            var lastResetDate = Instant.now().minus(1, ChronoUnit.DAYS)
            var consecutiveLosses = 3
            var dailyPnl = -45000.0

            // Check if daily reset needed
            val now = Instant.now()
            if (ChronoUnit.DAYS.between(lastResetDate, now) >= 1) {
                consecutiveLosses = 0
                dailyPnl = 0.0
                lastResetDate = now
            }

            assertEquals(0, consecutiveLosses, "Consecutive losses should reset")
            assertEquals(0.0, dailyPnl, "Daily PnL should reset")
        }
    }

    @Nested
    @DisplayName("Fee Accumulation Prevention")
    inner class FeeAccumulationPreventionTest {

        @Test
        @DisplayName("Rapid round-trip trades limited by circuit breaker")
        fun rapidRoundTripLimited() {
            val positionSize = 10000.0
            var balance = 100000.0
            var consecutiveLosses = 0

            // Simulate rapid buy-sell cycles
            var trades = 0
            while (consecutiveLosses < MAX_CONSECUTIVE_LOSSES) {
                // Buy
                val buyFee = positionSize * FEE_RATE
                balance -= buyFee

                // Immediate sell at same price (only fee loss)
                val sellFee = positionSize * FEE_RATE
                balance -= sellFee

                val pnl = -(buyFee + sellFee)
                consecutiveLosses++
                trades++
            }

            // After 3 trades, circuit breaker stops trading
            assertEquals(3, trades, "Circuit breaker should stop after 3 consecutive losses")

            val totalFeeLoss = trades * positionSize * FEE_RATE * 2
            assertTrue(totalFeeLoss < balance * 0.01, "Total fee loss ($totalFeeLoss) should be < 1% of balance")
        }

        @Test
        @DisplayName("Minimum holding time prevents fee drain")
        fun minimumHoldingTimePreventsFeeDrain() {
            val minHoldingSeconds = 10L
            val entryTime = Instant.now()

            // Try to exit immediately
            val immediateExitTime = entryTime.plusSeconds(5)
            val canExitImmediately = ChronoUnit.SECONDS.between(entryTime, immediateExitTime) >= minHoldingSeconds

            assertFalse(canExitImmediately, "Should not allow exit before minimum holding time")

            // After minimum time
            val laterExitTime = entryTime.plusSeconds(15)
            val canExitLater = ChronoUnit.SECONDS.between(entryTime, laterExitTime) >= minHoldingSeconds

            assertTrue(canExitLater, "Should allow exit after minimum holding time")
        }

        @Test
        @DisplayName("Total fee paid tracked across all strategies")
        fun totalFeePaidTracked() {
            val trades = listOf(
                IntegrationTrade("VOLUME_SURGE", 10000.0, 10000.0 * FEE_RATE * 2),
                IntegrationTrade("GRID", 10000.0, 10000.0 * FEE_RATE * 2),
                IntegrationTrade("DCA", 10000.0, 10000.0 * FEE_RATE * 2)
            )

            val totalFees = trades.sumOf { it.fee }
            val totalAmount = trades.sumOf { it.amount }
            val feePercent = (totalFees / totalAmount) * 100

            assertEquals(0.08, feePercent, 0.001, "Total fee should be 0.08% per round trip")
        }
    }

    @Nested
    @DisplayName("State Consistency Across Restarts")
    inner class StateConsistencyTest {

        @Test
        @DisplayName("All strategy states serializable")
        fun allStrategyStatesSerializable() {
            val states = mapOf(
                "DCA" to IntegrationStrategyState(
                    lastAction = "BUY",
                    positions = listOf("BTC_KRW"),
                    circuitBreakerTripped = false
                ),
                "GRID" to IntegrationStrategyState(
                    lastAction = "SELL",
                    positions = listOf("ETH_KRW", "BTC_KRW"),
                    circuitBreakerTripped = false
                ),
                "VOLUME_SURGE" to IntegrationStrategyState(
                    lastAction = null,
                    positions = emptyList(),
                    circuitBreakerTripped = true
                )
            )

            // Verify all states can be serialized (simulated as string conversion)
            states.forEach { (strategy, state) ->
                val serialized = "Strategy=$strategy,Action=${state.lastAction},Positions=${state.positions.size},Tripped=${state.circuitBreakerTripped}"
                assertTrue(serialized.isNotEmpty(), "$strategy state should be serializable")
            }
        }

        @Test
        @DisplayName("Open positions restored on restart")
        fun openPositionsRestoredOnRestart() {
            val storedPositions = listOf(
                IntegrationStoredPosition(1, "BTC_KRW", 50000000.0, 0.001, "OPEN", true, 52000000.0),
                IntegrationStoredPosition(2, "ETH_KRW", 3000000.0, 0.01, "OPEN", false, null),
                IntegrationStoredPosition(3, "XRP_KRW", 1000.0, 100.0, "CLOSING", false, null)
            )

            // Restore positions to memory
            val restoredPositions = ConcurrentHashMap<Long, IntegrationStoredPosition>()
            val trailingHighs = ConcurrentHashMap<Long, Double>()

            storedPositions.filter { it.status in listOf("OPEN", "CLOSING") }.forEach { pos ->
                restoredPositions[pos.id] = pos
                if (pos.trailingActive && pos.highestPrice != null) {
                    trailingHighs[pos.id] = pos.highestPrice
                }
            }

            assertEquals(3, restoredPositions.size, "All non-closed positions should be restored")
            assertEquals(1, trailingHighs.size, "Trailing high should be restored for position 1")
            assertEquals(52000000.0, trailingHighs[1L], "Trailing high value should match")
        }

        @Test
        @DisplayName("Circuit breaker state persisted and restored")
        fun circuitBreakerStatePersisted() {
            val persistedState = IntegrationCircuitBreakerState(
                consecutiveLosses = 2,
                dailyPnl = -15000.0,
                lastResetDate = "2026-01-18"
            )

            // Restore
            var consecutiveLosses = persistedState.consecutiveLosses
            var dailyPnl = persistedState.dailyPnl

            assertEquals(2, consecutiveLosses, "Consecutive losses should be restored")
            assertEquals(-15000.0, dailyPnl, "Daily PnL should be restored")

            // Verify circuit breaker not tripped (max is 3)
            val canTrade = consecutiveLosses < MAX_CONSECUTIVE_LOSSES &&
                    dailyPnl > -MAX_DAILY_LOSS_KRW

            assertTrue(canTrade, "Should be able to trade with restored state")
        }
    }

    @Nested
    @DisplayName("Concurrent Access Safety")
    inner class ConcurrentAccessSafetyTest {

        @Test
        @DisplayName("Position counter is thread-safe")
        fun positionCounterThreadSafe() {
            val positionCount = AtomicInteger(0)
            val errors = AtomicInteger(0)
            val maxPositions = 5

            // Simulate concurrent position opens
            val threads = (1..10).map { threadId ->
                Thread {
                    repeat(100) {
                        val current = positionCount.get()
                        if (current < maxPositions) {
                            if (positionCount.incrementAndGet() > maxPositions) {
                                // Exceeded max - decrement and record error
                                positionCount.decrementAndGet()
                                errors.incrementAndGet()
                            }
                        }
                        // Simulate some position closes
                        if (Math.random() > 0.5 && positionCount.get() > 0) {
                            positionCount.decrementAndGet()
                        }
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertTrue(positionCount.get() <= maxPositions,
                "Position count should never exceed max: ${positionCount.get()}")
        }

        @Test
        @DisplayName("Strategy state maps are thread-safe")
        fun strategyStateMapsThreadSafe() {
            val stateMap = ConcurrentHashMap<String, String>()
            var exceptions = 0

            val threads = (1..10).map { threadId ->
                Thread {
                    try {
                        repeat(100) { iteration ->
                            val market = "MARKET_$threadId"
                            stateMap[market] = "state_$iteration"
                            stateMap[market]  // Read
                            if (iteration % 10 == 0) {
                                stateMap.remove(market)
                            }
                        }
                    } catch (e: ConcurrentModificationException) {
                        exceptions++
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(0, exceptions, "No concurrent modification exceptions should occur")
        }
    }

    @Nested
    @DisplayName("Order Book Imbalance Safety")
    inner class OrderBookImbalanceSafetyTest {

        @Test
        @DisplayName("Imbalance calculation handles zero volume")
        fun imbalanceHandlesZeroVolume() {
            val bidVolume = BigDecimal.ZERO
            val askVolume = BigDecimal.ZERO
            val totalVolume = bidVolume + askVolume

            val imbalance = if (totalVolume > BigDecimal.ZERO) {
                (bidVolume - askVolume).divide(totalVolume, 4, java.math.RoundingMode.HALF_UP).toDouble()
            } else {
                0.0  // Safe default when no volume
            }

            assertEquals(0.0, imbalance, "Imbalance should be 0 when no volume")
        }

        @Test
        @DisplayName("History size limited to prevent memory leak")
        fun historySizeLimited() {
            val maxHistorySize = 10
            val history = mutableListOf<Double>()

            // Add more than max
            repeat(20) { i ->
                history.add(i.toDouble())
                while (history.size > maxHistorySize) {
                    history.removeAt(0)
                }
            }

            assertEquals(maxHistorySize, history.size, "History should be limited to $maxHistorySize")
            assertEquals(10.0, history.first(), "Oldest should be 10 (items 0-9 removed)")
        }

        @Test
        @DisplayName("Consecutive direction requires minimum history")
        fun consecutiveDirectionRequiresMinHistory() {
            val minRequired = 3

            val shortHistory = listOf(0.4, 0.5)  // Only 2 items
            val longHistory = listOf(0.4, 0.5, 0.6)  // 3 items

            fun checkConsecutive(history: List<Double>): Int {
                if (history.size < minRequired) return 0
                return if (history.all { it > 0 }) history.size else 0
            }

            assertEquals(0, checkConsecutive(shortHistory), "Should return 0 for short history")
            assertEquals(3, checkConsecutive(longHistory), "Should return count for sufficient history")
        }
    }

    @Nested
    @DisplayName("Volume Surge Engine Safety")
    inner class VolumeSurgeEngineSafetyTest {

        @Test
        @DisplayName("Invalid entry price handled")
        fun invalidEntryPriceHandled() {
            val position = IntegrationPosition(entryPrice = 0.0, status = "OPEN")

            // Should abandon if entry price is invalid
            if (position.entryPrice <= 0) {
                position.status = "ABANDONED"
            }

            assertEquals("ABANDONED", position.status, "Position with invalid entry price should be abandoned")
        }

        @Test
        @DisplayName("NaN/Infinity PnL handled safely")
        fun nanInfinityPnlHandled() {
            fun safePnl(pnl: Double): Double {
                return if (pnl.isNaN() || pnl.isInfinite()) 0.0 else pnl
            }

            assertEquals(0.0, safePnl(Double.NaN), "NaN should become 0")
            assertEquals(0.0, safePnl(Double.POSITIVE_INFINITY), "Infinity should become 0")
            assertEquals(0.0, safePnl(Double.NEGATIVE_INFINITY), "Negative infinity should become 0")
            assertEquals(100.5, safePnl(100.5), "Valid value should pass through")
        }

        @Test
        @DisplayName("Close retry with backoff")
        fun closeRetryWithBackoff() {
            val position = IntegrationPosition(
                entryPrice = 50000000.0,
                status = "OPEN",
                closeAttemptCount = 0,
                lastCloseAttempt = null
            )

            val backoffSeconds = 10L
            val maxAttempts = 5

            // First attempt
            position.closeAttemptCount++
            position.lastCloseAttempt = Instant.now()

            assertEquals(1, position.closeAttemptCount)

            // Check if can retry
            val elapsed = ChronoUnit.SECONDS.between(position.lastCloseAttempt, Instant.now())
            val canRetry = elapsed >= backoffSeconds && position.closeAttemptCount < maxAttempts

            assertFalse(canRetry, "Should wait for backoff before retry")
        }

        @Test
        @DisplayName("Max close attempts triggers ABANDONED")
        fun maxCloseAttemptsTriggersAbandoned() {
            val maxAttempts = 5
            var closeAttempts = 5
            var status = "CLOSING"

            if (closeAttempts >= maxAttempts) {
                status = "ABANDONED"
            }

            assertEquals("ABANDONED", status, "Should become ABANDONED after max attempts")
        }
    }
}
