---
name: quant-trading
description: 20년 경력 퀀트 트레이더의 암호화폐 트레이딩 완벽 가이드. Markowitz MVO, Black-Litterman, Risk Parity, Kelly Criterion, HRP, Factor Models, Robust Optimization 포함. 기술적 분석(RSI, MACD, 볼린저밴드, 이치모쿠, 피보나치, ATR), 컨플루언스 전략(85% 승률), 포지션 사이징, 리스크 관리, 온체인 분석, 뉴스 감성 분석, 펌프앤덤프 탐지, 시장 레짐 감지 포함. 장기투자/스윙/단타/스캘핑 모든 전략 지원.
---

# 퀀트 트레이딩 마스터 가이드

연간 40%+ 수익률을 목표로 하는 체계적 암호화폐 트레이딩 프레임워크.

---

## 제1부: 현대 포트폴리오 이론 (MPT)

### 1.1 Markowitz Mean-Variance Optimization

**기본 원리**: 기대수익률을最大化하면서 포트폴리오 분산을 最小化

**수식**:
```
max: w'μ
s.t.: w'Σw ≤ σ²_target
     Σw = 1
     w ≥ 0 (no short-selling)
```

**암호화폐 적용 시 문제점**:
1. **수익률 비정상성**: 로그수익률도 정규분포 아님 (Fat tails)
2. **공분산행렬 불안정**: p >> n 문제 (자산 수 > 데이터 수)
3. **에스토그레이션 오류**: 과거 데이터가 미래를 예측 못함
4. **코너 솔루션**: 극단적 집중 포트폴리오

**해결책**:
```kotlin
// 1. 샤프 비율 기반 최적화 (무위험이자율 고려)
fun maximizeSharpeRatio(
    expectedReturns: DoubleArray,
    covarianceMatrix: Array<DoubleArray>,
    riskFreeRate: Double = 0.02
): DoubleArray {
    val excessReturns = expectedReturns.map { it - riskFreeRate }.toDoubleArray()
    // 역공분산행렬 기반 가중치 계산
    val invCov = invertMatrix(covarianceMatrix)
    val numerator = matrixVectorMultiply(invCov, excessReturns)
    val sumWeights = numerator.sum()
    return numerator.map { it / sumWeights }.toDoubleArray()
}

// 2. 리스크 패리티 제약 조건 추가
fun riskParityConstraint(weights: DoubleArray, covMatrix: Array<DoubleArray>): Double {
    val contributions = weights.mapIndexed { i, w ->
        w * (covMatrix[i].zip(weights).sum { it.first * it.second })
    }
    val totalRisk = contributions.sum()
    val targetRisk = totalRisk / weights.size
    return contributions.map { (it - targetRisk).let { diff -> diff * diff } }.sum()
}
```

### 1.2 Efficient Frontier & Capital Market Line

**CML (자본시장선)**:
```
E(Rp) = Rf + σp × [(E(Rm) - Rf) / σm]

기울기 = Sharpe Ratio of Market Portfolio
```

**암호화폐 시장 포트폴리오**:
- BTC: 40-50% (디지털 골드)
- ETH: 25-30% (스마트 컨트랙트 플랫폼)
- Altcoins: 20-35% (고베타 자산)

---

## 제2부: Black-Litterman Model

### 2.1 모델 구조

**핵심 아이디어**: 시장均衡점 + 투자자 뷰 결합

```
E[R] = [(τΣ)^(-1) + P'Ω(-1)P]^(-1) × [(τΣ)^(-1)Π + P'Ω(-1)Q]

E[R]: Black-Litterman 수익률
Π: Implied Equilibrium Returns (시장均衡)
Q: 투자자 뷰 (View Vector)
P: Pick Matrix (뷰 선택 행렬)
Ω: 뷰 불확실성 행렬
τ: Scaling parameter (0.01~0.05)
```

### 2.2 암호화폐 뷰 설정 예시

```kotlin
data class View(
    val assets: List<Int>,           // 관련 자산 인덱스 [0, 2] = BTC, XRP
    val viewReturn: Double,          // 기대 수익률 0.15 = +15%
    val confidence: Double           // 신뢰도 0.0~1.0
)

// 실제 뷰 예시
val cryptoViews = listOf(
    // 뷰 1: BTC가 ETH보다 5% 더 오름
    View(assets = [0, 1], viewReturn = 0.05, confidence = 0.7),

    // 뷰 2: SOL이 20% 상승 (고신뢰)
    View(assets = [3], viewReturn = 0.20, confidence = 0.8),

    // 뷰 3: DeFi 토큰 전반 상승 (저신뢰)
    View(assets = [4, 5, 6], viewReturn = 0.10, confidence = 0.5)
)

// Implied Equilibrium Return 계산
fun calculateImpliedReturns(
    marketCaps: DoubleArray,      // [BTC, ETH, XRP, SOL, ...]
    riskFreeRate: Double = 0.02,
    marketRiskPremium: Double = 0.06
): DoubleArray {
    val weights = marketCaps.map { it / marketCaps.sum() }.toDoubleArray()
    // CAPM: E[Ri] = Rf + βi × (E[Rm] - Rf])
    // 암호화폐는 β ≈ 1 (전체 시장과 높은 상관관계)
    return weights.map { riskFreeRate + 1.0 * marketRiskPremium }.toDoubleArray()
}
```

### 2.3 뷰 불확실성 행렬 (Ω)

