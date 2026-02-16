package com.ant.cointrading.engine

import com.ant.cointrading.indicator.DivergenceResult
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import java.time.Instant

/**
 * 재평가 액션 유형
 */
enum class ReassessmentAction {
    HOLD,              // 변경 없음
    TIGHTEN_STOP,      // 손절 타이트닝 (다이버전스, 컨플루언스 급락)
    WIDEN_STOP,        // 손절 확장 (HIGH_VOLATILITY 레짐)
    LOWER_TARGET,      // 익절 목표 하향 (SIDEWAYS 레짐)
    IMMEDIATE_EXIT,    // 즉시 청산 (레짐 BULL→BEAR)
    BREAK_EVEN_STOP,   // 본전 이동 (+breakEvenTrigger% 이상)
    PROFIT_LOCK        // 수익 잠금 (+profitLockTrigger% 이상 → 최소 profitLockMin% 확보)
}

/**
 * 포지션 재평가 결과
 */
data class PositionReassessment(
    val action: ReassessmentAction,
    val newStopLossPercent: Double?,
    val newTakeProfitPercent: Double?,
    val reason: String,
    val currentRegime: MarketRegime,
    val confluenceScore: Int?
)

/**
 * 마켓별 분석 캐시
 *
 * 동일 마켓에 복수 포지션이 있을 때 API 호출 1회로 공유한다.
 */
data class CachedAnalysis(
    val market: String,
    val regime: RegimeAnalysis,
    val divergence: DivergenceResult?,
    val confluenceScore: Int?,
    val rsi: Double?,
    val macdSignal: String?,
    val timestamp: Instant
)
