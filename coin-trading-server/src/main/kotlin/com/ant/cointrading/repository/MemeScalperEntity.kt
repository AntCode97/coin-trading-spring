package com.ant.cointrading.repository

import com.ant.cointrading.engine.PositionEntity
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

/**
 * Meme Scalper 트레이드 엔티티
 *
 * 초단타 포지션 추적용
 */
@Entity
@Table(
    name = "meme_scalper_trades",
    indexes = [
        Index(name = "idx_ms_trades_market", columnList = "market"),
        Index(name = "idx_ms_trades_status", columnList = "status"),
        Index(name = "idx_ms_trades_created", columnList = "createdAt")
    ]
)
class MemeScalperTradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override var id: Long? = null,

    /** 마켓 코드 (예: KRW-DOGE) */
    @Column(nullable = false, length = 20)
    override var market: String = "",

    /** 진입 가격 */
    @Column(nullable = false)
    override var entryPrice: Double = 0.0,

    /** 청산 가격 */
    @Column
    override var exitPrice: Double? = null,

    /** 수량 */
    @Column(nullable = false)
    override var quantity: Double = 0.0,

    /** 진입 시각 */
    @Column(nullable = false)
    override var entryTime: Instant = Instant.now(),

    /** 청산 시각 */
    @Column
    override var exitTime: Instant? = null,

    /** 청산 사유 (STOP_LOSS/TAKE_PROFIT/TIMEOUT/VOLUME_DROP/IMBALANCE_FLIP/MANUAL) */
    @Column(length = 30)
    var exitReason: String? = null,

    /** 손익 금액 (KRW) */
    @Column
    override var pnlAmount: Double? = null,

    /** 손익률 (%) */
    @Column
    override var pnlPercent: Double? = null,

    // === 진입 시점 데이터 ===

    /** 진입 시점 거래량 스파이크 비율 */
    @Column
    var entryVolumeSpikeRatio: Double? = null,

    /** 진입 시점 가격 스파이크 (%) */
    @Column
    var entryPriceSpikePercent: Double? = null,

    /** 진입 시점 호가창 Imbalance */
    @Column
    var entryImbalance: Double? = null,

    /** 진입 시점 RSI */
    @Column
    var entryRsi: Double? = null,

    /** 진입 시점 MACD 신호 (BULLISH/BEARISH/NEUTRAL) */
    @Column(length = 10)
    var entryMacdSignal: String? = null,

    /** 진입 시점 스프레드 (%) */
    @Column
    var entrySpread: Double? = null,

    /** 피크 가격 (트레일링용) */
    @Column
    var peakPrice: Double? = null,

    /** 트레일링 스탑 활성화 여부 */
    @Column(nullable = false)
    var trailingActive: Boolean = false,

    /** 피크 거래량 (청산 판단용) */
    @Column
    var peakVolume: Double? = null,

    /** 적용된 손절 비율 (%) - ActivePositionManager가 동적 갱신. 0이면 미조정(properties 기본값 사용) */
    @Column(nullable = false)
    var appliedStopLossPercent: Double = 0.0,

    /** 적용된 익절 비율 (%) - ActivePositionManager가 동적 갱신. 0이면 미조정(properties 기본값 사용) */
    @Column(nullable = false)
    var appliedTakeProfitPercent: Double = 0.0,

    // === 상태 관리 ===

    /** 포지션 상태 (OPEN/CLOSING/CLOSED/ABANDONED) */
    @Column(nullable = false, length = 20)
    override var status: String = "OPEN",

    /** 시장 레짐 (BULL_TREND/BEAR_TREND/SIDEWAYS/HIGH_VOLATILITY) */
    @Column(length = 30)
    var regime: String? = null,

    /** 청산 주문 ID */
    @Column(length = 50)
    override var closeOrderId: String? = null,

    /** 마지막 청산 시도 시각 */
    @Column
    override var lastCloseAttempt: Instant? = null,

    /** 청산 시도 횟수 */
    @Column(nullable = false)
    override var closeAttemptCount: Int = 0,

    /** ABANDONED 후 재시도 횟수 */
    @Column(nullable = false)
    var abandonRetryCount: Int = 0,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 수정 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) : PositionEntity {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/**
 * Meme Scalper 일일 통계 엔티티
 */
@Entity
@Table(
    name = "meme_scalper_daily_stats",
    indexes = [
        Index(name = "idx_ms_stats_date", columnList = "date", unique = true)
    ]
)
class MemeScalperDailyStatsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 날짜 */
    @Column(nullable = false)
    var date: LocalDate = LocalDate.now(),

    /** 총 거래 수 */
    @Column(nullable = false)
    var totalTrades: Int = 0,

    /** 승리 거래 수 */
    @Column(nullable = false)
    var winningTrades: Int = 0,

    /** 패배 거래 수 */
    @Column(nullable = false)
    var losingTrades: Int = 0,

    /** 총 손익 (KRW) */
    @Column(nullable = false)
    var totalPnl: Double = 0.0,

    /** 승률 (0.0~1.0) */
    @Column(nullable = false)
    var winRate: Double = 0.0,

    /** 평균 보유 시간 (초) */
    @Column
    var avgHoldingSeconds: Double? = null,

    /** 최대 단일 손실 */
    @Column
    var maxSingleLoss: Double? = null,

    /** 최대 단일 이익 */
    @Column
    var maxSingleProfit: Double? = null,

    // === 서킷 브레이커 상태 (재시작 시 복원용) ===

    /** 연속 손실 횟수 */
    @Column(nullable = false)
    var consecutiveLosses: Int = 0,

    /** 마지막 서킷브레이커 상태 업데이트 시각 */
    @Column
    var circuitBreakerUpdatedAt: Instant? = null,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
)
