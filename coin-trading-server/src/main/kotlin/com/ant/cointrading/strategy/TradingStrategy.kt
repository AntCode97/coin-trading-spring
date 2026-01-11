package com.ant.cointrading.strategy

import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.model.RegimeAnalysis
import java.math.BigDecimal

/**
 * 트레이딩 전략 인터페이스
 */
interface TradingStrategy {

    val name: String

    /**
     * 캔들 데이터를 분석하여 트레이딩 신호 생성
     */
    fun analyze(
        market: String,
        candles: List<Candle>,
        currentPrice: BigDecimal,
        regime: RegimeAnalysis
    ): TradingSignal

    /**
     * 전략이 현재 레짐에 적합한지 확인
     */
    fun isSuitableFor(regime: RegimeAnalysis): Boolean

    /**
     * 전략 설명
     */
    fun getDescription(): String
}
