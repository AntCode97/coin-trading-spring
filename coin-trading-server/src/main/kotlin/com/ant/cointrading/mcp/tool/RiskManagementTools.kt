package com.ant.cointrading.mcp.tool

import com.ant.cointrading.risk.PositionSizer
import com.ant.cointrading.risk.StopLossCalculator
import com.ant.cointrading.risk.StopLossResult
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 리스크 관리 MCP 도구
 *
 * quant-trading 스킬 기반 리스크 관리 기능 제공:
 * - Kelly Criterion 기반 포지션 사이징
 * - ATR 기반 동적 손절
 * - 리스크 리워드 비율 계산
 */
@Component
class RiskManagementTools(
    private val positionSizer: PositionSizer,
    private val stopLossCalculator: StopLossCalculator
) {

    // ========================================
    // 응답 Data Classes
    // ========================================

    /** Kelly Criterion 결과 */
    data class KellyCriterionResult(
        val winRate: Double,           // 승률
        val riskReward: Double,        // 손익비
        val kellyPercent: Double,      // Kelly 비율 (%)
        val recommendedAmount: Double, // 권장 투자 금액 (KRW)
        val riskPercent: Double,       // 리스크 비율 (%)
        val riskAmount: Double,        // 리스크 금액 (KRW)
        val reason: String             // 설명
    )

    /** 포지션 사이징 결과 */
    data class PositionSizingResult(
        val capital: Double,           // 총 자본
        val confidence: Double,        // 신뢰도 (0~100)
        val winRate: Double,           // 적용 승률
        val riskReward: Double,        // 적용 손익비
        val positionAmount: Double,    // 포지션 금액 (KRW)
        val positionPercent: Double,   // 포지션 비율 (%)
        val riskPercent: Double,       // 리스크 비율 (%)
        val reason: String             // 설명
    )

    /** 리스크 체크 결과 */
    data class RiskCheckResult(
        val isAcceptable: Boolean,     // 리스크 허용 여부
        val riskPercent: Double,       // 현재 리스크 비율 (%)
        val maxRiskPercent: Double,    // 최대 허용 리스크 비율 (%)
        val recommendation: String     // 권장사항
    )

    // ========================================
    // Kelly Criterion 도구
    // ========================================

    @McpTool(description = """
        Kelly Criterion 기반 포지션 크기를 계산합니다.

        Kelly 공식: f* = (bp - q) / b
        - f* = 최적 베팅 비율
        - b = 손익비 (Risk-Reward Ratio)
        - p = 승률
        - q = 1 - p

        Half Kelly 사용 (안전성): kelly / 2
    """)
    fun calculateKellyPosition(
        @McpToolParam(description = "승률 (0.0 ~ 1.0, 예: 0.7 = 70%)") winRate: Double,
        @McpToolParam(description = "손익비 (예: 2.0 = 2:1)") riskReward: Double,
        @McpToolParam(description = "총 자본 (KRW)") capital: Double
    ): KellyCriterionResult {
        val kellyPercent = positionSizer.calculateKellyPercent(winRate, riskReward)
        val recommendedAmount = positionSizer.calculatePositionSize(winRate, riskReward, capital)

        val riskPercent = kellyPercent / 2  // Stop loss 2% 가정
        val riskAmount = capital * (riskPercent / 100)

        val reason = when {
            winRate < 0.4 -> "낮은 승률: 기본값 사용 (1%)"
            winRate > 0.7 -> "높은 승률: 공격적 포지션 가능"
            riskReward < 1.5 -> "낮은 손익비: 보수적 포지션"
            else -> "정상 범위: Kelly Criterion 적용"
        }

        return KellyCriterionResult(
            winRate = winRate,
            riskReward = riskReward,
            kellyPercent = kellyPercent,
            recommendedAmount = recommendedAmount,
            riskPercent = riskPercent,
            riskAmount = riskAmount,
            reason = reason
        )
    }

    @McpTool(description = """
        신뢰도별 Kelly 파라미터를 추천합니다.

        quant-trading 스킬 기반:
        - 90%+ 신뢰도: R:R 1.5:1 (공격적)
        - 70-89%: R:R 2:1 (균형적)
        - 50-69%: R:R 3:1 (보수적)
    """)
    fun getKellyParamsByConfidence(
        @McpToolParam(description = "신뢰도 (0~100)") confidence: Double
    ): com.ant.cointrading.risk.KellyParams {
        return positionSizer.getRecommendedParams(confidence)
    }

    // ========================================
    // 포지션 사이징 도구
    // ========================================

    @McpTool(description = """
        자본과 신뢰도를 고려하여 포지션 크기를 계산합니다.

        Kelly Criterion과 리스크 한도를 고려한 포지션 사이징.
    """)
    fun calculatePositionSize(
        @McpToolParam(description = "총 자본 (KRW)") capital: Double,
        @McpToolParam(description = "신뢰도 (0~100, 컨플루언스 점수 등)") confidence: Double,
        @McpToolParam(description = "손절 비율 (%)") stopLossPercent: Double
    ): PositionSizingResult {
        // Kelly 파라미터 추천
        val kellyParams = positionSizer.getRecommendedParams(confidence)

        // Kelly 비율 계산
        val kellyPercent = positionSizer.calculateKellyPercent(
            kellyParams.winRate,
            kellyParams.riskReward
        )

        // 포지션 금액 계산
        var positionAmount = capital * (kellyPercent / 100)

        // 리스크 한도 체크 (2%)
        positionAmount = positionSizer.adjustForRiskLimit(
            positionAmount,
            stopLossPercent,
            capital,
            maxRiskPercent = 2.0
        )

        val positionPercent = (positionAmount / capital) * 100
        val riskPercent = (positionAmount * (stopLossPercent / 100) / capital) * 100

        return PositionSizingResult(
            capital = capital,
            confidence = confidence,
            winRate = kellyParams.winRate,
            riskReward = kellyParams.riskReward,
            positionAmount = BigDecimal(positionAmount).setScale(0, RoundingMode.HALF_UP).toDouble(),
            positionPercent = BigDecimal(positionPercent).setScale(2, RoundingMode.HALF_UP).toDouble(),
            riskPercent = BigDecimal(riskPercent).setScale(2, RoundingMode.HALF_UP).toDouble(),
            reason = kellyParams.reason
        )
    }

    @McpTool(description = """
        리스크 허용 여부를 확인합니다.

        포지션 금액과 손절 비율을 고려하여 리스크가 허용 범위 내인지 확인.
    """)
    fun checkRiskAcceptable(
        @McpToolParam(description = "포지션 금액 (KRW)") positionAmount: Double,
        @McpToolParam(description = "손절 비율 (%)") stopLossPercent: Double,
        @McpToolParam(description = "총 자본 (KRW)") capital: Double,
        @McpToolParam(description = "최대 허용 리스크 비율 (%), 기본 2%") maxRiskPercent: Double = 2.0
    ): RiskCheckResult {
        val isAcceptable = positionSizer.isRiskAcceptable(
            positionAmount,
            stopLossPercent,
            capital,
            maxRiskPercent
        )

        val riskPercent = positionSizer.calculateRiskAmount(positionAmount, stopLossPercent) / capital * 100

        val recommendation = when {
            !isAcceptable && riskPercent > maxRiskPercent * 1.5 ->
                "리스크가 크게 초과했습니다. 포지션을 ${(maxRiskPercent / (stopLossPercent / 100) / capital * 100).toInt()}% 이하로 축소하세요."
            !isAcceptable ->
                "리스크가 초과했습니다. 포지션을 축소하거나 손절을 타이트하게 하세요."
            riskPercent > maxRiskPercent * 0.8 ->
                "리스크가 허용 한도에 근접했습니다. 주의하세요."
            else ->
                "리스크가 허용 범위 내입니다."
        }

        return RiskCheckResult(
            isAcceptable = isAcceptable,
            riskPercent = BigDecimal(riskPercent).setScale(2, RoundingMode.HALF_UP).toDouble(),
            maxRiskPercent = maxRiskPercent,
            recommendation = recommendation
        )
    }

    // ========================================
    // 손절 도구
    // ========================================

    @McpTool(description = """
        ATR 기반 동적 손절가를 계산합니다.

        레짐별 ATR 배수 적용:
        - 횡보: 1.5x (타이트)
        - 추세: 3.0x (여유)
        - 고변동: 4.0x (넓은 여유)
    """)
    fun calculateDynamicStopLoss(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String,
        @McpToolParam(description = "진입 가격 (KRW)") entryPrice: Double,
        @McpToolParam(description = "매수 포지션 여부") isBuy: Boolean = true
    ): StopLossResult {
        return stopLossCalculator.calculate(market, entryPrice, isBuy)
    }

    @McpTool(description = """
        ATR 기반 손절 비율만 조회합니다 (가격 없이).
    """)
    fun getStopLossPercent(
        @McpToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String
    ): Double {
        return stopLossCalculator.calculateStopLossPercent(market)
    }

    // ========================================
    // 계산기 도구
    // ========================================

    @McpTool(description = """
        포지션 수량을 계산합니다.
    """)
    fun calculateQuantity(
        @McpToolParam(description = "투자 금액 (KRW)") amount: Double,
        @McpToolParam(description = "현재가 (KRW)") price: Double
    ): Double {
        return positionSizer.calculateQuantity(amount, price)
    }

    @McpTool(description = """
        손익비(Risk-Reward Ratio)를 기반으로 익절가를 계산합니다.
    """)
    fun calculateTakeProfit(
        @McpToolParam(description = "진입 가격 (KRW)") entryPrice: Double,
        @McpToolParam(description = "손절 비율 (%)") stopLossPercent: Double,
        @McpToolParam(description = "손익비 (예: 2.0 = 2:1)") riskReward: Double,
        @McpToolParam(description = "매수 포지션 여부") isBuy: Boolean = true
    ): Double {
        val stopLossAmount = entryPrice * (stopLossPercent / 100)
        val takeProfitAmount = stopLossAmount * riskReward

        return if (isBuy) {
            entryPrice + takeProfitAmount
        } else {
            entryPrice - takeProfitAmount
        }
    }
}
