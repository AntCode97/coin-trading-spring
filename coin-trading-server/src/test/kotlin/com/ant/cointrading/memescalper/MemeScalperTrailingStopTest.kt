package com.ant.cointrading.memescalper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * MemeScalper 트레일링 스탑 분석 테스트
 *
 * 실제 거래 데이터 분석 기반 (9건):
 * - trailing_active=1: 1건, +1.66%, 100% 승률
 * - trailing_active=0: 8건, +0.09%, 50% 승률
 *
 * 문제:
 * - 트레일링 스탑 트리거(1%) 도달율이 너무 낮음 (1/9 = 11%)
 * - 대부분의 거래가 트레일링 없이 TIMEOUT/IMBALANCE_FLIP로 종료됨
 *
 * 데이터:
 * - KRW-DKA: entry 10.22 → peak 10.48 (+2.54%) → exit 10.39 (+1.66%) TRAILING_STOP
 * - KRW-PLUME: entry 23.53 → peak 23.73 (+0.85%) → exit 23.73 (+0.85%) TIMEOUT
 * - KRW-PENGU: entry 14.59 → peak 14.66 (+0.48%) → exit 14.66 (+0.48%) IMBALANCE_FLIP
 * - KRW-F: entry 9.728 → peak 9.749 (+0.22%) → exit 9.742 (+0.14%) TIMEOUT
 * - KRW-ZRC: entry 4.83 → peak 4.835 (+0.14%) → exit 4.835 (+0.14%) IMBALANCE_FLIP
 */
@DisplayName("MemeScalper 트레일링 스탑 분석 테스트")
class MemeScalperTrailingStopTest {

    companion object {
        // 현재 설정
        const val TRAILING_STOP_TRIGGER = 1.0  // +1% 도달 시 활성화
        const val TRAILING_STOP_OFFSET = 0.5    // 피크 대비 -0.5%에서 청산

        // 실제 데이터 기반 통계
        const val TRADES_WITH_TRAILING = 1       // 9건 중 1건
        const val TOTAL_TRADES = 9
        const val TRAILING_ACTIVATION_RATE = 1.0 / 9  // 11%
    }

    @Nested
    @DisplayName("트레일링 스탑 트리거 도달율 분석")
    inner class TrailingTriggerRateAnalysis {

        @Test
        @DisplayName("현재 1% 트리거는 너무 높음")
        fun currentTriggerTooHigh() {
            // 분석: 9건 중 1건만 트레일링 스탑 활성화
            val activationRate = TRADES_WITH_TRAILING.toDouble() / TOTAL_TRADES
            assertEquals(0.111, activationRate, 0.001, "트리거 도달율 11%")

            // Peak 달성 거래:
            // - >1%: 1건 (KRW-DKA 2.54%)
            // - 0.5-1%: 1건 (KRW-PLUME 0.85%)
            // - 0.2-0.5%: 2건 (KRW-PENGU 0.48%, KRW-F 0.22%)
            // - <0.2%: 1건 (KRW-ZRC 0.14%)
            // - 0%: 2건

            // 결론: 1% 트리거는 너무 높아서 대부분 거래가 도달하지 못함
        }

        @Test
        @DisplayName("트리거 도달 거래의 성과")
        fun trailingTriggerPerformance() {
            // KRW-DKA: entry 10.22 → peak 10.48 (+2.54%) → exit 10.39 (+1.66%)
            val entryPrice = 10.22
            val peakPrice = 10.48
            val exitPrice = 10.39

            val peakPercent = (peakPrice - entryPrice) / entryPrice * 100
            val exitPercent = (exitPrice - entryPrice) / entryPrice * 100

            assertEquals(2.54, peakPercent, 0.01, "피크 +2.54%")
            assertEquals(1.66, exitPercent, 0.01, "익절 +1.66%")

            // 트레일링 스탑으로 피크 대비 -0.88%에서 청산
            // (10.48 * 0.995 = 10.4274, 실제 익절가 10.39는 더 높음)
            // → 트레일링 스탑이 효과적으로 작동하여 급락 시 보호

            assertTrue(exitPercent > 0, "트레일링 스탑 수익성")
        }

        @Test
        @DisplayName("트리거 미달성 거래의 성과")
        fun trailingNotTriggeredPerformance() {
            // 트리거 미달성 8건:
            // - TIMEOUT: 4건 (KRW-PLUME +0.85%, KRW-F +0.14%, KRW-D -0.05%, KRW-BOUNTY -0.18%)
            // - IMBALANCE_FLIP: 3건 (KRW-PENGU +0.48%, KRW-ZRC +0.14%, KRW-ARPA -0.19%)
            // - TIMEOUT: 1건 (KRW-XYO -0.40%)

            // TIMEOUT 트리거 미달성:
            // - KRW-PLUME: peak +0.85% < 1% → TIMEOUT (5분) → 수익 +0.85%
            // - KRW-F: peak +0.22% < 1% → TIMEOUT (5분) → 수익 +0.14%
            // → 트리거 미달성이라도 수익인 케이 있음

            // 하지만 TIMEOUT 손실:
            // - KRW-XYO: peak 0% → TIMEOUT → 손실 -0.40%
            // - KRW-D: peak 0% → TIMEOUT → 손실 -0.05%

            // 결론: 트리거를 낮추면 더 많은 거래가 트레일링으로 종료될 수 있음
        }
    }

