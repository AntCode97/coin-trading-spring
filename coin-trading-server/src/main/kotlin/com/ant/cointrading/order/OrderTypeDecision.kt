package com.ant.cointrading.order

import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.risk.MarketConditionResult

/**
 * 주문 유형 결정 클래스 (퀀트 지식 기반)
 */
class OrderTypeDecision {

    companion object {
        const val HIGH_VOLATILITY_THRESHOLD = 1.5
        const val HIGH_CONFIDENCE_THRESHOLD = 85.0
        const val THIN_LIQUIDITY_THRESHOLD = 5.0
    }

    enum class Type {
        LIMIT,
        MARKET,
        MARKET_IMMEDIATE
    }

    fun decide(
        signal: TradingSignal,
        marketCondition: MarketConditionResult
    ): Type {
        val strategyType = signal.strategy
        val confidence = signal.confidence
        val volatility1Min = marketCondition.volatility1Min
        val liquidityRatio = marketCondition.liquidityRatio ?: 10.0

        if (isUltraShortTermStrategy(strategyType)) {
            return Type.MARKET_IMMEDIATE
        }

        val meetsHighVolatility = (volatility1Min ?: 0.0) > HIGH_VOLATILITY_THRESHOLD
        val meetsHighConfidence = confidence > HIGH_CONFIDENCE_THRESHOLD
        val meetsThinLiquidity = liquidityRatio < THIN_LIQUIDITY_THRESHOLD

        val conditionCount = listOf(meetsHighVolatility, meetsHighConfidence, meetsThinLiquidity).count { it }

        return when {
            conditionCount >= 2 -> Type.MARKET
            conditionCount >= 1 -> Type.LIMIT
            else -> Type.LIMIT
        }
    }

    private fun isUltraShortTermStrategy(strategyType: String): Boolean {
        return strategyType in listOf(
            "ORDER_BOOK_IMBALANCE",
            "MOMENTUM",
            "BREAKOUT"
        )
    }
}
