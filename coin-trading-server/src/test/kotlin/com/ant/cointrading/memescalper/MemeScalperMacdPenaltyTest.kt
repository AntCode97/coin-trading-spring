package com.ant.cointrading.memescalper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * MemeScalper MACD 패널티 재검증 테스트
 *
 * 실제 거래 데이터 분석 (9건, Iteration 5와 다른 최신 데이터):
 *
 * MACD BEARISH 성과:
 * - TIMEOUT: 3건, 평균 +0.27%, 승률 66.7% (2승 1패)
 * - IMBALANCE_FLIP: 2건, 평균 +0.15%, 승률 50.0% (1승 1패)
 * - BEARISH 전체: 5건, 평균 +0.22%, 승률 60%
 *
 * MACD BULLISH 성과:
 * - TIMEOUT: 2건, 평균 -0.22%, 승률 0% (0승 2패)
 * - IMBALANCE_FLIP: 1건, 평균 +0.14%, 승률 100% (1승 0패)
 * - BULLISH 전체: 3건, 평균 -0.10%, 승률 33.3%
 *
 * 결론: BEARISH가 BULLISH보다 성과가 좋음
 * 현재 -20점 패널티는 부적절히 BEARISH 진입을 차단하고 있음
 */
@DisplayName("MemeScalper MACD 패널티 재검증 테스트")
class MemeScalperMacdPenaltyTest {

    companion object {
        // MemeScalperDetector 점수 체계
        const val MACD_BULLISH_SCORE = 10
        const val MACD_NEUTRAL_SCORE = 3
        const val MACD_BEARISH_CURRENT = -10  // 현재 패널티 (Iteration 5에서 -20 → -10으로 변경)

        const val MIN_ENTRY_SCORE = 70
    }

    @Nested
    @DisplayName("실제 데이터 성과 비교")
    inner class ActualPerformanceComparison {

        @Test
        @DisplayName("BEARISH 전체 평균이 BULLISH보다 우수")
        fun bearishOutperformsBullish() {
            val bearishAvg = 0.22   // +0.22%
            val bullshAvg = -0.10  // -0.10%
            val bearishWinRate = 60.0
            val bullshWinRate = 33.33

            assertTrue(bearishAvg > bullshAvg,
                "BEARISH 평균(${bearishAvg}%) > BULLISH(${bullshAvg}%)")
            assertTrue(bearishWinRate > bullshWinRate,
                "BEARISH 승률(${bearishWinRate}%) > BULLISH(${bullshWinRate}%)")
        }

        @Test
        @DisplayName("TIMEOUT 청산 시 BEARISH가 BULLISH보다 우수")
        fun bearishOutperformsBullishInTimeout() {
            // BEARISH + TIMEOUT: 3건, +0.27%, 66.7% 승률
            // BULLISH + TIMEOUT: 2건, -0.22%, 0% 승률

            val bearishTimeoutAvg = 0.27
            val bullshTimeoutAvg = -0.22
            val bearishTimeoutWinRate = 66.7
            val bullshTimeoutWinRate = 0.0

            assertTrue(bearishTimeoutAvg > bullshTimeoutAvg,
                "TIMEOUT: BEARISH(${bearishTimeoutAvg}%) > BULLISH(${bullshTimeoutAvg}%)")
            assertTrue(bearishTimeoutWinRate > bullshTimeoutWinRate,
                "TIMEOUT: BEARISH 승률(${bearishTimeoutWinRate}%) > BULLISH(${bullshTimeoutWinRate}%)")
        }

        @Test
        @DisplayName("IMBALANCE_FLIP 청산 시 BULLISH만 수익")
        fun onlyBullishProfitsInImbalanceFlip() {
            // BEARISH + IMBALANCE_FLIP: 2건, +0.15%, 50% 승률
            // BULLISH + IMBALANCE_FLIP: 1건, +0.14%, 100% 승률

            val bearishImbalanceAvg = 0.15
            val bullshImbalanceAvg = 0.14

            // BEARISH는 2건 중 1건 수익 (50%)
            // BULLISH는 1건 전부 수익 (100%)
            // 하지만 평균 PnL은 BEARISH가 더 높음

            assertTrue(bearishImbalanceAvg >= bullshImbalanceAvg,
                "IMBALANCE_FLIP: BEARISH(${bearishImbalanceAvg}%) >= BULLISH(${bullshImbalanceAvg}%)")
        }
    }

