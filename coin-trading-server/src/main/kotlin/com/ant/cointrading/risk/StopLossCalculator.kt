package com.ant.cointrading.risk

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.regime.RegimeDetector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * ATR 기반 동적 손절 계산기
 *
 * 퀀트 연구 기반:
 * - 횡보장: ATR 1.5-2x (타이트한 손절)
 * - 추세장: ATR 3-4x (추세를 타기 위해 여유)
 * - 고변동장: ATR 4x+ (노이즈 필터링)
 *
 * 장점:
 * - 시장 상황에 맞는 유연한 손절
 * - 변동성 높을 때 불필요한 손절 방지
 * - 횡보 시 빠른 손실 제한
 */
@Service
class StopLossCalculator(
    private val bithumbPublicApi: BithumbPublicApi,
    private val regimeDetector: RegimeDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 레짐별 ATR 배수 (퀀트 연구 기반)
        const val ATR_MULTIPLIER_SIDEWAYS = 1.5      // 횡보: 타이트
        const val ATR_MULTIPLIER_TREND = 3.0         // 추세: 여유
        const val ATR_MULTIPLIER_HIGH_VOL = 4.0      // 고변동: 넓은 여유

        // 최소/최대 손절 비율 (안전장치)
        const val MIN_STOP_LOSS_PERCENT = 0.5        // 최소 0.5%
        const val MAX_STOP_LOSS_PERCENT = 10.0       // 최대 10%

        // 기본 고정 손절 (폴백)
        const val DEFAULT_STOP_LOSS_PERCENT = 2.0
    }

    /**
     * ATR 기반 동적 손절가 계산
     *
     * @param market 마켓 코드 (예: KRW-BTC)
     * @param entryPrice 진입 가격
     * @param isBuy 매수 포지션 여부
     * @return 손절 결과 (가격, 비율, ATR 정보)
     */
    fun calculate(market: String, entryPrice: Double, isBuy: Boolean = true): StopLossResult {
        return try {
            // 1시간봉 50개로 ATR 계산
            val candles = fetchCandles(market, "minute60", 50)

            if (candles.size < 15) {
                log.warn("[$market] 캔들 데이터 부족 (${candles.size}개), 고정 손절 사용")
                return createFixedStopLoss(market, entryPrice, isBuy)
            }

            // 레짐 분석
            val regimeAnalysis = regimeDetector.detect(candles)

            // ATR 배수 결정
            val atrMultiplier = getAtrMultiplier(regimeAnalysis.regime)

            // 손절 거리 계산 (ATR * 배수)
            val stopDistance = regimeAnalysis.atr * atrMultiplier

            // 손절 비율 계산
            val stopLossPercent = if (entryPrice > 0) {
                (stopDistance / entryPrice) * 100
            } else {
                DEFAULT_STOP_LOSS_PERCENT
            }

            // 안전장치 적용
            val adjustedPercent = stopLossPercent.coerceIn(MIN_STOP_LOSS_PERCENT, MAX_STOP_LOSS_PERCENT)

            // 손절가 계산
            val stopLossPrice = if (isBuy) {
                entryPrice * (1 - adjustedPercent / 100)
            } else {
                entryPrice * (1 + adjustedPercent / 100)
            }

            val result = StopLossResult(
                market = market,
                entryPrice = entryPrice,
                stopLossPrice = stopLossPrice,
                stopLossPercent = adjustedPercent,
                atr = regimeAnalysis.atr,
                atrPercent = regimeAnalysis.atrPercent,
                atrMultiplier = atrMultiplier,
                regime = regimeAnalysis.regime,
                method = StopLossMethod.ATR_DYNAMIC,
                timestamp = Instant.now()
            )

            log.info("[$market] 동적 손절 계산: regime=${regimeAnalysis.regime}, ATR=${String.format("%.2f", regimeAnalysis.atr)}, 배수=${atrMultiplier}x, 손절=${String.format("%.2f", adjustedPercent)}%")

            result
        } catch (e: Exception) {
            log.error("[$market] ATR 손절 계산 실패: ${e.message}, 고정 손절 사용")
            createFixedStopLoss(market, entryPrice, isBuy)
        }
    }

    /**
     * 간소화된 손절 비율 계산 (가격 없이)
     */
    fun calculateStopLossPercent(market: String): Double {
        return try {
            val candles = fetchCandles(market, "minute60", 50)
            if (candles.size < 15) {
                return DEFAULT_STOP_LOSS_PERCENT
            }

            val regimeAnalysis = regimeDetector.detect(candles)
            val atrMultiplier = getAtrMultiplier(regimeAnalysis.regime)

            // ATR%를 손절 비율로 변환
            val stopLossPercent = regimeAnalysis.atrPercent * atrMultiplier

            stopLossPercent.coerceIn(MIN_STOP_LOSS_PERCENT, MAX_STOP_LOSS_PERCENT)
        } catch (e: Exception) {
            log.warn("[$market] 손절 비율 계산 실패: ${e.message}")
            DEFAULT_STOP_LOSS_PERCENT
        }
    }

    /**
     * 레짐별 ATR 배수 반환
     */
    private fun getAtrMultiplier(regime: MarketRegime): Double {
        return when (regime) {
            MarketRegime.SIDEWAYS -> ATR_MULTIPLIER_SIDEWAYS
            MarketRegime.BULL_TREND -> ATR_MULTIPLIER_TREND
            MarketRegime.BEAR_TREND -> ATR_MULTIPLIER_TREND
            MarketRegime.HIGH_VOLATILITY -> ATR_MULTIPLIER_HIGH_VOL
        }
    }

    /**
     * 고정 손절 생성 (폴백)
     */
    private fun createFixedStopLoss(market: String, entryPrice: Double, isBuy: Boolean): StopLossResult {
        val stopLossPrice = if (isBuy) {
            entryPrice * (1 - DEFAULT_STOP_LOSS_PERCENT / 100)
        } else {
            entryPrice * (1 + DEFAULT_STOP_LOSS_PERCENT / 100)
        }

        return StopLossResult(
            market = market,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            stopLossPercent = DEFAULT_STOP_LOSS_PERCENT,
            atr = 0.0,
            atrPercent = 0.0,
            atrMultiplier = 0.0,
            regime = MarketRegime.SIDEWAYS,
            method = StopLossMethod.FIXED,
            timestamp = Instant.now()
        )
    }

    /**
     * 캔들 데이터 조회 및 변환
     */
    private fun fetchCandles(market: String, interval: String, count: Int): List<Candle> {
        val response = bithumbPublicApi.getOhlcv(market, interval, count) ?: return emptyList()

        return response.map { candle ->
            Candle(
                timestamp = Instant.ofEpochMilli(candle.timestamp),
                open = candle.openingPrice,
                high = candle.highPrice,
                low = candle.lowPrice,
                close = candle.tradePrice,
                volume = candle.candleAccTradeVolume
            )
        }.reversed()  // 오래된 순으로 정렬
    }
}

/**
 * 손절 계산 결과
 */
data class StopLossResult(
    val market: String,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val stopLossPercent: Double,
    val atr: Double,
    val atrPercent: Double,
    val atrMultiplier: Double,
    val regime: MarketRegime,
    val method: StopLossMethod,
    val timestamp: Instant
) {
    /**
     * 현재 가격이 손절 트리거되었는지 확인
     */
    fun isTriggered(currentPrice: Double, isBuy: Boolean = true): Boolean {
        return if (isBuy) {
            currentPrice <= stopLossPrice
        } else {
            currentPrice >= stopLossPrice
        }
    }

    /**
     * 손절까지 남은 비율
     */
    fun remainingPercent(currentPrice: Double, isBuy: Boolean = true): Double {
        return if (isBuy) {
            ((currentPrice - stopLossPrice) / currentPrice) * 100
        } else {
            ((stopLossPrice - currentPrice) / currentPrice) * 100
        }
    }
}

/**
 * 손절 계산 방식
 */
enum class StopLossMethod {
    ATR_DYNAMIC,  // ATR 기반 동적 손절
    FIXED         // 고정 비율 손절
}
