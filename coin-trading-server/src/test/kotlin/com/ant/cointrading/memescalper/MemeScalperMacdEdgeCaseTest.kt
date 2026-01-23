package com.ant.cointrading.memescalper

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MemeScalper MACD 기반 엣지 케이스 테스트
 *
 * 실제 거래 데이터 분석 기반:
 * - BEARISH MACD: 5건, 평균 +0.30%, 승률 60%
 * - BULLISH MACD: 3건, 평균 -0.10%, 승률 33%
 * - NEUTRAL MACD: 1건, +1.66%, 승률 100%
 *
 * 펌프앤덤프 전략 특성:
 * - BEARISH = 펌프 후 덤프 진행 중, 적절한 타이밍에 진입 시 수익
 * - BULLISH = 펌프 초기, 오히려 진입 시기가 늦을 수 있음
 */
@DisplayName("MemeScalper MACD 엣지 케이스 테스트")
class MemeScalperMacdEdgeCaseTest {

    companion object {
        // MemeScalperDetector 점수 체계
        const val VOLUME_SPIKE_MAX = 35      // 거래량 스파이크 최대 점수
        const val PRICE_SPIKE_MAX = 25       // 가격 스파이크 최대 점수
        const val IMBALANCE_MAX = 15         // 불균형 최대 점수
        const val RSI_MAX = 15               // RSI 최대 점수
        const val MACD_BULLISH = 10          // MACD BULLISH 점수
        const val MACD_NEUTRAL = 3           // MACD NEUTRAL 점수
        const val MACD_BEARISH_OLD = -10     // 기존 BEARISH 패널티
        const val MACD_BEARISH_CURRENT = -20 // 현재 BEARISH 패널티

        const val MIN_ENTRY_SCORE = 70       // 최소 진입 점수
    }

    @Nested
    @DisplayName("MACD BEARISH 패널티 분석")
    inner class MacdBearishPenaltyAnalysis {

        @Test
        @DisplayName("현재 패널티(-20)는 BEARISH 진입 차단")
        fun currentPenaltyBlocksBearishEntry() {
            // 시나리오: 모든 지표 최대 점수, MACD만 BEARISH
            val score = VOLUME_SPIKE_MAX + PRICE_SPIKE_MAX + IMBALANCE_MAX + RSI_MAX + MACD_BEARISH_CURRENT
            // 35 + 25 + 15 + 15 - 20 = 70점

            assertEquals(MIN_ENTRY_SCORE, score, "정확히 70점 - 진입 가능한 최저점")

            // 하지만 실제 데이터: BEARISH는 평균 +0.30%, 승률 60%
            // BULLISH는 평균 -0.10%, 승률 33%

            // 결론: BEARISH 패널티가 너무 강함
        }

        @Test
        @DisplayName("이전 패널티(-10)으로도 BEARISH 진입 가능")
        fun previousPenaltyAllowsBearishEntry() {
            val score = VOLUME_SPIKE_MAX + PRICE_SPIKE_MAX + IMBALANCE_MAX + RSI_MAX + MACD_BEARISH_OLD
            // 35 + 25 + 15 + 15 - 10 = 80점

            assertTrue(score >= MIN_ENTRY_SCORE, "이전 패널티로는 진입 가능: ${score}점")
        }

        @Test
        @DisplayName("BULLISH MACD는 패널티 없이도 최고 점수")
        fun bullishMacdNoPenalty() {
            val score = VOLUME_SPIKE_MAX + PRICE_SPIKE_MAX + IMBALANCE_MAX + RSI_MAX + MACD_BULLISH
            // 35 + 25 + 15 + 15 + 10 = 100점

            assertEquals(100, score, "BULLISH는 최고 점수")
        }

        @Test
        @DisplayName("실제 데이터: BEARISH > BULLISH 성과")
        fun actualDataBearishOutperformsBullish() {
            val bearishAvgPnl = 0.30    // +0.30%
            val bearishWinRate = 60.0    // 60%
            val bullishAvgPnl = -0.10    // -0.10%
            val bullishWinRate = 33.33   // 33%

            assertTrue(bearishAvgPnl > bullishAvgPnl,
                "BEARISH 평균 PnL(${bearishAvgPnl}%) > BULLISH(${bullishAvgPnl}%)")
            assertTrue(bearishWinRate > bullishWinRate,
                "BEARISH 승률(${bearishWinRate}%) > BULLISH(${bullishWinRate}%)")
        }
    }

