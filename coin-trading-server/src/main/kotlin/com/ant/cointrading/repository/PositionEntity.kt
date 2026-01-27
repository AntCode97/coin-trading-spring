package com.ant.cointrading.repository

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 통합 포지션 엔티티
 *
 * 모든 전략(DCA, Grid, MeanReversion, VolumeSurge, MemeScalper 등)의 포지션을 통합 관리.
 * 부분 체결을 추적하고 포지션별로 개별 손절/익절/트레일링을 관리.
 *
 * @since 2026-01-28
 */
@Entity
@Table(
    name = "positions",
    indexes = [
        Index(name = "idx_positions_market", columnList = "market"),
        Index(name = "idx_positions_strategy", columnList = "strategy"),
        Index(name = "idx_positions_status", columnList = "status"),
        Index(name = "idx_positions_created", columnList = "createdAt"),
        Index(name = "idx_positions_updated", columnList = "updatedAt"),
        Index(name = "idx_positions_side", columnList = "side")
    ]
)
class PositionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // ==================== 기본 정보 ====================

    /** 거래 전략 (DCA/GRID/MEAN_REVERSION/ORDER_BOOK_IMBALANCE/VOLUME_SURGE/MEME_SCALPER) */
    @Column(nullable = false, length = 30)
    var strategy: String = "",

    /** 마켓 코드 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 포지션 방향 (LONG/SHORT) */
    @Column(nullable = false, length = 10)
    var side: String = "LONG",

    /** 포지션 상태 (OPEN/PARTIALLY_FILLED/FILLED/CLOSING/CLOSED/FAILED) */
    @Column(nullable = false, length = 20)
    var status: String = "OPEN",

    // ==================== 주문 정보 ====================

    /** 진입 주문 ID (부분 체결 추적용) */
    @Column(length = 50)
    var entryOrderId: String? = null,

    /** 청산 주문 ID (부분 체결 추적용) */
    @Column(length = 50)
    var exitOrderId: String? = null,

    /** 목표 수량 */
    @Column(nullable = false)
    var targetQuantity: BigDecimal = BigDecimal.ZERO,

    /** 현재 체결된 수량 */
    @Column(nullable = false)
    var filledQuantity: BigDecimal = BigDecimal.ZERO,

    /** 평균 진입 가격 (KRW) */
    @Column(nullable = false)
    var averageEntryPrice: BigDecimal = BigDecimal.ZERO,

    /** 평균 청산 가격 (KRW) */
    @Column
    var averageExitPrice: BigDecimal? = null,

    /** 총 진입 금액 (KRW) */
    @Column(nullable = false)
    var totalEntryValue: BigDecimal = BigDecimal.ZERO,

    /** 총 청산 금액 (KRW) */
    @Column
    var totalExitValue: BigDecimal? = null,

    // ==================== 리스크 관리 ====================

    /** 손절 가격 (KRW) */
    @Column
    var stopLossPrice: BigDecimal? = null,

    /** 손절 비율 (%) */
    @Column
    var stopLossPercent: BigDecimal? = null,

    /** 익절 가격 (KRW) */
    @Column
    var takeProfitPrice: BigDecimal? = null,

    /** 익절 비율 (%) */
    @Column
    var takeProfitPercent: BigDecimal? = null,

    /** 트레일링 스탑 활성화 여부 */
    @Column(nullable = false)
    var trailingActive: Boolean = false,

    /** 트레일링 최고가 (LONG) / 최저가 (SHORT) */
    @Column
    var trailingPeakPrice: BigDecimal? = null,

    /** 트레일링 offset 비율 (%) */
    @Column
    var trailingOffsetPercent: BigDecimal? = null,

    /** 타임아웃 시각 */
    @Column
    var timeoutAt: Instant? = null,

    // ==================== 손익 정보 ====================

    /** 미실현 손익 (KRW) */
    @Column
    var unrealizedPnl: BigDecimal? = null,

    /** 미실현 손익률 (%) */
    @Column
    var unrealizedPnlPercent: BigDecimal? = null,

    /** 실현 손익 (KRW) */
    @Column
    var realizedPnl: BigDecimal? = null,

    /** 실현 손익률 (%) */
    @Column
    var realizedPnlPercent: BigDecimal? = null,

    // ==================== 진입/청산 분석 데이터 ====================

    /** 진입 시각 */
    @Column(nullable = false)
    var entryTime: Instant = Instant.now(),

    /** 청산 시각 */
    @Column
    var exitTime: Instant? = null,

    /** 청산 사유 (STOP_LOSS/TAKE_PROFIT/TRAILING/TIMEOUT/MANUAL/SIGNAL_REVERSAL) */
    @Column(length = 30)
    var exitReason: String? = null,

    /** 진입 시점 RSI */
    @Column
    var entryRsi: Double? = null,

    /** 진입 시점 MACD 신호 */
    @Column(length = 10)
    var entryMacdSignal: String? = null,

    /** 진입 시점 볼린저밴드 위치 */
    @Column(length = 10)
    var entryBollingerPosition: String? = null,

    /** 진입 시점 컨플루언스 점수 */
    @Column
    var entryConfluenceScore: Int? = null,

    /** 진입 시점 ATR */
    @Column
    var entryAtr: BigDecimal? = null,

    /** 진입 시점 ATR 비율 (%) */
    @Column
    var entryAtrPercent: BigDecimal? = null,

    /** 적용된 손절 비율 (%) */
    @Column
    var appliedStopLossPercent: BigDecimal? = null,

    /** 적용된 익절 비율 (%) */
    @Column
    var appliedTakeProfitPercent: BigDecimal? = null,

    /** 진입 시점 시장 레짐 */
    @Column(length = 30)
    var entryRegime: String? = null,

    /** 진입 시점 신뢰도 */
    @Column
    var entryConfidence: BigDecimal? = null,

    // ==================== 청산 시도 정보 ====================

    /** 마지막 청산 시도 시각 */
    @Column
    var lastCloseAttemptAt: Instant? = null,

    /** 청산 시도 횟수 */
    @Column(nullable = false)
    var closeAttemptCount: Int = 0,

    // ==================== 주문 실행 추적 ====================

    /** 첫 번째 부분 체결 시각 */
    @Column
    var firstFillAt: Instant? = null,

    /** 완전 체결 시각 */
    @Column
    var fullyFilledAt: Instant? = null,

    /** 총 체결 횟수 */
    @Column(nullable = false)
    var fillCount: Int = 0,

    // ==================== 생성/수정 정보 ====================

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 수정 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    /** 생성자 (시스템/사용자) */
    @Column(length = 20)
    var createdBy: String = "SYSTEM",

    /** 수정자 (시스템/사용자) */
    @Column(length = 20)
    var updatedBy: String = "SYSTEM",

    /** 메모 */
    @Column(columnDefinition = "TEXT")
    var notes: String? = null
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * 진입 완료 여부
     */
    fun isEntryComplete(): Boolean = filledQuantity >= targetQuantity

    /**
     * 청산 완료 여부
     */
    fun isExitComplete(): Boolean = status == "CLOSED" || status == "FAILED"

    /**
     * 미실현 손익률 계산
     */
    fun calculateUnrealizedPnlPercent(currentPrice: BigDecimal): BigDecimal {
        if (averageEntryPrice == BigDecimal.ZERO) return BigDecimal.ZERO
        val pnlPercent = when (side) {
            "LONG" -> ((currentPrice - averageEntryPrice)
                .divide(averageEntryPrice, 8, java.math.RoundingMode.HALF_UP))
                .multiply(java.math.BigDecimal(100))
            "SHORT" -> ((averageEntryPrice - currentPrice)
                .divide(averageEntryPrice, 8, java.math.RoundingMode.HALF_UP))
                .multiply(java.math.BigDecimal(100))
            else -> BigDecimal.ZERO
        }
        return pnlPercent
    }

    /**
     * 손절 도달 여부 확인
     */
    fun isStopLossTriggered(currentPrice: BigDecimal): Boolean {
        if (stopLossPrice == null) return false
        return when (side) {
            "LONG" -> currentPrice <= stopLossPrice
            "SHORT" -> currentPrice >= stopLossPrice
            else -> false
        }
    }

    /**
     * 익절 도달 여부 확인
     */
    fun isTakeProfitTriggered(currentPrice: BigDecimal): Boolean {
        if (takeProfitPrice == null) return false
        return when (side) {
            "LONG" -> currentPrice >= takeProfitPrice
            "SHORT" -> currentPrice <= takeProfitPrice
            else -> false
        }
    }

    /**
     * 트레일링 스탑 업데이트
     */
    fun updateTrailingStop(currentPrice: BigDecimal) {
        if (!trailingActive) return

        when (side) {
            "LONG" -> {
                if (currentPrice > (trailingPeakPrice ?: BigDecimal.ZERO)) {
                    trailingPeakPrice = currentPrice
                    val offset = trailingOffsetPercent
                    if (offset != null) {
                        val offsetMultiplier = java.math.BigDecimal.ONE.subtract(
                            offset.divide(java.math.BigDecimal(100), 8, java.math.RoundingMode.HALF_UP)
                        )
                        stopLossPrice = currentPrice.multiply(offsetMultiplier)
                    }
                }
            }
            "SHORT" -> {
                val currentPeak = trailingPeakPrice
                if (currentPeak == null || currentPrice < currentPeak) {
                    trailingPeakPrice = currentPrice
                    val offset = trailingOffsetPercent
                    if (offset != null) {
                        val offsetMultiplier = java.math.BigDecimal.ONE.add(
                            offset.divide(java.math.BigDecimal(100), 8, java.math.RoundingMode.HALF_UP)
                        )
                        stopLossPrice = currentPrice.multiply(offsetMultiplier)
                    }
                }
            }
        }
    }

    /**
     * 타임아웃 확인
     */
    fun isTimeout(): Boolean {
        return timeoutAt != null && Instant.now().isAfter(timeoutAt)
    }

    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_PARTIALLY_FILLED = "PARTIALLY_FILLED"
        const val STATUS_FILLED = "FILLED"
        const val STATUS_CLOSING = "CLOSING"
        const val STATUS_CLOSED = "CLOSED"
        const val STATUS_FAILED = "FAILED"

        const val SIDE_LONG = "LONG"
        const val SIDE_SHORT = "SHORT"

        const val EXIT_REASON_STOP_LOSS = "STOP_LOSS"
        const val EXIT_REASON_TAKE_PROFIT = "TAKE_PROFIT"
        const val EXIT_REASON_TRAILING = "TRAILING"
        const val EXIT_REASON_TIMEOUT = "TIMEOUT"
        const val EXIT_REASON_MANUAL = "MANUAL"
        const val EXIT_REASON_SIGNAL_REVERSAL = "SIGNAL_REVERSAL"
    }
}