```kotlin
fun calculateViewUncertainty(
    covarianceMatrix: Array<DoubleArray>,
    pickMatrix: Array<DoubleArray>,
    confidences: DoubleArray,
    tau: Double = 0.025
): Array<DoubleArray> {
    return pickMatrix.map { p ->
        val pSigmaP = p.zip(covarianceMatrix).sum { row ->
            row.first.zip(p).sum { it.first * it.second * row.second }
        }
        p.map { pSigmaP / tau }.toDoubleArray()
    }.toTypedArray()
}
```

---

## 제3부: 리스크 패리티 & Equal Risk Contribution

### 3.1 Risk Parity 기본 원리

**핵심**: 자산별 리스크 기여도를 동등하게 배분

```
RC_i = w_i × (Σw)_i / σ_portfolio

목표: RC_1 = RC_2 = ... = RC_n
```

**암호화폐 Risk Parity 가중치**:
```kotlin
fun solveRiskParity(
    covMatrix: Array<DoubleArray>,
    tolerance: Double = 1e-8,
    maxIter: Int = 1000
): DoubleArray {
    val n = covMatrix.size
    var weights = DoubleArray(n) { 1.0 / n }  // 초기: 균등

    repeat(maxIter) {
        val portfolioRisk = calculatePortfolioRisk(weights, covMatrix)
        val marginalContribs = calculateMarginalContributions(weights, covMatrix)
        val riskContribs = weights.mapIndexed { i, w -> w * marginalContribs[i] }
        val avgRiskContrib = riskContribs.sum() / n

        // 가중치 업데이트 (Newton-Raphson 유사)
        weights = weights.mapIndexed { i, w ->
            w * (avgRiskContrib / riskContribs[i]).pow(0.5)
        }.toDoubleArray()

        // 정규화
        val sumW = weights.sum()
        weights = weights.map { it / sumW }.toDoubleArray()

        // 수렴 확인
        val maxDeviation = riskContribs.map { (it - avgRiskContrib).absoluteValue }.maxOrNull() ?: 0.0
        if (maxDeviation < tolerance) break
    }

    return weights
}
```

### 3.2 Hierarchical Risk Parity (HRP)

**문제점**: 전통 Risk Parity는 상관관계 구조 무시

**HRP 해결**: 계층적 군집화 + 역분산 가중치

```kotlin
// 1. 거리 행렬 (상관관계 기반)
fun calculateDistanceMatrix(correlationMatrix: Array<DoubleArray>): Array<DoubleArray> {
    val n = correlationMatrix.size
    return Array(n) { i ->
        Array(n) { j ->
            sqrt(0.5 * (1 - correlationMatrix[i][j]))
        }
    }
}

// 2. 계층적 군집화 (Single Linkage)
fun hierarchicalClustering(distanceMatrix: Array<DoubleArray>): List<List<Int>> {
    val clusters = (0 until distanceMatrix.size).map { listOf(it) }.toMutableList()
    val n = clusters.size

    repeat(n - 1) {
        var minDistance = Double.MAX_VALUE
        var mergePair = Pair(0, 0)

        for (i in clusters.indices) {
            for (j in i + 1 until clusters.size) {
                val dist = clusterDistance(clusters[i], clusters[j], distanceMatrix)
                if (dist < minDistance) {
                    minDistance = dist
                    mergePair = Pair(i, j)
                }
            }
        }

        // 병합
        val merged = clusters[mergePair.first] + clusters[mergePair.second]
        clusters.removeAt(mergePair.second)
        clusters.removeAt(mergePair.first)
        clusters.add(merged)
    }

    return clusters
}

// 3. HRP 가중치 배정
fun calculateHRPWeights(
    clusters: List<List<Int>>,
    covMatrix: Array<DoubleArray>
): DoubleArray {
    val n = covMatrix.size
    val weights = DoubleArray(n)
    val clusterItems = clusters.flatten()

    // 반분할 가중치 배정
    fun assignWeights(cluster: List<Int>, weight: Double) {
        if (cluster.size == 1) {
            weights[cluster[0]] = weight
        } else {
            val mid = cluster.size / 2
            val left = cluster.take(mid)
            val right = cluster.drop(mid)

            // 역분산 비율 계산
            val leftVar = calculateClusterVariance(left, covMatrix)
            val rightVar = calculateClusterVariance(right, covMatrix)
            val totalVar = leftVar + rightVar

            assignWeights(left, weight * rightVar / totalVar)
            assignWeights(right, weight * leftVar / totalVar)
        }
    }

    assignWeights(clusterItems, 1.0)
    return weights
}
```

### 3.3 Clustered Risk Parity (CRP)

HRP + K-Means 군집화 조합:

```kotlin
fun clusteredRiskParity(
    returns: Array<DoubleArray>,  // [time][asset]
    nClusters: Int = 5
): DoubleArray {
    val nAssets = returns[0].size

    // 1. K-Means 군집화 (상관관계 기반)
    val correlationMatrix = calculateCorrelationMatrix(returns)
    val clusters = kMeansClustering(correlationMatrix, nClusters)

    // 2. 클러스터 내부 Risk Parity
    val clusterWeights = clusters.map { cluster ->
        solveRiskParity(
            cluster.map { i ->
                cluster.map { j -> correlationMatrix[i][j] }.toDoubleArray()
            }.toTypedArray()
        )
    }

    // 3. 클러스터 간 Risk Parity
    val clusterCov = calculateClusterCovariance(clusters, returns)
    val interClusterWeights = solveRiskParity(clusterCov)

    // 4. 최종 가중치 결합
    val finalWeights = DoubleArray(nAssets)
    clusters.forEachIndexed { clusterIdx, cluster ->
        cluster.forEachIndexed { assetIdx, asset ->
            finalWeights[asset] = interClusterWeights[clusterIdx] * clusterWeights[clusterIdx][assetIdx]
        }
    }

    return finalWeights
}
```

