# 리스크 관리 완벽 가이드

## 핵심 원칙

> "좋은 트레이더는 수익보다 리스크 관리로 정의된다."

### 황금률

1. **단일 거래 리스크**: 자본의 **1-2%**
2. **일일 최대 손실**: 자본의 **5%**
3. **동시 포지션**: **최대 3개**
4. **상관관계 포지션**: 동일 방향 자산 합산 3% 이내

---

## Kelly Criterion (켈리 공식)

### 공식

```
f* = (bp - q) / b

f* = 최적 베팅 비율
b  = 손익비 (Risk-Reward Ratio)
p  = 승률
q  = 1 - p (패배 확률)
```

### 실전 예시

| 승률 (p) | R:R (b) | Full Kelly | Half Kelly | Quarter Kelly |
|----------|---------|------------|------------|---------------|
| 50% | 2.0 | 25% | 12.5% | 6.25% |
| 55% | 2.0 | 30% | 15% | 7.5% |
| 60% | 1.5 | 20% | 10% | 5% |
| 70% | 1.5 | 36.7% | 18.3% | 9.2% |
| 85% | 2.0 | 57.5% | 28.8% | 14.4% |

### 암호화폐에서 Half Kelly 권장 이유

- **변동성**: Full Kelly = 50-70% 드로다운 가능
- **블랙스완**: 해킹, 규제 등 예측 불가 이벤트
- **심리적 안정**: 지속 가능한 트레이딩

### 코드 구현

```kotlin
data class KellyResult(
    val fullKelly: Double,
    val halfKelly: Double,
    val quarterKelly: Double,
    val recommendedSize: Double,
    val maxPositionKRW: Double
)

fun calculateKelly(
    winRate: Double,
    riskReward: Double,
    capital: Double,
    confidence: Double = 0.85  // 컨플루언스 신뢰도
): KellyResult {
    val p = winRate
    val q = 1 - winRate
    val b = riskReward

    // Kelly 공식
    val fullKelly = ((b * p) - q) / b

    // 음수면 베팅하지 않음
    if (fullKelly <= 0) {
        return KellyResult(0.0, 0.0, 0.0, 0.0, 0.0)
    }

    val halfKelly = fullKelly / 2
    val quarterKelly = fullKelly / 4

    // 신뢰도에 따른 조정
    val adjustedKelly = halfKelly * confidence

    // 최대 5% 제한 (안전장치)
    val recommended = minOf(adjustedKelly, 0.05)

    return KellyResult(
        fullKelly = fullKelly,
        halfKelly = halfKelly,
        quarterKelly = quarterKelly,
        recommendedSize = recommended,
        maxPositionKRW = capital * recommended
    )
}

// 사용 예시
val result = calculateKelly(
    winRate = 0.70,
    riskReward = 2.0,
    capital = 1_000_000.0,
    confidence = 0.85
)
// result.recommendedSize = 약 4.25%
// result.maxPositionKRW = 42,500 KRW
```

---

## 포지션 사이징 전략

### 1. 고정 비율 (Fixed Fractional)

```kotlin
fun fixedFractionalSize(
    capital: Double,
    riskPercent: Double,  // 0.01 ~ 0.02 권장
    entryPrice: Double,
    stopLossPrice: Double
): Double {
    val riskAmount = capital * riskPercent
    val stopDistance = abs(entryPrice - stopLossPrice)
    val stopPercent = stopDistance / entryPrice

    return riskAmount / stopDistance
}
```

### 2. ATR 기반 사이징

변동성에 따라 자동 조정:

```kotlin
fun atrBasedSize(
    capital: Double,
    riskPercent: Double,
    entryPrice: Double,
    atr: Double,
    atrMultiplier: Double = 2.0
): PositionSizing {
    val riskAmount = capital * riskPercent
    val stopDistance = atr * atrMultiplier
    val stopLossPrice = entryPrice - stopDistance  // Long 기준

    val positionSize = riskAmount / stopDistance
    val positionValue = positionSize * entryPrice

    return PositionSizing(
        quantity = positionSize,
        valueKRW = positionValue,
        stopLossPrice = stopLossPrice,
        riskAmount = riskAmount
    )
}
```

