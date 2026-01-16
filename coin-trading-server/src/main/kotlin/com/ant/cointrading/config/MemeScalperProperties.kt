package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Meme Scalper 전략 설정
 *
 * 세력 코인 초단타 전략:
 * - 펌프앤덤프 초기 진입, 빠른 청산
 * - 1분봉 기반 분석
 * - 타이트한 손절/익절
 */
@ConfigurationProperties(prefix = "memescalper")
data class MemeScalperProperties(
    /** 전략 활성화 여부 */
    val enabled: Boolean = false,

    /** 펌프 감지 폴링 간격 (밀리초) - 빠른 감지 필요 */
    val pollingIntervalMs: Long = 5000,

    /** 1회 포지션 크기 (KRW) - 최소 5100원 이상 */
    val positionSizeKrw: Int = 6000,

    /** 최대 동시 포지션 수 */
    val maxPositions: Int = 3,

    // === 손절/익절 (타이트) ===

    /** 손절 비율 (%) */
    val stopLossPercent: Double = -1.5,

    /** 익절 비율 (%) */
    val takeProfitPercent: Double = 2.0,

    /** 타임아웃 (분) - 5분 */
    val positionTimeoutMin: Int = 5,

    // === 펌프 감지 조건 ===

    /** 거래량 급등 배율 (5분 평균 대비) */
    val volumeSpikeRatio: Double = 5.0,

    /** 가격 급등 비율 (1분 내, %) */
    val priceSpikePercent: Double = 3.0,

    /** 호가창 매수 Imbalance 최소값 */
    val minBidImbalance: Double = 0.3,

    /** RSI 상한 (과매수 진입 방지) */
    val maxRsi: Int = 80,

    // === 청산 신호 ===

    /** 거래량 급감 비율 (피크 대비) - 매수세 이탈 */
    val volumeDropRatio: Double = 0.3,

    /** 호가창 반전 임계값 (Imbalance < 이 값이면 탈출) */
    val imbalanceExitThreshold: Double = 0.0,

    // === 리스크 관리 ===

    /** 쿨다운 (초) - 동일 마켓 재진입 대기 */
    val cooldownSec: Int = 60,

    /** 연속 손실 한도 */
    val maxConsecutiveLosses: Int = 3,

    /** 일일 최대 손실 (KRW) */
    val dailyMaxLossKrw: Int = 20000,

    /** 일일 최대 거래 횟수 */
    val dailyMaxTrades: Int = 50,

    // === 제외 마켓 ===

    /** 거래 제외 마켓 (대형 코인 - 시가총액 상위) */
    val excludeMarkets: List<String> = listOf(
        "KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL",
        "KRW-ADA", "KRW-DOGE", "KRW-DOT", "KRW-MATIC",
        "KRW-ETC", "KRW-LTC", "KRW-BCH", "KRW-LINK",
        "KRW-AVAX", "KRW-TRX", "KRW-SHIB", "KRW-ATOM",
        "KRW-EOS", "KRW-XLM", "KRW-HBAR", "KRW-VET",
        "KRW-BSV", "KRW-XMR", "KRW-NEO", "KRW-KLAY"
    ),

    /** 최소 거래대금 (KRW) - 너무 작은 코인 제외 */
    val minTradingValueKrw: Long = 100_000_000,  // 1억

    /** 최대 거래대금 (KRW) - 대형 코인 제외 */
    val maxTradingValueKrw: Long = 50_000_000_000  // 500억
)