---

## 제4부: Kelly Criterion & 포지션 사이징

### 4.1 Kelly Criterion 기본

```
f* = (bp - q) / b = p - q/b

f*: 최적 베팅 비율 (자본 대비)
b: 손익비 (Win Amount / Loss Amount)
p: 승률
q: 패배율 = 1 - p
```

**암호화폐 실전 적용**:

| 전략 | 승률(p) | R:R(b) | Kelly f* | Half Kelly | 실전 권장 |
|------|---------|--------|----------|------------|-----------|
| 컨플루언스 강 | 0.85 | 2.0 | 55% | 27.5% | **5-10%** |
| 컨플루언스 중 | 0.70 | 2.5 | 42% | 21% | **3-5%** |
| 컨플루언스 약 | 0.55 | 3.0 | 23% | 11.5% | **1-2%** |

**주의**: Kelly Criterion은 분산을 고려하지 않음 → **Half Kelly** 또는 **Quarter Kelly** 권장

### 4.2 Multi-Asset Kelly Criterion

```kotlin
// 다자산 Kelly 최적화 (Covariance 고려)
fun multiAssetKelly(
    expectedReturns: DoubleArray,
    covarianceMatrix: Array<DoubleArray>,
    riskFreeRate: Double = 0.02
): DoubleArray {
    val excessReturns = expectedReturns.map { it - riskFreeRate }.toDoubleArray()
    val invCov = invertMatrix(covarianceMatrix)

    // f* = Σ^(-1) × μ
    val kellyWeights = matrixVectorMultiply(invCov, excessReturns)

    // 음수 가중치 처리 (no-short constraint)
    val normalized = kellyWeights.map { maxOf(0.0, it) }.toDoubleArray()
    val sumW = normalized.sum()

    return if (sumW > 0) {
        normalized.map { it / sumW }.toDoubleArray()
    } else {
        DoubleArray(expectedReturns.size) { 1.0 / expectedReturns.size }
    }
}
```

### 4.3 Adaptive Kelly (동적 포지션 사이징)

```kotlin
data class TradingResult(
    val isWin: Boolean,
    val pnlPercent: Double,
    val confidence: Double,     // 컨플루언스 점수 기반
    val marketRegime: String    // LOW_VOL / HIGH_VOL / CRISIS
)

class AdaptiveKellySizer(
    private val initialCapital: Double,
    private val maxPositionSize: Double = 0.05,  // 최대 5%
    private val minPositionSize: Double = 0.01   // 최소 1%
) {
    private val history = mutableListOf<TradingResult>()

    // 지수 가중 이동평균 승률 (최신 데이터 더 중요)
    fun calculateEWMAPWinRate(span: Int = 20): Double {
        if (history.isEmpty()) return 0.5
        val alpha = 2.0 / (span + 1)
        var ewma = 0.5
        history.forEach { result ->
            val win = if (result.isWin) 1.0 else 0.0
            ewma = alpha * win + (1 - alpha) * ewma
        }
        return ewma
    }

    // 레짐별 Kelly 조정
    fun calculateAdjustedKelly(confidence: Double, marketRegime: String): Double {
        val baseWinRate = calculateEWMAPWinRate()
        val baseRR = calculateEWMARR()

        // 레짐별 승률 보정
        val adjustedWinRate = when (marketRegime) {
            "LOW_VOL" -> baseWinRate * 1.1   // 횡보장: 승률 상향
            "HIGH_VOL" -> baseWinRate * 0.9  // 고변동: 승률 하향
            "CRISIS" -> baseWinRate * 0.5    // 위기: 승률 급락
            else -> baseWinRate
        }

        // Kelly 계산
        val rawKelly = (adjustedWinRate * baseRR - (1 - adjustedWinRate)) / baseRR

        // 컨플루언스 신뢰도 반영
        val confidenceAdjusted = rawKelly * (0.5 + confidence)  // confidence 0~1

        // Half Kelly + 리스크 한도
        val halfKelly = confidenceAdjusted / 2
        return halfKelly.coerceIn(minPositionSize, maxPositionSize)
    }

    fun recordTrade(result: TradingResult) {
        history.add(result)
        // 최근 100개만 유지
        if (history.size > 100) {
            history.removeAt(0)
        }
    }
}
```

---

## 제5부: Factor Models

### 5.1 CAPM (Capital Asset Pricing Model)

```
E[Ri] = Rf + βi × (E[Rm] - Rf])

βi = Cov(Ri, Rm) / Var(Rm)
```

