package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.engine.GlobalPositionManager
import com.ant.cointrading.engine.PositionCloser
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.extension.isPositive
import com.ant.cointrading.extension.orDefault
import com.ant.cointrading.extension.orZero
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.*
import com.ant.cointrading.risk.DynamicRiskRewardCalculator
import com.ant.cointrading.risk.PnlCalculator
import com.ant.cointrading.risk.SimpleCircuitBreaker
import com.ant.cointrading.risk.SimpleCircuitBreakerFactory
import com.ant.cointrading.risk.SimpleCircuitBreakerState
import com.ant.cointrading.risk.SimpleCircuitBreakerStatePersistence
import com.ant.cointrading.risk.StopLossCalculator
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Volume Surge Trading Engine
 *
 * 거래량 급등 종목 단타 전략의 메인 엔진.
 *
 * 전략 흐름:
 * 1. AlertPollingService에서 경보 감지
 * 2. LLM 필터로 펌프앤덤프 필터링
 * 3. 기술적 분석 (컨플루언스)
 * 4. 포지션 진입
 * 5. 실시간 포지션 모니터링 (손절/익절/트레일링/타임아웃)
 * 6. 일일 회고로 학습
 */
@Component
class VolumeSurgeEngine(
    private val properties: VolumeSurgeProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val volumeSurgeFilter: VolumeSurgeFilter,
    private val volumeSurgeAnalyzer: VolumeSurgeAnalyzer,
    private val orderExecutor: OrderExecutor,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val dailySummaryRepository: VolumeSurgeDailySummaryRepository,
    private val slackNotifier: SlackNotifier,
    private val stopLossCalculator: StopLossCalculator,
    private val dynamicRiskRewardCalculator: DynamicRiskRewardCalculator,
    private val globalPositionManager: GlobalPositionManager,
    private val regimeDetector: RegimeDetector,
    circuitBreakerFactory: SimpleCircuitBreakerFactory
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 공통 청산 로직 (PositionCloser 사용)
    private val positionCloser = PositionCloser(bithumbPrivateApi, orderExecutor, slackNotifier)

    // 서킷브레이커 (공통 컴포넌트)
    private val circuitBreaker = circuitBreakerFactory.create(
        maxConsecutiveLosses = properties.maxConsecutiveLosses,
        dailyMaxLossKrw = properties.dailyMaxLossKrw.toDouble(),
        statePersistence = VolumeSurgeCircuitBreakerStatePersistence(dailySummaryRepository)
    )

    // 트레일링 스탑 고점 추적
    private val highestPrices = ConcurrentHashMap<Long, Double>()

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Volume Surge Engine 시작 (Virtual Thread 모드) ===")
            log.info("설정: $properties")

            // 상태 복원
            circuitBreaker.restoreState()
            restoreOpenPositions()
        }
    }

    /**
     * 서버 재시작 시 상태 복원
     *
     * - 트레일링 스탑 고점을 메모리 맵에 로드
     */
    private fun restoreOpenPositions() {
        val openPositions = tradeRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) {
            log.info("복원할 열린 포지션 없음")
            return
        }

        log.info("열린 포지션 ${openPositions.size}건 복원 중...")

        openPositions.forEach { position ->
            // 트레일링 스탑 고점 복원
            if (position.trailingActive && position.highestPrice != null) {
                highestPrices[position.id!!] = position.highestPrice!!
                log.info("[${position.market}] 트레일링 고점 복원: ${position.highestPrice}")
            }

            log.info("[${position.market}] 포지션 복원 완료 - 진입가: ${position.entryPrice}, 트레일링: ${position.trailingActive}")
        }

        log.info("=== ${openPositions.size}건 포지션 복원 완료, 모니터링 재개 ===")
    }

    /**
     * 경보 처리 (AlertPollingService에서 호출)
     */
    fun processAlert(alert: VolumeSurgeAlertEntity) {
        val market = alert.market

        log.info("[$market] 경보 처리 시작")

        // 마켓 유효성 검증 (상장 코인 여부 확인)
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
        if (ticker == null) {
            log.warn("[$market] 상장 폐지 또는 존재하지 않는 마켓")
            markAlertProcessed(alert, "SKIPPED", "상장 폐지 코�")
            return
        }

        // 서킷 브레이커 체크
        if (!canTrade()) {
            log.warn("[$market] 서킷 브레이커 발동, 거래 중지")
            markAlertProcessed(alert, "SKIPPED", "서킷 브레이커 발동")
            return
        }

        // 동시 포지션 수 체크
        val openPositions = tradeRepository.countByStatus("OPEN")
        if (openPositions >= properties.maxPositions) {
            log.info("[$market] 최대 포지션 도달 ($openPositions/${properties.maxPositions})")
            markAlertProcessed(alert, "SKIPPED", "최대 포지션 도달")
            return
        }

        // 쿨다운 체크
        val lastTrade = tradeRepository.findTopByMarketOrderByCreatedAtDesc(market)
        if (lastTrade != null) {
            val cooldownEnd = lastTrade.createdAt.plus(properties.cooldownMin.toLong(), ChronoUnit.MINUTES)
            if (Instant.now().isBefore(cooldownEnd)) {
                log.info("[$market] 쿨다운 중")
                markAlertProcessed(alert, "SKIPPED", "쿨다운 기간")
                return
            }
        }

        // LLM 필터 (Virtual Thread로 비동기 처리)
        Thread.startVirtualThread {
            try {
                val filterResult = volumeSurgeFilter.filter(market, alert)

                alert.llmFilterResult = filterResult.decision
                alert.llmFilterReason = filterResult.reason
                alert.llmConfidence = filterResult.confidence
                alertRepository.save(alert)

                if (filterResult.decision != "APPROVED") {
                    log.info("[$market] LLM 필터 거부: ${filterResult.reason}")
                    markAlertProcessed(alert, filterResult.decision, filterResult.reason)
                    return@startVirtualThread
                }

                log.info("[$market] LLM 필터 승인 (신뢰도: ${filterResult.confidence})")

                // 기술적 분석
                val analysis = volumeSurgeAnalyzer.analyze(market)
                if (analysis == null) {
                    log.warn("[$market] 기술적 분석 실패")
                    markAlertProcessed(alert, "REJECTED", "기술적 분석 실패")
                    return@startVirtualThread
                }

                // 진입 조건 체크
                if (!shouldEnter(analysis)) {
                    log.info("[$market] 진입 조건 미충족: ${analysis.rejectReason}")
                    markAlertProcessed(alert, "REJECTED", analysis.rejectReason ?: "진입 조건 미충족")
                    return@startVirtualThread
                }

                // 포지션 진입
                enterPosition(alert, analysis, filterResult)

            } catch (e: Exception) {
                log.error("[$market] 경보 처리 중 오류: ${e.message}", e)
                markAlertProcessed(alert, "ERROR", e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 진입 조건 체크 (모멘텀 전략 기준)
     *
     * 퀀트 원칙:
     * - 거래량 급등이 핵심, RSI는 보조
     * - RSI 80 이하까지 허용 (모멘텀 추종)
     * - 컨플루언스 점수가 낮아도 거래량이 충분하면 진입
     */
    private fun shouldEnter(analysis: VolumeSurgeAnalysis): Boolean {
        // RSI 극단적 과매수만 거부 (Properties.maxRsi 사용)
        if (analysis.rsi > properties.maxRsi) {
            analysis.rejectReason = "RSI 극단적 과매수 (${String.format("%.1f", analysis.rsi)} > ${properties.maxRsi})"
            return false
        }

        // 거래량이 충분하면 컨플루언스 기준 완화
        val effectiveMinConfluence = when {
            analysis.volumeRatio >= 5.0 -> 30  // 500%+ 거래량이면 30점만
            analysis.volumeRatio >= 3.0 -> 40  // 300%+ 거래량이면 40점
            analysis.volumeRatio >= 2.0 -> 50  // 200%+ 거래량이면 50점
            else -> properties.minConfluenceScore  // 기본값
        }

        if (analysis.confluenceScore < effectiveMinConfluence) {
            analysis.rejectReason = "컨플루언스 점수 부족 (${analysis.confluenceScore} < $effectiveMinConfluence)"
            return false
        }

        // 최소 거래량은 유지 (필터에서 이미 검증됨)
        val effectiveMinVolume = 1.5  // 150% 이상
        if (analysis.volumeRatio < effectiveMinVolume) {
            analysis.rejectReason = "거래량 비율 부족 (${String.format("%.1f", analysis.volumeRatio)} < $effectiveMinVolume)"
            return false
        }

        return true
    }

    /**
     * 포지션 진입
     *
     * 켄트백: 각 하위 작업을 명확한 메서드로 분리 (Compose Method 패턴)
     */
    private fun enterPosition(
        alert: VolumeSurgeAlertEntity,
        analysis: VolumeSurgeAnalysis,
        filterResult: FilterResult
    ) {
        val market = alert.market

        try {
            val currentPrice = fetchCurrentPriceWithValidation(market, alert) ?: return
            val regime = detectMarketRegime(market)
            val executionResult = executeBuyOrderWithValidation(
                market, currentPrice, filterResult, regime, alert
            ) ?: return

            val riskParams = calculateRiskManagementParams(market, executionResult.price, filterResult)
            val savedTrade = createAndSaveTradeEntity(
                alert, analysis, executionResult, riskParams, regime
            )

            markAlertProcessed(alert, "APPROVED", "포지션 진입 완료")
            sendEntryNotification(market, analysis, filterResult, executionResult, savedTrade)

            log.info("[$market] 포지션 진입 완료: id=${savedTrade.id}, orderId=${executionResult.orderId}")

        } catch (e: Exception) {
            log.error("[$market] 포지션 진입 실패: ${e.message}", e)
            markAlertProcessed(alert, "ERROR", "주문 실행 실패: ${e.message}")
        }
    }

    /**
     * 현재가 조회 및 검증
     */
    private fun fetchCurrentPriceWithValidation(
        market: String,
        alert: VolumeSurgeAlertEntity
    ): BigDecimal? {
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
        if (ticker == null) {
            log.error("[$market] 현재가 조회 실패")
            markAlertProcessed(alert, "ERROR", "현재가 조회 실패")
            return null
        }
        return ticker.tradePrice
    }

    /**
     * 시장 레짐 감지
     */
    private fun detectMarketRegime(market: String): String? {
        return try {
            val candles = bithumbPublicApi.getOhlcv(market, "minute60", 100)
            if (!candles.isNullOrEmpty() && candles.size >= 15) {
                regimeDetector.detectFromBithumb(candles).regime.name
            } else {
                log.debug("[$market] 캔들 데이터 부족으로 레짐 감지 스킵")
                null
            }
        } catch (e: Exception) {
            log.warn("[$market] 레짐 감지 실패: ${e.message}")
            null
        }
    }

    /**
     * 매수 주문 실행 및 검증
     */
    private fun executeBuyOrderWithValidation(
        market: String,
        currentPrice: BigDecimal,
        filterResult: FilterResult,
        regime: String?,
        alert: VolumeSurgeAlertEntity
    ): OrderExecutionResult? {
        val positionSize = BigDecimal(properties.positionSizeKrw)
        log.info("[$market] 시장가 매수 시도: 금액=${positionSize}원")

        val buySignal = TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = filterResult.confidence * 100,
            price = currentPrice,
            reason = "Volume Surge 진입: ${filterResult.reason}${regime?.let { ", 레짐: $it" } ?: ""}",
            strategy = "VOLUME_SURGE",
            regime = regime
        )

        val orderResult = orderExecutor.execute(buySignal, positionSize)

        if (!orderResult.success) {
            log.error("[$market] 매수 주문 실패: ${orderResult.message}")
            markAlertProcessed(alert, "ERROR", "매수 주문 실패: ${orderResult.message}")
            return null
        }

        val executedPrice = orderResult.price.orDefault(currentPrice)
        val executedQuantity = orderResult.executedQuantity.orZero()
            .let { if (it.isPositive()) it else orderResult.quantity.orZero() }

        if (executedPrice <= BigDecimal.ZERO || executedQuantity <= BigDecimal.ZERO) {
            log.error("[$market] 유효하지 않은 체결 데이터: 가격=$executedPrice, 수량=$executedQuantity")
            markAlertProcessed(alert, "ERROR", "유효하지 않은 체결 데이터")
            slackNotifier.sendWarning(market, """
                체결 데이터 오류로 진입 실패
                orderResult.price: ${orderResult.price}
                orderResult.executedQuantity: ${orderResult.executedQuantity}
                orderResult.quantity: ${orderResult.quantity}
                currentPrice: $currentPrice
                주문은 실행되었을 수 있음. 빗썸에서 확인 필요.
            """.trimIndent())
            return null
        }

        log.info("[$market] 매수 체결: 가격=$executedPrice, 수량=$executedQuantity")

        return OrderExecutionResult(
            price = executedPrice,
            quantity = executedQuantity,
            orderId = orderResult.orderId
        )
    }

    /**
     * 리스크 관리 파라미터 계산 (손절/익절)
     */
    private fun calculateRiskManagementParams(
        market: String,
        executedPrice: BigDecimal,
        filterResult: FilterResult
    ): RiskManagementParams {
        val stopLossResult = if (properties.useDynamicStopLoss) {
            stopLossCalculator.calculate(market, executedPrice.toDouble())
        } else null

        val appliedStopLossPercent = stopLossResult?.stopLossPercent
            ?: kotlin.math.abs(properties.stopLossPercent)

        val confidence = filterResult.confidence * 100
        val rrResult = dynamicRiskRewardCalculator.calculate(confidence, appliedStopLossPercent)
        val appliedTakeProfitPercent = if (properties.useDynamicTakeProfit) {
            rrResult.takeProfitPercent
        } else {
            properties.takeProfitPercent
        }

        log.info("[$market] 리스크 관리: 신뢰도=${String.format("%.0f", confidence)}%, " +
                "손절=${String.format("%.2f", appliedStopLossPercent)}%, " +
                "익절=${String.format("%.2f", appliedTakeProfitPercent)}% " +
                "(R:R=${String.format("%.1f", rrResult.riskReward)}:1, ${rrResult.reason})")

        return RiskManagementParams(
            stopLossPercent = appliedStopLossPercent,
            takeProfitPercent = appliedTakeProfitPercent,
            atr = stopLossResult?.atr,
            atrPercent = stopLossResult?.atrPercent,
            method = stopLossResult?.method?.name ?: "FIXED"
        )
    }

    /**
     * 트레이드 엔티티 생성 및 저장
     */
    private fun createAndSaveTradeEntity(
        alert: VolumeSurgeAlertEntity,
        analysis: VolumeSurgeAnalysis,
        executionResult: OrderExecutionResult,
        riskParams: RiskManagementParams,
        regime: String?
    ): VolumeSurgeTradeEntity {
        val trade = VolumeSurgeTradeEntity(
            alertId = alert.id,
            market = alert.market,
            entryPrice = executionResult.price.toDouble(),
            quantity = executionResult.quantity.toDouble(),
            entryTime = Instant.now(),
            entryRsi = analysis.rsi,
            entryMacdSignal = analysis.macdSignal,
            entryBollingerPosition = analysis.bollingerPosition,
            entryVolumeRatio = analysis.volumeRatio,
            confluenceScore = analysis.confluenceScore,
            entryAtr = riskParams.atr,
            entryAtrPercent = riskParams.atrPercent,
            appliedStopLossPercent = riskParams.stopLossPercent,
            appliedTakeProfitPercent = riskParams.takeProfitPercent,
            stopLossMethod = riskParams.method,
            llmEntryReason = executionResult.filterReason,
            llmConfidence = executionResult.confidence,
            status = "OPEN",
            regime = regime
        )

        return tradeRepository.save(trade)
    }

    /**
     * 진입 알림 발송
     */
    private fun sendEntryNotification(
        market: String,
        analysis: VolumeSurgeAnalysis,
        filterResult: FilterResult,
        executionResult: OrderExecutionResult,
        savedTrade: VolumeSurgeTradeEntity
    ) {
        slackNotifier.sendSystemNotification(
            "Volume Surge 진입",
            """
            마켓: $market
            체결가: ${executionResult.price.toPlainString()}원
            수량: ${executionResult.quantity}
            RSI: ${analysis.rsi}
            컨플루언스: ${analysis.confluenceScore}
            LLM 사유: ${filterResult.reason}
            주문ID: ${executionResult.orderId ?: "N/A"}
            """.trimIndent()
        )
    }

    /**
     * 주문 실행 결과
     */
    private data class OrderExecutionResult(
        val price: BigDecimal,
        val quantity: BigDecimal,
        val orderId: String?,
        val filterReason: String = "",
        val confidence: Double = 0.0
    )

    /**
     * 리스크 관리 파라미터
     */
    private data class RiskManagementParams(
        val stopLossPercent: Double,
        val takeProfitPercent: Double,
        val atr: Double?,
        val atrPercent: Double?,
        val method: String
    )

    /**
     * 포지션 모니터링 (1초마다)
     *
     * OPEN 포지션: 손절/익절/트레일링/타임아웃 조건 체크
     * CLOSING 포지션: 청산 주문 상태 확인 또는 재시도
     */
    @Scheduled(fixedDelay = 1000)
    fun monitorPositions() {
        if (!properties.enabled) return

        // OPEN 포지션 모니터링
        val openPositions = tradeRepository.findByStatus("OPEN")
        openPositions.forEach { position ->
            try {
                monitorSinglePosition(position)
            } catch (e: Exception) {
                log.error("[${position.market}] 포지션 모니터링 오류: ${e.message}")
            }
        }

        // CLOSING 포지션 모니터링 (청산 진행 중)
        val closingPositions = tradeRepository.findByStatus("CLOSING")
        closingPositions.forEach { position ->
            try {
                monitorClosingPosition(position)
            } catch (e: Exception) {
                log.error("[${position.market}] 청산 모니터링 오류: ${e.message}")
            }
        }
    }

    /**
     * 청산 중인 포지션 모니터링 (PositionHelper 위임)
     */
    private fun monitorClosingPosition(position: VolumeSurgeTradeEntity) {
        PositionHelper.monitorClosingPosition(
            bithumbPrivateApi = bithumbPrivateApi,
            position = position,
            waitTimeoutSeconds = 30L,
            errorTimeoutMinutes = 5L,
            onOrderDone = { actualPrice ->
                finalizeClose(position, actualPrice, position.exitReason ?: "UNKNOWN")
                log.info("[${position.market}] 청산 주문 체결 확인: ${position.closeOrderId}")
            },
            onOrderCancelled = {
                log.warn("[${position.market}] 청산 주문 취소됨 또는 복원 필요")
                position.status = "OPEN"
                position.closeOrderId = null
                position.closeAttemptCount = 0
                tradeRepository.save(position)
            }
        )
    }

    /**
     * 단일 포지션 모니터링
     */
    private fun monitorSinglePosition(position: VolumeSurgeTradeEntity) {
        val market = position.market

        // 잘못된 데이터 방어: entryPrice가 0 이하면 ABANDONED 처리
        if (position.entryPrice <= 0) {
            log.error("[$market] 유효하지 않은 진입가격 (${position.entryPrice}) - ABANDONED 처리")
            position.status = "ABANDONED"
            position.exitReason = "INVALID_ENTRY_PRICE"
            position.exitTime = Instant.now()
            position.pnlAmount = 0.0
            position.pnlPercent = 0.0
            tradeRepository.save(position)
            highestPrices.remove(position.id)

            slackNotifier.sendWarning(
                market,
                """
                유효하지 않은 포지션 데이터 감지
                진입가격: ${position.entryPrice}원
                포지션 ID: ${position.id}

                ABANDONED 처리됨. DB 데이터 확인 필요.
                """.trimIndent()
            )
            return
        }

        // 현재가 조회
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull() ?: return
        val currentPrice = ticker.tradePrice.toDouble()
        val entryPrice = position.entryPrice
        val pnlPercent = safePnlPercent(entryPrice, currentPrice)

        // 1. 손절 체크 (저장된 손절 비율 사용, 없으면 기본값)
        val appliedStopLoss = position.appliedStopLossPercent
            ?: kotlin.math.abs(properties.stopLossPercent)
        val stopLossPercent = -appliedStopLoss
        if (pnlPercent <= stopLossPercent) {
            closePosition(position, currentPrice, "STOP_LOSS")
            return
        }

        // 2. 익절 체크 (저장된 익절 비율 사용, 없으면 계산)
        val takeProfitPercent = position.appliedTakeProfitPercent
            ?: if (properties.useDynamicTakeProfit) {
                appliedStopLoss * properties.takeProfitMultiplier
            } else {
                properties.takeProfitPercent
            }
        if (pnlPercent >= takeProfitPercent) {
            closePosition(position, currentPrice, "TAKE_PROFIT")
            return
        }

        // 3. 트레일링 스탑 체크 (익절 도달 이후에만 활성화)
        // NOTE: 익절 도달 후에만 트레일링으로 보호
        if (pnlPercent >= properties.trailingStopTrigger && pnlPercent >= takeProfitPercent) {
            // 익절 도달: 트레일링으로 보호
            if (!position.trailingActive) {
                position.trailingActive = true
                position.highestPrice = currentPrice
                highestPrices[position.id!!] = currentPrice
                tradeRepository.save(position)
                log.info("[$market] 트레일링 스탑 활성화 (익절 도달 후 수익률: ${String.format("%.2f", pnlPercent)}%)")
            } else {
                val highestPrice = highestPrices.getOrDefault(position.id!!, currentPrice)
                if (currentPrice > highestPrice) {
                    highestPrices[position.id!!] = currentPrice
                    position.highestPrice = currentPrice
                    tradeRepository.save(position)
                }

                val trailingStopPrice = highestPrice * (1 - properties.trailingStopOffset / 100)
                if (currentPrice <= trailingStopPrice) {
                    closePosition(position, currentPrice, "TRAILING_PROFIT")
                    return
                }
            }
        }

        // 4. 타임아웃 체크
        val holdingMinutes = ChronoUnit.MINUTES.between(position.entryTime, Instant.now())
        if (holdingMinutes >= properties.positionTimeoutMin) {
            closePosition(position, currentPrice, "TIMEOUT")
            return
        }
    }

    /**
     * 포지션 청산 (PositionCloser 위임)
     */
    private fun closePosition(position: VolumeSurgeTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market

        // PositionCloser에 공통 로직 위임
        positionCloser.executeClose(
            position = position,
            exitPrice = exitPrice,
            reason = reason,
            strategyName = "Volume Surge",
            maxAttempts = TradingConstants.MAX_CLOSE_ATTEMPTS,
            backoffSeconds = TradingConstants.CLOSE_RETRY_BACKOFF_SECONDS,
            updatePosition = { pos, status, price, qty, orderId ->
                pos.status = status
                pos.exitReason = reason
                pos.exitPrice = price
                pos.lastCloseAttempt = Instant.now()
                pos.closeAttemptCount++
                pos.closeOrderId = orderId  // orderId 설정
                if (qty != pos.quantity) {
                    log.info("[$market] 매도 수량 조정: ${pos.quantity} -> $qty")
                    pos.quantity = qty
                }
                tradeRepository.save(pos)
            },
            onComplete = { pos, actualPrice, exitReason, orderResult ->
                // VolumeSurgeEngine 특화 로직
                pos.closeOrderId = orderResult.orderId  // orderId 설정
                finalizeClose(pos, actualPrice, exitReason)
            }
            // onAbandoned는 null (기본 ABANDONED 처리 사용)
        )
    }

    /**
     * 청산 완료 처리 (finalizeClose)
     *
     * CLOSING 상태의 포지션을 CLOSED로 전환하고 손익 계산
     */
    private fun finalizeClose(position: VolumeSurgeTradeEntity, actualExitPrice: Double, reason: String) {
        val market = position.market

        // PnL 계산 (수수료 미포함 - 수수료는 OrderExecutor에서 계산)
        val pnlResult = PnlCalculator.calculateWithoutFee(
            entryPrice = position.entryPrice,
            exitPrice = actualExitPrice,
            quantity = position.quantity
        )

        position.exitPrice = actualExitPrice
        position.exitTime = Instant.now()
        position.exitReason = reason
        position.pnlAmount = pnlResult.pnlAmount
        position.pnlPercent = pnlResult.pnlPercent
        position.status = "CLOSED"

        tradeRepository.save(position)

        // 트레일링 추적 제거
        highestPrices.remove(position.id)

        // 서킷 브레이커 업데이트
        circuitBreaker.recordPnl(pnlResult.pnlAmount)

        // Slack 알림
        val emoji = if (pnlResult.pnlAmount >= 0) "+" else ""
        slackNotifier.sendSystemNotification(
            "Volume Surge 청산",
            """
            마켓: $market
            사유: $reason
            진입가: ${position.entryPrice}원
            청산가: ${actualExitPrice}원
            손익: $emoji${String.format("%.0f", pnlResult.pnlAmount)}원 (${String.format("%.2f", pnlResult.pnlPercent)}%)
            보유시간: ${ChronoUnit.MINUTES.between(position.entryTime, Instant.now())}분
            주문상태: 체결완료
            주문ID: ${position.closeOrderId ?: "N/A"}
            """.trimIndent()
        )

        log.info("[$market] 청산 완료: 손익=${pnlResult.pnlAmount}원 (${pnlResult.pnlPercent}%)")
    }

    /**
     * 거래 가능 여부 체크
     */
    private fun canTrade(): Boolean = circuitBreaker.canTrade()

    /**
     * 경보 처리 완료 마킹
     */
    private fun markAlertProcessed(alert: VolumeSurgeAlertEntity, result: String, reason: String) {
        alert.processed = true
        if (alert.llmFilterResult == null) {
            alert.llmFilterResult = result
        }
        if (alert.llmFilterReason == null) {
            alert.llmFilterReason = reason
        }
        alertRepository.save(alert)
    }

    /**
     * 현재 상태 조회 (API용)
     */
    fun getStatus(): Map<String, Any> {
        val openPositions = tradeRepository.findByStatus("OPEN")
        val cbState = circuitBreaker.getState()
        return mapOf(
            "enabled" to properties.enabled,
            "openPositions" to openPositions.size,
            "consecutiveLosses" to cbState.consecutiveLosses,
            "dailyPnl" to cbState.dailyPnl,
            "canTrade" to canTrade()
        )
    }

    /**
     * 수동 트리거 (테스트용)
     *
     * LLM 필터를 스킵하고 바로 기술적 분석 → 진입 진행.
     * 또는 LLM 필터를 포함해서 전체 흐름 실행.
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param skipLlmFilter true면 LLM 필터 스킵
     * @return 실행 결과
     */
    fun manualTrigger(market: String, skipLlmFilter: Boolean = false): Map<String, Any?> {
        log.info("[$market] 수동 트리거 시작 (skipLlmFilter=$skipLlmFilter)")

        val cbState = circuitBreaker.getState()

        // 서킷 브레이커 체크
        if (!canTrade()) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "서킷 브레이커 발동 (연속손실: ${cbState.consecutiveLosses}, 일일손익: ${cbState.dailyPnl})"
            )
        }

        // 동시 포지션 수 체크
        val openPositions = tradeRepository.countByStatus("OPEN")
        if (openPositions >= properties.maxPositions) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "최대 포지션 도달 ($openPositions/${properties.maxPositions})"
            )
        }

        // 이미 해당 마켓에 열린 포지션이 있는지 확인
        // [엔진 간 충돌 방지] GlobalPositionManager로 모든 엔진의 포지션 확인
        val existingPosition = tradeRepository.findByMarketAndStatus(market, "OPEN")
        if (existingPosition.isNotEmpty()) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "이미 열린 포지션 존재 (id=${existingPosition.first().id})"
            )
        }

        // 다른 엔진(TradingEngine, MemeScalper)에서 포지션 확인
        if (globalPositionManager.hasOpenPosition(market)) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "다른 엔진에서 열린 포지션 존재 - VolumeSurge 진입 차단"
            )
        }

        // 가짜 경보 생성 (수동 트리거용)
        val manualAlert = VolumeSurgeAlertEntity(
            market = market,
            alertType = "MANUAL_TRIGGER",
            detectedAt = Instant.now(),
            processed = false
        )
        val savedAlert = alertRepository.save(manualAlert)

        // 시작 알림
        slackNotifier.sendSystemNotification(
            "Volume Surge 수동 트리거",
            """
            마켓: $market
            LLM 필터: ${if (skipLlmFilter) "스킵" else "사용"}
            경보ID: ${savedAlert.id}
            """.trimIndent()
        )

        // Virtual Thread로 비동기 처리
        Thread.startVirtualThread {
            try {
                // LLM 필터 (옵션)
                val filterResult = if (skipLlmFilter) {
                    log.info("[$market] LLM 필터 스킵 (수동 트리거)")
                    FilterResult(
                        decision = "APPROVED",
                        confidence = 1.0,
                        reason = "수동 트리거 (LLM 스킵)"
                    )
                } else {
                    volumeSurgeFilter.filter(market, savedAlert)
                }

                savedAlert.llmFilterResult = filterResult.decision
                savedAlert.llmFilterReason = filterResult.reason
                savedAlert.llmConfidence = filterResult.confidence
                alertRepository.save(savedAlert)

                if (filterResult.decision != "APPROVED") {
                    log.info("[$market] LLM 필터 거부: ${filterResult.reason}")
                    markAlertProcessed(savedAlert, filterResult.decision, filterResult.reason)

                    slackNotifier.sendWarning(
                        market,
                        """
                        수동 트리거 거부됨
                        사유: ${filterResult.reason}
                        신뢰도: ${filterResult.confidence}
                        """.trimIndent()
                    )
                    return@startVirtualThread
                }

                log.info("[$market] 필터 통과, 기술적 분석 시작")

                // 기술적 분석
                val analysis = volumeSurgeAnalyzer.analyze(market)
                if (analysis == null) {
                    log.warn("[$market] 기술적 분석 실패")
                    markAlertProcessed(savedAlert, "REJECTED", "기술적 분석 실패")

                    slackNotifier.sendWarning(market, "수동 트리거 실패: 기술적 분석 실패")
                    return@startVirtualThread
                }

                // 진입 조건 체크 (수동 트리거는 조건 완화)
                log.info("[$market] 기술적 분석 결과: RSI=${analysis.rsi}, 컨플루언스=${analysis.confluenceScore}")

                // 포지션 진입
                enterPosition(savedAlert, analysis, filterResult)

            } catch (e: Exception) {
                log.error("[$market] 수동 트리거 처리 중 오류: ${e.message}", e)
                markAlertProcessed(savedAlert, "ERROR", e.message ?: "Unknown error")

                slackNotifier.sendWarning(market, "수동 트리거 오류: ${e.message}")
            }
        }

        return mapOf(
            "success" to true,
            "market" to market,
            "alertId" to savedAlert.id,
            "skipLlmFilter" to skipLlmFilter,
            "message" to "트리거 실행 중 (비동기 처리)"
        )
    }

    /**
     * 포지션 수동 청산
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @return 청산 결과
     */
    fun manualClose(market: String): Map<String, Any?> {
        log.info("[$market] 수동 청산 요청")

        val openPositions = tradeRepository.findByMarketAndStatus(market, "OPEN")
        if (openPositions.isEmpty()) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "열린 포지션 없음"
            )
        }

        // 수동 청산 시작 알림
        slackNotifier.sendSystemNotification(
            "Volume Surge 수동 청산 요청",
            """
            마켓: $market
            포지션 수: ${openPositions.size}
            """.trimIndent()
        )

        val results = openPositions.map { position ->
            try {
                // 현재가 조회
                val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
                val exitPrice = ticker?.tradePrice?.toDouble() ?: position.entryPrice

                closePosition(position, exitPrice, "MANUAL")

                mapOf(
                    "positionId" to position.id,
                    "exitPrice" to exitPrice,
                    "pnlAmount" to position.pnlAmount,
                    "pnlPercent" to position.pnlPercent,
                    "success" to true
                )
            } catch (e: Exception) {
                slackNotifier.sendWarning(market, "수동 청산 실패: ${e.message}")
                mapOf(
                    "positionId" to position.id,
                    "success" to false,
                    "error" to e.message
                )
            }
        }

        return mapOf(
            "success" to true,
            "market" to market,
            "closedPositions" to results
        )
    }

    /**
     * 서킷 브레이커 리셋 (테스트용)
     */
    fun resetCircuitBreaker(): Map<String, Any> {
        circuitBreaker.reset()
        val cbState = circuitBreaker.getState()
        return mapOf(
            "success" to true,
            "consecutiveLosses" to cbState.consecutiveLosses,
            "dailyPnl" to cbState.dailyPnl
        )
    }
}

/**
 * VolumeSurge 서킷브레이커 상태 저장소 구현체
 */
private class VolumeSurgeCircuitBreakerStatePersistence(
    private val dailySummaryRepository: VolumeSurgeDailySummaryRepository
) : SimpleCircuitBreakerStatePersistence {

    override fun load(): SimpleCircuitBreakerState? {
        val today = LocalDate.now()
        val todaySummary = dailySummaryRepository.findByDate(today)
        return todaySummary?.let {
            SimpleCircuitBreakerState(
                consecutiveLosses = it.consecutiveLosses,
                dailyPnl = it.totalPnl,
                lastResetDate = it.circuitBreakerUpdatedAt ?: Instant.now()
            )
        }
    }

    override fun save(state: SimpleCircuitBreakerState) {
        try {
            val today = LocalDate.now()
            val summary = dailySummaryRepository.findByDate(today)
                ?: VolumeSurgeDailySummaryEntity(date = today)

            summary.consecutiveLosses = state.consecutiveLosses
            summary.totalPnl = state.dailyPnl
            summary.circuitBreakerUpdatedAt = state.lastResetDate

            dailySummaryRepository.save(summary)
        } catch (e: Exception) {
            // State persistence 실패는 로그만 남기고 무시
        }
    }
}
