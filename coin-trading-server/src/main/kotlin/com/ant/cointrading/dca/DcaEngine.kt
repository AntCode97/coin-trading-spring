package com.ant.cointrading.dca

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.DcaPositionEntity
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.strategy.DcaStrategy
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * DCA (Dollar Cost Averaging) 트레이딩 엔진
 *
 * 정해진 간격으로 일정 금액을 매수하는 전략.
 * 장기 우상향 시장에서 평균 매입가를 낮추는 효과.
 *
 * 독립 엔진 (TradingEngine 독립):
 * - 1분마다 마켓 스캔 (진입 기회 확인)
 * - 5초마다 포지션 모니터링 (익절/손절 체크)
 *
 * 포지션 관리:
 * - dca_positions 테이블에 포지션 추적
 * - 익절: +15%
 * - 손절: -10%
 * - 30일 보유 후 +5% 이상 시 익절 완화
 */
@Component
class DcaEngine(
    private val tradingProperties: TradingProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val dcaStrategy: DcaStrategy,
    private val orderExecutor: OrderExecutor,
    private val dcaPositionRepository: DcaPositionRepository,
    private val slackNotifier: SlackNotifier,
    private val regimeDetector: RegimeDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 마켓별 쿨다운 추적
    private val cooldowns = ConcurrentHashMap<String, Instant>()

    companion object {
        private const val SCAN_INTERVAL_MS = 60_000L  // 1분
        private const val MONITOR_INTERVAL_MS = 5_000L // 5초
        // MIN_ORDER_AMOUNT_KRW는 TradingConstants.MIN_ORDER_AMOUNT_KRW 사용
    }

    @PostConstruct
    fun init() {
        log.info("=== DCA Engine 시작 ===")
        log.info("설정: enabled=${tradingProperties.enabled}, interval=${tradingProperties.strategy.dcaInterval}ms")
        log.info("마켓: ${tradingProperties.markets}")

        // 열린 포지션 복원
        restoreOpenPositions()
    }

    /**
     * 서버 재시작 시 열린 포지션 복원
     */
    private fun restoreOpenPositions() {
        val openPositions = dcaPositionRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) {
            log.info("복원할 DCA 포지션 없음")
            return
        }

        log.info("DCA 열린 포지션 ${openPositions.size}건 복원")
        openPositions.forEach { position ->
            log.info("[${position.market}] 포지션 복원: 수량=${position.totalQuantity}, 평균가=${position.averagePrice}원")
        }
    }

    /**
     * 마켓 스캔 (1분마다)
     *
     * 각 마켓에 대해 DCA 매수 조건 확인 후 진입
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    fun scanMarkets() {
        if (!tradingProperties.enabled) return

        try {
            val markets = tradingProperties.markets
            log.debug("DCA 마켓 스캔 시작: ${markets.size}개 마켓")

            for (market in markets) {
                try {
                    scanSingleMarket(market)
                } catch (e: Exception) {
                    log.error("[$market] 스캔 중 오류: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            log.error("DCA 마켓 스캔 오류: ${e.message}", e)
        }
    }

    /**
     * 단일 마켓 스캔
     */
    private fun scanSingleMarket(market: String) {
        // 쿨다운 체크
        val cooldownEnd = cooldowns[market]
        if (cooldownEnd != null && Instant.now().isBefore(cooldownEnd)) {
            log.debug("[$market] DCA 쿨다운 중")
            return
        }

        // 이미 열린 포지션 체크 (마켓당 1개만 유지)
        val existingPosition = dcaPositionRepository.findByMarketAndStatus(market, "OPEN").firstOrNull()
        if (existingPosition != null) {
            log.debug("[$market] 이미 열린 DCA 포지션 존재")
            return
        }

        // 캔들 데이터 가져오기
        val candleResponses = bithumbPublicApi.getOhlcv(market, "minute60", 100)
        if (candleResponses.isNullOrEmpty() || candleResponses.size < 20) {
            log.debug("[$market] 캔들 데이터 부족")
            return
        }

        // 현재가 조회
        val currentPrice = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice
            ?: return

        // 레짐 감지
        val regime = try {
            regimeDetector.detectFromBithumb(candleResponses)
        } catch (e: Exception) {
            log.warn("[$market] 레짐 감지 실패: ${e.message}")
            RegimeAnalysis(MarketRegime.SIDEWAYS, 50.0, 0.0, 0.0, 0.0, 0, Instant.now())
        }

        // CandleResponse -> Candle 변환
        val candles = candleResponses.map { response ->
            Candle(
                timestamp = Instant.parse(response.candleDateTimeUtc),
                open = response.openingPrice,
                high = response.highPrice,
                low = response.lowPrice,
                close = response.tradePrice,
                volume = response.candleAccTradeVolume
            )
        }

        // DCA 전략 분석
        val signal = dcaStrategy.analyze(market, candles, currentPrice, regime)

        // 매수 신호면 진입
        if (signal.action == SignalAction.BUY) {
            enterPosition(market, signal, currentPrice, regime)
        }
    }

    /**
     * 포지션 진입
     */
    private fun enterPosition(
        market: String,
        signal: TradingSignal,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ) {
        log.info("[$market] DCA 진입 시도: 신뢰도=${signal.confidence}%, 이유=${signal.reason}")

        // 주문 금액 결정 (기본 10,000원)
        val orderAmount = BigDecimal.valueOf(tradingProperties.orderAmountKrw.toLong())

        // KRW 잔고 확인
        try {
            val balances = bithumbPrivateApi.getBalances()
            val krwBalance = balances?.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO

            if (krwBalance < orderAmount) {
                log.warn("[$market] KRW 잔고 부족: ${krwBalance}원 < ${orderAmount}원")
                return
            }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            return
        }

        // 매수 주문 실행
        val buySignal = TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = signal.confidence,
            price = currentPrice,
            reason = "DCA 진입: ${signal.reason}",
            strategy = "DCA",
            regime = regime.regime.name
        )

        val orderResult = orderExecutor.execute(buySignal, orderAmount)

        if (!orderResult.success) {
            log.error("[$market] DCA 매수 실패: ${orderResult.message}")
            cooldowns[market] = Instant.now().plus(5, ChronoUnit.MINUTES)
            return
        }

        // 체결 정보 추출
        val executedQuantity = orderResult.executedQuantity?.toDouble() ?: 0.0
        val executedPrice = orderResult.price?.toDouble()
            ?: bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
            ?: currentPrice.toDouble()

        if (executedQuantity <= 0) {
            log.error("[$market] 체결 수량 0 - 진입 실패")
            cooldowns[market] = Instant.now().plus(5, ChronoUnit.MINUTES)
            return
        }

        val executedAmount = executedQuantity * executedPrice

        // DcaStrategy.recordBuy() 호출 (포지션 생성/갱신)
        dcaStrategy.recordBuy(market, executedQuantity, executedPrice, executedAmount)

        // 쿨다운 설정 (DCA 간격만큼)
        val cooldownMinutes = tradingProperties.strategy.dcaInterval / 60_000
        cooldowns[market] = Instant.now().plus(cooldownMinutes.toLong(), ChronoUnit.MINUTES)

        // Slack 알림
        slackNotifier.sendSystemNotification(
            "DCA 진입",
            """
            마켓: $market
            체결가: ${executedPrice}원
            수량: ${String.format("%.6f", executedQuantity)}
            금액: ${executedAmount}원
            레짐: ${regime.regime.name}
            """.trimIndent()
        )

        log.info("[$market] DCA 진입 완료: 가격=$executedPrice, 수량=$executedQuantity")
    }

    /**
     * 포지션 모니터링 (5초마다)
     *
     * 열린 포지션의 익절/손절/타임아웃 조건 확인
     */
    @Scheduled(fixedDelay = MONITOR_INTERVAL_MS)
    fun monitorPositions() {
        if (!tradingProperties.enabled) return

        val openPositions = dcaPositionRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) {
            return
        }

        openPositions.forEach { position ->
            try {
                monitorSinglePosition(position)
            } catch (e: Exception) {
                log.error("[${position.market}] 포지션 모니터링 오류: ${e.message}")
            }
        }
    }

    /**
     * 단일 포지션 모니터링
     */
    private fun monitorSinglePosition(position: DcaPositionEntity) {
        val market = position.market

        // 현재가 조회
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull() ?: return
        val currentPrice = ticker.tradePrice.toDouble()

        // 현재 수익률 계산
        val currentPnlPercent = ((currentPrice - position.averagePrice) / position.averagePrice) * 100

        // 포지션 정보 갱신
        position.lastPrice = currentPrice
        position.lastPriceUpdate = Instant.now()
        position.currentPnlPercent = currentPnlPercent
        dcaPositionRepository.save(position)

        // 보유 기간 계산
        val holdingDays = ChronoUnit.DAYS.between(position.createdAt, Instant.now())

        // 익절 체크
        if (currentPnlPercent >= position.takeProfitPercent) {
            closePosition(position, currentPrice, "TAKE_PROFIT")
            return
        }

        // 손절 체크
        if (currentPnlPercent <= position.stopLossPercent) {
            closePosition(position, currentPrice, "STOP_LOSS")
            return
        }

        // 30일 타임아웃 익절 완화 (5% 이상 시)
        if (holdingDays >= 30 && currentPnlPercent >= 5.0) {
            closePosition(position, currentPrice, "TIMEOUT")
            return
        }
    }

    /**
     * 포지션 청산
     */
    private fun closePosition(position: DcaPositionEntity, exitPrice: Double, reason: String) {
        val market = position.market

        log.info("[$market] DCA 청산 시도: $reason")

        // 코인 잔고 확인
        val coinSymbol = market.removePrefix("KRW-")
        val actualBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            BigDecimal(position.totalQuantity)
        }

        if (actualBalance <= BigDecimal.ZERO) {
            log.warn("[$market] 잔고 없음 - ABANDONED 처리")
            position.status = "CLOSED"
            position.exitReason = "ABANDONED_NO_BALANCE"
            position.exitedAt = Instant.now()
            position.exitPrice = exitPrice
            position.realizedPnl = 0.0
            position.realizedPnlPercent = 0.0
            dcaPositionRepository.save(position)
            return
        }

        // 매도 수량 결정
        val sellQuantity = actualBalance.toDouble().coerceAtMost(position.totalQuantity)
        val sellAmount = BigDecimal(sellQuantity * exitPrice)

        // 최소 주문 금액 체크
        if (sellAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 매도 금액 미달 (${sellAmount}원)")
            position.status = "CLOSED"
            position.exitReason = "ABANDONED_MIN_AMOUNT"
            position.exitedAt = Instant.now()
            position.exitPrice = exitPrice
            position.realizedPnl = 0.0
            position.realizedPnlPercent = 0.0
            dcaPositionRepository.save(position)
            return
        }

        // 매도 주문 실행
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "DCA 청산: $reason",
            strategy = "DCA",
            regime = null
        )

        val orderResult = orderExecutor.execute(sellSignal, sellAmount)

        if (!orderResult.success) {
            log.error("[$market] DCA 매도 실패: ${orderResult.message}")
            return
        }

        // 체결가 결정
        val actualExitPrice = orderResult.price?.toDouble() ?: exitPrice

        // 실제 매도 수량
        val actualSellQuantity = orderResult.executedQuantity?.toDouble() ?: sellQuantity

        // 손익 계산
        val pnlAmount = (actualExitPrice - position.averagePrice) * actualSellQuantity
        val pnlPercent = ((actualExitPrice - position.averagePrice) / position.averagePrice) * 100

        // DcaStrategy.recordSell() 호출
        dcaStrategy.recordSell(market, actualSellQuantity, actualExitPrice, reason)

        // 포지션 상태 업데이트
        position.status = "CLOSED"
        position.exitReason = reason
        position.exitPrice = actualExitPrice
        position.exitedAt = Instant.now()
        position.realizedPnl = pnlAmount
        position.realizedPnlPercent = pnlPercent
        dcaPositionRepository.save(position)

        // Slack 알림
        val emoji = if (pnlAmount >= 0) "+" else ""
        slackNotifier.sendSystemNotification(
            "DCA 청산",
            """
            마켓: $market
            사유: $reason
            진입가: ${position.averagePrice}원
            청산가: ${actualExitPrice}원
            손익: $emoji${String.format("%.0f", pnlAmount)}원 (${String.format("%.2f", pnlPercent)}%)
            보유수량: ${String.format("%.6f", actualSellQuantity)}
            """.trimIndent()
        )

        log.info("[$market] DCA 청산 완료: $reason, 손익=${pnlAmount}원 (${pnlPercent}%)")
    }

    /**
     * 상태 조회 (API용)
     */
    fun getStatus(): Map<String, Any> {
        val openPositions = dcaPositionRepository.findByStatus("OPEN")
        return mapOf(
            "enabled" to tradingProperties.enabled,
            "openPositions" to openPositions.size,
            "markets" to tradingProperties.markets,
            "positions" to openPositions.map { pos ->
                mapOf(
                    "market" to pos.market,
                    "totalQuantity" to pos.totalQuantity,
                    "averagePrice" to pos.averagePrice,
                    "totalInvested" to pos.totalInvested,
                    "currentPnlPercent" to pos.currentPnlPercent,
                    "holdingDays" to ChronoUnit.DAYS.between(pos.createdAt, Instant.now())
                )
            }
        )
    }

    /**
     * 수동 청산
     */
    fun manualClose(market: String): Map<String, Any?> {
        val positions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
        if (positions.isEmpty()) {
            return mapOf("success" to false, "error" to "열린 포지션 없음")
        }

        val position = positions.first()
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
        val exitPrice = ticker?.tradePrice?.toDouble() ?: position.averagePrice

        closePosition(position, exitPrice, "MANUAL")

        return mapOf("success" to true, "exitPrice" to exitPrice)
    }
}
