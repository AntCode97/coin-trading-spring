package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.Balance
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.order.OrderRejectionReason
import com.ant.cointrading.regime.HmmRegimeDetector
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.TradeRepository
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
import java.time.LocalDate
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
    private val tradeRepository: TradeRepository,
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

        // 레짐 기반 거래 중지 설정
        const val REGIME_CHECK_INTERVAL_MS = 300_000L  // 5분마다 레짐 확인
        const val BEAR_TREND_THRESHOLD = 0.8  // 80% 이상 마켓이 하락 추세면 중지
        const val BULL_TREND_THRESHOLD = 0.5  // 50% 이상 상승/횡보면 재개
        const val MIN_BEAR_DURATION_MINUTES = 30L  // 최소 30분간 하락 추세 유지 시 중지

        private val EMERGENCY_EXIT_KEYWORDS = listOf(
            "손절",
            "stop_loss",
            "stop-loss",
            "stop loss",
            "리스크 오프",
            "risk off",
            "트레일링",
            "trailing"
        )

        internal fun shouldBypassMinHoldingForSell(signal: TradingSignal): Boolean {
            if (signal.action != SignalAction.SELL) return false
            val normalizedReason = signal.reason.lowercase()
            return EMERGENCY_EXIT_KEYWORDS.any { normalizedReason.contains(it) }
        }

        internal fun isBearDominantRegime(bearMarketRatio: Double): Boolean {
            return bearMarketRatio >= BEAR_TREND_THRESHOLD
        }

        internal fun isRecoveryRegime(bearMarketRatio: Double): Boolean {
            return bearMarketRatio < (1.0 - BULL_TREND_THRESHOLD)
        }

        internal fun resolveOrderFailureDetail(
            rejectionReason: OrderRejectionReason?,
            fallbackMessage: String
        ): String {
            return when (rejectionReason) {
                OrderRejectionReason.MARKET_CONDITION -> "시장 상태 불량 - 스프레드/유동성 확인"
                OrderRejectionReason.API_ERROR -> "API 장애 - 거래소 상태 확인"
                OrderRejectionReason.VERIFICATION_FAILED -> "주문 상태 확인 실패 - 수동 확인 필요"
                OrderRejectionReason.NO_FILL -> "체결 없음 - 유동성 부족 의심"
                OrderRejectionReason.EXCEPTION -> "시스템 예외"
                OrderRejectionReason.CIRCUIT_BREAKER -> "서킷 브레이커 발동"
                OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT -> "최소 주문 금액(5000원) 미달"
                OrderRejectionReason.MARKET_SUSPENDED -> "거래 정지/상장 폐지 코인"
                null -> fallbackMessage
            }
        }
    }

    private val log = LoggerFactory.getLogger(TradingEngine::class.java)

    // 마켓별 상태
    private val marketStates = ConcurrentHashMap<String, MarketState>()

    // 리스크 경고 추적器
    private val riskAlertTracker = RiskAlertTracker()

    // [무한 루프 방지] 마켓별 마지막 매수 시간 (최소 보유 시간 체크용)
    private val lastBuyTime = ConcurrentHashMap<String, Instant>()

    // [무한 루프 방지] 마켓별 마지막 매도 시간 (재매수 쿨다운 체크용)
    private val lastSellTime = ConcurrentHashMap<String, Instant>()

    // 레짐 기반 거래 중지 상태 추적
    private var lastBearTrendDetected: Instant? = null  // 마지막 하락 추세 감지 시간
    @Volatile
    private var isTradingSuspendedByRegime = false  // 레짐으로 인한 거래 중지 상태

    data class MarketState(
        var candles: List<Candle> = emptyList(),
        var currentPrice: BigDecimal = BigDecimal.ZERO,
        var regime: RegimeAnalysis? = null,
        var currentStrategy: TradingStrategy? = null,
        var lastSignal: TradingSignal? = null,
        var lastAnalysisTime: Instant = Instant.EPOCH
    )

    private data class BalanceSnapshot(
        val krwBalance: BigDecimal,
        val coinSymbol: String,
        val coinBalance: BigDecimal
    )

    private data class OrderPreparation(
        val orderAmountKrw: BigDecimal
    )

    private data class DcaFillData(
        val quantity: Double,
        val price: Double,
        val amount: Double
    )

    private data class RegimeSnapshot(
        val totalMarkets: Int,
        val bearMarketCount: Int,
        val bearMarketRatio: Double,
        val tradingEnabled: Boolean
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
        if (!isTradingEnabled()) {
            log.debug("trading.enabled=false - 스케줄 분석 스킵")
            return
        }
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
        val candles = loadCandlesIntoState(market, state) ?: return null
        val regime = analyzeRegimeIntoState(market, state, candles)
        val strategy = selectStrategyIntoState(market, state, regime)
        val signal = generateSignalIntoState(market, state, strategy, regime, candles)
        processSignalIfNeeded(market, signal, state)
        return signal
    }

    private fun loadCandlesIntoState(market: String, state: MarketState): List<Candle>? {
        val candles = fetchCandles(market)
        if (candles.isEmpty()) {
            log.warn("캔들 데이터 없음: $market")
            return null
        }
        state.candles = candles
        state.currentPrice = candles.last().close
        return candles
    }

    private fun analyzeRegimeIntoState(
        market: String,
        state: MarketState,
        candles: List<Candle>
    ): RegimeAnalysis {
        val regime = detectRegime(candles)
        state.regime = regime
        log.debug("[$market] 레짐: ${regime.regime}, ADX: ${regime.adx}, ATR%: ${regime.atrPercent}")
        return regime
    }

    private fun selectStrategyIntoState(
        market: String,
        state: MarketState,
        regime: RegimeAnalysis
    ): TradingStrategy {
        val strategy = strategySelector.selectStrategy(regime, market)
        state.currentStrategy = strategy
        return strategy
    }

    private fun generateSignalIntoState(
        market: String,
        state: MarketState,
        strategy: TradingStrategy,
        regime: RegimeAnalysis,
        candles: List<Candle>
    ): TradingSignal {
        val signal = strategy.analyze(market, candles, state.currentPrice, regime)
        state.lastSignal = signal
        state.lastAnalysisTime = Instant.now()
        return signal
    }

    private fun processSignalIfNeeded(market: String, signal: TradingSignal, state: MarketState) {
        if (signal.action == SignalAction.HOLD) {
            return
        }
        processSignal(market, signal, state)
    }

    /**
     * 신호 처리
     */
    private fun processSignal(market: String, signal: TradingSignal, state: MarketState) {
        logSignal(market, signal)
        val preparation = prepareOrderExecution(market, signal, state) ?: return
        val result = orderExecutor.execute(signal, preparation.orderAmountKrw)
        if (result.success) {
            handleSuccessfulOrderResult(market, signal, state, result)
            return
        }

        handleFailedOrderResult(market, result)
    }

    private fun logSignal(market: String, signal: TradingSignal) {
        log.info(
            """
            [$market] 신호 발생
            행동: ${signal.action}
            신뢰도: ${signal.confidence}%
            전략: ${signal.strategy}
            사유: ${signal.reason}
            """.trimIndent()
        )
    }

    private fun prepareOrderExecution(
        market: String,
        signal: TradingSignal,
        state: MarketState
    ): OrderPreparation? {
        val now = Instant.now()
        if (!passesTradeGuards(market, signal, now)) return null

        val balances = fetchBalancesOrNull(market) ?: return null
        val balanceSnapshot = createBalanceSnapshot(market, balances)
        val totalAssetKrw = syncAndGetTotalAssetKrw(balances, state.currentPrice, market)
        if (!passesRiskChecks(market, totalAssetKrw)) return null

        val orderAmountKrw = resolveOrderAmount(market, signal, state, balanceSnapshot) ?: return null
        if (isBelowMinimumOrderAmount(market, orderAmountKrw)) return null

        return OrderPreparation(orderAmountKrw = orderAmountKrw)
    }

    private fun isBelowMinimumOrderAmount(market: String, orderAmountKrw: BigDecimal): Boolean {
        if (orderAmountKrw >= TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            return false
        }
        log.info(
            "[$market] 주문 금액 최소치 미달로 실행 스킵: amount=$orderAmountKrw, min=${TradingConstants.MIN_ORDER_AMOUNT_KRW}"
        )
        return true
    }

    private fun passesTradeGuards(market: String, signal: TradingSignal, now: Instant): Boolean {
        if (!passesTradingEnabledGuard(market)) return false
        if (!passesCircuitBreakerGuard(market)) return false
        return passesActionTimingGuard(market, signal, now)
    }

    private fun passesTradingEnabledGuard(market: String): Boolean {
        if (isTradingEnabled()) {
            return true
        }
        log.warn("[$market] trading.enabled=false - 주문 실행 차단")
        return false
    }

    private fun passesCircuitBreakerGuard(market: String): Boolean {
        val circuitCheck = circuitBreaker.canTrade(market)
        if (circuitCheck.canTrade) {
            return true
        }
        log.warn("[$market] 서킷 브레이커 발동: ${circuitCheck.reason}")
        return false
    }

    private fun passesActionTimingGuard(market: String, signal: TradingSignal, now: Instant): Boolean {
        if (signal.action == SignalAction.BUY && shouldBlockBuyByCooldown(market, now)) {
            return false
        }
        if (signal.action == SignalAction.SELL && shouldBlockSellByHolding(market, signal, now)) {
            return false
        }
        return true
    }

    private fun fetchBalancesOrNull(market: String): List<Balance>? {
        val balances = try {
            bithumbPrivateApi.getBalances()
        } catch (e: Exception) {
            log.error("[$market] 잔고 조회 실패: ${e.message}")
            // API 에러는 OrderExecutor를 통해 주문 단계에서 기록됨
            return null
        }

        if (balances == null) {
            log.error("[$market] 잔고 응답 null")
            return null
        }

        return balances
    }

    private fun createBalanceSnapshot(market: String, balances: List<Balance>): BalanceSnapshot {
        val krwBalance = balances.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val coinBalance = balances.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        return BalanceSnapshot(
            krwBalance = krwBalance,
            coinSymbol = coinSymbol,
            coinBalance = coinBalance
        )
    }

    private fun syncAndGetTotalAssetKrw(
        balances: List<Balance>,
        currentPrice: BigDecimal,
        market: String
    ): BigDecimal {
        // 총 자산 기록 (CircuitBreaker 낙폭 추적용)
        val totalAssetKrw = calculateTotalAssetKrw(balances, currentPrice, market)
        circuitBreaker.recordTotalAsset(totalAssetKrw.toDouble())
        dailyLossLimitService.syncInitialCapitalForToday(totalAssetKrw.toDouble())
        return totalAssetKrw
    }

    private fun passesRiskChecks(market: String, totalAssetKrw: BigDecimal): Boolean {
        if (!passesPortfolioRiskCheck(market, totalAssetKrw)) return false
        return passesDailyLossLimitCheck(market)
    }

    private fun passesPortfolioRiskCheck(market: String, totalAssetKrw: BigDecimal): Boolean {
        val riskCheck = riskManager.canTrade(market, totalAssetKrw)
        if (riskCheck.canTrade) {
            return true
        }

        log.warn("[$market] 리스크 체크 실패: ${riskCheck.reason}")
        if (!riskAlertTracker.isInCooldown(market)) {
            slackNotifier.sendWarning(market, "리스크 체크 실패: ${riskCheck.reason}")
            riskAlertTracker.recordAlert(market)
        }
        return false
    }

    private fun passesDailyLossLimitCheck(market: String): Boolean {
        if (dailyLossLimitService.canTrade()) {
            return true
        }
        log.warn("[$market] 일일 손실 한도 도달로 트레이딩 중지: ${dailyLossLimitService.tradingHaltedReason}")
        slackNotifier.sendError(market, "일일 손실 한도 도달: ${dailyLossLimitService.tradingHaltedReason}")
        return false
    }

    private fun resolveOrderAmount(
        market: String,
        signal: TradingSignal,
        state: MarketState,
        balances: BalanceSnapshot
    ): BigDecimal? {
        val riskBasedPositionSize = riskManager.calculatePositionSize(market, balances.krwBalance, signal.confidence)

        return when (signal.action) {
            SignalAction.BUY -> resolveBuyOrderAmount(market, state, riskBasedPositionSize, balances)
            SignalAction.SELL -> resolveSellOrderAmount(market, state, balances)
            SignalAction.HOLD -> null
        }
    }

    private fun resolveBuyOrderAmount(
        market: String,
        state: MarketState,
        riskBasedPositionSize: BigDecimal,
        balances: BalanceSnapshot
    ): BigDecimal? {
        if (!hasSufficientKrwForBuy(market, riskBasedPositionSize, balances)) return null
        if (isDuplicateBuyBlocked(market, state, balances)) return null
        if (isBuyBlockedByOtherEngines(market)) return null
        return riskBasedPositionSize
    }

    private fun hasSufficientKrwForBuy(
        market: String,
        riskBasedPositionSize: BigDecimal,
        balances: BalanceSnapshot
    ): Boolean {
        if (riskBasedPositionSize <= balances.krwBalance) {
            return true
        }
        log.warn("[$market] KRW 잔고 부족: 필요 $riskBasedPositionSize, 보유 ${balances.krwBalance}")
        return false
    }

    private fun isDuplicateBuyBlocked(
        market: String,
        state: MarketState,
        balances: BalanceSnapshot
    ): Boolean {
        val coinValueKrw = balances.coinBalance.multiply(state.currentPrice)
        if (coinValueKrw < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            return false
        }
        log.warn(
            "[$market] 이미 ${balances.coinSymbol} 보유 중: ${balances.coinBalance} (${coinValueKrw}원 상당) - 중복 매수 방지"
        )
        return true
    }

    private fun isBuyBlockedByOtherEngines(market: String): Boolean {
        if (!isOccupiedByOtherEngines(market)) {
            return false
        }
        log.warn("[$market] 다른 엔진 점유 중 - TradingEngine 진입 차단")
        return true
    }

    private fun resolveSellOrderAmount(
        market: String,
        state: MarketState,
        balances: BalanceSnapshot
    ): BigDecimal? {
        // 매도 시 코인 잔고 확인
        if (balances.coinBalance <= BigDecimal.ZERO) {
            log.warn("[$market] ${balances.coinSymbol} 잔고 없음: ${balances.coinBalance} - 매도 취소")
            return null
        }

        // SELL은 KRW 잔고가 아니라 보유 코인 가치 기준으로 주문 금액 산정
        val sellableAmountKrw = balances.coinBalance.multiply(state.currentPrice)
        if (sellableAmountKrw < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn(
                "[$market] 매도 가능 금액 최소 주문 미달: ${sellableAmountKrw}원 < ${TradingConstants.MIN_ORDER_AMOUNT_KRW}원"
            )
            return null
        }
        return sellableAmountKrw
    }

    private fun handleSuccessfulOrderResult(
        market: String,
        signal: TradingSignal,
        state: MarketState,
        result: com.ant.cointrading.order.OrderResult
    ) {
        val hasExecutedFill = hasExecutedFill(result)
        logPendingExecutionStatus(market, result, hasExecutedFill)
        updateExecutionTracking(market, signal, state, result, hasExecutedFill)
        notifySuccessfulTrade(market, signal, result, hasExecutedFill)
        handlePostSellRiskSync(market, signal, result, hasExecutedFill)
    }

    private fun logPendingExecutionStatus(
        market: String,
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ) {
        if (hasExecutedFill || !result.isPending) {
            return
        }
        log.info("[$market] 주문 접수됨(미체결) - 체결 전까지 루프가드/실현손익 반영을 보류합니다")
    }

    private fun updateExecutionTracking(
        market: String,
        signal: TradingSignal,
        state: MarketState,
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ) {
        if (hasExecutedFill) {
            recordExecutionTimestamp(market, signal.action)
        }
        handleDcaPositionTrackingIfNeeded(market, signal, state, result, hasExecutedFill)
    }

    private fun notifySuccessfulTrade(
        market: String,
        signal: TradingSignal,
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ) {
        val notificationMessage = buildTradeNotificationMessage(result, hasExecutedFill)
        log.info("[$market] $notificationMessage")
        slackNotifier.sendTradeNotification(signal, result)
    }

    private fun handlePostSellRiskSync(
        market: String,
        signal: TradingSignal,
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ) {
        if (signal.action != SignalAction.SELL || !hasExecutedFill) {
            return
        }
        recordDailyPnlFromExecutedTrade(market, result)
        riskManager.refreshStats(market)
    }

    private fun handleDcaPositionTrackingIfNeeded(
        market: String,
        signal: TradingSignal,
        state: MarketState,
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ) {
        // DCA 전략인 경우 포지션 추적 (실제 체결된 경우만 반영)
        if (signal.strategy != "DCA" || !hasExecutedFill) return

        val fillData = resolveDcaFillData(market, result) ?: return
        val dcaStrategy = state.currentStrategy as? DcaStrategy ?: return
        applyDcaExecution(market, signal, dcaStrategy, fillData)
    }

    private fun resolveDcaFillData(
        market: String,
        result: com.ant.cointrading.order.OrderResult
    ): DcaFillData? {
        val quantity = result.executedQuantity?.toDouble() ?: 0.0
        val price = result.price?.toDouble() ?: 0.0
        if (quantity <= 0.0 || price <= 0.0) {
            log.warn("[$market] DCA 체결 데이터 불완전(수량=$quantity, 가격=$price) - 포지션 반영 스킵")
            return null
        }
        return DcaFillData(
            quantity = quantity,
            price = price,
            amount = quantity * price
        )
    }

    private fun applyDcaExecution(
        market: String,
        signal: TradingSignal,
        dcaStrategy: DcaStrategy,
        fillData: DcaFillData
    ) {
        when (signal.action) {
            SignalAction.BUY -> dcaStrategy.recordBuy(
                market,
                fillData.quantity,
                fillData.price,
                fillData.amount
            )
            SignalAction.SELL -> dcaStrategy.recordSell(
                market,
                fillData.quantity,
                fillData.price,
                resolveDcaExitReason(signal.reason)
            )
            SignalAction.HOLD -> Unit
        }
    }

    private fun resolveDcaExitReason(reason: String): String {
        return when {
            reason.contains("익절") -> "TAKE_PROFIT"
            reason.contains("손절") -> "STOP_LOSS"
            reason.contains("타임아웃") -> "TIMEOUT"
            else -> "SIGNAL"
        }
    }

    private fun buildTradeNotificationMessage(
        result: com.ant.cointrading.order.OrderResult,
        hasExecutedFill: Boolean
    ): String {
        return buildString {
            if (hasExecutedFill) {
                append("체결 완료")
                if (result.isPartialFill) {
                    append(" (부분 ${String.format("%.1f", result.fillRatePercent)}%)")
                }
                if (result.slippagePercent > 0.5) {
                    append(" ⚠️슬리피지: ${String.format("%.2f", result.slippagePercent)}%")
                }
                result.fee?.let { append(" | 수수료: $it") }
            } else if (result.isPending) {
                append("주문 접수됨 (PendingOrderManager에서 체결 관리 중)")
            } else {
                append("주문 성공 응답 수신 (체결 수량 미확인)")
            }
        }
    }

    private fun handleFailedOrderResult(market: String, result: com.ant.cointrading.order.OrderResult) {
        val errorDetail = resolveOrderFailureDetail(result.rejectionReason, result.message)
        log.error("[$market] 주문 실패: $errorDetail")
        slackNotifier.sendError(market, "주문 실패: $errorDetail\n원인: ${result.rejectionReason}")
    }

    /**
     * 총 자산 계산 (KRW 환산)
     */
    private fun calculateTotalAssetKrw(
        balances: List<Balance>,
        currentPrice: BigDecimal,
        market: String
    ): BigDecimal {
        val knownPricesByCoin = buildKnownCoinPrices()
        return balances.fold(BigDecimal.ZERO) { total, balance ->
            total + calculateBalanceValueInKrw(balance, market, currentPrice, knownPricesByCoin)
        }
    }

    private fun buildKnownCoinPrices(): Map<String, BigDecimal> {
        return marketStates.asSequence()
            .map { (knownMarket, state) -> PositionHelper.extractCoinSymbol(knownMarket) to state.currentPrice }
            .filter { (_, price) -> price > BigDecimal.ZERO }
            .toMap()
    }

    private fun calculateBalanceValueInKrw(
        balance: Balance,
        market: String,
        currentPrice: BigDecimal,
        knownPricesByCoin: Map<String, BigDecimal>
    ): BigDecimal {
        val amount = balance.balance
        if (amount <= BigDecimal.ZERO) {
            return BigDecimal.ZERO
        }
        if (balance.currency == "KRW") {
            return amount
        }

        val valuationPrice = resolveCoinValuationPrice(balance, market, currentPrice, knownPricesByCoin)
        return amount.multiply(valuationPrice)
    }

    private fun resolveCoinValuationPrice(
        balance: Balance,
        market: String,
        currentPrice: BigDecimal,
        knownPricesByCoin: Map<String, BigDecimal>
    ): BigDecimal {
        val coinMarket = "${balance.currency}_KRW"
        if (coinMarket == market) {
            return currentPrice
        }
        return knownPricesByCoin[balance.currency]
            ?.takeIf { it > BigDecimal.ZERO }
            ?: (balance.avgBuyPrice ?: BigDecimal.ZERO)
    }

    private fun recordDailyPnlFromExecutedTrade(market: String, result: com.ant.cointrading.order.OrderResult) {
        val orderId = result.orderId
        if (orderId.isNullOrBlank()) {
            log.warn("[$market] orderId 없음 - 일일 손익 기록 스킵")
            return
        }

        val executedTrade = resolveSellTradeForDailyPnl(market, orderId) ?: return
        val pnl = executedTrade.pnl ?: run {
            log.warn("[$market] 거래 PnL 없음(orderId=$orderId) - 일일 손익 기록 스킵")
            return
        }
        dailyLossLimitService.recordPnl(pnl)
        log.info("[$market] 일일 손익 반영: pnl=${String.format("%.0f", pnl)}원, orderId=$orderId")
    }

    private fun resolveSellTradeForDailyPnl(market: String, orderId: String): com.ant.cointrading.repository.TradeEntity? {
        val executedTrade = tradeRepository.findTopByOrderIdOrderByCreatedAtDesc(orderId)
        if (executedTrade == null) {
            log.warn("[$market] 체결 거래 레코드 없음(orderId=$orderId) - 일일 손익 기록 스킵")
            return null
        }
        if (!executedTrade.side.equals("SELL", ignoreCase = true)) {
            log.info("[$market] SELL 체결이 아님(side=${executedTrade.side}, orderId=$orderId) - 일일 손익 반영 스킵")
            return null
        }
        return executedTrade
    }

    private fun hasExecutedFill(result: com.ant.cointrading.order.OrderResult): Boolean {
        return result.executedQuantity?.let { it > BigDecimal.ZERO } == true
    }

    private fun recordExecutionTimestamp(market: String, action: SignalAction) {
        when (action) {
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
    }

    private fun shouldBlockBuyByCooldown(market: String, now: Instant): Boolean {
        val lastSell = lastSellTime[market] ?: return false
        val secondsSinceSell = Duration.between(lastSell, now).seconds
        if (secondsSinceSell >= TRADE_COOLDOWN_SECONDS) return false

        val remaining = TRADE_COOLDOWN_SECONDS - secondsSinceSell
        log.info("[$market] 재매수 쿨다운 중: ${remaining}초 남음")
        return true
    }

    private fun shouldBlockSellByHolding(market: String, signal: TradingSignal, now: Instant): Boolean {
        val lastBuy = lastBuyTime[market] ?: return false
        val secondsSinceBuy = Duration.between(lastBuy, now).seconds
        if (secondsSinceBuy >= MIN_HOLDING_SECONDS) return false

        if (shouldBypassMinHoldingForSell(signal)) {
            val remaining = MIN_HOLDING_SECONDS - secondsSinceBuy
            log.warn("[$market] 보호성 매도 감지로 최소 보유 시간 우회: ${remaining}초 단축")
            return false
        }

        val remaining = MIN_HOLDING_SECONDS - secondsSinceBuy
        log.info("[$market] 최소 보유 시간 미충족: ${remaining}초 남음 (수수료 손실 방지)")
        return true
    }

    private fun isOccupiedByOtherEngines(market: String): Boolean {
        val occupiedByVolumeSurge = globalPositionManager.hasOpenPositionInEngine(market, EngineType.VOLUME_SURGE)
        val occupiedByMemeScalper = globalPositionManager.hasOpenPositionInEngine(market, EngineType.MEME_SCALPER)
        return occupiedByVolumeSurge || occupiedByMemeScalper
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
            // market 형식 통일: KRW-BTC, BTC_KRW, KRW_BTC 등 → KRW-BTC
            val apiMarket = PositionHelper.convertToApiMarket(market)

            val response = bithumbPublicApi.getOhlcv(apiMarket, "minute60", 100)

            if (response == null) {
                // API 에러 기록
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
        val (dailyPnl, dailyPnlPercent) = calculateDailyPnl(market, state.currentPrice)

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
     * [버그 수정] 마켓 형식을 KRW-BTC, BTC_KRW 등 다양한 형식 지원
     * 기존: _ 분할만 지원 (BTC_KRW만 정상 작동)
     * 수정: PositionHelper.extractCoinSymbol 사용으로 모든 형식 지원
     */
    private fun calculatePosition(market: String, currentPrice: BigDecimal): Position? {
        val balances = fetchBalancesForPosition() ?: return null
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val coinBalance = findPositiveCoinBalance(balances, coinSymbol) ?: return null
        return buildPositionSnapshot(market, currentPrice, coinBalance.balance, coinBalance.avgBuyPrice)
    }

    private fun fetchBalancesForPosition(): List<Balance>? {
        return try {
            bithumbPrivateApi.getBalances()
        } catch (_: Exception) {
            null
        }
    }

    private fun findPositiveCoinBalance(balances: List<Balance>, coinSymbol: String): Balance? {
        val coinBalance = balances.find { it.currency == coinSymbol } ?: return null
        if (coinBalance.balance <= BigDecimal.ZERO) {
            return null
        }
        return coinBalance
    }

    private fun buildPositionSnapshot(
        market: String,
        currentPrice: BigDecimal,
        quantity: BigDecimal,
        avgBuyPrice: BigDecimal?
    ): Position {
        val avgPrice = avgBuyPrice ?: currentPrice
        val unrealizedPnl = (currentPrice - avgPrice) * quantity
        return Position(
            market = market,
            quantity = quantity,
            avgPrice = avgPrice,
            currentPrice = currentPrice,
            unrealizedPnl = unrealizedPnl,
            unrealizedPnlPercent = calculateUnrealizedPnlPercent(currentPrice, avgPrice)
        )
    }

    private fun calculateUnrealizedPnlPercent(currentPrice: BigDecimal, avgPrice: BigDecimal): Double {
        if (avgPrice <= BigDecimal.ZERO) {
            return 0.0
        }
        return ((currentPrice - avgPrice) / avgPrice * BigDecimal(100)).toDouble()
    }

    /**
     * 일별 PnL 계산
     */
    private fun calculateDailyPnl(market: String, currentPrice: BigDecimal): Pair<BigDecimal, Double> {
        return try {
            val trades = findTodayTrades(market)
            val totalPnl = calculateTotalPnl(trades)
            val totalAsset = calculateCurrentTotalAsset(market, currentPrice)
            Pair(totalPnl, calculatePnlPercent(totalPnl, totalAsset))
        } catch (e: Exception) {
            log.warn("[$market] 일별 PnL 계산 실패: ${e.message}")
            Pair(BigDecimal.ZERO, 0.0)
        }
    }

    private fun findTodayTrades(market: String): List<com.ant.cointrading.repository.TradeEntity> {
        val startOfDay = LocalDate.now(com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE)
            .atStartOfDay(com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE)
            .toInstant()
        return tradeRepository.findByMarketAndSimulatedAndCreatedAtAfter(market, false, startOfDay)
    }

    private fun calculateTotalPnl(trades: List<com.ant.cointrading.repository.TradeEntity>): BigDecimal {
        return trades.mapNotNull { it.pnl }
            .fold(BigDecimal.ZERO) { acc, pnl -> acc + BigDecimal.valueOf(pnl) }
    }

    private fun calculateCurrentTotalAsset(market: String, currentPrice: BigDecimal): BigDecimal {
        return try {
            val balances = bithumbPrivateApi.getBalances() ?: emptyList()
            calculateTotalAssetKrw(balances, currentPrice, market)
        } catch (_: Exception) {
            BigDecimal.ZERO
        }
    }

    private fun calculatePnlPercent(totalPnl: BigDecimal, totalAsset: BigDecimal): Double {
        if (totalAsset <= BigDecimal.ZERO) {
            return 0.0
        }
        return totalPnl.divide(totalAsset, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()
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
        if (!isTradingEnabled()) return null
        return analyzeMarket(market)
    }

    /**
     * 레짐 기반 거래 중지 모니터링 (5분마다 실행)
     *
     * 모든 마켓이 하락 추세(BEAR_TREND)이면 거래 중지
     * 상승/횡보 전환 시 거래 재개
     */
    @Scheduled(fixedDelay = REGIME_CHECK_INTERVAL_MS)
    fun monitorRegimeBasedTradingSuspension() {
        try {
            checkRegimeAndSuspendTrading()
        } catch (e: Exception) {
            log.error("레짐 기반 거래 중지 모니터링 오류: ${e.message}", e)
        }
    }

    /**
     * 레짐 확인 및 거래 중지/재개 처리
     */
    private fun checkRegimeAndSuspendTrading() {
        val snapshot = collectRegimeSnapshot() ?: return
        val now = Instant.now()
        logRegimeSnapshot(snapshot)
        processRegimeTransition(snapshot, now)
    }

    private fun collectRegimeSnapshot(): RegimeSnapshot? {
        val regimes = marketStates.values.mapNotNull { it.regime }
        if (regimes.isEmpty()) {
            log.debug("레짐 데이터 없음, 거래 중지 체크 스킵")
            return null
        }

        val bearMarketCount = regimes.count { it.regime == MarketRegime.BEAR_TREND }
        val bearMarketRatio = bearMarketCount.toDouble() / regimes.size
        val tradingEnabled = keyValueService.get("trading.enabled", "true").toBoolean()
        return RegimeSnapshot(
            totalMarkets = regimes.size,
            bearMarketCount = bearMarketCount,
            bearMarketRatio = bearMarketRatio,
            tradingEnabled = tradingEnabled
        )
    }

    private fun logRegimeSnapshot(snapshot: RegimeSnapshot) {
        log.debug(
            "레짐 현황: 하락=${snapshot.bearMarketCount}/${snapshot.totalMarkets} " +
                "(${String.format("%.0f", snapshot.bearMarketRatio * 100)}%), " +
                "거래활성=${snapshot.tradingEnabled}, 중지상태=${isTradingSuspendedByRegime}"
        )
    }

    private fun processRegimeTransition(snapshot: RegimeSnapshot, now: Instant) {
        when {
            isBearDominantRegime(snapshot.bearMarketRatio) ->
                handleBearDominantRegime(snapshot.bearMarketRatio, now)
            isRecoveryRegime(snapshot.bearMarketRatio) ->
                handleRecoveryRegime(snapshot.bearMarketRatio)
            else -> resetBearTrendDetection()
        }
    }

    private fun handleBearDominantRegime(bearMarketRatio: Double, now: Instant) {
        if (lastBearTrendDetected == null) {
            lastBearTrendDetected = now
            log.warn("하락 추세 감지: ${String.format("%.0f", bearMarketRatio * 100)}% 마켓이 BEAR_TREND")
        }

        val detectedAt = lastBearTrendDetected ?: now
        val bearDurationMinutes = Duration.between(detectedAt, now).toMinutes()
        if (bearDurationMinutes >= MIN_BEAR_DURATION_MINUTES && !isTradingSuspendedByRegime) {
            suspendTradingDueToRegime(bearMarketRatio, bearDurationMinutes)
        }
    }

    private fun handleRecoveryRegime(bearMarketRatio: Double) {
        if (isTradingSuspendedByRegime) {
            resumeTradingDueToRegime(bearMarketRatio)
        }
        resetBearTrendDetection()
    }

    private fun resetBearTrendDetection() {
        lastBearTrendDetected = null
    }

    /**
     * 레짐으로 인한 거래 중지 실행
     */
    private fun suspendTradingDueToRegime(bearMarketRatio: Double, durationMinutes: Long) {
        isTradingSuspendedByRegime = true

        // KeyValueService에 거래 중지 상태 저장
        keyValueService.set(
            key = "trading.enabled",
            value = "false",
            category = "trading",
            description = "레짐 기반 자동 거래 중지 (하락 추세 ${String.format("%.0f", bearMarketRatio * 100)}%)"
        )

        val message = """
            🛑 [레짐 기반 거래 중지]
            하락 추세 지속 ${durationMinutes}분 (${String.format("%.0f", bearMarketRatio * 100)}% 마켓이 BEAR_TREND)
            trading.enabled = false로 변경됨
            상승/횡보 전환 시 자동 재개됩니다
        """.trimIndent()

        log.warn(message)
        slackNotifier.sendSystemNotification("레짐 기반 거래 중지", message)
    }

    /**
     * 레짐 개선으로 인한 거래 재개
     */
    private fun resumeTradingDueToRegime(bearMarketRatio: Double) {
        isTradingSuspendedByRegime = false

        // KeyValueService에 거래 재개 상태 저장
        keyValueService.set(
            key = "trading.enabled",
            value = "true",
            category = "trading",
            description = "레짐 개선으로 거래 재개 (하락 추세 ${String.format("%.0f", bearMarketRatio * 100)}%)"
        )

        val message = """
            ✅ [레짐 기반 거래 재개]
            시장 상태 개선 (하락 추세 ${String.format("%.0f", bearMarketRatio * 100)}%)
            trading.enabled = true로 변경됨
            정상 트레이딩 재개
        """.trimIndent()

        log.info(message)
        slackNotifier.sendSystemNotification("레짐 기반 거래 재개", message)
    }

    private fun isTradingEnabled(): Boolean {
        return keyValueService.getBoolean("trading.enabled", tradingProperties.enabled)
    }
}
