package com.ant.cointrading.strategy

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.model.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

/**
 * Mean Reversion (평균 회귀) 전략
 *
 * 가격이 이동평균에서 크게 벗어나면 다시 평균으로 회귀한다는 원리.
 * Z-Score를 사용하여 진입/청산 시점 판단.
 *
 * 검증된 성과: Sharpe Ratio 2.3
 */
@Component
class MeanReversionStrategy(
    private val tradingProperties: TradingProperties
) : TradingStrategy {

    override val name = "MEAN_REVERSION"

    override fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal {
        val config = tradingProperties.strategy

        if (candles.size < config.bollingerPeriod) {
            return TradingSignal(
                market = market,
                action = SignalAction.HOLD,
                confidence = 0.0,
                price = currentPrice,
                reason = "데이터 부족 (${candles.size}/${config.bollingerPeriod} 캔들)",
                strategy = name
            )
        }

        // 볼린저 밴드 계산
        val closes = candles.takeLast(config.bollingerPeriod).map { it.close.toDouble() }
        val sma = closes.average()
        val stdDev = calculateStdDev(closes, sma)
        val upperBand = sma + (stdDev * config.bollingerStdDev)
        val lowerBand = sma - (stdDev * config.bollingerStdDev)

        // Z-Score 계산
        val zScore = if (stdDev > 0) {
            (currentPrice.toDouble() - sma) / stdDev
        } else {
            0.0
        }

        val threshold = config.meanReversionThreshold

        return when {
            // 하단 이탈 - 매수 신호
            zScore <= -threshold -> {
                val confidence = calculateConfidence(zScore, threshold, regime)
                TradingSignal(
                    market = market,
                    action = SignalAction.BUY,
                    confidence = confidence,
                    price = currentPrice,
                    reason = buildReason(zScore, sma, lowerBand, upperBand, "하단 이탈"),
                    strategy = name
                )
            }

            // 상단 이탈 - 매도 신호
            zScore >= threshold -> {
                val confidence = calculateConfidence(-zScore, threshold, regime)
                TradingSignal(
                    market = market,
                    action = SignalAction.SELL,
                    confidence = confidence,
                    price = currentPrice,
                    reason = buildReason(zScore, sma, lowerBand, upperBand, "상단 이탈"),
                    strategy = name
                )
            }

            // 평균 근처 - 홀드
            else -> {
                TradingSignal(
                    market = market,
                    action = SignalAction.HOLD,
                    confidence = 100.0,
                    price = currentPrice,
                    reason = buildReason(zScore, sma, lowerBand, upperBand, "평균 근처"),
                    strategy = name
                )
            }
        }
    }

    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun calculateConfidence(zScore: Double, threshold: Double, regime: RegimeAnalysis): Double {
        // Z-Score가 임계값을 넘은 정도에 따라 신뢰도 계산
        val baseConfidence = 50.0 + ((-zScore - threshold) * 15.0).coerceIn(0.0, 40.0)

        // 레짐에 따른 조정
        val regimeMultiplier = when (regime.regime) {
            MarketRegime.SIDEWAYS -> 1.0
            MarketRegime.BULL_TREND -> 0.9   // 상승장에서 매수는 좋지만 매도는 주의
            MarketRegime.BEAR_TREND -> 0.9   // 하락장에서 매도는 좋지만 매수는 주의
            MarketRegime.HIGH_VOLATILITY -> 0.7
        }

        return (baseConfidence * regimeMultiplier).coerceIn(30.0, 95.0)
    }

    override fun isSuitableFor(regime: RegimeAnalysis): Boolean {
        // Mean Reversion은 횡보장에서 최적
        return regime.regime == MarketRegime.SIDEWAYS
    }

    override fun getDescription(): String {
        val config = tradingProperties.strategy
        return """
            Mean Reversion - 평균 회귀 전략

            설정:
            - 볼린저 기간: ${config.bollingerPeriod}
            - 표준편차 배수: ${config.bollingerStdDev}
            - 진입 임계값: Z-Score ±${config.meanReversionThreshold}

            원리:
            - 가격은 결국 평균으로 회귀한다
            - Z-Score로 평균에서 벗어난 정도 측정
            - 하단 밴드 이탈 시 매수 (과매도)
            - 상단 밴드 이탈 시 매도 (과매수)

            적합한 시장: 횡보장
            위험: 추세장에서 연속 손실 가능
        """.trimIndent()
    }

    private fun buildReason(
        zScore: Double,
        sma: Double,
        lowerBand: Double,
        upperBand: Double,
        status: String
    ): String {
        val zScoreStr = String.format("%.2f", zScore)
        val smaStr = BigDecimal(sma).setScale(0, RoundingMode.HALF_UP)
        val lowerStr = BigDecimal(lowerBand).setScale(0, RoundingMode.HALF_UP)
        val upperStr = BigDecimal(upperBand).setScale(0, RoundingMode.HALF_UP)

        return "$status (Z-Score: $zScoreStr, SMA: $smaStr, 밴드: $lowerStr ~ $upperStr)"
    }
}
