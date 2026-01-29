package com.ant.cointrading.memescalper

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.indicator.EmaCalculator
import com.ant.cointrading.indicator.RsiCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 펌프 감지 결과
 */
data class PumpSignal(
    val market: String,
    val volumeSpikeRatio: Double,    // 거래량 스파이크 비율
    val priceSpikePercent: Double,   // 가격 스파이크 (%)
    val bidImbalance: Double,        // 호가창 매수 Imbalance
    val rsi: Double,                 // RSI
    val macdSignal: String,          // MACD 신호 (BULLISH/BEARISH/NEUTRAL)
    val spreadPercent: Double,       // 스프레드 (%)
    val currentPrice: BigDecimal,    // 현재가
    val tradingValue: BigDecimal,    // 24시간 거래대금
    val score: Int                   // 종합 점수 (0-100)
)

/**
 * Meme Scalper 펌프 감지기
 *
 * 1분봉 기반 실시간 펌프 감지:
 * - 거래량 급등 (5분 평균 대비 500%+)
 * - 가격 급등 (1분 내 +3%+)
 * - 호가창 매수 압력 (Imbalance > 0.3)
 */
@Component
class MemeScalperDetector(
    private val bithumbPublicApi: BithumbPublicApi,
    private val properties: MemeScalperProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실패 마켓 캐시 (TTL 기반)
    private val failedMarkets = mutableMapOf<String, Instant>()

    companion object {
        const val RSI_PERIOD = 9  // 빠른 RSI
        const val MIN_ENTRY_SCORE = 70  // 진입 최소 점수
        const val MIN_PRICE_SPIKE_PERCENT = 1.0  // 최소 가격 스파이크 1% 필수
        // 최대 스프레드: TradingConstants.MAX_SPREAD_PERCENT 사용

        // MACD 설정 (스캘핑용 빠른 설정)
        const val MACD_FAST = 5
        const val MACD_SLOW = 13
        const val MACD_SIGNAL = 6

        // 실패 마켓 캐시 TTL (1시간)
        private val FAILURE_TTL_MINUTES = 60L
    }

    /**
     * 만료된 실패 마켓 정리
     */
    private fun cleanExpiredFailures() {
        val cutoff = Instant.now().minus(FAILURE_TTL_MINUTES, ChronoUnit.MINUTES)
        val beforeSize = failedMarkets.size
        failedMarkets.entries.removeIf { it.value < cutoff }
        val removed = beforeSize - failedMarkets.size
        if (removed > 0) {
            log.debug("실패 마켓 캐시 정리: ${removed}개 제거 (남은: ${failedMarkets.size}개)")
        }
    }

    /**
     * 마켓 실패 여부 체크 (TTL 고려)
     */
    private fun isMarketFailed(market: String): Boolean {
        val failedAt = failedMarkets[market] ?: return false
        val cutoff = Instant.now().minus(FAILURE_TTL_MINUTES, ChronoUnit.MINUTES)
        return failedAt > cutoff
    }

    /**
     * 마켓 실패 기록
     */
    private fun markFailure(market: String) {
        failedMarkets[market] = Instant.now()
    }

    /**
     * 전체 마켓 스캔하여 펌프 신호 감지
     */
    fun scanForPumps(): List<PumpSignal> {
        // 만료된 실패 마켓 정리
        cleanExpiredFailures()

        val allMarkets = bithumbPublicApi.getMarketAll() ?: return emptyList()

        // KRW 마켓만 필터링 (제외 마켓 + 실패 마켓 제외)
        val krwMarkets = allMarkets
            .filter { it.market.startsWith("KRW-") }
            .filter { it.market !in properties.excludeMarkets }
            .filter { !isMarketFailed(it.market) }
            .map { it.market }

        log.debug("스캔 대상 마켓: ${krwMarkets.size}개 (실패 캐시: ${failedMarkets.size}개)")

        val signals = mutableListOf<PumpSignal>()

        for (market in krwMarkets) {
            try {
                val signal = detectPump(market)
                if (signal != null &&
                    signal.score >= MIN_ENTRY_SCORE &&
                    signal.priceSpikePercent >= MIN_PRICE_SPIKE_PERCENT) {
                    signals.add(signal)
                    log.info("[$market] 펌프 감지! 점수=${signal.score}, 거래량=${signal.volumeSpikeRatio}x, 가격=${signal.priceSpikePercent}%")
                }
            } catch (e: Exception) {
                log.debug("[$market] 분석 실패: ${e.message}")
                markFailure(market)
            }
        }

        // 점수 높은 순으로 정렬
        return signals.sortedByDescending { it.score }
    }

    /**
     * 단일 마켓 펌프 감지
     */
    fun detectPump(market: String): PumpSignal? {
        // 1분봉 데이터 조회 (MACD 계산 위해 더 많이)
        val candles = bithumbPublicApi.getOhlcv(market, "minute1", 20)
        if (candles == null || candles.size < 15) {
            markFailure(market)
            return null
        }

        // 최신순 정렬 (API는 최신이 앞)
        val sortedCandles = candles.sortedByDescending { it.timestamp }

        val currentCandle = sortedCandles.first()
        val currentPrice = currentCandle.tradePrice
        val currentVolume = currentCandle.candleAccTradeVolume.toDouble()

        // 5분 평균 거래량 계산 (현재 제외)
        val prevVolumes = sortedCandles.drop(1).take(5).map { it.candleAccTradeVolume.toDouble() }
        val avgVolume = if (prevVolumes.isNotEmpty()) prevVolumes.average() else 1.0

        // 거래량 스파이크 비율
        val volumeSpikeRatio = if (avgVolume > 0) currentVolume / avgVolume else 0.0

        // 가격 스파이크 (1분 전 대비)
        val prevClose = sortedCandles.getOrNull(1)?.tradePrice ?: currentPrice
        val priceSpikePercent = if (prevClose > BigDecimal.ZERO) {
            ((currentPrice - prevClose) / prevClose * BigDecimal(100)).toDouble()
        } else 0.0

        // 24시간 거래대금 확인
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull() ?: return null
        val tradingValue = ticker.accTradePrice ?: BigDecimal.ZERO

        // 거래대금 필터
        if (tradingValue < BigDecimal(properties.minTradingValueKrw) ||
            tradingValue > BigDecimal(properties.maxTradingValueKrw)) {
            return null
        }

        // 호가창 데이터로 Imbalance와 스프레드 계산
        val orderbook = bithumbPublicApi.getOrderbook(market)?.firstOrNull()
        val bidImbalance = calculateImbalanceFromOrderbook(orderbook)
        val spreadPercent = calculateSpread(orderbook)

        // 스프레드 필터 (0.5% 초과 시 제외 - 슬리피지 위험)
        if (spreadPercent > TradingConstants.MAX_SPREAD_PERCENT) {
            log.debug("[$market] 스프레드 과다: ${String.format("%.2f", spreadPercent)}%")
            return null
        }

        // RSI 계산
        val closes = sortedCandles.map { it.tradePrice.toDouble() }
        val rsi = RsiCalculator.calculate(closes, RSI_PERIOD)

        // MACD 계산
        val macdSignal = calculateMacdSignal(closes)

        // 점수 계산
        val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi, macdSignal)

        return PumpSignal(
            market = market,
            volumeSpikeRatio = volumeSpikeRatio,
            priceSpikePercent = priceSpikePercent,
            bidImbalance = bidImbalance,
            rsi = rsi,
            macdSignal = macdSignal,
            spreadPercent = spreadPercent,
            currentPrice = currentPrice,
            tradingValue = tradingValue,
            score = score
        )
    }

    /**
     * 호가창 Imbalance 계산 (orderbook 파라미터 버전)
     */
    private fun calculateImbalanceFromOrderbook(orderbook: com.ant.cointrading.api.bithumb.OrderbookInfo?): Double {
        val units = orderbook?.orderbookUnits ?: return 0.0

        val bidVolume = units.sumOf { it.bidSize.toDouble() }
        val askVolume = units.sumOf { it.askSize.toDouble() }

        val total = bidVolume + askVolume
        return if (total > 0) (bidVolume - askVolume) / total else 0.0
    }

    /**
     * 스프레드 계산 (%)
     * 스프레드 = (최우선 매도호가 - 최우선 매수호가) / 최우선 매수호가 × 100
     */
    private fun calculateSpread(orderbook: com.ant.cointrading.api.bithumb.OrderbookInfo?): Double {
        val units = orderbook?.orderbookUnits?.firstOrNull() ?: return 10.0  // 데이터 없으면 높은 값
        val bestBid = units.bidPrice.toDouble()
        val bestAsk = units.askPrice.toDouble()

        if (bestBid <= 0) return 10.0
        return ((bestAsk - bestBid) / bestBid) * 100
    }

    /**
     * 호가창 Imbalance 계산 (market 파라미터 버전 - 청산 신호 감지용)
     */
    private fun calculateImbalance(market: String): Double {
        val orderbook = bithumbPublicApi.getOrderbook(market)?.firstOrNull() ?: return 0.0
        return calculateImbalanceFromOrderbook(orderbook)
    }

    /**
     * MACD 신호 계산 (스캘핑용 빠른 설정: 5/13/6)
     *
     * @return BULLISH (매수 신호), BEARISH (매도 신호), NEUTRAL (중립)
     */
    private fun calculateMacdSignal(closes: List<Double>): String {
        if (closes.size < MACD_SLOW + MACD_SIGNAL) return "NEUTRAL"

        // EMA 계산
        val (fastEma, slowEma) = EmaCalculator.calculateMacdEmas(closes, MACD_FAST, MACD_SLOW)

        // MACD 라인
        val macdLine = fastEma.zip(slowEma) { f, s -> f - s }
        if (macdLine.size < MACD_SIGNAL + 1) return "NEUTRAL"

        // 시그널 라인 (MACD의 EMA)
        val signalLine = EmaCalculator.calculate(macdLine, MACD_SIGNAL)

        // 최근 2개 값으로 크로스오버 판단
        val currentMacd = macdLine.lastOrNull() ?: 0.0
        val previousMacd = macdLine.getOrNull(macdLine.size - 2) ?: 0.0
        val currentSignal = signalLine.lastOrNull() ?: 0.0
        val previousSignal = signalLine.getOrNull(signalLine.size - 2) ?: 0.0

        return when {
            // 골든 크로스 (MACD가 시그널 상향 돌파)
            previousMacd <= previousSignal && currentMacd > currentSignal -> "BULLISH"
            // 데드 크로스 (MACD가 시그널 하향 돌파)
            previousMacd >= previousSignal && currentMacd < currentSignal -> "BEARISH"
            // MACD가 시그널 위에 있고 상승 중
            currentMacd > currentSignal && currentMacd > previousMacd -> "BULLISH"
            // MACD가 시그널 아래에 있고 하락 중
            currentMacd < currentSignal && currentMacd < previousMacd -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    /**
     * 펌프 점수 계산 (0-100)
     *
     * 배점 (컨플루언스 강화):
     * - 거래량 스파이크: 35점 (핵심)
     * - 가격 스파이크: 25점
     * - 호가창 Imbalance: 15점
     * - RSI 조건: 15점
     * - MACD 신호: 10점 (추가)
     */
    private fun calculateScore(
        volumeSpikeRatio: Double,
        priceSpikePercent: Double,
        bidImbalance: Double,
        rsi: Double,
        macdSignal: String
    ): Int {
        var score = 0

        // 1. 거래량 스파이크 (35점) - 핵심 지표
        score += when {
            volumeSpikeRatio >= 10.0 -> 35  // 1000%+
            volumeSpikeRatio >= 7.0 -> 30   // 700%+
            volumeSpikeRatio >= 5.0 -> 25   // 500%+ (기준)
            volumeSpikeRatio >= 3.0 -> 20   // 300%+
            volumeSpikeRatio >= 2.0 -> 10   // 200%+
            else -> 0
        }

        // 2. 가격 스파이크 (25점) - 상승 모멘텀 확인
        score += when {
            priceSpikePercent >= 10.0 -> 25  // 10%+
            priceSpikePercent >= 5.0 -> 22   // 5%+
            priceSpikePercent >= 3.0 -> 18   // 3%+ (기준)
            priceSpikePercent >= 2.0 -> 12   // 2%+
            priceSpikePercent >= 1.0 -> 8    // 1%+
            else -> 0
        }

        // 3. 호가창 Imbalance (15점) - 매수 압력 확인
        score += when {
            bidImbalance >= 0.5 -> 15   // 강한 매수 압력
            bidImbalance >= 0.3 -> 12   // 매수 압력 (기준)
            bidImbalance >= 0.1 -> 8    // 약한 매수 압력
            bidImbalance >= 0.0 -> 4    // 중립
            else -> 0                    // 매도 압력 - 진입 금지
        }

        // 4. RSI 조건 (15점) - 과매수 회피
        score += when {
            rsi in 50.0..65.0 -> 15    // 최적 구간 (상승 중이지만 과매수 아님)
            rsi in 40.0..50.0 -> 10    // 상승 초기
            rsi in 65.0..75.0 -> 8     // 주의 구간
            rsi in 75.0..80.0 -> 3     // 위험 구간
            rsi > 80.0 -> 0            // 과매수 - 진입 위험
            else -> 0
        }

        // 5. MACD 신호 (10점) - 추세 확인
        // MACD BEARISH 시 강력한 패널티 적용 (VolumeSurge Analyzer와 동일)
        score += when (macdSignal) {
            "BULLISH" -> 10   // 골든 크로스 또는 상승 추세
            "NEUTRAL" -> 3    // 중립
            "BEARISH" -> -20  // 데드 크로스 - 강력한 패널티 (-10점 → -20점)
            else -> 0
        }

        return score
    }

    /**
     * 청산 신호 감지
     *
     * - 거래량 급감 (피크 대비 30% 이하)
     * - 호가창 반전 (Imbalance < 0)
     * - 가격 하락 반전
     */
    fun detectExitSignal(market: String, peakVolume: Double): ExitSignal? {
        val candles = bithumbPublicApi.getOhlcv(market, "minute1", 3) ?: return null
        if (candles.isEmpty()) return null

        val currentCandle = candles.maxByOrNull { it.timestamp } ?: return null
        val currentVolume = currentCandle.candleAccTradeVolume.toDouble()

        // 거래량 급감 체크
        val volumeRatio = if (peakVolume > 0) currentVolume / peakVolume else 1.0
        if (volumeRatio <= properties.volumeDropRatio) {
            return ExitSignal(
                market = market,
                reason = "VOLUME_DROP",
                detail = "거래량 급감 (${String.format("%.1f", volumeRatio * 100)}% of peak)"
            )
        }

        // 호가창 반전 체크
        val imbalance = calculateImbalance(market)
        if (imbalance < properties.imbalanceExitThreshold) {
            return ExitSignal(
                market = market,
                reason = "IMBALANCE_FLIP",
                detail = "호가창 반전 (Imbalance: ${String.format("%.2f", imbalance)})"
            )
        }

        return null
    }
}

/**
 * 청산 신호
 */
data class ExitSignal(
    val market: String,
    val reason: String,
    val detail: String
)
