package com.ant.cointrading.backtesting

import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.model.Candle
import java.math.BigDecimal

/**
 * 백테스팅 시뮬레이터 인터페이스
 */
interface Simulator {

    /**
     * 전략 백테스트 실행
     *
     * @param strategy 테스트할 전략
     * @param historicalData 과거 캔들 데이터
     * @param initialCapital 초기 자본
     * @param commissionRate 수수료율 (기본값: 빗썸 0.04%)
     * @param slippageRate 체결 슬리피지율 (예: 0.001 = 0.1%)
     * @return 백테스트 결과
     */
    fun simulate(
        strategy: BacktestableStrategy,
        historicalData: List<Candle>,
        initialCapital: Double,
        commissionRate: Double = TradingConstants.BITHUMB_FEE_RATE.toDouble(),
        slippageRate: Double = 0.0
    ): BacktestResult
}

/**
 * 백테스팅 가능한 전략 인터페이스
 *
 * 시뮬레이션을 위해 필요한 최소한의 인터페이스 정의
 */
interface BacktestableStrategy {
    val name: String

    /**
     * 캔들 데이터 분석 후 신호 생성
     *
     * @param candles 사용 가능한 전체 캔들 데이터 (시간순)
     * @param currentIndex 분석할 캔들의 인덱스
     * @param initialCapital 현재 자본
     * @param currentPosition 현재 포지션 (수량, 0 = 포지션 없음)
     * @return 트레이딩 신호
     */
    fun analyzeForBacktest(
        candles: List<Candle>,
        currentIndex: Int,
        initialCapital: Double,
        currentPrice: BigDecimal,
        currentPosition: Double
    ): BacktestSignal
}

/**
 * 백테스팅용 트레이딩 신호
 */
data class BacktestSignal(
    val action: BacktestAction,
    val confidence: Double = 0.0,
    val reason: String = ""
)

/**
 * 백테스팅용 액션
 */
enum class BacktestAction {
    BUY,       // 매수 (진입)
    SELL,      // 매도 (청산)
    HOLD       // 유지
}

/**
 * 백테스트 결과
 */
data class BacktestResult(
    val strategyName: String,
    val initialCapital: Double,
    val finalCapital: Double,
    val totalReturn: Double,           // 총 수익률 (%)
    val totalTrades: Int,              // 총 거래 횟수
    val winningTrades: Int,            // 수익 거래 횟수
    val losingTrades: Int,             // 손실 거래 횟수
    val winRate: Double,               // 승률 (%)
    val maxDrawdown: Double,           // 최대 낙폭 (%)
    val sharpeRatio: Double,           // 샤프 비율
    val profitFactor: Double?,         // 프로핏 팩터
    val avgWin: Double?,               // 평균 수익 (%)
    val avgLoss: Double?,              // 평균 손실 (%)
    val trades: List<BacktestTrade>,   // 거래 내역
    val equityCurve: List<Double>      // 자산 곡선
)

/**
 * 백테스트 거래 내역
 */
data class BacktestTrade(
    val entryIndex: Int,               // 진입 캔들 인덱스
    val exitIndex: Int,                // 청산 캔들 인덱스
    val entryPrice: Double,            // 진입 가격
    val exitPrice: Double,             // 청산 가격
    val quantity: Double,              // 수량
    val pnl: Double,                   // 손익 금액
    val pnlPercent: Double,            // 손익률 (%)
    val entryReason: String,           // 진입 사유
    val exitReason: String,            // 청산 사유
    val holdingPeriod: Int             // 보유 기간 (캔들 수)
)
