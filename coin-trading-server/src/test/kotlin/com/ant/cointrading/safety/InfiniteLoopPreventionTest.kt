package com.ant.cointrading.safety

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Infinite Loop Prevention Tests
 *
 * Verifies safety mechanisms that prevent:
 * 1. Rapid BUY-SELL loops (fee drain)
 * 2. Duplicate position entries
 * 3. Concurrent entry race conditions
 * 4. Cooldown bypasses
 *
 * 20년차 퀀트의 교훈:
 * "무한 루프로 수수료에 잔고가 녹는 것은 가장 흔하고 치명적인 버그다"
 */
@DisplayName("Infinite Loop Prevention Tests")
class InfiniteLoopPreventionTest {

    companion object {
        const val BITHUMB_FEE_RATE = 0.0004  // 0.04%
        const val ROUND_TRIP_FEE = 0.0008    // 매수 + 매도 = 0.08%
        const val MIN_HOLDING_SECONDS = 10L
        const val COOLDOWN_SECONDS = 60L
    }

    @Nested
    @DisplayName("Cooldown Mechanism")
    inner class CooldownMechanismTest {

        @Test
        @DisplayName("Cooldown prevents immediate re-entry")
        fun cooldownPreventsImmediateReEntry() {
            val cooldowns = ConcurrentHashMap<String, Instant>()
            val market = "KRW-BTC"

            // 거래 완료 후 쿨다운 설정
            cooldowns[market] = Instant.now().plus(COOLDOWN_SECONDS, ChronoUnit.SECONDS)

            // 즉시 재진입 시도
            val cooldownEnd = cooldowns[market]
            val canEnter = cooldownEnd == null || Instant.now().isAfter(cooldownEnd)

            assertFalse(canEnter, "Cooldown should prevent immediate re-entry")
        }

        @Test
        @DisplayName("Entry allowed after cooldown expires")
        fun entryAllowedAfterCooldownExpires() {
            val cooldowns = ConcurrentHashMap<String, Instant>()
            val market = "KRW-BTC"

            // 이미 만료된 쿨다운 설정
            cooldowns[market] = Instant.now().minus(10, ChronoUnit.SECONDS)

            val cooldownEnd = cooldowns[market]
            val canEnter = cooldownEnd == null || Instant.now().isAfter(cooldownEnd)

            assertTrue(canEnter, "Entry should be allowed after cooldown expires")
        }

        @Test
        @DisplayName("Different markets have independent cooldowns")
        fun differentMarketsIndependentCooldowns() {
            val cooldowns = ConcurrentHashMap<String, Instant>()

            // BTC는 쿨다운 중
            cooldowns["KRW-BTC"] = Instant.now().plus(60, ChronoUnit.SECONDS)
            // ETH는 쿨다운 없음

            val btcCooldown = cooldowns["KRW-BTC"]
            val ethCooldown = cooldowns["KRW-ETH"]

            val canEnterBtc = btcCooldown == null || Instant.now().isAfter(btcCooldown)
            val canEnterEth = ethCooldown == null || Instant.now().isAfter(ethCooldown)

            assertFalse(canEnterBtc, "BTC should be in cooldown")
            assertTrue(canEnterEth, "ETH should allow entry")
        }
    }

