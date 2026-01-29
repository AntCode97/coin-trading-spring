package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.PositionEntity
import com.ant.cointrading.repository.safePnlPercent
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 포지션 관리 엔진 기본 클래스
 *
 * 모든 전략 엔진의 공통 로직을 제공:
 * - 포지션 모니터링 (손절/익절/트레일링/타임아웃)
 * - 청산 처리 (중복 주문 방지, 잔고 확인)
 * - 서킷 브레이커 관리
 * - 일일 통계 관리
 */
abstract class BasePositionEngine(
    protected val bithumbPublicApi: BithumbPublicApi,
    protected val bithumbPrivateApi: BithumbPrivateApi,
    protected val orderExecutor: OrderExecutor,
    protected val slackNotifier: SlackNotifier,
    protected val globalPositionManager: GlobalPositionManager,
    protected val regimeDetector: RegimeDetector
) {
    protected val log = LoggerFactory.getLogger(this.javaClass)

    // 서킷 브레이커 상태
    @Volatile protected var consecutiveLosses = 0
    @Volatile protected var dailyPnl = 0.0
    @Volatile protected var lastResetDate: Instant = Instant.now()

    // 트레일링 스탑 고점 추적
    protected val highestPrices = ConcurrentHashMap<Long, Double>()

    // 전략별 설정
    protected abstract val strategyName: String
    protected abstract val stopLossPercent: Double
    protected abstract val takeProfitPercent: Double
    protected abstract val trailingStopTrigger: Double
    protected abstract val trailingStopOffset: Double
    protected abstract val positionTimeoutMin: Int
    protected abstract val closeRetryBackoffSeconds: Long
    protected abstract val maxCloseAttempts: Int

    /**
     * 포지션 모니터링 (손절/익절/트레일링/타임아웃)
     */
    protected fun monitorSinglePosition(position: PositionEntity) {
        val currentPrice = getCurrentPrice(position.market) ?: return
        val entryPrice = position.averageEntryPrice.toDouble()
        val pnlPercent = safePnlPercent(entryPrice, currentPrice)

        // 1. 손절 체크
        if (pnlPercent <= stopLossPercent) {
            executeClose(position, currentPrice, PositionEntity.EXIT_REASON_STOP_LOSS)
            return
        }

        // 2. 익절 체크
        if (pnlPercent >= takeProfitPercent) {
            executeClose(position, currentPrice, PositionEntity.EXIT_REASON_TAKE_PROFIT)
            return
        }

        // 3. 트레일링 스탑 체크
        if (pnlPercent >= trailingStopTrigger) {
            handleTrailingStop(position, currentPrice, pnlPercent)
        }

        // 4. 타임아웃 체크
        if (position.isTimeout()) {
            executeClose(position, currentPrice, PositionEntity.EXIT_REASON_TIMEOUT)
        }
    }

    /**
     * 트레일링 스탑 처리
     */
    private fun handleTrailingStop(position: PositionEntity, currentPrice: Double, pnlPercent: Double) {
        val positionId = position.id ?: return

        if (!position.trailingActive) {
            position.trailingActive = true
            position.trailingPeakPrice = BigDecimal(currentPrice)
            highestPrices[positionId] = currentPrice
            log.info("[${position.market}] 트레일링 스탑 활성화 (수익률: ${String.format("%.2f", pnlPercent)}%)")
            savePosition(position)
        } else {
            val highestPrice = highestPrices.getOrDefault(positionId, currentPrice)
            if (currentPrice > highestPrice) {
                highestPrices[positionId] = currentPrice
                position.trailingPeakPrice = BigDecimal(currentPrice)
                savePosition(position)
            }

            val trailingStopPrice = highestPrice * (1 - trailingStopOffset / 100)
            if (currentPrice <= trailingStopPrice) {
                executeClose(position, currentPrice, PositionEntity.EXIT_REASON_TRAILING)
            }
        }
    }

    /**
     * 포지션 청산 실행
     *
     * 중복 주문 방지:
     * 1. CLOSING 상태 체크
     * 2. 백오프 시간 내 재시도 방지
     * 3. 최대 시도 횟수 초과 시 ABANDONED
     * 4. 실제 잔고 확인 후 매도
     */
    protected fun executeClose(position: PositionEntity, exitPrice: Double, reason: String) {
        val market = position.market

        // 중복 청산 방지
        if (!canClosePosition(position)) {
            return
        }

        // 최대 시도 횟수 체크
        if (position.closeAttemptCount >= maxCloseAttempts) {
            log.error("[$market] 청산 시도 ${maxCloseAttempts}회 초과, FAILED 처리")
            position.status = PositionEntity.STATUS_FAILED
            position.exitReason = "MAX_ATTEMPTS"
            position.averageExitPrice = BigDecimal(exitPrice)
            position.exitTime = Instant.now()
            savePosition(position)
            return
        }

        log.info("[$market] 포지션 청산 시도 #${position.closeAttemptCount + 1}: $reason, 가격=$exitPrice")

        // 실제 잔고 확인
        val coinSymbol = PositionHelper.extractCoinSymbol(market)
        val actualBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, DB 수량 사용: ${e.message}")
            position.filledQuantity
        }

        // 실제 잔고 없으면 이미 청산됨
        if (actualBalance <= BigDecimal.ZERO) {
            log.warn("[$market] 실제 잔고 없음 - FAILED 처리")
            position.status = PositionEntity.STATUS_FAILED
            position.exitReason = "NO_BALANCE"
            position.averageExitPrice = BigDecimal(exitPrice)
            position.exitTime = Instant.now()
            savePosition(position)
            return
        }

        // 매도 수량 결정
        val sellQuantity = actualBalance.coerceAtMost(position.filledQuantity)
        val positionAmount = sellQuantity.multiply(BigDecimal(exitPrice))

        // 최소 금액 미달 체크
        if (positionAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 최소 주문 금액 미달 - FAILED 처리")
            position.status = PositionEntity.STATUS_FAILED
            position.exitReason = "MIN_AMOUNT"
            position.averageExitPrice = BigDecimal(exitPrice)
            position.exitTime = Instant.now()
            savePosition(position)
            return
        }

        // 상태 변경 및 저장
        position.status = PositionEntity.STATUS_CLOSING
        position.exitReason = reason
        position.averageExitPrice = BigDecimal(exitPrice)
        position.lastCloseAttemptAt = Instant.now()
        position.closeAttemptCount++
        if (sellQuantity < position.filledQuantity) {
            log.info("[$market] 매도 수량 조정: ${position.filledQuantity} -> $sellQuantity")
            position.filledQuantity = sellQuantity
        }
        savePosition(position)

        // 주문 실행
        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "$strategyName 청산: $reason",
            strategy = strategyName
        )

        val orderResult = orderExecutor.execute(sellSignal, positionAmount)

        if (orderResult.success) {
            val actualPrice = orderResult.price?.toDouble() ?: exitPrice
            if (orderResult.executedQuantity != null && orderResult.executedQuantity!! > BigDecimal.ZERO) {
                finalizeClose(position, actualPrice, reason)
            } else {
                position.exitOrderId = orderResult.orderId
                savePosition(position)
            }
        } else {
            val errorMessage = orderResult.message ?: ""

            when {
                errorMessage.contains("insufficient", ignoreCase = true) ||
                errorMessage.contains("부족") ||
                errorMessage.contains("최소") -> {
                    position.status = PositionEntity.STATUS_FAILED
                    position.exitReason = "API_ERROR"
                    savePosition(position)
                }
                else -> {
                    position.status = PositionEntity.STATUS_OPEN
                    position.exitOrderId = null
                    savePosition(position)
                }
            }
        }
    }

    /**
     * 청산 완료 처리
     */
    protected fun finalizeClose(position: PositionEntity, actualPrice: Double, reason: String) {
        val entryPrice = position.averageEntryPrice.toDouble()
        val quantity = position.filledQuantity.toDouble()

        val pnlAmount = (actualPrice - entryPrice) * quantity
        val pnlPercent = safePnlPercent(entryPrice, actualPrice)

        position.status = PositionEntity.STATUS_CLOSED
        position.averageExitPrice = BigDecimal(actualPrice)
        position.totalExitValue = BigDecimal(actualPrice * quantity)
        position.realizedPnl = BigDecimal(pnlAmount)
        position.realizedPnlPercent = BigDecimal(pnlPercent)
        position.exitTime = Instant.now()
        savePosition(position)

        // 서킷 브레이커 업데이트
        recordPnl(pnlAmount)

        log.info("""
            [${position.market}] 포지션 청산 완료
            사유: $reason
            진입: $entryPrice → 청산: $actualPrice
            PnL: ${String.format("%.0f", pnlAmount)}원 (${String.format("%.2f", pnlPercent)}%)
        """.trimIndent())
    }

    /**
     * 일일 리셋 (자정 기준)
     */
    protected fun resetDailyIfNeeded() {
        val today = LocalDate.now()
        val lastDate = lastResetDate.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()

        if (today != lastDate) {
            consecutiveLosses = 0
            dailyPnl = 0.0
            lastResetDate = Instant.now()
            log.info("=== $strategyName 일일 통계 리셋 ===")
        }
    }

    /**
     * 서킷 브레이커 체크
     */
    protected fun isCircuitBreakerOpen(): Boolean {
        return consecutiveLosses >= TradingConstants.DEFAULT_MAX_CONSECUTIVE_LOSSES ||
            dailyPnl <= -TradingConstants.DEFAULT_DAILY_MAX_LOSS_KRW
    }

    /**
     * PnL 기록 (서킷 브레이커 업데이트)
     */
    protected fun recordPnl(pnl: Double) {
        if (pnl < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }
        dailyPnl += pnl

        log.info("$strategyName PnL: ${String.format("%.0f", pnl)}원 (연속손실: $consecutiveLosses, 일일누적: ${String.format("%.0f", dailyPnl)})")
    }

    // ========== 추상 메서드 (전략별 구현) ==========

    protected abstract fun savePosition(position: PositionEntity)

    // ========== 유틸리티 ==========

    protected fun getCurrentPrice(market: String): Double? {
        return bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
    }

    protected fun canClosePosition(position: PositionEntity): Boolean {
        if (position.status == PositionEntity.STATUS_CLOSING) {
            val lastAttempt = position.lastCloseAttemptAt
            if (lastAttempt != null) {
                val elapsed = ChronoUnit.SECONDS.between(lastAttempt, Instant.now())
                if (elapsed < closeRetryBackoffSeconds) {
                    return false
                }
            }
            return true
        }
        return position.status == PositionEntity.STATUS_OPEN
    }
}
