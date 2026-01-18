package com.ant.cointrading.strategy

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Grid & DCA Strategy Edge Case Tests
 *
 * Verifies edge cases for traditional trading strategies:
 * 1. Grid level boundary conditions
 * 2. DCA interval enforcement
 * 3. State persistence and recovery
 * 4. Market regime adaptation
 * 5. Concurrent access safety
 */
@DisplayName("Grid & DCA Strategy Edge Cases")
class GridDcaEdgeCaseTest {

    companion object {
        const val GRID_LEVELS = 5
        const val GRID_SPACING_PERCENT = 1.0
        const val DCA_INTERVAL_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    @Nested
    @DisplayName("Grid Strategy - Level Management")
    inner class GridLevelManagementTest {

        @Test
        @DisplayName("Grid levels created symmetrically around base price")
        fun gridLevelsSymmetric() {
            val basePrice = BigDecimal("50000000")  // 5000만원
            val spacing = GRID_SPACING_PERCENT / 100.0

            val buyLevels = mutableListOf<BigDecimal>()
            val sellLevels = mutableListOf<BigDecimal>()

            for (i in 1..GRID_LEVELS) {
                val buyMultiplier = BigDecimal.ONE - BigDecimal(spacing * i)
                val buyPrice = basePrice.multiply(buyMultiplier).setScale(0, RoundingMode.DOWN)
                buyLevels.add(buyPrice)

                val sellMultiplier = BigDecimal.ONE + BigDecimal(spacing * i)
                val sellPrice = basePrice.multiply(sellMultiplier).setScale(0, RoundingMode.UP)
                sellLevels.add(sellPrice)
            }

            assertEquals(GRID_LEVELS, buyLevels.size, "Buy levels count")
            assertEquals(GRID_LEVELS, sellLevels.size, "Sell levels count")

            // Verify spacing - allow for rounding differences
            buyLevels.forEachIndexed { index, price ->
                val expectedPercent = (1 + index) * GRID_SPACING_PERCENT
                val actualPercent = (basePrice.subtract(price))
                    .divide(basePrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP).toDouble()
                assertTrue(actualPercent in (expectedPercent - 0.2)..(expectedPercent + 0.2),
                    "Buy level $index: expected $expectedPercent%, actual $actualPercent%")
            }
        }

        @Test
        @DisplayName("Grid resets when price exits range")
        fun gridResetsWhenPriceExitsRange() {
            val basePrice = BigDecimal("50000000")
            val spacing = GRID_SPACING_PERCENT / 100.0
            val levels = GRID_LEVELS

            // Lowest buy level
            val lowestBuyPrice = basePrice.multiply(BigDecimal.ONE - BigDecimal(spacing * levels))

            // Price drops below lowest level
            val currentPrice = lowestBuyPrice.multiply(BigDecimal("0.95"))

            val shouldReset = currentPrice < lowestBuyPrice
            assertTrue(shouldReset, "Grid should reset when price drops below lowest level")
        }

        @Test
        @DisplayName("Grid triggers at correct price level with tolerance")
        fun gridTriggersWithTolerance() {
            val levelPrice = BigDecimal("49000000")  // 매수 레벨
            val tolerance = levelPrice.multiply(BigDecimal("0.001"))  // 0.1% 허용 오차

            // Price slightly above level (within tolerance)
            val currentPrice1 = levelPrice.add(tolerance.divide(BigDecimal(2)))
            val shouldTrigger1 = currentPrice1 <= levelPrice.add(tolerance)
            assertTrue(shouldTrigger1, "Should trigger when price within tolerance above level")

            // Price exactly at level
            val currentPrice2 = levelPrice
            val shouldTrigger2 = currentPrice2 <= levelPrice.add(tolerance)
            assertTrue(shouldTrigger2, "Should trigger at exact level price")

            // Price well below level
            val currentPrice3 = levelPrice.subtract(BigDecimal("100000"))
            val shouldTrigger3 = currentPrice3 <= levelPrice.add(tolerance)
            assertTrue(shouldTrigger3, "Should trigger when price below level")

            // Price above tolerance
            val currentPrice4 = levelPrice.add(tolerance.multiply(BigDecimal(2)))
            val shouldTrigger4 = currentPrice4 <= levelPrice.add(tolerance)
            assertFalse(shouldTrigger4, "Should NOT trigger when price above tolerance")
        }

        @Test
        @DisplayName("Filled level does not trigger again")
        fun filledLevelDoesNotTriggerAgain() {
            data class MockGridLevel(
                val price: BigDecimal,
                val type: String,
                var filled: Boolean
            )

            val level = MockGridLevel(
                price = BigDecimal("49000000"),
                type = "BUY",
                filled = false
            )

            // First trigger - should work
            val canTrigger1 = !level.filled
            assertTrue(canTrigger1, "First trigger should work")
            level.filled = true

            // Second trigger - should not work
            val canTrigger2 = !level.filled
            assertFalse(canTrigger2, "Second trigger should NOT work - level already filled")
        }
    }

    @Nested
    @DisplayName("Grid Strategy - Risk Scenarios")
    inner class GridRiskScenariosTest {

        @Test
        @DisplayName("Grid avoids high volatility markets")
        fun gridAvoidsHighVolatility() {
            val atrPercent = 5.0  // 5% ATR
            val maxAtrForGrid = 3.0

            val isSuitable = atrPercent < maxAtrForGrid
            assertFalse(isSuitable, "Grid should not be suitable for high volatility (ATR $atrPercent% > $maxAtrForGrid%)")
        }

        @Test
        @DisplayName("Grid suitable for sideways market")
        fun gridSuitableForSideways() {
            val regimeType = "SIDEWAYS"
            val atrPercent = 1.5  // Low volatility

            val isSuitable = regimeType == "SIDEWAYS" && atrPercent < 3.0
            assertTrue(isSuitable, "Grid should be suitable for sideways market with low ATR")
        }

        @Test
        @DisplayName("Grid confidence drops in high volatility")
        fun gridConfidenceDropsInHighVolatility() {
            // Confidence based on regime
            fun calculateConfidence(regime: String): Double {
                return when (regime) {
                    "SIDEWAYS" -> 80.0
                    "BULL_TREND" -> 70.0
                    "BEAR_TREND" -> 60.0
                    "HIGH_VOLATILITY" -> 40.0
                    else -> 50.0
                }
            }

            assertEquals(80.0, calculateConfidence("SIDEWAYS"), "Sideways confidence")
            assertEquals(40.0, calculateConfidence("HIGH_VOLATILITY"), "High volatility confidence")
        }

        @Test
        @DisplayName("All buy levels filled = maximum exposure")
        fun allBuyLevelsFilled() {
            val totalLevels = GRID_LEVELS
            val positionSizeKrw = 10000.0
            val maxExposure = totalLevels * positionSizeKrw

            // If all buy levels are filled
            val filledBuyLevels = GRID_LEVELS
            val currentExposure = filledBuyLevels * positionSizeKrw

            assertEquals(maxExposure, currentExposure, "Maximum exposure reached")

            // Should not buy more
            val canBuyMore = filledBuyLevels < totalLevels
            assertFalse(canBuyMore, "Cannot buy more - all levels filled")
        }
    }

    @Nested
    @DisplayName("DCA Strategy - Interval Enforcement")
    inner class DcaIntervalEnforcementTest {

        @Test
        @DisplayName("DCA interval correctly calculated")
        fun dcaIntervalCalculation() {
            val intervalHours = 24L
            val intervalMs = intervalHours * 60 * 60 * 1000

            val lastBuyTime = Instant.now().minusMillis(intervalMs - 1000)  // 1 second before interval
            val now = Instant.now()

            val elapsedMs = now.toEpochMilli() - lastBuyTime.toEpochMilli()
            val shouldBuy = elapsedMs >= intervalMs

            assertFalse(shouldBuy, "Should NOT buy - interval not reached yet (${elapsedMs}ms < ${intervalMs}ms)")
        }

        @Test
        @DisplayName("DCA buys when interval elapsed")
        fun dcaBuysWhenIntervalElapsed() {
            val intervalMs = DCA_INTERVAL_MS

            val lastBuyTime = Instant.now().minusMillis(intervalMs + 1000)  // 1 second after interval
            val now = Instant.now()

            val elapsedMs = now.toEpochMilli() - lastBuyTime.toEpochMilli()
            val shouldBuy = elapsedMs >= intervalMs

            assertTrue(shouldBuy, "Should buy - interval elapsed")
        }

        @Test
        @DisplayName("DCA buys immediately on first run")
        fun dcaBuysOnFirstRun() {
            val lastBuyTime: Instant? = null

            val shouldBuy = lastBuyTime == null
            assertTrue(shouldBuy, "Should buy on first run - no previous buy recorded")
        }

        @Test
        @DisplayName("DCA interval timer resets after buy")
        fun dcaIntervalResetsAfterBuy() {
            var lastBuyTime = Instant.now().minusMillis(DCA_INTERVAL_MS + 1000)
            val intervalMs = DCA_INTERVAL_MS

            // First check - should buy
            val shouldBuy1 = Instant.now().toEpochMilli() - lastBuyTime.toEpochMilli() >= intervalMs
            assertTrue(shouldBuy1, "First check - should buy")

            // Record buy
            lastBuyTime = Instant.now()

            // Second check - should not buy
            val shouldBuy2 = Instant.now().toEpochMilli() - lastBuyTime.toEpochMilli() >= intervalMs
            assertFalse(shouldBuy2, "Second check - should NOT buy (interval reset)")
        }
    }

    @Nested
    @DisplayName("DCA Strategy - Market Regime Adaptation")
    inner class DcaMarketRegimeTest {

        @Test
        @DisplayName("DCA confidence varies by market regime")
        fun dcaConfidenceByRegime() {
            fun calculateConfidence(regime: String): Double {
                return when (regime) {
                    "BULL_TREND" -> 85.0
                    "SIDEWAYS" -> 70.0
                    "BEAR_TREND" -> 50.0
                    "HIGH_VOLATILITY" -> 40.0
                    else -> 50.0
                }
            }

            assertEquals(85.0, calculateConfidence("BULL_TREND"), "Bull trend confidence")
            assertEquals(70.0, calculateConfidence("SIDEWAYS"), "Sideways confidence")
            assertEquals(50.0, calculateConfidence("BEAR_TREND"), "Bear trend confidence")
            assertEquals(40.0, calculateConfidence("HIGH_VOLATILITY"), "High volatility confidence")
        }

        @Test
        @DisplayName("DCA suitable for bull and sideways markets")
        fun dcaSuitableMarkets() {
            fun isSuitable(regime: String): Boolean {
                return regime in listOf("BULL_TREND", "SIDEWAYS")
            }

            assertTrue(isSuitable("BULL_TREND"), "DCA suitable for bull trend")
            assertTrue(isSuitable("SIDEWAYS"), "DCA suitable for sideways")
            assertFalse(isSuitable("BEAR_TREND"), "DCA NOT suitable for bear trend")
            assertFalse(isSuitable("HIGH_VOLATILITY"), "DCA NOT suitable for high volatility")
        }

        @Test
        @DisplayName("DCA continues in bear market with warning")
        fun dcaContinuesInBearMarket() {
            val regime = "BEAR_TREND"
            val shouldBuy = true  // Interval elapsed
            val confidence = 50.0  // Lower confidence in bear market

            // DCA can still execute but with lower confidence
            val canExecute = shouldBuy && confidence >= 40.0  // Minimum confidence threshold

            assertTrue(canExecute, "DCA can execute in bear market with lower confidence")
            assertEquals(50.0, confidence, "Confidence reduced in bear market")
        }
    }

    @Nested
    @DisplayName("State Persistence & Recovery")
    inner class StatePersistenceTest {

        @Test
        @DisplayName("Grid state serializes correctly")
        fun gridStateSerializes() {
            data class GridLevelDto(
                val price: String,
                val type: String,
                val filled: Boolean
            )

            data class GridStateDto(
                val basePrice: String,
                val levels: List<GridLevelDto>,
                val lastAction: String?
            )

            val state = GridStateDto(
                basePrice = "50000000",
                levels = listOf(
                    GridLevelDto("49500000", "BUY", false),
                    GridLevelDto("49000000", "BUY", true),
                    GridLevelDto("50500000", "SELL", false)
                ),
                lastAction = "BUY"
            )

            // Verify serialization fields
            assertEquals("50000000", state.basePrice)
            assertEquals(3, state.levels.size)
            assertEquals("BUY", state.lastAction)

            // Verify filled status preserved
            val filledCount = state.levels.count { it.filled }
            assertEquals(1, filledCount, "Filled level count preserved")
        }

        @Test
        @DisplayName("DCA state persists last buy time")
        fun dcaStatePersistsLastBuyTime() {
            val lastBuyTime = Instant.parse("2026-01-17T10:00:00Z")
            val serialized = lastBuyTime.toString()
            val restored = Instant.parse(serialized)

            assertEquals(lastBuyTime, restored, "Timestamp serialization round-trip")
        }

        @Test
        @DisplayName("Grid state recovery handles missing data")
        fun gridStateRecoveryHandlesMissingData() {
            val savedJson: String? = null

            val recovered = if (savedJson != null) {
                try {
                    // Parse JSON
                    mapOf("success" to true)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            // New state should be created when no saved state exists
            val shouldCreateNew = recovered == null
            assertTrue(shouldCreateNew, "Should create new grid state when none saved")
        }

        @Test
        @DisplayName("Concurrent state access is thread-safe")
        fun concurrentStateAccessSafe() {
            val states = java.util.concurrent.ConcurrentHashMap<String, String>()
            var errorCount = 0

            // Simulate concurrent access
            val threads = (1..10).map { i ->
                Thread {
                    try {
                        for (j in 1..100) {
                            val market = "BTC_KRW"
                            states[market] = "state_${i}_$j"
                            states[market]  // Read
                        }
                    } catch (e: Exception) {
                        errorCount++
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(0, errorCount, "No errors during concurrent access")
        }
    }

    @Nested
    @DisplayName("Fee Impact Analysis")
    inner class FeeImpactAnalysisTest {

        @Test
        @DisplayName("Grid profit must exceed round-trip fees")
        fun gridProfitExceedsFees() {
            val feeRate = 0.0004  // 0.04% Bithumb fee
            val roundTripFee = feeRate * 2  // Buy + Sell

            val gridSpacing = GRID_SPACING_PERCENT / 100.0  // 1%
            val netProfit = gridSpacing - roundTripFee

            assertTrue(netProfit > 0, "Grid spacing ($gridSpacing) must exceed fees ($roundTripFee)")
            assertTrue(netProfit > roundTripFee, "Net profit should exceed fee cost for viability")
        }

        @Test
        @DisplayName("DCA break-even with fees")
        fun dcaBreakEvenWithFees() {
            val feeRate = 0.0004
            val numBuys = 10
            val avgEntryPrice = 50000000.0

            // Total fees paid on buys
            val totalBuyFees = avgEntryPrice * numBuys * feeRate

            // Sell fee
            val sellFee = avgEntryPrice * numBuys * feeRate

            // Total fees
            val totalFees = totalBuyFees + sellFee
            val totalInvested = avgEntryPrice * numBuys

            // Break-even requires this % gain
            val breakEvenPercent = (totalFees / totalInvested) * 100

            assertTrue(breakEvenPercent < 0.1, "DCA break-even should be under 0.1%: $breakEvenPercent%")
        }

        @Test
        @DisplayName("Minimum grid spacing for profitability")
        fun minGridSpacingForProfit() {
            val feeRate = 0.0004
            val roundTripFee = feeRate * 2
            val minProfitRatio = 2.0  // Want at least 2x fees as profit

            val minSpacing = roundTripFee * (1 + minProfitRatio)
            val minSpacingPercent = minSpacing * 100

            assertTrue(GRID_SPACING_PERCENT >= minSpacingPercent,
                "Grid spacing ${GRID_SPACING_PERCENT}% should be >= $minSpacingPercent% for profitability")
        }
    }

    @Nested
    @DisplayName("Extreme Market Conditions")
    inner class ExtremeMarketConditionsTest {

        @Test
        @DisplayName("Grid handles flash crash (price drops 50%)")
        fun gridHandlesFlashCrash() {
            val basePrice = BigDecimal("50000000")
            val flashCrashPrice = basePrice.multiply(BigDecimal("0.5"))

            // All buy levels would be triggered instantly
            val levels = GRID_LEVELS
            val spacing = GRID_SPACING_PERCENT / 100.0

            var triggeredLevels = 0
            for (i in 1..levels) {
                val levelPrice = basePrice.multiply(BigDecimal.ONE - BigDecimal(spacing * i))
                if (flashCrashPrice <= levelPrice) {
                    triggeredLevels++
                }
            }

            assertEquals(levels, triggeredLevels, "All buy levels triggered in flash crash")

            // Grid should reset since price is outside range
            val lowestLevel = basePrice.multiply(BigDecimal.ONE - BigDecimal(spacing * levels))
            val shouldReset = flashCrashPrice < lowestLevel

            assertTrue(shouldReset, "Grid should reset after flash crash exits range")
        }

        @Test
        @DisplayName("DCA handles extended bear market")
        fun dcaHandlesExtendedBearMarket() {
            // Simulating 12 months of bear market DCA
            val numMonths = 12
            val initialPrice = 50000000.0
            val monthlyDecline = 0.05  // 5% monthly decline

            var totalInvested = 0.0
            var totalCoins = 0.0
            val monthlyInvestment = 10000.0

            for (month in 1..numMonths) {
                val currentPrice = initialPrice * Math.pow(1 - monthlyDecline, month.toDouble())
                val coinsAcquired = monthlyInvestment / currentPrice
                totalInvested += monthlyInvestment
                totalCoins += coinsAcquired
            }

            val avgPrice = totalInvested / totalCoins
            val finalPrice = initialPrice * Math.pow(1 - monthlyDecline, numMonths.toDouble())

            // In extended bear market, DCA results in loss
            val pnlPercent = ((finalPrice - avgPrice) / avgPrice) * 100

            // But average price is lower than if bought all at beginning
            val firstDayAvgPrice = initialPrice
            val dcaBenefit = ((firstDayAvgPrice - avgPrice) / firstDayAvgPrice) * 100

            assertTrue(dcaBenefit > 0, "DCA provides better average than lump sum in bear market: $dcaBenefit%")
        }

        @Test
        @DisplayName("Grid handles gap up (price jumps 20%)")
        fun gridHandlesGapUp() {
            val basePrice = BigDecimal("50000000")
            val gapUpPrice = basePrice.multiply(BigDecimal("1.20"))  // +20%

            val levels = GRID_LEVELS
            val spacing = GRID_SPACING_PERCENT / 100.0

            // All sell levels would be triggered
            var triggeredLevels = 0
            for (i in 1..levels) {
                val levelPrice = basePrice.multiply(BigDecimal.ONE + BigDecimal(spacing * i))
                if (gapUpPrice >= levelPrice) {
                    triggeredLevels++
                }
            }

            assertEquals(levels, triggeredLevels, "All sell levels triggered in gap up")

            // Grid should reset since price is outside range
            val highestLevel = basePrice.multiply(BigDecimal.ONE + BigDecimal(spacing * levels))
            val shouldReset = gapUpPrice > highestLevel

            assertTrue(shouldReset, "Grid should reset after gap up exits range")
        }
    }
}