**암호화폐 베타 추정**:
```kotlin
fun calculateCryptoBeta(
    assetReturns: DoubleArray,    // 자산 수익률
    marketReturns: DoubleArray,   // BTC 수익률 (시장 프록시)
    riskFreeRate: Double = 0.02
): FactorModelResult {
    // OLS 회귀
    val n = assetReturns.size
    val sumX = marketReturns.sum()
    val sumY = assetReturns.sum()
    val sumXY = marketReturns.zip(assetReturns).sum { it.first * it.second }
    val sumX2 = marketReturns.sumOf { it * it }

    val beta = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    val alpha = (sumY - beta * sumX) / n

    // R-squared
    val yMean = assetReturns.average()
    val ssTot = assetReturns.sumOf { (it - yMean) * (it - yMean) }
    val yPred = marketReturns.map { alpha + beta * it }
    val ssRes = assetReturns.zip(yPred).sum { (it.first - it.second).let { d -> d * d } }
    val rSquared = 1 - (ssRes / ssTot)

    // Idiosyncratic Risk (잔차 분산)
    val residuals = assetReturns.zip(yPred).map { it.first - it.second }
    val idiosyncraticVol = sqrt(residuals.map { it * it }.average())

    return FactorModelResult(
        alpha = alpha,
        beta = beta,
        rSquared = rSquared,
        idiosyncraticVol = idiosyncraticVol,
        expectedReturn = riskFreeRate + beta * (0.06 - riskFreeRate)  // Market Risk Premium = 6%
    )
}
```

### 5.2 Fama-French 3-Factor Model (암호화폐 버전)

```
E[Ri] = Rf + βi,MKT × (RM - Rf)
            + βi,SMB × SMB
            + βi,HML × HML
```

**암호화폐 팩터 정의**:

| 팩터 | 설명 | Long | Short |
|------|------|------|-------|
| MKT | 시장 위험 | BTC | Cash |
| SMB | Small Minus Big Cap | Small Cap (<$1B) | Large Cap (>$10B) |
| HML | High Minus Low Momentum | High Momentum (30d) | Low Momentum (30d) |

```kotlin
data class CryptoFactorReturns(
    val mkt: Double,  // Market Risk Premium
    val smb: Double,  // Size Factor
    val hml: Double   // Momentum Factor
)

fun calculateFamaFrenchBetas(
    assetReturns: DoubleArray,
    factors: List<CryptoFactorReturns>
): Triple<Double, Double, Double> {
    // 다중 회귀 (Matrix form: β = (X'X)^(-1)X'Y)
    val X = factors.map { listOf(1.0, it.mkt, it.smb, it.hml) }.toTypedArray()
    val Y = assetReturns

    val Xt = transpose(X)
    val XtX = matrixMultiply(Xt, X)
    val XtXInv = invertMatrix(XtX)
    val XtY = matrixVectorMultiply(Xt, Y)
    val betas = matrixVectorMultiply(XtXInv, XtY)

    return Triple(betas[1], betas[2], betas[3])  // (MKT, SMB, HML)
}
```

### 5.3 Statistical Factor Model (PCA)

```kotlin
import org.apache.commons.math3.linear.*
import org.apache.commons.math3.stat.correlation.Covariance

// PCA 기반 팩터 모델
fun pcaFactorModel(
    returns: Array<DoubleArray>,  // [time][asset]
    nFactors: Int = 5
): PCAResult {
    val covMatrix = Covariance(returns).covariance
    val eigenDecomposition = EigenDecomposition(covMatrix)

    // 상위 nFactors 개의 고유벡터 (팩터)
    val factors = (0 until nFactors).map { i ->
        eigenDecomposition.eigenvector(i).toArray()
    }

    // 팩터 수익률 (시계열)
    val factorReturns = (0 until returns.size).map { t ->
        factors.map { factor ->
            returns[t].zip(factor).sum { it.first * it.second }
        }.toDoubleArray()
    }

    // 팩터 적재량 (Loading)
    val loadings = factors.map { factor ->
        returns.transpose().map { assetReturns ->
            assetReturns.zip(factor).sum { it.first * it.second }
        }.toDoubleArray()
    }

    return PCAResult(
        factors = factors,
        factorReturns = factorReturns,
        loadings = loadings,
        explainedVariance = (0 until nFactors).sumOf {
            eigenDecomposition.realEigenvalues[it].absoluteValue
        } / eigenDecomposition.realEigenvalues.sumOf { it.absoluteValue }
    )
}
```

---

## 제6부: Robust Optimization

### 6.1 모델 불확실성 처리

**문제**: 추정 오류 → 최적화 결과 과도한 민감

**해결**: Worst-Case 최적화

```
min max: w'Σ(ε)w
w   ε∈U

U: 불확실성 집합 (Uncertainty Set)
```

### 6.2 Ellipsoidal Uncertainty Set

```kotlin
data class RobustOptimizationResult(
    val weights: DoubleArray,
    val worstCaseRisk: Double,
    val trueRisk: Double,
    val robustnessGap: Double
)

fun robustMeanVarianceOptimization(
    expectedReturns: DoubleArray,
    covarianceMatrix: Array<DoubleArray>,
    uncertaintyRadius: Double = 0.1,  // 추정 오류 반경
    targetReturn: Double
): RobustOptimizationResult {
    val n = expectedReturns.size

    // Ellipsoidal Uncertainty Set
    // U = {μ | (μ - μ̂)'Σμ^(-1)(μ - μ̂) ≤ κ²}

    // Worst-Case 수익률 계산
    val worstCaseReturns = DoubleArray(n) { i ->
        expectedReturns[i] - uncertaintyRadius * sqrt(covarianceMatrix[i][i])
    }

    // Robust 최적화 (Convex Optimization)
    // 이중화 (Duality)를 통해 원래 문제로 변환
    val robustWeights = solveConvexOptimization(
        expectedReturns = worstCaseReturns,
        covarianceMatrix = covarianceMatrix,
        targetReturn = targetReturn - uncertaintyRadius  // Conservative target
    )

    val trueRisk = calculatePortfolioRisk(robustWeights, covarianceMatrix)
    val worstCaseRisk = calculatePortfolioRisk(robustWeights, covarianceMatrix) *
                        (1 + uncertaintyRadius)

    return RobustOptimizationResult(
        weights = robustWeights,
        worstCaseRisk = worstCaseRisk,
        trueRisk = trueRisk,
        robustnessGap = worstCaseRisk - trueRisk
    )
}
```

