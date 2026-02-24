package com.ant.cointrading.order

import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.OrderResponse
import com.ant.cointrading.config.RiskThrottleProperties
import com.ant.cointrading.config.StrategyGuardProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.OrderSide
import com.ant.cointrading.model.OrderType
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.ConditionSeverity
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.risk.MarketConditionResult
import com.ant.cointrading.risk.RiskThrottleDecision
import com.ant.cointrading.risk.RiskThrottleService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OrderExecutor 단위 테스트
 *
 * 켄트 백의 TDD 원칙:
 * 1. 실패하는 테스트를 먼저 작성
 * 2. 테스트가 통과하는 최소한의 코드 작성
 * 3. 리팩토링
 *
 * 테스트 범위:
 * - 최소 주문 금액 검증 (5100원)
 * - 수수료 고려 (0.04%)
 * - 매도 시 잔고 확인
 * - 시장 상태 검사
 * - 슬리피지 계산
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderExecutor")
class OrderExecutorTest {

    @Mock
    private lateinit var bithumbPrivateApi: BithumbPrivateApi

    @Mock
    private lateinit var tradingProperties: TradingProperties

    @Mock
    private lateinit var tradeRepository: TradeRepository

    @Mock
    private lateinit var circuitBreaker: CircuitBreaker

    @Mock
    private lateinit var marketConditionChecker: MarketConditionChecker

    @Mock
    private lateinit var riskThrottleService: RiskThrottleService

    @Mock
    private lateinit var slackNotifier: SlackNotifier

    @Mock
    private lateinit var pendingOrderManager: PendingOrderManager

    private lateinit var orderExecutor: OrderExecutor

    @BeforeEach
    fun setup() {
        // TradingProperties 기본 mock 설정
        whenever(tradingProperties.feeRate).thenReturn(BigDecimal("0.0004"))
        whenever(tradingProperties.enabled).thenReturn(false) // 기본 시뮬레이션 모드
        whenever(tradingProperties.riskThrottle).thenReturn(RiskThrottleProperties())
        whenever(tradingProperties.strategyGuard).thenReturn(StrategyGuardProperties(enabled = false))
        whenever(riskThrottleService.getDecision(any(), any(), any())).thenReturn(
            RiskThrottleDecision(
                multiplier = 1.0,
                severity = "NORMAL",
                blockNewBuys = false,
                reason = "테스트 기본값",
                sampleSize = 0,
                recentConsecutiveLosses = 0,
                winRate = 0.0,
                avgPnlPercent = 0.0,
                enabled = true,
                cached = false
            )
        )

        orderExecutor = OrderExecutor(
            bithumbPrivateApi,
            tradingProperties,
            tradeRepository,
            circuitBreaker,
            marketConditionChecker,
            riskThrottleService,
            slackNotifier,
            pendingOrderManager
        )
    }

    // 헬퍼 함수: OrderResponse 생성
    private fun createOrderResponse(
        uuid: String = "test-order-123",
        side: String = "bid",
        market: String = "KRW-BTC",
        state: String? = "done",
        price: BigDecimal? = BigDecimal("65000000"),
        volume: BigDecimal? = BigDecimal("0.001"),
        executedVolume: BigDecimal? = BigDecimal("0.001"),
        paidFee: BigDecimal? = BigDecimal("26"),
        locked: BigDecimal? = BigDecimal("65000")
    ): OrderResponse = OrderResponse(
        uuid = uuid,
        side = side,
        ordType = "market",
        price = price,
        state = state,
        market = market,
        createdAt = "2026-01-18T10:00:00",
        volume = volume,
        remainingVolume = BigDecimal.ZERO,
        reservedFee = BigDecimal.ZERO,
        remainingFee = BigDecimal.ZERO,
        paidFee = paidFee,
        locked = locked,
        executedVolume = executedVolume
    )

