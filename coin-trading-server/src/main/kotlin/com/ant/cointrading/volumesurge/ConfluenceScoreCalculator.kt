package com.ant.cointrading.volumesurge

import com.ant.cointrading.indicator.DivergenceStrength
import com.ant.cointrading.indicator.DivergenceType

/**
 * 컨플루언스 점수 계산기
 *
 * 켄트백의 "의도를 표현하라" 원칙에 따라
 * 각 지표별 점수 계산을 명확한 메서드로 분리.
 *
 * 배점 체계 (최대 115점, 100점 coerce):
 * 기본 배점 (80점):
 *   - 거래량: 20점 (핵심)
 *   - MACD: 15점
 *   - RSI: 20점
 *   - 볼린저: 25점
 *
 * 보너스 (35점):
 *   - MACD 히스토그램 반전: +10점
 *   - RSI 강세 다이버전스: +15점
 *   - MTF 정렬: +15점
 */
object ConfluenceScoreCalculator {

    /**
     * 컨플루언스 점수 계산 (0~100)
     */
    fun calculate(
        rsi: Double,
        macdSignal: String,
        macdHistogramReversal: Boolean,
        bollingerPosition: String,
        volumeRatio: Double,
        rsiDivergence: DivergenceType,
        divergenceStrength: DivergenceStrength?,
        mtfAlignmentBonus: Int = 0
    ): Int {
        var score = 0

        score += calculateVolumeScore(volumeRatio)
        score += calculateMacdScore(macdSignal)
        score += calculateMacdReversalBonus(macdHistogramReversal, macdSignal)
        score += calculateRsiScore(rsi)
        score += calculateDivergenceBonus(rsiDivergence, divergenceStrength)
        score += calculateBollingerScore(bollingerPosition)
        score += mtfAlignmentBonus

        return score.coerceIn(0, 100)
    }

    /**
     * 거래량 점수 (20점)
     * Volume Surge의 핵심 지표
     */
    private fun calculateVolumeScore(volumeRatio: Double): Int = when {
        volumeRatio >= 5.0 -> 20   // 500% 이상 = 폭발적 급등
        volumeRatio >= 3.0 -> 17   // 300% 이상 = 강한 급등
        volumeRatio >= 2.0 -> 14   // 200% 이상 = 급등
        volumeRatio >= 1.5 -> 10   // 150% 이상 = 상승
        volumeRatio >= 1.2 -> 5    // 120% 이상
        else -> 0
    }

    /**
     * MACD 점수 (15점)
     * 추세 방향 확인
     */
    private fun calculateMacdScore(macdSignal: String): Int = when (macdSignal) {
        "BULLISH" -> 15           // 상승 신호
        "NEUTRAL" -> 8            // 중립
        "BEARISH" -> -20          // 강력한 패널티
        else -> 0
    }

    /**
     * MACD 히스토그램 반전 보너스 (+10점)
     * quant-trading: 조기 진입 신호
     */
    private fun calculateMacdReversalBonus(
        histogramReversal: Boolean,
        macdSignal: String
    ): Int = if (histogramReversal && macdSignal == "BULLISH") 10 else 0

    /**
     * RSI 점수 (20점)
     * 모멘텀 확인
     * 상승 모멘텀 전략: 너무 낮으면 덤프 중일 수 있음
     */
    private fun calculateRsiScore(rsi: Double): Int = when {
        rsi in 55.0..70.0 -> 20   // 최적 모멘텀
        rsi in 45.0..75.0 -> 15   // 양호
        rsi in 40.0..80.0 -> 10   // 허용
        rsi in 30.0..85.0 -> 5    // 넓은 허용
        rsi > 85.0 -> 0           // 과매수 진입 회피
        rsi < 30.0 -> -15         // 과매도 진입 회피
        else -> 0
    }

    /**
     * RSI 강세 다이버전스 보너스 (+15점)
     * quant-trading: 반전 신호
     */
    private fun calculateDivergenceBonus(
        divergenceType: DivergenceType,
        strength: DivergenceStrength?
    ): Int = if (divergenceType == DivergenceType.BULLISH) {
        when (strength) {
            DivergenceStrength.STRONG -> 15
            DivergenceStrength.MODERATE -> 10
            DivergenceStrength.WEAK -> 5
            else -> 0
        }
    } else 0

    /**
     * 볼린저밴드 점수 (25점)
     * 돌파/위치 확인
     * 상승 돌파를 원함: LOWER는 역추세 신호일 수 있음
     */
    private fun calculateBollingerScore(bollingerPosition: String): Int = when (bollingerPosition) {
        "UPPER" -> 25      // 상단 돌파 = 강한 모멘텀
        "MIDDLE" -> 22     // 중앙 = 안정적 상승
        "LOWER" -> 5       // 하단 = 역추세 헷갈림
        else -> 10
    }
}
