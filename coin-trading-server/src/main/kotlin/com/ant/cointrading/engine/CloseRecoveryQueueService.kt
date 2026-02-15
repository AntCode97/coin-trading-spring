package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.repository.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

enum class CloseRecoveryStrategy {
    MEME_SCALPER,
    VOLUME_SURGE,
    DCA
}

/**
 * 청산 복구 큐 워커
 *
 * ABANDONED/실패 포지션을 DB 큐로 모아 별도 스케줄러에서 자동 청산 재시도한다.
 */
@Component
class CloseRecoveryQueueService(
    private val queueRepository: CloseRecoveryQueueRepository,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val bithumbPublicApi: BithumbPublicApi,
    private val orderExecutor: OrderExecutor,
    private val memeScalperTradeRepository: MemeScalperTradeRepository,
    private val volumeSurgeTradeRepository: VolumeSurgeTradeRepository,
    private val dcaPositionRepository: DcaPositionRepository,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(CloseRecoveryQueueService::class.java)
    private val marketLocks = ConcurrentHashMap<String, ReentrantLock>()

    companion object {
        private val ACTIVE_STATUSES = listOf(
            CloseRecoveryTaskStatus.PENDING,
            CloseRecoveryTaskStatus.PROCESSING,
            CloseRecoveryTaskStatus.RETRYING
        )
        private val DUE_STATUSES = listOf(
            CloseRecoveryTaskStatus.PENDING,
            CloseRecoveryTaskStatus.PROCESSING,
            CloseRecoveryTaskStatus.RETRYING
        )
        private const val BASE_BACKOFF_SECONDS = 5L
        private const val MAX_BACKOFF_SECONDS = 300L
    }

    fun enqueue(
        strategy: CloseRecoveryStrategy,
        positionId: Long,
        market: String,
        entryPrice: Double,
        targetQuantity: Double,
        reason: String,
        lastKnownPrice: Double? = null
    ) {
        val safeReason = reason.take(200)
        val existing = queueRepository.findTopByStrategyAndPositionIdAndStatusInOrderByCreatedAtDesc(
            strategy = strategy.name,
            positionId = positionId,
            statuses = ACTIVE_STATUSES
        )

        if (existing != null) {
            existing.reason = safeReason
            existing.entryPrice = if (entryPrice > 0) entryPrice else existing.entryPrice
            if (targetQuantity > 0) {
                existing.targetQuantity = targetQuantity
            }
            if (lastKnownPrice != null && lastKnownPrice > 0) {
                existing.lastKnownPrice = lastKnownPrice
            }
            existing.nextAttemptAt = Instant.now()
            if (existing.status == CloseRecoveryTaskStatus.PROCESSING) {
                existing.status = CloseRecoveryTaskStatus.RETRYING
            }
            queueRepository.save(existing)
            return
        }

        queueRepository.save(
            CloseRecoveryQueueEntity(
                strategy = strategy.name,
                positionId = positionId,
                market = market,
                status = CloseRecoveryTaskStatus.PENDING,
                reason = safeReason,
                entryPrice = entryPrice,
                targetQuantity = targetQuantity,
                lastKnownPrice = lastKnownPrice,
                nextAttemptAt = Instant.now()
            )
        )
        log.info("[$market] 청산 복구 큐 등록: strategy=${strategy.name}, positionId=$positionId, reason=$safeReason")
    }

    @Scheduled(fixedDelayString = "\${trading.close-recovery.polling-ms:3000}")
    fun processQueue() {
        val now = Instant.now()
        val dueTasks = queueRepository.findTop50ByStatusInAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            statuses = DUE_STATUSES,
            now = now
        )

        if (dueTasks.isEmpty()) {
            return
        }

        dueTasks.forEach { task ->
            val taskId = task.id ?: return@forEach
            processSingleTask(taskId)
        }
    }

    private fun processSingleTask(taskId: Long) {
        val task = queueRepository.findById(taskId).orElse(null) ?: return
        if (task.status !in DUE_STATUSES || task.nextAttemptAt.isAfter(Instant.now())) {
            return
        }

        val lock = marketLocks.computeIfAbsent(task.market) { ReentrantLock() }
        if (!lock.tryLock()) {
            return
        }

        try {
            runRecovery(task)
        } catch (e: Exception) {
            retryTask(task, "복구 예외: ${e.message}")
        } finally {
            lock.unlock()
        }
    }

    private fun runRecovery(task: CloseRecoveryQueueEntity) {
        task.status = CloseRecoveryTaskStatus.PROCESSING
        task.lastAttemptAt = Instant.now()
        queueRepository.save(task)

        if (isPositionClosed(task)) {
            completeTask(task, "DB 포지션이 이미 CLOSED 상태")
            return
        }

        val balance = fetchCoinBalance(task.market)
        if (balance == null) {
            retryTask(task, "잔고 조회 실패")
            return
        }

        if (balance <= BigDecimal.ZERO) {
            closePositionWithoutBalance(task)
            completeTask(task, "실제 잔고 없음 - 복구 완료")
            return
        }

        val currentPrice = fetchCurrentPrice(task.market)
            ?: task.lastKnownPrice
            ?: if (task.entryPrice > 0) task.entryPrice else null

        if (currentPrice == null || currentPrice <= 0) {
            retryTask(task, "현재가 조회 실패")
            return
        }

        task.lastKnownPrice = currentPrice

        val sellQuantity = resolveSellQuantity(balance.toDouble(), task.targetQuantity)
        if (sellQuantity <= 0) {
            retryTask(task, "유효한 매도 수량 없음")
            return
        }

        val notional = currentPrice * sellQuantity
        if (notional < TradingConstants.MIN_ORDER_AMOUNT_KRW.toDouble()) {
            closePositionAsDust(task, currentPrice)
            completeTask(task, "최소 주문금액 미달 잔량(dust) - DB 청산 완료")
            return
        }

        val signal = TradingSignal(
            market = task.market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal.valueOf(currentPrice),
            reason = "자동 복구 청산: ${task.reason}",
            strategy = "EXIT_RECOVERY"
        )

        val orderResult = orderExecutor.execute(
            signal = signal,
            positionSize = BigDecimal.valueOf(notional)
        )

        if (!orderResult.success) {
            retryTask(task, "청산 주문 실패: ${orderResult.message}")
            return
        }

        val actualExitPrice = orderResult.price?.toDouble() ?: currentPrice
        val executedQty = orderResult.executedQuantity?.toDouble()
            ?.takeIf { it > 0 }
            ?: sellQuantity

        closePositionByExecution(task, actualExitPrice, executedQty)
        completeTask(task, "자동 청산 완료 (orderId=${orderResult.orderId ?: "N/A"})")
        log.info("[${task.market}] 청산 복구 성공: positionId=${task.positionId}, qty=$executedQty, price=$actualExitPrice")
    }

    private fun retryTask(task: CloseRecoveryQueueEntity, error: String) {
        task.attemptCount++
        task.status = CloseRecoveryTaskStatus.RETRYING
        task.lastError = error.take(500)
        task.nextAttemptAt = Instant.now().plusSeconds(calculateBackoffSeconds(task.attemptCount))
        queueRepository.save(task)

        if (task.attemptCount % 5 == 0) {
            slackNotifier.sendWarning(
                task.market,
                """
                자동 청산 복구 재시도 중
                전략: ${task.strategy}
                포지션ID: ${task.positionId}
                시도 횟수: ${task.attemptCount}
                마지막 오류: ${task.lastError}
                """.trimIndent()
            )
        }
    }

    private fun completeTask(task: CloseRecoveryQueueEntity, message: String) {
        task.status = CloseRecoveryTaskStatus.COMPLETED
        task.completedAt = Instant.now()
        task.lastError = null
        queueRepository.save(task)
        log.info("[${task.market}] 청산 복구 큐 완료: $message")
    }

    private fun calculateBackoffSeconds(attemptCount: Int): Long {
        val shift = (attemptCount - 1).coerceIn(0, 6)
        val backoff = BASE_BACKOFF_SECONDS * (1L shl shift)
        return backoff.coerceAtMost(MAX_BACKOFF_SECONDS)
    }

    private fun fetchCurrentPrice(market: String): Double? {
        return try {
            bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchCoinBalance(market: String): BigDecimal? {
        val symbol = PositionHelper.extractCoinSymbol(market)
        return try {
            val balances = bithumbPrivateApi.getBalances() ?: return null
            val coin = balances.find { it.currency == symbol }
            val available = coin?.balance ?: BigDecimal.ZERO
            val locked = coin?.locked ?: BigDecimal.ZERO
            available + locked
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveSellQuantity(actualBalance: Double, targetQuantity: Double): Double {
        if (actualBalance <= 0) return 0.0
        if (targetQuantity <= 0) return actualBalance
        return minOf(actualBalance, targetQuantity)
    }

    private fun isPositionClosed(task: CloseRecoveryQueueEntity): Boolean {
        return when (task.strategy) {
            CloseRecoveryStrategy.MEME_SCALPER.name ->
                memeScalperTradeRepository.findById(task.positionId).orElse(null)?.status == "CLOSED"

            CloseRecoveryStrategy.VOLUME_SURGE.name ->
                volumeSurgeTradeRepository.findById(task.positionId).orElse(null)?.status == "CLOSED"

            CloseRecoveryStrategy.DCA.name ->
                dcaPositionRepository.findById(task.positionId).orElse(null)?.status == "CLOSED"

            else -> true
        }
    }

    private fun closePositionWithoutBalance(task: CloseRecoveryQueueEntity) {
        when (task.strategy) {
            CloseRecoveryStrategy.MEME_SCALPER.name -> {
                val position = memeScalperTradeRepository.findById(task.positionId).orElse(null) ?: return
                if (position.status == "CLOSED") return
                val exitPrice = task.lastKnownPrice ?: task.entryPrice
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_NO_BALANCE"
                position.exitTime = Instant.now()
                position.exitPrice = exitPrice
                position.pnlAmount = safePnlAmount(position.entryPrice, exitPrice, position.quantity)
                position.pnlPercent = safePnlPercent(position.entryPrice, exitPrice)
                memeScalperTradeRepository.save(position)
            }

            CloseRecoveryStrategy.VOLUME_SURGE.name -> {
                val position = volumeSurgeTradeRepository.findById(task.positionId).orElse(null) ?: return
                if (position.status == "CLOSED") return
                val exitPrice = task.lastKnownPrice ?: task.entryPrice
                val pnlResult = com.ant.cointrading.risk.PnlCalculator.calculateWithoutFee(
                    entryPrice = position.entryPrice,
                    exitPrice = exitPrice,
                    quantity = position.quantity
                )
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_NO_BALANCE"
                position.exitTime = Instant.now()
                position.exitPrice = exitPrice
                position.pnlAmount = pnlResult.pnlAmount
                position.pnlPercent = pnlResult.pnlPercent
                volumeSurgeTradeRepository.save(position)
            }

            CloseRecoveryStrategy.DCA.name -> {
                val position = dcaPositionRepository.findById(task.positionId).orElse(null) ?: return
                if (position.status == "CLOSED") return
                val exitPrice = task.lastKnownPrice ?: task.entryPrice
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_NO_BALANCE"
                position.exitedAt = Instant.now()
                position.exitPrice = exitPrice
                position.realizedPnl = (exitPrice - position.averagePrice) * position.totalQuantity
                position.realizedPnlPercent = safePnlPercent(position.averagePrice, exitPrice)
                dcaPositionRepository.save(position)
            }
        }
    }

    private fun closePositionAsDust(task: CloseRecoveryQueueEntity, exitPrice: Double) {
        closePositionWithoutBalance(task.apply { lastKnownPrice = exitPrice })
        slackNotifier.sendWarning(
            task.market,
            """
            자동 청산 복구: 최소 주문금액 미달 잔량 처리
            전략: ${task.strategy}
            포지션ID: ${task.positionId}
            현재가: ${String.format("%.8f", exitPrice)}
            잔량은 dust로 간주하여 DB를 CLOSED로 정리했습니다.
            """.trimIndent()
        )
    }

    private fun closePositionByExecution(
        task: CloseRecoveryQueueEntity,
        actualExitPrice: Double,
        executedQuantity: Double
    ) {
        when (task.strategy) {
            CloseRecoveryStrategy.MEME_SCALPER.name -> {
                val position = memeScalperTradeRepository.findById(task.positionId).orElse(null) ?: return
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_EXECUTED"
                position.exitTime = Instant.now()
                position.exitPrice = actualExitPrice
                position.pnlAmount = safePnlAmount(position.entryPrice, actualExitPrice, executedQuantity)
                position.pnlPercent = safePnlPercent(position.entryPrice, actualExitPrice)
                memeScalperTradeRepository.save(position)
            }

            CloseRecoveryStrategy.VOLUME_SURGE.name -> {
                val position = volumeSurgeTradeRepository.findById(task.positionId).orElse(null) ?: return
                val pnlResult = com.ant.cointrading.risk.PnlCalculator.calculateWithoutFee(
                    entryPrice = position.entryPrice,
                    exitPrice = actualExitPrice,
                    quantity = executedQuantity
                )
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_EXECUTED"
                position.exitTime = Instant.now()
                position.exitPrice = actualExitPrice
                position.pnlAmount = pnlResult.pnlAmount
                position.pnlPercent = pnlResult.pnlPercent
                volumeSurgeTradeRepository.save(position)
            }

            CloseRecoveryStrategy.DCA.name -> {
                val position = dcaPositionRepository.findById(task.positionId).orElse(null) ?: return
                val pnlAmount = (actualExitPrice - position.averagePrice) * executedQuantity
                position.status = "CLOSED"
                position.exitReason = "RECOVERY_EXECUTED"
                position.exitedAt = Instant.now()
                position.exitPrice = actualExitPrice
                position.realizedPnl = pnlAmount
                position.realizedPnlPercent = safePnlPercent(position.averagePrice, actualExitPrice)
                dcaPositionRepository.save(position)
            }
        }
    }
}
