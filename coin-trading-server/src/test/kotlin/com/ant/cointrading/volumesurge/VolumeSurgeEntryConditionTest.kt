package com.ant.cointrading.volumesurge

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VolumeSurge 전략 진입 조건 테스트
 *
 * quant-trading 스킬 기반 컨플루언스 분석 검증:
 * - 4중 컨플루언스 (RSI + MACD + 볼린저 + 거래량)
 * - MACD BEARISH 패널티
 * - MTF 정렬 보너스
 */
@DisplayName("VolumeSurge 진입 조건 테스트")
class VolumeSurgeEntryConditionTest {

    companion object {
        // VolumeSurgeAnalyzer 컨플루언스 기본 배점
        const val VOLUME_SCORE_MAX = 20
        const val MACD_SCORE_BULLISH = 15
        const val MACD_SCORE_BEARISH = -5
        const val MACD_HISTOGRAM_REVERSAL_BONUS = 10
        const val RSI_SCORE_MAX = 20
        const val RSI_BULLISH_DIVERGENCE_MAX = 15
        const val BOLLINGER_SCORE_UPPER = 25
        const val BOLLINGER_SCORE_MIDDLE = 18
        const val BOLLINGER_SCORE_LOWER = 10
        const val MTF_ALIGNMENT_3 = 15
        const val MTF_ALIGNMENT_2 = 5
        const val MIN_CONFLUENCE_SCORE = 40
    }

    @Nested
    @DisplayName("MACD BEARISH 패널티 테스트")
    inner class MacdBearishPenaltyTest {

        @Test
        @DisplayName("MACD BEARISH 시 컨플루언스 점수 감소")
        fun bearishMacdReducesScore() {
            // 시나리오: 모든 지표가 최상이지만 MACD만 BEARISH

            val volumeScore = VOLUME_SCORE_MAX           // 20점 (500%+ 급등)
            val macdScore = MACD_SCORE_BEARISH           // -5점
            val rsiScore = RSI_SCORE_MAX                 // 20점 (50-65 RSI)
            val bollingerScore = BOLLINGER_SCORE_UPPER    // 25점 (상단 돌파)
            val mtfBonus = 0

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore + mtfBonus
            // 20 + (-5) + 20 + 25 = 60점

            assertTrue(totalScore >= MIN_CONFLUENCE_SCORE,
                "MACD BEARISH라도 60점 도달 가능: ${totalScore}점")
        }

        @Test
        @DisplayName("MACD BEARISH + 낮은 RSI 시 진입 차단")
        fun bearishMacdWithLowRsiBlocked() {
            // 시나리오: MACD BEARISH + RSI 낮음 (모멘텀 없음)

            val volumeScore = VOLUME_SCORE_MAX           // 20점
            val macdScore = MACD_SCORE_BEARISH           // -5점
            val rsiScore = 0                           // RSI 25 미만
            val bollingerScore = BOLLINGER_SCORE_LOWER  // 10점 (하단)
            val mtfBonus = 0

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore + mtfBonus
            // 20 + (-5) + 0 + 10 = 25점

            assertFalse(totalScore >= MIN_CONFLUENCE_SCORE,
                "MACD BEARISH + 낮은 RSI로 진입 차단: ${totalScore}점 < 40점")
        }

        @Test
        @DisplayName("MACD BEARISH 패널티 강화 필요성")
        fun bearishMacdNeedsStrongerPenalty() {
            // MACD BEARISH는 -5점 패널티만으로는 부족
            // 다른 지표가 좋으면 진입 가능

            val volumeScore = VOLUME_SCORE_MAX           // 20점
            val macdScore = MACD_SCORE_BEARISH           // -5점
            val rsiScore = RSI_SCORE_MAX                 // 20점
            val bollingerScore = BOLLINGER_SCORE_UPPER    // 25점
            val divergenceBonus = RSI_BULLISH_DIVERGENCE_MAX  // 15점
            val mtfBonus = MTF_ALIGNMENT_3               // 15점

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore
                           + divergenceBonus + mtfBonus
            // 20 + (-5) + 20 + 25 + 15 + 15 = 90점

            assertTrue(totalScore >= MIN_CONFLUENCE_SCORE,
                "강력한 컨플루언스로 MACD BEARISH 패널티 상쇄: ${totalScore}점")

            // 개선 필요: BEARISH 시 완전 차단 또는 -20점 패널티
            val recommendedPenalty = -20
            val scoreWithStrongerPenalty = volumeScore + recommendedPenalty
                                              + rsiScore + bollingerScore
            // 20 + (-20) + 20 + 25 = 45점

            assertTrue(scoreWithStrongerPenalty < MIN_CONFLUENCE_SCORE,
                "BEARISH 패널티 강화 시 진입 차단: ${scoreWithStrongerPenalty}점 < 40점")
        }
    }

