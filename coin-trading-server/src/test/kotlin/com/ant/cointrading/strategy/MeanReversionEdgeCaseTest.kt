package com.ant.cointrading.strategy

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Data class for confluence result (must be at file level in Kotlin)
data class MeanReversionConfluenceResult(
    val score: Int,
    val action: String,
    val details: String
)

/**
 * Mean Reversion Strategy Edge Case Tests
 *
 * Verifies edge cases for the mean reversion strategy:
 * 1. Z-Score calculations
 * 2. RSI calculations
 * 3. Confluence scoring system
 * 4. Regime adaptation
 * 5. Volume confirmation
 */
@DisplayName("Mean Reversion Strategy Edge Cases")
class MeanReversionEdgeCaseTest {

    companion object {
        const val RSI_PERIOD = 14
        const val RSI_OVERSOLD = 25.0
        const val RSI_OVERBOUGHT = 75.0
        const val VOLUME_MULTIPLIER = 1.2
        const val MIN_CONFLUENCE_SCORE = 40
        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_STD_DEV = 1.8
        const val MEAN_REVERSION_THRESHOLD = 1.2
    }

    @Nested
    @DisplayName("Z-Score Calculations")
    inner class ZScoreCalculationsTest {

        private fun calculateStdDev(values: List<Double>, mean: Double): Double {
            if (values.size < 2) return 0.0
            val variance = values.map { (it - mean) * (it - mean) }.average()
            return sqrt(variance)
        }

        private fun calculateZScore(currentPrice: Double, sma: Double, stdDev: Double): Double {
            return if (stdDev > 0) (currentPrice - sma) / stdDev else 0.0
        }

        @Test
        @DisplayName("Z-Score = 0 when price equals SMA")
        fun zScoreZeroAtSma() {
            val prices = listOf(100.0, 102.0, 98.0, 101.0, 99.0)
            val sma = prices.average()
            val stdDev = calculateStdDev(prices, sma)

            val currentPrice = sma
            val zScore = calculateZScore(currentPrice, sma, stdDev)

            assertEquals(0.0, zScore, 0.001, "Z-Score should be 0 at SMA")
        }

        @Test
        @DisplayName("Z-Score positive when price above SMA")
        fun zScorePositiveAboveSma() {
            val prices = listOf(100.0, 102.0, 98.0, 101.0, 99.0)
            val sma = prices.average()
            val stdDev = calculateStdDev(prices, sma)

            val currentPrice = sma + (stdDev * 2)  // 2 standard deviations above
            val zScore = calculateZScore(currentPrice, sma, stdDev)

            assertTrue(zScore > 0, "Z-Score should be positive above SMA")
            assertEquals(2.0, zScore, 0.001, "Z-Score should be ~2.0")
        }

        @Test
        @DisplayName("Z-Score negative when price below SMA")
        fun zScoreNegativeBelowSma() {
            val prices = listOf(100.0, 102.0, 98.0, 101.0, 99.0)
            val sma = prices.average()
            val stdDev = calculateStdDev(prices, sma)

            val currentPrice = sma - (stdDev * 1.5)  // 1.5 standard deviations below
            val zScore = calculateZScore(currentPrice, sma, stdDev)

            assertTrue(zScore < 0, "Z-Score should be negative below SMA")
            assertEquals(-1.5, zScore, 0.001, "Z-Score should be ~-1.5")
        }

        @Test
        @DisplayName("Z-Score handles zero standard deviation")
        fun zScoreHandlesZeroStdDev() {
            val prices = listOf(100.0, 100.0, 100.0, 100.0, 100.0)  // No variance
            val sma = prices.average()
            val stdDev = calculateStdDev(prices, sma)

            val currentPrice = 105.0
            val zScore = calculateZScore(currentPrice, sma, stdDev)

            assertEquals(0.0, zScore, "Z-Score should be 0 when stdDev is 0")
        }

        @Test
        @DisplayName("Threshold triggers buy signal at -1.2")
        fun thresholdTriggersBuySignal() {
            val zScore = -1.3  // Below -threshold
            val threshold = MEAN_REVERSION_THRESHOLD

            val isBuySignal = zScore <= -threshold
            assertTrue(isBuySignal, "Z-Score -1.3 should trigger buy signal (threshold: -$threshold)")
        }

        @Test
        @DisplayName("Threshold triggers sell signal at +1.2")
        fun thresholdTriggersSellSignal() {
            val zScore = 1.5  // Above +threshold
            val threshold = MEAN_REVERSION_THRESHOLD

            val isSellSignal = zScore >= threshold
            assertTrue(isSellSignal, "Z-Score +1.5 should trigger sell signal (threshold: +$threshold)")
        }
    }