### 6.3 Resampling Efficiency (Michaud Resampling)

```kotlin
fun resampledEfficientFrontier(
    historicalReturns: Array<DoubleArray>,
    nResamples: Int = 100,
    nPoints: Int = 20
): List<EfficientPoint> {
    val nAssets = historicalReturns[0].size
    val mu = calculateMeanReturns(historicalReturns)
    val sigma = calculateCovarianceMatrix(historicalReturns)

    val allWeights = mutableListOf<DoubleArray>()

    // 부트스트래핑
    repeat(nResamples) {
        // 수익률 재표본 (Resample returns with replacement)
        val resampledReturns = bootstrapReturns(historicalReturns)

        // 새로운 μ̂, Σ̂ 계산
        val resampledMu = calculateMeanReturns(resampledReturns)
        val resampledSigma = calculateCovarianceMatrix(resampledReturns)

        // Efficient Frontier 계산
        val frontierWeights = calculateEfficientFrontier(
            resampledMu, resampledSigma, nPoints
        )
        allWeights.addAll(frontierWeights)
    }

    // 평균 가중치 (Average across resamples)
    val averagedWeights = averageWeights(allWeights, nPoints)

    return averagedWeights.map { weights ->
        EfficientPoint(
            weights = weights,
            expectedReturn = weights.dot(mu),
            risk = sqrt(weights.dot(sigma).dot(weights))
        )
    }
}
```

---

## 제7부: 동적 자산배분 (Tactical Asset Allocation)

### 7.1 Momentum-Based Tactical Shift

```kotlin
class TacticalAssetAllocator(
    private val lookbackPeriod: Int = 30,  // 30일 모멘텀
    private val rebalanceThreshold: Double = 0.05  // 5% 변동 시 리밸런싱
) {
    // 시간 가중 모멘텀 점수
    fun calculateMomentumScore(
        returns: DoubleArray,
        halflife: Int = 10
    ): Double {
        var score = 0.0
        var weight = 1.0

        // 최신 데이터 더 중요 (Exponential decay)
        for (i in returns.size - 1 downTo maxOf(0, returns.size - lookbackPeriod)) {
            score += returns[i] * weight
            weight *= exp(-ln(2.0) / halflife)
        }

        return score
    }

    // 모멘텀 기반 가중치 조정
    fun adjustWeightsByMomentum(
        baseWeights: DoubleArray,      // Strategic weights (장기)
        recentReturns: Array<DoubleArray>  // 각 자산의 최근 수익률
    ): DoubleArray {
        val momentumScores = recentReturns.map { returns ->
            calculateMomentumScore(returns)
        }

        // 상대 모멘텀 점수 (정규화)
        val avgScore = momentumScores.average()
        val normalizedScores = momentumScores.map {
            exp((it - avgScore) / 0.1)  // Temperature scaling
        }

        val sumScores = normalizedScores.sum()
        val momentumWeights = normalizedScores.map { it / sumScores }.toDoubleArray()

        // Strategic + Tactical 혼합 (70:30)
        return baseWeights.mapIndexed { i, w ->
            0.7 * w + 0.3 * momentumWeights[i]
        }.toDoubleArray()
    }
}
```

### 7.2 Volatility Targeting

```kotlin
fun volatilityTargeting(
    currentWeights: DoubleArray,
    covarianceMatrix: Array<DoubleArray>,
    targetVolatility: Double = 0.15,  // 연 15% 변동성 목표
    maxLeverage: Double = 1.5
): DoubleArray {
    val currentVol = sqrt(
        currentWeights.dot(covarianceMatrix).dot(currentWeights)
    )

    // 변동성 스케일링 팩터
    val scaleFactor = minOf(
        targetVolatility / currentVol,
        maxLeverage
    ).coerceAtLeast(0.5)  // 최소 50% 레버리지

    return currentWeights.map { it * scaleFactor }.toDoubleArray()
}
```

---

## 제8부: 실행 고려사항 (Execution Considerations)

### 8.1 Market Impact Modeling

**Almgren-Chriss Model**:
```
Total Cost = Market Impact + Timing Risk

Market Impact = a × (X/V)^(1-α) + b × X

X: 실행 수량
V: 일일 거래량
α: 0.5~1.0 (암호화폐는 0.7~0.9)
```

```kotlin
fun estimateMarketImpact(
    orderSize: Double,
    dailyVolume: Double,
    currentPrice: Double,
    alpha: Double = 0.8,
    a: Double = 0.1,
    b: Double = 0.01
): Double {
    val temporaryImpact = a * pow(orderSize / dailyVolume, 1 - alpha)
    val permanentImpact = b * orderSize / dailyVolume
    return currentPrice * (temporaryImpact + permanentImpact)
}
```

### 8.2 Optimal Execution (TWAP / VWAP)