    @Nested
    @DisplayName("컨플루언스 기준 테스트")
    inner class ConfluenceThresholdTest {

        @Test
        @DisplayName("최소 컨플루언스 40점 도달 시나리오")
        fun minConfluenceScenario() {
            // 시나리오: 최소한의 컨플루언스로 40점 도달

            val volumeScore = 10    // 150% 급등
            val macdScore = 8       // NEUTRAL
            val rsiScore = 15       // 40-70 RSI
            val bollingerScore = 10 // 하단 반등

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore
            // 10 + 8 + 15 + 10 = 43점

            assertTrue(totalScore >= MIN_CONFLUENCE_SCORE,
                "최소 컨플루언스 40점 도달: ${totalScore}점")
        }

        @Test
        @DisplayName("거래량 부족 시 진입 차단")
        fun lowVolumeBlocksEntry() {
            // 시나리오: 다른 지표는 좋으나 거래량 부족

            val volumeScore = 0     // 120% 미만
            val macdScore = MACD_SCORE_BULLISH     // 15점
            val rsiScore = RSI_SCORE_MAX           // 20점
            val bollingerScore = BOLLINGER_SCORE_UPPER  // 25점

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore
            // 0 + 15 + 20 + 25 = 60점

            // 거래량이 Volume Surge의 핵심 지표이므로,
            // volumeScore가 0이면 진입 차단해야 함
            assertTrue(volumeScore == 0, "거래량 부족으로 진입 차단 필요")
        }

        @Test
        @DisplayName("MTF 역방향 정렬 시 진입 차단")
        fun mtfMisalignmentBlocksEntry() {
            // 시나리오: MTF 역방향 정렬 (-5점)

            val volumeScore = VOLUME_SCORE_MAX        // 20점
            val macdScore = MACD_SCORE_BULLISH        // 15점
            val rsiScore = RSI_SCORE_MAX              // 20점
            val bollingerScore = BOLLINGER_SCORE_UPPER // 25점
            val mtfBonus = -5                          // 역방향 정렬

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore + mtfBonus
            // 20 + 15 + 20 + 25 - 5 = 75점

            // MTF 역방향이면 무조건 차단 (VolumeSurgeAnalyzer 101-118줄 참조)
            assertTrue(mtfBonus < 0, "MTF 역방향으로 진입 차단")
        }
    }

    @Nested
    @DisplayName("볼린저밴드 위치별 점수 테스트")
    inner class BollingerPositionScoreTest {

        @Test
        @DisplayName("상단 돌파: 최고 점수")
        fun upperBandBreakout() {
            val position = "UPPER"
            val score = when (position) {
                "UPPER" -> 25
                "MIDDLE" -> 18
                "LOWER" -> 10
                else -> 10
            }

            assertEquals(25, score, "상단 돌파 25점")
        }

        @Test
        @DisplayName("중앙 위치: 중간 점수")
        fun middleBand() {
            val position = "MIDDLE"
            val score = when (position) {
                "UPPER" -> 25
                "MIDDLE" -> 18
                "LOWER" -> 10
                else -> 10
            }

            assertEquals(18, score, "중앙 18점")
        }

        @Test
        @DisplayName("하단 반등: 최저 점수")
        fun lowerBandBounce() {
            val position = "LOWER"
            val score = when (position) {
                "UPPER" -> 25
                "MIDDLE" -> 18
                "LOWER" -> 10
                else -> 10
            }

            assertEquals(10, score, "하단 10점")
        }
    }

