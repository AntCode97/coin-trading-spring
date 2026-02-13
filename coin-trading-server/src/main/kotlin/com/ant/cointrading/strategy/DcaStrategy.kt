package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.repository.DcaPositionEntity
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.service.KeyValueService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * DCA (Dollar Cost Averaging) 전략
 *
 * 정해진 간격으로 일정 금액을 매수하는 전략.
 * 장기 우상향 시장에서 평균 매입가를 낮추는 효과.
 *
 * 검증된 성과: 연간 18.7% (3Commas 플랫폼 데이터)
 */
@Component
class DcaStrategy(
    private val tradingProperties: TradingProperties,
    private val keyValueService: KeyValueService,
    private val dcaPositionRepository: DcaPositionRepository
) : TradingStrategy {

    private val log = LoggerFactory.getLogger(DcaStrategy::class.java)

    override val name = "DCA"

    companion object {
        private const val KEY_PREFIX = "dca.last_buy_time."
    }

    // 마켓별 마지막 매수 시간 (메모리 캐시)
    private val lastBuyTime = ConcurrentHashMap<String, Instant>()

    /**
     * 애플리케이션 시작 시 DB에서 상태 복원
     */
    @PostConstruct
    fun restoreState() {
        log.info("DCA 전략 상태 복원 시작")
        tradingProperties.markets.forEach { market ->
            val key = KEY_PREFIX + market
            val savedTime = keyValueService.get(key)
            if (savedTime != null) {
                val instant = parseInstantSafe(savedTime)
                lastBuyTime[market] = instant
                log.info("[$market] DCA 마지막 매수 시간 복원: $instant")
            }
        }
        log.info("DCA 전략 상태 복원 완료: ${lastBuyTime.size}개 마켓")
    }

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        val now = Instant.now()
        val interval = tradingProperties.strategy.dcaInterval
        val lastBuy = lastBuyTime[market]

        // 1. 청산 조건 체크 (기존 포지션이 있는 경우)
        val openPositions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
        if (openPositions.isNotEmpty()) {
            val position = openPositions.first() // 하나의 포지션만 유지

            // 포지션 정보 갱신 (현재가, 수익률)
            val currentPnlPercent = ((currentPrice.toDouble() - position.averagePrice) / position.averagePrice) * 100
            position.lastPrice = currentPrice.toDouble()
            position.lastPriceUpdate = now
            position.currentPnlPercent = currentPnlPercent
            dcaPositionRepository.save(position)

            // 익절 체크
            if (currentPnlPercent >= position.takeProfitPercent) {
                log.info("[$market] DCA 익절 조건 도달: ${String.format("%.2f", currentPnlPercent)}% >= ${position.takeProfitPercent}%")
                return TradingSignal(
                    market = market,
                    action = SignalAction.SELL,
                    confidence = 100.0,
                    price = currentPrice,
                    reason = "DCA 익절: ${String.format("%.2f", currentPnlPercent)}% 수익 (목표: +${position.takeProfitPercent}%)",
                    strategy = name,
                    regime = regime.regime.name
                )
            }

            // 손절 체크
            if (currentPnlPercent <= position.stopLossPercent) {
                log.warn("[$market] DCA 손절 조건 도달: ${String.format("%.2f", currentPnlPercent)}% <= ${position.stopLossPercent}%")
                return TradingSignal(
                    market = market,
                    action = SignalAction.SELL,
                    confidence = 100.0,
                    price = currentPrice,
                    reason = "DCA 손절: ${String.format("%.2f", currentPnlPercent)}% 손실 (한도: ${position.stopLossPercent}%)",
                    strategy = name,
                    regime = regime.regime.name
                )
            }

            // 타임아웃 체크 (30일 보유 후 익절 목표 완화)
            val holdingDays = ChronoUnit.DAYS.between(position.createdAt, now)
            if (holdingDays >= 30 && currentPnlPercent >= 5.0) {
                log.info("[$market] DCA 30일 경과 + 5% 이상 수익: ${String.format("%.2f", currentPnlPercent)}%")
                return TradingSignal(
                    market = market,
                    action = SignalAction.SELL,
                    confidence = 80.0,
                    price = currentPrice,
                    reason = "DCA 30일 타임아웃 익절: ${String.format("%.2f", currentPnlPercent)}% 수익",
                    strategy = name,
                    regime = regime.regime.name
                )
            }
        }

        // 2. 매수 조건 체크
        // 하락장/고변동성에서는 DCA 매수 완전 차단 (손실 방지)
        if (regime.regime == MarketRegime.BEAR_TREND || regime.regime == MarketRegime.HIGH_VOLATILITY) {
            return TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 100.0,
                price = currentPrice,
                reason = "DCA 매수 차단: ${regime.regime.name} 레짐에서는 매수하지 않음",
                strategy = name,
                regime = regime.regime.name
            )
        }

        val shouldBuy = lastBuy == null ||
                now.toEpochMilli() - lastBuy.toEpochMilli() >= interval

        return if (shouldBuy) {
            val confidence = when (regime.regime) {
                MarketRegime.BULL_TREND -> 85.0
                MarketRegime.SIDEWAYS -> 70.0
                MarketRegime.BEAR_TREND -> 0.0
                MarketRegime.HIGH_VOLATILITY -> 0.0
            }

            TradingSignal(
                market = market,
                action = SignalAction.BUY,
                confidence = confidence,
                price = currentPrice,
                reason = buildReason(regime, lastBuy),
                strategy = name,
                regime = regime.regime.name
            )
        } else {
            val remainingMs = interval - (now.toEpochMilli() - lastBuy.toEpochMilli())
            val remainingHours = remainingMs / 3600000.0

            TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 100.0,
                price = currentPrice,
                reason = "DCA 간격 대기 중 (${String.format("%.1f", remainingHours)}시간 후 매수)",
                strategy = name,
                regime = regime.regime.name
            )
        }
    }

    /**
     * 매수 완료 기록 (메모리 + DB + 포지션 추적)
     */
    fun recordBuy(market: String, quantity: Double, price: Double, amount: Double) {
        val now = Instant.now()
        lastBuyTime[market] = now

        // DB에도 저장 (재시작 시 복원용)
        val key = KEY_PREFIX + market
        keyValueService.set(key, now.toString(), "dca", "DCA 마지막 매수 시간 ($market)")

        // DCA 포지션 관리
        val openPositions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
        if (openPositions.isNotEmpty()) {
            // 기존 포지션에 추가: 평균가 재계산
            val position = openPositions.first()
            val newTotalValue = (position.totalQuantity * position.averagePrice) + (quantity * price)
            val newTotalQuantity = position.totalQuantity + quantity
            position.averagePrice = newTotalValue / newTotalQuantity
            position.totalQuantity = newTotalQuantity
            position.totalInvested += amount
            dcaPositionRepository.save(position)
            log.info("[$market] DCA 포지션 추가: 수량=${quantity}원, 가격=${price}원, 평균가=${position.averagePrice}원")
        } else {
            // 새 포지션 생성
            val position = DcaPositionEntity(
                market = market,
                totalQuantity = quantity,
                averagePrice = price,
                totalInvested = amount,
                status = "OPEN",
                lastPrice = price,
                lastPriceUpdate = now,
                currentPnlPercent = 0.0,
                takeProfitPercent = tradingProperties.strategy.dcaTakeProfitPercent,
                stopLossPercent = tradingProperties.strategy.dcaStopLossPercent
            )
            dcaPositionRepository.save(position)
            log.info("[$market] DCA 신규 포지션 생성: 수량=${quantity}원, 가격=${price}원")
        }
    }

    /**
     * 매도 완료 기록 (포지션 청산)
     */
    fun recordSell(market: String, quantity: Double, price: Double, exitReason: String) {
        val openPositions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
        if (openPositions.isEmpty()) {
            log.warn("[$market] DCA 포지션을 찾을 수 없음 (매도 기록 불가)")
            return
        }

        val position = openPositions.first()
        position.status = "CLOSED"
        position.exitReason = exitReason
        position.exitPrice = price
        position.exitedAt = Instant.now()
        position.realizedPnl = (price - position.averagePrice) * quantity
        position.realizedPnlPercent = ((price - position.averagePrice) / position.averagePrice) * 100
        dcaPositionRepository.save(position)

        log.info("[$market] DCA 포지션 청산: 사유=$exitReason, 손익=${position.realizedPnl}원 (${String.format("%.2f", position.realizedPnlPercent)}%)")
    }

    /**
     * 현재 상태 조회
     */
    fun getState(): Map<String, Any?> {
        return lastBuyTime.mapValues { (_, instant) ->
            mapOf(
                "lastBuyTime" to instant.toString(),
                "nextBuyTime" to instant.plusMillis(tradingProperties.strategy.dcaInterval).toString()
            )
        }
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        // DCA는 모든 레짐에서 사용 가능하지만 상승장/횡보장에서 최적
        return regime.regime in listOf(
            MarketRegime.BULL_TREND,
            MarketRegime.SIDEWAYS
        )
    }

    override fun getDescription(): String {
        val intervalHours = tradingProperties.strategy.dcaInterval / 3600000
        return """
            DCA (Dollar Cost Averaging) - 분할 매수 전략

            설정:
            - 매수 간격: ${intervalHours}시간

            원리:
            - 정해진 간격으로 일정 금액 매수
            - 가격 변동과 관계없이 꾸준히 매수
            - 장기적으로 평균 매입가 안정화

            적합한 시장: 상승장, 횡보장
            위험: 지속적인 하락장에서 손실 누적
        """.trimIndent()
    }

    private fun buildReason(regime: RegimeAnalysis, lastBuy: Instant?): String {
        val regimeText = when (regime.regime) {
            MarketRegime.BULL_TREND -> "상승 추세"
            MarketRegime.BEAR_TREND -> "하락 추세 (주의)"
            MarketRegime.SIDEWAYS -> "횡보"
            MarketRegime.HIGH_VOLATILITY -> "고변동성 (주의)"
        }

        return if (lastBuy == null) {
            "DCA 첫 매수 (시장: $regimeText)"
        } else {
            "DCA 정기 매수 (시장: $regimeText)"
        }
    }

    /**
     * 안전한 Instant 파싱 (시간대 정보 없으면 UTC로 간주)
     */
    private fun parseInstantSafe(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()

        return try {
            Instant.parse(dateTimeStr)
        } catch (e: Exception) {
            try {
                Instant.parse("${dateTimeStr}Z")
            } catch (e2: Exception) {
                val localDateTime = java.time.LocalDateTime.parse(dateTimeStr)
                localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
            }
        }
    }
}
