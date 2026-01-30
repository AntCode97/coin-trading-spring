package com.ant.cointrading.stats

import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bootstrap 통계 검증 (Jim Simons/Renaissance 스타일)
 *
 * Renaissance Technologies의 엄격한 통계 검증:
 * - Bootstrap resampling으로 Sharpe Ratio 신뢰구간 추정
 * - 과거 수익률 재표본추출로 전략 견고성 검증
 * - P-value 계산으로 우연성 배제
 *
 * 참고: Efron & Tibshirani (1993) Bootstrap Methods
 */
object BootstrapValidator {

    private val log = LoggerFactory.getLogger(javaClass)

    private const val DEFAULT_BOOTSTRAP_SAMPLES = 1000
    private const val CONFIDENCE_LEVEL = 0.95

    /**
     * Sharpe Ratio Bootstrap 신뢰구간
     *
     * @param pnls 개별 거래 손익 리스트
     * @param capital 초기 자본
     * @param periodDays 관측 기간 (일)
     * @param bootstrapSamples 리표본추출 횟수
     * @return 95% 신뢰구간 (lower, upper)
     */
    fun sharpeRatioConfidenceInterval(
        pnls: List<Double>,
        capital: Double,
        periodDays: Int,
        bootstrapSamples: Int = DEFAULT_BOOTSTRAP_SAMPLES
    ): ConfidenceInterval {
        if (pnls.isEmpty() || capital <= 0 || periodDays <= 0) {
            return ConfidenceInterval(0.0, 0.0, 0.0, false)
        }

        val originalSharpe = SharpeRatioCalculator.calculate(pnls, capital, periodDays)
        val bootstrappedSharpe = mutableListOf<Double>()

        repeat(bootstrapSamples) {
            // Resample with replacement
            val resampledPnls = resampleWithReplacement(pnls)
            val sharpe = SharpeRatioCalculator.calculate(resampledPnls, capital, periodDays)
            bootstrappedSharpe.add(sharpe)
        }

        bootstrappedSharpe.sort()

        val alpha = (1.0 - CONFIDENCE_LEVEL) / 2.0
        val lowerIndex = (alpha * bootstrapSamples).toInt().coerceAtLeast(0)
        val upperIndex = ((1.0 - alpha) * bootstrapSamples).toInt().coerceAtMost(bootstrapSamples - 1)

        val lower = bootstrappedSharpe[lowerIndex]
        val upper = bootstrappedSharpe[upperIndex]
        val width = upper - lower

        // Jim Simons 기준: Sharpe > 0이 하한보다 크거나 같아야 함
        val isValid = lower >= 0.0

        log.info("Bootstrap Sharpe: ${String.format("%.3f", originalSharpe)} " +
                "95% CI [${String.format("%.3f", lower)}, ${String.format("%.3f", upper)}] " +
                "Valid=$isValid")

        return ConfidenceInterval(originalSharpe, lower, upper, isValid)
    }

    /**
     * P-value 계산 (Null Hypothesis: Sharpe <= 0)
     *
     * H0: 전략 수익률은 우연 (Sharpe <= 0)
     * H1: 전략이 통계적으로 유의미 (Sharpe > 0)
     */
    fun calculatePValue(
        pnls: List<Double>,
        capital: Double,
        periodDays: Int,
        bootstrapSamples: Int = DEFAULT_BOOTSTRAP_SAMPLES
    ): PValueResult {
        if (pnls.isEmpty() || capital <= 0 || periodDays <= 0) {
            return PValueResult(1.0, false, "데이터 부족")
        }

        val originalSharpe = SharpeRatioCalculator.calculate(pnls, capital, periodDays)

        // Null Hypothesis: 평균 수익률 = 0
        // 수익률을 평균 0으로 조정하여 resampling
        val returns = pnls.map { it / capital }
        val meanReturn = returns.average()
        val zeroMeanReturns = returns.map { it - meanReturn }

        val nullDistribution = mutableListOf<Double>()

        repeat(bootstrapSamples) {
            val resampledReturns = resampleWithReplacement(zeroMeanReturns)
            val nullPnls = resampledReturns.map { it * capital }
            val nullSharpe = SharpeRatioCalculator.calculate(nullPnls, capital, periodDays)
            nullDistribution.add(nullSharpe)
        }

        // P-value: P(Sharpe_null >= Sharpe_observed)
        val count = nullDistribution.count { it >= originalSharpe }
        val pValue = count.toDouble() / bootstrapSamples

        // 유의수준 5%
        val isSignificant = pValue < 0.05

        val interpretation = when {
            pValue < 0.01 -> "매우 유의미 (p < 0.01), Renaissance 기준 통과"
            pValue < 0.05 -> "유의미 (p < 0.05), Simons 기준 통과"
            pValue < 0.10 -> "약한 증거 (p < 0.10), 추가 데이터 필요"
            else -> "우연일 가능성 높음 (p >= 0.10), 전략 폐기 권장"
        }

        log.info("P-value: ${String.format("%.4f", pValue)} " +
                "Significant=$isSignificant ($interpretation)")

        return PValueResult(pValue, isSignificant, interpretation)
    }

