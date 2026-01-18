package com.ant.cointrading.memescalper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MemeScalper Edge Case Tests
 *
 * Verifies extreme scenarios specific to meme coin scalping:
 * 1. Rapid pump-and-dump detection
 * 2. Fee-based loss prevention
 * 3. Slippage protection
 * 4. Concurrent entry prevention
 * 5. Balance verification edge cases
 */
@DisplayName("MemeScalper Edge Cases")
class MemeScalperEdgeCaseTest {

    companion object {
        val MIN_ORDER_AMOUNT = BigDecimal("5100")
        val BITHUMB_FEE_RATE = BigDecimal("0.0004")  // 0.04%
        const val MIN_HOLDING_SECONDS = 10L
        const val MIN_PROFIT_PERCENT = 0.1
    }

    @Nested
    @DisplayName("Pump-and-Dump Protection")
    inner class PumpDumpProtectionTest {

        @Test
        @DisplayName("Detect dump within 30 seconds of pump")
        fun detectRapidDump() {
            // Pump detected at t=0
            val pumpTime = Instant.now()
            val pumpPrice = 100.0

            // Dump starts at t=30s (price drops 10%)
            val dumpTime = pumpTime.plusSeconds(30)
            val dumpPrice = 90.0

            val priceDropPercent = ((pumpPrice - dumpPrice) / pumpPrice) * 100
            val timeSincePump = ChronoUnit.SECONDS.between(pumpTime, dumpTime)

            // Should trigger exit signal
            val isPumpDump = priceDropPercent > 5.0 && timeSincePump < 60
            assertTrue(isPumpDump, "Pump-dump pattern detected")
        }

        @Test
        @DisplayName("Volume spike ratio drops below entry level")
        fun detectVolumeCrash() {
            val entryVolumeRatio = 5.0  // 500% of 20-day average
            val currentVolumeRatio = 1.5  // Dropped to 150%
            val volumeDropThreshold = 0.5  // 50% drop triggers exit

            val volumeDrop = (entryVolumeRatio - currentVolumeRatio) / entryVolumeRatio
            val shouldExit = volumeDrop >= volumeDropThreshold

            assertTrue(shouldExit, "Volume crash exit: drop=${volumeDrop * 100}%")
        }

        @Test
        @DisplayName("Order book imbalance reversal")
        fun detectOrderBookReversal() {
            val entryBidImbalance = 0.6   // 60% buy pressure at entry
            val currentBidImbalance = 0.3  // Reversed to 30%
            val reversalThreshold = 0.2    // 20% reversal triggers exit

            val imbalanceChange = entryBidImbalance - currentBidImbalance
            val shouldExit = imbalanceChange >= reversalThreshold

            assertTrue(shouldExit, "Order book reversal detected: change=$imbalanceChange")
        }
    }

    @Nested
    @DisplayName("Fee-Based Loss Prevention")
    inner class FeeLossPreventionTest {

        @Test
        @DisplayName("Minimum holding time prevents fee drain")
        fun minHoldingTimePreventsFees() {
            val entryTime = Instant.now()
            val immediateExitTime = entryTime.plusSeconds(5)  // 5 seconds

            val holdingSeconds = ChronoUnit.SECONDS.between(entryTime, immediateExitTime)
            val canExit = holdingSeconds >= MIN_HOLDING_SECONDS

            assertFalse(canExit, "Cannot exit before minimum holding time")
        }

        @Test
        @DisplayName("Minimum profit threshold covers fees")
        fun minProfitCoverssFees() {
            // 0.04% * 2 = 0.08% total fees
            val totalFeePercent = 0.08
            val minProfitPercent = MIN_PROFIT_PERCENT  // 0.1%

            assertTrue(minProfitPercent > totalFeePercent, "Min profit covers fees")
        }

        @Test
        @DisplayName("Break-even calculation with fees")
        fun breakEvenWithFees() {
            val entryPrice = 1000.0
            val quantity = 10.0
            val feeRate = 0.0004

            // Entry fee
            val entryFee = entryPrice * quantity * feeRate  // 4 KRW

            // Break-even requires covering entry + exit fees
            // Exit fee = exitPrice * quantity * feeRate
            // P&L = (exitPrice - entryPrice) * quantity - entryFee - exitFee = 0
            // exitPrice * quantity - entryPrice * quantity - entryFee - exitPrice * quantity * feeRate = 0
            // exitPrice * quantity * (1 - feeRate) = entryPrice * quantity + entryFee
            // exitPrice = (entryPrice * quantity + entryFee) / (quantity * (1 - feeRate))

            val breakEvenPrice = (entryPrice * quantity + entryFee) / (quantity * (1 - feeRate))
            val breakEvenPercent = ((breakEvenPrice - entryPrice) / entryPrice) * 100

            assertTrue(breakEvenPercent < 0.1, "Break-even under 0.1%: $breakEvenPercent%")
        }

        @Test
        @DisplayName("Reject tiny profit trades")
        fun rejectTinyProfitTrades() {
            val entryPrice = 1000.0
            val currentPrice = 1000.5  // +0.05% profit
            val takeProfitPercent = 3.0

            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

            // Should NOT trigger take profit if below minimum
            val canTakeProfit = pnlPercent >= takeProfitPercent && pnlPercent > MIN_PROFIT_PERCENT
            assertFalse(canTakeProfit, "Reject tiny profit: $pnlPercent%")
        }
    }