```kotlin
// TWAP (Time-Weighted Average Price)
fun calculateTWAPSlices(
    totalQuantity: Double,
    executionWindow: Int,  // 분 단위
    sliceInterval: Int = 5  // 5분마다
): List<Double> {
    val nSlices = executionWindow / sliceInterval
    val sliceSize = totalQuantity / nSlices
    return List(nSlices) { sliceSize }
}

// VWAP (Volume-Weighted Average Price) 추정
fun estimateVWAP(
    orderBook: OrderBook,
    quantity: Double
): Double {
    var remainingQty = quantity
    var totalValue = 0.0
    var totalQty = 0.0

    for (level in orderBook.asks) {
        if (remainingQty <= 0) break

        val execQty = minOf(remainingQty, level.quantity)
        totalValue += execQty * level.price
        totalQty += execQty
        remainingQty -= execQty
    }

    return if (totalQty > 0) totalValue / totalQty else 0.0
}
```

---

## 제9부: 기존 컨플루언스 전략과의 통합

### 9.1 컨플루언스 기반 뷰 생성

```kotlin
class ConfluenceBasedViewGenerator(
    private val technicalAnalyzer: TechnicalAnalyzer,
    private val marketRegimeDetector: MarketRegimeDetector
) {
    fun generateView(
        market: String,
        currentTime: Instant
    ): View? {
        val confluenceScore = technicalAnalyzer.calculateConfluenceScore(market)

        return when {
            confluenceScore >= 80 -> {
                // 강한 매수 신호
                View(
                    assets = listOf(getAssetIndex(market)),
                    viewReturn = 0.05,  // +5% 기대
                    confidence = minOf(1.0, confluenceScore / 100.0)
                )
            }
            confluenceScore >= 60 -> {
                // 보통 매수 신호
                View(
                    assets = listOf(getAssetIndex(market)),
                    viewReturn = 0.02,  // +2% 기대
                    confidence = 0.5
                )
            }
            confluenceScore <= 20 -> {
                // 강한 매도 신호
                View(
                    assets = listOf(getAssetIndex(market)),
                    viewReturn = -0.03,  // -3% 기대
                    confidence = minOf(1.0, (100 - confluenceScore) / 100.0)
                )
            }
            else -> null  // 중립: 뷰 없음
        }
    }
}
```

### 9.2 리스크 패리티 기반 포지션 사이징

```kotlin
fun calculatePositionSizeWithRiskParity(
    signalStrength: Double,        // 0~1 (컨플루언스 점수/100)
    totalCapital: Double,
    riskBudget: Double = 0.02,     // 자본의 2% 리스크
    assetVolatility: Double,       // 연 변동성
    portfolioVolatility: Double = 0.2  // 포트폴리오 목표 변동성
): Double {
    // 리스크 패리티 기반 할당
    val riskParityWeight = minOf(
        portfolioVolatility / assetVolatility,
        1.0
    )

    // 신호 강도에 따른 조정
    val adjustedWeight = riskParityWeight * signalStrength

    // 최종 포지션 크기
    val capitalAtRisk = totalCapital * riskBudget
    val positionSize = (capitalAtRisk / assetVolatility) * adjustedWeight

    // 최대 10%, 최소 1% 제한
    return positionSize.coerceIn(totalCapital * 0.01, totalCapital * 0.10)
}
```

---

## 제10부: 전체 통합 프레임워크

### 10.1 Adaptive Portfolio Management System

```kotlin
class AdaptiveCryptoPortfolioManager(
    private val riskFreeRate: Double = 0.02,
    private val maxPositionSize: Double = 0.10,
    private val rebalanceThreshold: Double = 0.05
) {
    // 1. 장기 전략적 배분 (Strategic Asset Allocation)
    private val strategicWeights = calculateStrategicWeights(
        method = "HRP",  // Hierarchical Risk Parity
        lookbackDays = 90
    )

    // 2. 전술적 배분 (Tactical Asset Allocation)
    fun calculateTacticalWeights(
        currentPrices: Map<String, Double>,
        marketRegime: MarketRegime
    ): Map<String, Double> {
        // 모멘텀 점수 계산
        val momentumScores = calculateMomentumScores(currentPrices)

        // 시장 레짐에 따른 조정
        val regimeAdjustments = when (marketRegime) {
            MarketRegime.BULL -> mapOf("BTC" to 1.2, "Altcoins" to 1.5)
            MarketRegime.BEAR -> mapOf("BTC" to 0.8, "Altcoins" to 0.5)
            MarketRegime.SIDEWAYS -> mapOf("BTC" to 1.0, "Altcoins" to 1.0)
        }

        // 전술적 가중치 = Strategic × Momentum × Regime
        return strategicWeights.mapValues { (asset, baseWeight) ->
            val momentumAdj = momentumScores[asset] ?: 1.0
            val regimeAdj = regimeAdjustments[asset] ?: 1.0
            (baseWeight * momentumAdj * regimeAdj).coerceIn(0.0, 1.0)
        }.normalize()
    }

    // 3. Black-Litterman 뷰 생성 (컨플루언스 기반)
    fun generateBLViews(
        confluenceSignals: Map<String, ConfluenceResult>
    ): List<View> {
        return confluenceSignals.mapNotNull { (market, signal) ->
            when {
                signal.score >= 75 -> View(
                    assets = listOf(market),
                    viewReturn = signal.expectedReturn,
                    confidence = signal.confidence
                )
                signal.score <= 25 -> View(
                    assets = listOf(market),
                    viewReturn = -signal.expectedReturn * 0.5,
                    confidence = signal.confidence
                )
                else -> null
            }
        }
    }

    // 4. 최종 가중치 계산
    fun calculateOptimalWeights(
        tacticalWeights: Map<String, Double>,
        blViews: List<View>,
        covarianceMatrix: CovarianceMatrix
    ): Map<String, Double> {
        // Black-Litterman 최적화
        val blReturns = calculateBlackLittermanReturns(
            impliedReturns = calculateImpliedReturns(tacticalWeights),
            views = blViews,
            covarianceMatrix = covarianceMatrix
        )

        // 리스크 패리티 제약 조건 하에서 Mean-Variance 최적화
        val optimalWeights = solveConstrainedOptimization(
            expectedReturns = blReturns,
            covarianceMatrix = covarianceMatrix,
            riskParityConstraint = true,
            maxPositionSize = maxPositionSize
        )

        return optimalWeights.normalize()
    }

    // 5. 포지션 사이징 (Kelly Criterion + Risk Parity)
    fun calculatePositionSizes(
        optimalWeights: Map<String, Double>,
        totalCapital: Double,
        assetVolatilities: Map<String, Double>,
        confluenceScores: Map<String, Double>
    ): Map<String, Double> {
        return optimalWeights.mapValues { (asset, weight) ->
            val baseSize = totalCapital * weight

            // Kelly Criterion 기반 조정
            val winRate = estimateWinRate(asset)
            val riskReward = estimateRiskReward(asset)
            val kellyFraction = calculateKellyFraction(winRate, riskReward) / 2  // Half Kelly

            // 컨플루언스 신호 강도 반영
            val signalMultiplier = confluenceScores[asset]?.let {
                when {
                    it >= 80 -> 1.5
                    it >= 60 -> 1.2
                    it >= 40 -> 1.0
                    else -> 0.5
                }
            } ?: 1.0

            baseSize * kellyFraction * signalMultiplier
        }
    }
}
```