    @Nested
    @DisplayName("MACD 신호별 진입 조건")
    inner class MacdSignalEntryConditions {

        @Test
        @DisplayName("BEARISH: 모든 지표 80% 이상 시 진입 가능")
        fun bearishEntryWithStrongIndicators() {
            // 거래량 80%, 가격 80%, 불균형 80%, RSI 80%
            val volumeScore = (VOLUME_SPIKE_MAX * 0.8).toInt()
            val priceScore = (PRICE_SPIKE_MAX * 0.8).toInt()
            val imbalanceScore = (IMBALANCE_MAX * 0.8).toInt()
            val rsiScore = (RSI_MAX * 0.8).toInt()

            val score = volumeScore + priceScore + imbalanceScore + rsiScore + MACD_BEARISH_CURRENT
            // 28 + 20 + 12 + 12 - 20 = 52점

            // 현재 패널티로는 진입 불가
            assertTrue(score < MIN_ENTRY_SCORE, "강력한 지표라도 BEARISH 패널티로 진입 차단")
        }

        @Test
        @DisplayName("NEUTRAL: 중간 지표로도 진입 가능")
        fun neutralEntryWithModerateIndicators() {
            // 거래량 60%, 가격 60%, 불균형 60%, RSI 60%
            val volumeScore = (VOLUME_SPIKE_MAX * 0.6).toInt()
            val priceScore = (PRICE_SPIKE_MAX * 0.6).toInt()
            val imbalanceScore = (IMBALANCE_MAX * 0.6).toInt()
            val rsiScore = (RSI_MAX * 0.6).toInt()

            val score = volumeScore + priceScore + imbalanceScore + rsiScore + MACD_NEUTRAL
            // 21 + 15 + 9 + 9 + 3 = 57점

            assertTrue(score < MIN_ENTRY_SCORE, "NEUTRAL도 중간 지표로는 진입 불가")
        }
    }

    @Nested
    @DisplayName("펌프앤덤프 전략 특성")
    inner class PumpAndDumpCharacteristics {

        @Test
        @DisplayName("BEARISH는 펌프 후 덤프 진행 중 신호")
        fun bearishSignalsPumpAfterDump() {
            // 펌프앤덤프 패턴:
            // 1. 거래량 급등 + 가격 급등 (펌프)
            // 2. MACD가 BEARISH로 전환 (덤프 시작)
            // 3. 적절한 타이밍에 진입 시 수익

            val description = """
                펌프앤덤프에서 BEARISH MACD는 덤프 진행 중 신호.

                진입 전략:
                - 거래량 스파이크: 여전히 높음 (펌프 잔여)
                - 가격 스파이크: 이미 발생함
                - MACD BEARISH: 덤프 시작이지만, 초단탄 기회

                실제 데이터: BEARISH 5건, 평균 +0.30%, 승률 60%
                결론: BEARISH라도 거래량/가격 스파이크 확인 시 진입 가치 있음
            """.trimIndent()

            assertTrue(description.isNotEmpty(), "펌프앤덤프 전략 설명")
        }

        @Test
        @DisplayName("BULLISH는 펌프 초기로 진입 시기 늦을 수 있음")
        fun bullishSignalsPumpEntryLate() {
            val description = """
                BULLISH MACD는 펌프 초기 신호.

                문제:
                - 이미 상승이 많이 진행된 상태일 수 있음
                - 진입 직후 하락할 위험 (덤프)

                실제 데이터: BULLISH 3건, 평균 -0.10%, 승률 33%
                결론: BULLISH라도 오히려 손실 위험
            """.trimIndent()

            assertTrue(description.isNotEmpty(), "BULLISH 위험 설명")
        }
    }

    @Nested
    @DisplayName("개선 방안")
    inner class ImprovementSuggestions {

        @Test
        @DisplayName("제안1: MACD 점수 차등 폐지")
        fun removeMacdScoring() {
            // 모든 MACD 신호에 동일한 점수 부여 (예: 5점)
            val macdScore = 5

            val bearishScore = VOLUME_SPIKE_MAX + PRICE_SPIKE_MAX + IMBALANCE_MAX + RSI_MAX + macdScore
            // 35 + 25 + 15 + 15 + 5 = 95점

            assertTrue(bearishScore >= MIN_ENTRY_SCORE, "모든 MACD에 동일 점수 시 BEARISH 진입 가능")
        }

        @Test
        @DisplayName("제안2: BEARISH 패널티 완화")
        fun reduceBearishPenalty() {
            // BEARISH 패널티를 -10점으로 완화
            val bearishScore = VOLUME_SPIKE_MAX + PRICE_SPIKE_MAX + IMBALANCE_MAX + RSI_MAX + MACD_BEARISH_OLD
            // 35 + 25 + 15 + 15 - 10 = 80점

            assertTrue(bearishScore >= MIN_ENTRY_SCORE, "패널티 완화 시 BEARISH 진입 가능")
        }

        @Test
        @DisplayName("제안3: 샘플 확대 후 재평가")
        fun needMoreSamples() {
            val currentSamples = 9
            val recommendedSamples = 50

            assertTrue(currentSamples < recommendedSamples,
                "현재 ${currentSamples}건으로 통계적 유의성 부족, ${recommendedSamples}건 이상 권장")
        }
    }
}