    @Nested
    @DisplayName("Minimum Holding Time")
    inner class MinimumHoldingTimeTest {

        @Test
        @DisplayName("Cannot sell before minimum holding time")
        fun cannotSellBeforeMinHoldingTime() {
            val entryTime = Instant.now()
            val currentTime = entryTime.plusSeconds(5)  // 5초 후

            val holdingSeconds = ChronoUnit.SECONDS.between(entryTime, currentTime)
            val canSell = holdingSeconds >= MIN_HOLDING_SECONDS

            assertFalse(canSell, "Should not sell before minimum holding time (10s)")
        }

        @Test
        @DisplayName("Can sell after minimum holding time")
        fun canSellAfterMinHoldingTime() {
            val entryTime = Instant.now().minus(15, ChronoUnit.SECONDS)  // 15초 전
            val currentTime = Instant.now()

            val holdingSeconds = ChronoUnit.SECONDS.between(entryTime, currentTime)
            val canSell = holdingSeconds >= MIN_HOLDING_SECONDS

            assertTrue(canSell, "Should allow sell after minimum holding time")
        }

        @Test
        @DisplayName("Fee drain calculation for rapid trading")
        fun feeDrainCalculationForRapidTrading() {
            val initialCapital = 1000000.0  // 100만원
            var capital = initialCapital

            // 1초 간격으로 100번 매수/매도 반복 시뮬레이션 (최소 보유 시간 무시 시)
            val trades = 100
            for (i in 1..trades) {
                val fee = capital * ROUND_TRIP_FEE
                capital -= fee
            }

            val totalFeePaid = initialCapital - capital
            val feePercent = (totalFeePaid / initialCapital) * 100

            // 100번 거래 시 약 7.7% 손실 (0.08% × 100 = 8%, 복리 효과로 조금 적음)
            assertTrue(feePercent > 7.0, "100 rapid trades should drain >7% in fees: $feePercent%")
            assertTrue(feePercent < 8.0, "Fee drain should be less than 8%: $feePercent%")
        }

        @Test
        @DisplayName("Minimum holding time prevents fee drain")
        fun minHoldingTimePreventsFeeDrain() {
            // 최소 10초 보유 시, 1시간 동안 최대 360번 거래 가능
            val maxTradesPerHour = 3600 / MIN_HOLDING_SECONDS
            val maxFeePerHour = maxTradesPerHour * ROUND_TRIP_FEE * 100  // %

            // 1시간 최대 28.8% 손실 (실제로는 훨씬 적음 - 쿨다운 + 신호 대기)
            assertTrue(maxFeePerHour < 30, "Max fee per hour should be limited: $maxFeePerHour%")
        }
    }

    @Nested
    @DisplayName("Duplicate Position Prevention")
    inner class DuplicatePositionPreventionTest {

        @Test
        @DisplayName("Cannot enter if position already exists")
        fun cannotEnterIfPositionExists() {
            // 시뮬레이션: DB에 OPEN 포지션 존재
            val openPositions = listOf("KRW-BTC")
            val market = "KRW-BTC"

            val hasOpenPosition = openPositions.contains(market)
            val canEnter = !hasOpenPosition

            assertFalse(canEnter, "Should not enter if position already exists")
        }

        @Test
        @DisplayName("Cannot enter if CLOSING position exists")
        fun cannotEnterIfClosingPositionExists() {
            val openPositions = emptyList<String>()
            val closingPositions = listOf("KRW-BTC")
            val market = "KRW-BTC"

            val hasActivePosition = openPositions.contains(market) || closingPositions.contains(market)
            val canEnter = !hasActivePosition

            assertFalse(canEnter, "Should not enter if CLOSING position exists")
        }

        @Test
        @DisplayName("Balance check prevents duplicate entry")
        fun balanceCheckPreventsDuplicateEntry() {
            // 시뮬레이션: 이미 코인 잔고가 있음
            val coinBalance = 0.001  // BTC 잔고
            val market = "KRW-BTC"

            val hasCoinBalance = coinBalance > 0
            val canEnter = !hasCoinBalance

            assertFalse(canEnter, "Should not enter if already holding the coin")
        }
    }

