package com.ant.cointrading.event

import java.time.Instant

/**
 * 트레이딩 시스템 에러 이벤트
 *
 * 에러 발생 시 Spring Event로 발행하여
 * ErrorEventHandler에서 Slack 알림 전송
 *
 * Spring 6+에서는 ApplicationEvent 상속 불필요
 */
data class TradingErrorEvent(
    val eventSource: Any,       // 소스 (ApplicationEvent.getSource()와 충돌 방지)
    val component: String,      // 에러 발생 컴포넌트 (예: BithumbPublicApi, FundingRateMonitor)
    val operation: String,       // 수행 중이던 작업 (예: getOhlcv, monitorFundingRates)
    val market: String?,         // 관련 마켓 (선택)
    val errorMessage: String,    // 에러 메시지
    val exception: Throwable?,   // 예외 객체 (선택)
    val timestamp: Instant = Instant.now()
)
