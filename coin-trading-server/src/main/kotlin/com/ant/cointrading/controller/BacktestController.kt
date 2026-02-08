package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.backtest.BacktestEngine
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.model.Candle
import com.ant.cointrading.strategy.MeanReversionStrategy
import com.ant.cointrading.strategy.TradingStrategy
import com.ant.cointrading.strategy.VolatilitySurvivalStrategy
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 백테스팅 API (Jim Simons 스타일 검증)
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val bithumbPublicApi: BithumbPublicApi,
    private val backtestEngine: BacktestEngine,
    private val meanReversionStrategy: MeanReversionStrategy,
    private val volatilitySurvivalStrategy: VolatilitySurvivalStrategy
) {
    companion object {
        private const val MAX_CANDLES_PER_CALL = 200
        private const val MAX_PAGINATION_ROUNDS = 20
        private val BITHUMB_TO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
    }

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
        return runBacktest(
            market = market,
            days = days,
            strategy = meanReversionStrategy
        )
    }

    /**
     * 고변동성 생존 전략 백테스팅
     *
     * @param market 마켓 (예: KRW-BTC)
     * @param days 과거 일수 (기본 30일)
     */
    @GetMapping("/volatility-survival/{market}")
    fun backtestVolatilitySurvival(
        @PathVariable market: String,
        @RequestParam(defaultValue = "30") days: Int
    ): Map<String, Any?> {
        return runBacktest(
            market = market,
            days = days,
            strategy = volatilitySurvivalStrategy
        )
    }

    private fun runBacktest(
        market: String,
        days: Int,
        strategy: TradingStrategy
    ): Map<String, Any?> {
        val normalizedMarket = PositionHelper.convertToApiMarket(market)
        val requestedHours = days.coerceAtLeast(1) * 24
        val rawCandles = fetchHourlyCandles(normalizedMarket, requestedHours)

        if (rawCandles.isNullOrEmpty() || rawCandles.size < 100) {
            return mapOf(
                "success" to false,
                "market" to normalizedMarket,
                "strategy" to strategy.name,
                "period" to "${days}days",
                "requestedHours" to requestedHours,
                "usedHours" to (rawCandles?.size ?: 0),
                "isCappedByApiLimit" to ((rawCandles?.size ?: 0) < requestedHours),
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
            strategy = strategy,
            market = normalizedMarket,
            candles = candles
        )

        return mapOf(
            "success" to true,
            "market" to normalizedMarket,
            "strategy" to strategy.name,
            "period" to "${days}days",
            "requestedHours" to requestedHours,
            "usedHours" to rawCandles.size,
            "isCappedByApiLimit" to (rawCandles.size < requestedHours),
            "candles" to candles.size,
            "result" to result
        )
    }

    private fun fetchHourlyCandles(market: String, requestedHours: Int): List<com.ant.cointrading.api.bithumb.CandleResponse>? {
        val all = mutableListOf<com.ant.cointrading.api.bithumb.CandleResponse>()
        var cursorTo: String? = null
        var remaining = requestedHours
        var rounds = 0

        while (remaining > 0 && rounds < MAX_PAGINATION_ROUNDS) {
            rounds++
            val count = remaining.coerceAtMost(MAX_CANDLES_PER_CALL)
            val batch = bithumbPublicApi.getOhlcv(market, "minute60", count, cursorTo) ?: break
            if (batch.isEmpty()) break

            all.addAll(batch)
            remaining -= batch.size

            val oldest = batch.last()
            cursorTo = BITHUMB_TO_FORMATTER.format(Instant.ofEpochMilli(oldest.timestamp).minusSeconds(1))

            if (batch.size < count) break
        }

        if (all.isEmpty()) return null

        return all
            .groupBy { it.timestamp }
            .values
            .map { it.first() }
            .sortedByDescending { it.timestamp }
    }
}