### 3. 변동성 조정 사이징

시장 변동성에 반비례:

```kotlin
fun volatilityAdjustedSize(
    capital: Double,
    baseRisk: Double,
    currentATR: Double,
    avgATR: Double
): Double {
    val volatilityRatio = avgATR / currentATR

    // 변동성 높으면 포지션 축소
    val adjustedRisk = baseRisk * volatilityRatio

    // 최소 0.5%, 최대 2%
    return adjustedRisk.coerceIn(0.005, 0.02)
}
```

### 4. 컨플루언스 점수 기반 사이징

```kotlin
fun confluenceBasedSize(
    baseSize: Double,
    confluenceScore: Int
): Double {
    val multiplier = when (confluenceScore) {
        100 -> 1.5
        75 -> 1.0
        50 -> 0.5
        else -> 0.0  // 진입 불가
    }
    return baseSize * multiplier
}
```

---

## 손절 (Stop Loss) 전략

### 1. ATR 기반 손절 (권장)

```kotlin
data class StopLoss(
    val price: Double,
    val percent: Double,
    val type: StopType
)

enum class StopType { FIXED, ATR, TRAILING, BREAK_EVEN }

fun calculateATRStop(
    entryPrice: Double,
    atr: Double,
    multiplier: Double,
    isLong: Boolean
): StopLoss {
    val stopDistance = atr * multiplier
    val stopPrice = if (isLong) {
        entryPrice - stopDistance
    } else {
        entryPrice + stopDistance
    }
    val stopPercent = stopDistance / entryPrice * 100

    return StopLoss(
        price = stopPrice,
        percent = stopPercent,
        type = StopType.ATR
    )
}

// 시장 상황별 ATR 배수
fun getATRMultiplier(regime: MarketRegime): Double {
    return when (regime) {
        MarketRegime.LOW_VOLATILITY -> 1.5
        MarketRegime.NORMAL -> 2.0
        MarketRegime.HIGH_VOLATILITY -> 3.0
        MarketRegime.EXTREME -> 4.0
    }
}
```

### 2. 스윙 기반 손절

직전 스윙 저점/고점 기준:

```kotlin
fun swingBasedStop(
    candles: List<Candle>,
    isLong: Boolean,
    buffer: Double = 0.002  // 0.2% 버퍼
): Double {
    val recentCandles = candles.takeLast(20)

    return if (isLong) {
        val swingLow = recentCandles.minOf { it.low }
        swingLow * (1 - buffer)
    } else {
        val swingHigh = recentCandles.maxOf { it.high }
        swingHigh * (1 + buffer)
    }
}
```

### 3. 손절 이동 규칙

| 수익률 | 액션 | 이유 |
|--------|------|------|
| +1% | 손절 유지 | 아직 불확실 |
| +2% | 손절 → 진입가 (손익분기) | 손실 제거 |
| +3% | 손절 → +1% | 수익 보호 |
| +5% | 트레일링 시작 | 추세 추종 |

```kotlin
class DynamicStopManager(
    private val entryPrice: Double,
    private var currentStop: Double
) {
    fun updateStop(currentPrice: Double, atr: Double): Double {
        val profitPercent = (currentPrice - entryPrice) / entryPrice * 100

        currentStop = when {
            profitPercent >= 5 -> {
                // 트레일링: 고점 대비 ATR 1배
                maxOf(currentStop, currentPrice - atr)
            }
            profitPercent >= 3 -> {
                maxOf(currentStop, entryPrice * 1.01)  // +1%
            }
            profitPercent >= 2 -> {
                maxOf(currentStop, entryPrice)  // 손익분기
            }
            else -> currentStop
        }

        return currentStop
    }
}
```

---

## 익절 (Take Profit) 전략

### 1. 분할 익절 (권장)