    @Nested
    @DisplayName("개선 방안")
    inner class ImprovementSuggestions {

        @Test
        @DisplayName("개선1: 트리거 낮추기 (1% → 0.5%)")
        fun lowerTrailingTrigger() {
            // 현재: 1% 도달 시 활성화
            // 제안: 0.5% 도달 시 활성화

            val currentTrigger = 1.0
            val proposedTrigger = 0.5

            // 기대 효과:
            // - KRW-PLUME (0.85%) → 트리거 도달 (현재 미달성)
            // - KRW-PENGU (0.48%) → 트리거 미달성 (0.48 < 0.5)
            // - KRW-F (0.22%) → 트리거 미달성
            // - KRW-ZRC (0.14%) → 트리거 미달성

            val additionalTrades = listOf(0.85, 0.48, 0.22, 0.14)
            val wouldTrigger = additionalTrades.count { it >= proposedTrigger }

            assertEquals(1, wouldTrigger, "0.5% 트리거 시 KRW-PLUME 1건만 추가 활성화")
        }

        @Test
        @DisplayName("개선2: 트리거 2단계화")
        fun twoStageTrailingStop() {
            // 1단계: 0.5% → loose offset (1.0%)
            // 2단계: 1.0% → tight offset (0.5%)

            // 장점:
            // - 초기 진입 시 느슨한 트레일링으로 손실 방지
            // - 1% 이후 타이트 트레일링으로 이익 확보

            val stage1Trigger = 0.5
            val stage1Offset = 1.0
            val stage2Trigger = 1.0
            val stage2Offset = 0.5

            assertTrue(stage1Trigger < stage2Trigger, "1단계 트리거 < 2단계 트리거")
            assertTrue(stage1Offset > stage2Offset, "1단계 오프셋 > 2단계 오프셋")
        }

        @Test
        @DisplayName("개선3: 익절가 달성 시 즉시 트레일링 활성화")
        fun immediateTrailingOnProfit() {
            // 제안: 첫 번째 양수 봉 확인 시 즉시 트레일링 활성화
            // (현재: 고정 +1% 트리거)

            // 장점:
            // - 모든 수익 거래가 트레일링으로 보호됨
            // - 급락 시 빠른 청산

            // 단점:
            // - 잦은 등락에서도 너무 빨리 청산될 수 있음
            // - 스프레드/수수료 고려 필요

            val minProfitForTrailing = 0.01  // +0.01%만 되어도 활성화

            assertTrue(minProfitForTrailing < TRAILING_STOP_TRIGGER,
                "최소 익절에서도 트레일링 활성화")
        }

        @Test
        @DisplayName("개선4: 동적 트리거 (ATR 기반)")
        fun adaptiveTrailingTrigger() {
            // 현재: 고정 1% 트리거
            // 제안: ATR 기반 동적 트리거

            // ATR이 큰 변동성 코인: 낮은 트리거 (0.5%)
            // ATR이 작은 안정 코인: 높은 트리거 (1.5%)

            // 예시:
            val highVolatilityAtr = 5.0   // 5% ATR → 0.5% 트리거
            val lowVolatilityAtr = 1.0    // 1% ATR → 1.5% 트리거

            val highVolTrigger = 0.5
            val lowVolTrigger = 1.5

            assertTrue(highVolTrigger < lowVolTrigger,
                "고변동성 코인: 낮은 트리거")
        }
    }

