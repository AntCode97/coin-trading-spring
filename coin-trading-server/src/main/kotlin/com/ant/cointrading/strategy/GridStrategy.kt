package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.service.KeyValueService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
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
    private val tradingProperties: TradingProperties,
    private val keyValueService: KeyValueService
) : TradingStrategy {

    private val log = LoggerFactory.getLogger(GridStrategy::class.java)
    private val objectMapper = jacksonObjectMapper()

    override val name = "GRID"

    companion object {
        private const val KEY_PREFIX = "grid.state."
    }

    // 마켓별 그리드 상태 (메모리 캐시)
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

    /** DB 저장용 직렬화 클래스 */
    data class GridStateDto(
        val basePrice: String,
        val levels: List<GridLevelDto>,
        val lastAction: String?
    )

    data class GridLevelDto(
        val price: String,
        val type: String,
        val filled: Boolean
    )

    /**
     * 애플리케이션 시작 시 DB에서 상태 복원
     */
    @PostConstruct
    fun restoreState() {
        log.info("Grid 전략 상태 복원 시작")
        tradingProperties.markets.forEach { market ->
            val key = KEY_PREFIX + market
            val savedJson = keyValueService.get(key)
            if (savedJson != null) {
                try {
                    val dto = objectMapper.readValue<GridStateDto>(savedJson)
                    val state = GridState(
                        basePrice = BigDecimal(dto.basePrice),
                        levels = dto.levels.map { level ->
                            GridLevel(
                                price = BigDecimal(level.price),
                                type = SignalAction.valueOf(level.type),
                                filled = level.filled
                            )
                        },
                        lastAction = dto.lastAction?.let { SignalAction.valueOf(it) }
                    )
                    gridStates[market] = state
                    log.info("[$market] Grid 상태 복원: 기준가=${state.basePrice}, 레벨=${state.levels.size}개")
                } catch (e: Exception) {
                    log.warn("[$market] Grid 상태 복원 실패: ${e.message}")
                }
            }
        }
        log.info("Grid 전략 상태 복원 완료: ${gridStates.size}개 마켓")
    }

    /**
     * 상태를 DB에 저장
     */
    private fun saveState(market: String, state: GridState) {
        val dto = GridStateDto(
            basePrice = state.basePrice.toPlainString(),
            levels = state.levels.map { level ->
                GridLevelDto(
                    price = level.price.toPlainString(),
                    type = level.type.name,
                    filled = level.filled
                )
            },
            lastAction = state.lastAction?.name
        )
        val json = objectMapper.writeValueAsString(dto)
        val key = KEY_PREFIX + market
        keyValueService.set(key, json, "grid", "Grid 전략 상태 ($market)")
        log.debug("[$market] Grid 상태 저장 완료")
    }

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        // 그리드 초기화 또는 갱신
        val state = gridStates.getOrPut(market) {
            val newState = createGridState(currentPrice)
            saveState(market, newState)
            newState
        }

        // 가격이 그리드 범위를 벗어나면 재설정
        val lowestLevel = state.levels.filter { it.type == SignalAction.BUY }.minOfOrNull { it.price }
        val highestLevel = state.levels.filter { it.type == SignalAction.SELL }.maxOfOrNull { it.price }

        if (lowestLevel != null && highestLevel != null) {
            if (currentPrice < lowestLevel || currentPrice > highestLevel) {
                val newState = createGridState(currentPrice)
                gridStates[market] = newState
                saveState(market, newState)
                return TradingSignal(
                    market = market,
                    action = SignalAction.HOLD,
                    confidence = 50.0,
                    price = currentPrice,
                    reason = "그리드 재설정 중 (가격 범위 이탈)",
                    strategy = name,
                    regime = regime.regime.name
                )
            }
        }

        // 현재 가격에서 트리거될 레벨 찾기
        val triggeredLevel = findTriggeredLevel(state, currentPrice)

        return if (triggeredLevel != null && !triggeredLevel.filled) {
            triggeredLevel.filled = true
            state.lastAction = triggeredLevel.type

            // 상태 변경 시 DB에 저장
            saveState(market, state)

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
                strategy = name,
                regime = regime.regime.name
            )
        } else {
            TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 100.0,
                price = currentPrice,
                reason = "그리드 레벨 대기 중 (기준가: ${state.basePrice})",
                strategy = name,
                regime = regime.regime.name
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

    /**
     * 트리거된 레벨 찾기
     * [버그 수정] SELL 레벨 threshold 역설정 수정
     * - 기존: SELL에서 subtract(threshold) 사용 (역설정으로 항상 트리거됨)
     * - 수정: SELL에서 add(threshold) 또는 price 그대로 사용
     */
    private fun findTriggeredLevel(state: GridState, currentPrice: BigDecimal): GridLevel? {
        // 가장 가까운 미체결 레벨 찾기
        return state.levels
            .filter { !it.filled }
            .filter { level ->
                val threshold = level.price.multiply(BigDecimal("0.001")) // 0.1% 허용 오차
                when (level.type) {
                    // BUY: 가격이 레벨 이하로 떨어지면 트리거
                    SignalAction.BUY -> currentPrice <= level.price.add(threshold)
                    // SELL: 가격이 레벨 이상으로 상승하면 트리거 (threshold는 상방 여유)
                    SignalAction.SELL -> currentPrice >= level.price.add(threshold)
                    else -> false
                }
            }
            .minByOrNull { (it.price - currentPrice).abs() }
    }

    /**
     * 그리드 상태 리셋 (DB도 함께)
     */
    fun resetGrid(market: String) {
        gridStates.remove(market)
        val key = KEY_PREFIX + market
        keyValueService.delete(key)
        log.info("[$market] Grid 상태 리셋 완료")
    }

    /**
     * 현재 상태 조회
     */
    fun getState(): Map<String, Any?> {
        return gridStates.mapValues { (_, state) ->
            mapOf(
                "basePrice" to state.basePrice.toPlainString(),
                "levelCount" to state.levels.size,
                "filledCount" to state.levels.count { it.filled },
                "lastAction" to state.lastAction?.name
            )
        }
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
