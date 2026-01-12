package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Volume Surge 전략 설정
 *
 * 퀀트 연구 기반 기본값:
 * - 거래량 비율 300% 이상에서 의미 있는 급등
 * - RSI 70 이하에서 진입 (과매수 회피)
 * - 컨플루언스 60점 이상 (최소 2~3개 지표 일치)
 * - 손절 -2%, 익절 +5% (리스크:리워드 = 1:2.5)
 */
@Component
@ConfigurationProperties(prefix = "volumesurge")
class VolumeSurgeProperties {

    /** 전략 활성화 여부 */
    var enabled: Boolean = false

    /** 경보 폴링 간격 (밀리초) */
    var pollingIntervalMs: Long = 30000

    // === 진입 조건 ===

    /** 최소 거래량 비율 (20일 평균 대비) */
    var minVolumeRatio: Double = 3.0

    /** 최대 RSI (과매수 영역 진입 제한) */
    var maxRsi: Double = 70.0

    /** 최소 컨플루언스 점수 */
    var minConfluenceScore: Int = 60

    /** 최소 시가총액 (KRW) */
    var minMarketCapKrw: Long = 50_000_000_000

    // === 포지션 관리 ===

    /** 1회 포지션 금액 (KRW) */
    var positionSizeKrw: Int = 10000

    /** 최대 동시 포지션 수 */
    var maxPositions: Int = 3

    // === 리스크 관리 ===

    /** 손절 (%) */
    var stopLossPercent: Double = -2.0

    /** 익절 (%) */
    var takeProfitPercent: Double = 5.0

    /** 트레일링 스탑 트리거 (%) - 이 수익률 이상에서 활성화 */
    var trailingStopTrigger: Double = 2.0

    /** 트레일링 스탑 오프셋 (%) - 고점 대비 이 비율 하락 시 청산 */
    var trailingStopOffset: Double = 1.0

    /** 포지션 타임아웃 (분) */
    var positionTimeoutMin: Int = 30

    // === 필터링 ===

    /** 경보 신선도 (분) - 이 시간 이내 경보만 처리 */
    var alertFreshnessMin: Int = 5

    /** 같은 종목 재진입 쿨다운 (분) */
    var cooldownMin: Int = 60

    /** LLM 필터 쿨다운 (분) - LLM 검증 후 이 시간 동안 재호출 안 함 */
    var llmCooldownMin: Int = 240

    // === 서킷 브레이커 ===

    /** 연속 손실 횟수 제한 */
    var maxConsecutiveLosses: Int = 3

    /** 일일 최대 손실 (KRW) */
    var dailyMaxLossKrw: Int = 30000

    // === LLM 설정 ===

    /** LLM 필터 활성화 */
    var llmFilterEnabled: Boolean = true

    /** 회고 크론 표현식 (기본: 매일 새벽 1시) */
    var reflectionCron: String = "0 0 1 * * *"

    override fun toString(): String {
        return """
            VolumeSurgeProperties(
                enabled=$enabled,
                minVolumeRatio=$minVolumeRatio,
                positionSizeKrw=$positionSizeKrw,
                stopLossPercent=$stopLossPercent,
                takeProfitPercent=$takeProfitPercent,
                llmFilterEnabled=$llmFilterEnabled
            )
        """.trimIndent()
    }
}
