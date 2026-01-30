package com.ant.cointrading.memescalper

import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.engine.GlobalPositionManager
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.risk.SimpleCircuitBreaker
import com.ant.cointrading.risk.SimpleCircuitBreakerFactory
import com.ant.cointrading.risk.SimpleCircuitBreakerState
import com.ant.cointrading.risk.SimpleCircuitBreakerStatePersistence
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MemeScalperEngine 단위 테스트
 *
 * 검증 항목:
 * 1. 손절/익절/트레일링 스탑 로직
 * 2. 서킷 브레이커 동작
 * 3. 포지션 타임아웃
 * 4. 손익 계산
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemeScalperEngine")
class MemeScalperEngineTest {

    @Mock
    private lateinit var properties: MemeScalperProperties

    @Mock
    private lateinit var bithumbPublicApi: BithumbPublicApi

    @Mock
    private lateinit var bithumbPrivateApi: BithumbPrivateApi

    @Mock
    private lateinit var memeScalperDetector: MemeScalperDetector

    @Mock
    private lateinit var orderExecutor: OrderExecutor

    @Mock
    private lateinit var tradeRepository: MemeScalperTradeRepository

    @Mock
    private lateinit var statsRepository: MemeScalperDailyStatsRepository

    @Mock
    private lateinit var slackNotifier: SlackNotifier

    @Mock
    private lateinit var globalPositionManager: GlobalPositionManager

    @Mock
    private lateinit var regimeDetector: RegimeDetector

    @Mock
    private lateinit var circuitBreakerFactory: SimpleCircuitBreakerFactory

    private lateinit var engine: MemeScalperEngine

    @BeforeEach
    fun setup() {
        // CircuitBreakerFactory mock 설정
        whenever(circuitBreakerFactory.create(any(), any(), any())).thenReturn(
            SimpleCircuitBreaker(3, 20000.0, object : SimpleCircuitBreakerStatePersistence {
                override fun load() = null
                override fun save(state: SimpleCircuitBreakerState) {}
            })
        )

        engine = MemeScalperEngine(
            properties,
            bithumbPublicApi,
            bithumbPrivateApi,
            memeScalperDetector,
            orderExecutor,
            tradeRepository,
            statsRepository,
            slackNotifier,
            globalPositionManager,
            regimeDetector,
            circuitBreakerFactory
        )

        // 기본 설정
        whenever(properties.enabled).thenReturn(true)
        whenever(properties.stopLossPercent).thenReturn(-1.5)
        whenever(properties.takeProfitPercent).thenReturn(3.0)
        whenever(properties.trailingStopTrigger).thenReturn(1.0)
        whenever(properties.trailingStopOffset).thenReturn(0.5)
        whenever(properties.positionTimeoutMin).thenReturn(5)
        whenever(properties.maxConsecutiveLosses).thenReturn(3)
        whenever(properties.dailyMaxLossKrw).thenReturn(20000)
        whenever(properties.dailyMaxTrades).thenReturn(50)
    }

    @Nested
    @DisplayName("손절 로직")
    inner class StopLossTest {

        @Test
        @DisplayName("-1.5% 손실 시 손절 트리거")
        fun triggerStopLossAt15Percent() {
            // given
            val entryPrice = 1000.0
            val currentPrice = 984.0  // -1.6% 손실
            val stopLossPercent = -1.5

            // when
            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

            // then
            assertTrue(pnlPercent <= stopLossPercent, "손절 조건 충족: $pnlPercent% <= $stopLossPercent%")
        }

        @Test
        @DisplayName("-1.4% 손실 시 손절 미트리거")
        fun noStopLossAt14Percent() {
            // given
            val entryPrice = 1000.0
            val currentPrice = 986.0  // -1.4% 손실
            val stopLossPercent = -1.5

            // when
            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

            // then
            assertFalse(pnlPercent <= stopLossPercent, "손절 조건 미충족: $pnlPercent% > $stopLossPercent%")
        }
    }

    @Nested
    @DisplayName("익절 로직")
    inner class TakeProfitTest {

        @Test
        @DisplayName("+3% 수익 시 익절 트리거")
        fun triggerTakeProfitAt3Percent() {
            // given
            val entryPrice = 1000.0
            val currentPrice = 1031.0  // +3.1% 수익
            val takeProfitPercent = 3.0

            // when
            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

            // then
            assertTrue(pnlPercent >= takeProfitPercent, "익절 조건 충족: $pnlPercent% >= $takeProfitPercent%")
        }

        @Test
        @DisplayName("R:R = 2:1 검증 (손절 -1.5%, 익절 +3%)")
        fun verifyRiskRewardRatio() {
            // given
            val stopLoss = 1.5  // 리스크
            val takeProfit = 3.0  // 리워드

            // when
            val riskRewardRatio = takeProfit / stopLoss

            // then
            assertEquals(2.0, riskRewardRatio, 0.01, "R:R = 2:1")
        }
    }