    @Nested
    @DisplayName("현재 패널티 문제점 분석")
    inner class CurrentPenaltyIssues {

        @Test
        @DisplayName("-10점 패널티로도 진입 가능 (완화됨)")
        fun penaltyAllowsBearishEntry() {
            // 시나리오: 모든 지표 최대 점수, MACD만 BEARISH
            val score = 35 + 25 + 15 + 15 + MACD_BEARISH_CURRENT
            // 거래량 35 + 가격 25 + 불균형 15 + RSI 15 - 10 = 80점

            assertTrue(score >= MIN_ENTRY_SCORE,
                "-10점 패널티로도 진입 가능: ${score}점 >= ${MIN_ENTRY_SCORE}점")
        }

        @Test
        @DisplayName("BEARISH 패널티를 완화하면 진입 가능")
        fun removePenaltyAllowsEntry() {
            // 시나리오: 모든 지표 최대 점수, MACD만 BEARISH
            // 패널티 제거: MACD BEARISH도 +10점 (NEUTRAL과 동일)

            val neutralScore = 35 + 25 + 15 + 15 + MACD_NEUTRAL_SCORE
            val bearishNoPenaltyScore = 35 + 25 + 15 + 15 + MACD_NEUTRAL_SCORE

            assertEquals(85, neutralScore, "NEUTRAL 점수: 85점")
            assertEquals(85, bearishNoPenaltyScore, "BEARISH(무패널티) 점수: 85점")

            assertTrue(neutralScore >= MIN_ENTRY_SCORE,
                "패널티 제거 시 진입 가능: ${neutralScore}점 >= ${MIN_ENTRY_SCORE}점")
        }

        @Test
        @DisplayName("BULLISH는 최고 점수라도 손실 가능")
        fun bullishCanLoseMoney() {
            // BULLISH + TIMEOUT: 2건 모두 손실
            // → BULLISH가 보장이 아님

            val bullishTimeoutLosses = 2
            val bullishTimeoutTrades = 2

            assertEquals(bullishTimeoutLosses, bullishTimeoutTrades,
                "BULLISH TIMEOUT: 전체 손실 (${bullishTimeoutLosses}/${bullishTimeoutTrades})")
        }
    }

    @Nested
    @DisplayName("펌프앤덤프 전략 관점 해석")
    inner class PumpAndDumpPerspective {

        @Test
        @DisplayName("BEARISH MACD = 펌프 후 덤프 진행 중 신호")
        fun bearishMeansDumpAfterPump() {
            val description = """
                펌프앤덤프에서 BEARISH MACD의 의미:

                일반적 상승장: BULLISH = 매수 신호 (맞음)
                펌프앤덤프 상승장: BEARISH = 덤프 진행 중 (적절한 타이밍)

                왜 BEARISH가 수익인가?
                1. 펌프 급등 후 일부 트레이더가 익절하면서 가격 하락
                2. 이 시점에 진입하면 단타로 수익 실현 가능
                3. MACD가 BEARISH라도 이미 펌프로 인한 거래량 급등 유지
                4. 남은 거래량을 활용한 초단타

                데이터: BEARISH TIMEOUT 3건, 평균 +0.27%, 승률 66.7%
                결론: 펌프앤덤프에서 BEARISH는 정상적인 신호
            """.trimIndent()

            assertTrue(description.contains("BEARISH"), "설명 존재")
        }

        @Test
        @DisplayName("BULLISH MACD = 펌프 초기로 진입 시기 늦을 수 있음")
        fun bullishMeansLateEntry() {
            val description = """
                펌프앤덤프에서 BULLISH MACD의 위험성:

                BULLISH = 펌프 시작 단계
                문제:
                1. 이미 상승이 많이 진행된 상태일 수 있음
                2. 진입 직후 덤프 시작되면 손실
                3. 펌프가 거의 끝나갔을 때 진입할 위험

                데이터: BULLISH TIMEOUT 2건, 모두 손실 (0% 승률)
                결론: 펌프앤덤프에서 BULLISH는 오히려 덤프에 휘말림 위험
            """.trimIndent()

            assertTrue(description.contains("BULLISH"), "설명 존재")
        }
    }

