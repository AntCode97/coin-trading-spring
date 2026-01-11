package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Grid Trading 전략
 *
 * 가격 구간을 그리드로 나누어 자동 매매.
 * 횡보장에서 작은 수익을 반복적으로 획득.
 *
 * 예시 (5레벨, 1% 간격):
 * - 5200만원: 매도
 * - 5100만원: 매도
 * - 5000만원: 기준점
 * - 4900만원: 매수
 * - 4800만원: 매수
 */
@Component
class GridStrategy(
    private val tradingProperties: TradingProperties
) : TradingStrategy {

    override val name = "GRID"

    // 마켓별 그리드 상태
    private val gridStates = ConcurrentHashMap<String, GridState>()

    data class GridState(
        val basePrice: BigDecimal,
        val levels: List<GridLevel>,
        var lastAction: SignalAction? = null
    )

    data class GridLevel(
        val price: BigDecimal,
        val type: SignalAction,  // BUY or SELL
        var filled: Boolean = false
    )

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        // 그리드 초기화 또는 갱신
        val state = gridStates.getOrPut(market) {
            createGridState(currentPrice)
        }

        // 가격이 그리드 범위를 벗어나면 재설정
        val lowestLevel = state.levels.filter { it.type == SignalAction.BUY }.minOfOrNull { it.price }
        val highestLevel = state.levels.filter { it.type == SignalAction.SELL }.maxOfOrNull { it.price }

        if (lowestLevel != null && highestLevel != null) {
            if (currentPrice < lowestLevel || currentPrice > highestLevel) {
                gridStates[market] = createGridState(currentPrice)
                return TradingSignal(
                    market = market,
                    action = SignalAction.HOLD,
                    confidence = 50.0,
                    price = currentPrice,
                    reason = "그리드 재설정 중 (가격 범위 이탈)",
                    strategy = name
                )
            }
        }

        // 현재 가격에서 트리거될 레벨 찾기
        val triggeredLevel = findTriggeredLevel(state, currentPrice)

        return if (triggeredLevel != null && !triggeredLevel.filled) {
            triggeredLevel.filled = true
            state.lastAction = triggeredLevel.type

            val confidence = when (regime.regime) {
                MarketRegime.SIDEWAYS -> 80.0
                MarketRegime.BULL_TREND -> if (triggeredLevel.type == SignalAction.BUY) 70.0 else 60.0
                MarketRegime.BEAR_TREND -> if (triggeredLevel.type == SignalAction.SELL) 70.0 else 60.0
                MarketRegime.HIGH_VOLATILITY -> 40.0
            }

            TradingSignal(
                market = market,
                action = triggeredLevel.type,
                confidence = confidence,
                price = currentPrice,
                reason = buildReason(triggeredLevel, state.basePrice, currentPrice),
                strategy = name
            )
        } else {
            TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 100.0,
                price = currentPrice,
                reason = "그리드 레벨 대기 중 (기준가: ${state.basePrice})",
                strategy = name
            )
        }
    }

    private fun createGridState(basePrice: BigDecimal): GridState {
        val levels = tradingProperties.strategy.gridLevels
        val spacing = tradingProperties.strategy.gridSpacingPercent / 100.0

        val gridLevels = mutableListOf<GridLevel>()

        // 매수 레벨 (기준가 아래)
        for (i in 1..levels) {
            val multiplier = BigDecimal.ONE - BigDecimal(spacing * i)
            val price = basePrice.multiply(multiplier).setScale(0, RoundingMode.DOWN)
            gridLevels.add(GridLevel(price, SignalAction.BUY))
        }

        // 매도 레벨 (기준가 위)
        for (i in 1..levels) {
            val multiplier = BigDecimal.ONE + BigDecimal(spacing * i)
            val price = basePrice.multiply(multiplier).setScale(0, RoundingMode.UP)
            gridLevels.add(GridLevel(price, SignalAction.SELL))
        }

        return GridState(basePrice, gridLevels)
    }

    private fun findTriggeredLevel(state: GridState, currentPrice: BigDecimal): GridLevel? {
        // 가장 가까운 미체결 레벨 찾기
        return state.levels
            .filter { !it.filled }
            .filter { level ->
                val threshold = level.price.multiply(BigDecimal("0.001")) // 0.1% 허용 오차
                when (level.type) {
                    SignalAction.BUY -> currentPrice <= level.price.add(threshold)
                    SignalAction.SELL -> currentPrice >= level.price.subtract(threshold)
                    else -> false
                }
            }
            .minByOrNull { (it.price - currentPrice).abs() }
    }

    /**
     * 그리드 상태 리셋
     */
    fun resetGrid(market: String) {
        gridStates.remove(market)
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        // 그리드는 횡보장에서 최적, 고변동성에서는 위험
        return regime.regime == MarketRegime.SIDEWAYS &&
                regime.atrPercent < 3.0  // ATR이 3% 미만일 때
    }

    override fun getDescription(): String {
        val levels = tradingProperties.strategy.gridLevels
        val spacing = tradingProperties.strategy.gridSpacingPercent
        return """
            Grid Trading - 격자 매매 전략

            설정:
            - 그리드 레벨: ${levels}개 (위/아래 각각)
            - 레벨 간격: ${spacing}%

            원리:
            - 현재가를 기준으로 위/아래에 주문 배치
            - 가격 하락 시 자동 매수
            - 가격 상승 시 자동 매도
            - 횡보장에서 반복 수익 획득

            적합한 시장: 횡보장 (변동성 낮음)
            위험: 한 방향으로 추세 발생 시 손실
        """.trimIndent()
    }

    private fun buildReason(level: GridLevel, basePrice: BigDecimal, currentPrice: BigDecimal): String {
        val action = if (level.type == SignalAction.BUY) "매수" else "매도"
        val diff = ((currentPrice - basePrice) / basePrice * BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)
        val direction = if (diff >= BigDecimal.ZERO) "+" else ""
        return "그리드 $action (레벨: ${level.price}, 기준가 대비: $direction$diff%)"
    }
}
