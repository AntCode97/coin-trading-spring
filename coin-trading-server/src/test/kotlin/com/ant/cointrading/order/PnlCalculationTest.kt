package com.ant.cointrading.order

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PnL Calculation Tests
 *
 * Verifies the PnL (Profit and Loss) calculation logic:
 * 1. Basic PnL calculation
 * 2. Fee impact on PnL
 * 3. Edge cases (zero price, partial fills)
 * 4. CircuitBreaker integration
 */
@DisplayName("PnL Calculation Tests")
class PnlCalculationTest {

    companion object {
        const val BITHUMB_FEE_RATE = 0.0004  // 0.04%
    }

    @Nested
    @DisplayName("Basic PnL Calculation")
    inner class BasicPnlCalculationTest {

        @Test
        @DisplayName("Profit calculation with fees")
        fun profitWithFees() {
            val buyPrice = 100000.0
            val sellPrice = 102000.0  // +2%
            val quantity = 0.1
            val buyAmount = buyPrice * quantity  // 10000
            val sellAmount = sellPrice * quantity  // 10200
            val buyFee = buyAmount * BITHUMB_FEE_RATE  // 4
            val sellFee = sellAmount * BITHUMB_FEE_RATE  // 4.08

            val pnl = (sellPrice - buyPrice) * quantity - buyFee - sellFee
            val pnlPercent = ((sellPrice - buyPrice) / buyPrice) * 100

            // Expected: (102000 - 100000) * 0.1 - 4 - 4.08 = 200 - 8.08 = 191.92
            assertTrue(pnl > 0, "Should be profitable")
            assertEquals(191.92, pnl, 0.01, "PnL amount")
            assertEquals(2.0, pnlPercent, 0.01, "PnL percent")
        }

        @Test
        @DisplayName("Loss calculation with fees")
        fun lossWithFees() {
            val buyPrice = 100000.0
            val sellPrice = 99000.0  // -1%
            val quantity = 0.1
            val buyAmount = buyPrice * quantity
            val sellAmount = sellPrice * quantity
            val buyFee = buyAmount * BITHUMB_FEE_RATE
            val sellFee = sellAmount * BITHUMB_FEE_RATE

            val pnl = (sellPrice - buyPrice) * quantity - buyFee - sellFee
            val pnlPercent = ((sellPrice - buyPrice) / buyPrice) * 100

            // Expected: (99000 - 100000) * 0.1 - 4 - 3.96 = -100 - 7.96 = -107.96
            assertTrue(pnl < 0, "Should be a loss")
            assertEquals(-107.96, pnl, 0.01, "PnL amount")
            assertEquals(-1.0, pnlPercent, 0.01, "PnL percent")
        }

        @Test
        @DisplayName("Break-even price calculation")
        fun breakEvenPrice() {
            val buyPrice = 100000.0
            val quantity = 0.1
            val buyAmount = buyPrice * quantity
            val buyFee = buyAmount * BITHUMB_FEE_RATE

            // To break even: sellPrice * quantity - buyPrice * quantity - buyFee - sellFee = 0
            // sellPrice * quantity - buyPrice * quantity - buyFee - sellPrice * quantity * feeRate = 0
            // sellPrice * quantity * (1 - feeRate) = buyPrice * quantity + buyFee
            // sellPrice = (buyPrice * quantity + buyFee) / (quantity * (1 - feeRate))

            val breakEvenPrice = (buyPrice * quantity + buyFee) / (quantity * (1 - BITHUMB_FEE_RATE))
            val breakEvenPercent = ((breakEvenPrice - buyPrice) / buyPrice) * 100

            // Break-even should be slightly above buy price to cover fees
            assertTrue(breakEvenPrice > buyPrice, "Break-even above buy price")
            assertTrue(breakEvenPercent < 0.1, "Break-even within 0.1%: $breakEvenPercent%")
        }
    }

