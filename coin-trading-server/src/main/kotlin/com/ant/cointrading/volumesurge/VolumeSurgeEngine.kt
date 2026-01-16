package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.repository.*
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
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
    private val volumeSurgeFilter: VolumeSurgeFilter,
    private val volumeSurgeAnalyzer: VolumeSurgeAnalyzer,
    private val orderExecutor: OrderExecutor,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 서킷 브레이커 상태
    private var consecutiveLosses = 0
    private var dailyPnl = 0.0
    private var lastResetDate = Instant.now()

    // 트레일링 스탑 고점 추적
    private val highestPrices = ConcurrentHashMap<Long, Double>()

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Volume Surge Engine 시작 (Virtual Thread 모드) ===")
            log.info("설정: $properties")

            // 열린 포지션 복원 (트레일링 스탑 고점 등)
            restoreOpenPositions()
        }
    }

    /**
     * 서버 재시작 시 열린 포지션 상태 복원
     *
     * - 트레일링 스탑 고점을 메모리 맵에 로드
     * - 서킷 브레이커는 보수적으로 리셋 (재시작 후 거래 가능)
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
        // RSI 극단적 과매수만 거부 (80 → 허용 범위 확대)
        val effectiveMaxRsi = 80.0
        if (analysis.rsi > effectiveMaxRsi) {
            analysis.rejectReason = "RSI 극단적 과매수 (${String.format("%.1f", analysis.rsi)} > $effectiveMaxRsi)"
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
     */
    private fun enterPosition(
        alert: VolumeSurgeAlertEntity,
        analysis: VolumeSurgeAnalysis,
        filterResult: FilterResult
    ) {
        val market = alert.market

        try {
            // 현재가 조회
            val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
            if (ticker == null) {
                log.error("[$market] 현재가 조회 실패")
                markAlertProcessed(alert, "ERROR", "현재가 조회 실패")
                return
            }

            val currentPrice = ticker.tradePrice
            val positionSize = BigDecimal(properties.positionSizeKrw)

            // 실제 매수 주문 실행
            log.info("[$market] 시장가 매수 시도: 금액=${positionSize}원")

            val buySignal = TradingSignal(
                market = market,
                action = SignalAction.BUY,
                confidence = filterResult.confidence * 100,
                price = currentPrice,
                reason = "Volume Surge 진입: ${filterResult.reason}",
                strategy = "VOLUME_SURGE"
            )

            val orderResult = orderExecutor.execute(buySignal, positionSize)

            if (!orderResult.success) {
                log.error("[$market] 매수 주문 실패: ${orderResult.message}")
                markAlertProcessed(alert, "ERROR", "매수 주문 실패: ${orderResult.message}")
                return
            }

            // 체결가 처리: null 또는 0이면 currentPrice 사용
            val executedPrice = orderResult.price?.takeIf { it > BigDecimal.ZERO } ?: currentPrice
            val executedQuantity = orderResult.executedQuantity?.takeIf { it > BigDecimal.ZERO }
                ?: orderResult.quantity?.takeIf { it > BigDecimal.ZERO }
                ?: BigDecimal.ZERO

            // 체결가 또는 수량이 0이면 진입 실패 처리 (0으로 나누기 방지)
            if (executedPrice <= BigDecimal.ZERO || executedQuantity <= BigDecimal.ZERO) {
                log.error("[$market] 유효하지 않은 체결 데이터: 가격=${executedPrice}, 수량=${executedQuantity}")
                log.error("[$market] orderResult: price=${orderResult.price}, executedQty=${orderResult.executedQuantity}, qty=${orderResult.quantity}")
                markAlertProcessed(alert, "ERROR", "유효하지 않은 체결 데이터")

                slackNotifier.sendWarning(
                    market,
                    """
                    체결 데이터 오류로 진입 실패
                    orderResult.price: ${orderResult.price}
                    orderResult.executedQuantity: ${orderResult.executedQuantity}
                    orderResult.quantity: ${orderResult.quantity}
                    currentPrice: $currentPrice

                    주문은 실행되었을 수 있음. 빗썸에서 확인 필요.
                    """.trimIndent()
                )
                return
            }

            log.info("[$market] 매수 체결: 가격=${executedPrice}, 수량=${executedQuantity}")

            // 트레이드 엔티티 생성
            val trade = VolumeSurgeTradeEntity(
                alertId = alert.id,
                market = market,
                entryPrice = executedPrice.toDouble(),
                quantity = executedQuantity.toDouble(),
                entryTime = Instant.now(),
                entryRsi = analysis.rsi,
                entryMacdSignal = analysis.macdSignal,
                entryBollingerPosition = analysis.bollingerPosition,
                entryVolumeRatio = analysis.volumeRatio,
                confluenceScore = analysis.confluenceScore,
                llmEntryReason = filterResult.reason,
                llmConfidence = filterResult.confidence,
                status = "OPEN"
            )

            val savedTrade = tradeRepository.save(trade)

            markAlertProcessed(alert, "APPROVED", "포지션 진입 완료")

            // Slack 알림
            slackNotifier.sendSystemNotification(
                "Volume Surge 진입",
                """
                마켓: $market
                체결가: ${executedPrice.toPlainString()}원
                수량: $executedQuantity
                RSI: ${analysis.rsi}
                컨플루언스: ${analysis.confluenceScore}
                LLM 사유: ${filterResult.reason}
                주문ID: ${orderResult.orderId ?: "N/A"}
                """.trimIndent()
            )

            log.info("[$market] 포지션 진입 완료: id=${savedTrade.id}, orderId=${orderResult.orderId}")

        } catch (e: Exception) {
            log.error("[$market] 포지션 진입 실패: ${e.message}", e)
            markAlertProcessed(alert, "ERROR", "주문 실행 실패: ${e.message}")
        }
    }

    /**
     * 포지션 모니터링 (1초마다)
     */
    @Scheduled(fixedDelay = 1000)
    fun monitorPositions() {
        if (!properties.enabled) return

        val openPositions = tradeRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) return

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
        val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

        // 1. 손절 체크 (-2%)
        if (pnlPercent <= properties.stopLossPercent) {
            closePosition(position, currentPrice, "STOP_LOSS")
            return
        }

        // 2. 익절 체크 (+5%)
        if (pnlPercent >= properties.takeProfitPercent) {
            closePosition(position, currentPrice, "TAKE_PROFIT")
            return
        }

        // 3. 트레일링 스탑 체크
        if (pnlPercent >= properties.trailingStopTrigger) {
            if (!position.trailingActive) {
                position.trailingActive = true
                position.highestPrice = currentPrice
                highestPrices[position.id!!] = currentPrice
                tradeRepository.save(position)
                log.info("[$market] 트레일링 스탑 활성화 (수익률: ${String.format("%.2f", pnlPercent)}%)")
            } else {
                val highestPrice = highestPrices.getOrDefault(position.id!!, currentPrice)
                if (currentPrice > highestPrice) {
                    highestPrices[position.id!!] = currentPrice
                    position.highestPrice = currentPrice
                    tradeRepository.save(position)
                }

                val trailingStopPrice = highestPrice * (1 - properties.trailingStopOffset / 100)
                if (currentPrice <= trailingStopPrice) {
                    closePosition(position, currentPrice, "TRAILING")
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

    companion object {
        // 빗썸 최소 주문 금액 (KRW) - 수수료(0.04%) 고려
        private val MIN_ORDER_AMOUNT_KRW = BigDecimal("5100")
    }

    /**
     * 포지션 청산
     *
     * 퀀트 최적화: 최소 금액 미달 시에도 일단 API 호출 시도
     * - 빗썸이 허용하면 청산 완료
     * - 에러 발생 시 ABANDONED 처리
     * - 손절은 무조건 시도 (손실 최소화)
     */
    private fun closePosition(position: VolumeSurgeTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market

        log.info("[$market] 포지션 청산: $reason, 가격=$exitPrice")

        // 금액 = 수량 * 현재가 (OrderExecutor는 금액 기준으로 동작)
        val positionAmount = BigDecimal(position.quantity * exitPrice)

        // 최소 금액 미달 경고 (하지만 손절은 시도)
        val isBelowMinAmount = positionAmount < MIN_ORDER_AMOUNT_KRW
        val isStopLoss = reason == "STOP_LOSS"

        if (isBelowMinAmount) {
            log.warn("[$market] 최소 주문 금액 미달: ${positionAmount.toPlainString()}원 < ${MIN_ORDER_AMOUNT_KRW}원")

            // 손절이 아닌 경우에만 ABANDONED 처리 (익절, 트레일링, 타임아웃)
            // 손절은 무조건 시도 (손실 확대 방지)
            if (!isStopLoss) {
                log.warn("[$market] 손절이 아니므로 ABANDONED 처리 (무한루프 방지)")
                handleAbandonedPosition(position, exitPrice, "ABANDONED_MIN_AMOUNT")
                return
            }

            log.info("[$market] 손절 시도 - 최소 금액 미달이지만 API 호출 시도")
        }

        log.info("[$market] 매도 금액: ${positionAmount.toPlainString()}원 (수량: ${position.quantity})")

        // 실제 매도 주문 실행
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "Volume Surge 청산: $reason",
            strategy = "VOLUME_SURGE"
        )

        val orderResult = orderExecutor.execute(sellSignal, positionAmount)

        // 주문 실패 처리
        if (!orderResult.success) {
            val errorMessage = orderResult.message ?: ""

            // 잔고 부족 에러: 코인이 없으므로 ABANDONED 처리 (무한루프 방지)
            if (errorMessage.contains("insufficient_funds") ||
                errorMessage.contains("주문가능한 금액") ||
                errorMessage.contains("부족")
            ) {
                log.error("[$market] 잔고 부족으로 청산 불가 - ABANDONED 처리")
                handleAbandonedPosition(position, exitPrice, "ABANDONED_NO_BALANCE")
                return
            }

            // 최소 금액 에러: ABANDONED 처리
            if (errorMessage.contains("최소") || errorMessage.contains("minimum")) {
                log.error("[$market] 최소 금액 미달로 청산 불가 - ABANDONED 처리")
                handleAbandonedPosition(position, exitPrice, "ABANDONED_MIN_AMOUNT_API")
                return
            }

            // 다른 에러: 로그만 남기고 다음 사이클에서 재시도
            log.error("[$market] 매도 주문 실패: ${orderResult.message}, 다음 사이클에서 재시도")
            return
        }

        val actualExitPrice = orderResult.price?.toDouble() ?: exitPrice

        // Infinity/NaN 방지
        val pnlAmount = if (position.entryPrice > 0 && position.quantity > 0) {
            (actualExitPrice - position.entryPrice) * position.quantity
        } else {
            0.0
        }
        val pnlPercent = if (position.entryPrice > 0) {
            ((actualExitPrice - position.entryPrice) / position.entryPrice) * 100
        } else {
            0.0
        }

        // NaN/Infinity 최종 검사
        val safePnlAmount = if (pnlAmount.isNaN() || pnlAmount.isInfinite()) 0.0 else pnlAmount
        val safePnlPercent = if (pnlPercent.isNaN() || pnlPercent.isInfinite()) 0.0 else pnlPercent

        position.exitPrice = actualExitPrice
        position.exitTime = Instant.now()
        position.exitReason = reason
        position.pnlAmount = safePnlAmount
        position.pnlPercent = safePnlPercent
        position.status = "CLOSED"

        tradeRepository.save(position)

        // 트레일링 추적 제거
        highestPrices.remove(position.id)

        // 서킷 브레이커 업데이트
        updateCircuitBreaker(pnlAmount)

        // Slack 알림
        val emoji = if (pnlAmount >= 0) "+" else ""
        val orderStatus = if (orderResult.success) "체결완료" else "주문실패"
        slackNotifier.sendSystemNotification(
            "Volume Surge 청산",
            """
            마켓: $market
            사유: $reason
            진입가: ${position.entryPrice}원
            청산가: ${actualExitPrice}원
            손익: $emoji${String.format("%.0f", pnlAmount)}원 (${String.format("%.2f", pnlPercent)}%)
            보유시간: ${ChronoUnit.MINUTES.between(position.entryTime, Instant.now())}분
            주문상태: $orderStatus
            주문ID: ${orderResult.orderId ?: "N/A"}
            """.trimIndent()
        )

        log.info("[$market] 청산 완료: 손익=${pnlAmount}원 (${pnlPercent}%), orderId=${orderResult.orderId}")
    }

    /**
     * ABANDONED 포지션 처리 헬퍼
     */
    private fun handleAbandonedPosition(position: VolumeSurgeTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market
        val positionAmount = BigDecimal(position.quantity * exitPrice)

        position.exitPrice = exitPrice
        position.exitTime = Instant.now()
        position.exitReason = reason
        position.pnlAmount = 0.0  // 실제 청산 안 됨
        position.pnlPercent = 0.0
        position.status = "ABANDONED"

        tradeRepository.save(position)
        highestPrices.remove(position.id)

        // Slack 경고 알림
        slackNotifier.sendWarning(
            market,
            """
            포지션 청산 불가 ($reason)
            금액: ${positionAmount.toPlainString()}원 (최소 5000원)
            수량: ${position.quantity}
            진입가: ${position.entryPrice}원
            현재가: ${exitPrice}원

            해당 포지션은 ABANDONED 상태로 변경됨.
            수동으로 빗썸에서 확인/처리 필요.
            """.trimIndent()
        )

        log.info("[$market] 포지션 ABANDONED 처리 완료 ($reason)")
    }

    /**
     * 서킷 브레이커 업데이트
     */
    private fun updateCircuitBreaker(pnl: Double) {
        // 일일 리셋 체크
        val now = Instant.now()
        if (ChronoUnit.DAYS.between(lastResetDate, now) >= 1) {
            dailyPnl = 0.0
            consecutiveLosses = 0
            lastResetDate = now
        }

        dailyPnl += pnl

        if (pnl < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }
    }

    /**
     * 거래 가능 여부 체크
     */
    private fun canTrade(): Boolean {
        if (consecutiveLosses >= properties.maxConsecutiveLosses) {
            log.warn("연속 손실 ${consecutiveLosses}회 - 거래 중지")
            return false
        }
        if (dailyPnl <= -properties.dailyMaxLossKrw) {
            log.warn("일일 손실 ${dailyPnl}원 - 거래 중지")
            return false
        }
        return true
    }

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
        return mapOf(
            "enabled" to properties.enabled,
            "openPositions" to openPositions.size,
            "consecutiveLosses" to consecutiveLosses,
            "dailyPnl" to dailyPnl,
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

        // 서킷 브레이커 체크
        if (!canTrade()) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "서킷 브레이커 발동 (연속손실: $consecutiveLosses, 일일손익: $dailyPnl)"
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
        val existingPosition = tradeRepository.findByMarketAndStatus(market, "OPEN")
        if (existingPosition.isNotEmpty()) {
            return mapOf(
                "success" to false,
                "market" to market,
                "error" to "이미 열린 포지션 존재 (id=${existingPosition.first().id})"
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
        consecutiveLosses = 0
        dailyPnl = 0.0
        lastResetDate = Instant.now()
        log.info("서킷 브레이커 리셋")
        return mapOf(
            "success" to true,
            "consecutiveLosses" to consecutiveLosses,
            "dailyPnl" to dailyPnl
        )
    }
}
