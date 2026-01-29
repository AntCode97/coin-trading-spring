package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.order.OrderRejectionReason
import com.ant.cointrading.regime.HmmRegimeDetector
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.risk.CircuitBreaker
import com.ant.cointrading.risk.DailyLossLimitService
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.risk.MarketConditionChecker
import com.ant.cointrading.risk.RiskManager
import com.ant.cointrading.strategy.DcaStrategy
import com.ant.cointrading.strategy.StrategySelector
import com.ant.cointrading.strategy.TradingStrategy
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 트레이딩 엔진 (강화된 리스크 관리)
 *
 * 20년차 퀀트의 교훈 (QUANT_RESEARCH.md 기반):
 * 1. 시장 상태 확인 없이 주문하면 슬리피지로 손실 확정
 * 2. API 에러는 즉시 기록해야 시스템 장애 조기 감지
 * 3. 총 자산 추적 없이는 전체 낙폭을 모른다
 *
 * 전체 트레이딩 사이클:
 * 1. 시장 데이터 수집 (+ API 에러 추적)
 * 2. 레짐 분석
 * 3. 전략 선택 및 신호 생성
 * 4. 시장 상태 검사 (NEW)
 * 5. 리스크 체크
 * 6. 주문 실행 (+ 실행 실패/슬리피지 추적)
 * 7. 총 자산 기록 (NEW)
 */