```kotlin
data class TakeProfitLevel(
    val priceTarget: Double,
    val percentOfPosition: Double,
    val reason: String
)

fun calculateScaledTakeProfit(
    entryPrice: Double,
    atr: Double,
    riskReward: Double
): List<TakeProfitLevel> {
    return listOf(
        TakeProfitLevel(
            priceTarget = entryPrice + (atr * riskReward * 0.5),
            percentOfPosition = 0.33,
            reason = "1/3 익절: 리스크 제거"
        ),
        TakeProfitLevel(
            priceTarget = entryPrice + (atr * riskReward),
            percentOfPosition = 0.33,
            reason = "2/3 익절: 목표 달성"
        ),
        TakeProfitLevel(
            priceTarget = entryPrice + (atr * riskReward * 1.5),
            percentOfPosition = 0.34,
            reason = "3/3 익절: 트레일링 또는 전량 청산"
        )
    )
}
```

### 2. 피보나치 익절

```kotlin
fun fibonacciTakeProfit(
    swingLow: Double,
    swingHigh: Double,
    entryPrice: Double
): List<TakeProfitLevel> {
    val range = swingHigh - swingLow

    return listOf(
        TakeProfitLevel(
            priceTarget = swingHigh + range * 0.272,
            percentOfPosition = 0.25,
            reason = "Fib 127.2%"
        ),
        TakeProfitLevel(
            priceTarget = swingHigh + range * 0.618,
            percentOfPosition = 0.50,
            reason = "Fib 161.8%"
        ),
        TakeProfitLevel(
            priceTarget = swingHigh + range * 1.0,
            percentOfPosition = 0.25,
            reason = "Fib 200%"
        )
    )
}
```

---

## Risk-Reward 최적화

### 최소 R:R 가이드라인

| 승률 | 최소 R:R | 이유 |
|------|---------|------|
| 40% | 2.5:1 | 손실 보전 |
| 50% | 2.0:1 | 수익 창출 |
| 60% | 1.5:1 | 안정적 수익 |
| 70%+ | 1.2:1 | 빈도 우선 가능 |
| 85%+ | 1.0:1 | 컨플루언스 전략 |

### R:R 계산

```kotlin
fun calculateRiskReward(
    entryPrice: Double,
    stopLossPrice: Double,
    takeProfitPrice: Double
): Double {
    val risk = abs(entryPrice - stopLossPrice)
    val reward = abs(takeProfitPrice - entryPrice)
    return reward / risk
}

// 진입 전 검증
fun shouldEnter(rr: Double, winRate: Double): Boolean {
    val minRR = when {
        winRate >= 0.85 -> 1.0
        winRate >= 0.70 -> 1.2
        winRate >= 0.60 -> 1.5
        winRate >= 0.50 -> 2.0
        else -> 2.5
    }
    return rr >= minRR
}
```

---

## 드로다운 관리

### 드로다운 레벨별 대응

| 드로다운 | 액션 | 이유 |
|---------|------|------|
| 5% | 신규 진입 중단 | 냉각 기간 |
| 10% | 포지션 50% 축소 | 리스크 감소 |
| 15% | 모든 포지션 청산 | 전략 재검토 |
| 20% | 1주일 휴식 | 심리적 회복 |

```kotlin
class DrawdownManager(
    private val initialCapital: Double,
    private var peakCapital: Double = initialCapital
) {
    fun update(currentCapital: Double): DrawdownStatus {
        peakCapital = maxOf(peakCapital, currentCapital)
        val drawdown = (peakCapital - currentCapital) / peakCapital * 100

        return when {
            drawdown >= 20 -> DrawdownStatus.STOP_TRADING
            drawdown >= 15 -> DrawdownStatus.LIQUIDATE_ALL
            drawdown >= 10 -> DrawdownStatus.REDUCE_50_PERCENT
            drawdown >= 5 -> DrawdownStatus.PAUSE_NEW_ENTRIES
            else -> DrawdownStatus.NORMAL
        }
    }

    fun getPositionMultiplier(drawdown: Double): Double {
        return when {
            drawdown >= 10 -> 0.5
            drawdown >= 5 -> 0.75
            else -> 1.0
        }
    }
}
```

---

## 상관관계 리스크

### 상관관계 매트릭스 (암호화폐)

| | BTC | ETH | SOL | XRP |
|------|-----|-----|-----|-----|
| BTC | 1.0 | 0.85 | 0.75 | 0.70 |
| ETH | 0.85 | 1.0 | 0.80 | 0.65 |
| SOL | 0.75 | 0.80 | 1.0 | 0.55 |
| XRP | 0.70 | 0.65 | 0.55 | 1.0 |