/**
 * 통합 포지션 Repository
 */
interface PositionRepository : org.springframework.data.jpa.repository.JpaRepository<PositionEntity, Long> {
    /** 열린 포지션 조회 */
    fun findByStatus(status: String): List<PositionEntity>

    /** 마켓별 열린 포지션 조회 */
    fun findByMarketAndStatus(market: String, status: String): List<PositionEntity>

    /** 전략별 열린 포지션 조회 */
    fun findByStrategyAndStatus(strategy: String, status: String): List<PositionEntity>

    /** 전략 + 마켓별 열린 포지션 조회 */
    fun findByStrategyAndMarketAndStatus(
        strategy: String,
        market: String,
        status: String
    ): List<PositionEntity>

    /** 타임아웃 예정 포지션 조회 */
    fun findByStatusAndTimeoutAtBefore(
        status: String,
        timeoutAt: Instant
    ): List<PositionEntity>

    /** 진입 주문 ID로 조회 */
    fun findByEntryOrderId(entryOrderId: String): PositionEntity?

    /** 청산 주문 ID로 조회 */
    fun findByExitOrderId(exitOrderId: String): PositionEntity?

    /** 생성일 기준 정렬 조회 */
    fun findAllByOrderByCreatedAtDesc(): List<PositionEntity>

    /** 생성일 기준 정렬 + 상태 필터 */
    fun findByStatusOrderByCreatedAtDesc(status: String): List<PositionEntity>

    /** 특정 전략의 최근 포지션 */
    fun findTop10ByStrategyOrderByCreatedAtDesc(strategy: String): List<PositionEntity>
}