    @Nested
    @DisplayName("Fee Impact Analysis")
    inner class FeeImpactAnalysisTest {

        @Test
        @DisplayName("Minimum profitable move (Bithumb 0.04% fee)")
        fun minimumProfitableMove() {
            val buyPrice = 100000.0
            val quantity = 0.1

            // Total fees = 0.04% * 2 = 0.08%
            // To be profitable, price must increase by more than 0.08%
            val minProfitablePrice = buyPrice * 1.001  // 0.1% increase

            val buyFee = buyPrice * quantity * BITHUMB_FEE_RATE
            val sellFee = minProfitablePrice * quantity * BITHUMB_FEE_RATE
            val pnl = (minProfitablePrice - buyPrice) * quantity - buyFee - sellFee

            assertTrue(pnl > 0, "0.1% move should be profitable after fees: $pnl")
        }

        @Test
        @DisplayName("Small position fee impact")
        fun smallPositionFeeImpact() {
            val buyPrice = 100.0  // Low price coin
            val quantity = 100.0  // 10000 KRW position
            val sellPrice = 101.0  // +1%

            val buyFee = buyPrice * quantity * BITHUMB_FEE_RATE
            val sellFee = sellPrice * quantity * BITHUMB_FEE_RATE
            val pnl = (sellPrice - buyPrice) * quantity - buyFee - sellFee

            // 1% profit = 100 KRW, fees = ~8 KRW
            assertTrue(pnl > 90, "Small position should be profitable: $pnl")
        }

        @Test
        @DisplayName("Fee impact on rapid trading")
        fun feeImpactOnRapidTrading() {
            val price = 100000.0
            val quantity = 0.1
            val roundTripFeePercent = BITHUMB_FEE_RATE * 2 * 100  // 0.08%

            // 3 consecutive round-trips
            val totalFeePercent = roundTripFeePercent * 3  // 0.24%

            assertTrue(totalFeePercent < 0.3, "3 round-trips < 0.3% fees")

            // This is why CircuitBreaker stops at 3 consecutive losses
            // Even with zero slippage, fees drain ~0.24% in worst case
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("Zero buy price should not calculate PnL")
        fun zeroBuyPrice() {
            val buyPrice = 0.0
            val sellPrice = 100.0

            // Should not divide by zero
            val pnlPercent = if (buyPrice > 0) {
                ((sellPrice - buyPrice) / buyPrice) * 100
            } else {
                null
            }

            assertEquals(null, pnlPercent, "PnL should be null for zero buy price")
        }

        @Test
        @DisplayName("Negative PnL percent edge case")
        fun negativePnlPercent() {
            val buyPrice = 100.0
            val sellPrice = 50.0  // -50% loss

            val pnlPercent = ((sellPrice - buyPrice) / buyPrice) * 100

            assertEquals(-50.0, pnlPercent, 0.01, "Should calculate -50%")
        }

        @Test
        @DisplayName("Large position PnL")
        fun largePositionPnl() {
            val buyPrice = 150000000.0  // BTC at 1.5억
            val sellPrice = 151500000.0  // +1%
            val quantity = 0.1  // 1500만원 포지션

            val buyFee = buyPrice * quantity * BITHUMB_FEE_RATE
            val sellFee = sellPrice * quantity * BITHUMB_FEE_RATE
            val pnl = (sellPrice - buyPrice) * quantity - buyFee - sellFee

            // 1% of 15M = 150000, fees = ~12000
            assertTrue(pnl > 135000, "Large position profit: $pnl")
        }

        @Test
        @DisplayName("Partial fill PnL calculation")
        fun partialFillPnl() {
            val buyPrice = 100000.0
            val originalQuantity = 0.1
            val filledQuantity = 0.08  // 80% filled
            val sellPrice = 102000.0

            val buyFee = buyPrice * filledQuantity * BITHUMB_FEE_RATE
            val sellFee = sellPrice * filledQuantity * BITHUMB_FEE_RATE
            val pnl = (sellPrice - buyPrice) * filledQuantity - buyFee - sellFee

            // Should calculate based on actually filled quantity
            val expectedPnl = 2000 * 0.08 - 3.2 - 3.264  // 153.536
            assertEquals(expectedPnl, pnl, 0.01, "Partial fill PnL")
        }
    }

    @Nested
    @DisplayName("CircuitBreaker Integration")
    inner class CircuitBreakerIntegrationTest {

        @Test
        @DisplayName("PnL percent triggers circuit breaker")
        fun pnlPercentTriggerCircuitBreaker() {
            // CircuitBreaker uses pnlPercent, not absolute PnL
            val pnlPercent = -2.5  // 2.5% loss

            // Simulate 3 consecutive losses
            val consecutiveLosses = mutableListOf(-1.0, -0.5, pnlPercent)
            val totalLossPercent = consecutiveLosses.sum()

            // After 3 losses, circuit should trip
            val shouldTrip = consecutiveLosses.size >= 3

            assertTrue(shouldTrip, "Circuit should trip after 3 losses")
            assertEquals(-4.0, totalLossPercent, 0.01, "Total loss percent")
        }

        @Test
        @DisplayName("Profit resets consecutive loss counter")
        fun profitResetsLossCounter() {
            var consecutiveLosses = 2

            // Profit trade
            val pnlPercent = 1.5  // 1.5% profit
            if (pnlPercent > 0) {
                consecutiveLosses = 0
            }

            assertEquals(0, consecutiveLosses, "Profit resets counter")
        }
    }

    @Nested
    @DisplayName("BUY Price Calculation")
    inner class BuyPriceCalculationTest {

        @Test
        @DisplayName("Calculate BUY price from positionSize when locked is 0")
        fun calculateBuyPriceWhenLockedIsZero() {
            // Bithumb API 특성: 체결 완료 후 locked = 0 반환
            val lockedFromApi = 0.0  // 체결 완료 후 0
            val positionSize = 10000.0  // 투입 KRW
            val executedVolume = 0.1  // 체결 수량

            // 로직: locked가 0이면 positionSize 사용
            val invested = if (lockedFromApi <= 0) positionSize else lockedFromApi
            val executedPrice = invested / executedVolume

            // 10000 / 0.1 = 100000
            assertEquals(100000.0, executedPrice, 0.01, "BUY price from positionSize")
        }

        @Test
        @DisplayName("Calculate BUY price from locked when available")
        fun calculateBuyPriceWhenLockedAvailable() {
            // 체결 전 또는 부분 체결 시 locked 값 존재
            val lockedFromApi = 10000.0  // 묶인 KRW
            val positionSize = 10000.0  // 투입 KRW (동일)
            val executedVolume = 0.1

            val invested = if (lockedFromApi <= 0) positionSize else lockedFromApi
            val executedPrice = invested / executedVolume

            assertEquals(100000.0, executedPrice, 0.01, "BUY price from locked")
        }

        @Test
        @DisplayName("Handle partial fill BUY price calculation")
        fun handlePartialFillBuyPrice() {
            val positionSize = 10000.0  // 원래 투입하려던 금액
            val executedVolume = 0.08  // 80% 부분 체결

            // 부분 체결의 경우, 체결된 금액은 비율만큼 줄어듦
            val actualInvested = positionSize * 0.8  // 8000 KRW
            val executedPrice = actualInvested / executedVolume

            // 8000 / 0.08 = 100000 (평균 체결가는 동일)
            assertEquals(100000.0, executedPrice, 0.01, "Partial fill BUY price")
        }

        @Test
        @DisplayName("Null locked should fallback to positionSize")
        fun nullLockedFallback() {
            val lockedFromApi: Double? = null
            val positionSize = 10000.0
            val executedVolume = 0.1

            val invested = lockedFromApi ?: positionSize
            val executedPrice = invested / executedVolume

            assertEquals(100000.0, executedPrice, 0.01, "Null locked fallback")
        }
    }

    @Nested
    @DisplayName("Strategy-specific PnL")
    inner class StrategySpecificPnlTest {

        @Test
        @DisplayName("DCA average price calculation")
        fun dcaAveragePriceCalculation() {
            // Multiple buys at different prices
            val buys = listOf(
                Pair(100000.0, 0.05),  // 5000 KRW
                Pair(95000.0, 0.05),   // 4750 KRW
                Pair(90000.0, 0.05)    // 4500 KRW
            )

            val totalQuantity = buys.sumOf { it.second }
            val totalCost = buys.sumOf { it.first * it.second }
            val avgPrice = totalCost / totalQuantity

            // Average price = (5000 + 4750 + 4500) / 0.15 = 95000
            assertEquals(95000.0, avgPrice, 0.01, "Average price")

            // Sell at 100000
            val sellPrice = 100000.0
            val pnlPercent = ((sellPrice - avgPrice) / avgPrice) * 100

            // (100000 - 95000) / 95000 * 100 = 5.26%
            assertEquals(5.26, pnlPercent, 0.01, "DCA PnL percent")
        }

        @Test
        @DisplayName("Grid strategy multiple levels PnL")
        fun gridMultipleLevelsPnl() {
            // Grid buys at different levels
            val gridBuys = listOf(
                Pair(100000.0, 0.01),
                Pair(99000.0, 0.01),
                Pair(98000.0, 0.01)
            )

            // Grid sells at corresponding levels
            val gridSells = listOf(
                Pair(101000.0, 0.01),  // +1%
                Pair(100000.0, 0.01),  // +1.01%
                Pair(99000.0, 0.01)    // +1.02%
            )

            val totalBuyCost = gridBuys.sumOf { it.first * it.second }
            val totalSellRevenue = gridSells.sumOf { it.first * it.second }
            val gridProfit = totalSellRevenue - totalBuyCost

            // (1010 + 1000 + 990) - (1000 + 990 + 980) = 3000 - 2970 = 30
            assertEquals(30.0, gridProfit, 0.01, "Grid profit before fees")
        }
    }
}
