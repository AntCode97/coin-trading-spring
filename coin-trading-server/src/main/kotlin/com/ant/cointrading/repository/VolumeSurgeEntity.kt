package com.ant.cointrading.repository

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

/**
 * 거래량 급등 경보 엔티티
 */
@Entity
@Table(
    name = "volume_surge_alerts",
    indexes = [
        Index(name = "idx_vs_alerts_market", columnList = "market"),
        Index(name = "idx_vs_alerts_detected", columnList = "detectedAt"),
        Index(name = "idx_vs_alerts_processed", columnList = "processed")
    ]
)
class VolumeSurgeAlertEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 마켓 코드 (예: KRW-BTC) */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 경보 유형 (TRADING_VOLUME_SUDDEN_FLUCTUATION 등) */
    @Column(nullable = false, length = 50)
    var alertType: String = "",

    /** 거래량 비율 (20일 평균 대비) */
    @Column
    var volumeRatio: Double? = null,

    /** 경보 감지 시각 */
    @Column(nullable = false)
    var detectedAt: Instant = Instant.now(),

    /** LLM 필터 결과 (APPROVED/REJECTED/SKIPPED) */
    @Column(length = 20)
    var llmFilterResult: String? = null,

    /** LLM 필터 판단 사유 */
    @Column(columnDefinition = "TEXT")
    var llmFilterReason: String? = null,

    /** LLM 필터 신뢰도 (0.0~1.0) */
    @Column
    var llmConfidence: Double? = null,

    /** 처리 완료 여부 */
    @Column(nullable = false)
    var processed: Boolean = false,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
)

/**
 * 거래량 급등 트레이드 엔티티 (학습 케이스)
 */
@Entity
@Table(
    name = "volume_surge_trades",
    indexes = [
        Index(name = "idx_vs_trades_market", columnList = "market"),
        Index(name = "idx_vs_trades_status", columnList = "status"),
        Index(name = "idx_vs_trades_created", columnList = "createdAt"),
        Index(name = "idx_vs_trades_alert", columnList = "alertId")
    ]
)
class VolumeSurgeTradeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 관련 경보 ID (FK) */
    @Column
    var alertId: Long? = null,

    /** 마켓 코드 */
    @Column(nullable = false, length = 20)
    var market: String = "",

    /** 진입 가격 */
    @Column(nullable = false)
    var entryPrice: Double = 0.0,

    /** 청산 가격 */
    @Column
    var exitPrice: Double? = null,

    /** 수량 */
    @Column(nullable = false)
    var quantity: Double = 0.0,

    /** 진입 시각 */
    @Column(nullable = false)
    var entryTime: Instant = Instant.now(),

    /** 청산 시각 */
    @Column
    var exitTime: Instant? = null,

    /** 청산 사유 (STOP_LOSS/TAKE_PROFIT/TRAILING/TIMEOUT/MANUAL) */
    @Column(length = 30)
    var exitReason: String? = null,

    /** 손익 금액 (KRW) */
    @Column
    var pnlAmount: Double? = null,

    /** 손익률 (%) */
    @Column
    var pnlPercent: Double? = null,

    // === 진입 시점 분석 데이터 (회고용) ===

    /** 진입 시점 RSI */
    @Column
    var entryRsi: Double? = null,

    /** 진입 시점 MACD 신호 (BULLISH/BEARISH/NEUTRAL) */
    @Column(length = 10)
    var entryMacdSignal: String? = null,

    /** 진입 시점 볼린저밴드 위치 (LOWER/MIDDLE/UPPER) */
    @Column(length = 10)
    var entryBollingerPosition: String? = null,

    /** 진입 시점 거래량 비율 */
    @Column
    var entryVolumeRatio: Double? = null,

    /** 컨플루언스 점수 (0-100) */
    @Column
    var confluenceScore: Int? = null,

    // === ATR 기반 동적 손절 ===

    /** 진입 시점 ATR */
    @Column
    var entryAtr: Double? = null,

    /** 진입 시점 ATR 비율 (%) */
    @Column
    var entryAtrPercent: Double? = null,

    /** 적용된 손절 비율 (%) - 동적 또는 고정 */
    @Column
    var appliedStopLossPercent: Double? = null,

    /** 적용된 익절 비율 (%) - 동적 또는 고정 */
    @Column
    var appliedTakeProfitPercent: Double? = null,

    /** 손절 방식 (ATR_DYNAMIC / FIXED) */
    @Column(length = 20)
    var stopLossMethod: String? = null,

    // === LLM 판단 기록 ===

    /** LLM 진입 판단 사유 */
    @Column(columnDefinition = "TEXT")
    var llmEntryReason: String? = null,

    /** LLM 진입 신뢰도 */
    @Column
    var llmConfidence: Double? = null,

    // === 회고 결과 ===

    /** 회고 노트 */
    @Column(columnDefinition = "TEXT")
    var reflectionNotes: String? = null,

    /** 학습된 교훈 */
    @Column(columnDefinition = "TEXT")
    var lessonLearned: String? = null,

    // === 트레일링 스탑 관련 ===

    /** 트레일링 활성화 여부 */
    @Column
    var trailingActive: Boolean = false,

    /** 최고가 (트레일링용) */
    @Column
    var highestPrice: Double? = null,

    // === 상태 ===

    /** 포지션 상태 (OPEN/CLOSING/CLOSED/ABANDONED) */
    @Column(nullable = false, length = 20)
    var status: String = "OPEN",

    /** 시장 레짐 (BULL_TREND/BEAR_TREND/SIDEWAYS/HIGH_VOLATILITY) */
    @Column(length = 30)
    var regime: String? = null,

    /** 청산 주문 ID (미체결 청산 추적용) */
    @Column(length = 50)
    var closeOrderId: String? = null,

    /** 마지막 청산 시도 시각 (재시도 백오프용) */
    @Column
    var lastCloseAttempt: Instant? = null,

    /** 청산 시도 횟수 */
    @Column(nullable = false)
    var closeAttemptCount: Int = 0,

    /** 생성 시각 */
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    /** 수정 시각 */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}

/**
 * 거래량 급등 전략 일일 요약 엔티티
 */
@Entity
@Table(
    name = "volume_surge_daily_summary",
    indexes = [
        Index(name = "idx_vs_summary_date", columnList = "date", unique = true)
    ]
)
class VolumeSurgeDailySummaryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 날짜 */
    @Column(nullable = false)
    var date: LocalDate = LocalDate.now(),

    /** 총 경보 수 */
    @Column(nullable = false)
    var totalAlerts: Int = 0,

    /** 승인된 경보 수 */
    @Column(nullable = false)
    var approvedAlerts: Int = 0,

    /** 총 트레이드 수 */
    @Column(nullable = false)
    var totalTrades: Int = 0,

    /** 승리 트레이드 수 */
    @Column(nullable = false)
    var winningTrades: Int = 0,

    /** 패배 트레이드 수 */
    @Column(nullable = false)
    var losingTrades: Int = 0,

    /** 총 손익 (KRW) */
    @Column(nullable = false)
    var totalPnl: Double = 0.0,

    /** 승률 (0.0~1.0) */
    @Column(nullable = false)
    var winRate: Double = 0.0,

    /** 평균 보유 시간 (분) */
    @Column
    var avgHoldingMinutes: Double? = null,

    // === LLM 회고 결과 ===

    /** 회고 요약 */
    @Column(columnDefinition = "TEXT")
    var reflectionSummary: String? = null,

    /** 파라미터 변경 기록 (JSON) */
    @Column(columnDefinition = "TEXT")
    var parameterChanges: String? = null,

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
