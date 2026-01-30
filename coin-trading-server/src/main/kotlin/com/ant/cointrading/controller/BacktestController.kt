package com.ant.cointrading.controller

import com.ant.cointrading.backtest.BacktestEngine
import com.ant.cointrading.strategy.MeanReversionStrategy
import org.springframework.web.bind.annotation.*

/**
 * 백테스팅 API (Jim Simons 스타일 검증)
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestEngine: BacktestEngine,
    private val meanReversionStrategy: MeanReversionStrategy
) {

    /**
     * 평균 회귀 전략 백테스팅
     *
     * @param market 마켓 (예: KRW-BTC)
     * @param days 과거 일수 (기본 30일)
     */
    @GetMapping("/mean-reversion/{market}")
    fun backtestMeanReversion(
        @PathVariable market: String,
        @RequestParam(defaultValue = "30") days: Int
    ): Map<String, Any?> {
        // TODO: 과거 데이터 가져오기 (Bithumb API or DB)
        // 현재는 더미 데이터 반환

        return mapOf(
            "market" to market,
            "strategy" to "MEAN_REVERSION",
            "period" to "${days}days",
            "status" to "TODO",
            "message" to "과거 데이터 수집 기능 구현 필요"
        )
    }
}
