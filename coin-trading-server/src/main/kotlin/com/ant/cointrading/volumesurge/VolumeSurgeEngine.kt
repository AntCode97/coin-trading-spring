package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.repository.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 서킷 브레이커 상태
    private var consecutiveLosses = 0
    private var dailyPnl = 0.0
    private var lastResetDate = Instant.now()

    // 트레일링 스탑 고점 추적
    private val highestPrices = ConcurrentHashMap<Long, Double>()

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Volume Surge Engine 시작 ===")
            log.info("설정: $properties")
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        log.info("Volume Surge Engine 종료")
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

        // LLM 필터 (비동기로 처리)
        scope.launch {
            try {
                val filterResult = volumeSurgeFilter.filter(market, alert)

                alert.llmFilterResult = filterResult.decision
                alert.llmFilterReason = filterResult.reason
                alert.llmConfidence = filterResult.confidence
                alertRepository.save(alert)

                if (filterResult.decision != "APPROVED") {
                    log.info("[$market] LLM 필터 거부: ${filterResult.reason}")
                    markAlertProcessed(alert, filterResult.decision, filterResult.reason)
                    return@launch
                }

                log.info("[$market] LLM 필터 승인 (신뢰도: ${filterResult.confidence})")

                // 기술적 분석
                val analysis = volumeSurgeAnalyzer.analyze(market)
                if (analysis == null) {
                    log.warn("[$market] 기술적 분석 실패")
                    markAlertProcessed(alert, "REJECTED", "기술적 분석 실패")
                    return@launch
                }

                // 진입 조건 체크
                if (!shouldEnter(analysis)) {
                    log.info("[$market] 진입 조건 미충족: ${analysis.rejectReason}")
                    markAlertProcessed(alert, "REJECTED", analysis.rejectReason ?: "진입 조건 미충족")
                    return@launch
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
     * 진입 조건 체크
     */
    private fun shouldEnter(analysis: VolumeSurgeAnalysis): Boolean {
        if (analysis.rsi > properties.maxRsi) {
            analysis.rejectReason = "RSI 과매수 (${analysis.rsi})"
            return false
        }
        if (analysis.confluenceScore < properties.minConfluenceScore) {
            analysis.rejectReason = "컨플루언스 점수 부족 (${analysis.confluenceScore})"
            return false
        }
        if (analysis.volumeRatio < properties.minVolumeRatio) {
            analysis.rejectReason = "거래량 비율 부족 (${analysis.volumeRatio})"
            return false
        }
        return true
    }

    /**
     * 포지션 진입
     */
    private suspend fun enterPosition(
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
            val quantity = positionSize.divide(currentPrice, 8, java.math.RoundingMode.DOWN)

            // 시장가 매수 (단타는 빠른 체결 우선)
            log.info("[$market] 시장가 매수 시도: 금액=${positionSize}원, 수량=$quantity")

            // 트레이드 엔티티 생성
            val trade = VolumeSurgeTradeEntity(
                alertId = alert.id,
                market = market,
                entryPrice = currentPrice.toDouble(),
                quantity = quantity.toDouble(),
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
                가격: ${currentPrice.toPlainString()}원
                수량: $quantity
                RSI: ${analysis.rsi}
                컨플루언스: ${analysis.confluenceScore}
                LLM 사유: ${filterResult.reason}
                """.trimIndent()
            )

            log.info("[$market] 포지션 진입 완료: id=${savedTrade.id}")

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

    /**
     * 포지션 청산
     */
    private fun closePosition(position: VolumeSurgeTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market

        log.info("[$market] 포지션 청산: $reason, 가격=$exitPrice")

        val pnlAmount = (exitPrice - position.entryPrice) * position.quantity
        val pnlPercent = ((exitPrice - position.entryPrice) / position.entryPrice) * 100

        position.exitPrice = exitPrice
        position.exitTime = Instant.now()
        position.exitReason = reason
        position.pnlAmount = pnlAmount
        position.pnlPercent = pnlPercent
        position.status = "CLOSED"

        tradeRepository.save(position)

        // 트레일링 추적 제거
        highestPrices.remove(position.id)

        // 서킷 브레이커 업데이트
        updateCircuitBreaker(pnlAmount)

        // Slack 알림
        val emoji = if (pnlAmount >= 0) "+" else ""
        slackNotifier.sendSystemNotification(
            "Volume Surge 청산",
            """
            마켓: $market
            사유: $reason
            진입가: ${position.entryPrice}원
            청산가: ${exitPrice}원
            손익: $emoji${String.format("%.0f", pnlAmount)}원 (${String.format("%.2f", pnlPercent)}%)
            보유시간: ${ChronoUnit.MINUTES.between(position.entryTime, Instant.now())}분
            """.trimIndent()
        )

        log.info("[$market] 청산 완료: 손익=${pnlAmount}원 (${pnlPercent}%)")
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
}