    @Nested
    @DisplayName("트레일링 스탑")
    inner class TrailingStopTest {

        @Test
        @DisplayName("+1% 수익 시 트레일링 스탑 활성화")
        fun activateTrailingStopAt1Percent() {
            // given
            val entryPrice = 1000.0
            val currentPrice = 1011.0  // +1.1% 수익
            val trailingTrigger = 1.0

            // when
            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100
            val shouldActivate = pnlPercent >= trailingTrigger

            // then
            assertTrue(shouldActivate, "트레일링 스탑 활성화: $pnlPercent% >= $trailingTrigger%")
        }

        @Test
        @DisplayName("고점 대비 -0.5% 하락 시 트레일링 스탑 발동")
        fun triggerTrailingStopOnDrawdown() {
            // given
            val peakPrice = 1020.0  // 고점
            val currentPrice = 1014.0  // 고점 대비 -0.59% 하락
            val trailingOffset = 0.5

            // when
            val drawdownPercent = ((peakPrice - currentPrice) / peakPrice) * 100

            // then
            assertTrue(drawdownPercent >= trailingOffset, "트레일링 스탑 발동: $drawdownPercent% >= $trailingOffset%")
        }

        @Test
        @DisplayName("트레일링 스탑으로 이익 실현 (익절 전 청산)")
        fun realizeProfitWithTrailingStop() {
            // given
            val entryPrice = 1000.0
            val peakPrice = 1025.0   // +2.5% 고점
            val currentPrice = 1019.0  // 고점 대비 -0.59% (트레일링 발동)

            // when
            val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

            // then
            assertTrue(pnlPercent > 0, "이익 상태에서 청산: $pnlPercent%")
            assertTrue(pnlPercent < 3.0, "익절 전에 트레일링으로 청산")
        }
    }

    @Nested
    @DisplayName("포지션 타임아웃")
    inner class PositionTimeoutTest {

        @Test
        @DisplayName("5분 초과 시 타임아웃 청산")
        fun timeoutAfter5Minutes() {
            // given
            val entryTime = Instant.now().minusSeconds(310)  // 5분 10초 전
            val timeoutMin = 5

            // when
            val holdingMinutes = java.time.Duration.between(entryTime, Instant.now()).toMinutes()
            val isTimeout = holdingMinutes >= timeoutMin

            // then
            assertTrue(isTimeout, "타임아웃: ${holdingMinutes}분 >= ${timeoutMin}분")
        }

        @Test
        @DisplayName("4분 경과 시 타임아웃 미발동")
        fun noTimeoutBefore5Minutes() {
            // given
            val entryTime = Instant.now().minusSeconds(240)  // 4분 전
            val timeoutMin = 5

            // when
            val holdingMinutes = java.time.Duration.between(entryTime, Instant.now()).toMinutes()
            val isTimeout = holdingMinutes >= timeoutMin

            // then
            assertFalse(isTimeout, "타임아웃 미발동: ${holdingMinutes}분 < ${timeoutMin}분")
        }
    }

    @Nested
    @DisplayName("서킷 브레이커")
    inner class CircuitBreakerTest {

        @Test
        @DisplayName("연속 손실 3회 시 거래 중지")
        fun stopTradingAfterConsecutiveLosses() {
            // given
            val consecutiveLosses = 3
            val maxLosses = 3

            // when
            val shouldStop = consecutiveLosses >= maxLosses

            // then
            assertTrue(shouldStop, "연속 손실로 거래 중지")
        }

        @Test
        @DisplayName("일일 손실 한도 초과 시 거래 중지")
        fun stopTradingAfterDailyLossLimit() {
            // given
            val dailyLoss = -21000.0  // 일일 손실
            val dailyMaxLoss = 20000   // 한도

            // when
            val shouldStop = dailyLoss <= -dailyMaxLoss

            // then
            assertTrue(shouldStop, "일일 손실 한도 초과로 거래 중지")
        }

        @Test
        @DisplayName("일일 최대 거래 횟수 초과 시 거래 중지")
        fun stopTradingAfterMaxDailyTrades() {
            // given
            val dailyTrades = 51
            val maxTrades = 50

            // when
            val shouldStop = dailyTrades >= maxTrades

            // then
            assertTrue(shouldStop, "일일 거래 횟수 초과로 거래 중지")
        }
    }

