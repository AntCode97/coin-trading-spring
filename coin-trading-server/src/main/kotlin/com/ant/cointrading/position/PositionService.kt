package com.ant.cointrading.position

import com.ant.cointrading.repository.PositionEntity
import com.ant.cointrading.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 통합 포지션 관리 서비스
 *
 * 모든 전략(DCA, Grid, MeanReversion, VolumeSurge, MemeScalper 등)의 포지션을
 * PositionEntity를 통해 통합 관리.
 *
 * @since 2026-01-28
 */
@Service
class PositionService(
    private val positionRepository: PositionRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ==================== 포지션 생성 ====================

    /**
     * 새 포지션 생성
     */
    @Transactional
    fun createPosition(request: CreatePositionRequest): PositionEntity {
        val position = PositionEntity(
            strategy = request.strategy,
            market = request.market,
            side = request.side,
            targetQuantity = request.targetQuantity,
            averageEntryPrice = request.entryPrice ?: BigDecimal.ZERO,
            stopLossPrice = request.stopLossPrice,
            stopLossPercent = request.stopLossPercent,
            takeProfitPrice = request.takeProfitPrice,
            takeProfitPercent = request.takeProfitPercent,
            trailingActive = request.trailingActive ?: false,
            trailingOffsetPercent = request.trailingOffsetPercent,
            timeoutAt = request.timeoutAt,
            entryRsi = request.entryRsi,
            entryMacdSignal = request.entryMacdSignal,
            entryBollingerPosition = request.entryBollingerPosition,
            entryConfluenceScore = request.entryConfluenceScore,
            entryRegime = request.entryRegime,
            entryConfidence = request.entryConfidence,
            notes = request.notes
        )

        // 진입 주문 ID가 있으면 설정
        if (request.entryOrderId != null) {
            position.entryOrderId = request.entryOrderId
        }

        val saved = positionRepository.save(position)
        log.info("[${saved.strategy}] 포지션 생성: ${saved.market} ${saved.side} 목표=${saved.targetQuantity}")
        return saved
    }

    /**
     * 진입 주문 연결
     */
    @Transactional
    fun linkEntryOrder(positionId: Long, orderId: String): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null
        position.entryOrderId = orderId
        position.updatedBy = "SYSTEM"
        return positionRepository.save(position)
    }

    // ==================== 부분 체결 처리 ====================

    /**
     * 부분 체결 업데이트
     *
     * @param positionId 포지션 ID
     * @param filledQuantity 체결된 수량
     * @param fillPrice 체결 가격
     * @return 업데이트된 포지션
     */
    @Transactional
    fun updatePartialFill(
        positionId: Long,
        filledQuantity: BigDecimal,
        fillPrice: BigDecimal
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        val previousFilled = position.filledQuantity
        val newFilled = previousFilled.add(filledQuantity)

        // 평균 진입 가격 재계산 (VWAP)
        val totalValue = position.averageEntryPrice.multiply(previousFilled)
            .add(fillPrice.multiply(filledQuantity))
        position.averageEntryPrice = totalValue.divide(newFilled, 8, RoundingMode.HALF_UP)

        position.filledQuantity = newFilled
        position.fillCount++

        // 첫 번째 체결 기록
        if (position.firstFillAt == null) {
            position.firstFillAt = Instant.now()
        }

        // 완전 체결 확인
        if (newFilled >= position.targetQuantity) {
            position.filledQuantity = position.targetQuantity  // 초과 방지
            position.fullyFilledAt = Instant.now()
            position.status = when {
                newFilled > BigDecimal.ZERO && newFilled < position.targetQuantity -> {
                    PositionEntity.STATUS_PARTIALLY_FILLED
                }
                else -> {
                    PositionEntity.STATUS_FILLED
                }
            }
        } else if (newFilled > BigDecimal.ZERO) {
            position.status = PositionEntity.STATUS_PARTIALLY_FILLED
        }

        // 총 진입 금액 업데이트
        position.totalEntryValue = position.averageEntryPrice.multiply(position.filledQuantity)

        position.updatedBy = "SYSTEM"
        val saved = positionRepository.save(position)

        log.info("[${saved.strategy}] 부분 체결: ${saved.market} ${previousFilled}->${newFilled}/${saved.targetQuantity} (${saved.averageEntryPrice}원)")
        return saved
    }

    /**
     * 부분 청산 업데이트
     *
     * @param positionId 포지션 ID
     * @param filledQuantity 청산된 수량
     * @param fillPrice 청산 가격
     * @return 업데이트된 포지션
     */
    @Transactional
    fun updatePartialExit(
        positionId: Long,
        filledQuantity: BigDecimal,
        fillPrice: BigDecimal
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        // 평균 청산 가격 재계산
        val previouslyExited = (position.targetQuantity.subtract(position.filledQuantity))
        val totalExitValue = (position.averageExitPrice ?: BigDecimal.ZERO).multiply(previouslyExited)
            .add(fillPrice.multiply(filledQuantity))
        val totalExited = previouslyExited.add(filledQuantity)

        position.averageExitPrice = if (totalExited > BigDecimal.ZERO) {
            totalExitValue.divide(totalExited, 8, RoundingMode.HALF_UP)
        } else {
            fillPrice
        }

        position.filledQuantity = position.filledQuantity.add(filledQuantity)

        // 완전 청산 확인
        if (position.filledQuantity >= position.targetQuantity) {
            position.filledQuantity = position.targetQuantity
            position.status = PositionEntity.STATUS_CLOSED
            position.exitTime = Instant.now()

            // 실현 손익 계산
            val realizedPnl = when (position.side) {
                PositionEntity.SIDE_LONG -> {
                    (position.averageExitPrice ?: BigDecimal.ZERO)
                        .subtract(position.averageEntryPrice)
                        .multiply(position.targetQuantity)
                }
                PositionEntity.SIDE_SHORT -> {
                    (position.averageEntryPrice)
                        .subtract(position.averageExitPrice ?: BigDecimal.ZERO)
                        .multiply(position.targetQuantity)
                }
                else -> BigDecimal.ZERO
            }
            position.realizedPnl = realizedPnl

            val realizedPnlPercent = if (position.totalEntryValue > BigDecimal.ZERO) {
                (realizedPnl.divide(position.totalEntryValue, 8, java.math.RoundingMode.HALF_UP))
                    .multiply(BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }
            position.realizedPnlPercent = realizedPnlPercent
        } else if (position.filledQuantity > BigDecimal.ZERO) {
            position.status = PositionEntity.STATUS_CLOSING
        }

        position.totalExitValue = totalExitValue
        position.updatedBy = "SYSTEM"
        val saved = positionRepository.save(position)

        log.info("[${saved.strategy}] 부분 청산: ${saved.market} ${filledQuantity}/${saved.targetQuantity} (${saved.averageExitPrice}원)")
        return saved
    }

    // ==================== 포지션 조회 ====================

    /**
     * 열린 포지션 목록 조회
     */
    fun getOpenPositions(): List<PositionEntity> {
        return positionRepository.findByStatus(PositionEntity.STATUS_OPEN)
    }

    /**
     * 특정 마켓의 열린 포지션 조회
     */
    fun getOpenPositionsByMarket(market: String): List<PositionEntity> {
        return positionRepository.findByMarketAndStatus(market, PositionEntity.STATUS_OPEN)
    }

    /**
     * 특정 전략의 열린 포지션 조회
     */
    fun getOpenPositionsByStrategy(strategy: String): List<PositionEntity> {
        return positionRepository.findByStrategyAndStatus(strategy, PositionEntity.STATUS_OPEN)
    }

    /**
     * 포지션 ID로 조회
     */
    fun getPosition(positionId: Long): PositionEntity? {
        return positionRepository.findById(positionId).orElse(null)
    }

    /**
     * 진입 주문 ID로 포지션 조회
     */
    fun getPositionByEntryOrderId(entryOrderId: String): PositionEntity? {
        return positionRepository.findByEntryOrderId(entryOrderId)
    }

    /**
     * 청산 주문 ID로 포지션 조회
     */
    fun getPositionByExitOrderId(exitOrderId: String): PositionEntity? {
        return positionRepository.findByExitOrderId(exitOrderId)
    }

    // ==================== 포지션 청산 ====================

    /**
     * 포지션 청산
     *
     * @param positionId 포지션 ID
     * @param exitReason 청산 사유
     * @param exitPrice 청산 가격 (선택)
     * @return 청산된 포지션
     */
    @Transactional
    fun closePosition(
        positionId: Long,
        exitReason: String,
        exitPrice: BigDecimal? = null
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        position.exitReason = exitReason
        position.exitTime = Instant.now()
        position.status = PositionEntity.STATUS_CLOSED
        position.updatedBy = "SYSTEM"

        if (exitPrice != null) {
            position.averageExitPrice = exitPrice
        }

        val saved = positionRepository.save(position)
        log.info("[${saved.strategy}] 포지션 청산: ${saved.market} ${exitReason}")
        return saved
    }

    /**
     * 포지션 실패 처리
     */
    @Transactional
    fun failPosition(
        positionId: Long,
        reason: String
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        position.status = PositionEntity.STATUS_FAILED
        position.exitReason = "FAILED:$reason"
        position.exitTime = Instant.now()
        position.updatedBy = "SYSTEM"

        val saved = positionRepository.save(position)
        log.warn("[${saved.strategy}] 포지션 실패: ${saved.market} $reason")
        return saved
    }

    // ==================== 리스크 관리 ====================

    /**
     * 손절 가격 재계산 (ATR 기반 동적 손절)
     */
    @Transactional
    fun updateStopLoss(
        positionId: Long,
        stopLossPrice: BigDecimal,
        stopLossPercent: BigDecimal? = null
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        position.stopLossPrice = stopLossPrice
        if (stopLossPercent != null) {
            position.stopLossPercent = java.math.BigDecimal.valueOf(stopLossPercent.toDouble())
        }
        position.updatedBy = "SYSTEM"

        return positionRepository.save(position)
    }

    /**
     * 트레일링 스탑 활성화
     */
    @Transactional
    fun activateTrailingStop(
        positionId: Long,
        offsetPercent: BigDecimal,
        currentPrice: BigDecimal
    ): PositionEntity? {
        val position = positionRepository.findById(positionId).orElse(null) ?: return null

        position.trailingActive = true
        position.trailingOffsetPercent = offsetPercent
        position.trailingPeakPrice = currentPrice
        position.updatedBy = "SYSTEM"

        // 초기 트레일링 스탑 가격 설정
        position.updateTrailingStop(currentPrice)

        return positionRepository.save(position)
    }

    /**
     * 모든 열린 포지션의 손절/익절 체크 및 업데이트
     */
    @Transactional
    fun checkAndClosePositions(
        currentPrices: Map<String, BigDecimal>
    ): List<PositionEntity> {
        val closedPositions = mutableListOf<PositionEntity>()
        val openPositions = getOpenPositions()

        for (position in openPositions) {
            val currentPrice = currentPrices[position.market] ?: continue

            // 타임아웃 체크
            if (position.isTimeout()) {
                val closed = closePosition(position.id!!, PositionEntity.EXIT_REASON_TIMEOUT)
                if (closed != null) closedPositions.add(closed)
                continue
            }

            // 손절 체크
            if (position.isStopLossTriggered(currentPrice)) {
                val closed = closePosition(position.id!!, PositionEntity.EXIT_REASON_STOP_LOSS, currentPrice)
                if (closed != null) closedPositions.add(closed)
                continue
            }

            // 익절 체크
            if (position.isTakeProfitTriggered(currentPrice)) {
                val closed = closePosition(position.id!!, PositionEntity.EXIT_REASON_TAKE_PROFIT, currentPrice)
                if (closed != null) closedPositions.add(closed)
                continue
            }

            // 트레일링 스탑 업데이트
            if (position.trailingActive) {
                position.updateTrailingStop(currentPrice)
                positionRepository.save(position)
            }

            // 미실현 손익 업데이트
            val unrealizedPnlPercent = position.calculateUnrealizedPnlPercent(currentPrice)
            position.unrealizedPnlPercent = unrealizedPnlPercent
            val unrealizedPnl = position.totalEntryValue.multiply(
                unrealizedPnlPercent.divide(java.math.BigDecimal(100), 8, java.math.RoundingMode.HALF_UP)
            )
            position.unrealizedPnl = unrealizedPnl
            positionRepository.save(position)
        }

        return closedPositions
    }

    // ==================== 포지션 정보 ====================

    /**
     * 전체 포지션 통계
     */
    fun getPositionSummary(): PositionSummary {
        val allPositions = positionRepository.findAll()

        val openPositions = allPositions.filter { it.status == PositionEntity.STATUS_OPEN }
        val closedPositions = allPositions.filter { it.status == PositionEntity.STATUS_CLOSED }

        val totalRealizedPnl = closedPositions.sumOf { it.realizedPnl ?: BigDecimal.ZERO }
        val winningTrades = closedPositions.count { (it.realizedPnlPercent ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losingTrades = closedPositions.count { (it.realizedPnlPercent ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        val winRate = if (closedPositions.isNotEmpty()) {
            winningTrades.toDouble() / closedPositions.size
        } else {
            0.0
        }

        return PositionSummary(
            totalPositions = allPositions.size,
            openPositions = openPositions.size,
            closedPositions = closedPositions.size,
            totalRealizedPnl = totalRealizedPnl,
            winningTrades = winningTrades,
            losingTrades = losingTrades,
            winRate = winRate
        )
    }
}

/**
 * 포지션 생성 요청
 */
data class CreatePositionRequest(
    val strategy: String,
    val market: String,
    val side: String = PositionEntity.SIDE_LONG,
    val targetQuantity: BigDecimal,
    val entryPrice: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
    val stopLossPercent: BigDecimal? = null,
    val takeProfitPrice: BigDecimal? = null,
    val takeProfitPercent: BigDecimal? = null,
    val trailingActive: Boolean? = null,
    val trailingOffsetPercent: BigDecimal? = null,
    val timeoutAt: Instant? = null,
    val entryOrderId: String? = null,
    val entryRsi: Double? = null,
    val entryMacdSignal: String? = null,
    val entryBollingerPosition: String? = null,
    val entryConfluenceScore: Int? = null,
    val entryRegime: String? = null,
    val entryConfidence: BigDecimal? = null,
    val notes: String? = null
)

/**
 * 포지션 통계
 */
data class PositionSummary(
    val totalPositions: Int,
    val openPositions: Int,
    val closedPositions: Int,
    val totalRealizedPnl: BigDecimal,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double
)
