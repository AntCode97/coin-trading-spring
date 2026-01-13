# 전략 유형별 상세 가이드

## 개요

투자 기간과 스타일에 따른 최적화된 전략 가이드.

---

## 스캘핑 (1분-15분)

### 목표 & 특성

| 항목 | 값 |
|------|---|
| 보유 시간 | 1-15분 |
| 목표 수익 | +0.3-0.5% |
| 손절 | -0.2% |
| 일일 거래 수 | 10-50회 |
| 승률 목표 | 55%+ |

### 핵심 지표

```kotlin
// 스캘핑용 지표 설정
val rsiPeriod = 9
val macdFast = 5
val macdSlow = 13
val macdSignal = 6
val bbPeriod = 10
val bbStdDev = 1.5
```

### 진입 조건

```kotlin
data class ScalpSignal(
    val direction: Direction,
    val confidence: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double
)

fun scalpingSignal(
    candles: List<Candle>,  // 1분봉
    orderbook: OrderBook
): ScalpSignal? {
    val rsi = calculateRSI(candles, 9)
    val (macd, signal, _) = calculateMACD(candles, 5, 13, 6)
    val imbalance = calculateImbalance(orderbook)

    // 매수 조건
    val buyCondition = rsi.last() < 35 &&
        macd.crossedAbove(signal) &&
        imbalance.imbalance > 0.2

    // 매도 조건
    val sellCondition = rsi.last() > 65 &&
        macd.crossedBelow(signal) &&
        imbalance.imbalance < -0.2

    val currentPrice = candles.last().close

    return when {
        buyCondition -> ScalpSignal(
            direction = Direction.LONG,
            confidence = 0.6,
            entryPrice = currentPrice,
            stopLoss = currentPrice * 0.998,   // -0.2%
            takeProfit = currentPrice * 1.004   // +0.4%
        )
        sellCondition -> ScalpSignal(
            direction = Direction.SHORT,
            confidence = 0.6,
            entryPrice = currentPrice,
            stopLoss = currentPrice * 1.002,
            takeProfit = currentPrice * 0.996
        )
        else -> null
    }
}
```

### 필수 조건

- **스프레드**: 0.1% 미만 (수수료 포함 0.15% 미만)
- **유동성**: 거래금액 10배 이상
- **시간대**: 09:00-18:00 KST (유동성 높음)
- **수수료**: 최대 0.04% (Bithumb VIP)

### 리스크

```
위험 요소:
- 수수료 누적 (1회 0.08% = 왕복)
- 슬리피지
- 정서적 피로
- 오버트레이딩

권장 일일 제한:
- 최대 손실: 자본의 2%
- 최대 거래: 30회
- 연속 손실 3회 = 1시간 휴식
```

---

## 단타 (15분-4시간)

### 목표 & 특성

| 항목 | 값 |
|------|---|
| 보유 시간 | 15분-4시간 |
| 목표 수익 | +2-5% |
| 손절 | -1-2% |
| 일일 거래 수 | 1-5회 |
| 승률 목표 | 65%+ |

### 핵심 지표

```kotlin
val rsiPeriod = 14
val macdFast = 8
val macdSlow = 21
val macdSignal = 5
val bbPeriod = 20
val bbStdDev = 2.0
val atrPeriod = 14
```

### 4중 컨플루언스 전략 (85% 승률)

```kotlin
fun dayTradingSignal(candles: List<Candle>): TradeSignal? {
    val confluenceScore = calculateConfluence(candles)

    if (confluenceScore < 75) return null

    val atr = calculateATR(candles, 14)
    val currentPrice = candles.last().close

    val positionMultiplier = when (confluenceScore) {
        100 -> 1.5
        75 -> 1.0
        else -> 0.5
    }

    return TradeSignal(
        direction = Direction.LONG,
        confidence = confluenceScore / 100.0,
        entryPrice = currentPrice,
        stopLoss = currentPrice - (atr * 2),
        takeProfit = currentPrice + (atr * 4),  // R:R 2:1
        positionMultiplier = positionMultiplier
    )
}
```

### 타임프레임 분석

```
4시간: 중기 추세 확인
   ↓
1시간: 컨플루언스 점수 계산, 진입 존 결정
   ↓
15분: 정확한 진입 타이밍, 캔들 패턴 확인
```

### 최적 진입 시점

| 패턴 | 점수 보너스 | 설명 |
|------|-----------|------|
| 볼린저 하단 + RSI <30 | +10 | 과매도 반등 |
| MACD 크로스 + 거래량 급증 | +15 | 추세 전환 확인 |
| 피보 골든존 터치 | +10 | 지지 확인 |
| EMA 수렴 후 돌파 | +10 | 추세 시작 |

