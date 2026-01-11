package com.ant.cointrading.repository

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 미체결 주문 상태
 */
enum class PendingOrderStatus {
    PENDING,           // 대기 중 (체결 확인 필요)
    PARTIALLY_FILLED,  // 부분 체결
    FILLED,            // 완전 체결
    CANCELLED,         // 취소됨
    EXPIRED,           // 타임아웃으로 만료
    REPLACED,          // 새 주문으로 대체됨
    FAILED             // 처리 실패
}

/**
 * 미체결 주문 취소 사유
 */
enum class CancelReason {
    TIMEOUT,           // 타임아웃
    PRICE_MOVED,       // 가격 불리하게 이동
    SPREAD_WIDENED,    // 스프레드 급등
    VOLATILITY_SPIKE,  // 변동성 급등
    MANUAL,            // 수동 취소
    STRATEGY_CHANGE,   // 전략 신호 변경
    MARKET_CLOSE       // 시장 상황 악화
}

/**
 * 미체결 주문 엔티티
 *
 * 20년차 퀀트의 교훈:
 * 1. 모든 미체결 주문은 반드시 추적해야 한다
 * 2. 주문 제출 시점의 시장 상황을 기록해야 한다
 * 3. 왜 취소했는지 기록해야 다음에 개선할 수 있다
 * 4. 부분 체결은 항상 발생한다 - 별도 추적 필수
 */
@Entity
@Table(name = "pending_orders", indexes = [
    Index(name = "idx_pending_orders_status", columnList = "status"),
    Index(name = "idx_pending_orders_market", columnList = "market"),
    Index(name = "idx_pending_orders_created_at", columnList = "createdAt")
])
class PendingOrderEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 거래소 주문 ID */
    @Column(nullable = false, unique = true, length = 64)
    var orderId: String = "",

    /** 거래 마켓 (예: BTC_KRW) */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 거래 방향 (BUY/SELL) */
    @Column(nullable = false, length = 10)
    var side: String = "",

    /** 주문 유형 (LIMIT/MARKET) */
    @Column(nullable = false, length = 10)
    var orderType: String = "",

    /** 주문 가격 (지정가) */
    @Column(nullable = false, precision = 20, scale = 8)
    var orderPrice: BigDecimal = BigDecimal.ZERO,

    /** 주문 수량 */
    @Column(nullable = false, precision = 20, scale = 8)
    var orderQuantity: BigDecimal = BigDecimal.ZERO,

    /** 주문 금액 (KRW) */
    @Column(nullable = false, precision = 20, scale = 2)
    var orderAmountKrw: BigDecimal = BigDecimal.ZERO,

    /** 체결된 수량 */
    @Column(nullable = false, precision = 20, scale = 8)
    var filledQuantity: BigDecimal = BigDecimal.ZERO,

    /** 평균 체결 가격 */
    @Column(precision = 20, scale = 8)
    var avgFilledPrice: BigDecimal? = null,

    /** 현재 상태 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PendingOrderStatus = PendingOrderStatus.PENDING,

    /** 취소 사유 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var cancelReason: CancelReason? = null,

    /** 전략명 */
    @Column(nullable = false, length = 30)
    var strategy: String = "",

    /** 신호 신뢰도 */
    @Column(nullable = false)
    var signalConfidence: Double = 0.0,

    /** 신호 사유 */
    @Column(length = 500)
    var signalReason: String? = null,

    // === 주문 시점 시장 상황 (분석용) ===

    /** 주문 시점 중간가 */
    @Column(precision = 20, scale = 8)
    var snapshotMidPrice: BigDecimal? = null,

    /** 주문 시점 스프레드 (%) */
    @Column
    var snapshotSpread: Double? = null,

    /** 주문 시점 변동성 (1분, %) */
    @Column
    var snapshotVolatility: Double? = null,

    /** 주문 시점 호가 불균형 */
    @Column
    var snapshotImbalance: Double? = null,

    // === 타이밍 ===

    /** 주문 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 마지막 확인 시각 */
    @Column(nullable = false)
    var lastCheckedAt: Instant = Instant.now(),

    /** 만료 예정 시각 (이 시간이 지나면 취소 고려) */
    @Column(nullable = false)
    var expiresAt: Instant = Instant.now().plusSeconds(30),

    /** 상태 변경 시각 */
    @Column
    var statusChangedAt: Instant? = null,

    // === 체결 후 분석용 ===

    /** 슬리피지 (%) */
    @Column
    var slippage: Double? = null,

    /** 체결까지 걸린 시간 (ms) */
    @Column
    var fillDurationMs: Long? = null,

    /** 체결 시점 중간가 (슬리피지 계산용) */
    @Column(precision = 20, scale = 8)
    var fillMidPrice: BigDecimal? = null,

    /** 확인 횟수 */
    @Column(nullable = false)
    var checkCount: Int = 0,

    /** 후속 주문 ID (대체 주문 시) */
    @Column(length = 64)
    var replacedByOrderId: String? = null,

    /** 메모 */
    @Column(length = 500)
    var note: String? = null
) {
    /**
     * 체결률 계산
     */
    fun fillRate(): Double {
        return if (orderQuantity > BigDecimal.ZERO) {
            filledQuantity.divide(orderQuantity, 4, java.math.RoundingMode.HALF_UP).toDouble()
        } else 0.0
    }

    /**
     * 부분 체결 여부
     */
    fun isPartiallyFilled(): Boolean {
        return filledQuantity > BigDecimal.ZERO && filledQuantity < orderQuantity
    }

    /**
     * 미체결 수량
     */
    fun remainingQuantity(): BigDecimal {
        return orderQuantity.subtract(filledQuantity).max(BigDecimal.ZERO)
    }

    /**
     * 타임아웃 여부
     */
    fun isExpired(): Boolean {
        return Instant.now().isAfter(expiresAt)
    }

    /**
     * 가격 이탈률 계산 (현재가 기준)
     */
    fun priceDeviation(currentPrice: BigDecimal): Double {
        return if (orderPrice > BigDecimal.ZERO) {
            currentPrice.subtract(orderPrice)
                .divide(orderPrice, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()
        } else 0.0
    }
}