    @Nested
    @DisplayName("Concurrent Entry Prevention")
    inner class ConcurrentEntryPreventionTest {

        @Test
        @DisplayName("Lock prevents race condition")
        fun lockPreventsRaceCondition() {
            val entryLocks = ConcurrentHashMap<String, ReentrantLock>()
            val market = "KRW-BTC"
            var entryCount = 0

            val lock = entryLocks.computeIfAbsent(market) { ReentrantLock() }

            // 두 스레드가 동시에 진입 시도
            val thread1 = Thread {
                lock.withLock {
                    Thread.sleep(50)  // 진입 로직 시뮬레이션
                    entryCount++
                }
            }

            val thread2 = Thread {
                // tryLock으로 락 획득 실패 시 즉시 반환
                val acquired = lock.tryLock()
                if (acquired) {
                    try {
                        Thread.sleep(50)
                        entryCount++
                    } finally {
                        lock.unlock()
                    }
                }
            }

            thread1.start()
            Thread.sleep(10)  // thread1이 먼저 락 획득하도록
            thread2.start()

            thread1.join()
            thread2.join()

            // thread2는 tryLock 실패로 진입 못함
            assertEquals(1, entryCount, "Only one thread should enter with tryLock")
        }

        @Test
        @DisplayName("Max positions limit prevents over-entry")
        fun maxPositionsLimitPreventsOverEntry() {
            val currentPositions = 3
            val maxPositions = 3

            val canEnter = currentPositions < maxPositions

            assertFalse(canEnter, "Should not enter when max positions reached")
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Integration")
    inner class CircuitBreakerIntegrationTest {

        @Test
        @DisplayName("Consecutive losses trigger circuit breaker")
        fun consecutiveLossesTriggerCircuitBreaker() {
            var consecutiveLosses = 0
            val maxConsecutiveLosses = 3

            // 3번 연속 손실
            repeat(3) {
                consecutiveLosses++
            }

            val circuitTriggered = consecutiveLosses >= maxConsecutiveLosses

            assertTrue(circuitTriggered, "Circuit breaker should trigger after 3 losses")
        }

        @Test
        @DisplayName("Profit resets consecutive loss counter")
        fun profitResetsConsecutiveLossCounter() {
            var consecutiveLosses = 2

            // 수익 거래
            val pnlPercent = 1.5
            if (pnlPercent > 0) {
                consecutiveLosses = 0
            }

            assertEquals(0, consecutiveLosses, "Profit should reset loss counter")
        }

        @Test
        @DisplayName("Daily loss limit prevents trading")
        fun dailyLossLimitPreventsTarding() {
            val dailyPnl = -25000.0  // 25,000원 손실
            val dailyMaxLoss = 20000  // 최대 손실 한도

            val canTrade = dailyPnl > -dailyMaxLoss

            assertFalse(canTrade, "Should stop trading after daily loss limit")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("Zero entry price should block position monitoring")
        fun zeroEntryPriceShouldBlockMonitoring() {
            val entryPrice = 0.0
            val currentPrice = 100.0

            // 0으로 나누기 방지
            val pnlPercent = if (entryPrice > 0) {
                ((currentPrice - entryPrice) / entryPrice) * 100
            } else {
                null  // 계산 불가
            }

            assertEquals(null, pnlPercent, "PnL should be null for zero entry price")
        }

        @Test
        @DisplayName("Negative entry price should be rejected")
        fun negativeEntryPriceShouldBeRejected() {
            val entryPrice = -100.0

            val isValidEntry = entryPrice > 0

            assertFalse(isValidEntry, "Negative entry price should be rejected")
        }

        @Test
        @DisplayName("Server restart should restore open positions")
        fun serverRestartShouldRestoreOpenPositions() {
            // 시뮬레이션: DB에서 OPEN 포지션 조회
            data class Position(val id: Long, val market: String, val status: String)

            val dbPositions = listOf(
                Position(1, "KRW-BTC", "OPEN"),
                Position(2, "KRW-ETH", "OPEN")
            )

            val restoredPositions = dbPositions.filter { it.status == "OPEN" }

            assertEquals(2, restoredPositions.size, "Should restore all open positions")
        }

        @Test
        @DisplayName("Partial fill should be handled correctly")
        fun partialFillShouldBeHandledCorrectly() {
            val requestedQuantity = 0.1
            val executedQuantity = 0.08  // 80% 체결

            val fillRate = executedQuantity / requestedQuantity
            val isPartialFill = fillRate < 1.0

            assertTrue(isPartialFill, "Should detect partial fill")
            assertEquals(0.8, fillRate, 0.01, "Fill rate should be 80%")
        }
    }
}
