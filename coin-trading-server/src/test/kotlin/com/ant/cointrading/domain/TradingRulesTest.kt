package com.ant.cointrading.domain

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 트레이딩 도메인 규칙 테스트
 *
 * 켄트 백의 원칙: "테스트는 문서다"
 * 이 테스트를 읽으면 비즈니스 규칙을 이해할 수 있어야 한다.
 *
 * 핵심 규칙:
 * 1. 최소 주문 금액: 5100원 (Bithumb 5000원 + 수수료 여유)
 * 2. 수수료: 0.04% (Bithumb 고정)
 * 3. 매도 시 실제 잔고 확인 필수
 * 4. 손절/익절 시에도 최소 금액 검증
 */
@DisplayName("트레이딩 도메인 규칙")
class TradingRulesTest {

    companion object {
        val MIN_ORDER_AMOUNT_KRW = BigDecimal("5100")
        val BITHUMB_FEE_RATE = BigDecimal("0.0004")  // 0.04%
    }

    @Nested
    @DisplayName("최소 주문 금액 규칙")
    inner class MinOrderAmountRules {

        @Test
        @DisplayName("5100원은 주문 가능")
        fun orderAbove5100() {
            val amount = BigDecimal("5100")
            assertTrue(amount >= MIN_ORDER_AMOUNT_KRW)
        }

        @Test
        @DisplayName("5099원은 주문 불가")
        fun orderBelow5100() {
            val amount = BigDecimal("5099")
            assertFalse(amount >= MIN_ORDER_AMOUNT_KRW)
        }

        @ParameterizedTest
        @CsvSource(
            "5100, true",
            "5000, false",
            "10000, true",
            "4999, false",
            "5050, false"
        )
        @DisplayName("다양한 금액 테스트")
        fun variousAmounts(amount: String, canOrder: Boolean) {
            val orderAmount = BigDecimal(amount)
            assertEquals(canOrder, orderAmount >= MIN_ORDER_AMOUNT_KRW)
        }
    }

    @Nested
    @DisplayName("수수료 계산 규칙")
    inner class FeeCalculationRules {

        @Test
        @DisplayName("10000원 주문 시 수수료 4원")
        fun feeFor10000() {
            val orderAmount = BigDecimal("10000")
            val fee = orderAmount.multiply(BITHUMB_FEE_RATE)
            assertEquals(BigDecimal("4.0000"), fee)
        }

        @Test
        @DisplayName("65000원 주문 시 수수료 26원")
        fun feeFor65000() {
            val orderAmount = BigDecimal("65000")
            val fee = orderAmount.multiply(BITHUMB_FEE_RATE)
            assertEquals(BigDecimal("26.0000"), fee)
        }

        @Test
        @DisplayName("왕복 수수료 계산 (매수 + 매도)")
        fun roundTripFee() {
            val orderAmount = BigDecimal("100000")
            val buyFee = orderAmount.multiply(BITHUMB_FEE_RATE)
            val sellFee = orderAmount.multiply(BITHUMB_FEE_RATE)
            val totalFee = buyFee.add(sellFee)

            // 10만원 왕복 수수료 = 80원
            assertEquals(BigDecimal("80.0000"), totalFee)
        }

        @Test
        @DisplayName("수수료 고려 손익분기점")
        fun breakEvenWithFee() {
            // 매수 + 매도 수수료 = 0.08%
            // 최소 0.08% 이상 상승해야 손익분기
            val totalFeeRate = BITHUMB_FEE_RATE.multiply(BigDecimal(2))
            assertEquals(BigDecimal("0.0008"), totalFeeRate)
        }
    }

    @Nested
    @DisplayName("수량 계산 규칙")
    inner class QuantityCalculationRules {

        @Test
        @DisplayName("BTC 가격 6500만원일 때 10000원 = 0.00015384 BTC")
        fun calculateBtcQuantity() {
            val price = BigDecimal("65000000")
            val amount = BigDecimal("10000")
            val quantity = amount.divide(price, 8, RoundingMode.DOWN)

            assertEquals(BigDecimal("0.00015384"), quantity)
        }

        @Test
        @DisplayName("ETH 가격 350만원일 때 10000원 = 0.00285714 ETH")
        fun calculateEthQuantity() {
            val price = BigDecimal("3500000")
            val amount = BigDecimal("10000")
            val quantity = amount.divide(price, 8, RoundingMode.DOWN)

            assertEquals(BigDecimal("0.00285714"), quantity)
        }

        @Test
        @DisplayName("소수점 8자리로 버림")
        fun quantityTruncatedTo8Decimals() {
            val price = BigDecimal("65000000")
            val amount = BigDecimal("10000")
            val quantity = amount.divide(price, 8, RoundingMode.DOWN)

            // 10000 / 65000000 = 0.00015384615384...
            // 버림 후 0.00015384
            assertTrue(quantity.scale() <= 8)
        }
    }

    @Nested
    @DisplayName("손익 계산 규칙")
    inner class PnlCalculationRules {

        @Test
        @DisplayName("수익률 계산 (수수료 제외)")
        fun calculatePnlPercentWithoutFee() {
            val entryPrice = BigDecimal("65000000")
            val exitPrice = BigDecimal("66000000")

            val pnlPercent = exitPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))

