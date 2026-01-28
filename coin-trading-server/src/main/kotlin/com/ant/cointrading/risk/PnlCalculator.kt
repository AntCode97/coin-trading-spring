package com.ant.cointrading.risk

import com.ant.cointrading.config.TradingConstants
import java.math.BigDecimal

/**
 * PnL 계산기 (켄트백 스타일)
 *
 * 모든 PnL 계산 로직을 단일화하여 중복 제거
 */
object PnlCalculator {

    /**
     * PnL 계산 결과
     */
    data class PnlResult(
        val pnlAmount: Double,      // 손익 금액 (KRW)
        val pnlPercent: Double,     // 손익률 (%)
        val entryPrice: Double,    // 진입 가격
        val exitPrice: Double,     // 청산 가격
        val quantity: Double,      // 수량
        val entryFee: Double,      // 진입 수수료
        val exitFee: Double       // 청산 수수료
    )

    /**
     * PnL 계산 (수수료 포함)
     *
     * @param entryPrice 진입 가격
     * @param exitPrice 청산 가격
     * @param quantity 수량
     * @param entryFee 진입 수수료 (기본값: 빗썸 수수료)
     * @param exitFee 청산 수수료 (기본값: 빗썸 수수료)
     * @return PnL 계산 결과
     */
    fun calculate(
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        entryFee: Double = 0.0,
        exitFee: Double = 0.0
    ): PnlResult {
        // 수수료가 없으면 기본값 사용 (진입/청산 금액의 0.04%)
        val actualEntryFee = if (entryFee == 0.0) {
            entryPrice * quantity * TradingConstants.BITHUMB_FEE_RATE.toDouble()
        } else {
            entryFee
        }
        val actualExitFee = if (exitFee == 0.0) {
            exitPrice * quantity * TradingConstants.BITHUMB_FEE_RATE.toDouble()
        } else {
            exitFee
        }

        // PnL = (청산가 - 진입가) * 수량 - 진입수수료 - 청산수수료
        val pnlAmount = (exitPrice - entryPrice) * quantity - actualEntryFee - actualExitFee

        // PnL% = ((청산가 - 진입가) / 진입가) * 100
        val pnlPercent = if (entryPrice > 0) {
            ((exitPrice - entryPrice) / entryPrice) * 100
        } else {
            0.0
        }

        // NaN/Infinity 방지
        val safePnlAmount = if (pnlAmount.isNaN() || pnlAmount.isInfinite()) 0.0 else pnlAmount
        val safePnlPercent = if (pnlPercent.isNaN() || pnlPercent.isInfinite()) 0.0 else pnlPercent

        return PnlResult(
            pnlAmount = safePnlAmount,
            pnlPercent = safePnlPercent,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            quantity = quantity,
            entryFee = actualEntryFee,
            exitFee = actualExitFee
        )
    }

    /**
     * PnL 계산 (수수료 미포함 - 수수료가 별도 계산된 경우)
     *
     * @param entryPrice 진입 가격
     * @param exitPrice 청산 가격
     * @param quantity 수량
     * @return PnL 계산 결과 (수수료 제외)
     */
    fun calculateWithoutFee(
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double
    ): PnlResult {
        // PnL = (청산가 - 진입가) * 수량
        val pnlAmount = (exitPrice - entryPrice) * quantity

        // PnL% = ((청산가 - 진입가) / 진입가) * 100
        val pnlPercent = if (entryPrice > 0) {
            ((exitPrice - entryPrice) / entryPrice) * 100
        } else {
            0.0
        }

        // NaN/Infinity 방지
        val safePnlAmount = if (pnlAmount.isNaN() || pnlAmount.isInfinite()) 0.0 else pnlAmount
        val safePnlPercent = if (pnlPercent.isNaN() || pnlPercent.isInfinite()) 0.0 else pnlPercent

        return PnlResult(
            pnlAmount = safePnlAmount,
            pnlPercent = safePnlPercent,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            quantity = quantity,
            entryFee = 0.0,
            exitFee = 0.0
        )
    }

    /**
     * PnL 계산 (BigDecimal 버전)
     */
    fun calculate(
        entryPrice: BigDecimal,
        exitPrice: BigDecimal,
        quantity: BigDecimal,
        entryFee: BigDecimal = BigDecimal.ZERO,
        exitFee: BigDecimal = BigDecimal.ZERO
    ): PnlResult {
        return calculate(
            entryPrice = entryPrice.toDouble(),
            exitPrice = exitPrice.toDouble(),
            quantity = quantity.toDouble(),
            entryFee = entryFee.toDouble(),
            exitFee = exitFee.toDouble()
        )
    }
}