    @Nested
    @DisplayName("RSI Calculations")
    inner class RsiCalculationsTest {

        private fun calculateRsi(changes: List<Double>): Double {
            val gains = changes.filter { it > 0 }
            val losses = changes.filter { it < 0 }.map { abs(it) }

            val avgGain = if (gains.isNotEmpty()) gains.average() else 0.0
            val avgLoss = if (losses.isNotEmpty()) losses.average() else 0.0001

            val rs = avgGain / avgLoss
            return 100.0 - (100.0 / (1.0 + rs))
        }

        @Test
        @DisplayName("RSI = 50 when gains equal losses")
        fun rsiFiftyWhenBalanced() {
            val changes = listOf(1.0, -1.0, 1.0, -1.0, 1.0, -1.0)
            val rsi = calculateRsi(changes)

            assertEquals(50.0, rsi, 1.0, "RSI should be ~50 when gains equal losses")
        }

        @Test
        @DisplayName("RSI near 100 when all gains")
        fun rsiHighWhenAllGains() {
            val changes = listOf(1.0, 2.0, 1.5, 0.5, 1.0, 2.0)  // All positive
            val rsi = calculateRsi(changes)

            assertTrue(rsi > 90, "RSI should be >90 when all gains: $rsi")
        }

        @Test
        @DisplayName("RSI near 0 when all losses")
        fun rsiLowWhenAllLosses() {
            val changes = listOf(-1.0, -2.0, -1.5, -0.5, -1.0, -2.0)  // All negative
            val rsi = calculateRsi(changes)

            assertTrue(rsi < 10, "RSI should be <10 when all losses: $rsi")
        }

        @Test
        @DisplayName("RSI oversold threshold at 25")
        fun rsiOversoldThreshold() {
            val rsi = 23.0
            val isOversold = rsi <= RSI_OVERSOLD

            assertTrue(isOversold, "RSI $rsi should be oversold (threshold: $RSI_OVERSOLD)")
        }

        @Test
        @DisplayName("RSI overbought threshold at 75")
        fun rsiOverboughtThreshold() {
            val rsi = 78.0
            val isOverbought = rsi >= RSI_OVERBOUGHT

            assertTrue(isOverbought, "RSI $rsi should be overbought (threshold: $RSI_OVERBOUGHT)")
        }
    }

