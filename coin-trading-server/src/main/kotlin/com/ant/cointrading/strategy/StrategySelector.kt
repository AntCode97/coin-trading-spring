package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.StrategyType
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 전략 선택기
 *
 * 시장 레짐에 따라 적합한 전략을 자동 선택.
 *
 * 안전장치 (웹 검색 기반 교훈):
 * - 레짐 전환 지연: 최소 3회 연속 같은 레짐 감지 필요
 * - 전략 전환 쿨다운: 전략 변경 후 최소 1시간 유지
 * - 휩소(Whipsaw) 방지: 잦은 전략 변경으로 인한 손실 방지
 */
@Component
class StrategySelector(
    private val tradingProperties: TradingProperties,
    private val dcaStrategy: DcaStrategy,
    private val gridStrategy: GridStrategy,
    private val meanReversionStrategy: MeanReversionStrategy,
    private val orderBookImbalanceStrategy: OrderBookImbalanceStrategy,
    private val breakoutStrategy: BreakoutStrategy,
    private val enhancedBreakoutStrategy: EnhancedBreakoutStrategy
) {

    private val log = LoggerFactory.getLogger(StrategySelector::class.java)

    // 마켓별 레짐 히스토리 (휩소 방지)
    private val regimeHistory = ConcurrentHashMap<String, MutableList<MarketRegime>>()

    // 마켓별 현재 활성 전략
    private val activeStrategies = ConcurrentHashMap<String, ActiveStrategy>()

    data class ActiveStrategy(
        val strategy: TradingStrategy,
        val activatedAt: Instant,
        val regime: MarketRegime
    )

    companion object {
        const val REGIME_CONFIRMATION_COUNT = 3     // 3회 연속 같은 레짐 필요
        const val STRATEGY_COOLDOWN_MINUTES = 60L   // 전략 변경 후 1시간 쿨다운
        const val MAX_HISTORY_SIZE = 10             // 히스토리 최대 크기
    }

    /**
     * 현재 설정된 전략 반환
     */
    fun getCurrentStrategy(): TradingStrategy {
        return when (tradingProperties.strategy.type) {
            StrategyType.DCA -> dcaStrategy
            StrategyType.GRID -> gridStrategy
            StrategyType.MEAN_REVERSION -> meanReversionStrategy
            StrategyType.ORDER_BOOK_IMBALANCE -> orderBookImbalanceStrategy
            StrategyType.BREAKOUT -> breakoutStrategy
            StrategyType.BREAKOUT_ENHANCED -> enhancedBreakoutStrategy
        }
    }

    /**
     * 레짐에 따른 최적 전략 선택 (마켓별 지연 적용)
     */
    fun selectStrategy(regime: RegimeAnalysis, market: String = "DEFAULT"): TradingStrategy {
        // 1. 레짐 히스토리 업데이트
        updateRegimeHistory(market, regime.regime)

        // 2. 현재 활성 전략 확인
        val active = activeStrategies[market]

        // 3. 쿨다운 체크 - 최근 전략 변경 후 충분한 시간이 지나지 않았으면 현재 전략 유지
        if (active != null && !isCooldownExpired(active)) {
            log.debug("[$market] 전략 쿨다운 중: ${active.strategy.name} (${getRemainingCooldownMinutes(active)}분 남음)")
            return active.strategy
        }

        // 4. 레짐 확인 체크 - 3회 연속 같은 레짐이 아니면 현재 전략 유지
        if (!isRegimeConfirmed(market, regime.regime)) {
            log.debug("[$market] 레짐 미확정: ${getRegimeHistoryString(market)}")
            return active?.strategy ?: dcaStrategy
        }

        // 5. 최적 전략 결정
        val optimalStrategy = determineOptimalStrategy(regime)

        // 6. 전략 변경 필요 여부 확인
        if (active == null || active.strategy.name != optimalStrategy.name) {
            log.info("[$market] 전략 전환: ${active?.strategy?.name ?: "없음"} -> ${optimalStrategy.name} (레짐: ${regime.regime})")
            activeStrategies[market] = ActiveStrategy(
                strategy = optimalStrategy,
                activatedAt = Instant.now(),
                regime = regime.regime
            )
        }

        return optimalStrategy
    }

    /**
     * 레짐 확인 없이 최적 전략 결정 (내부 로직)
     */
    private fun determineOptimalStrategy(regime: RegimeAnalysis): TradingStrategy {
        // 신뢰도가 낮으면 보수적으로 DCA 사용
        if (regime.confidence < 50.0) {
            return dcaStrategy
        }

        return when (regime.regime) {
            MarketRegime.BULL_TREND -> breakoutStrategy  // 상승 추세에서는 Breakout 전략
            MarketRegime.BEAR_TREND -> dcaStrategy  // 하락장에서도 DCA (금액 조절은 리스크 관리에서)
            MarketRegime.SIDEWAYS -> {
                // 횡보장에서는 변동성에 따라 선택
                if (regime.atrPercent < 2.0) {
                    gridStrategy
                } else {
                    meanReversionStrategy
                }
            }
            MarketRegime.HIGH_VOLATILITY -> breakoutStrategy  // 고변동성에서도 Breakout
        }
    }

    /**
     * 레짐 히스토리 업데이트
     */
    private fun updateRegimeHistory(market: String, regime: MarketRegime) {
        val history = regimeHistory.getOrPut(market) { mutableListOf() }
        history.add(regime)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }
    }

    /**
     * 레짐 확인 여부 (N회 연속 같은 레짐인지)
     */
    private fun isRegimeConfirmed(market: String, currentRegime: MarketRegime): Boolean {
        val history = regimeHistory[market] ?: return false
        if (history.size < REGIME_CONFIRMATION_COUNT) return false

        val recent = history.takeLast(REGIME_CONFIRMATION_COUNT)
        return recent.all { it == currentRegime }
    }

    /**
     * 쿨다운 만료 여부
     */
    private fun isCooldownExpired(active: ActiveStrategy): Boolean {
        val elapsed = ChronoUnit.MINUTES.between(active.activatedAt, Instant.now())
        return elapsed >= STRATEGY_COOLDOWN_MINUTES
    }

    /**
     * 남은 쿨다운 시간 (분)
     */
    private fun getRemainingCooldownMinutes(active: ActiveStrategy): Long {
        val elapsed = ChronoUnit.MINUTES.between(active.activatedAt, Instant.now())
        return maxOf(0, STRATEGY_COOLDOWN_MINUTES - elapsed)
    }

    /**
     * 레짐 히스토리 문자열 (디버깅용)
     */
    private fun getRegimeHistoryString(market: String): String {
        return regimeHistory[market]?.takeLast(REGIME_CONFIRMATION_COUNT)
            ?.joinToString(" -> ") { it.name }
            ?: "없음"
    }

    /**
     * 전략 변경 제안
     */
    fun suggestStrategyChange(
        currentStrategy: TradingStrategy,
        regime: RegimeAnalysis
    ): StrategyChangeRecommendation? {
        val recommended = selectStrategy(regime)

        if (recommended.name == currentStrategy.name) {
            return null
        }

        val reason = buildChangeReason(currentStrategy, recommended, regime)

        return StrategyChangeRecommendation(
            currentStrategy = currentStrategy.name,
            recommendedStrategy = recommended.name,
            reason = reason,
            confidence = regime.confidence
        )
    }

    /**
     * 모든 전략 목록
     */
    fun getAllStrategies(): List<TradingStrategy> {
        return listOf(dcaStrategy, gridStrategy, meanReversionStrategy, orderBookImbalanceStrategy, breakoutStrategy, enhancedBreakoutStrategy)
    }

    /**
     * 이름으로 전략 조회
     */
    fun getStrategyByName(name: String): TradingStrategy? {
        return getAllStrategies().find { it.name.equals(name, ignoreCase = true) }
    }

    private fun buildChangeReason(
        current: TradingStrategy,
        recommended: TradingStrategy,
        regime: RegimeAnalysis
    ): String {
        val regimeText = when (regime.regime) {
            MarketRegime.BULL_TREND -> "상승 추세 (ADX: ${String.format("%.1f", regime.adx)})"
            MarketRegime.BEAR_TREND -> "하락 추세 (ADX: ${String.format("%.1f", regime.adx)})"
            MarketRegime.SIDEWAYS -> "횡보장 (ADX: ${String.format("%.1f", regime.adx)})"
            MarketRegime.HIGH_VOLATILITY -> "고변동성 (ATR: ${String.format("%.2f", regime.atrPercent)}%)"
        }

        return """
            현재 시장: $regimeText
            현재 전략: ${current.name}
            추천 전략: ${recommended.name}
            이유: ${recommended.name}이(가) 현재 시장에 더 적합
        """.trimIndent()
    }
}

data class StrategyChangeRecommendation(
    val currentStrategy: String,
    val recommendedStrategy: String,
    val reason: String,
    val confidence: Double
)