---

## 스윙 트레이딩 (1일-1주)

### 목표 & 특성

| 항목 | 값 |
|------|---|
| 보유 시간 | 1-7일 |
| 목표 수익 | +10-20% |
| 손절 | -5% |
| 주간 거래 수 | 1-3회 |
| 승률 목표 | 60%+ |

### 핵심 지표

```kotlin
val rsiPeriod = 14
val macdFast = 12
val macdSlow = 26
val macdSignal = 9
val ichimokuTenkan = 20  // 암호화폐 설정
val ichimokuKijun = 60
val ichimokuSenkou = 120
```

### 이치모쿠 기반 전략

```kotlin
data class SwingSetup(
    val direction: Direction,
    val strength: Strength,
    val entryZone: PriceRange,
    val stopLoss: Double,
    val targets: List<Double>
)

fun swingTradeSetup(dailyCandles: List<Candle>): SwingSetup? {
    val ichimoku = calculateIchimoku(dailyCandles, 20, 60, 120, 30)
    val rsi = calculateRSI(dailyCandles, 14)
    val currentPrice = dailyCandles.last().close

    // 강세 셋업: 가격 > 구름, 전환선 > 기준선
    val bullishSetup = currentPrice > ichimoku.cloud.top &&
        ichimoku.tenkan > ichimoku.kijun &&
        rsi.last() < 70

    if (!bullishSetup) return null

    // 진입 존: 기준선 ~ 전환선
    val entryZone = PriceRange(ichimoku.kijun, ichimoku.tenkan)

    // 손절: 구름 하단 아래
    val stopLoss = ichimoku.cloud.bottom * 0.98

    // 목표: 피보나치 확장
    val range = currentPrice - ichimoku.cloud.bottom
    val targets = listOf(
        currentPrice + range * 0.618,
        currentPrice + range * 1.0,
        currentPrice + range * 1.618
    )

    return SwingSetup(
        direction = Direction.LONG,
        strength = if (ichimoku.cloud.isThick) Strength.STRONG else Strength.MODERATE,
        entryZone = entryZone,
        stopLoss = stopLoss,
        targets = targets
    )
}
```

### 분할 진입/청산

```kotlin
data class ScaledEntry(
    val entries: List<EntryLevel>,
    val exits: List<ExitLevel>
)

fun createScaledPlan(
    setup: SwingSetup,
    totalCapital: Double
): ScaledEntry {
    val positionSize = totalCapital * 0.02  // 2% 리스크

    val entries = listOf(
        EntryLevel(setup.entryZone.high, positionSize * 0.33, "1차 진입"),
        EntryLevel(setup.entryZone.mid, positionSize * 0.33, "2차 진입"),
        EntryLevel(setup.entryZone.low, positionSize * 0.34, "3차 진입")
    )

    val exits = listOf(
        ExitLevel(setup.targets[0], 0.33, "1/3 익절"),
        ExitLevel(setup.targets[1], 0.33, "2/3 익절"),
        ExitLevel(setup.targets[2], 0.34, "전량 청산")
    )

    return ScaledEntry(entries, exits)
}
```

### 온체인 통합

```kotlin
fun enhanceWithOnChain(
    technicalSetup: SwingSetup,
    onchain: OnChainMetrics
): EnhancedSetup {
    var confidenceBonus = 0.0

    // MVRV 저평가 = 강세 보너스
    if (onchain.mvrv < 1.0) confidenceBonus += 0.1

    // 거래소 순유출 = 강세 보너스
    if (onchain.exchangeNetFlow < -1000) confidenceBonus += 0.1

    // 축적 점수 높음 = 강세 보너스
    if (onchain.accumulationScore > 0.8) confidenceBonus += 0.1

    return technicalSetup.copy(
        confidence = minOf(1.0, technicalSetup.confidence + confidenceBonus),
        onchainSupport = confidenceBonus > 0.15
    )
}
```

---

## 장기투자 / HODL (1개월+)

### 목표 & 특성

| 항목 | 값 |
|------|---|
| 보유 시간 | 1개월-수년 |
| 목표 수익 | +50-200%+ |
| 손절 | -20% 또는 펀더멘털 붕괴 |
| 연간 거래 수 | 4-12회 |
| 목표 | 시장 대비 초과 수익 |

### DCA (Dollar Cost Averaging)