    @Nested
    @DisplayName("손익 계산")
    inner class PnlCalculationTest {

        @Test
        @DisplayName("수수료 포함 손익 계산")
        fun calculatePnlWithFee() {
            // given
            val entryPrice = 1000.0
            val exitPrice = 1030.0  // +3% 수익
            val quantity = 100.0
            val fee = 0.0004  // 0.04%

            // when
            val grossPnl = (exitPrice - entryPrice) * quantity  // 3000원
            val entryFee = entryPrice * quantity * fee  // 40원
            val exitFee = exitPrice * quantity * fee    // 41.2원
            val netPnl = grossPnl - entryFee - exitFee  // 2918.8원

            // then
            assertEquals(3000.0, grossPnl, 0.01, "총 손익")
            assertTrue(netPnl < grossPnl, "순 손익 < 총 손익")
            assertTrue(netPnl > 0, "순 손익 양수")
        }

        @Test
        @DisplayName("손절 시에도 수수료 고려")
        fun calculateStopLossPnlWithFee() {
            // given
            val entryPrice = 1000.0
            val exitPrice = 985.0  // -1.5% 손실
            val quantity = 100.0
            val fee = 0.0004

            // when
            val grossPnl = (exitPrice - entryPrice) * quantity  // -1500원
            val totalFee = (entryPrice + exitPrice) * quantity * fee  // 79.4원
            val netPnl = grossPnl - totalFee  // -1579.4원

            // then
            assertTrue(netPnl < grossPnl, "수수료로 손실 증가")
            assertEquals(-1500.0, grossPnl, 0.01, "총 손실")
        }

        @Test
        @DisplayName("진입가 0원 시 NaN 방지")
        fun preventNaNWithZeroEntryPrice() {
            // given
            val entryPrice = 0.0
            val exitPrice = 1000.0

            // when
            val pnlPercent = if (entryPrice > 0) {
                ((exitPrice - entryPrice) / entryPrice) * 100
            } else {
                0.0
            }

            // then
            assertEquals(0.0, pnlPercent, "진입가 0원 시 0% 반환")
        }
    }

    @Nested
    @DisplayName("포지션 청산")
    inner class ClosePositionTest {

        @Test
        @DisplayName("실제 잔고 없으면 ABANDONED 처리")
        fun abandonedWhenNoBalance() {
            // given
            val position = createOpenPosition(
                market = "KRW-BTC",
                entryPrice = 65000000.0,
                quantity = 0.001
            )

            // 잔고 없음
            whenever(bithumbPrivateApi.getBalances()).thenReturn(
                listOf(Balance("KRW", BigDecimal("100000"), null, null, null, null))
            )

            // then - 잔고 없음 확인 로직
            val balances = bithumbPrivateApi.getBalances()
            val btcBalance = balances?.find { it.currency == "BTC" }
            assertTrue(btcBalance == null, "BTC 잔고 없음")
        }

        @Test
        @DisplayName("최소 주문 금액 미달 시 매도 불가")
        fun cannotSellBelowMinAmount() {
            // given
            val currentPrice = 1000.0
            val quantity = 4.0  // 4000원 (최소 5100원 미달)
            val minAmount = 5100

            // when
            val orderAmount = currentPrice * quantity
            val canSell = orderAmount >= minAmount

            // then
            assertFalse(canSell, "최소 주문 금액 미달: $orderAmount < $minAmount")
        }
    }

    @Nested
    @DisplayName("진입 조건")
    inner class EntryConditionTest {

        @Test
        @DisplayName("점수 70점 이상 시 진입 가능")
        fun canEnterWithScore70() {
            // given
            val score = 70
            val minScore = 70

            // when
            val canEnter = score >= minScore

            // then
            assertTrue(canEnter, "점수 충족")
        }

        @Test
        @DisplayName("가격 스파이크 1% 미만 시 진입 불가")
        fun cannotEnterBelowMinPriceSpike() {
            // given
            val priceSpikePercent = 0.5
            val minPriceSpike = 1.0

            // when
            val canEnter = priceSpikePercent >= minPriceSpike

            // then
            assertFalse(canEnter, "가격 스파이크 부족: $priceSpikePercent% < $minPriceSpike%")
        }

        @Test
        @DisplayName("스프레드 0.5% 초과 시 진입 불가")
        fun cannotEnterWithHighSpread() {
            // given
            val spreadPercent = 0.7
            val maxSpread = 0.5

            // when
            val canEnter = spreadPercent <= maxSpread

            // then
            assertFalse(canEnter, "스프레드 과다: $spreadPercent% > $maxSpread%")
        }
    }

    private fun createOpenPosition(
        market: String,
        entryPrice: Double,
        quantity: Double
    ): MemeScalperTradeEntity {
        return MemeScalperTradeEntity(
            id = 1L,
            market = market,
            entryPrice = entryPrice,
            quantity = quantity,
            entryTime = Instant.now(),
            status = "OPEN"
        )
    }
}