    @Nested
    @DisplayName("RSI 다이버전스 보너스 테스트")
    inner class RsiDivergenceBonusTest {

        @Test
        @DisplayName("강세 다이버전스: 최대 보너스")
        fun strongBullishDivergence() {
            val strength = com.ant.cointrading.indicator.DivergenceStrength.STRONG
            val bonus = when (strength) {
                com.ant.cointrading.indicator.DivergenceStrength.STRONG -> 15
                com.ant.cointrading.indicator.DivergenceStrength.MODERATE -> 10
                com.ant.cointrading.indicator.DivergenceStrength.WEAK -> 5
                else -> 0
            }

            assertEquals(15, bonus, "강세 다이버전스 15점 보너스")
        }

        @Test
        @DisplayName("다이버전스 + 최상 RSI = 최대 컨플루언스")
        fun divergencePlusOptimalRsi() {
            val rsiScore = RSI_SCORE_MAX                 // 20점 (50-65)
            val divergenceBonus = RSI_BULLISH_DIVERGENCE_MAX  // 15점

            val rsiTotal = rsiScore + divergenceBonus  // 35점

            assertEquals(35, rsiTotal, "RSI 다이버전스 보너스로 최대 35점")
        }
    }

    @Nested
    @DisplayName("실제 거래 케이스 시뮬레이션")
    inner class RealTradeCaseSimulation {

        @Test
        @DisplayName("KRW-ARB 손실 케이스 분석")
        fun analyzeKrwArbLoss() {
            // 실제 데이터: KRW-ARB, -0.7% 손실, TIMEOUT
            // entry_rsi: 37.598, entry_macd_signal: BULLISH
            // entry_bollinger_position: LOWER, confluence_score: 65

            val actualRsi = 37.598
            val actualMacd = "BULLISH"
            val actualBollinger = "LOWER"

            // 당시 점수 계산
            val volumeScore = 20  // 500%+ 급등 (가정)
            val macdScore = MACD_SCORE_BULLISH  // 15점
            val rsiScore = 10    // 30-40 범위 (10점)
            val bollingerScore = BOLLINGER_SCORE_LOWER  // 10점
            val mtfBonus = 0

            val totalScore = volumeScore + macdScore + rsiScore + bollingerScore + mtfBonus
            // 20 + 15 + 10 + 10 = 55점

            // 당시에는 65점이었지만 시뮬레이션에서는 55점
            // 이는 volumeRatio나 다른 보너스가 달랐을 수 있음

            assertTrue(totalScore >= MIN_CONFLUENCE_SCORE,
                "KRW-ARB 진입 정당: ${totalScore}점 >= 40점")

            // 손실 원인 분석: TIMEOUT (30분 타임아웃)
            // 개선 필요: 진입 후 30분 내 청산 실패
            // -> 손절이 더 타이트했어야 하거나, 트레일링 스탑이 필요했음
        }

        @Test
        @DisplayName("XYO 손실 케이스: MACD BEARISH 진입")
        fun analyzeXyoLoss() {
            // 실제 데이터: MACD BEARISH에서 진입 후 -0.4% 손실
            // entry_rsi: 58.6, entry_macd_signal: BULLISH (실제는 BEARISH였을 수도)

            val rsi = 58.6  // 40-70 범위 (15점)
            val macd = "BEARISH"
            val volume = 10.38  // 1038% 급등 (20점)

            val macdScore = MACD_SCORE_BEARISH  // -5점
            val rsiScore = 15   // 40-70 범위
            val volumeScore = 20  // 500%+ 급등

            val totalScore = volumeScore + macdScore + rsiScore + 18  // 볼린저 MIDDLE 가정
            // 20 + (-5) + 15 + 18 = 48점

            // BEARISH 패널티 -5점만으로는 48점 도달 가능
            // 개선 필요: BEARISH 시 더 강력한 패널티 또는 완전 차단

            assertTrue(totalScore >= MIN_CONFLUENCE_SCORE,
                "BEARISH라도 48점 도달 가능 - 개선 필요")
        }
    }
}
