package com.ant.cointrading.collector

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.CandleResponse
import com.ant.cointrading.repository.OhlcvHistoryEntity
import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 과거 OHLCV 데이터 수집기
 *
 * 매일 자정에 과거 데이터를 수집하여 DB에 저장
 */
@Component
class OhlcvHistoryCollector(
    private val bithumbPublicApi: BithumbPublicApi,
    private val ohlcvHistoryRepository: OhlcvHistoryRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val intervals = listOf("minute60", "day", "week", "month")

    /**
     * 매일 새벽 2시에 과거 데이터 수집 (배치 작업)
     */
    @Scheduled(cron = "0 0 2 * * ?")
    fun collectHistoricalData() {
        log.info("=== OHLCV 히스토리 수집 시작 ===")

        val allMarkets = bithumbPublicApi.getMarketAll()
        if (allMarkets == null) {
            log.warn("마켓 목록 조회 실패")
            return
        }

        val krwMarkets = allMarkets.filter { it.market.startsWith("KRW-") }
        log.info("KRW 마켓 ${krwMarkets.size}개 발견")

        krwMarkets.forEach { marketInfo ->
            intervals.forEach { interval ->
                try {
                    collectForInterval(marketInfo.market, interval)
                } catch (e: Exception) {
                    log.error("수집 실패 [${marketInfo.market}][$interval]: ${e.message}")
                }
            }
        }

        log.info("=== OHLCV 히스토리 수집 완료 ===")
    }

    /**
     * 특정 마켓과 간격의 데이터 수집
     */
    fun collectForInterval(market: String, interval: String, maxIterations: Int = 10) {
        val count = 200  // 빗썸 API 최대값
        var to: String? = null
        var totalCollected = 0
        var totalSkipped = 0

        // 최근 데이터부터 과거로 순차 조회
        repeat(maxIterations) { iteration ->
            val candles = bithumbPublicApi.getOhlcv(market, interval, count, to)
            if (candles.isNullOrEmpty()) {
                log.debug("더 이상 데이터 없음 [$market][$interval] (반복 ${iteration + 1})")
                return
            }

            // 중복 체크 및 저장
            var saved = 0
            var skipped = 0

            candles.forEach { candle ->
                val timestamp = candle.timestamp

                // 중복 체크
                if (ohlcvHistoryRepository.existsByMarketAndIntervalAndTimestamp(
                        market,
                        interval,
                        timestamp
                    )
                ) {
                    skipped++
                    return@forEach
                }

                // DB 저장
                val entity = OhlcvHistoryEntity()
                entity.market = market
                entity.interval = interval
                entity.timestamp = timestamp
                entity.open = candle.openingPrice.toDouble()
                entity.high = candle.highPrice.toDouble()
                entity.low = candle.lowPrice.toDouble()
                entity.close = candle.tradePrice.toDouble()
                entity.volume = candle.candleAccTradeVolume.toDouble()
                ohlcvHistoryRepository.save(entity)
                saved++
            }

            totalCollected += saved
            totalSkipped += skipped

            log.debug(
                "수집 [$market][$interval] ${candles.size}건 (저장: $saved, 중복: $skipped, " +
                        "반복 ${iteration + 1}/$maxIterations)"
            )

            // 다음 페이지 조회를 위해 마지막 캔들의 타임스탬프 사용
            to = candles.lastOrNull()?.let { formatToTimestamp(it) }
        }

        log.info("수집 완료 [$market][$interval] - 저장: $totalCollected, 중복: $totalSkipped")
    }

    /**
     * 수동 수집 트리거 (테스트용)
     */
    fun manualCollect(market: String, interval: String) {
        log.info("수동 수집 시작 [$market][$interval]")
        collectForInterval(market, interval, maxIterations = 5)
    }

    /**
     * 타임스탬프 포맷팅 (yyyy-MM-dd HH:mm:ss)
     */
    private fun formatToTimestamp(candle: CandleResponse): String {
        return Instant.ofEpochMilli(candle.timestamp)
            .atZone(java.time.ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    /**
     * DB에 저장된 데이터의 타임스탬프 범위 조회
     */
    fun getDataRange(market: String, interval: String): Pair<Long?, Long?> {
        val oldest = ohlcvHistoryRepository
            .findFirstByMarketAndIntervalOrderByTimestampAsc(market, interval)
        val newest = ohlcvHistoryRepository
            .findFirstByMarketAndIntervalOrderByTimestampDesc(market, interval)

        return Pair(oldest?.timestamp, newest?.timestamp)
    }
}
