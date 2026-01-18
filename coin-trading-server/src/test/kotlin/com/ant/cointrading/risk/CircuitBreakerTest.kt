package com.ant.cointrading.risk

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CircuitBreaker 단위 테스트
 *
 * 퀀트 리스크 관리의 핵심 - 무한 루프 및 연속 손실 방지
 *
 * 테스트 시나리오:
 * 1. 연속 3회 손실 시 서킷 발동
 * 2. 일일 손실 5% 초과 시 서킷 발동
 * 3. 연속 5회 주문 실행 실패 시 서킷 발동
 * 4. 연속 3회 고슬리피지 시 서킷 발동
 * 5. API 에러 과다 시 글로벌 서킷 발동
 * 6. 수익 거래 후 연속 손실 카운터 리셋
 * 7. 쿨다운 후 자동 리셋
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CircuitBreaker - 리스크 관리")
class CircuitBreakerTest {

    @Mock
    private lateinit var tradeRepository: TradeRepository

    @Mock
    private lateinit var slackNotifier: SlackNotifier

    private lateinit var circuitBreaker: CircuitBreaker

    @BeforeEach
    fun setup() {
        circuitBreaker = CircuitBreaker(tradeRepository, slackNotifier)
    }

    @Nested
    @DisplayName("연속 손실 트리거")
    inner class ConsecutiveLossTriggerTest {

        @Test
        @DisplayName("연속 3회 손실 시 서킷 발동")
        fun tripAfter3ConsecutiveLosses() {
            val market = "KRW-BTC"

            // 1차 손실
            circuitBreaker.recordTradeResult(market, -1.0)
            assertTrue(circuitBreaker.canTrade(market).canTrade)

            // 2차 손실
            circuitBreaker.recordTradeResult(market, -0.5)
            assertTrue(circuitBreaker.canTrade(market).canTrade)

            // 3차 손실 -> 서킷 발동
            circuitBreaker.recordTradeResult(market, -0.8)
            assertFalse(circuitBreaker.canTrade(market).canTrade)

            // Slack 알림 발송 확인
            verify(slackNotifier).sendError(eq(market), any())
        }

        @Test
        @DisplayName("수익 거래 후 연속 손실 카운터 리셋")
        fun resetCounterAfterProfit() {
            val market = "KRW-BTC"

            // 2회 손실
            circuitBreaker.recordTradeResult(market, -1.0)
            circuitBreaker.recordTradeResult(market, -0.5)

            // 1회 수익 -> 카운터 리셋
            circuitBreaker.recordTradeResult(market, 2.0)

            // 다시 2회 손실 -> 서킷 미발동
            circuitBreaker.recordTradeResult(market, -1.0)
            circuitBreaker.recordTradeResult(market, -0.5)
            assertTrue(circuitBreaker.canTrade(market).canTrade)
        }

        @Test
        @DisplayName("매수 직후 매도 시나리오 - 3회 연속 손실 시 자동 중단")
        fun stopBuySellLoop() {
            val market = "KRW-BTC"

            // 매수 직후 매도 반복 시나리오 (각 -0.1% 손실 가정)
            // 첫 번째 왕복
            circuitBreaker.recordTradeResult(market, -0.1)
            assertTrue(circuitBreaker.canTrade(market).canTrade, "1회 손실 후 거래 가능해야 함")

            // 두 번째 왕복
            circuitBreaker.recordTradeResult(market, -0.1)
            assertTrue(circuitBreaker.canTrade(market).canTrade, "2회 손실 후 거래 가능해야 함")

            // 세 번째 왕복 -> 서킷 발동
            circuitBreaker.recordTradeResult(market, -0.1)
            assertFalse(circuitBreaker.canTrade(market).canTrade, "3회 연속 손실 후 거래 중지")

            val result = circuitBreaker.canTrade(market)
            assertTrue(result.reason.contains("연속"))
        }
    }

    @Nested
    @DisplayName("일일 손실 한도")
    inner class DailyLossLimitTest {

        @Test
        @DisplayName("일일 손실 5% 초과 시 서킷 발동")
        fun tripAfterDailyLossExceeds5Percent() {
            val market = "KRW-BTC"

            // 단번에 5% 손실
            circuitBreaker.recordTradeResult(market, -5.5)
            assertFalse(circuitBreaker.canTrade(market).canTrade)
        }

        @Test
        @DisplayName("누적 손실 5% 초과 시 서킷 발동")
        fun tripAfterCumulativeLossExceeds5Percent() {
            val market = "KRW-BTC"

            // 누적 손실: 2% + 2% + 1.5% = 5.5%
            circuitBreaker.recordTradeResult(market, -2.0)
            circuitBreaker.recordTradeResult(market, 1.0)  // 중간에 수익 있어도
            circuitBreaker.recordTradeResult(market, -2.0)
            circuitBreaker.recordTradeResult(market, 0.5)  // 중간에 수익 있어도
            circuitBreaker.recordTradeResult(market, -1.5)

            // 누적 손실 5.5% 초과
            assertFalse(circuitBreaker.canTrade(market).canTrade)
        }
    }