            // (66000000 - 65000000) / 65000000 * 100 ≈ 1.538%
            assertTrue(pnlPercent.toDouble() in 1.538..1.539)
        }

        @Test
        @DisplayName("실제 손익 계산 (수수료 포함)")
        fun calculateNetPnlWithFee() {
            val entryPrice = BigDecimal("65000000")
            val exitPrice = BigDecimal("66000000")
            val quantity = BigDecimal("0.001")

            // 매수 금액
            val buyAmount = entryPrice.multiply(quantity)  // 65000
            val buyFee = buyAmount.multiply(BITHUMB_FEE_RATE)  // 26

            // 매도 금액
            val sellAmount = exitPrice.multiply(quantity)  // 66000
            val sellFee = sellAmount.multiply(BITHUMB_FEE_RATE)  // 26.4

            // 순 손익
            val grossPnl = sellAmount.subtract(buyAmount)  // 1000
            val netPnl = grossPnl.subtract(buyFee).subtract(sellFee)  // 1000 - 26 - 26.4 = 947.6

            assertTrue(netPnl < grossPnl)  // 수수료 차감 확인
            assertEquals(0, BigDecimal("947.6").compareTo(netPnl.setScale(1, RoundingMode.HALF_UP)))
        }

        @Test
        @DisplayName("0원 진입가 시 NaN 방지")
        fun preventDivisionByZero() {
            val entryPrice = BigDecimal.ZERO
            val exitPrice = BigDecimal("65000000")

            val pnlPercent = if (entryPrice > BigDecimal.ZERO) {
                exitPrice.subtract(entryPrice)
                    .divide(entryPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }

            assertEquals(BigDecimal.ZERO, pnlPercent)
        }
    }

    @Nested
    @DisplayName("잔고 검증 규칙")
    inner class BalanceValidationRules {

        @Test
        @DisplayName("잔고가 요청 수량보다 적으면 전량 매도")
        fun sellAllWhenInsufficientBalance() {
            val requestedQuantity = BigDecimal("0.01")
            val actualBalance = BigDecimal("0.005")

            val sellQuantity = actualBalance.min(requestedQuantity)

            assertEquals(actualBalance, sellQuantity)
        }

        @Test
        @DisplayName("잔고가 0이면 매도 불가")
        fun cannotSellWithZeroBalance() {
            val actualBalance = BigDecimal.ZERO

            val canSell = actualBalance > BigDecimal.ZERO

            assertFalse(canSell)
        }

        @Test
        @DisplayName("잔고가 충분하면 요청 수량 매도")
        fun sellRequestedWhenSufficientBalance() {
            val requestedQuantity = BigDecimal("0.01")
            val actualBalance = BigDecimal("0.1")

            val sellQuantity = actualBalance.min(requestedQuantity)

            assertEquals(requestedQuantity, sellQuantity)
        }
    }

    @Nested
    @DisplayName("마켓 형식 변환 규칙")
    inner class MarketFormatRules {

        @Test
        @DisplayName("BTC_KRW -> KRW-BTC (Bithumb API 형식)")
        fun convertToApiFormat() {
            val market = "BTC_KRW"
            val apiMarket = market.split("_").let { parts ->
                if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
            }

            assertEquals("KRW-BTC", apiMarket)
        }

        @Test
        @DisplayName("KRW-BTC에서 코인 심볼 추출")
        fun extractCoinSymbolFromApi() {
            val apiMarket = "KRW-BTC"
            val coinSymbol = apiMarket.removePrefix("KRW-")

            assertEquals("BTC", coinSymbol)
        }

        @Test
        @DisplayName("BTC_KRW에서 코인 심볼 추출")
        fun extractCoinSymbolFromInternal() {
            val market = "BTC_KRW"
            val coinSymbol = market.split("_").firstOrNull() ?: market

            assertEquals("BTC", coinSymbol)
        }
    }

    @Nested
    @DisplayName("손절/익절 규칙")
    inner class StopLossTakeProfitRules {

        @Test
        @DisplayName("-2% 손절 트리거")
        fun stopLossAt2Percent() {
            val entryPrice = BigDecimal("65000000")
            val stopLossPercent = -2.0
            val currentPrice = BigDecimal("63700000")  // -2%

            val pnlPercent = currentPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()

            assertTrue(pnlPercent <= stopLossPercent)
        }

        @Test
        @DisplayName("+5% 익절 트리거")
        fun takeProfitAt5Percent() {
            val entryPrice = BigDecimal("65000000")
            val takeProfitPercent = 5.0
            val currentPrice = BigDecimal("68250000")  // +5%

            val pnlPercent = currentPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()

            assertTrue(pnlPercent >= takeProfitPercent)
        }

        @Test
        @DisplayName("손절 시에도 최소 금액 검증")
        fun stopLossStillNeedsMinAmount() {
            val quantity = BigDecimal("0.0001")
            val currentPrice = BigDecimal("48000")  // 4.8원 어치

            val sellAmount = quantity.multiply(currentPrice)

            assertFalse(sellAmount >= MIN_ORDER_AMOUNT_KRW)
            // 손절이라도 최소 금액 미달이면 ABANDONED 처리
        }
    }
}