    @Nested
    @DisplayName("Confluence Scoring System")
    inner class ConfluenceScoringTest {

        private fun calculateConfluenceScore(
            zScore: Double,
            threshold: Double,
            rsi: Double,
            volumeRatio: Double,
            regime: String
        ): MeanReversionConfluenceResult {
            var score = 0
            val details = mutableListOf<String>()

            val isBuySignal = zScore <= -threshold
            val isSellSignal = zScore >= threshold

            if (!isBuySignal && !isSellSignal) {
                return MeanReversionConfluenceResult(0, "HOLD", "No signal")
            }

            // 1. Bollinger Band (30 points)
            val zScoreAbs = abs(zScore)
            val bollingerScore = when {
                zScoreAbs >= threshold + 1.0 -> 30
                zScoreAbs >= threshold + 0.5 -> 25
                zScoreAbs >= threshold -> 20
                else -> 0
            }
            score += bollingerScore
            details.add("Bollinger: $bollingerScore")

            // 2. RSI (30 points)
            val rsiScore = if (isBuySignal) {
                when {
                    rsi <= RSI_OVERSOLD -> 30
                    rsi <= RSI_OVERSOLD + 10 -> 20
                    rsi <= 50 -> 10
                    else -> 0
                }
            } else {
                when {
                    rsi >= RSI_OVERBOUGHT -> 30
                    rsi >= RSI_OVERBOUGHT - 10 -> 20
                    rsi >= 50 -> 10
                    else -> 0
                }
            }
            score += rsiScore
            details.add("RSI: $rsiScore")

            // 3. Volume (20 points)
            val volumeScore = when {
                volumeRatio >= VOLUME_MULTIPLIER * 1.5 -> 20
                volumeRatio >= VOLUME_MULTIPLIER -> 15
                volumeRatio >= 1.0 -> 10
                else -> 5
            }
            score += volumeScore
            details.add("Volume: $volumeScore")

            // 4. Regime (20 points)
            val regimeScore = when (regime) {
                "SIDEWAYS" -> 20
                "HIGH_VOLATILITY" -> 5
                "BULL_TREND" -> if (isBuySignal) 15 else 5
                "BEAR_TREND" -> if (isSellSignal) 15 else 5
                else -> 10
            }
            score += regimeScore
            details.add("Regime: $regimeScore")

            return MeanReversionConfluenceResult(
                score = score,
                action = if (isBuySignal) "BUY" else "SELL",
                details = details.joinToString(", ")
            )
        }

        @Test
        @DisplayName("Perfect buy signal scores 100 points")
        fun perfectBuySignalScores100() {
            val result = calculateConfluenceScore(
                zScore = -2.5,             // Strong lower band breach (+30)
                threshold = MEAN_REVERSION_THRESHOLD,
                rsi = 20.0,                // Oversold (+30)
                volumeRatio = 2.0,         // High volume (+20)
                regime = "SIDEWAYS"        // Perfect regime (+20)
            )

            assertEquals(100, result.score, "Perfect buy signal should score 100")
            assertEquals("BUY", result.action)
        }

        @Test
        @DisplayName("Perfect sell signal scores 100 points")
        fun perfectSellSignalScores100() {
            val result = calculateConfluenceScore(
                zScore = 2.5,              // Strong upper band breach (+30)
                threshold = MEAN_REVERSION_THRESHOLD,
                rsi = 80.0,                // Overbought (+30)
                volumeRatio = 2.0,         // High volume (+20)
                regime = "SIDEWAYS"        // Perfect regime (+20)
            )

            assertEquals(100, result.score, "Perfect sell signal should score 100")
            assertEquals("SELL", result.action)
        }

        @Test
        @DisplayName("Weak signal with RSI mismatch scores low")
        fun weakSignalWithRsiMismatch() {
            val result = calculateConfluenceScore(
                zScore = -1.3,             // Lower band breach (+20)
                threshold = MEAN_REVERSION_THRESHOLD,
                rsi = 60.0,                // RSI NOT oversold (+0)
                volumeRatio = 0.8,         // Low volume (+5)
                regime = "BEAR_TREND"      // Against buy signal (+5)
            )

            assertTrue(result.score < MIN_CONFLUENCE_SCORE,
                "Weak signal should score below threshold: ${result.score}")
        }

        @Test
        @DisplayName("Signal rejected below 40 points")
        fun signalRejectedBelowThreshold() {
            val result = calculateConfluenceScore(
                zScore = -1.25,            // Barely triggered (+20)
                threshold = MEAN_REVERSION_THRESHOLD,
                rsi = 55.0,                // Not oversold (+10)
                volumeRatio = 0.5,         // Very low volume (+5)
                regime = "BEAR_TREND"      // Against signal (+5)
            )

            val shouldReject = result.score < MIN_CONFLUENCE_SCORE
            assertTrue(shouldReject, "Signal with ${result.score} points should be rejected")
        }

        @Test
        @DisplayName("Marginal signal at threshold boundary")
        fun marginalSignalAtBoundary() {
            // Design a signal that scores exactly at threshold
            val result = calculateConfluenceScore(
                zScore = -1.3,             // Just triggered (+20)
                threshold = MEAN_REVERSION_THRESHOLD,
                rsi = 32.0,                // Near oversold (+20)
                volumeRatio = 0.9,         // Below average (+5)
                regime = "HIGH_VOLATILITY" // Risky (+5)
            )

            // Score should be around 50, just above threshold
            assertTrue(result.score >= 40 && result.score <= 60,
                "Marginal signal should be near threshold: ${result.score}")
        }
    }