    /**
     * Kelly Criterion Bootstrap 신뢰구간
     */
    fun kellyConfidenceInterval(
        pnls: List<Double>,
        bootstrapSamples: Int = DEFAULT_BOOTSTRAP_SAMPLES
    ): ConfidenceInterval {
        if (pnls.isEmpty()) {
            return ConfidenceInterval(0.0, 0.0, 0.0, false)
        }

        val originalKelly = calculateKelly(pnls)
        val bootstrappedKelly = mutableListOf<Double>()

        repeat(bootstrapSamples) {
            val resampled = resampleWithReplacement(pnls)
            bootstrappedKelly.add(calculateKelly(resampled))
        }

        bootstrappedKelly.sort()

        val alpha = (1.0 - CONFIDENCE_LEVEL) / 2.0
        val lowerIndex = (alpha * bootstrapSamples).toInt().coerceAtLeast(0)
        val upperIndex = ((1.0 - alpha) * bootstrapSamples).toInt().coerceAtMost(bootstrapSamples - 1)

        val lower = bootstrappedKelly[lowerIndex]
        val upper = bootstrappedKelly[upperIndex]
        val width = upper - lower

        // Jim Simons: 하한이 양수여야 함
        val isValid = lower > 0.0

        return ConfidenceInterval(originalKelly, lower, upper, isValid)
    }

    /**
     * Bootstrap 검증 리포트
     */
    fun generateValidationReport(
        strategyName: String,
        pnls: List<Double>,
        capital: Double,
        periodDays: Int
    ): BootstrapReport {
        if (pnls.size < 30) {
            return BootstrapReport(
                strategyName = strategyName,
                sampleSize = pnls.size,
                sharpeCI = ConfidenceInterval(0.0, 0.0, 0.0, false),
                kellyCI = ConfidenceInterval(0.0, 0.0, 0.0, false),
                pValue = PValueResult(1.0, false, "표본 부족 (최소 30건 필요)"),
                isValid = false,
                recommendation = "데이터 부족으로 검증 불가. 최소 30건 거래 필요."
            )
        }

        val sharpeCI = sharpeRatioConfidenceInterval(pnls, capital, periodDays)
        val kellyCI = kellyConfidenceInterval(pnls)
        val pValue = calculatePValue(pnls, capital, periodDays)

        val isValid = sharpeCI.isValid && kellyCI.isValid && pValue.isSignificant

        val recommendation = when {
            !isValid -> "Bootstrap 검증 실패. 전략 폐기 권장."
            sharpeCI.lower < 1.0 -> "Sharpe 95% CI 하한 < 1.0. Simons 기준 미달."
            kellyCI.lower <= 0 -> "Kelly 95% CI 하한 <= 0. 리스크 과다."
            else -> "Renaissance 기준 통과. 실전 운용 가능."
        }

        return BootstrapReport(
            strategyName = strategyName,
            sampleSize = pnls.size,
            sharpeCI = sharpeCI,
            kellyCI = kellyCI,
            pValue = pValue,
            isValid = isValid,
            recommendation = recommendation
        )
    }

    /**
     * Replacement으로 리표본추출
     */
    private fun resampleWithReplacement(data: List<Double>): List<Double> {
        return List(data.size) { data.random() }
    }

    /**
     * Kelly Criterion 계산
     */
    private fun calculateKelly(pnls: List<Double>): Double {
        if (pnls.isEmpty()) return 0.0

        val winning = pnls.filter { it > 0 }
        val losing = pnls.filter { it < 0 }

        if (winning.isEmpty() || losing.isEmpty()) return 0.0

        val winRate = winning.size.toDouble() / pnls.size
        val avgWin = winning.average()
        val avgLoss = abs(losing.average())

        if (avgLoss == 0.0) return 0.0

        val b = avgWin / avgLoss
        val p = winRate
        val q = 1.0 - winRate

        return (b * p - q) / b
    }
}

/**
 * 신뢰구간
 */
data class ConfidenceInterval(
    val estimate: Double,      // 추정값 (원래 Sharpe/Kelly)
    val lower: Double,         // 하한
    val upper: Double,         // 상한
    val isValid: Boolean       // Jim Simons 기준 통과 여부
) {
    val width: Double get() = upper - lower
}

/**
 * P-value 결과
 */
data class PValueResult(
    val pValue: Double,        // P-value
    val isSignificant: Boolean, // 유의성 (p < 0.05)
    val interpretation: String  // 해석
)

/**
 * Bootstrap 검증 리포트
 */
data class BootstrapReport(
    val strategyName: String,
    val sampleSize: Int,
    val sharpeCI: ConfidenceInterval,
    val kellyCI: ConfidenceInterval,
    val pValue: PValueResult,
    val isValid: Boolean,
    val recommendation: String
)