```kotlin
data class DCASchedule(
    val frequency: Frequency,
    val amount: Double,
    val conditions: List<DCACondition>
)

enum class Frequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY }

sealed class DCACondition {
    object AlwaysBuy : DCACondition()
    data class RSIBelow(val threshold: Double) : DCACondition()
    data class MVRVBelow(val threshold: Double) : DCACondition()
    data class PriceBelow200EMA(val buffer: Double) : DCACondition()
}

fun evaluateDCA(
    schedule: DCASchedule,
    currentPrice: Double,
    rsi: Double,
    mvrv: Double,
    ema200: Double
): Boolean {
    return schedule.conditions.any { condition ->
        when (condition) {
            is DCACondition.AlwaysBuy -> true
            is DCACondition.RSIBelow -> rsi < condition.threshold
            is DCACondition.MVRVBelow -> mvrv < condition.threshold
            is DCACondition.PriceBelow200EMA ->
                currentPrice < ema200 * (1 + condition.buffer)
        }
    }
}
```

### 가치 기반 매수

```kotlin
data class AccumulationZone(
    val priceRange: PriceRange,
    val mvrvRange: ClosedFloatingPointRange<Double>,
    val nuplRange: ClosedFloatingPointRange<Double>,
    val accumulationIntensity: Double  // 0-1
)

fun identifyAccumulationZone(
    historicalMVRV: List<Double>,
    historicalNUPL: List<Double>,
    currentPrice: Double
): AccumulationZone {
    val mvrvPercentile20 = percentile(historicalMVRV, 0.2)
    val nuplPercentile20 = percentile(historicalNUPL, 0.2)

    val intensity = when {
        historicalMVRV.last() < mvrvPercentile20 &&
        historicalNUPL.last() < nuplPercentile20 -> 1.0  // 극적 축적
        historicalMVRV.last() < 1.0 -> 0.7               // 강한 축적
        historicalMVRV.last() < 1.5 -> 0.4               // 보통 축적
        else -> 0.1                                       // 약한/없음
    }

    return AccumulationZone(
        priceRange = PriceRange(currentPrice * 0.8, currentPrice * 1.2),
        mvrvRange = 0.5..1.0,
        nuplRange = -0.5..0.25,
        accumulationIntensity = intensity
    )
}
```

### 사이클 기반 투자

```kotlin
enum class MarketCycle {
    ACCUMULATION,  // 바닥, 강한 매수
    MARKUP,        // 상승 초기, 홀딩
    DISTRIBUTION,  // 고점 근처, 분할 매도
    MARKDOWN       // 하락, 현금 보유/숏
}

fun identifyCycle(
    price: Double,
    ema200: Double,
    mvrv: Double,
    nupl: Double
): MarketCycle {
    return when {
        price < ema200 && mvrv < 1.0 && nupl < 0 -> MarketCycle.ACCUMULATION
        price > ema200 && mvrv in 1.0..2.5 && nupl in 0.0..0.5 -> MarketCycle.MARKUP
        price > ema200 && mvrv > 2.5 && nupl > 0.5 -> MarketCycle.DISTRIBUTION
        price < ema200 && mvrv in 1.0..2.0 -> MarketCycle.MARKDOWN
        else -> MarketCycle.MARKUP  // 기본값
    }
}

fun getAllocation(cycle: MarketCycle): AllocationAdvice {
    return when (cycle) {
        MarketCycle.ACCUMULATION -> AllocationAdvice(
            crypto = 0.7, stablecoin = 0.3, action = "적극 매수"
        )
        MarketCycle.MARKUP -> AllocationAdvice(
            crypto = 0.8, stablecoin = 0.2, action = "홀딩, 트레일링"
        )
        MarketCycle.DISTRIBUTION -> AllocationAdvice(
            crypto = 0.4, stablecoin = 0.6, action = "분할 매도"
        )
        MarketCycle.MARKDOWN -> AllocationAdvice(
            crypto = 0.2, stablecoin = 0.8, action = "관망, 매수 대기"
        )
    }
}
```

---

## 그리드 트레이딩

### 설정

```kotlin
data class GridConfig(
    val basePrice: Double,
    val gridCount: Int,
    val gridSpacing: Double,  // %
    val amountPerGrid: Double,
    val upperLimit: Double,
    val lowerLimit: Double
)

fun createGrid(config: GridConfig): List<GridLevel> {
    val levels = mutableListOf<GridLevel>()

    for (i in -config.gridCount/2..config.gridCount/2) {
        val price = config.basePrice * (1 + config.gridSpacing * i / 100)

        if (price < config.lowerLimit || price > config.upperLimit) continue

        levels.add(GridLevel(
            price = price,
            buyOrder = i < 0,  // 기준가 아래 = 매수
            sellOrder = i > 0, // 기준가 위 = 매도
            amount = config.amountPerGrid
        ))
    }

    return levels
}
```

