package com.ant.cointrading.guided

import com.ant.cointrading.config.TradingConstants
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

private val GUIDED_BITHUMB_FEE_RATE: BigDecimal = TradingConstants.BITHUMB_FEE_RATE

internal data class FeeAdjustedTradePnl(
    val pnlAmount: BigDecimal,
    val pnlPercent: BigDecimal
)

internal fun calculateFeeAdjustedReturnPercent(entryPrice: Double, exitPrice: Double): Double {
    if (entryPrice <= 0.0 || exitPrice <= 0.0) return 0.0
    val grossReturn = (exitPrice - entryPrice) / entryPrice
    val feeReturn = GUIDED_BITHUMB_FEE_RATE.toDouble() * (1.0 + exitPrice / entryPrice)
    return (grossReturn - feeReturn) * 100.0
}

internal fun calculateFeeAdjustedRewardPercent(entryPrice: Double, takeProfitPrice: Double): Double {
    return max(0.0, calculateFeeAdjustedReturnPercent(entryPrice, takeProfitPrice))
}

internal fun calculateFeeAdjustedLossPercent(entryPrice: Double, stopLossPrice: Double): Double {
    return max(0.0, -calculateFeeAdjustedReturnPercent(entryPrice, stopLossPrice))
}

internal fun calculateFeeAdjustedRiskRewardRatio(
    entryPrice: Double,
    stopLossPrice: Double,
    takeProfitPrice: Double
): Double {
    val rewardPercent = calculateFeeAdjustedRewardPercent(entryPrice, takeProfitPrice)
    val lossPercent = calculateFeeAdjustedLossPercent(entryPrice, stopLossPrice)
    if (lossPercent <= 0.0) return 0.0
    return (rewardPercent / lossPercent).coerceIn(0.0, 3.0)
}

internal fun calculateFeeAdjustedPnl(
    entryPrice: BigDecimal,
    exitPrice: BigDecimal,
    quantity: BigDecimal
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
        .multiply(GUIDED_BITHUMB_FEE_RATE)
        .setScale(8, RoundingMode.HALF_UP)
    val pnlAmount = exitValue.subtract(entryValue)
        .subtract(totalFees)
        .setScale(2, RoundingMode.HALF_UP)
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
