package com.ant.cointrading.stats

import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sharpe Ratio 계산기 (Jim Simons / Renaissance 스타일)
 *
 * 암호화폐 시장의 특성을 반영한 Sharpe Ratio 계산:
 * - 24/7 거래 (252거래일 가정 제거)
 * - 자기상관 고려 (수익률 군집화)
 * - 실제 거래 빈도 기준 연율화
 *
 * Jim Simons 원칙:
 * 1. 절대 Sharpe Ratio > 1.0 목표
 * 2. 자기상관 보정 표준오차 사용
 * 3. 거래 빈도 고려한 연율화
 */
object SharpeRatioCalculator {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 암호화폐에 적합한 Sharpe Ratio 계산
     *
     * @param pnls 개별 거래 손익 리스트 (원)
     * @param capital 초기 자본 (원)
     * @param periodDays 관측 기간 (일)
     * @return Sharpe Ratio
     */
    fun calculate(
        pnls: List<Double>,
        capital: Double,
        periodDays: Int
    ): Double {
        if (pnls.isEmpty() || capital <= 0 || periodDays <= 0) {
            return 0.0
        }

        // 수익률 계산
        val returns = pnls.map { it / capital }

        val avgReturn = returns.average()

        // 표준편차 (자기상관 보정)
        val stdReturn = calculateAutocorrelationAdjustedStd(returns)

        // Sharpe Ratio 계산
        val sharpe = if (stdReturn > 0) {
            // 거래 빈도 기준 연율화 (단순 연율화 사용)
            val annualizationFactor = sqrt(365.0 / periodDays)
            (avgReturn / stdReturn) * annualizationFactor
        } else {
            0.0
        }

        return sharpe
    }

    /**
     * 자기상관 보정 표준편차 (간이 Newey-West 스타일)
     *
     * 암호화폐 수익률은 자기상관이 존재합니다:
     * - 변동성 군집 (큰 변동이 연속 발생)
     * - 수익률이 양/음으로 군집화
     *
     * 간이 보정: 자기상관 계수를 고려한 표준오차
     */
    private fun calculateAutocorrelationAdjustedStd(returns: List<Double>): Double {
        if (returns.size < 2) return 0.0

        val n = returns.size
        val mean = returns.average()

        // 기본 표준편차
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        val basicStd = sqrt(variance)

        if (basicStd == 0.0) return 0.0

        // 1기 후 자기상관 계수 (lag-1 autocorrelation)
        val autocorr = calculateLag1Autocorrelation(returns, mean, basicStd)

        // 자기상관 보정 계수
        // Newey-West 근사: sqrt((1 + 2*sum(autocorr)) / n)
        val adjustmentFactor = sqrt((1.0 + 2.0 * abs(autocorr)) / n)

        // 보정된 표준오차
        val adjustedStd = basicStd * adjustmentFactor * sqrt(n.toDouble())

        return if (adjustedStd > 0) adjustedStd else basicStd
    }

    /**
     * Lag-1 자기상관 계수 계산
     */
    private fun calculateLag1Autocorrelation(
        returns: List<Double>,
        mean: Double,
        std: Double
    ): Double {
        if (returns.size < 2 || std == 0.0) return 0.0

        val n = returns.size - 1
        var numerator = 0.0
        var denominator = 0.0

        for (i in 0 until n) {
            val diff1 = returns[i] - mean
            val diff2 = returns[i + 1] - mean
            numerator += diff1 * diff2
            denominator += diff1 * diff1
        }

        return if (denominator > 0) numerator / denominator else 0.0
    }

    /**
     * 거래 빈도 기준 Sharpe Ratio (단순화 버전)
     *
     * Renaissance 스타일: 거래 횟수를 기준으로 계산
     * 주기적 거래가 아닌 이벤트 기반 거래에 적합
     */
    fun calculateTradeBased(
        pnls: List<Double>,
        capital: Double
    ): Double {
        if (pnls.isEmpty() || capital <= 0) return 0.0

        val returns = pnls.map { it / capital }
        val avgReturn = returns.average()
        val stdReturn = returns.std()

        // 거래 기반 Sharpe: (평균 수익률 / 표준편차) * sqrt(거래 횟수)
        return if (stdReturn > 0) {
            val sharpe = avgReturn / stdReturn * sqrt(pnls.size.toDouble())
            log.debug("거래 기반 Sharpe: ${String.format("%.3f", sharpe)} (거래 ${pnls.size}건)")
            sharpe
        } else {
            0.0
        }
    }

    /**
     * Sharpe Ratio 등급 (Renaissance 기준)
     */
    fun getGrade(sharpe: Double): String {
        return when {
            sharpe >= 2.0 -> "우수 (S급)"
            sharpe >= 1.5 -> "양호 (A급)"
            sharpe >= 1.0 -> "기준 (B급) - Simons 최소 기준"
            sharpe >= 0.5 -> "미달 (C급)"
            else -> "부적합 (D급)"
        }
    }

    /**
     * Jim Simons 해석
     */
    fun interpret(sharpe: Double, totalTrades: Int): String {
        if (totalTrades < 100) {
            return "통계적 유의성 부족 (최소 100건 필요)"
        }

        return when {
            sharpe >= 2.0 -> "Renaissance 수준, 전략 검증 완료"
            sharpe >= 1.0 -> "Simons 기준 달성, 운용 가능"
            sharpe > 0 -> "기준 미달, 전략 개선 필요"
            else -> "음수 Sharpe, 전략 폐기 권장"
        }
    }

    /**
     * List<Double> 확장 함수: 표준편차
     */
    private fun List<Double>.std(): Double {
        if (this.size < 2) return 0.0
        val mean = this.average()
        val variance = this.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
}
