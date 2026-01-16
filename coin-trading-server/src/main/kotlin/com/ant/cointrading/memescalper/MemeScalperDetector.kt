package com.ant.cointrading.memescalper

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.MemeScalperProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
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

    companion object {
        const val RSI_PERIOD = 9  // 빠른 RSI
    }

    /**
     * 전체 마켓 스캔하여 펌프 신호 감지
     */
    fun scanForPumps(): List<PumpSignal> {
        val allMarkets = bithumbPublicApi.getMarketAll() ?: return emptyList()

        // KRW 마켓만 필터링
        val krwMarkets = allMarkets
            .filter { it.market.startsWith("KRW-") }
            .filter { it.market !in properties.excludeMarkets }
            .map { it.market }

        log.debug("스캔 대상 마켓: ${krwMarkets.size}개")

        val signals = mutableListOf<PumpSignal>()

        for (market in krwMarkets) {
            try {
                val signal = detectPump(market)
                if (signal != null && signal.score >= 60) {
                    signals.add(signal)
                    log.info("[$market] 펌프 감지! 점수=${signal.score}, 거래량=${signal.volumeSpikeRatio}x, 가격=${signal.priceSpikePercent}%")
                }
            } catch (e: Exception) {
                log.debug("[$market] 분석 실패: ${e.message}")
            }
        }

        // 점수 높은 순으로 정렬
        return signals.sortedByDescending { it.score }
    }

    /**
     * 단일 마켓 펌프 감지
     */
    fun detectPump(market: String): PumpSignal? {
        // 1분봉 데이터 조회 (최근 10개)
        val candles = bithumbPublicApi.getOhlcv(market, "minute1", 10) ?: return null
        if (candles.size < 6) return null

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

        // 호가창 Imbalance 계산
        val bidImbalance = calculateImbalance(market)

        // RSI 계산
        val rsi = calculateRsi(sortedCandles.map { it.tradePrice.toDouble() })

        // 점수 계산
        val score = calculateScore(volumeSpikeRatio, priceSpikePercent, bidImbalance, rsi)

        return PumpSignal(
            market = market,
            volumeSpikeRatio = volumeSpikeRatio,
            priceSpikePercent = priceSpikePercent,
            bidImbalance = bidImbalance,
            rsi = rsi,
            currentPrice = currentPrice,
            tradingValue = tradingValue,
            score = score
        )
    }

    /**
     * 호가창 Imbalance 계산
     *
     * Imbalance = (Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)
     * 양수: 매수 압력, 음수: 매도 압력
     */
    private fun calculateImbalance(market: String): Double {
        val orderbook = bithumbPublicApi.getOrderbook(market)?.firstOrNull() ?: return 0.0
        val units = orderbook.orderbookUnits ?: return 0.0

        val bidVolume = units.sumOf { it.bidSize.toDouble() }
        val askVolume = units.sumOf { it.askSize.toDouble() }

        val total = bidVolume + askVolume
        return if (total > 0) (bidVolume - askVolume) / total else 0.0
    }

    /**
     * 빠른 RSI 계산 (9기간)
     */
    private fun calculateRsi(closes: List<Double>): Double {
        if (closes.size < RSI_PERIOD + 1) return 50.0

        val changes = closes.zipWithNext { prev, curr -> curr - prev }

        var avgGain = changes.take(RSI_PERIOD).filter { it > 0 }.sum() / RSI_PERIOD
        var avgLoss = abs(changes.take(RSI_PERIOD).filter { it < 0 }.sum()) / RSI_PERIOD

        for (i in RSI_PERIOD until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (RSI_PERIOD - 1) + gain) / RSI_PERIOD
            avgLoss = (avgLoss * (RSI_PERIOD - 1) + loss) / RSI_PERIOD
        }

        if (avgLoss == 0.0) return 100.0

        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    /**
     * 펌프 점수 계산 (0-100)
     *
     * 배점:
     * - 거래량 스파이크: 40점 (핵심)
     * - 가격 스파이크: 30점
     * - 호가창 Imbalance: 20점
     * - RSI 조건: 10점
     */
    private fun calculateScore(
        volumeSpikeRatio: Double,
        priceSpikePercent: Double,
        bidImbalance: Double,
        rsi: Double
    ): Int {
        var score = 0

        // 1. 거래량 스파이크 (40점)
        score += when {
            volumeSpikeRatio >= 10.0 -> 40  // 1000%+
            volumeSpikeRatio >= 7.0 -> 35   // 700%+
            volumeSpikeRatio >= 5.0 -> 30   // 500%+ (기준)
            volumeSpikeRatio >= 3.0 -> 20   // 300%+
            volumeSpikeRatio >= 2.0 -> 10   // 200%+
            else -> 0
        }

        // 2. 가격 스파이크 (30점)
        score += when {
            priceSpikePercent >= 10.0 -> 30  // 10%+
            priceSpikePercent >= 5.0 -> 25   // 5%+
            priceSpikePercent >= 3.0 -> 20   // 3%+ (기준)
            priceSpikePercent >= 2.0 -> 15   // 2%+
            priceSpikePercent >= 1.0 -> 10   // 1%+
            else -> 0
        }

        // 3. 호가창 Imbalance (20점)
        score += when {
            bidImbalance >= 0.5 -> 20   // 강한 매수 압력
            bidImbalance >= 0.3 -> 15   // 매수 압력 (기준)
            bidImbalance >= 0.1 -> 10   // 약한 매수 압력
            bidImbalance >= 0.0 -> 5    // 중립
            else -> 0                    // 매도 압력
        }

        // 4. RSI 조건 (10점)
        // 과매수 영역이 아니면서 상승 중일 때 점수
        score += when {
            rsi in 50.0..70.0 -> 10    // 최적 구간
            rsi in 40.0..80.0 -> 5     // 허용 구간
            rsi > 80.0 -> 0            // 과매수 - 진입 위험
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
