package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.service.KeyValueService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
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
    private val keyValueService: KeyValueService
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
                try {
                    val instant = Instant.parse(savedTime)
                    lastBuyTime[market] = instant
                    log.info("[$market] DCA 마지막 매수 시간 복원: $instant")
                } catch (e: Exception) {
                    log.warn("[$market] DCA 상태 복원 실패: ${e.message}")
                }
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

        // 간격 체크
        val shouldBuy = lastBuy == null ||
                now.toEpochMilli() - lastBuy.toEpochMilli() >= interval

        return if (shouldBuy) {
            // 하락장에서는 신뢰도를 낮춤
            val confidence = when (regime.regime) {
                MarketRegime.BULL_TREND -> 85.0
                MarketRegime.SIDEWAYS -> 70.0
                MarketRegime.BEAR_TREND -> 50.0
                MarketRegime.HIGH_VOLATILITY -> 40.0
            }

            TradingSignal(
                market = market,
                action = SignalAction.BUY,
                confidence = confidence,
                price = currentPrice,
                reason = buildReason(regime, lastBuy),
                strategy = name
            )
        } else {
            val remainingMs = interval - (now.toEpochMilli() - (lastBuy?.toEpochMilli() ?: 0))
            val remainingHours = remainingMs / 3600000.0

            TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 100.0,
                price = currentPrice,
                reason = "DCA 간격 대기 중 (${String.format("%.1f", remainingHours)}시간 후 매수)",
                strategy = name
            )
        }
    }

    /**
     * 매수 완료 기록 (메모리 + DB)
     */
    fun recordBuy(market: String) {
        val now = Instant.now()
        lastBuyTime[market] = now

        // DB에도 저장 (재시작 시 복원용)
        val key = KEY_PREFIX + market
        keyValueService.set(key, now.toString(), "dca", "DCA 마지막 매수 시간 ($market)")
        log.debug("[$market] DCA 매수 시간 기록: $now")
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
}