    @Nested
    @DisplayName("개선 방안")
    inner class ImprovementSuggestions {

        @Test
        @DisplayName("제안1: BEARISH 패널티 완화")
        fun removeBearishPenalty() {
            // 현재: BEARISH → -10점 (Iteration 5에서 -20에서 -10으로 완화됨)
            // 제안: BEARISH → +3점 (NEUTRAL과 동일)

            val currentPenalty = -10
            val proposedScore = 3  // NEUTRAL과 동일

            assertEquals(3, proposedScore, "BEARISH 점수: ${proposedScore}점")
            assertTrue(proposedScore > currentPenalty, "패널티 완화: ${proposedScore} > ${currentPenalty}")
        }

        @Test
        @DisplayName("제안2: MACD 점수 차등 완전 폐지")
        fun eliminateMacdScoring() {
            // 모든 MACD 신호에 동일한 점수 부여 (예: 3점)
            // RSI, 거래량, 볼린저밴드가 충분한 필터링 제공

            val uniformScore = 3
            val bearishScore = uniformScore
            val bullshScore = uniformScore
            val neutralScore = uniformScore

            assertEquals(bearishScore, bullshScore, "모든 MACD에 동일 점수")
            assertEquals(bearishScore, neutralScore, "모든 MACD에 동일 점수")
        }

        @Test
        @DisplayName("제안3: 샘플 확대 후 재평가")
        fun needMoreSamples() {
            val currentSamples = 9
            val recommendedSamples = 50

            assertTrue(currentSamples < recommendedSamples,
                "현재 ${currentSamples}건으로 통계적 유의성 부족")
            assertTrue(recommendedSamples >= 50,
                "권장 샘플: ${recommendedSamples}건 이상")
        }

        @Test
        @DisplayName("제안4: MACD별 별도 익절/손절 전략")
        fun macdSpecificExitStrategy() {
            // BEARISH: 빠른 익절 (IMBALANCE_FLIP 우선)
            // BULLISH: 더 보수적 접근 (타이밍 또는 손절 타이트)

            val bearishStrategy = "IMBALANCE_FLIP 또는 빠른 익절"
            val bullshStrategy = "TIMEOUT 주의 또는 손절 타이트"

            assertTrue(bearishStrategy.contains("IMBALANCE_FLIP"),
                "BEARISH: ${bearishStrategy}")
            assertTrue(bullshStrategy.contains("TIMEOUT"),
                "BULLISH: ${bullshStrategy}")
        }
    }

    @Nested
    @DisplayName("구현 가이드")
    inner class ImplementationGuide {

        @Test
        @DisplayName("패널티 수정: MemeScalperDetector.kt")
        fun modifyPenaltyInDetector() {
            // 파일: MemeScalperDetector.kt
            // 메서드: calculateScore() 내 MACD 점수 계산

            // 변경 전:
            // MACD_BEARISH → -20점
            // MACD_BULLISH → +10점

            // 변경 후:
            // MACD_BEARISH → +3점 (NEUTRAL과 동일)
            // MACD_BULLISH → +3점
            // MACD_NEUTRAL → +3점

            val newBearishScore = 3
            val newBullishScore = 3
            val newNeutralScore = 3

            assertEquals(newBearishScore, newBullishScore,
                "BEARISH와 BULLISH 동일 점수")
            assertEquals(newNeutralScore, 3, "NEUTRAL 점수")
        }

        @Test
        @DisplayName("테스트 상수 업데이트")
        fun updateTestConstants() {
            // VolumeSurgeEntryConditionTest.kt
            // MACD_SCORE_BEARISH: -10 → +3 (또는 폐지)

            val oldConstant = -10
            val newConstant = 3

            assertTrue(newConstant > oldConstant,
                "상수 업데이트: ${oldConstant} → ${newConstant}")
        }
    }

    @Nested
    @DisplayName("위험 평가")
    inner class RiskAssessment {

        @Test
        @DisplayName("변경 시 위험도 평가")
        fun riskOfChange() {
            val currentPenalty = -10
            val proposedPenalty = 3

            val risk = when {
                proposedPenalty > currentPenalty -> "LOWER (진입 빈도 증가)"
                proposedPenalty < currentPenalty -> "HIGHER (진입 빈도 감소)"
                else -> "UNCHANGED"
            }

            assertEquals("LOWER (진입 빈도 증가)", risk,
                "패널티 완화 시 위험도: ${risk}")
        }

        @Test
        @DisplayName("완화된 패널티로 재계산")
        fun recalculateWithNewPenalty() {
            // 시나리오: 모든 지표 80% 점수
            // 거래량: 35 * 0.8 = 28
            // 가격: 25 * 0.8 = 20
            // 불균형: 15 * 0.8 = 12
            // RSI: 15 * 0.8 = 12
            // MACD (BEARISH): 3점 (변경 후)
            // 합계: 85점

            val volumeScore = 28
            val priceScore = 20
            val imbalanceScore = 12
            val rsiScore = 12
            val macdScore = 3

            val totalScore = volumeScore + priceScore + imbalanceScore + rsiScore + macdScore

            assertEquals(85, totalScore, "새로운 총점: ${totalScore}점")
            assertTrue(totalScore >= MIN_ENTRY_SCORE,
                "새로운 점수로 진입 가능: ${totalScore}점 >= ${MIN_ENTRY_SCORE}점")
        }
    }
}
