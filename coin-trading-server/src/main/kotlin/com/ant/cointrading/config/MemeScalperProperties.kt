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
    var enabled: Boolean = true,

    /** 펌프 감지 폴링 간격 (밀리초) - 빠른 감지 필요 */
    var pollingIntervalMs: Long = 5000,

    /** 1회 포지션 크기 (KRW) - 최소 5100원 이상 */
    var positionSizeKrw: Int = 6000,

    /** 최대 동시 포지션 수 */
    var maxPositions: Int = 3,

    // === 손절/익절 (타이트) ===

    /** 손절 비율 (%) */
    var stopLossPercent: Double = -1.5,

    /** 익절 비율 (%) - 2:1 손익비를 위해 3% */
    var takeProfitPercent: Double = 3.0,

    /** 타임아웃 (분) - 10분 (TIMEOUT 분석: 5분 타임아웃에서 평균 +0.07% 수익) */
    var positionTimeoutMin: Int = 10,

    // === 트레일링 스탑 ===

    /** 트레일링 스탑 시작 기준 (%) - 이 수익 이상이면 트레일링 활성화 */
    var trailingStopTrigger: Double = 1.0,

    /** 트레일링 스탑 오프셋 (%) - 고점 대비 이만큼 하락 시 청산 */
    var trailingStopOffset: Double = 0.5,

    // === 펌프 감지 조건 ===

    /** 거래량 급등 배율 (5분 평균 대비) */
    var volumeSpikeRatio: Double = 5.0,

    /** 가격 급등 비율 (1분 내, %) */
    var priceSpikePercent: Double = 3.0,

    /** 호가창 매수 Imbalance 최소값 */
    var minBidImbalance: Double = 0.3,

    /** RSI 상한 (과매수 진입 방지) */
    var maxRsi: Int = 80,

    // === 웹소켓 후보 추출 ===

    /** 웹소켓 피드 기반 후보 추출 사용 여부 */
    var useWebSocketFeed: Boolean = true,

    /** 웹소켓 후보 상위 N개만 정밀 분석 */
    var websocketCandidateLimit: Int = 40,

    /** 최근 10초 최소 체결대금 (KRW) */
    var websocketMinNotional10sKrw: Long = 30_000_000,

    /** 최근 10초 최소 가격 상승률 (%) */
    var websocketMinPriceSpikePercent: Double = 0.4,

    /** 최근 10초 체결대금 스파이크 배율 (직전 60초 평균 대비) */
    var websocketMinNotionalSpikeRatio: Double = 1.8,

    /** 웹소켓 후보 최소 매수 Imbalance */
    var websocketMinBidImbalance: Double = 0.05,

    /** 웹소켓 후보 최대 스프레드 (%) */
    var websocketMaxSpreadPercent: Double = 0.5,

    // === 청산 신호 ===

    /** 거래량 급감 비율 (피크 대비) - 매수세 이탈 */
    var volumeDropRatio: Double = 0.3,

    /** 호가창 반전 임계값 (Imbalance < 이 값이면 탈출) */
    var imbalanceExitThreshold: Double = 0.0,

    // === 리스크 관리 ===

    /** 쿨다운 (초) - 동일 마켓 재진입 대기 */
    var cooldownSec: Int = 60,

    /** 연속 손실 한도 */
    var maxConsecutiveLosses: Int = 3,

    /** 일일 최대 손실 (KRW) */
    var dailyMaxLossKrw: Int = 20000,

    /** 일일 최대 거래 횟수 */
    var dailyMaxTrades: Int = 50,

    // === 제외 마켓 ===

    /** 거래 제외 마켓 (대형 코인 - 시가총액 상위) */
    var excludeMarkets: List<String> = listOf(
        "KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL",
        "KRW-ADA", "KRW-DOGE", "KRW-DOT", "KRW-MATIC",
        "KRW-ETC", "KRW-LTC", "KRW-BCH", "KRW-LINK",
        "KRW-AVAX", "KRW-TRX", "KRW-SHIB", "KRW-ATOM",
        "KRW-EOS", "KRW-XLM", "KRW-HBAR", "KRW-VET",
        "KRW-BSV", "KRW-XMR", "KRW-NEO", "KRW-KLAY"
    ),

    /** 최소 거래대금 (KRW) - 유동성 확보를 위한 최소 기준 */
    var minTradingValueKrw: Long = 1_000_000_000,  // 10억 (저유동성 잡코인 제외)

    /** 최대 거래대금 (KRW) - 대형 코인 제외 */
    var maxTradingValueKrw: Long = 50_000_000_000,  // 500억

    // === AI 회고 ===

    /** 회고 크론 표현식 (기본: 매일 새벽 1시) */
    var reflectionCron: String = "0 0 1 * * *"
)
