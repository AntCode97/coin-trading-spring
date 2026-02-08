package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.BithumbPrivateApi
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

        // 운영 킬스위치: trading.enabled=false면 주문 차단
        if (!isTradingEnabled()) {
            log.warn("[$market] trading.enabled=false - 주문 실행 차단")
            return
        }

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
            // API 에러는 OrderExecutor를 통해 주문 단계에서 기록됨
            return
        }

        if (balances == null) {
            log.error("[$market] 잔고 응답 null")
            return
        }

        val krwBalance = balances.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO

        // 1.5 총 자산 기록 (CircuitBreaker 낙폭 추적용)
        val totalAssetKrw = calculateTotalAssetKrw(balances, state.currentPrice, market)
        circuitBreaker.recordTotalAsset(totalAssetKrw)
        dailyLossLimitService.syncInitialCapitalForToday(totalAssetKrw)

        // 2. 리스크 체크
        val riskCheck = riskManager.canTrade(market, krwBalance)
        if (!riskCheck.canTrade) {
            log.warn("[$market] 리스크 체크 실패: ${riskCheck.reason}")

            if (!riskAlertTracker.isInCooldown(market)) {
                slackNotifier.sendWarning(market, "리스크 체크 실패: ${riskCheck.reason}")
                riskAlertTracker.recordAlert(market)
            }
            return
        }

        // 2.5. 일일 손실 한도 체크
        if (!dailyLossLimitService.canTrade()) {
            log.warn("[$market] 일일 손실 한도 도달로 트레이딩 중지: ${dailyLossLimitService.tradingHaltedReason}")
            slackNotifier.sendError(market, "일일 손실 한도 도달: ${dailyLossLimitService.tradingHaltedReason}")
            return
        }

        // 3. 주문 금액 산정
        val riskBasedPositionSize = riskManager.calculatePositionSize(market, krwBalance, signal.confidence)
        var orderAmountKrw = riskBasedPositionSize

        // [BUG FIX] 매수/매도 모두 잔고 체크 + 중복 매수 방지
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val coinBalance = balances.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO

        if (signal.action == SignalAction.BUY) {
            // KRW 잔고 확인
            if (orderAmountKrw > krwBalance) {
                log.warn("[$market] KRW 잔고 부족: 필요 $orderAmountKrw, 보유 $krwBalance")
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

            // SELL은 KRW 잔고가 아니라 보유 코인 가치 기준으로 주문 금액 산정
            val sellableAmountKrw = coinBalance.multiply(state.currentPrice)
            if (sellableAmountKrw < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
                log.warn(
                    "[$market] 매도 가능 금액 최소 주문 미달: ${sellableAmountKrw}원 < ${TradingConstants.MIN_ORDER_AMOUNT_KRW}원"
                )
                return
            }
            orderAmountKrw = sellableAmountKrw
        }

        // 4. 주문 실행 (MarketConditionChecker는 OrderExecutor 내부에서 호출)
        val result = orderExecutor.execute(signal, orderAmountKrw)

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

            // 일일 손실 한도에 실제 실현손익 기록 (매도만)
            if (signal.action == SignalAction.SELL) {
                recordDailyPnlFromExecutedTrade(market, result)
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
                OrderRejectionReason.MARKET_SUSPENDED -> "거래 정지/상장 폐지 코인"
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

    private fun recordDailyPnlFromExecutedTrade(market: String, result: com.ant.cointrading.order.OrderResult) {
        val orderId = result.orderId
        if (orderId.isNullOrBlank()) {
            log.warn("[$market] orderId 없음 - 일일 손익 기록 스킵")
            return
        }

        val executedTrade = tradeRepository.findTopByOrderIdOrderByCreatedAtDesc(orderId)
        if (executedTrade == null) {
            log.warn("[$market] 체결 거래 레코드 없음(orderId=$orderId) - 일일 손익 기록 스킵")
            return
        }

        val pnl = executedTrade.pnl
        if (pnl == null) {
            log.warn("[$market] 거래 PnL 없음(orderId=$orderId) - 일일 손익 기록 스킵")
            return
        }

        dailyLossLimitService.recordPnl(pnl)
        log.info("[$market] 일일 손익 반영: pnl=${String.format("%.0f", pnl)}원, orderId=$orderId")
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
     * [버그 수정] 마켓 형식을 KRW-BTC, BTC_KRW 등 다양한 형식 지원
     * 기존: _ 분할만 지원 (BTC_KRW만 정상 작동)
     * 수정: PositionHelper.extractCoinSymbol 사용으로 모든 형식 지원
     */
    private fun calculatePosition(market: String, currentPrice: BigDecimal): Position? {
        val balances = try {
            bithumbPrivateApi.getBalances() ?: return null
        } catch (e: Exception) {
            return null
        }

        // 코인 심변 추출 (KRW-BTC, BTC_KRW, BTC_KRW 등 모든 형식 지원)
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val coinBalance = balances.find { it.currency == coinSymbol } ?: return null
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
        return try {
            val startOfDay = LocalDate.now(com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE)
                .atStartOfDay(com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE)
                .toInstant()

            val trades = tradeRepository.findByMarketAndCreatedAtAfter(market, startOfDay)
            val totalPnl = trades
                .mapNotNull { it.pnl }
                .fold(BigDecimal.ZERO) { acc, pnl -> acc + BigDecimal.valueOf(pnl) }

            val currentBalance = try {
                bithumbPrivateApi.getBalances()
                    ?.find { it.currency == "KRW" }
                    ?.balance
                    ?: BigDecimal.ZERO
            } catch (_: Exception) {
                BigDecimal.ZERO
            }

            val pnlPercent = if (currentBalance > BigDecimal.ZERO) {
                totalPnl.divide(currentBalance, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .toDouble()
            } else {
                0.0
            }

            Pair(totalPnl, pnlPercent)
        } catch (e: Exception) {
            log.warn("[$market] 일별 PnL 계산 실패: ${e.message}")
            Pair(BigDecimal.ZERO, 0.0)
        }
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
        val now = Instant.now()
        val regimes = marketStates.values.mapNotNull { it.regime }

        if (regimes.isEmpty()) {
            log.debug("레짐 데이터 없음, 거래 중지 체크 스킵")
            return
        }

        // 하락 추세 마켓 비율 계산
        val bearMarketCount = regimes.count { it.regime == MarketRegime.BEAR_TREND }
        val bearMarketRatio = bearMarketCount.toDouble() / regimes.size

        // 현재 거래 활성화 상태 확인
        val currentTradingEnabled = keyValueService.get("trading.enabled", "true").toBoolean()

        log.debug("레짐 현황: 하락=${bearMarketCount}/${regimes.size} (${String.format("%.0f", bearMarketRatio * 100)}%), " +
                  "거래활성=${currentTradingEnabled}, 중지상태=${isTradingSuspendedByRegime}")

        when {
            // 하락 추세가 80% 이상이면 거래 중지 고려
            bearMarketRatio >= BEAR_TREND_THRESHOLD -> {
                if (lastBearTrendDetected == null) {
                    lastBearTrendDetected = now
                    log.warn("하락 추세 감지: ${String.format("%.0f", bearMarketRatio * 100)}% 마켓이 BEAR_TREND")
                }

                val bearDurationMinutes = Duration.between(lastBearTrendDetected!!, now).toMinutes()

                // 최소 30분간 하락 추세 유지 시 거래 중지
                if (bearDurationMinutes >= MIN_BEAR_DURATION_MINUTES && !isTradingSuspendedByRegime) {
                    suspendTradingDueToRegime(bearMarketRatio, bearDurationMinutes)
                }
            }

            // 상승/횡보 50% 이상이면 거래 재개
            bearMarketRatio < (1.0 - BULL_TREND_THRESHOLD) -> {
                if (isTradingSuspendedByRegime) {
                    resumeTradingDueToRegime(bearMarketRatio)
                }
                lastBearTrendDetected = null
            }

            // 중간 상태: 시간 초기화만
            else -> {
                lastBearTrendDetected = null
            }
        }
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
