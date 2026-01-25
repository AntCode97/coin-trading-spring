package com.ant.cointrading.backtesting

/**
 * ATR 기반 동적 손절 계산기
 *
 * 레짐별 ATR 배수 조정:
 * - 횡보: 1.5x (타이트)
 * - 추세: 3.0x (여유)
 * - 고변동: 4.0x (넓은 여유)
 */
object DynamicStopLossCalculator {

    enum class MarketRegime {
        SIDEWAYS,   // 횡보
        TREND,      // 추세
        HIGH_VOLATILITY  // 고변동
    }

    /**
     * ATR 배수 반환 (레짐별)
     */
    fun getAtrMultiplier(regime: MarketRegime): Double {
        return when (regime) {
            MarketRegime.SIDEWAYS -> 1.5
            MarketRegime.TREND -> 3.0
            MarketRegime.HIGH_VOLATILITY -> 4.0
        }
    }

    /**
     * 동적 손절가 계산
     *
     * @param entryPrice 진입 가격
     * @param atr ATR 값
     * @param isBuy 매수 포지션 여부
     * @param regime 시장 레짐
     * @return 손절가
     */
    fun calculateStopLoss(
        entryPrice: Double,
        atr: Double,
        isBuy: Boolean,
        regime: MarketRegime = MarketRegime.TREND
    ): Double {
        val multiplier = getAtrMultiplier(regime)
        val stopDistance = atr * multiplier

        return if (isBuy) {
            entryPrice - stopDistance  // 매수: 진입가 아래
        } else {
            entryPrice + stopDistance  // 매도: 진입가 위
        }
    }

    /**
     * 손절 비율(%) 계산
     */
    fun calculateStopLossPercent(
        entryPrice: Double,
        stopLoss: Double
    ): Double {
        return ((stopLoss - entryPrice) / entryPrice) * 100
    }

    /**
     * ATR 퍼센트 계산 (진입가 대비 ATR 비율)
     */
    fun calculateAtrPercent(entryPrice: Double, atr: Double): Double {
        return (atr / entryPrice) * 100
    }
}
