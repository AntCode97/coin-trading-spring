package com.ant.cointrading.regime

import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Market Regime Model (Jim Simons / Renaissance 스타일)
 *
 * Renaissance Technologies의 시장 레짐 모델링:
 * 1. Hidden Markov Model (HMM)로 레짐 식별
 * 2. Baum-Welch 알고리즘으로 파라미터 학습
 * 3. Regime-dependent Kelly Criterion (레짐별 다른 베팅)
 * 4. Regime stability score로 신뢰도 측정
 *
 * 은닉 상태 (Hidden States):
 * - BULL (상승 추세): 양의 수익률, 낮은 변동성
 * - BEAR (하락 추세): 음의 수익률, 높은 변동성
 * - SIDEWAYS (횡보): 작은 변동, 레인지)
 *
 * Jim Simons 원칙:
 * - "레짐이 안정적일 때만 베팅한다"
 * - "레짐 전환 시 포지션을 축소한다"
 * - "각 레짐에서의 Kelly Criterion을 따로 계산한다"
 */
@Component
class RegimeModel(
    private val hmmDetector: HmmRegimeDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 레짐 상태
        const val REGIME_BULL = 0
        const val REGIME_BEAR = 1
        const val REGIME_SIDEWAYS = 2
        const val NUM_REGIMES = 3

        // 관측값 이산화 (수익률 5레벨)
        const val OBS_STRONG_NEG = 0   // < -2%
        const val OBS_NEG = 1          // -2% ~ -0.5%
        const val OBS_NEUTRAL = 2      // -0.5% ~ +0.5%
        const val OBS_POS = 3          // +0.5% ~ +2%
        const val OBS_STRONG_POS = 4   // > +2%

        // 최소 학습 데이터
        const val MIN_OBSERVATIONS_FOR_TRAINING = 100

        // Log epsilon
        private const val LOG_EPSILON = -100.0
    }

    // HMM 파라미터 (학습 가능)
    private var logPi: DoubleArray = DoubleArray(NUM_REGIMES)  // 초기 상태 확률
    private var logA: Array<DoubleArray> = Array(NUM_REGIMES) { DoubleArray(NUM_REGIMES) }  // 전이 확률
    private var logB: Array<DoubleArray> = Array(NUM_REGIMES) { DoubleArray(5) }  // 방출 확률

    // 학습 완료 여부
    @Volatile private var isTrained = false

    init {
        // 초기 파라미터 설정 (Renaissance 경험치)
        initializeParameters()
    }

    /**
     * 초기 파라미터 설정 (Renaissance 경험치)
     */
    private fun initializeParameters() {
        // 초기 상태 확률 (균등 분포)
        logPi = DoubleArray(NUM_REGIMES) { ln(1.0 / NUM_REGIMES) }

        // 전이 확률 (Renaissance 경험치: 레짐 지속성)
        logA = arrayOf(
            // BULL → BULL, BEAR, SIDEWAYS
            doubleArrayOf(0.70, 0.15, 0.15).map { ln(it) }.toDoubleArray(),
            // BEAR → BULL, BEAR, SIDEWAYS
            doubleArrayOf(0.20, 0.60, 0.20).map { ln(it) }.toDoubleArray(),
            // SIDEWAYS → BULL, BEAR, SIDEWAYS
            doubleArrayOf(0.25, 0.25, 0.50).map { ln(it) }.toDoubleArray()
        )

        // 방출 확률 (각 레짐의 특성)
        logB = arrayOf(
            // BULL: 양의 수익률에 높은 확률
            doubleArrayOf(0.05, 0.10, 0.20, 0.35, 0.30).map { ln(it) }.toDoubleArray(),
            // BEAR: 음의 수익률에 높은 확률
            doubleArrayOf(0.30, 0.35, 0.20, 0.10, 0.05).map { ln(it) }.toDoubleArray(),
            // SIDEWAYS: 중립에 높은 확률
            doubleArrayOf(0.10, 0.20, 0.40, 0.20, 0.10).map { ln(it) }.toDoubleArray()
        )
    }

    /**
     * 파라미터 학습 (Baum-Welch 알고리즘)
     *
     * Jim Simons: "데이터에서 파라미터를 학습해라, 경험치만 믿지 마라"
     *
     * @param candles 학습할 캔들 데이터
     * @param maxIterations 최대 반복 횟수
     * @param convergence 수렴 기준
     */
    fun train(
        candles: List<Candle>,
        maxIterations: Int = 100,
        convergence: Double = 1e-6
    ): RegimeTrainingResult {
        if (candles.size < MIN_OBSERVATIONS_FOR_TRAINING) {
            log.warn("데이터 부족: ${candles.size}개 (최소 ${MIN_OBSERVATIONS_FOR_TRAINING}개 필요)")
            return RegimeTrainingResult(
                success = false,
                reason = "데이터 부족",
                iterations = 0,
                logLikelihood = Double.NaN
            )
        }

        log.info("=== HMM 파라미터 학습 시작 ===")
        log.info("데이터: ${candles.size}개, 최대 반복: $maxIterations")

        // 관측 시퀀스 추출
        val observations = extractObservations(candles).toIntArray()
        val T = observations.size

        var oldLogLikelihood = Double.NEGATIVE_INFINITY
        val startTime = System.currentTimeMillis()

        for (iter in 0 until maxIterations) {
            // E-step: Forward-Backward 알고리즘
            val (alpha, beta, gamma, xi, c) = forwardBackward(observations, T)

            // M-step: 파라미터 업데이트
            updateParameters(gamma, xi, observations, T)

            // 로그 우도 계산 (스케일링 계수 포함)
            val logLikelihood = calculateLogLikelihood(alpha, c, T)

            // 수렴 확인
            val improvement = abs(logLikelihood - oldLogLikelihood)
            if (improvement < convergence) {
                log.info("수렴: iter=$iter, logLikelihood=${String.format("%.4f", logLikelihood)}")
                break
            }

            oldLogLikelihood = logLikelihood

            if (iter % 10 == 0) {
                log.debug("Iter $iter: logLikelihood=${String.format("%.4f", logLikelihood)}, improvement=${String.format("%.6f", improvement)}")
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        isTrained = true

        log.info("=== 학습 완료 ===")
        log.info("최종 log-likelihood: ${String.format("%.4f", oldLogLikelihood)}")
        log.info("소요 시간: ${elapsed}ms")

        return RegimeTrainingResult(
            success = true,
            reason = "학습 완료",
            iterations = maxIterations,
            logLikelihood = oldLogLikelihood,
            elapsedMs = elapsed
        )
    }

    /**
     * Forward-Backward 알고리즘 (E-step)
     */
    private fun forwardBackward(
        observations: IntArray,
        T: Int
    ): ForwardBackwardResult {
        // Forward 변수
        val alpha = Array(T) { DoubleArray(NUM_REGIMES) { LOG_EPSILON } }
        val c = DoubleArray(T) { 1.0 }  // 스케일링 계수

        // Forward 초기화
        for (i in 0 until NUM_REGIMES) {
            alpha[0][i] = logPi[i] + logB[i][observations[0]]
        }
        scaleAlpha(alpha, c, 0)

        // Forward 재귀 (log-sum-exp로 확률 합 계산)
        for (t in 1 until T) {
            for (j in 0 until NUM_REGIMES) {
                // alpha[t-1][i] + logA[i][j]의 합을 log-space에서 계산
                val logProb = DoubleArray(NUM_REGIMES) { alpha[t - 1][it] + logA[it][j] }
                alpha[t][j] = logB[j][observations[t]] + logSumExp(logProb)
            }
            scaleAlpha(alpha, c, t)
        }

        // Backward 변수
        val beta = Array(T) { DoubleArray(NUM_REGIMES) { LOG_EPSILON } }

        // Backward 초기화
        for (i in 0 until NUM_REGIMES) {
            beta[T - 1][i] = 0.0  // ln(1.0) = 0
        }
        scaleBeta(beta, c, T - 1)

        // Backward 재귀 (log-sum-exp로 확률 합 계산)
        for (t in T - 2 downTo 0) {
            for (i in 0 until NUM_REGIMES) {
                // logA[i][j] + logB[j][obs[t+1]] + beta[t+1][j]의 합을 log-space에서 계산
                val logProb = DoubleArray(NUM_REGIMES) {
                    logA[i][it] + logB[it][observations[t + 1]] + beta[t + 1][it]
                }
                beta[t][i] = logSumExp(logProb)
            }
            scaleBeta(beta, c, t)
        }

        // Gamma 계산 (각 시점에서 상태 확률)
        val gamma = Array(T) { DoubleArray(NUM_REGIMES) }
        for (t in 0 until T) {
            val logSum = logSumExp(alpha[t], beta[t])
            for (i in 0 until NUM_REGIMES) {
                gamma[t][i] = alpha[t][i] + beta[t][i] - logSum
            }
        }

        // Xi 계산 (연속 상태 쌍 확률)
        val xi = Array(T - 1) { Array(NUM_REGIMES) { DoubleArray(NUM_REGIMES) } }
        for (t in 0 until T - 1) {
            val denom = logSumExp(alpha[t], beta[t])
            for (i in 0 until NUM_REGIMES) {
                for (j in 0 until NUM_REGIMES) {
                    xi[t][i][j] = alpha[t][i] + logA[i][j] + logB[j][observations[t + 1]] + beta[t + 1][j] - denom
                }
            }
        }

        return ForwardBackwardResult(alpha, beta, gamma, xi, c)
    }

    /**
     * 파라미터 업데이트 (M-step)
     */
    private fun updateParameters(
        gamma: Array<DoubleArray>,
        xi: Array<Array<DoubleArray>>,
        observations: IntArray,
        T: Int
    ) {
        // 초기 상태 확률 업데이트
        for (i in 0 until NUM_REGIMES) {
            logPi[i] = gamma[0][i]
        }

        // 전이 확률 업데이트
        for (i in 0 until NUM_REGIMES) {
            var sumXi = 0.0
            for (t in 0 until T - 1) {
                for (j in 0 until NUM_REGIMES) {
                    sumXi += exp(xi[t][i][j])
                }
            }

            if (sumXi > 0) {
                for (j in 0 until NUM_REGIMES) {
                    var sumXi_ij = 0.0
                    for (t in 0 until T - 1) {
                        sumXi_ij += exp(xi[t][i][j])
                    }
                    logA[i][j] = ln(sumXi_ij / sumXi)
                }
            }
        }

        // 방출 확률 업데이트
        for (j in 0 until NUM_REGIMES) {
            val obsCounts = DoubleArray(5) { 0.0 }
            var sumGamma = 0.0

            for (t in 0 until T) {
                obsCounts[observations[t]] += exp(gamma[t][j])
                sumGamma += exp(gamma[t][j])
            }

            if (sumGamma > 0) {
                for (k in 0 until 5) {
                    logB[j][k] = ln(obsCounts[k] / sumGamma)
                }
            }
        }
    }

    /**
     * Forward 스케일링
     */
    private fun scaleAlpha(alpha: Array<DoubleArray>, c: DoubleArray, t: Int) {
        var sum = LOG_EPSILON
        for (i in 0 until NUM_REGIMES) {
            if (alpha[t][i] > sum) sum = alpha[t][i]
        }

        c[t] = if (sum > LOG_EPSILON) exp(sum) else 1.0

        for (i in 0 until NUM_REGIMES) {
            alpha[t][i] -= if (c[t] > 0) ln(c[t]) else 0.0
        }
    }

    /**
     * Backward 스케일링
     */
    private fun scaleBeta(beta: Array<DoubleArray>, c: DoubleArray, t: Int) {
        beta[t].indices.forEach { i ->
            beta[t][i] -= if (c[t] > 0) ln(c[t]) else 0.0
        }
    }

    /**
     * 로그 우도 계산 (스케일링 계수 포함)
     */
    private fun calculateLogLikelihood(alpha: Array<DoubleArray>, c: DoubleArray, T: Int): Double {
        // 마지막 시점의 alpha (이미 스케일링됨)
        val logLikelihood = logSumExp(alpha[T - 1])

        // 스케일링 보정: sum(ln(c[t]))
        var sumC = 0.0
        for (t in 0 until T) {
            sumC += if (c[t] > 0) ln(c[t]) else 0.0
        }

        return logLikelihood + sumC
    }

    /**
     * 현재 레짐 감지 및 분석
     */
    fun analyze(candles: List<Candle>): RegimeAnalysis {
        val baseAnalysis = hmmDetector.detect(candles)

        // 추가: 레짐 안정성 점수
        val stabilityScore = calculateRegimeStability(candles)

        // 추가: Regime-dependent Kelly
        val regimeKelly = calculateRegimeDependentKelly(candles)

        // 원래 RegimeAnalysis 반환 (확장 필드는 별도 제공)
        return RegimeAnalysis(
            regime = baseAnalysis.regime,
            confidence = baseAnalysis.confidence,
            adx = baseAnalysis.adx,
            atr = baseAnalysis.atr,
            atrPercent = baseAnalysis.atrPercent,
            trendDirection = baseAnalysis.trendDirection,
            timestamp = baseAnalysis.timestamp
        )
    }

    /**
     * 확장 레짐 분석 (추가 정보 포함)
     */
    fun analyzeExtended(candles: List<Candle>): ExtendedRegimeAnalysis {
        val baseAnalysis = hmmDetector.detect(candles)

        // 추가: 레짐 안정성 점수
        val stabilityScore = calculateRegimeStability(candles)

        // 추가: Regime-dependent Kelly
        val regimeKelly = calculateRegimeDependentKelly(candles)

        return ExtendedRegimeAnalysis(
            regime = baseAnalysis.regime,
            confidence = baseAnalysis.confidence,
            adx = baseAnalysis.adx,
            atr = baseAnalysis.atr,
            atrPercent = baseAnalysis.atrPercent,
            trendDirection = baseAnalysis.trendDirection,
            timestamp = baseAnalysis.timestamp,
            stabilityScore = stabilityScore,
            regimeKelly = regimeKelly
        )
    }

    /**
     * 레짐 안정성 점수 계산
     *
     * Jim Simons: "불안정한 레짐에서는 베팅을 축소한다"
     */
    private fun calculateRegimeStability(candles: List<Candle>): Double {
        if (candles.size < 20) return 0.5

        // 최근 20개 캔들의 수익률 표준편차
        val returns = mutableListOf<Double>()
        for (i in 1 until minOf(21, candles.size)) {
            val ret = (candles[i].close.toDouble() / candles[i - 1].close.toDouble()) - 1
            returns.add(ret)
        }

        val std = calculateStd(returns)

        // 안정성 점수 (표준편차가 낮을수록 안정적)
        return when {
            std < 0.01 -> 1.0  // 1% 미만 = 매우 안정적
            std < 0.02 -> 0.8  // 2% 미만 = 안정적
            std < 0.04 -> 0.5  // 4% 미만 = 보통
            else -> 0.3        // 4% 이상 = 불안정
        }
    }

    /**
     * Regime-dependent Kelly Criterion
     *
     * Jim Simons: "각 레짐에서의 Kelly Criterion을 따로 계산한다"
     */
    private fun calculateRegimeDependentKelly(candles: List<Candle>): RegimeKellyRecommendation {
        val analysis = hmmDetector.detect(candles)

        // 레짐별 기본 Kelly 배수
        val baseKelly = when (analysis.regime) {
            com.ant.cointrading.model.MarketRegime.BULL_TREND -> 0.02      // 2%
            com.ant.cointrading.model.MarketRegime.BEAR_TREND -> 0.005     // 0.5%
            com.ant.cointrading.model.MarketRegime.SIDEWAYS -> 0.01      // 1%
            else -> 0.01
        }

        // 안정성에 따른 조정
        val stability = calculateRegimeStability(candles)
        val adjustedKelly = baseKelly * stability

        // 신뢰도에 따른 추가 조정
        val confidenceFactor = analysis.confidence / 100.0
        val finalKelly = adjustedKelly * (0.5 + 0.5 * confidenceFactor)

        return RegimeKellyRecommendation(
            regime = analysis.regime.name,
            baseKelly = baseKelly,
            stabilityScore = stability,
            confidence = analysis.confidence,
            recommendedKelly = finalKelly.coerceIn(0.0, 0.03),  // 최대 3%
            reasoning = when {
                stability < 0.5 -> "불안정한 레짐, 포지션 축소 권장"
                analysis.confidence < 50 -> "낮은 신뢰도, 보수적 베팅 권장"
                else -> "레짐 안정적, 정상 베팅 가능"
            }
        )
    }

    /**
     * 관측값 추출
     */
    private fun extractObservations(candles: List<Candle>): List<Int> {
        val observations = mutableListOf<Int>()

        for (i in 1 until candles.size) {
            val returnPct = ((candles[i].close.toDouble() / candles[i - 1].close.toDouble()) - 1) * 100

            val obs = when {
                returnPct < -2.0 -> OBS_STRONG_NEG
                returnPct < -0.5 -> OBS_NEG
                returnPct < 0.5 -> OBS_NEUTRAL
                returnPct < 2.0 -> OBS_POS
                else -> OBS_STRONG_POS
            }

            observations.add(obs)
        }

        return observations
    }

    /**
     * 표준편차 계산
     */
    private fun calculateStd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Log Sum Exp (수치적 안정성) - 단일 배열
     * ln(sum(exp(x_i))) 계산
     */
    private fun logSumExp(xs: DoubleArray): Double {
        val max = xs.maxOrNull() ?: LOG_EPSILON
        var sum = 0.0
        for (x in xs) {
            sum += exp(x - max)
        }
        return max + ln(sum)
    }

    /**
     * Log Sum Exp (수치적 안정성) - 두 배열 합침
     * logSumExp(alpha) + logSumExp(beta) 계산용
     */
    private fun logSumExp(a: DoubleArray, b: DoubleArray): Double {
        var maxVal = LOG_EPSILON
        for (i in a.indices) {
            if (a[i] > maxVal) maxVal = a[i]
            if (b[i] > maxVal) maxVal = b[i]
        }

        var sum = 0.0
        for (i in a.indices) {
            sum += exp(a[i] - maxVal) + exp(b[i] - maxVal)
        }

        return maxVal + ln(sum)
    }

    /**
     * Forward-Backward 결과
     */
    private data class ForwardBackwardResult(
        val alpha: Array<DoubleArray>,
        val beta: Array<DoubleArray>,
        val gamma: Array<DoubleArray>,
        val xi: Array<Array<DoubleArray>>,
        val c: DoubleArray  // 스케일링 계수
    )
}

/**
 * 확장 레짐 분석 (Jim Simons 추가 정보)
 */
data class ExtendedRegimeAnalysis(
    val regime: com.ant.cointrading.model.MarketRegime,
    val confidence: Double,
    val adx: Double?,
    val atr: Double?,
    val atrPercent: Double?,
    val trendDirection: Int,
    val timestamp: java.time.Instant,
    val stabilityScore: Double = 0.5,
    val regimeKelly: RegimeKellyRecommendation? = null
)

/**
 * Regime-dependent Kelly 권장사항
 */
data class RegimeKellyRecommendation(
    val regime: String,
    val baseKelly: Double,
    val stabilityScore: Double,
    val confidence: Double,
    val recommendedKelly: Double,
    val reasoning: String
)

/**
 * 학습 결과
 */
data class RegimeTrainingResult(
    val success: Boolean,
    val reason: String,
    val iterations: Int,
    val logLikelihood: Double,
    val elapsedMs: Long = 0
)
