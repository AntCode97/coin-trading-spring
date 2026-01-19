package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Funding Rate Arbitrage 전략 설정
 *
 * Phase 1 (모니터링): 펀딩 비율 추적 및 알림
 * Phase 2 (수동 실행): 알림 후 수동 진입
 * Phase 3 (반자동): 현물 자동, 선물 수동
 * Phase 4 (자동화): 완전 자동 실행
 *
 * 퀀트 리스크 관리:
 * - 델타 중립 유지 (현물 Long = 선물 Short)
 * - 최대 자본 30%만 사용
 * - 1x 레버리지만 사용 (청산 위험 최소화)
 */
@Component
@ConfigurationProperties(prefix = "funding")
class FundingArbitrageProperties {

    /** 전략 활성화 여부 */
    var enabled: Boolean = false

    /** 자동 거래 활성화 (false = 모니터링만) */
    var autoTradingEnabled: Boolean = false

    // === 모니터링 설정 ===

    /** 펀딩 비율 모니터링 간격 (밀리초) */
    var monitoringIntervalMs: Long = 60000  // 1분

    /** 펀딩 비율 히스토리 저장 간격 (분) */
    var historySaveIntervalMin: Int = 480   // 8시간 (펀딩 주기)

    // === 진입 조건 ===

    /** 최소 연환산 수익률 (%) */
    var minAnnualizedRate: Double = 15.0

    /** 펀딩까지 최대 대기 시간 (분) - 이 시간 이내면 진입 */
    var maxMinutesUntilFunding: Int = 120   // 2시간

    /** 펀딩 비율 안정성 기준 (최근 10회 평균 대비 변동률) */
    var maxFundingRateDeviation: Double = 0.5  // 50%

    // === 포지션 관리 ===

    /** 최대 자본 비율 (%) */
    var maxCapitalRatio: Double = 30.0

    /** 최대 단일 포지션 (KRW) */
    var maxSinglePositionKrw: Long = 1_000_000  // 100만원

    /** 최소 포지션 (KRW) */
    var minPositionKrw: Long = 50_000  // 5만원

    /** 최대 동시 포지션 수 */
    var maxPositions: Int = 3

    // === 리스크 관리 ===

    /** 최대 레버리지 */
    var maxLeverage: Double = 1.0  // 1x만 사용

    /** 최대 델타 노출 (%) - 이 이상 불균형 시 리밸런싱 */
    var maxDeltaExposure: Double = 5.0

    /** 슬리피지 버퍼 (%) */
    var slippageBuffer: Double = 0.2

    /** 마진 비율 경고 임계값 (%) */
    var marginWarningThreshold: Double = 50.0

    /** 마진 비율 청산 임계값 (%) */
    var marginLiquidationThreshold: Double = 30.0

    // === 청산 조건 ===

    /** 펀딩 수령 후 유지 시간 (시간) */
    var minHoldingPeriodHours: Int = 24  // 3회 펀딩 (8h * 3)

    /** 최대 유지 시간 (시간) */
    var maxHoldingPeriodHours: Int = 72  // 9회 펀딩

    /** 펀딩 비율 반전 청산 임계값 (%) */
    var fundingRateReversalThreshold: Double = 0.5  // 연환산 0.5% 미만

    // === 알림 설정 ===

    /** 높은 수익률 알림 임계값 (%) */
    var highRateAlertThreshold: Double = 30.0  // 30% APY 이상

    /** 포지션 상태 알림 간격 (시간) */
    var positionStatusAlertIntervalHours: Int = 8

    // === 모니터링 대상 ===

    /** 모니터링할 심볼 목록 */
    var symbols: List<String> = listOf(
        "BTCUSDT", "ETHUSDT", "XRPUSDT", "SOLUSDT",
        "ADAUSDT", "DOGEUSDT", "AVAXUSDT", "LINKUSDT"
    )

    override fun toString(): String {
        return """
            FundingArbitrageProperties(
                enabled=$enabled,
                autoTradingEnabled=$autoTradingEnabled,
                minAnnualizedRate=$minAnnualizedRate,
                maxCapitalRatio=$maxCapitalRatio%,
                maxSinglePositionKrw=${maxSinglePositionKrw / 10000}만원,
                symbols=${symbols.size}개
            )
        """.trimIndent()
    }
}
