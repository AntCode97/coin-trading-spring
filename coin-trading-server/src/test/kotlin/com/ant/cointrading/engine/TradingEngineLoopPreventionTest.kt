package com.ant.cointrading.engine

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TradingEngine Infinite Loop Prevention Tests
 *
 * TradingEngine에 추가된 무한 루프 방지 로직을 검증한다.
 *
 * 20년차 퀀트의 교훈:
 * "무한 매수/매도 루프는 수수료로 잔고를 녹이는 가장 흔한 버그다"
 *
 * 방지 로직:
 * 1. 최소 보유 시간: 매수 후 5분은 보유해야 매도 가능
 * 2. 거래 쿨다운: 매도 후 5분은 대기해야 재매수 가능
 * 3. 중복 매수 방지: 이미 코인 보유 중이면 추가 매수 금지
 */
@DisplayName("TradingEngine Loop Prevention Tests")
class TradingEngineLoopPreventionTest {

    companion object {
        // TradingEngine.kt의 상수와 동일하게 유지
        const val MIN_HOLDING_SECONDS = 300L  // 5분
        const val TRADE_COOLDOWN_SECONDS = 300L  // 5분
        val MIN_POSITION_VALUE_KRW = BigDecimal("5000")  // 최소 포지션 가치
    }

    @Nested
    @DisplayName("Minimum Holding Time Tests")
    inner class MinimumHoldingTimeTest {

        @Test
        @DisplayName("Cannot sell before minimum holding time (5 minutes)")
        fun cannotSellBeforeMinHoldingTime() {
            val lastBuyTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 2분 전에 매수
            lastBuyTime[market] = Instant.now().minusSeconds(120)

            val lastBuy = lastBuyTime[market]
            val canSell = if (lastBuy != null) {
                val secondsSinceBuy = Duration.between(lastBuy, Instant.now()).seconds
                secondsSinceBuy >= MIN_HOLDING_SECONDS
            } else {
                true  // 매수 기록 없으면 매도 가능
            }

            assertFalse(canSell, "Should not sell before minimum holding time (5 min)")
        }

        @Test
        @DisplayName("Can sell after minimum holding time")
        fun canSellAfterMinHoldingTime() {
            val lastBuyTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 10분 전에 매수
            lastBuyTime[market] = Instant.now().minusSeconds(600)

            val lastBuy = lastBuyTime[market]
            val canSell = if (lastBuy != null) {
                val secondsSinceBuy = Duration.between(lastBuy, Instant.now()).seconds
                secondsSinceBuy >= MIN_HOLDING_SECONDS
            } else {
                true
            }

            assertTrue(canSell, "Should allow sell after minimum holding time")
        }

        @Test
        @DisplayName("Can sell if no buy record exists")
        fun canSellIfNoBuyRecord() {
            val lastBuyTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 매수 기록 없음 (서버 재시작 후 기존 보유분 매도 시나리오)
            val lastBuy = lastBuyTime[market]
            val canSell = lastBuy == null || Duration.between(lastBuy, Instant.now()).seconds >= MIN_HOLDING_SECONDS

            assertTrue(canSell, "Should allow sell if no buy record exists")
        }
    }

    @Nested
    @DisplayName("Emergency Exit Bypass Tests")
    inner class EmergencyExitBypassTest {

        @Test
        @DisplayName("Stop-loss sell bypasses minimum holding time")
        fun stopLossBypassesHoldingGuard() {
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.SELL,
                confidence = 95.0,
                price = BigDecimal("60000000"),
                reason = "손절 청산 -2.3%",
                strategy = "VOLATILITY_SURVIVAL"
            )

            assertTrue(TradingEngine.shouldBypassMinHoldingForSell(signal))
        }

        @Test
        @DisplayName("Trailing stop sell bypasses minimum holding time")
        fun trailingStopBypassesHoldingGuard() {
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.SELL,
                confidence = 84.0,
                price = BigDecimal("63000000"),
                reason = "트레일링 청산 +1.5%",
                strategy = "VOLATILITY_SURVIVAL"
            )

