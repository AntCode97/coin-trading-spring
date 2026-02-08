package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.backtest.BacktestEngine
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.model.Candle
import com.ant.cointrading.strategy.MeanReversionStrategy
import org.springframework.web.bind.annotation.*
import java.time.Instant

/**
 * 백테스팅 API (Jim Simons 스타일 검증)
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val bithumbPublicApi: BithumbPublicApi,
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
        val normalizedMarket = PositionHelper.convertToApiMarket(market)
        val lookbackHours = (days.coerceAtLeast(1) * 24).coerceAtMost(200)
        val rawCandles = bithumbPublicApi.getOhlcv(normalizedMarket, "minute60", lookbackHours)

        if (rawCandles.isNullOrEmpty() || rawCandles.size < 100) {
            return mapOf(
                "success" to false,
                "market" to normalizedMarket,
                "strategy" to "MEAN_REVERSION",
                "period" to "${days}days",
                "message" to "백테스트 데이터 부족 (필요 최소 100개, 현재 ${rawCandles?.size ?: 0}개)"
            )
        }

        val candles = rawCandles.map {
            Candle(
                timestamp = Instant.ofEpochMilli(it.timestamp),
                open = it.openingPrice,
                high = it.highPrice,
                low = it.lowPrice,
                close = it.tradePrice,
                volume = it.candleAccTradeVolume
            )
        }.reversed()

        val result = backtestEngine.runBacktest(
            strategy = meanReversionStrategy,
            market = normalizedMarket,
            candles = candles
        )

        return mapOf(
            "success" to true,
            "market" to normalizedMarket,
            "strategy" to "MEAN_REVERSION",
            "period" to "${days}days",
            "candles" to candles.size,
            "result" to result
        )
    }
}
