package com.ant.cointrading.regime

import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.ln

/**
 * HMM(Hidden Markov Model) 기반 시장 레짐 감지기
 *
 * ADX/ATR 기반 단순 분류보다 정교한 레짐 전환 감지.
 * 가격 변화율, 변동성, 거래량 변화를 이산화하여 관측값으로 사용.
 *
 * 은닉 상태(Hidden States):
 * - 0: SIDEWAYS (횡보)
 * - 1: BULL_TREND (상승 추세)
 * - 2: BEAR_TREND (하락 추세)
 * - 3: HIGH_VOLATILITY (고변동성)
 *
 * 관측값(Observations):
 * - 수익률 (5레벨) × 변동성 (3레벨) × 거래량 변화 (3레벨) = 45개 이산 관측값
 *
 * Viterbi 알고리즘을 사용하여 가장 가능성 높은 상태 시퀀스 추정.
 */
@Component
class HmmRegimeDetector(
    private val regimeDetector: RegimeDetector  // 기존 감지기 (폴백용)
) {

    private val log = LoggerFactory.getLogger(HmmRegimeDetector::class.java)

    // 상태 매핑
    private val stateToRegime = arrayOf(
        MarketRegime.SIDEWAYS,       // 0
        MarketRegime.BULL_TREND,     // 1
        MarketRegime.BEAR_TREND,     // 2
        MarketRegime.HIGH_VOLATILITY // 3
    )

    // HMM 파라미터
    private val numStates = 4
    private val numObservations = 45  // 5 * 3 * 3

    // 초기 상태 확률 (log 스케일)
    private val logPi: DoubleArray

    // 전이 확률 행렬 (log 스케일)
    private val logA: Array<DoubleArray>

    // 방출 확률 행렬 (log 스케일)
    private val logB: Array<DoubleArray>

    companion object {
        // 수익률 레벨 (5개)
        const val RETURN_VERY_NEGATIVE = 0  // < -2%
        const val RETURN_NEGATIVE = 1       // -2% ~ -0.5%
        const val RETURN_NEUTRAL = 2        // -0.5% ~ +0.5%
        const val RETURN_POSITIVE = 3       // +0.5% ~ +2%
        const val RETURN_VERY_POSITIVE = 4  // > +2%

        // 변동성 레벨 (3개)
        const val VOLATILITY_LOW = 0     // ATR% < 1%
        const val VOLATILITY_MEDIUM = 1  // 1% ~ 3%
        const val VOLATILITY_HIGH = 2    // > 3%

        // 거래량 변화 레벨 (3개)
        const val VOLUME_DECREASE = 0  // < 0.8
        const val VOLUME_NORMAL = 1    // 0.8 ~ 1.5
        const val VOLUME_INCREASE = 2  // > 1.5

        // 관측값 인코딩: return_level * 9 + volatility_level * 3 + volume_level
        fun encodeObservation(returnLevel: Int, volatilityLevel: Int, volumeLevel: Int): Int {
            return returnLevel * 9 + volatilityLevel * 3 + volumeLevel
        }

        // 아주 작은 확률의 로그 값
        private const val LOG_EPSILON = -100.0

        private fun safeLog(x: Double): Double {
            return if (x > 0) ln(x) else LOG_EPSILON
        }
    }

    init {
        log.info("=== HMM 레짐 감지기 초기화 ===")

        // 초기 상태 확률
        val pi = doubleArrayOf(0.40, 0.20, 0.20, 0.20)
        logPi = pi.map { safeLog(it) }.toDoubleArray()

        // 전이 확률 행렬
        val transitionMatrix = arrayOf(
            // FROM: SIDEWAYS  ->  SIDEWAYS, BULL, BEAR, HIGH_VOL
            doubleArrayOf(0.70, 0.12, 0.12, 0.06),
            // FROM: BULL      ->  SIDEWAYS, BULL, BEAR, HIGH_VOL
            doubleArrayOf(0.15, 0.70, 0.05, 0.10),
            // FROM: BEAR      ->  SIDEWAYS, BULL, BEAR, HIGH_VOL
            doubleArrayOf(0.15, 0.05, 0.70, 0.10),
            // FROM: HIGH_VOL  ->  SIDEWAYS, BULL, BEAR, HIGH_VOL
            doubleArrayOf(0.20, 0.15, 0.15, 0.50)
        )
        logA = transitionMatrix.map { row -> row.map { safeLog(it) }.toDoubleArray() }.toTypedArray()

        // 방출 확률 행렬
        val emissionMatrix = buildInitialEmissionMatrix()
        logB = emissionMatrix.map { row -> row.map { safeLog(it) }.toDoubleArray() }.toTypedArray()

        log.info("HMM 파라미터: states=$numStates, observations=$numObservations")
    }

    /**
     * 초기 방출 확률 행렬 구성
     */
    private fun buildInitialEmissionMatrix(): Array<DoubleArray> {
        val matrix = Array(numStates) { DoubleArray(numObservations) { 0.001 } }

        // SIDEWAYS (0): 중립 수익률, 낮은/중간 변동성
        for (vol in 0..1) {
            for (volChange in 0..2) {
                matrix[0][encodeObservation(RETURN_NEUTRAL, vol, volChange)] = 0.15
                matrix[0][encodeObservation(RETURN_POSITIVE, vol, volChange)] = 0.05
                matrix[0][encodeObservation(RETURN_NEGATIVE, vol, volChange)] = 0.05
            }
        }

        // BULL_TREND (1): 양의 수익률
        for (vol in 0..2) {
            for (volChange in 0..2) {
                val volBonus = if (volChange == VOLUME_INCREASE) 1.5 else 1.0
                matrix[1][encodeObservation(RETURN_VERY_POSITIVE, vol, volChange)] = 0.10 * volBonus
                matrix[1][encodeObservation(RETURN_POSITIVE, vol, volChange)] = 0.12 * volBonus
                matrix[1][encodeObservation(RETURN_NEUTRAL, vol, volChange)] = 0.03
            }
        }

        // BEAR_TREND (2): 음의 수익률
        for (vol in 0..2) {
            for (volChange in 0..2) {
                val volBonus = if (volChange == VOLUME_INCREASE) 1.5 else 1.0
                matrix[2][encodeObservation(RETURN_VERY_NEGATIVE, vol, volChange)] = 0.10 * volBonus
                matrix[2][encodeObservation(RETURN_NEGATIVE, vol, volChange)] = 0.12 * volBonus
                matrix[2][encodeObservation(RETURN_NEUTRAL, vol, volChange)] = 0.03
            }
        }

        // HIGH_VOLATILITY (3): 높은 변동성
        for (ret in 0..4) {
            for (volChange in 0..2) {
                val retBonus = if (ret == RETURN_VERY_POSITIVE || ret == RETURN_VERY_NEGATIVE) 1.5 else 1.0
                matrix[3][encodeObservation(ret, VOLATILITY_HIGH, volChange)] = 0.06 * retBonus
                matrix[3][encodeObservation(ret, VOLATILITY_MEDIUM, volChange)] = 0.02
            }
        }

        // 정규화
        for (state in 0 until numStates) {
            val sum = matrix[state].sum()
            if (sum > 0) {
                for (obs in 0 until numObservations) {
                    matrix[state][obs] /= sum
                }
            }
        }

        return matrix
    }

    /**
     * Viterbi 알고리즘: 가장 가능성 높은 상태 시퀀스 추정
     *
     * @param observations 관측값 시퀀스
     * @return 추정된 상태 시퀀스
     */
    private fun viterbi(observations: IntArray): IntArray {
        val t = observations.size
        if (t == 0) return intArrayOf()

        // delta[t][i] = 시간 t에서 상태 i로 끝나는 최적 경로의 로그 확률
        val delta = Array(t) { DoubleArray(numStates) { LOG_EPSILON } }

        // psi[t][i] = 시간 t에서 상태 i에 도달하기 직전 상태
        val psi = Array(t) { IntArray(numStates) { 0 } }

        // 초기화 (t=0)
        for (i in 0 until numStates) {
            delta[0][i] = logPi[i] + logB[i][observations[0]]
        }

        // 재귀 (t=1 ~ T-1)
        for (step in 1 until t) {
            for (j in 0 until numStates) {
                var maxVal = LOG_EPSILON
                var maxState = 0

                for (i in 0 until numStates) {
                    val val_ = delta[step - 1][i] + logA[i][j]
                    if (val_ > maxVal) {
                        maxVal = val_
                        maxState = i
                    }
                }

                delta[step][j] = maxVal + logB[j][observations[step]]
                psi[step][j] = maxState
            }
        }

        // 종료: 마지막 시간에서 최적 상태 찾기
        var maxVal = LOG_EPSILON
        var lastState = 0
        for (i in 0 until numStates) {
            if (delta[t - 1][i] > maxVal) {
                maxVal = delta[t - 1][i]
                lastState = i
            }
        }

        // 역추적
        val path = IntArray(t)
        path[t - 1] = lastState
        for (step in t - 2 downTo 0) {
            path[step] = psi[step + 1][path[step + 1]]
        }

        return path
    }

    /**
     * 시장 레짐 감지 (HMM 기반)
     */
    fun detect(candles: List<Candle>): RegimeAnalysis {
        if (candles.size < 20) {
            log.debug("캔들 데이터 부족 (${candles.size}개), 기존 감지기 사용")
            return regimeDetector.detect(candles)
        }

        try {
            // 관측 시퀀스 생성
            val observations = extractObservations(candles)

            if (observations.size < 5) {
                return regimeDetector.detect(candles)
            }

            // Viterbi 알고리즘으로 상태 시퀀스 추정
            val stateSequence = viterbi(observations.toIntArray())

            // 마지막 상태가 현재 레짐
            val currentState = stateSequence.last()
            val regime = stateToRegime[currentState]

            // 신뢰도 계산 (최근 5개 상태의 일관성)
            val recentStates = stateSequence.takeLast(5)
            val consistency = recentStates.count { it == currentState }.toDouble() / recentStates.size
            val confidence = (consistency * 100).coerceIn(30.0, 95.0)

            // 기존 지표도 함께 계산
            val baseAnalysis = regimeDetector.detect(candles)

            log.debug(
                "HMM 레짐 감지: regime={}, confidence={:.1f}%, base_regime={}, seq_len={}",
                regime, confidence, baseAnalysis.regime, stateSequence.size
            )

            return RegimeAnalysis(
                regime = regime,
                confidence = confidence,
                adx = baseAnalysis.adx,
                atr = baseAnalysis.atr,
                atrPercent = baseAnalysis.atrPercent,
                trendDirection = baseAnalysis.trendDirection,
                timestamp = Instant.now()
            )

        } catch (e: Exception) {
            log.warn("HMM 감지 실패, 기존 감지기 사용: ${e.message}")
            return regimeDetector.detect(candles)
        }
    }

    /**
     * 캔들 데이터에서 관측 시퀀스 추출
     */
    private fun extractObservations(candles: List<Candle>): List<Int> {
        val observations = mutableListOf<Int>()

        // 평균 거래량 계산
        val avgVolume = candles.map { it.volume.toDouble() }.average()

        for (i in 1 until candles.size) {
            val current = candles[i]
            val previous = candles[i - 1]

            // 1. 수익률 계산
            val returnPct = ((current.close.toDouble() / previous.close.toDouble()) - 1) * 100
            val returnLevel = when {
                returnPct < -2.0 -> RETURN_VERY_NEGATIVE
                returnPct < -0.5 -> RETURN_NEGATIVE
                returnPct < 0.5 -> RETURN_NEUTRAL
                returnPct < 2.0 -> RETURN_POSITIVE
                else -> RETURN_VERY_POSITIVE
            }

            // 2. 변동성 계산
            val range = current.high.toDouble() - current.low.toDouble()
            val midPrice = (current.high.toDouble() + current.low.toDouble()) / 2
            val volatilityPct = if (midPrice > 0) (range / midPrice) * 100 else 0.0
            val volatilityLevel = when {
                volatilityPct < 1.0 -> VOLATILITY_LOW
                volatilityPct < 3.0 -> VOLATILITY_MEDIUM
                else -> VOLATILITY_HIGH
            }

            // 3. 거래량 변화
            val volumeRatio = if (avgVolume > 0) current.volume.toDouble() / avgVolume else 1.0
            val volumeLevel = when {
                volumeRatio < 0.8 -> VOLUME_DECREASE
                volumeRatio > 1.5 -> VOLUME_INCREASE
                else -> VOLUME_NORMAL
            }

            observations.add(encodeObservation(returnLevel, volatilityLevel, volumeLevel))
        }

        return observations
    }

    /**
     * 상태 전환 확률 조회
     */
    fun getTransitionProbabilities(): Map<String, Map<String, Double>> {
        val transitionMatrix = arrayOf(
            doubleArrayOf(0.70, 0.12, 0.12, 0.06),
            doubleArrayOf(0.15, 0.70, 0.05, 0.10),
            doubleArrayOf(0.15, 0.05, 0.70, 0.10),
            doubleArrayOf(0.20, 0.15, 0.15, 0.50)
        )

        val result = mutableMapOf<String, Map<String, Double>>()
        for (from in 0 until numStates) {
            val fromRegime = stateToRegime[from].name
            val transitions = mutableMapOf<String, Double>()
            for (to in 0 until numStates) {
                transitions[stateToRegime[to].name] = transitionMatrix[from][to]
            }
            result[fromRegime] = transitions
        }
        return result
    }

    /**
     * 다음 레짐 예측
     */
    fun predictNextRegime(currentRegime: MarketRegime): Map<MarketRegime, Double> {
        val transitionMatrix = arrayOf(
            doubleArrayOf(0.70, 0.12, 0.12, 0.06),
            doubleArrayOf(0.15, 0.70, 0.05, 0.10),
            doubleArrayOf(0.15, 0.05, 0.70, 0.10),
            doubleArrayOf(0.20, 0.15, 0.15, 0.50)
        )

        val currentState = stateToRegime.indexOf(currentRegime)
        if (currentState == -1) {
            return stateToRegime.associateWith { 0.25 }
        }

        return stateToRegime.mapIndexed { index, regime ->
            regime to transitionMatrix[currentState][index]
        }.toMap()
    }

    /**
     * HMM 상태 정보 조회
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "numStates" to numStates,
            "numObservations" to numObservations,
            "regimeMapping" to stateToRegime.mapIndexed { idx, regime -> idx to regime.name }.toMap(),
            "transitionProbabilities" to getTransitionProbabilities()
        )
    }
}
