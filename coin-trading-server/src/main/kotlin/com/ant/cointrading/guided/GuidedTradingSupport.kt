package com.ant.cointrading.guided

import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.repository.GuidedTradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

private val GUIDED_BITHUMB_FEE_RATE: BigDecimal = TradingConstants.BITHUMB_FEE_RATE
private val GUIDED_BINANCE_FUTURES_FEE_RATE: BigDecimal = BigDecimal("0.0005")

internal data class FeeAdjustedTradePnl(
    val pnlAmount: BigDecimal,
    val pnlPercent: BigDecimal
)

internal fun calculateFeeAdjustedReturnPercent(
    entryPrice: Double,
    exitPrice: Double,
    positionSide: String = GuidedTradeEntity.POSITION_SIDE_LONG,
    feeRate: BigDecimal = GUIDED_BITHUMB_FEE_RATE
): Double {
    if (entryPrice <= 0.0 || exitPrice <= 0.0) return 0.0
    val grossReturn = when (positionSide) {
        GuidedTradeEntity.POSITION_SIDE_SHORT -> (entryPrice - exitPrice) / entryPrice
        else -> (exitPrice - entryPrice) / entryPrice
    }
    val feeReturn = feeRate.toDouble() * (1.0 + exitPrice / entryPrice)
    return (grossReturn - feeReturn) * 100.0
}

internal fun calculateFeeAdjustedRewardPercent(
    entryPrice: Double,
    takeProfitPrice: Double,
    positionSide: String = GuidedTradeEntity.POSITION_SIDE_LONG,
    feeRate: BigDecimal = GUIDED_BITHUMB_FEE_RATE
): Double {
    return max(0.0, calculateFeeAdjustedReturnPercent(entryPrice, takeProfitPrice, positionSide, feeRate))
}

internal fun calculateFeeAdjustedLossPercent(
    entryPrice: Double,
    stopLossPrice: Double,
    positionSide: String = GuidedTradeEntity.POSITION_SIDE_LONG,
    feeRate: BigDecimal = GUIDED_BITHUMB_FEE_RATE
): Double {
    return max(0.0, -calculateFeeAdjustedReturnPercent(entryPrice, stopLossPrice, positionSide, feeRate))
}

internal fun calculateFeeAdjustedRiskRewardRatio(
    entryPrice: Double,
    stopLossPrice: Double,
    takeProfitPrice: Double,
    positionSide: String = GuidedTradeEntity.POSITION_SIDE_LONG,
    feeRate: BigDecimal = GUIDED_BITHUMB_FEE_RATE
): Double {
    val rewardPercent = calculateFeeAdjustedRewardPercent(entryPrice, takeProfitPrice, positionSide, feeRate)
    val lossPercent = calculateFeeAdjustedLossPercent(entryPrice, stopLossPrice, positionSide, feeRate)
    if (lossPercent <= 0.0) return 0.0
    return (rewardPercent / lossPercent).coerceIn(0.0, 3.0)
}

internal fun calculateFeeAdjustedPnl(
    entryPrice: BigDecimal,
    exitPrice: BigDecimal,
    quantity: BigDecimal,
    positionSide: String = GuidedTradeEntity.POSITION_SIDE_LONG,
    feeRate: BigDecimal = GUIDED_BITHUMB_FEE_RATE
): FeeAdjustedTradePnl {
    if (entryPrice <= BigDecimal.ZERO || exitPrice <= BigDecimal.ZERO || quantity <= BigDecimal.ZERO) {
        return FeeAdjustedTradePnl(
            pnlAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            pnlPercent = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
        )
    }

    val entryValue = entryPrice.multiply(quantity)
    val exitValue = exitPrice.multiply(quantity)
    val totalFees = entryValue.add(exitValue)
        .multiply(feeRate)
        .setScale(8, RoundingMode.HALF_UP)
    val grossPnl = when (positionSide) {
        GuidedTradeEntity.POSITION_SIDE_SHORT -> entryValue.subtract(exitValue)
        else -> exitValue.subtract(entryValue)
    }
    val pnlAmount = grossPnl.subtract(totalFees).setScale(2, RoundingMode.HALF_UP)
    val pnlPercent = if (entryValue > BigDecimal.ZERO) {
        pnlAmount.divide(entryValue, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(4, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
    }

    return FeeAdjustedTradePnl(
        pnlAmount = pnlAmount,
        pnlPercent = pnlPercent
    )
}

internal fun guidedFeeRateForVenue(executionVenue: String?): BigDecimal {
    return when (executionVenue) {
        GuidedTradeEntity.EXECUTION_VENUE_BINANCE_FUTURES -> GUIDED_BINANCE_FUTURES_FEE_RATE
        else -> GUIDED_BITHUMB_FEE_RATE
    }
}

internal fun normalizeStrategyCodePrefix(
    strategyCodePrefix: String?,
    defaultPrefix: String? = null
): String? {
    val normalized = strategyCodePrefix
        ?.trim()
        ?.uppercase()
        ?.ifBlank { null }
    return normalized ?: defaultPrefix
}

internal fun strategyCodeMatchesPrefix(strategyCode: String?, normalizedPrefix: String?): Boolean {
    if (normalizedPrefix == null) return true
    return strategyCode
        ?.trim()
        ?.uppercase()
        ?.startsWith(normalizedPrefix) == true
}