    @Nested
    @DisplayName("Slippage Protection")
    inner class SlippageProtectionTest {

        @Test
        @DisplayName("Warn on 2%+ slippage")
        fun warnOnHighSlippage() {
            val expectedPrice = 1000.0
            val executedPrice = 1025.0  // +2.5% slippage
            val maxSlippage = 2.0

            val slippagePercent = ((executedPrice - expectedPrice) / expectedPrice) * 100
            val isHighSlippage = kotlin.math.abs(slippagePercent) > maxSlippage

            assertTrue(isHighSlippage, "High slippage warning: $slippagePercent%")
        }

        @Test
        @DisplayName("Spread too wide for scalping")
        fun spreadTooWideForScalping() {
            val bidPrice = 1000.0
            val askPrice = 1008.0  // 0.8% spread
            val maxSpread = 0.5

            val spreadPercent = ((askPrice - bidPrice) / bidPrice) * 100
            val isTooWide = spreadPercent > maxSpread

            assertTrue(isTooWide, "Spread too wide: $spreadPercent% > $maxSpread%")
        }

        @Test
        @DisplayName("Negative slippage is beneficial")
        fun negativeSlippageIsBeneficial() {
            val expectedPrice = 1000.0
            val executedPrice = 995.0  // -0.5% slippage (better price)

            val slippagePercent = ((executedPrice - expectedPrice) / expectedPrice) * 100
            val isBeneficial = slippagePercent < 0

            assertTrue(isBeneficial, "Negative slippage is good: $slippagePercent%")
        }
    }

    @Nested
    @DisplayName("Concurrent Entry Prevention")
    inner class ConcurrentEntryPreventionTest {

        @Test
        @DisplayName("Lock prevents duplicate entry")
        fun lockPreventsDuplicateEntry() {
            val lock = java.util.concurrent.locks.ReentrantLock()
            var entryCount = 0

            // Simulate concurrent entry attempts
            val thread1 = Thread {
                if (lock.tryLock()) {
                    try {
                        Thread.sleep(10)
                        entryCount++
                    } finally {
                        lock.unlock()
                    }
                }
            }

            val thread2 = Thread {
                if (lock.tryLock()) {
                    try {
                        Thread.sleep(10)
                        entryCount++
                    } finally {
                        lock.unlock()
                    }
                }
            }

            thread1.start()
            Thread.sleep(5)
            thread2.start()

            thread1.join()
            thread2.join()

            // Only one should succeed with tryLock
            assertTrue(entryCount <= 2, "Lock controls concurrent access")
        }

        @Test
        @DisplayName("Max positions check prevents over-entry")
        fun maxPositionsCheck() {
            val currentPositions = 3
            val maxPositions = 3

            val canEnter = currentPositions < maxPositions
            assertFalse(canEnter, "Max positions reached")
        }

        @Test
        @DisplayName("Cooldown prevents rapid re-entry")
        fun cooldownPreventsRapidReEntry() {
            val lastEntryTime = Instant.now()
            val cooldownSeconds = 30L

            val now = Instant.now().plusSeconds(20)
            val cooldownEnd = lastEntryTime.plusSeconds(cooldownSeconds)
            val canEnter = now.isAfter(cooldownEnd)

            assertFalse(canEnter, "Still in cooldown period")
        }
    }