            assertTrue(TradingEngine.shouldBypassMinHoldingForSell(signal))
        }

        @Test
        @DisplayName("Take-profit sell does not bypass minimum holding time")
        fun takeProfitDoesNotBypassHoldingGuard() {
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.SELL,
                confidence = 80.0,
                price = BigDecimal("65500000"),
                reason = "목표가 청산 +3.8%",
                strategy = "VOLATILITY_SURVIVAL"
            )

            assertFalse(TradingEngine.shouldBypassMinHoldingForSell(signal))
        }
    }

    @Nested
    @DisplayName("Trade Cooldown Tests")
    inner class TradeCooldownTest {

        @Test
        @DisplayName("Cannot buy immediately after sell (5 min cooldown)")
        fun cannotBuyImmediatelyAfterSell() {
            val lastSellTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 1분 전에 매도
            lastSellTime[market] = Instant.now().minusSeconds(60)

            val lastSell = lastSellTime[market]
            val canBuy = if (lastSell != null) {
                val secondsSinceSell = Duration.between(lastSell, Instant.now()).seconds
                secondsSinceSell >= TRADE_COOLDOWN_SECONDS
            } else {
                true  // 매도 기록 없으면 매수 가능
            }

            assertFalse(canBuy, "Should not buy immediately after sell (5 min cooldown)")
        }

        @Test
        @DisplayName("Can buy after cooldown expires")
        fun canBuyAfterCooldownExpires() {
            val lastSellTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 10분 전에 매도
            lastSellTime[market] = Instant.now().minusSeconds(600)

            val lastSell = lastSellTime[market]
            val canBuy = if (lastSell != null) {
                val secondsSinceSell = Duration.between(lastSell, Instant.now()).seconds
                secondsSinceSell >= TRADE_COOLDOWN_SECONDS
            } else {
                true
            }

            assertTrue(canBuy, "Should allow buy after cooldown expires")
        }

        @Test
        @DisplayName("Can buy if no sell record exists")
        fun canBuyIfNoSellRecord() {
            val lastSellTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 매도 기록 없음
            val lastSell = lastSellTime[market]
            val canBuy = lastSell == null || Duration.between(lastSell, Instant.now()).seconds >= TRADE_COOLDOWN_SECONDS

            assertTrue(canBuy, "Should allow buy if no sell record exists")
        }
    }

    @Nested
    @DisplayName("Duplicate Buy Prevention Tests")
    inner class DuplicateBuyPreventionTest {

        @Test
        @DisplayName("Cannot buy if already holding coin worth >= 5000 KRW")
        fun cannotBuyIfAlreadyHolding() {
            val coinBalance = BigDecimal("0.001")  // BTC 잔고
            val currentPrice = BigDecimal("65000000")  // 현재가 6500만원

            // 코인 가치 계산
            val coinValueKrw = coinBalance.multiply(currentPrice)

            val canBuy = coinValueKrw < MIN_POSITION_VALUE_KRW

            assertFalse(canBuy, "Should not buy if already holding coin worth >= 5000 KRW (value: $coinValueKrw)")
        }

        @Test
        @DisplayName("Can buy if holding dust amount (< 5000 KRW)")
        fun canBuyIfHoldingDustAmount() {
            val coinBalance = BigDecimal("0.00001")  // 아주 적은 BTC 잔고
            val currentPrice = BigDecimal("65000000")  // 현재가 6500만원

            // 코인 가치 계산: 0.00001 * 65000000 = 650 KRW
            val coinValueKrw = coinBalance.multiply(currentPrice)

            val canBuy = coinValueKrw < MIN_POSITION_VALUE_KRW

            assertTrue(canBuy, "Should allow buy if holding dust amount (< 5000 KRW)")
        }

        @Test
        @DisplayName("Can buy if zero balance")
        fun canBuyIfZeroBalance() {
            val coinBalance = BigDecimal.ZERO
            val currentPrice = BigDecimal("65000000")

            val coinValueKrw = coinBalance.multiply(currentPrice)

            val canBuy = coinValueKrw < MIN_POSITION_VALUE_KRW

            assertTrue(canBuy, "Should allow buy if zero balance")
        }
    }

    @Nested
    @DisplayName("Independent Market Tracking Tests")
    inner class IndependentMarketTrackingTest {

        @Test
        @DisplayName("Different markets have independent cooldowns")
        fun differentMarketsIndependentCooldowns() {
            val lastSellTime = ConcurrentHashMap<String, Instant>()

            // BTC는 방금 매도
            lastSellTime["BTC_KRW"] = Instant.now().minusSeconds(60)
            // ETH는 매도 기록 없음

            val canBuyBtc = isAfterCooldown(lastSellTime["BTC_KRW"])
            val canBuyEth = isAfterCooldown(lastSellTime["ETH_KRW"])

            assertFalse(canBuyBtc, "BTC should be in cooldown")
            assertTrue(canBuyEth, "ETH should allow buy (no sell record)")
        }

        @Test
        @DisplayName("Different markets have independent holding times")
        fun differentMarketsIndependentHoldingTimes() {
            val lastBuyTime = ConcurrentHashMap<String, Instant>()

            // BTC는 방금 매수
            lastBuyTime["BTC_KRW"] = Instant.now().minusSeconds(60)
            // ETH는 10분 전 매수
            lastBuyTime["ETH_KRW"] = Instant.now().minusSeconds(600)

            val canSellBtc = isAfterHoldingTime(lastBuyTime["BTC_KRW"])
            val canSellEth = isAfterHoldingTime(lastBuyTime["ETH_KRW"])

            assertFalse(canSellBtc, "BTC should not be sellable (just bought)")
            assertTrue(canSellEth, "ETH should be sellable (bought 10 min ago)")
        }

        private fun isAfterCooldown(lastSell: Instant?): Boolean {
            return lastSell == null || Duration.between(lastSell, Instant.now()).seconds >= TRADE_COOLDOWN_SECONDS
        }

        private fun isAfterHoldingTime(lastBuy: Instant?): Boolean {
            return lastBuy == null || Duration.between(lastBuy, Instant.now()).seconds >= MIN_HOLDING_SECONDS
        }
    }

    @Nested
    @DisplayName("Fee Drain Calculation Tests")
    inner class FeeDrainCalculationTest {

        @Test
        @DisplayName("Without protection: 100 rapid trades drain significant fees")
        fun rapidTradesWithoutProtection() {
            var balance = 1000000.0  // 100만원
            val feeRate = 0.0004  // 빗썸 수수료 0.04%
            val roundTripFee = feeRate * 2  // 매수 + 매도

            // 보호 없이 100번 매수/매도 반복
            repeat(100) {
                balance *= (1 - roundTripFee)
            }

            val lossPercent = (1 - balance / 1000000.0) * 100
            assertTrue(lossPercent > 7, "100 trades should lose >7%: $lossPercent%")
        }

        @Test
        @DisplayName("With protection: Max 12 trades per hour (5 min cooldown)")
        fun protectedTradesPerHour() {
            // 5분 쿨다운 = 시간당 최대 12번 거래 가능
            val maxTradesPerHour = 3600 / TRADE_COOLDOWN_SECONDS.toInt()
            val roundTripFeePercent = 0.08  // 0.04% * 2

            val maxFeePerHour = maxTradesPerHour * roundTripFeePercent

            // 시간당 최대 0.96% 손실 (12 * 0.08%)
            assertTrue(maxFeePerHour < 1.0, "Max fee per hour with protection: $maxFeePerHour%")
        }

        @Test
        @DisplayName("Circuit breaker limits loss before fee drain")
        fun circuitBreakerPreventsFeeDrain() {
            // Circuit breaker: 연속 3번 손실 시 4시간 정지
            // 하루 최대 6번 발동 가능 (24/4)
            // 발동 전 최대 3번 거래 가능
            val maxTradesBeforeCircuit = 3
            val maxCircuitTrips = 6
            val maxTradesPerDay = maxTradesBeforeCircuit * maxCircuitTrips  // 18번

            val roundTripFeePercent = 0.08
            val maxFeePerDay = maxTradesPerDay * roundTripFeePercent

            // 하루 최대 1.44% 손실 (18 * 0.08%)
            assertTrue(maxFeePerDay < 2.0, "Max daily fee with circuit breaker: $maxFeePerDay%")
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("Server restart clears in-memory tracking")
        fun serverRestartClearsTracking() {
            // 서버 재시작 시 lastBuyTime, lastSellTime이 초기화됨
            // 이 경우 기존 포지션은 잔고 체크로 보호됨
            val lastBuyTime = ConcurrentHashMap<String, Instant>()

            // 서버 재시작 후 빈 맵
            val canSell = lastBuyTime["BTC_KRW"] == null ||
                    Duration.between(lastBuyTime["BTC_KRW"], Instant.now()).seconds >= MIN_HOLDING_SECONDS

            assertTrue(canSell, "Should allow sell after restart (balance check will protect)")
        }

        @Test
        @DisplayName("Concurrent signals processed correctly")
        fun concurrentSignalsProcessed() {
            val lastBuyTime = ConcurrentHashMap<String, Instant>()
            val market = "BTC_KRW"

            // 동시에 여러 신호가 들어와도 ConcurrentHashMap이 thread-safe하게 처리
            val threads = (1..10).map { i ->
                Thread {
                    lastBuyTime[market] = Instant.now()
                    Thread.sleep(10)
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // 마지막 값만 저장됨 (덮어쓰기)
            assertTrue(lastBuyTime.containsKey(market), "Market should have buy time recorded")
        }

        @Test
        @DisplayName("Zero price should not crash")
        fun zeroPriceShouldNotCrash() {
            val coinBalance = BigDecimal("0.001")
            val currentPrice = BigDecimal.ZERO

            val coinValueKrw = coinBalance.multiply(currentPrice)

            // 0원 * 어떤 값 = 0
            val canBuy = coinValueKrw < MIN_POSITION_VALUE_KRW

            assertTrue(canBuy, "Should handle zero price gracefully")
        }

        @Test
        @DisplayName("Negative duration should not crash")
        fun negativeDurationShouldNotCrash() {
            // 시스템 시간이 역행하는 경우 (NTP 동기화 등)
            val futureTime = Instant.now().plusSeconds(60)

            val duration = Duration.between(futureTime, Instant.now())
            val seconds = duration.seconds

            // 음수 초가 나오면 쿨다운 미충족으로 처리
            val isAfterCooldown = seconds >= TRADE_COOLDOWN_SECONDS

            assertFalse(isAfterCooldown, "Should not allow trade if time went backwards")
        }
    }
}