    @Nested
    @DisplayName("주문 실행 실패 트리거")
    inner class ExecutionFailureTriggerTest {

        @Test
        @DisplayName("연속 5회 주문 실행 실패 시 서킷 발동")
        fun tripAfter5ConsecutiveFailures() {
            val market = "KRW-BTC"

            for (i in 1..4) {
                circuitBreaker.recordExecutionFailure(market, "주문 실패 $i")
                assertTrue(circuitBreaker.canTrade(market).canTrade)
            }

            // 5회 실패 -> 서킷 발동
            circuitBreaker.recordExecutionFailure(market, "주문 실패 5")
            assertFalse(circuitBreaker.canTrade(market).canTrade)
        }

        @Test
        @DisplayName("주문 성공 후 실패 카운터 리셋")
        fun resetFailureCounterAfterSuccess() {
            val market = "KRW-BTC"

            // 4회 실패
            for (i in 1..4) {
                circuitBreaker.recordExecutionFailure(market, "주문 실패")
            }

            // 1회 성공 -> 카운터 리셋
            circuitBreaker.recordExecutionSuccess(market)

            // 다시 4회 실패 -> 서킷 미발동
            for (i in 1..4) {
                circuitBreaker.recordExecutionFailure(market, "주문 실패")
            }
            assertTrue(circuitBreaker.canTrade(market).canTrade)
        }
    }

    @Nested
    @DisplayName("슬리피지 트리거")
    inner class SlippageTriggerTest {

        @Test
        @DisplayName("연속 3회 2% 이상 슬리피지 시 서킷 발동")
        fun tripAfter3ConsecutiveHighSlippage() {
            val market = "KRW-BTC"

            circuitBreaker.recordSlippage(market, 2.5)
            assertTrue(circuitBreaker.canTrade(market).canTrade)

            circuitBreaker.recordSlippage(market, 3.0)
            assertTrue(circuitBreaker.canTrade(market).canTrade)

            circuitBreaker.recordSlippage(market, 2.1)
            assertFalse(circuitBreaker.canTrade(market).canTrade)
        }

        @Test
        @DisplayName("정상 슬리피지 후 카운터 리셋")
        fun resetSlippageCounterAfterNormal() {
            val market = "KRW-BTC"

            circuitBreaker.recordSlippage(market, 2.5)
            circuitBreaker.recordSlippage(market, 3.0)

            // 정상 슬리피지 -> 카운터 리셋
            circuitBreaker.recordSlippage(market, 0.5)

            circuitBreaker.recordSlippage(market, 2.5)
            circuitBreaker.recordSlippage(market, 3.0)
            assertTrue(circuitBreaker.canTrade(market).canTrade)
        }
    }

    @Nested
    @DisplayName("API 에러 트리거")
    inner class ApiErrorTriggerTest {

        @Test
        @DisplayName("1분 내 10회 API 에러 시 글로벌 서킷 발동")
        fun tripGlobalAfterApiErrors() {
            val market = "KRW-BTC"

            for (i in 1..10) {
                circuitBreaker.recordApiError(market)
            }

            // 글로벌 서킷 발동으로 다른 마켓도 거래 불가
            assertFalse(circuitBreaker.canTrade("KRW-ETH").canTrade)
            verify(slackNotifier).sendSystemNotification(any(), any())
        }
    }

    @Nested
    @DisplayName("총 자산 변화 트리거")
    inner class TotalAssetTriggerTest {

        @Test
        @DisplayName("최고점 대비 10% 감소 시 글로벌 서킷 발동")
        fun tripGlobalAfterDrawdown() {
            // 초기 자산 설정
            circuitBreaker.recordTotalAsset(1000000.0)

            // 자산 증가 (최고점 갱신)
            circuitBreaker.recordTotalAsset(1100000.0)

            // 10% 하락 (110만 -> 99만)
            circuitBreaker.recordTotalAsset(990000.0)

            assertFalse(circuitBreaker.canTrade("KRW-BTC").canTrade)
        }
    }

    @Nested
    @DisplayName("마켓 간 격리")
    inner class MarketIsolationTest {

        @Test
        @DisplayName("한 마켓의 서킷 발동이 다른 마켓에 영향 없음")
        fun isolatedCircuitBreakers() {
            // BTC 마켓에서 3회 손실 -> 서킷 발동
            circuitBreaker.recordTradeResult("KRW-BTC", -2.0)
            circuitBreaker.recordTradeResult("KRW-BTC", -2.0)
            circuitBreaker.recordTradeResult("KRW-BTC", -2.0)

            assertFalse(circuitBreaker.canTrade("KRW-BTC").canTrade)
            assertTrue(circuitBreaker.canTrade("KRW-ETH").canTrade)  // ETH는 영향 없음
        }

        @Test
        @DisplayName("2개 마켓 서킷 발동 시 글로벌 서킷 발동")
        fun globalCircuitAfterMultipleMarkets() {
            // BTC 서킷 발동
            for (i in 1..3) {
                circuitBreaker.recordTradeResult("KRW-BTC", -2.0)
            }

            // ETH 서킷 발동 -> 글로벌 서킷 발동
            for (i in 1..3) {
                circuitBreaker.recordTradeResult("KRW-ETH", -2.0)
            }

            // 모든 마켓 거래 불가
            assertFalse(circuitBreaker.canTrade("KRW-XRP").canTrade)
        }
    }

    @Nested
    @DisplayName("상태 조회")
    inner class StatusTest {

        @Test
        @DisplayName("상태 조회 결과에 모든 정보 포함")
        fun statusContainsAllInfo() {
            circuitBreaker.recordTradeResult("KRW-BTC", -1.0)
            circuitBreaker.recordExecutionFailure("KRW-ETH", "테스트 실패")
            circuitBreaker.recordTotalAsset(1000000.0)

            val status = circuitBreaker.getStatus()

            assertTrue(status.containsKey("globalCircuitOpen"))
            assertTrue(status.containsKey("marketStates"))
            assertTrue(status.containsKey("initialTotalAsset"))
            assertTrue(status.containsKey("peakTotalAsset"))
        }
    }
}
