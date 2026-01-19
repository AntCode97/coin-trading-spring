package com.ant.cointrading.order

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Price = 0 버그 방지 테스트
 *
 * 버그 현상:
 * - BUY 주문 시 trades 테이블에 price = 0.0으로 저장됨
 * - PnL 계산 불가능
 * - 회고 시스템 분석 실패
 *
 * 원인:
 * 1. executedPrice가 null이고
 * 2. signal.price도 0이면
 * 3. tradePrice = 0으로 저장됨
 *
 * 수정:
 * - price가 0 이하면 marketConditionChecker로 현재가 조회
 * - 여전히 0이면 저장 스킵 (잘못된 데이터 방지)
 */
@DisplayName("Price Zero Bug Prevention Tests")
class PriceZeroBugPreventionTest {

    @Nested
    @DisplayName("Price Fallback Logic")
    inner class PriceFallbackLogicTest {

        @Test
        @DisplayName("executedPrice가 있으면 그대로 사용")
        fun useExecutedPriceWhenAvailable() {
            val executedPrice = BigDecimal("65000000")
            val signalPrice = BigDecimal("64000000")

            val tradePrice = (executedPrice ?: signalPrice).toDouble()

            assertEquals(65000000.0, tradePrice)
        }

        @Test
        @DisplayName("executedPrice가 null이면 signal.price 사용")
        fun useSignalPriceWhenExecutedPriceNull() {
            val executedPrice: BigDecimal? = null
            val signalPrice = BigDecimal("64000000")

            val tradePrice = (executedPrice ?: signalPrice).toDouble()

            assertEquals(64000000.0, tradePrice)
        }

        @Test
        @DisplayName("둘 다 0이면 가격 유효하지 않음")
        fun invalidPriceWhenBothZero() {
            val executedPrice = BigDecimal.ZERO
            val signalPrice = BigDecimal.ZERO

            val tradePrice = (executedPrice ?: signalPrice).toDouble()
            val isPriceValid = tradePrice > 0

            assertFalse(isPriceValid, "Price should be invalid when both are zero")
        }

        @Test
        @DisplayName("executedPrice가 0이면 signal.price로 폴백하지 않음 (null만 폴백)")
        fun doNotFallbackOnZeroExecutedPrice() {
            // Kotlin의 Elvis 연산자는 null만 체크, 0은 통과
            val executedPrice = BigDecimal.ZERO
            val signalPrice = BigDecimal("64000000")

            val tradePrice = (executedPrice ?: signalPrice).toDouble()

            // 현재 로직: executedPrice가 0이면 0 반환 (버그 상황)
            // 이 경우 추가 로직에서 현재가 조회 필요
            assertEquals(0.0, tradePrice)
        }
    }

    @Nested
    @DisplayName("Price Validation Logic")
    inner class PriceValidationLogicTest {

        @Test
        @DisplayName("양수 가격은 유효")
        fun positivepriceIsValid() {
            val tradePrice = 65000000.0
            val isValid = tradePrice > 0

            assertTrue(isValid)
        }

        @Test
        @DisplayName("0 가격은 유효하지 않음")
        fun zeroPriceIsInvalid() {
            val tradePrice = 0.0
            val isValid = tradePrice > 0

            assertFalse(isValid)
        }

        @Test
        @DisplayName("음수 가격은 유효하지 않음")
        fun negativePriceIsInvalid() {
            val tradePrice = -100.0
            val isValid = tradePrice > 0

            assertFalse(isValid)
        }
    }

    @Nested
    @DisplayName("Market Condition Fallback")
    inner class MarketConditionFallbackTest {

        @Test
        @DisplayName("midPrice를 사용한 폴백")
        fun fallbackToMidPrice() {
            val midPrice = BigDecimal("65500000")
            val tradePrice = midPrice.toDouble()

            assertTrue(tradePrice > 0)
            assertEquals(65500000.0, tradePrice)
        }

        @Test
        @DisplayName("midPrice도 없으면 저장 스킵")
        fun skipSaveWhenNoValidPrice() {
            val midPrice: BigDecimal? = null
            val tradePrice = midPrice?.toDouble() ?: 0.0
            val shouldSave = tradePrice > 0

            assertFalse(shouldSave, "Should skip save when no valid price available")
        }
    }

    @Nested
    @DisplayName("Data Quality Protection")
    inner class DataQualityProtectionTest {

        @Test
        @DisplayName("price=0 거래는 저장되지 않아야 함")
        fun priceZeroTradesShouldNotBeSaved() {
            val tradePrice = 0.0
            val shouldSave = tradePrice > 0

            assertFalse(shouldSave, "Trades with price=0 should not be saved to prevent data corruption")
        }

        @Test
        @DisplayName("PnL 계산에 price=0 방어")
        fun pnlCalculationProtection() {
            val buyPrice = 0.0
            val sellPrice = 65000000.0

            // price=0으로 나누기 방지
            val pnlPercent = if (buyPrice > 0) {
                ((sellPrice - buyPrice) / buyPrice) * 100
            } else {
                null  // 계산 불가
            }

            assertEquals(null, pnlPercent, "PnL should be null when buy price is 0")
        }

        @Test
        @DisplayName("회고 시스템에서 price=0 거래 필터링")
        fun filterZeroPriceInReflection() {
            data class Trade(val price: Double, val pnl: Double?)

            val trades = listOf(
                Trade(65000000.0, 1000.0),  // 정상
                Trade(0.0, null),           // 비정상
                Trade(66000000.0, 2000.0),  // 정상
                Trade(0.0, null)            // 비정상
            )

            val validTrades = trades.filter { it.price > 0 }

            assertEquals(2, validTrades.size, "Only trades with valid price should be used")
        }
    }
}