    @Test
    @DisplayName("MARKET 매수 평균단가 계산은 locked보다 positionSize를 우선 사용한다")
    fun resolveInvestedAmountPrefersPositionSizeOverLocked() {
        val method = OrderExecutor::class.java.getDeclaredMethod(
            "resolveInvestedAmount",
            TradingSignal::class.java,
            OrderResponse::class.java,
            BigDecimal::class.java
        )
        method.isAccessible = true

        val signal = TradingSignal(
            market = "BTC_KRW",
            action = SignalAction.BUY,
            confidence = 80.0,
            price = BigDecimal("65000000"),
            reason = "테스트",
            strategy = "TEST"
        )
        val order = createOrderResponse(
            locked = BigDecimal("99999"),
            price = BigDecimal("65000000"),
            executedVolume = BigDecimal("0.001")
        )
        val invested = method.invoke(orderExecutor, signal, order, BigDecimal("10000")) as BigDecimal

        assertEquals(BigDecimal("10000"), invested)
    }

    @Nested
    @DisplayName("최소 주문 금액 검증")
    inner class MinOrderAmountTest {

        @Test
        @DisplayName("5100원 미만 주문은 거부된다")
        fun rejectOrderBelowMinAmount() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("5000") // 최소 금액 미만

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT, result.rejectionReason)
            assertTrue(result.message.contains("최소 주문 금액"))
        }

        @Test
        @DisplayName("5100원 이상 주문은 처리된다")
        fun acceptOrderAboveMinAmount() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("5100")

            whenever(tradingProperties.enabled).thenReturn(false) // 시뮬레이션 모드

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertTrue(result.success)
            assertTrue(result.isSimulated)
        }

        @Test
        @DisplayName("수수료 고려해서 5050원도 거부된다")
        fun rejectOrderConsideringFee() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("5050") // 수수료 고려하면 부족

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT, result.rejectionReason)
        }

        @Test
        @DisplayName("리스크 스로틀 임계 구간이면 신규 매수는 차단된다")
        fun blockBuyWhenRiskThrottleCritical() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            whenever(riskThrottleService.getDecision("BTC_KRW", "TEST", false)).thenReturn(
                RiskThrottleDecision(
                    multiplier = 0.45,
                    severity = "CRITICAL",
                    blockNewBuys = true,
                    reason = "최근 성과 악화(강한 축소)",
                    sampleSize = 20,
                    recentConsecutiveLosses = 5,
                    winRate = 0.3,
                    avgPnlPercent = -1.0,
                    enabled = true,
                    cached = false
                )
            )

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.RISK_THROTTLE_BLOCK, result.rejectionReason)
            assertTrue(result.message.contains("리스크 스로틀 차단"))
        }

        @Test
        @DisplayName("신뢰도 기준 미달 매수 신호는 차단된다")
        fun rejectLowConfidenceBuySignal() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 52.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.LOW_SIGNAL_CONFIDENCE, result.rejectionReason)
            assertTrue(result.message.contains("신호 신뢰도 부족"))
        }

        @Test
        @DisplayName("스로틀 축소 결과가 최소 주문 금액 미만이면 진입을 거부한다")
        fun rejectBuyWhenThrottleDropsBelowMinimumOrder() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            whenever(riskThrottleService.getDecision("BTC_KRW", "TEST", false)).thenReturn(
                RiskThrottleDecision(
                    multiplier = 0.45,
                    severity = "WEAK",
                    blockNewBuys = false,
                    reason = "최근 성과 둔화(완화 축소)",
                    sampleSize = 20,
                    recentConsecutiveLosses = 1,
                    winRate = 0.42,
                    avgPnlPercent = -0.3,
                    enabled = true,
                    cached = false
                )
            )

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT, result.rejectionReason)
        }
    }

    @Nested
    @DisplayName("매도 잔고 검증")
    inner class SellBalanceValidationTest {

        @Test
        @Disabled("통합 테스트로 이동 필요 - OrderExecutor 내부 로직이 복잡함")
        @DisplayName("잔고가 요청 수량보다 적으면 전량 매도")
        fun sellAllWhenInsufficientBalance() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.SELL,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("1000000") // 100만원어치 매도 요청

            whenever(tradingProperties.enabled).thenReturn(true)

            val marketCondition = MarketConditionResult(
                canTrade = true,
                severity = ConditionSeverity.NORMAL,
                issues = emptyList(),
                spread = 0.1,
                midPrice = BigDecimal("65000000"),
                bestBid = BigDecimal("64990000"),
                bestAsk = BigDecimal("65010000"),
                liquidityRatio = 10.0,
                volatility1Min = 0.5,
                orderBookImbalance = 0.0
            )
            whenever(marketConditionChecker.checkMarketCondition(any(), any())).thenReturn(marketCondition)

            // BTC 잔고가 0.001 BTC만 있음 (약 6.5만원)
            whenever(bithumbPrivateApi.getBalances()).thenReturn(
                listOf(
                    Balance("KRW", BigDecimal("100000"), null, null, null, null),
                    Balance("BTC", BigDecimal("0.001"), null, BigDecimal("65000000"), null, null)
                )
            )

            // 매도 주문 응답
            whenever(bithumbPrivateApi.sellMarketOrder(any(), any())).thenReturn(
                createOrderResponse(side = "ask", executedVolume = BigDecimal("0.001"))
            )

            whenever(bithumbPrivateApi.getOrder(any())).thenReturn(
                createOrderResponse(side = "ask", executedVolume = BigDecimal("0.001"))
            )

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            verify(bithumbPrivateApi).sellMarketOrder(any(), eq(BigDecimal("0.001"))) // 전량 매도
        }
    }

    @Nested
    @DisplayName("시장 상태 검사")
    inner class MarketConditionTest {

        @Test
        @DisplayName("시장 상태가 불량하면 주문이 거부된다")
        fun rejectOrderWhenMarketConditionBad() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(true)

            val badCondition = MarketConditionResult(
                canTrade = false,
                severity = ConditionSeverity.CRITICAL,
                issues = listOf("스프레드 초과 (2.5%)", "유동성 부족"),
                spread = 2.5,
                midPrice = BigDecimal("65000000"),
                bestBid = null,
                bestAsk = null,
                liquidityRatio = 1.0,
                volatility1Min = 5.0,
                orderBookImbalance = 0.0
            )
            whenever(marketConditionChecker.checkMarketCondition(any(), any())).thenReturn(badCondition)

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertFalse(result.success)
            assertEquals(OrderRejectionReason.MARKET_CONDITION, result.rejectionReason)
            assertTrue(result.message.contains("시장 상태 불량"))
        }
    }

    @Nested
    @DisplayName("시뮬레이션 모드")
    inner class SimulationModeTest {

        @Test
        @DisplayName("시뮬레이션 모드에서는 실제 주문 없이 성공 반환")
        fun simulationModeReturnsSuccessWithoutRealOrder() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(false)

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            assertTrue(result.success)
            assertTrue(result.isSimulated)
            assertTrue(result.orderId!!.startsWith("SIM-"))

            // 실제 API 호출 없음
            verify(bithumbPrivateApi, never()).buyMarketOrder(any(), any())
            verify(bithumbPrivateApi, never()).sellMarketOrder(any(), any())
        }

        @Test
        @DisplayName("시뮬레이션 모드에서 수수료가 0.04%로 계산된다")
        fun simulationCalculatesFeeCorrectly() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 80.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "TEST"
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(false)

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            // 10000 * 0.0004 = 4원
            assertEquals(BigDecimal("4.0000"), result.fee)
        }
    }

    @Nested
    @DisplayName("주문 유형 결정")
    inner class OrderTypeDeterminationTest {

        @Test
        @DisplayName("초단기 전략은 시장가 주문")
        fun shortTermStrategyUsesMarketOrder() {
            // given
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 60.0,  // 낮은 신뢰도
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "MEME_SCALPER"  // 초단기 전략
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(true)

            val normalCondition = MarketConditionResult(
                canTrade = true,
                severity = ConditionSeverity.NORMAL,
                issues = emptyList(),
                spread = 0.1,
                midPrice = BigDecimal("65000000"),
                bestBid = BigDecimal("64990000"),
                bestAsk = BigDecimal("65010000"),
                liquidityRatio = 20.0,  // 높은 유동성
                volatility1Min = 0.3,  // 낮은 변동성
                orderBookImbalance = 0.0
            )
            whenever(marketConditionChecker.checkMarketCondition(any(), any())).thenReturn(normalCondition)

            whenever(bithumbPrivateApi.buyMarketOrder(any(), any())).thenReturn(
                createOrderResponse()
            )

            whenever(bithumbPrivateApi.getOrder(any())).thenReturn(
                createOrderResponse()
            )

            // when
            val result = orderExecutor.execute(signal, positionSize)

            // then
            verify(bithumbPrivateApi).buyMarketOrder(any(), any())  // 시장가
            verify(bithumbPrivateApi, never()).buyLimitOrder(any(), any(), any())  // 지정가 아님
        }
    }

    @Nested
    @DisplayName("주문 폴백")
    inner class OrderFallbackTest {

        @Test
        @DisplayName("시장가 주문 실패 시 지정가로 폴백한다")
        fun fallbackToLimitWhenMarketOrderFails() {
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 60.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "MEME_SCALPER"
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(true)
            whenever(marketConditionChecker.checkMarketCondition(any(), any())).thenReturn(
                MarketConditionResult(
                    canTrade = true,
                    severity = ConditionSeverity.NORMAL,
                    issues = emptyList(),
                    spread = 0.1,
                    midPrice = BigDecimal("65000000"),
                    bestBid = BigDecimal("64990000"),
                    bestAsk = BigDecimal("65010000"),
                    liquidityRatio = 20.0,
                    volatility1Min = 0.3,
                    orderBookImbalance = 0.0
                )
            )

            whenever(bithumbPrivateApi.buyMarketOrder(any(), any())).thenReturn(null)
            whenever(bithumbPrivateApi.buyLimitOrder(any(), any(), any()))
                .thenReturn(createOrderResponse(uuid = "limit-fallback-1"))
            whenever(bithumbPrivateApi.getOrder(any()))
                .thenReturn(createOrderResponse(uuid = "limit-fallback-1"))

            val result = orderExecutor.execute(signal, positionSize)

            assertTrue(result.success)
            assertEquals(OrderType.LIMIT, result.orderType)
            verify(bithumbPrivateApi).buyMarketOrder(any(), any())
            verify(bithumbPrivateApi).buyLimitOrder(any(), any(), any())
        }

        @Test
        @DisplayName("지정가 경로에서 호가 정보가 없으면 시장가로 전환한다")
        fun fallbackToMarketWhenOrderBookIsMissing() {
            val signal = TradingSignal(
                market = "BTC_KRW",
                action = SignalAction.BUY,
                confidence = 60.0,
                price = BigDecimal("65000000"),
                reason = "테스트",
                strategy = "SWING"
            )
            val positionSize = BigDecimal("10000")

            whenever(tradingProperties.enabled).thenReturn(true)
            whenever(marketConditionChecker.checkMarketCondition(any(), any())).thenReturn(
                MarketConditionResult(
                    canTrade = true,
                    severity = ConditionSeverity.NORMAL,
                    issues = emptyList(),
                    spread = 0.1,
                    midPrice = BigDecimal("65000000"),
                    bestBid = null,
                    bestAsk = null,
                    liquidityRatio = 20.0,
                    volatility1Min = 0.3,
                    orderBookImbalance = 0.0
                )
            )

            whenever(bithumbPrivateApi.buyMarketOrder(any(), any()))
                .thenReturn(createOrderResponse(uuid = "market-fallback-1"))
            whenever(bithumbPrivateApi.getOrder(any()))
                .thenReturn(createOrderResponse(uuid = "market-fallback-1"))

            val result = orderExecutor.execute(signal, positionSize)

            assertTrue(result.success)
            assertEquals(OrderType.MARKET, result.orderType)
            verify(bithumbPrivateApi).buyMarketOrder(any(), any())
            verify(bithumbPrivateApi, never()).buyLimitOrder(any(), any(), any())
        }
    }
}