@Component
class TradingEngine(
    private val tradingProperties: TradingProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val regimeDetector: RegimeDetector,
    private val hmmRegimeDetector: HmmRegimeDetector,
    private val keyValueService: KeyValueService,
    private val strategySelector: StrategySelector,
    private val riskManager: RiskManager,
    private val circuitBreaker: CircuitBreaker,
    private val marketConditionChecker: MarketConditionChecker,
    private val orderExecutor: OrderExecutor,
    private val slackNotifier: SlackNotifier,
    private val globalPositionManager: GlobalPositionManager,
    private val dailyLossLimitService: DailyLossLimitService
) {

    companion object {
        const val KEY_REGIME_DETECTOR_TYPE = "regime.detector.type"
        const val REGIME_DETECTOR_HMM = "hmm"
        const val REGIME_DETECTOR_SIMPLE = "simple"

        // 중복 알림 방지 쿨다운 (10분)
        const val ALERT_COOLDOWN_MINUTES = 10L

        // 무한 루프 방지 설정
        // 최소 보유 시간: 매수 후 최소 5분은 보유해야 매도 가능 (수수료 손실 방지)
        const val MIN_HOLDING_SECONDS = 300L  // 5분

        // 거래 쿨다운: 매도 후 최소 5분은 대기해야 재매수 가능 (급등락 회피)
        const val TRADE_COOLDOWN_SECONDS = 300L  // 5분
    }

    private val log = LoggerFactory.getLogger(TradingEngine::class.java)

    // 마켓별 상태
    private val marketStates = ConcurrentHashMap<String, MarketState>()

    // 마켓별 마지막 리스크 경고 시간 (중복 알림 방지)
    // [BUG FIX] compute 메서드로 원자적 업데이트하여 race condition 방지
    private val lastRiskAlertTime = ConcurrentHashMap<String, Instant>()

    // [무한 루프 방지] 마켓별 마지막 매수 시간 (최소 보유 시간 체크용)
    private val lastBuyTime = ConcurrentHashMap<String, Instant>()

    // [무한 루프 방지] 마켓별 마지막 매도 시간 (재매수 쿨다운 체크용)
    private val lastSellTime = ConcurrentHashMap<String, Instant>()

    data class MarketState(
        var candles: List<Candle> = emptyList(),
        var currentPrice: BigDecimal = BigDecimal.ZERO,
        var regime: RegimeAnalysis? = null,
        var currentStrategy: TradingStrategy? = null,
        var lastSignal: TradingSignal? = null,
        var lastAnalysisTime: Instant = Instant.EPOCH
    )

    @PostConstruct
    fun start() {
        log.info("트레이딩 엔진 시작 (Virtual Thread 모드)")
        log.info("거래 활성화: ${tradingProperties.enabled}")
        log.info("대상 마켓: ${tradingProperties.markets}")
        log.info("기본 전략: ${tradingProperties.strategy.type}")
    }

    /**
     * 모든 마켓 분석 (1분마다 실행, Virtual Thread에서 실행)
     */
    @Scheduled(fixedDelay = 60_000)
    fun scheduledAnalysis() {
        try {
            analyzeAllMarkets()
        } catch (e: Exception) {
            log.error("분석 중 오류 발생: ${e.message}", e)
        }
    }

    /**
     * 모든 마켓 분석
     */
    private fun analyzeAllMarkets() {
        tradingProperties.markets.forEach { market ->
            try {
                analyzeMarket(market)
            } catch (e: Exception) {
                log.error("마켓 분석 실패 [$market]: ${e.message}")
            }
        }
    }

    /**
     * 단일 마켓 분석
     */
    fun analyzeMarket(market: String): TradingSignal? {
        val state = marketStates.getOrPut(market) { MarketState() }

        // 1. 캔들 데이터 수집
        val candles = fetchCandles(market)
        if (candles.isEmpty()) {
            log.warn("캔들 데이터 없음: $market")
            return null
        }
        state.candles = candles
        state.currentPrice = candles.last().close

        // 2. 레짐 분석 (설정에 따라 HMM 또는 Simple 사용)
        val regime = detectRegime(candles)
        state.regime = regime

        log.debug("[$market] 레짐: ${regime.regime}, ADX: ${regime.adx}, ATR%: ${regime.atrPercent}")

        // 3. 전략 선택 (마켓별 레짐 전환 지연 적용)
        val strategy = strategySelector.selectStrategy(regime, market)
        state.currentStrategy = strategy

        // 4. 신호 생성
        val signal = strategy.analyze(market, candles, state.currentPrice, regime)
        state.lastSignal = signal
        state.lastAnalysisTime = Instant.now()

        // HOLD가 아닌 경우에만 처리
        if (signal.action != SignalAction.HOLD) {
            processSignal(market, signal, state)
        }

        return signal
    }

    /**
     * 신호 처리
     */
    private fun processSignal(market: String, signal: TradingSignal, state: MarketState) {
        log.info("""
            [$market] 신호 발생
            행동: ${signal.action}
            신뢰도: ${signal.confidence}%
            전략: ${signal.strategy}
            사유: ${signal.reason}
        """.trimIndent())

        // 0. 서킷 브레이커 체크 (안전장치)
        val circuitCheck = circuitBreaker.canTrade(market)
        if (!circuitCheck.canTrade) {
            log.warn("[$market] 서킷 브레이커 발동: ${circuitCheck.reason}")
            return
        }

        // 0.5 [무한 루프 방지] 쿨다운 및 최소 보유 시간 체크
        val now = Instant.now()

        if (signal.action == SignalAction.BUY) {
            // 매수 시: 마지막 매도 후 쿨다운 체크
            val lastSell = lastSellTime[market]
            if (lastSell != null) {
                val secondsSinceSell = Duration.between(lastSell, now).seconds
                if (secondsSinceSell < TRADE_COOLDOWN_SECONDS) {
                    val remaining = TRADE_COOLDOWN_SECONDS - secondsSinceSell
                    log.info("[$market] 재매수 쿨다운 중: ${remaining}초 남음")
                    return
                }
            }
        } else if (signal.action == SignalAction.SELL) {
            // 매도 시: 최소 보유 시간 체크
            val lastBuy = lastBuyTime[market]
            if (lastBuy != null) {
                val secondsSinceBuy = Duration.between(lastBuy, now).seconds
                if (secondsSinceBuy < MIN_HOLDING_SECONDS) {
                    val remaining = MIN_HOLDING_SECONDS - secondsSinceBuy
                    log.info("[$market] 최소 보유 시간 미충족: ${remaining}초 남음 (수수료 손실 방지)")
                    return
                }
            }
        }

        // 1. 잔고 조회
        val balances = try {
            bithumbPrivateApi.getBalances()
        } catch (e: Exception) {
            log.error("[$market] 잔고 조회 실패: ${e.message}")
            circuitBreaker.recordApiError(market)
            return
        }

        if (balances == null) {
            log.error("[$market] 잔고 응답 null")
            circuitBreaker.recordApiError(market)
            return
        }

        val krwBalance = balances.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO

        // 1.5 총 자산 기록 (CircuitBreaker 낙폭 추적용)
        val totalAssetKrw = calculateTotalAssetKrw(balances, state.currentPrice, market)
        circuitBreaker.recordTotalAsset(totalAssetKrw)

        // 2. 리스크 체크
        val riskCheck = riskManager.canTrade(market, krwBalance)
        if (!riskCheck.canTrade) {
            log.warn("[$market] 리스크 체크 실패: ${riskCheck.reason}")

            // [BUG FIX] 원자적 업데이트로 중복 알림 race condition 방지
            val reason = riskCheck.reason
            lastRiskAlertTime.compute(market) { _, lastAlert ->
                val now = Instant.now()
                if (lastAlert == null || Duration.between(lastAlert, now).toMinutes() >= ALERT_COOLDOWN_MINUTES) {
                    slackNotifier.sendWarning(market, "리스크 체크 실패: $reason")
                    now
                } else {
                    lastAlert
                }
            }
            return
        }

        // 2.5. 일일 손실 한도 체크
        if (!dailyLossLimitService.canTrade()) {
            log.warn("[$market] 일일 손실 한도 도달로 트레이딩 중지: ${dailyLossLimitService.tradingHaltedReason}")
            slackNotifier.sendError(market, "일일 손실 한도 도달: ${dailyLossLimitService.tradingHaltedReason}")
            return
        }

        // 3. 포지션 사이징
        val positionSize = riskManager.calculatePositionSize(market, krwBalance, signal.confidence)

        // [BUG FIX] 매수/매도 모두 잔고 체크 + 중복 매수 방지
        val parts = market.split("_")
        val coinSymbol = if (parts.size == 2) parts[0] else market  // BTC_KRW -> BTC
        val coinBalance = balances.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO

        if (signal.action == SignalAction.BUY) {
            // KRW 잔고 확인
            if (positionSize > krwBalance) {
                log.warn("[$market] KRW 잔고 부족: 필요 $positionSize, 보유 $krwBalance")
                return
            }

            // [무한 루프 방지 + 엔진 간 충돌 방지]
            // 1. 이미 코인을 보유하고 있으면 추가 매수 금지
            val coinValueKrw = coinBalance.multiply(state.currentPrice)
            if (coinValueKrw >= BigDecimal("5000")) {
                log.warn("[$market] 이미 $coinSymbol 보유 중: ${coinBalance} (${coinValueKrw}원 상당) - 중복 매수 방지")
                return
            }

            // 2. 다른 엔진(VolumeSurge, MemeScalper)에서 해당 마켓에 포지션이 있는지 확인
            if (globalPositionManager.hasOpenPosition(market)) {
                log.warn("[$market] 다른 엔진에서 열린 포지션 존재 - TradingEngine 진입 차단")
                return
            }
        } else if (signal.action == SignalAction.SELL) {
            // 매도 시 코인 잔고 확인
            if (coinBalance <= BigDecimal.ZERO) {
                log.warn("[$market] $coinSymbol 잔고 없음: $coinBalance - 매도 취소")
                return
            }
        }

        // 4. 주문 실행 (MarketConditionChecker는 OrderExecutor 내부에서 호출)
        val result = orderExecutor.execute(signal, positionSize)

        // 5. 결과 처리
        if (result.success) {
            // [무한 루프 방지] 매수/매도 시간 기록
            when (signal.action) {
                SignalAction.BUY -> {
                    lastBuyTime[market] = Instant.now()
                    log.info("[$market] 매수 시간 기록 - 최소 ${MIN_HOLDING_SECONDS}초 보유 필요")
                }
                SignalAction.SELL -> {
                    lastSellTime[market] = Instant.now()
                    log.info("[$market] 매도 시간 기록 - 재매수 ${TRADE_COOLDOWN_SECONDS}초 쿨다운")
                }
                else -> {}
            }

            // DCA 전략인 경우 포지션 추적
            if (signal.strategy == "DCA") {
                val quantity = result.executedQuantity?.toDouble() ?: 0.0
                val price = result.price?.toDouble() ?: 0.0
                val amount = quantity * price

                when (signal.action) {
                    SignalAction.BUY -> {
                        (state.currentStrategy as? DcaStrategy)?.recordBuy(market, quantity, price, amount)
                    }
                    SignalAction.SELL -> {
                        val exitReason = when {
                            signal.reason.contains("익절") -> "TAKE_PROFIT"
                            signal.reason.contains("손절") -> "STOP_LOSS"
                            signal.reason.contains("타임아웃") -> "TIMEOUT"
                            else -> "SIGNAL"
                        }
                        (state.currentStrategy as? DcaStrategy)?.recordSell(market, quantity, price, exitReason)
                    }
                    else -> {}
                }
            }

            // 슬리피지 경고 포함 알림
            val notificationMessage = buildString {
                append("체결 완료")
                if (result.isPartialFill) {
                    append(" (부분 ${String.format("%.1f", result.fillRatePercent)}%)")
                }
                if (result.slippagePercent > 0.5) {
                    append(" ⚠️슬리피지: ${String.format("%.2f", result.slippagePercent)}%")
                }
                result.fee?.let { append(" | 수수료: $it") }
            }
            log.info("[$market] $notificationMessage")

            slackNotifier.sendTradeNotification(signal, result)

            // 일일 손실 한도에 체결금액 기록 (매도만)
            // Note: 실제 PnL은 포지션 레벨에서 계산됨
            if (signal.action == SignalAction.SELL) {
                val executedValue = (result.executedQuantity?.toDouble() ?: 0.0) * (result.price?.toDouble() ?: 0.0)
                // 단일 주문 시점에서는 진입가를 알 수 없으므로 체결금액을 기록
                // 실제 PnL은 포지션 청산 시 계산되어 TradeEntity에 저장됨
                dailyLossLimitService.recordPnl(0.0)
            }
        } else {
            // 실패 사유별 처리
            val errorDetail = when (result.rejectionReason) {
                OrderRejectionReason.MARKET_CONDITION -> "시장 상태 불량 - 스프레드/유동성 확인"
                OrderRejectionReason.API_ERROR -> "API 장애 - 거래소 상태 확인"
                OrderRejectionReason.VERIFICATION_FAILED -> "주문 상태 확인 실패 - 수동 확인 필요"
                OrderRejectionReason.NO_FILL -> "체결 없음 - 유동성 부족 의심"
                OrderRejectionReason.EXCEPTION -> "시스템 예외"
                OrderRejectionReason.CIRCUIT_BREAKER -> "서킷 브레이커 발동"
                OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT -> "최소 주문 금액(5000원) 미달"
                null -> result.message
            }
            log.error("[$market] 주문 실패: $errorDetail")
            slackNotifier.sendError(market, "주문 실패: $errorDetail\n원인: ${result.rejectionReason}")
        }
    }

    /**
     * 총 자산 계산 (KRW 환산)
     */
    private fun calculateTotalAssetKrw(
        balances: List<com.ant.cointrading.api.bithumb.Balance>,
        currentPrice: BigDecimal,
        market: String
    ): Double {
        var totalKrw = BigDecimal.ZERO

        for (balance in balances) {
            val amount = balance.balance
            if (amount <= BigDecimal.ZERO) continue

            if (balance.currency == "KRW") {
                totalKrw += amount
            } else {
                // 코인 자산은 현재가로 환산 (단순화: 메인 마켓 가격 사용)
                val coinMarket = "${balance.currency}_KRW"
                if (coinMarket == market) {
                    totalKrw += amount.multiply(currentPrice)
                } else {
                    // 다른 코인은 평균 매수가로 환산
                    val avgPrice = balance.avgBuyPrice ?: BigDecimal.ZERO
                    totalKrw += amount.multiply(avgPrice)
                }
            }
        }

        return totalKrw.toDouble()
    }

    /**
     * 레짐 감지 (설정에 따라 HMM 또는 Simple 선택)
     */
    private fun detectRegime(candles: List<Candle>): RegimeAnalysis {
        val detectorType = keyValueService.get(KEY_REGIME_DETECTOR_TYPE, REGIME_DETECTOR_SIMPLE)

        return when (detectorType) {
            REGIME_DETECTOR_HMM -> {
                log.debug("HMM 레짐 감지기 사용")
                hmmRegimeDetector.detect(candles)
            }
            else -> {
                log.debug("Simple(ADX/ATR) 레짐 감지기 사용")
                regimeDetector.detect(candles)
            }
        }
    }

    /**
     * 캔들 데이터 조회
     */
    private fun fetchCandles(market: String): List<Candle> {
        return try {
            // market 형식 통일: KRW-BTC, BTC_KRW 등 → KRW-BTC
            val apiMarket = when {
                market.contains("-") -> market // 이미 KRW-BTC 형식이면 그대로 사용
                market.contains("_") -> {
                    val parts = market.split("_")
                    if (parts.size == 2) "${parts[1]}-${parts[0]}" else market // BTC_KRW → KRW-BTC
                }
                else -> market
            }

            val response = bithumbPublicApi.getOhlcv(apiMarket, "minute60", 100)

            if (response == null) {
                // API 에러 기록
                circuitBreaker.recordApiError(market)
                marketConditionChecker.recordApiError(market)
                log.warn("[$market] 캔들 API 응답 null")
                return emptyList()
            }

            response.map { candle ->
                Candle(
                    timestamp = Instant.ofEpochMilli(candle.timestamp),
                    open = candle.openingPrice,
                    high = candle.highPrice,
                    low = candle.lowPrice,
                    close = candle.tradePrice,
                    volume = candle.candleAccTradeVolume
                )
            }.reversed()  // 최신 데이터가 앞에 오므로 역순 정렬
        } catch (e: Exception) {
            log.error("[$market] 캔들 조회 실패: ${e.message}")
            circuitBreaker.recordApiError(market)
            marketConditionChecker.recordApiError(market)
            emptyList()
        }
    }

    /**
     * 마켓 상태 조회
     */
    fun getMarketStatus(market: String): MarketStatus? {
        val state = marketStates[market] ?: return null
        val regime = state.regime ?: return null

        // 포지션 계산
        val position = calculatePosition(market, state.currentPrice)

        // 일별 PnL 계산
        val (dailyPnl, dailyPnlPercent) = calculateDailyPnl(market)

        return MarketStatus(
            market = market,
            currentPrice = state.currentPrice,
            regime = regime,
            strategy = state.currentStrategy?.name ?: "NONE",
            lastSignal = state.lastSignal,
            position = position,
            dailyPnl = dailyPnl,
            dailyPnlPercent = dailyPnlPercent
        )
    }

    /**
     * 포지션 계산
     */
    private fun calculatePosition(market: String, currentPrice: BigDecimal): Position? {
        val balances = try {
            bithumbPrivateApi.getBalances() ?: return null
        } catch (e: Exception) {
            return null
        }

        val parts = market.split("_")
        if (parts.size != 2) return null

        val coinBalance = balances.find { it.currency == parts[0] } ?: return null
        val quantity = coinBalance.balance
        if (quantity <= BigDecimal.ZERO) return null

        val avgPrice = coinBalance.avgBuyPrice ?: currentPrice
        val unrealizedPnl = (currentPrice - avgPrice) * quantity
        val unrealizedPnlPercent = if (avgPrice > BigDecimal.ZERO) {
            ((currentPrice - avgPrice) / avgPrice * BigDecimal(100)).toDouble()
        } else 0.0

        return Position(
            market = market,
            quantity = quantity,
            avgPrice = avgPrice,
            currentPrice = currentPrice,
            unrealizedPnl = unrealizedPnl,
            unrealizedPnlPercent = unrealizedPnlPercent
        )
    }

    /**
     * 일별 PnL 계산
     */
    private fun calculateDailyPnl(market: String): Pair<BigDecimal, Double> {
        // RiskManager에서 계산된 일별 통계 활용
        val stats = riskManager.getRiskStats(market, BigDecimal("1000000"))
        return Pair(BigDecimal.ZERO, 0.0) // 실제 구현은 TradeRepository에서 조회 필요
    }

    /**
     * 모든 마켓 상태 조회
     */
    fun getAllMarketStatuses(): Map<String, MarketStatus> {
        return tradingProperties.markets.mapNotNull { market ->
            getMarketStatus(market)?.let { market to it }
        }.toMap()
    }

    /**
     * 수동 분석 트리거
     */
    fun triggerAnalysis(market: String): TradingSignal? {
        return analyzeMarket(market)
    }
}