### 10.2 백테스팅 및 평가

```kotlin
data class BacktestResult(
    val totalReturn: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double,
    val calmarRatio: Double
)

fun backtestPortfolioStrategy(
    historicalData: Map<String, List<OHLCV>>,
    strategy: (Map<String, OHLCV>) -> Map<String, Double>
): BacktestResult {
    val portfolioValues = mutableListOf<Double>()
    val returns = mutableListOf<Double>()
    val weightsHistory = mutableListOf<Map<String, Double>>()

    var currentValue = 1_000_000.0  // 초기 자본 100만원
    var currentWeights = mapOf<String, Double>()

    // 롤링 백테스트
    for (i in 30 until historicalData.values.first().size) {  // 30일 skip
        val currentDate = historicalData.values.first()[i].timestamp
        val currentPrices = historicalData.mapValues { it.value[i].close }

        // 리밸런싱 체크 (주 1회)
        if (shouldRebalance(currentDate, weightsHistory.lastOrNull())) {
            val lookbackData = getLookbackData(historicalData, i, lookback = 30)
            currentWeights = strategy(lookbackData)
        }

        // 일일 수익률 계산
        val previousPrices = historicalData.mapValues { it.value[i - 1].close }
        val dailyReturns = currentPrices.mapValues { (symbol, price) ->
            (price - previousPrices[symbol]!!) / previousPrices[symbol]!!
        }

        val portfolioReturn = currentWeights.mapValues { (symbol, weight) ->
            weight * dailyReturns[symbol]!!
        }.values.sum()

        currentValue *= (1 + portfolioReturn)
        portfolioValues.add(currentValue)
        returns.add(portfolioReturn)
        weightsHistory.add(currentWeights)
    }

    // 성과 지표 계산
    val totalReturn = (currentValue - 1_000_000) / 1_000_000
    val sharpeRatio = calculateSharpeRatio(returns, riskFreeRate = 0.02)
    val sortinoRatio = calculateSortinoRatio(returns)
    val maxDrawdown = calculateMaxDrawdown(portfolioValues)
    val winRate = returns.count { it > 0 }.toDouble() / returns.size
    val profitFactor = returns.filter { it > 0 }.sum() / abs(returns.filter { it < 0 }.sum())
    val calmarRatio = totalReturn / abs(maxDrawdown)

    return BacktestResult(
        totalReturn = totalReturn,
        sharpeRatio = sharpeRatio,
        sortinoRatio = sortinoRatio,
        maxDrawdown = maxDrawdown,
        winRate = winRate,
        profitFactor = profitFactor,
        calmarRatio = calmarRatio
    )
}
```

---

## 제11부: 기존 핵심 원칙 (유지)

### 11.1 단일 지표는 무의미하다

| 지표 수 | 백테스트 승률 | 실전 승률 |
|--------|-------------|----------|
| 1개 | 50-55% | 45-50% |
| 2개 | 65-70% | 60-65% |
| 3개 | 75-80% | 70-75% |
| 4개+ | 85-90% | 80-85% |

**항상 컨플루언스(Confluence)를 추구한다.**

### 11.2 리스크 관리가 수익보다 중요하다

- 단일 거래 리스크: 자본의 **1-2%**
- 일일 최대 손실: 자본의 **5%**
- 동시 포지션: **최대 3개**
- 손절은 필수, 익절은 선택

### 11.3 시장 레짐에 맞는 전략을 사용한다

| 레짐 | 특성 | 적합 전략 |
|------|------|----------|
| **추세장** | 방향성 명확 | 추세추종, MACD, 이치모쿠 |
| **횡보장** | 범위 제한 | 평균회귀, 볼린저밴드, RSI |
| **고변동** | 급등락 | ATR 기반 손절, 포지션 축소 |

