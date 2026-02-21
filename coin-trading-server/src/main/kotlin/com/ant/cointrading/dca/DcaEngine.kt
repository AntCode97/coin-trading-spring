package com.ant.cointrading.dca

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.engine.CloseRecoveryQueueService
import com.ant.cointrading.engine.CloseRecoveryStrategy
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
import com.ant.cointrading.service.BalanceReservationService
import com.ant.cointrading.service.TradingAmountService
import com.ant.cointrading.engine.GlobalPositionManager
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.engine.PositionCloser
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
    private val regimeDetector: RegimeDetector,
    private val globalPositionManager: GlobalPositionManager,
    private val closeRecoveryQueueService: CloseRecoveryQueueService,
    private val balanceReservationService: BalanceReservationService,
    private val tradingAmountService: TradingAmountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 공통 청산 로직 (PositionCloser 사용)
    private val positionCloser = PositionCloser(bithumbPrivateApi, orderExecutor, slackNotifier)

    // 마켓별 쿨다운 추적
    private val cooldowns = ConcurrentHashMap<String, Instant>()

    companion object {
        private const val SCAN_INTERVAL_MS = 60_000L  // 1분
        private const val MONITOR_INTERVAL_MS = 5_000L // 5초
        private const val CLOSE_RETRY_BACKOFF_SECONDS = 5L  // 청산 재시도 백오프
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
            log.info("복원할 DCA OPEN 포지션 없음")
        } else {
            log.info("DCA 열린 포지션 ${openPositions.size}건 복원")
            openPositions.forEach { position ->
                log.info("[${position.market}] 포지션 복원: 수량=${position.totalQuantity}, 평균가=${position.averagePrice}원")
            }
        }

        // CLOSING 포지션 복구 (서버 재시작으로 중단된 청산 재개)
        val closingPositions = dcaPositionRepository.findByStatus("CLOSING")
        if (closingPositions.isNotEmpty()) {
            log.warn("CLOSING 포지션 ${closingPositions.size}건 복구 - 청산 재개 시도")
            closingPositions.forEach { position ->
                log.warn("[${position.market}] CLOSING 포지션 복구: 시도=${position.closeAttemptCount}")
                // CLOSING 상태이면 다음 모니터링 사이클에서 자동 처리됨
            }
        }

        val abandonedPositions = dcaPositionRepository.findByStatus("ABANDONED")
        if (abandonedPositions.isNotEmpty()) {
            log.warn("ABANDONED 포지션 ${abandonedPositions.size}건 복구 큐 등록")
            abandonedPositions.forEach { enqueueCloseRecovery(it, "RESTORE_ABANDONED") }
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

        runCatching {
            val markets = tradingProperties.markets
            log.debug("DCA 마켓 스캔 시작: ${markets.size}개 마켓")
            forEachMarketSafely(markets, "스캔 중") { market -> scanSingleMarket(market) }
        }.onFailure { e ->
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
                timestamp = parseInstantSafe(response.candleDateTimeUtc),
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
     * 진입 조건 체크
     *
     * [엔진 간 충돌 방지] 다른 엔진의 포지션 확인
     * [잔고 불일치 방지] 이미 해당 코인 보유 시 진입 불가
     */
    private fun shouldEnterPosition(market: String): Boolean {
        // [엔진 간 충돌 방지] 다른 엔진(TradingEngine, VolumeSurge, MemeScalper)에서 포지션 확인
        if (globalPositionManager.hasOpenPosition(market)) {
            log.debug("[$market] 다른 엔진에서 열린 포지션 존재 - DCA 진입 차단")
            return false
        }

        // 실제 잔고 체크 - KRW 충분한지 + 이미 해당 코인 보유 시 진입 불가
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val requiredKrw = tradingAmountService.getAdaptiveAmount("dca")

        try {
            val balances = bithumbPrivateApi.getBalances() ?: return false

            // 1. KRW 잔고 체크 - 포지션 크기 + 여유분(10%)
            val krwBalance = balances.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO
            val minRequired = requiredKrw.multiply(BigDecimal("1.1"))  // 10% 여유
            if (krwBalance < minRequired) {
                log.warn("[$market] KRW 잔고 부족: ${krwBalance}원 < ${minRequired}원")
                return false
            }

            // 2. 코인 잔고 체크 - 이미 보유 시 진입 불가 (엣지 케이스 방지)
            val coin = balances.find { it.currency == coinSymbol }
            val coinBalance = (coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)
            if (coinBalance > BigDecimal.ZERO) {
                log.warn("[$market] 이미 $coinSymbol 잔고 보유 중: $coinBalance - 중복 진입 방지")
                return false
            }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, 진입 보류: ${e.message}")
            return false
        }

        return true
    }

    /**
     * 매수 주문 실행 및 검증
     *
     * [엣지 케이스 처리]
     * - executedVolume 0 체크 (API 응답과 실제 체결 불일치)
     * - 진입가 0 검증
     */
    private fun executeBuyOrderWithValidation(
        market: String,
        signal: TradingSignal,
        orderAmount: BigDecimal
    ): BuyExecutionResult? {
        val buySignal = TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = signal.confidence,
            price = signal.price,
            reason = "DCA 진입: ${signal.reason}",
            strategy = "DCA",
            regime = signal.regime
        )

        val orderResult = orderExecutor.execute(buySignal, orderAmount)

        if (!orderResult.success) {
            log.error("[$market] 매수 주문 실패: ${orderResult.message}")
            cooldowns[market] = Instant.now().plus(5, ChronoUnit.MINUTES)
            return null
        }

        if (orderResult.isPending && (orderResult.executedQuantity == null || orderResult.executedQuantity <= BigDecimal.ZERO)) {
            log.warn("[$market] 미체결 주문 상태로 DCA 진입 보류: orderId=${orderResult.orderId}, pendingOrderId=${orderResult.pendingOrderId}")
            cooldowns[market] = Instant.now().plus(1, ChronoUnit.MINUTES)
            return null
        }

        // 체결 수량 검증
        val executedQuantity = orderResult.executedQuantity?.toDouble() ?: 0.0
        if (executedQuantity <= 0) {
            log.error("[$market] 체결 수량 0 - 주문 실패로 간주")
            slackNotifier.sendWarning(market, "매수 주문 체결 수량 0 - API 확인 필요")
            cooldowns[market] = Instant.now().plus(5, ChronoUnit.MINUTES)
            return null
        }

        // 체결가 결정 - 우선순위: orderResult > API 현재가 > signal
        val executedPrice = orderResult.price?.toDouble()?.takeIf { it > 0 }
            ?: bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
            ?: signal.price.toDouble()

        // 진입가 검증
        if (executedPrice <= 0) {
            log.error("[$market] 유효하지 않은 진입가: $executedPrice - 포지션 생성 취소")
            slackNotifier.sendWarning(market, "매수 체결되었으나 체결가 확인 불가 - 수동 확인 필요")
            cooldowns[market] = Instant.now().plus(60, ChronoUnit.SECONDS)
            return null
        }

        // 슬리피지 경고
        val expectedPrice = signal.price.toDouble()
        if (expectedPrice > 0) {
            val slippagePercent = ((executedPrice - expectedPrice) / expectedPrice) * 100
            if (kotlin.math.abs(slippagePercent) > 1.0) {
                log.warn("[$market] 슬리피지 경고: ${String.format("%.2f", slippagePercent)}%")
            }
        }

        return BuyExecutionResult(executedPrice, executedQuantity)
    }

    /**
     * 실제 잔고 확인 (API 응답만 믿지 않음)
     *
     * 빗썸 API는 주문 체결 응답을 보내지만, 실제 잔고가 증가하지 않는 경우가 있음.
     * 주문 실행 후 반드시 실제 잔고를 확인하여 포지션 생성 여부를 결정해야 함.
     */
    private fun verifyActualBalance(market: String, expectedQuantity: Double): Boolean {
        return try {
            val coinSymbol = PositionHelper.extractCoinSymbol(market)
            val balances = bithumbPrivateApi.getBalances() ?: run {
                log.error("[$market] 잔고 조회 실패 - null 응답")
                return false
            }

            val coin = balances.find { it.currency == coinSymbol }
            val actualBalance = (coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)

            // 예상 수량의 90% 이상이면 성공으로 간주 (부분 체결/수수료 고려)
            val minAcceptableBalance = BigDecimal(expectedQuantity).multiply(BigDecimal("0.9"))

            if (actualBalance < minAcceptableBalance) {
                log.error("[$market] 잔고 불일치: 예상=${String.format("%.6f", expectedQuantity)}, 실제=${actualBalance}")
                slackNotifier.sendWarning(market, """
                    매수 주문 체결되었으나 실제 잔고 없음 (API 응답 불일치)
                    예상 수량: ${String.format("%.6f", expectedQuantity)}
                    실제 잔고: ${actualBalance}
                    주문 확인 필요.
                """.trimIndent())
                return false
            }

            log.info("[$market] 잔고 확인 완료: ${actualBalance}")
            true
        } catch (e: Exception) {
            log.error("[$market] 잔고 확인 중 예외 발생: ${e.message}", e)
            false
        }
    }

    /**
     * 포지션 진입
     *
     * 켄트백: 각 하위 작업을 명확한 메서드로 분리 (Compose Method 패턴)
     */
    private fun enterPosition(
        market: String,
        signal: TradingSignal,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ) {
        log.info("[$market] DCA 진입 시도: 신뢰도=${signal.confidence}%, 이유=${signal.reason}")

        val orderAmount = tradingAmountService.getAdaptiveAmount("dca")

        // 0. KRW 잔고 예약 (원자적 잔고 확보)
        if (!balanceReservationService.reserve("DCA", market, orderAmount)) {
            log.warn("[$market] KRW 잔고 부족 - DCA 진입 취소")
            return
        }

        try {
            // 1. 잔고 확인 및 진입 조건 체크 (2차 안전장치: 코인 중복 보유 + 엔진 간 충돌 방지)
            if (!shouldEnterPosition(market)) {
                return
            }

            // 2. 매수 주문 실행 및 검증
            val executionResult = executeBuyOrderWithValidation(market, signal, orderAmount) ?: return

            // 3. 실제 잔고 확인 (API 응답만 믿지 않음)
            if (!verifyActualBalance(market, executionResult.quantity)) {
                log.error("[$market] 실제 잔고 확인 실패 - 포지션 생성 취소")
                cooldowns[market] = Instant.now().plus(60, ChronoUnit.SECONDS)
                return
            }

            // 4. DCA 포지션 생성/갱신
            dcaStrategy.recordBuy(
                market,
                executionResult.quantity,
                executionResult.price,
                executionResult.quantity * executionResult.price
            )

            // 5. 진입 후 상태 설정
            setupPostEntryState(market, regime)

            // 6. 진입 알림 발송
            sendEntryNotification(market, executionResult, regime)

            log.info("[$market] 진입 완료: 가격=${executionResult.price}, 수량=${executionResult.quantity}")
        } finally {
            balanceReservationService.release("DCA", market)
        }
    }

    /**
     * 진입 후 상태 설정
     */
    private fun setupPostEntryState(market: String, regime: RegimeAnalysis) {
        val cooldownMinutes = tradingProperties.strategy.dcaInterval / 60_000
        cooldowns[market] = Instant.now().plus(cooldownMinutes, ChronoUnit.MINUTES)

        // 진입 시 레짐 저장 (ActivePositionManager용)
        val openPositions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
        openPositions.forEach { pos ->
            if (pos.entryRegime == "UNKNOWN") {
                pos.entryRegime = regime.regime.name
                dcaPositionRepository.save(pos)
            }
        }
    }

    /**
     * 진입 알림 발송
     */
    private fun sendEntryNotification(market: String, result: BuyExecutionResult, regime: RegimeAnalysis) {
        val executedAmount = result.price * result.quantity
        slackNotifier.sendSystemNotification(
            "DCA 진입",
            """
            마켓: $market
            체결가: ${result.price}원
            수량: ${String.format("%.6f", result.quantity)}
            금액: ${String.format("%.0f", executedAmount)}원
            레짐: ${regime.regime.name}
            """.trimIndent()
        )
    }

    /**
     * 매수 실행 결과
     */
    private data class BuyExecutionResult(
        val price: Double,
        val quantity: Double
    )

    /**
     * 포지션 모니터링 (5초마다)
     *
     * OPEN: 익절/손절/타임아웃 조건 확인
     * CLOSING: 청산 주문 상태 확인
     */
    @Scheduled(fixedDelay = MONITOR_INTERVAL_MS)
    fun monitorPositions() {
        if (!tradingProperties.enabled) return

        // OPEN 포지션 모니터링
        val openPositions = dcaPositionRepository.findByStatus("OPEN")
        forEachPositionSafely(openPositions, "OPEN 포지션 모니터링") { position ->
            monitorSinglePosition(position)
        }

        // CLOSING 포지션 모니터링 (청산 진행 중)
        val closingPositions = dcaPositionRepository.findByStatus("CLOSING")
        forEachPositionSafely(closingPositions, "CLOSING 포지션 모니터링") { position ->
            monitorClosingPosition(position)
        }

        // ABANDONED 포지션 재시도 (10분마다 체크)
        val now = Instant.now()
        val lastRetryCheckKey = "abandoned_last_check"
        val lastCheck = cooldowns[lastRetryCheckKey]
        if (lastCheck == null || ChronoUnit.MINUTES.between(lastCheck, now) >= 10) {
            cooldowns[lastRetryCheckKey] = now
            retryAbandonedPositions()
        }
    }

    private inline fun forEachMarketSafely(
        markets: List<String>,
        action: String,
        operation: (String) -> Unit
    ) {
        markets.forEach { market ->
            try {
                operation(market)
            } catch (e: Exception) {
                log.error("[$market] $action 오류: ${e.message}", e)
            }
        }
    }

    private inline fun forEachPositionSafely(
        positions: List<DcaPositionEntity>,
        action: String,
        operation: (DcaPositionEntity) -> Unit
    ) {
        positions.forEach { position ->
            try {
                operation(position)
            } catch (e: Exception) {
                log.error("[${position.market}] $action 오류: ${e.message}", e)
            }
        }
    }

    /**
     * 단일 포지션 모니터링
     *
     * 20년차 퀀트의 포지션 모니터링:
     * 1. 진입가 무결성 검증 - 0원이면 거래 불가
     * 2. 안전한 손익률 계산 - NaN/Infinity 방지
     */
    private fun monitorSinglePosition(position: DcaPositionEntity) {
        val market = position.market

        val actualHolding = getCoinTotalBalance(market).toDouble()
        if (actualHolding <= 0.0 && position.totalQuantity > 0.0) {
            log.warn("[$market] 수동 개입 감지(실보유 0) - DCA 포지션 자동 종료")
            position.status = "CLOSED"
            position.exitReason = "MANUAL_INTERVENTION"
            position.exitedAt = Instant.now()
            dcaPositionRepository.save(position)
            return
        }
        if (actualHolding > 0.0 && actualHolding < position.totalQuantity * 0.95) {
            log.info("[$market] 실보유 수량 동기화: ${position.totalQuantity} -> $actualHolding")
            position.totalQuantity = actualHolding
            dcaPositionRepository.save(position)
        }

        // 0. 진입가 무결성 검증 - 진입가가 0이면 거래 불가능
        if (position.averagePrice <= 0) {
            log.error("[$market] 진입가 무효(${position.averagePrice}) - 포지션 정리 필요")
            // 현재가로 진입가 보정 시도
            val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
            if (ticker != null) {
                position.averagePrice = ticker.tradePrice.toDouble()
                position.createdAt = Instant.now()  // 진입 시간도 리셋
                dcaPositionRepository.save(position)
                log.info("[$market] 진입가 보정: ${position.averagePrice}원")
            }
            return  // 이번 사이클은 스킵
        }

        // 현재가 조회
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull() ?: return
        val currentPrice = ticker.tradePrice.toDouble()

        // 현재 수익률 계산 (안전하게)
        val currentPnlPercent = safePnlPercent(position.averagePrice, currentPrice)

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
     * 안전한 손익률 계산 - NaN/Infinity 방지
     */
    private fun safePnlPercent(entryPrice: Double, currentPrice: Double): Double {
        if (entryPrice <= 0) return 0.0
        return ((currentPrice - entryPrice) / entryPrice) * 100
    }

    /**
     * CLOSING 포지션 모니터링
     *
     * PositionHelper를 통해 청산 주문 상태 확인 및 완료 처리
     */
    private fun monitorClosingPosition(position: DcaPositionEntity) {
        val market = position.market
        val closeOrderId = position.closeOrderId

        // 주문 ID가 없으면 OPEN으로 복원
        if (closeOrderId.isNullOrBlank()) {
            log.warn("[$market] 청산 주문 ID 없음, OPEN으로 복원")
            position.status = "OPEN"
            position.closeOrderId = null
            position.closeAttemptCount = 0
            dcaPositionRepository.save(position)
            return
        }

        // 주문 상태 조회 (PositionHelper 위임)
        PositionHelper.monitorClosingPosition(
            bithumbPrivateApi = bithumbPrivateApi,
            position = position,  // DcaPositionEntity가 PositionEntity를 구현하므로 직접 전달
            waitTimeoutSeconds = 10L,
            errorTimeoutMinutes = 2L,
            onOrderDone = { actualPrice ->
                // 주문 체결 완료 - 실제 체결 수량 확인
                val actualQuantity = try {
                    val coinSymbol = PositionHelper.extractCoinSymbol(market)
                    val balances = bithumbPrivateApi.getBalances()
                    val beforeQuantity = position.totalQuantity
                    val coin = balances?.find { it.currency == coinSymbol }
                    val currentBalance = ((coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)).toDouble()
                    val soldQuantity = beforeQuantity - currentBalance
                    if (soldQuantity > 0) soldQuantity else position.totalQuantity
                } catch (e: Exception) {
                    log.warn("[$market] 체결 수량 계산 실패, 전량 체결로 간주: ${e.message}")
                    position.totalQuantity
                }
                finalizeClose(position, actualPrice, actualQuantity, position.exitReason ?: "UNKNOWN")
            },
            onOrderCancelled = {
                log.warn("[$market] 청산 주문 취소됨 또는 복원 필요")
                position.status = "OPEN"
                position.closeOrderId = null
                position.closeAttemptCount = 0
                dcaPositionRepository.save(position)
            }
        )
    }

    /**
     * ABANDONED 포지션 재시도
     *
     * 청산 실패 후 ABANDONED된 포지션을 재시도하여 고립 방지
     */
    private fun retryAbandonedPositions() {
        val abandonedPositions = dcaPositionRepository.findByStatus("ABANDONED")
        if (abandonedPositions.isEmpty()) return

        log.info("ABANDONED 포지션 ${abandonedPositions.size}건 재시도 시작")

        forEachPositionSafely(abandonedPositions, "ABANDONED 재시도") { position ->
            retryAbandonedPosition(position)
        }
    }

    /**
     * ABANDONED 포지션 단일 재시도
     */
    private fun retryAbandonedPosition(position: DcaPositionEntity): Boolean {
        val market = position.market

        // 잔고 확인 - 이미 없으면 CLOSED로 변경
        val coinSymbol = market.removePrefix("KRW-")
        val actualBalance = try {
            val coin = bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }
            (coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            return false
        }

        if (actualBalance <= BigDecimal.ZERO) {
            log.info("[$market] 잔고 없음 - ABANDONED -> CLOSED 변경")
            position.status = "CLOSED"
            position.exitReason = "ABANDONED_NO_BALANCE"
            position.exitedAt = Instant.now()
            position.realizedPnl = 0.0
            position.realizedPnlPercent = 0.0
            dcaPositionRepository.save(position)
            return true
        }

        enqueueCloseRecovery(position, "ABANDONED_RETRY")
        return true
    }

    /**
     * 포지션 청산
     *
     * 켄트백: 각 하위 작업을 명확한 메서드로 분리 (Compose Method 패턴)
     */
    private fun closePosition(position: DcaPositionEntity, exitPrice: Double, reason: String) {
        val market = position.market

        // 1. 최대 시도 횟수 체크
        if (position.closeAttemptCount >= TradingConstants.MAX_CLOSE_ATTEMPTS) {
            log.error("[$market] 청산 시도 ${TradingConstants.MAX_CLOSE_ATTEMPTS}회 초과, ABANDONED 처리")
            handleAbandoned(position, exitPrice, "MAX_ATTEMPTS")
            return
        }

        // 2. 중복 청산 방지
        if (!canClosePosition(position.status, position.lastCloseAttempt)) {
            return
        }

        log.info("[$market] 포지션 청산 시도 #${position.closeAttemptCount + 1}: $reason")

        // 3. 실제 잔고 확인
        val actualBalance = getActualSellBalance(market, position.totalQuantity)
        if (actualBalance <= BigDecimal.ZERO) {
            log.warn("[$market] 실제 잔고 없음 - ABANDONED 처리")
            handleAbandoned(position, exitPrice, "NO_BALANCE")
            return
        }

        // 4. 매도 수량 결정 및 최소 금액 체크
        val sellQuantity = actualBalance.toDouble().coerceAtMost(position.totalQuantity)
        val sellAmount = BigDecimal(sellQuantity * exitPrice)

        if (sellAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 최소 주문 금액 미달 - ABANDONED 처리")
            handleAbandoned(position, exitPrice, "MIN_AMOUNT")
            return
        }

        // 5. 상태 변경 및 저장 (CLOSING 상태로)
        position.status = "CLOSING"
        position.exitReason = reason
        position.lastCloseAttempt = Instant.now()
        position.closeAttemptCount++
        dcaPositionRepository.save(position)

        // 6. 매도 주문 실행
        val orderResult = executeSellOrder(market, sellQuantity, exitPrice, reason)

        if (orderResult == null) {
            // 주문 실패 - OPEN으로 복원
            log.warn("[$market] 매도 주문 실패 - OPEN으로 복원")
            position.status = "OPEN"
            position.closeOrderId = null
            dcaPositionRepository.save(position)
            return
        }

        // 7. closeOrderId 업데이트
        position.closeOrderId = orderResult.orderId
        dcaPositionRepository.save(position)

        // 8. 체결 결과 확인
        if (orderResult.isFullyFilled) {
            // 전체 체결 - 완료 처리
            finalizeClose(
                position,
                orderResult.actualPrice,
                orderResult.actualQuantity,
                reason
            )
        } else {
            // 부분 체결 - 남은 수량을 위해 OPEN으로 복원 (다음 사이클에서 재청산)
            val remainingQuantity = position.totalQuantity - orderResult.actualQuantity
            log.warn("[$market] 부분 체결: ${orderResult.actualQuantity}/${position.totalQuantity}, 남은 수량=$remainingQuantity")

            position.totalQuantity = remainingQuantity.coerceAtLeast(0.0)
            position.status = if (remainingQuantity > 0) "OPEN" else "CLOSED"
            dcaPositionRepository.save(position)
        }
    }

    /**
     * 중복 청산 방지 체크
     */
    private fun canClosePosition(status: String, lastAttempt: Instant?): Boolean {
        if (status == "CLOSING") {
            val lastAttempt = lastAttempt ?: return false
            val elapsed = ChronoUnit.SECONDS.between(lastAttempt, Instant.now())
            return elapsed >= CLOSE_RETRY_BACKOFF_SECONDS
        }
        return true
    }

    /**
     * 실제 매도 가능 잔고 확인
     */
    private fun getActualSellBalance(market: String, requestedQuantity: Double): BigDecimal {
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        return try {
            val balances = bithumbPrivateApi.getBalances() ?: return BigDecimal.ZERO
            val coin = balances.find { it.currency.equals(coinSymbol, ignoreCase = true) }
            val coinBalance = (coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)

            when {
                coinBalance <= BigDecimal.ZERO -> BigDecimal.ZERO
                coinBalance < BigDecimal(requestedQuantity) -> coinBalance  // 전량 매도
                else -> BigDecimal(requestedQuantity)
            }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패: ${e.message}")
            BigDecimal.ZERO
        }
    }

    private fun getCoinTotalBalance(market: String): BigDecimal {
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val balances = bithumbPrivateApi.getBalances() ?: return BigDecimal.ZERO
        val coin = balances.find { it.currency.equals(coinSymbol, ignoreCase = true) }
        return (coin?.balance ?: BigDecimal.ZERO) + (coin?.locked ?: BigDecimal.ZERO)
    }

    /**
     * 매도 주문 실행
     *
     * @return SellExecutionResult or null (실패 시)
     */
    private fun executeSellOrder(
        market: String,
        sellQuantity: Double,
        exitPrice: Double,
        reason: String
    ): SellExecutionResult? {
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "DCA 청산: $reason",
            strategy = "DCA",
            regime = null
        )

        val sellAmount = BigDecimal(sellQuantity * exitPrice)
        val orderResult = orderExecutor.execute(sellSignal, sellAmount)

        if (!orderResult.success) {
            log.error("[$market] 매도 주문 실패: ${orderResult.message}")
            return null
        }

        if (orderResult.isPending && (orderResult.executedQuantity == null || orderResult.executedQuantity <= BigDecimal.ZERO)) {
            log.warn("[$market] 미체결 매도 주문 상태 - 청산 보류: orderId=${orderResult.orderId}, pendingOrderId=${orderResult.pendingOrderId}")
            return null
        }

        // executedVolume 0 체크 (엣지 케이스)
        val executedQuantity = orderResult.executedQuantity?.toDouble() ?: 0.0
        if (executedQuantity <= 0) {
            log.error("[$market] 체결 수량 0 - 매도 실패로 간주")
            slackNotifier.sendWarning(market, """
                매도 주문 체결 수량 0
                주문ID: ${orderResult.orderId}
                API 확인 필요.
            """.trimIndent())
            return null
        }

        // 실제 체결가 결정
        val actualPrice = orderResult.price?.toDouble() ?: exitPrice

        return SellExecutionResult(
            orderId = orderResult.orderId ?: "",
            actualPrice = actualPrice,
            actualQuantity = executedQuantity,
            isFullyFilled = kotlin.math.abs(executedQuantity - sellQuantity) < 0.0001  // 99.99% 이상 체결
        )
    }

    /**
     * 청산 완료 처리
     */
    private fun finalizeClose(
        position: DcaPositionEntity,
        actualExitPrice: Double,
        actualSellQuantity: Double,
        reason: String
    ) {
        val market = position.market

        // 손익 계산
        val pnlAmount = (actualExitPrice - position.averagePrice) * actualSellQuantity
        val pnlPercent = ((actualExitPrice - position.averagePrice) / position.averagePrice) * 100

        // DcaStrategy.recordSell() 호출 (포지션 완전 청산)
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

        log.info("[$market] 청산 완료: $reason, 손익=${pnlAmount}원 (${pnlPercent}%)")
    }

    /**
     * ABANDONED 처리
     */
    private fun handleAbandoned(position: DcaPositionEntity, exitPrice: Double, abandonReason: String) {
        val market = position.market

        // 실제 손익 계산 (현재가 기준)
        val pnlAmount = (exitPrice - position.averagePrice) * position.totalQuantity
        val pnlPercent = ((exitPrice - position.averagePrice) / position.averagePrice) * 100

        position.status = "ABANDONED"
        position.exitReason = "ABANDONED_$abandonReason"
        position.exitPrice = exitPrice
        position.exitedAt = Instant.now()
        position.realizedPnl = pnlAmount
        position.realizedPnlPercent = pnlPercent
        dcaPositionRepository.save(position)

        slackNotifier.sendWarning(
            market,
            """
            DCA 포지션 ABANDONED: $abandonReason
            진입가: ${position.averagePrice}원 → 현재가: ${exitPrice}원
            추정 손익: ${String.format("%.0f", pnlAmount)}원 (${String.format("%.2f", pnlPercent)}%)
            자동 복구 큐에 등록됨 (백그라운드 청산 재시도)
            """.trimIndent()
        )

        enqueueCloseRecovery(position, abandonReason, exitPrice)
        log.warn("[$market] ABANDONED 처리: $abandonReason, 추정 손익=${pnlAmount}원 (${pnlPercent}%)")
    }

    private fun enqueueCloseRecovery(position: DcaPositionEntity, reason: String, lastKnownPrice: Double? = null) {
        val positionId = position.id ?: return
        closeRecoveryQueueService.enqueue(
            strategy = CloseRecoveryStrategy.DCA,
            positionId = positionId,
            market = position.market,
            entryPrice = position.averagePrice,
            targetQuantity = position.totalQuantity,
            reason = reason,
            lastKnownPrice = lastKnownPrice
        )
    }

    /**
     * 매도 실행 결과
     */
    private data class SellExecutionResult(
        val orderId: String,
        val actualPrice: Double,
        val actualQuantity: Double,
        val isFullyFilled: Boolean
    )

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

    /**
     * 안전한 Instant 파싱 (시간대 정보 없으면 UTC로 간주)
     */
    private fun parseInstantSafe(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()

        return try {
            Instant.parse(dateTimeStr)
        } catch (e: Exception) {
            // 시간대 정보 없으면 UTC로 간주하고 'Z' 추가
            try {
                Instant.parse("${dateTimeStr}Z")
            } catch (e2: Exception) {
                // 그래도 실패하면 LocalDateTime으로 파싱 후 UTC로 변환
                try {
                    val localDateTime = java.time.LocalDateTime.parse(dateTimeStr)
                    localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
                } catch (e3: Exception) {
                    log.warn("시간 파싱 실패: $dateTimeStr, 현재 시간 사용")
                    Instant.now()
                }
            }
        }
    }
}