### 적합 시장

| 조건 | 필수 여부 | 이유 |
|------|----------|------|
| 횡보장 | 필수 | 그리드 수익 발생 |
| 낮은 변동성 | 권장 | 손절 방지 |
| 충분한 자본 | 필수 | 그리드 전체 커버 |

---

## 평균회귀 (Mean Reversion)

### 볼린저밴드 전략

```kotlin
fun meanReversionSignal(
    candles: List<Candle>,
    regime: MarketRegime
): TradeSignal? {
    // 횡보장에서만 사용
    if (regime.trend != TrendRegime.RANGING) return null

    val (upper, middle, lower) = calculateBollingerBands(candles, 20, 2.0)
    val percentB = calculatePercentB(candles.last().close, upper, lower)
    val rsi = calculateRSI(candles, 14)

    val currentPrice = candles.last().close

    // 매수: 하단밴드 + RSI 과매도
    if (percentB <= 0 && rsi.last() < 30) {
        return TradeSignal(
            direction = Direction.LONG,
            entryPrice = currentPrice,
            stopLoss = lower * 0.98,
            takeProfit = middle,  // 중심선으로 회귀
            confidence = 0.7
        )
    }

    // 매도: 상단밴드 + RSI 과매수
    if (percentB >= 1 && rsi.last() > 70) {
        return TradeSignal(
            direction = Direction.SHORT,
            entryPrice = currentPrice,
            stopLoss = upper * 1.02,
            takeProfit = middle,
            confidence = 0.7
        )
    }

    return null
}
```

---

## 브레이크아웃

### 볼린저 스퀴즈 돌파

```kotlin
fun breakoutSignal(
    candles: List<Candle>,
    regime: VolatilityRegime
): TradeSignal? {
    // 스퀴즈 상태에서만
    if (regime.percentile > 10) return null

    val (upper, middle, lower) = calculateBollingerBands(candles, 20, 2.0)
    val currentPrice = candles.last().close
    val prevPrice = candles[candles.size - 2].close
    val volume = candles.last().volume
    val avgVolume = candles.takeLast(20).map { it.volume }.average()

    // 상단 돌파 + 거래량 급증
    if (currentPrice > upper && prevPrice <= upper && volume > avgVolume * 1.5) {
        val atr = calculateATR(candles, 14)
        return TradeSignal(
            direction = Direction.LONG,
            entryPrice = currentPrice,
            stopLoss = middle,  // 중심선 하회 시 실패
            takeProfit = currentPrice + (atr * 3),
            confidence = 0.75
        )
    }

    return null
}
```

---

## 전략 비교표

| 전략 | 보유 기간 | 목표 수익 | 손절 | 적합 레짐 | 난이도 |
|------|----------|----------|------|----------|--------|
| 스캘핑 | 1-15분 | 0.3-0.5% | 0.2% | 모든 레짐 | 상 |
| 단타 | 15분-4시간 | 2-5% | 1-2% | 모든 레짐 | 중상 |
| 스윙 | 1-7일 | 10-20% | 5% | 추세 | 중 |
| HODL | 1개월+ | 50%+ | 20% | 장기 상승 | 하 |
| 그리드 | 지속 | 0.5%/거래 | 설정별 | 횡보 | 중 |
| 평균회귀 | 1시간-1일 | 3-5% | 2% | 횡보 | 중상 |
| 브레이크아웃 | 1시간-1일 | 5-10% | 3% | 스퀴즈 후 | 중 |

---

## 체크리스트: 전략 선택

```
1. 현재 시장 레짐 확인
   □ ADX > 40 = 추세추종
   □ ADX < 20 = 평균회귀/그리드
   □ 볼린저 스퀴즈 = 브레이크아웃 대기

2. 시간 가용성 확인
   □ 풀타임 모니터링 가능 = 스캘핑/단타
   □ 1일 1-2회 체크 = 스윙
   □ 주 1회 체크 = HODL/DCA

3. 리스크 성향 확인
   □ 공격적 = 스캘핑, 브레이크아웃
   □ 중립 = 단타, 스윙
   □ 보수적 = DCA, 그리드

4. 자본 규모 확인
   □ 소액 (100만 미만) = 단타, 스윙
   □ 중액 (100만-1000만) = 모든 전략
   □ 대액 (1000만+) = 그리드, DCA 유리
```