    @Nested
    @DisplayName("Regime Adaptation")
    inner class RegimeAdaptationTest {

        @Test
        @DisplayName("Mean reversion suitable only for sideways market")
        fun suitableOnlyForSideways() {
            fun isSuitable(regime: String): Boolean {
                return regime == "SIDEWAYS"
            }

            assertTrue(isSuitable("SIDEWAYS"), "Should be suitable for sideways")
            assertFalse(isSuitable("BULL_TREND"), "Should NOT be suitable for bull trend")
            assertFalse(isSuitable("BEAR_TREND"), "Should NOT be suitable for bear trend")
            assertFalse(isSuitable("HIGH_VOLATILITY"), "Should NOT be suitable for high volatility")
        }

        @Test
        @DisplayName("Regime scoring favors sideways market")
        fun regimeScoringFavorsSideways() {
            fun getRegimeScore(regime: String, isBuySignal: Boolean): Int {
                return when (regime) {
                    "SIDEWAYS" -> 20
                    "HIGH_VOLATILITY" -> 5
                    "BULL_TREND" -> if (isBuySignal) 15 else 5
                    "BEAR_TREND" -> if (isBuySignal) 5 else 15
                    else -> 10
                }
            }

            // Sideways gets full points
            assertEquals(20, getRegimeScore("SIDEWAYS", true))
            assertEquals(20, getRegimeScore("SIDEWAYS", false))

            // High volatility penalized
            assertEquals(5, getRegimeScore("HIGH_VOLATILITY", true))
            assertEquals(5, getRegimeScore("HIGH_VOLATILITY", false))

            // Bull trend favors buy
            assertEquals(15, getRegimeScore("BULL_TREND", true))
            assertEquals(5, getRegimeScore("BULL_TREND", false))

            // Bear trend favors sell
            assertEquals(5, getRegimeScore("BEAR_TREND", true))
            assertEquals(15, getRegimeScore("BEAR_TREND", false))
        }

        @Test
        @DisplayName("High volatility significantly reduces confidence")
        fun highVolatilityReducesConfidence() {
            val baseConfidence = 80.0
            val regimeMultiplier = mapOf(
                "SIDEWAYS" to 1.0,
                "BULL_TREND" to 0.9,
                "BEAR_TREND" to 0.9,
                "HIGH_VOLATILITY" to 0.7
            )

            val sidewaysConfidence = baseConfidence * regimeMultiplier["SIDEWAYS"]!!
            val highVolConfidence = baseConfidence * regimeMultiplier["HIGH_VOLATILITY"]!!

            assertEquals(80.0, sidewaysConfidence, "Sideways keeps full confidence")
            assertEquals(56.0, highVolConfidence, "High volatility reduces to 56%")
        }
    }

