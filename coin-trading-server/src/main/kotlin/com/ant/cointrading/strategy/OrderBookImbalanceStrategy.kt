package com.ant.cointrading.strategy

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.model.*
import com.ant.cointrading.risk.MarketConditionChecker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Order Book Imbalance (호가창 불균형) 전략
 *
 * 20년차 퀀트의 교훈 (QUANT_RESEARCH.md 기반):
 * - 호가창 불균형은 초단기 가격 방향의 강력한 예측 지표
 * - 기관은 이미 이를 사용하지만, 개인도 적용 가능
 * - 단, 수수료 + 슬리피지를 커버할 만큼의 움직임이 있어야 함
 *
 * 메커니즘:
 * Imbalance = (Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)
 *
 * - +1에 가까움 → 매수 압력 강함 → 가격 상승 예측
 * - -1에 가까움 → 매도 압력 강함 → 가격 하락 예측
 *
 * 적용 시간대: 초~분 단위 (매우 단기)
 * 적합한 시장: 유동성이 충분한 메이저 코인
 */
@Component
class OrderBookImbalanceStrategy(
    private val tradingProperties: TradingProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val marketConditionChecker: MarketConditionChecker
) : TradingStrategy {

    private val log = LoggerFactory.getLogger(OrderBookImbalanceStrategy::class.java)

    override val name = "ORDER_BOOK_IMBALANCE"

    // 불균형 이력 (추세 확인용)
    private val imbalanceHistory = ConcurrentHashMap<String, MutableList<ImbalancePoint>>()

    data class ImbalancePoint(
        val imbalance: Double,
        val microprice: BigDecimal,
        val timestamp: Instant
    )

    companion object {
        // 불균형 임계값 (±0.3 이상이면 유의미한 신호)
        const val IMBALANCE_THRESHOLD = 0.3

        // 강한 불균형 임계값 (±0.5 이상이면 강한 신호)
        const val STRONG_IMBALANCE_THRESHOLD = 0.5

        // 연속 불균형 확인 횟수 (3회 연속 같은 방향이면 신호 강화)
        const val CONSECUTIVE_CONFIRM_COUNT = 3

        // 최소 수익 임계값 % (수수료 + 슬리피지 커버)
        const val MIN_EXPECTED_MOVE_PERCENT = 0.3

        // 불균형 이력 보관 수
        const val HISTORY_SIZE = 10
    }

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        // 1. 호가창 조회
        val apiMarket = PositionHelper.convertToApiMarket(market)
        val orderbook = try {
            bithumbPublicApi.getOrderbook(apiMarket)?.firstOrNull()
        } catch (e: Exception) {
            log.warn("[$market] 호가창 조회 실패: ${e.message}")
            return holdSignal(market, currentPrice, "호가창 조회 실패")
        }

        if (orderbook == null || orderbook.orderbookUnits.isNullOrEmpty()) {
            return holdSignal(market, currentPrice, "호가창 데이터 없음")
        }

        val units = orderbook.orderbookUnits.take(5)  // 상위 5호가 사용

        // 2. Imbalance 계산
        var bidVolume = BigDecimal.ZERO
        var askVolume = BigDecimal.ZERO

        for (unit in units) {
            bidVolume += unit.bidPrice.multiply(unit.bidSize)
            askVolume += unit.askPrice.multiply(unit.askSize)
        }

        val totalVolume = bidVolume + askVolume
        if (totalVolume <= BigDecimal.ZERO) {
            return holdSignal(market, currentPrice, "호가 볼륨 없음")
        }

        val imbalance = (bidVolume - askVolume)
            .divide(totalVolume, 4, RoundingMode.HALF_UP)
            .toDouble()

        // 3. Microprice 계산 (가중 중간가)
        val bestBid = units.first().bidPrice
        val bestAsk = units.first().askPrice
        val bestBidSize = units.first().bidSize
        val bestAskSize = units.first().askSize

        val microprice = if (bestBidSize + bestAskSize > BigDecimal.ZERO) {
            (bestBid.multiply(bestAskSize) + bestAsk.multiply(bestBidSize))
                .divide(bestBidSize + bestAskSize, 8, RoundingMode.HALF_UP)
        } else {
            (bestBid + bestAsk).divide(BigDecimal(2), 8, RoundingMode.HALF_UP)
        }

        // 4. 불균형 이력 업데이트
        val history = imbalanceHistory.getOrPut(market) { mutableListOf() }
        history.add(ImbalancePoint(imbalance, microprice, Instant.now()))
        while (history.size > HISTORY_SIZE) {
            history.removeAt(0)
        }

        // 5. 연속 불균형 확인
        val consecutiveDirection = checkConsecutiveDirection(history)

        // 6. 신호 생성
        return when {
            // 강한 매수 신호: 불균형이 높고 연속 확인됨
            imbalance >= STRONG_IMBALANCE_THRESHOLD && consecutiveDirection > 0 -> {
                val confidence = calculateConfidence(imbalance, consecutiveDirection, regime, true)
                TradingSignal(
                    market = market,
                    action = SignalAction.BUY,
                    confidence = confidence,
                    price = currentPrice,
                    reason = buildReason(imbalance, microprice, consecutiveDirection, "강한 매수 압력"),
                    strategy = name
                )
            }

            // 일반 매수 신호
            imbalance >= IMBALANCE_THRESHOLD && consecutiveDirection >= 0 -> {
                val confidence = calculateConfidence(imbalance, consecutiveDirection, regime, false)
                if (confidence >= 60.0) {
                    TradingSignal(
                        market = market,
                        action = SignalAction.BUY,
                        confidence = confidence,
                        price = currentPrice,
                        reason = buildReason(imbalance, microprice, consecutiveDirection, "매수 압력 감지"),
                        strategy = name
                    )
                } else {
                    holdSignal(market, currentPrice, buildReason(imbalance, microprice, consecutiveDirection, "신뢰도 부족"))
                }
            }

            // 강한 매도 신호
            imbalance <= -STRONG_IMBALANCE_THRESHOLD && consecutiveDirection < 0 -> {
                val confidence = calculateConfidence(-imbalance, -consecutiveDirection, regime, true)
                TradingSignal(
                    market = market,
                    action = SignalAction.SELL,
                    confidence = confidence,
                    price = currentPrice,
                    reason = buildReason(imbalance, microprice, consecutiveDirection, "강한 매도 압력"),
                    strategy = name
                )
            }

            // 일반 매도 신호
            imbalance <= -IMBALANCE_THRESHOLD && consecutiveDirection <= 0 -> {
                val confidence = calculateConfidence(-imbalance, -consecutiveDirection, regime, false)
                if (confidence >= 60.0) {
                    TradingSignal(
                        market = market,
                        action = SignalAction.SELL,
                        confidence = confidence,
                        price = currentPrice,
                        reason = buildReason(imbalance, microprice, consecutiveDirection, "매도 압력 감지"),
                        strategy = name
                    )
                } else {
                    holdSignal(market, currentPrice, buildReason(imbalance, microprice, consecutiveDirection, "신뢰도 부족"))
                }
            }

            // 중립
            else -> {
                holdSignal(market, currentPrice, buildReason(imbalance, microprice, consecutiveDirection, "중립 상태"))
            }
        }
    }

    /**
     * 연속 불균형 방향 확인
     * 양수 반환: 연속 양의 불균형 (매수 압력)
     * 음수 반환: 연속 음의 불균형 (매도 압력)
     * 0 반환: 혼재
     * [버그 수정] recentImbalances.size 대신 CONSECUTIVE_CONFIRM_COUNT 사용으로 명확화
     */
    private fun checkConsecutiveDirection(history: List<ImbalancePoint>): Int {
        if (history.size < CONSECUTIVE_CONFIRM_COUNT) return 0

        val recentImbalances = history.takeLast(CONSECUTIVE_CONFIRM_COUNT).map { it.imbalance }

        val allPositive = recentImbalances.all { it > 0 }
        val allNegative = recentImbalances.all { it < 0 }

        return when {
            allPositive -> CONSECUTIVE_CONFIRM_COUNT
            allNegative -> -CONSECUTIVE_CONFIRM_COUNT
            else -> 0
        }
    }

    /**
     * 신뢰도 계산
     */
    private fun calculateConfidence(
        absImbalance: Double,
        consecutiveDirection: Int,
        regime: RegimeAnalysis,
        isStrong: Boolean
    ): Double {
        // 기본 신뢰도: 불균형 크기에 비례
        var confidence = 40.0 + (absImbalance * 60.0)  // 0.3 → 58%, 0.5 → 70%

        // 연속 확인 보너스
        if (kotlin.math.abs(consecutiveDirection) >= CONSECUTIVE_CONFIRM_COUNT) {
            confidence += 15.0
        }

        // 강한 신호 보너스
        if (isStrong) {
            confidence += 10.0
        }

        // 레짐에 따른 조정
        val regimeMultiplier = when (regime.regime) {
            MarketRegime.SIDEWAYS -> 1.0       // 횡보장에서 최적
            MarketRegime.BULL_TREND -> 0.95    // 추세장에서도 유효하나 약간 보수적
            MarketRegime.BEAR_TREND -> 0.95
            MarketRegime.HIGH_VOLATILITY -> 0.7  // 고변동성에서는 신호 불안정
        }

        return (confidence * regimeMultiplier).coerceIn(30.0, 95.0)
    }

    private fun holdSignal(market: String, price: BigDecimal, reason: String): TradingSignal {
        return TradingSignal(
            market = market,
            action = SignalAction.HOLD,
            confidence = 100.0,
            price = price,
            reason = reason,
            strategy = name
        )
    }

    private fun buildReason(
        imbalance: Double,
        microprice: BigDecimal,
        consecutiveDirection: Int,
        status: String
    ): String {
        val imbalanceStr = String.format("%.3f", imbalance)
        val micropriceStr = microprice.setScale(0, RoundingMode.HALF_UP)
        val consecutiveStr = if (consecutiveDirection != 0) {
            ", 연속 ${kotlin.math.abs(consecutiveDirection)}회"
        } else ""

        return "$status (Imbalance: $imbalanceStr$consecutiveStr, Microprice: $micropriceStr)"
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        // Order Book Imbalance는 유동성이 충분한 시장에서 유효
        // 고변동성에서는 비추천
        return regime.regime != MarketRegime.HIGH_VOLATILITY
    }

    override fun getDescription(): String {
        return """
            Order Book Imbalance - 호가창 불균형 전략

            설정:
            - 임계값: ±${IMBALANCE_THRESHOLD}
            - 강한 신호 임계값: ±${STRONG_IMBALANCE_THRESHOLD}
            - 연속 확인: ${CONSECUTIVE_CONFIRM_COUNT}회

            원리:
            - 호가창의 매수/매도 물량 비율로 단기 가격 방향 예측
            - Imbalance = (Bid - Ask) / (Bid + Ask)
            - +값: 매수 압력 → 가격 상승 예측
            - -값: 매도 압력 → 가격 하락 예측

            Microprice:
            - 가중 중간가로 정확한 fair value 추정
            - (BidPrice × AskSize + AskPrice × BidSize) / (BidSize + AskSize)

            적합한 시장: 유동성 충분한 메이저 코인
            시간대: 초~분 단위 (매우 단기)
            위험: 수수료/슬리피지가 수익보다 클 수 있음
        """.trimIndent()
    }

    /**
     * 현재 불균형 상태 조회
     */
    fun getImbalanceStatus(market: String): Map<String, Any?> {
        val history = imbalanceHistory[market]
        return mapOf(
            "market" to market,
            "historySize" to (history?.size ?: 0),
            "latestImbalance" to history?.lastOrNull()?.imbalance,
            "latestMicroprice" to history?.lastOrNull()?.microprice,
            "consecutiveDirection" to (history?.let { checkConsecutiveDirection(it) } ?: 0)
        )
    }
}