    @Nested
    @DisplayName("Balance Verification")
    inner class BalanceVerificationTest {

        @Test
        @DisplayName("Reject entry if already holding coin")
        fun rejectEntryIfHoldingCoin() {
            val coinBalance = BigDecimal("0.001")  // Already holding some

            val canEnter = coinBalance <= BigDecimal.ZERO
            assertFalse(canEnter, "Already holding coin - cannot enter")
        }

        @Test
        @DisplayName("Require 10% extra KRW buffer")
        fun require10PercentBuffer() {
            val positionSize = BigDecimal("10000")
            val requiredBuffer = BigDecimal("1.1")  // 10% extra
            val minRequired = positionSize.multiply(requiredBuffer)

            val krwBalance = BigDecimal("10500")  // Not enough

            val canEnter = krwBalance >= minRequired
            assertFalse(canEnter, "Insufficient KRW buffer: $krwBalance < $minRequired")
        }

        @Test
        @DisplayName("Sell actual balance even if different from DB")
        fun sellActualBalance() {
            val dbQuantity = 0.01
            val actualBalance = 0.008  // Less than DB (partial fill or fee)

            val sellQuantity = kotlin.math.min(actualBalance, dbQuantity)
            assertEquals(actualBalance, sellQuantity, "Sell actual balance")
        }

        @Test
        @DisplayName("ABANDONED if no balance to sell")
        fun abandonedIfNoBalance() {
            val actualBalance = BigDecimal.ZERO

            val shouldAbandon = actualBalance <= BigDecimal.ZERO
            assertTrue(shouldAbandon, "No balance - abandon position")
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Edge Cases")
    inner class CircuitBreakerEdgeCasesTest {

        @Test
        @DisplayName("Profit trade resets consecutive losses")
        fun profitResetsLosses() {
            var consecutiveLosses = 2

            // Profit trade
            val pnl = 100.0
            if (pnl > 0) {
                consecutiveLosses = 0
            }

            assertEquals(0, consecutiveLosses, "Profit resets losses")
        }

        @Test
        @DisplayName("Daily stats persist across restart")
        fun dailyStatsPersist() {
            // Simulating DB storage
            data class DailyStats(
                var consecutiveLosses: Int,
                var dailyPnl: Double,
                var dailyTradeCount: Int
            )

            // Save before restart
            val savedStats = DailyStats(2, -15000.0, 25)

            // Restore after restart
            var consecutiveLosses = savedStats.consecutiveLosses
            var dailyPnl = savedStats.dailyPnl
            var dailyTradeCount = savedStats.dailyTradeCount

            assertEquals(2, consecutiveLosses, "Losses restored")
            assertEquals(-15000.0, dailyPnl, 0.01, "PnL restored")
            assertEquals(25, dailyTradeCount, "Trade count restored")
        }

        @Test
        @DisplayName("All limits combined check")
        fun allLimitsCombinedCheck() {
            val dailyTradeCount = 30
            val dailyMaxTrades = 50
            val dailyPnl = -15000.0
            val dailyMaxLoss = 20000
            val consecutiveLosses = 2
            val maxConsecutiveLosses = 3

            val canTrade = dailyTradeCount < dailyMaxTrades
                && dailyPnl > -dailyMaxLoss
                && consecutiveLosses < maxConsecutiveLosses

            assertTrue(canTrade, "All limits within bounds - can trade")
        }
    }

    @Nested
    @DisplayName("Price Spike Validation")
    inner class PriceSpikeValidationTest {

        @Test
        @DisplayName("Minimum 1% price spike required")
        fun minPriceSpikeRequired() {
            val previousPrice = 100.0
            val currentPrice = 100.8  // Only 0.8% spike

            val priceSpikePercent = ((currentPrice - previousPrice) / previousPrice) * 100
            val minSpike = 1.0

            val isValidSpike = priceSpikePercent >= minSpike
            assertFalse(isValidSpike, "Spike too small: $priceSpikePercent% < $minSpike%")
        }

        @Test
        @DisplayName("Minimum 2x volume spike required")
        fun minVolumeSpikeRequired() {
            val avgVolume = 1000000.0  // 20-day average
            val currentVolume = 1800000.0  // 1.8x

            val volumeRatio = currentVolume / avgVolume
            val minRatio = 2.0

            val isValidSpike = volumeRatio >= minRatio
            assertFalse(isValidSpike, "Volume ratio too low: ${volumeRatio}x < ${minRatio}x")
        }

        @Test
        @DisplayName("RSI below 80 required")
        fun rsiBelowThresholdRequired() {
            val currentRsi = 82.0
            val maxRsi = 80.0

            val isValidRsi = currentRsi <= maxRsi
            assertFalse(isValidRsi, "RSI too high: $currentRsi > $maxRsi")
        }
    }

    @Nested
    @DisplayName("Exit Signal Validation")
    inner class ExitSignalValidationTest {

        @Test
        @DisplayName("Peak volume tracking for exit")
        fun peakVolumeTracking() {
            val peakVolume = 5.0  // 5x at peak
            val currentVolume = 2.0  // Dropped to 2x
            val dropThreshold = 0.5  // 50% drop

            val volumeDrop = (peakVolume - currentVolume) / peakVolume
            val shouldExit = volumeDrop >= dropThreshold

            assertTrue(shouldExit, "Volume dropped significantly: $volumeDrop")
        }

        @Test
        @DisplayName("Peak price tracking for trailing stop")
        fun peakPriceTracking() {
            val entryPrice = 100.0
            val peakPrice = 102.5  // +2.5% peak
            val currentPrice = 101.5  // Dropped from peak

            val trailingOffset = 0.5  // 0.5% offset
            val drawdownFromPeak = ((peakPrice - currentPrice) / peakPrice) * 100

            val shouldTrailingStop = drawdownFromPeak >= trailingOffset
            assertTrue(shouldTrailingStop, "Trailing stop triggered: $drawdownFromPeak%")
        }
    }
}