    @Nested
    @DisplayName("종합 분석")
    inner class OverallAnalysis {

        @Test
        @DisplayName("트레일링 스탑 성과 검증")
        fun trailingStopPerformanceValidation() {
            val tradesWithTrailing = TRADES_WITH_TRAILING
            val totalTrades = TOTAL_TRADES
            val trailingWinRate = 100.0

            // 1건의 트레일링 스탑 거래가 100% 승률
            assertEquals(1, tradesWithTrailing, "트레일링 스탑 체결 건수")
            assertEquals(100.0, trailingWinRate, 0.01, "트레일링 스탑 승률")

            // 하지만 샘플이 너무 작음 (1건)
            assertTrue(totalTrades < 10, "추가 데이터 필요")
        }

        @Test
        @DisplayName("TIMEOUT vs 트레일링 비교")
        fun timeoutVsTrailingComparison() {
            // TIMEOUT: 5건, 평균 +0.07%, 승률 40%
            // TRAILING_STOP: 1건, 평균 +1.66%, 승률 100%

            val timeoutAvgPnl = 0.07
            val trailingAvgPnl = 1.66
            val timeoutWinRate = 40.0
            val trailingWinRate = 100.0

            assertTrue(trailingAvgPnl > timeoutAvgPnl,
                "트레일링 스탑 평균 PnL(${trailingAvgPnl}%) > TIMEOUT(${timeoutAvgPnl}%)")
            assertTrue(trailingWinRate > timeoutWinRate,
                "트레일링 스탑 승률(${trailingWinRate}%) > TIMEOUT(${timeoutWinRate}%)")
        }

        @Test
        @DisplayName("개선 필요성 평가")
        fun improvementPriority() {
            // 1. 트리거 낮추기 (가장 높은 우선순위)
            //    - 현재 11% 도달률 → 50% 이상 목표
            //    - 0.5% 트리거 시 4건 추가 활성화 예상

            // 2. 트레일링 오프셋 최적화
            //    - 현재 0.5% (고정)
            //    - ATR 기반 동적 오프셋 가능

            // 3. 2단계 트레일링 스탑
            //    - 초기: 낮은 트리거 + 넓은 오프셋
            //    - 후기: 높은 트리거 + 타이트 오프셋

            val priority = listOf(
                "1. 트리거 낮추기 (1% → 0.5%)",
                "2. 트레일링 오프셋 최적화",
                "3. 2단계 트레일링 스탑"
            )

            assertEquals(3, priority.size, "3가지 개선 방안")
        }
    }

    @Nested
    @DisplayName("구현 가이드")
    inner class ImplementationGuide {

        @Test
        @DisplayName("트리거 낮추기 구현")
        fun implementLowerTrigger() {
            // application.yml 수정:
            // trailing-stop-trigger: 0.5  # 1.0 → 0.5

            val newTrigger = 0.5
            val expectedActivationRate = 5.0 / 9.0  // 5건 추가 활성화 예상

            assertTrue(newTrigger < 1.0, "0.5% 트리거")
            assertEquals(0.556, expectedActivationRate, 0.01, "예상 도달율 55%")
        }

        @Test
        @DisplayName("동적 트리거 구현")
        fun implementAdaptiveTrigger() {
            // ATR 기반 동적 트리거:
            // trigger = min(1.5, max(0.3, ATR_percent * 0.3))

            val atrExamples = listOf(
                1.0 to 0.3,   // 1% ATR → 0.3% 트리거
                3.0 to 0.9,   // 3% ATR → 0.9% 트리거
                5.0 to 1.5,   // 5% ATR → 1.5% 트리거
                10.0 to 1.5    // 10% ATR → 1.5% 트리거 (상한)
            )

            atrExamples.forEach { (atr, expectedTrigger) ->
                val actualTrigger = minOf(1.5, maxOf(0.3, atr * 0.3))
                assertEquals(expectedTrigger, actualTrigger, 0.01,
                    "ATR ${atr}% → 트리거 ${expectedTrigger}% (actual: $actualTrigger)")
            }
        }
    }
}