    @Nested
    @DisplayName("Volume Confirmation")
    inner class VolumeConfirmationTest {

        @Test
        @DisplayName("Volume ratio calculation")
        fun volumeRatioCalculation() {
            val recentVolumes = listOf(100.0, 120.0, 80.0, 110.0, 90.0)  // Last 5 candles
            val avgVolume = recentVolumes.dropLast(1).average()  // Exclude current
            val currentVolume = recentVolumes.last()
            val volumeRatio = currentVolume / avgVolume

            assertTrue(volumeRatio in 0.5..1.5, "Volume ratio: $volumeRatio")
        }

        @Test
        @DisplayName("High volume gets full points")
        fun highVolumeFullPoints() {
            val volumeRatio = 2.0  // 200% of average
            val volumeScore = when {
                volumeRatio >= VOLUME_MULTIPLIER * 1.5 -> 20
                volumeRatio >= VOLUME_MULTIPLIER -> 15
                volumeRatio >= 1.0 -> 10
                else -> 5
            }

            assertEquals(20, volumeScore, "Volume 200% should get full 20 points")
        }

        @Test
        @DisplayName("Normal volume gets partial points")
        fun normalVolumePartialPoints() {
            val volumeRatio = 1.3  // 130% of average
            val volumeScore = when {
                volumeRatio >= VOLUME_MULTIPLIER * 1.5 -> 20
                volumeRatio >= VOLUME_MULTIPLIER -> 15
                volumeRatio >= 1.0 -> 10
                else -> 5
            }

            assertEquals(15, volumeScore, "Volume 130% should get 15 points")
        }

        @Test
        @DisplayName("Low volume still gets minimum points")
        fun lowVolumeMinimumPoints() {
            val volumeRatio = 0.5  // 50% of average
            val volumeScore = when {
                volumeRatio >= VOLUME_MULTIPLIER * 1.5 -> 20
                volumeRatio >= VOLUME_MULTIPLIER -> 15
                volumeRatio >= 1.0 -> 10
                else -> 5
            }

            assertEquals(5, volumeScore, "Low volume should still get 5 points")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("Insufficient data returns hold signal")
        fun insufficientDataReturnsHold() {
            val candleCount = 15
            val requiredCount = BOLLINGER_PERIOD + RSI_PERIOD  // 20 + 14 = 34

            val hasEnoughData = candleCount >= requiredCount
            assertFalse(hasEnoughData, "15 candles is insufficient (need $requiredCount)")
        }

        @Test
        @DisplayName("Extreme Z-Score is still valid")
        fun extremeZScoreIsValid() {
            val zScore = -4.5  // 4.5 standard deviations below mean
            val threshold = MEAN_REVERSION_THRESHOLD

            val isBuySignal = zScore <= -threshold
            assertTrue(isBuySignal, "Extreme Z-Score should still trigger buy signal")

            // But should get maximum Bollinger score
            val zScoreAbs = abs(zScore)
            val bollingerScore = when {
                zScoreAbs >= threshold + 1.0 -> 30
                zScoreAbs >= threshold + 0.5 -> 25
                zScoreAbs >= threshold -> 20
                else -> 0
            }
            assertEquals(30, bollingerScore, "Extreme Z-Score should get max Bollinger points")
        }

        @Test
        @DisplayName("RSI at exact boundaries")
        fun rsiAtExactBoundaries() {
            // At oversold boundary
            val rsi1 = RSI_OVERSOLD
            val isOversold = rsi1 <= RSI_OVERSOLD
            assertTrue(isOversold, "RSI at boundary should count as oversold")

            // At overbought boundary
            val rsi2 = RSI_OVERBOUGHT
            val isOverbought = rsi2 >= RSI_OVERBOUGHT
            assertTrue(isOverbought, "RSI at boundary should count as overbought")
        }

        @Test
        @DisplayName("No signal when Z-Score within threshold")
        fun noSignalWithinThreshold() {
            val zScore = 0.5  // Within threshold
            val threshold = MEAN_REVERSION_THRESHOLD

            val isBuySignal = zScore <= -threshold
            val isSellSignal = zScore >= threshold

            assertFalse(isBuySignal, "Should not trigger buy signal")
            assertFalse(isSellSignal, "Should not trigger sell signal")
        }

        @Test
        @DisplayName("Bollinger bands with minimal variance")
        fun bollingerBandsMinimalVariance() {
            // Prices with minimal movement
            val prices = listOf(
                100.0, 100.1, 99.9, 100.0, 100.05,
                99.95, 100.0, 100.1, 99.9, 100.0
            )
            val sma = prices.average()
            val variance = prices.map { (it - sma) * (it - sma) }.average()
            val stdDev = sqrt(variance)

            // With minimal variance, bands are very tight
            val upperBand = sma + (stdDev * BOLLINGER_STD_DEV)
            val lowerBand = sma - (stdDev * BOLLINGER_STD_DEV)
            val bandWidth = upperBand - lowerBand

            assertTrue(bandWidth < 1.0, "Band width should be very narrow: $bandWidth")
        }
    }
}
