package com.ant.cointrading.controller

import com.ant.cointrading.backtesting.WalkForwardOptimizer
import com.ant.cointrading.collector.OhlcvHistoryCollector
import com.ant.cointrading.repository.BacktestResultEntity
import com.ant.cointrading.repository.BacktestResultRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * 백테스팅 API 컨트롤러
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val walkForwardOptimizer: WalkForwardOptimizer,
    private val backtestResultRepository: BacktestResultRepository,
    private val ohlcvHistoryCollector: OhlcvHistoryCollector
) {

    /**
     * 전략 파라미터 최적화 실행
     *
     * @param strategy 전략 이름 (BREAKOUT)
     * @param market 마켓 코드 (기본값: KRW-BTC)
     * @param startDate 시작일 (기본값: 3개월 전)
     * @param endDate 종료일 (기본값: 오늘)
     * @param interval 캔들 간격 (기본값: day)
     */
    @PostMapping("/optimize/{strategy}")
    fun optimizeStrategy(
        @PathVariable strategy: String,
        @RequestParam(defaultValue = "KRW-BTC") market: String,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") startDate: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") endDate: LocalDate?,
        @RequestParam(defaultValue = "day") interval: String
    ): ResponseEntity<Map<String, Any?>> {
        val end = endDate ?: LocalDate.now()
        val start = startDate ?: end.minusMonths(3)

        return try {
            val result = when (strategy.uppercase()) {
                "BREAKOUT" -> {
                    walkForwardOptimizer.optimizeBreakout(
                        market = market,
                        startDate = start,
                        endDate = end,
                        interval = interval
                    )
                }
                else -> {
                    return ResponseEntity.ok(
                        mapOf(
                            "status" to "error",
                            "message" to "Unknown strategy: $strategy",
                            "available" to listOf("BREAKOUT")
                        )
                    )
                }
            }

            ResponseEntity.ok(
                mapOf(
                    "status" to "completed",
                    "strategy" to strategy,
                    "market" to market,
                    "period" to mapOf(
                        "start" to start.toString(),
                        "end" to end.toString()
                    ),
                    "bestParameters" to result.parameters,
                    "sharpeRatio" to (result.result?.sharpeRatio ?: 0.0),
                    "totalReturn" to (result.result?.totalReturn ?: 0.0),
                    "totalTrades" to (result.result?.totalTrades ?: 0),
                    "winRate" to (result.result?.winRate ?: 0.0),
                    "maxDrawdown" to (result.result?.maxDrawdown ?: 0.0)
                )
            )
        } catch (e: Exception) {
            ResponseEntity.ok(
                mapOf(
                    "status" to "error",
                    "message" to e.message
                )
            )
        }
    }

    /**
     * 백테스트 결과 목록 조회
     */
    @GetMapping("/results")
    fun getResults(
        @RequestParam(defaultValue = "30") days: Int
    ): List<BacktestResultEntity> {
        val since = LocalDate.now().minusDays(days.toLong())
        return backtestResultRepository.findByStartDateAfterOrderByCreatedAtDesc(since)
    }

    /**
     * 특정 전략의 최적 결과 조회
     */
    @GetMapping("/results/{strategy}/best")
    fun getBestResult(
        @PathVariable strategy: String,
        @RequestParam(defaultValue = "KRW-BTC") market: String
    ): ResponseEntity<BacktestResultEntity?> {
        val result = when (strategy.uppercase()) {
            "BREAKOUT" -> {
                backtestResultRepository.findTopByStrategyNameAndMarketOrderBySharpeRatioDesc(
                    strategyName = "BREAKOUT",
                    market = market
                )
            }
            else -> null
        }

        return if (result != null) ResponseEntity.ok(result) else ResponseEntity.notFound().build()
    }

    /**
     * OHLCV 데이터 수집 상태 조회
     */
    @GetMapping("/ohlcv/status")
    fun getOhlcvStatus(
        @RequestParam market: String,
        @RequestParam(defaultValue = "day") interval: String
    ): ResponseEntity<Map<String, Any?>> {
        val (oldest, newest) = ohlcvHistoryCollector.getDataRange(market, interval)

        return ResponseEntity.ok(
            mapOf(
                "market" to market,
                "interval" to interval,
                "oldestTimestamp" to oldest,
                "newestTimestamp" to newest,
                "oldestDate" to oldest?.let { java.time.Instant.ofEpochMilli(it) },
                "newestDate" to newest?.let { java.time.Instant.ofEpochMilli(it) },
                "dataPoints" to if (oldest != null && newest != null) {
                    ohlcvHistoryCollector.let { collector ->
                        // 간단한 개수 추정 (실제로는 count 쿼리 필요)
                        "available"
                    }
                } else "none"
            )
        )
    }

    /**
     * 수동 OHLCV 데이터 수집 트리거
     */
    @PostMapping("/ohlcv/collect")
    fun collectOhlcv(
        @RequestParam market: String,
        @RequestParam(defaultValue = "day") interval: String
    ): ResponseEntity<Map<String, Any?>> {
        return try {
            ohlcvHistoryCollector.manualCollect(market, interval)
            ResponseEntity.ok(mapOf("status" to "started", "market" to market, "interval" to interval))
        } catch (e: Exception) {
            ResponseEntity.ok(
                mapOf(
                    "status" to "error",
                    "message" to e.message
                )
            )
        }
    }
}