---

## 제12부: 기술적 지표 요약 (유지)

### RSI (Relative Strength Index)

**계산**: `RSI = 100 - (100 / (1 + RS))`, RS = 평균상승/평균하락

| 설정 | 표준 | 암호화폐 |
|------|------|----------|
| 기간 | 14 | 9-14 |
| 과매수 | 70 | 75-80 |
| 과매도 | 30 | 20-25 |

**핵심 전략**:
- RSI 30 하향 후 상향 돌파 + 200 EMA 위 = **매수**
- RSI 다이버전스 = **반전 신호**

> 상세: [references/indicators.md](references/indicators.md#rsi)

### MACD (Moving Average Convergence Divergence)

**계산**: MACD = 12 EMA - 26 EMA, Signal = MACD의 9 EMA

**핵심 전략**:
- MACD 선이 시그널 상향 크로스 + RSI 30-50 = **매수** (77% 승률)
- 히스토그램 반전 = 조기 진입 신호
- 제로라인 크로스 = 추세 전환

> 상세: [references/indicators.md](references/indicators.md#macd)

### 볼린저밴드 (Bollinger Bands)

**계산**: 중심 = 20 SMA, 상/하단 = 중심 ± 2σ

**핵심 전략**:
- %B ≤ 0 + MACD 상승 반전 = **매수** (78% 승률)
- 밴드 스퀴즈 후 돌파 = 큰 움직임 예고
- W 바텀 패턴 = 강한 반등 신호

> 상세: [references/indicators.md](references/indicators.md#bollinger-bands)

### ATR (Average True Range)

**용도**: 변동성 측정, 동적 손절

| 시장 상황 | ATR 배수 | 용도 |
|----------|---------|------|
| 횡보 | 1.5-2x | 타이트한 손절 |
| 추세 | 3-4x | 여유 있는 손절 |

**손절 공식**: `Stop Loss = Entry ± (ATR × Multiplier)`

> 상세: [references/indicators.md](references/indicators.md#atr)

---

## 제13부: 전략 유형별 가이드 (유지)

### 스캘핑 (1-15분)

- **지표**: RSI(9), MACD(5/13/6), 호가창 Imbalance
- **목표**: +0.3-0.5%
- **손절**: -0.2%
- **핵심**: 스프레드 + 수수료 < 목표 수익

### 단타 (15분-4시간)

- **지표**: RSI(14), MACD(8/21/5), 볼린저(20,2)
- **목표**: +2-5%
- **손절**: -1-2%
- **핵심**: 컨플루언스 3개 이상

### 스윙 (1일-1주)

- **지표**: RSI(14), MACD(12/26/9), 이치모쿠
- **목표**: +10-20%
- **손절**: -5%
- **핵심**: 일봉 추세 방향 확인

### 장기투자 (1개월+)

- **지표**: 200 EMA, MVRV, 온체인 축적
- **목표**: +50-100%+
- **손절**: -20% 또는 펀더멘털 붕괴
- **핵심**: DCA, 분할 매수

> 상세: [references/strategies.md](references/strategies.md)

---

## 제14부: 체크리스트 (유지)

### 진입 전 확인

```
□ 시장 레짐 확인 (추세/횡보/고변동)
□ 멀티 타임프레임 정렬 확인
□ 컨플루언스 점수 ≥ 75
□ 리스크 = 자본의 1-2%
□ R:R ≥ 2:1
□ 뉴스/이벤트 확인
□ 펌프앤덤프 필터 통과
□ 포트폴리오 리스크 기여도 확인
```

### 진입 후 관리

```
□ 손절 주문 즉시 설정
□ 트레일링 스탑 조건 설정
□ 익절 목표 설정 (분할 청산)
□ 포지션 기록 (회고용)
□ 포트폴리오 리밸런싱 체크
```

---

## 참조 파일

| 파일 | 내용 |
|------|------|
| [references/indicators.md](references/indicators.md) | RSI, MACD, 볼린저, ATR, 이치모쿠, 피보나치 상세 |
| [references/confluence.md](references/confluence.md) | 컨플루언스 전략 상세, 코드 예시 |
| [references/risk-management.md](references/risk-management.md) | Kelly 공식, 포지션 사이징, 손절/익절 |
| [references/portfolio-theory.md](references/portfolio-theory.md) | MPT, Black-Litterman, Risk Parity, HRP |
| [references/factor-models.md](references/factor-models.md) | CAPM, Fama-French, PCA Factor Models |
| [references/robust-optimization.md](references/robust-optimization.md) | Robust Optimization, Resampling |
| [references/pump-dump.md](references/pump-dump.md) | 펌프앤덤프 탐지, ML 연구 |
| [references/onchain.md](references/onchain.md) | 온체인 지표, 웨일 추적 |
| [references/sentiment.md](references/sentiment.md) | 뉴스 감성 분석, LLM 활용 |
| [references/regime.md](references/regime.md) | 시장 레짐 감지, GARCH/GMM |
| [references/orderbook.md](references/orderbook.md) | 호가창 분석, 유동성 패턴 |
| [references/strategies.md](references/strategies.md) | 전략 유형별 상세 가이드 |

---

*Advanced Portfolio Management 이론 + 2025-2026 암호화폐 시장 연구 기반*
*Markowitz MVO, Black-Litterman, Risk Parity, Kelly Criterion, HRP, Factor Models, Robust Optimization 통합*