### 상관관계 리스크 관리

```kotlin
fun calculateCorrelatedRisk(
    positions: List<Position>,
    correlationMatrix: Map<Pair<String, String>, Double>
): Double {
    var totalCorrelatedRisk = 0.0

    for (i in positions.indices) {
        for (j in i + 1 until positions.size) {
            val p1 = positions[i]
            val p2 = positions[j]

            // 같은 방향이면 상관관계 리스크 증가
            if (p1.direction == p2.direction) {
                val correlation = correlationMatrix[Pair(p1.symbol, p2.symbol)] ?: 0.5
                val combinedRisk = (p1.riskPercent + p2.riskPercent) * correlation
                totalCorrelatedRisk += combinedRisk
            }
        }
    }

    return totalCorrelatedRisk
}

// 상관 리스크가 3%를 초과하면 추가 진입 금지
fun canOpenNewPosition(
    existingPositions: List<Position>,
    newPosition: Position,
    correlationMatrix: Map<Pair<String, String>, Double>
): Boolean {
    val allPositions = existingPositions + newPosition
    val correlatedRisk = calculateCorrelatedRisk(allPositions, correlationMatrix)
    return correlatedRisk <= 0.03  // 3% 제한
}
```

---

## 심리적 리스크 관리

### 연속 손실 대응

| 연속 손실 | 다음 포지션 크기 | 휴식 |
|----------|----------------|------|
| 1회 | 100% | 없음 |
| 2회 | 75% | 1시간 |
| 3회 | 50% | 4시간 |
| 4회 | 25% | 24시간 |
| 5회+ | 0% | 전략 재검토 |

```kotlin
class PsychologyManager(
    private var consecutiveLosses: Int = 0
) {
    fun recordTrade(isWin: Boolean) {
        consecutiveLosses = if (isWin) 0 else consecutiveLosses + 1
    }

    fun getPositionMultiplier(): Double {
        return when (consecutiveLosses) {
            0, 1 -> 1.0
            2 -> 0.75
            3 -> 0.50
            4 -> 0.25
            else -> 0.0
        }
    }

    fun getCooldownMinutes(): Int {
        return when (consecutiveLosses) {
            0, 1 -> 0
            2 -> 60
            3 -> 240
            4 -> 1440
            else -> 10080  // 1주일
        }
    }
}
```

---

## 일일 리스크 보고서

```kotlin
data class DailyRiskReport(
    val date: LocalDate,
    val startingCapital: Double,
    val endingCapital: Double,
    val realizedPnL: Double,
    val unrealizedPnL: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val averageRR: Double,
    val tradesCount: Int,
    val riskExposure: Double,  // 현재 리스크 노출
    val correlatedRisk: Double,
    val recommendations: List<String>
)

fun generateDailyReport(trades: List<Trade>, positions: List<Position>): DailyRiskReport {
    // 계산 로직...

    val recommendations = mutableListOf<String>()

    if (maxDrawdown > 5) {
        recommendations.add("드로다운 5% 초과: 신규 진입 자제")
    }
    if (correlatedRisk > 3) {
        recommendations.add("상관 리스크 3% 초과: 포지션 분산 필요")
    }
    if (winRate < 0.5 && tradesCount > 10) {
        recommendations.add("승률 50% 미만: 전략 재검토 권장")
    }

    return DailyRiskReport(
        // ... 값들 채우기
        recommendations = recommendations
    )
}
```

---

## 체크리스트

### 진입 전

```
□ 포지션 크기 = 자본의 1-2%
□ 손절 설정 완료
□ R:R ≥ 2:1 확인
□ 상관 리스크 ≤ 3%
□ 일일 손실 한도 미도달
□ 연속 손실 후 쿨다운 완료
```

### 진입 후

```
□ 손절 주문 활성화
□ +2%에서 손익분기 이동
□ +5%에서 트레일링 시작
□ 분할 익절 설정
□ 포지션 기록
```

### 일일 마감

```
□ 모든 포지션 리뷰
□ 손익 기록
□ 드로다운 확인
□ 다음 날 계획 수립
```
