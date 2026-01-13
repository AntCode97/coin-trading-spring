# 기술적 지표 완벽 가이드

## 목차

1. [RSI](#rsi)
2. [MACD](#macd)
3. [볼린저밴드](#bollinger-bands)
4. [ATR](#atr)
5. [이치모쿠](#ichimoku)
6. [피보나치](#fibonacci)
7. [EMA/SMA](#moving-averages)
8. [Volume Profile](#volume-profile)

---

## RSI

### 계산 공식

```
RS = 평균 상승폭 / 평균 하락폭
RSI = 100 - (100 / (1 + RS))

Wilder's Smoothing:
평균 상승 = (이전 평균 상승 × 13 + 현재 상승) / 14
평균 하락 = (이전 평균 하락 × 13 + 현재 하락) / 14
```

### 암호화폐 최적 설정

| 타임프레임 | 기간 | 과매수 | 과매도 |
|-----------|------|--------|--------|
| 15분 | 9 | 80 | 20 |
| 1시간 | 14 | 75 | 25 |
| 4시간 | 14 | 70 | 30 |
| 일봉 | 14 | 70 | 30 |

### 전략 1: RSI 기본 반전 (65% 승률)

```kotlin
// 매수 조건
val buySignal = rsi.crossedAbove(30) && price > ema200

// 매도 조건
val sellSignal = rsi.crossedBelow(70) && price < ema200
```

### 전략 2: RSI 다이버전스 (75% 승률)

**강세 다이버전스 (Hidden Bullish)**
- 가격: 저점 상승 (Higher Low)
- RSI: 저점 하락 (Lower Low)
- 의미: 추세 지속, 매수 기회

**약세 다이버전스 (Hidden Bearish)**
- 가격: 고점 하락 (Lower High)
- RSI: 고점 상승 (Higher High)
- 의미: 추세 지속, 매도 기회

**일반 다이버전스 (Regular)**
- 가격 ↑ RSI ↓ = 반전 매도
- 가격 ↓ RSI ↑ = 반전 매수

```kotlin
fun detectDivergence(prices: List<Double>, rsiValues: List<Double>): DivergenceType {
    val priceSlope = (prices.last() - prices.first()) / prices.size
    val rsiSlope = (rsiValues.last() - rsiValues.first()) / rsiValues.size

    return when {
        priceSlope > 0 && rsiSlope < 0 -> DivergenceType.BEARISH
        priceSlope < 0 && rsiSlope > 0 -> DivergenceType.BULLISH
        else -> DivergenceType.NONE
    }
}
```

### 전략 3: RSI 레인지 트레이딩 (91% 승률)

**조건**: RSI가 40-60 구간에서 횡보 후 방향 돌파

```kotlin
val rangeBreakout = when {
    rsi.crossedAbove(60) && rsi.wasInRange(40..60, periods = 10) -> Signal.BUY
    rsi.crossedBelow(40) && rsi.wasInRange(40..60, periods = 10) -> Signal.SELL
    else -> Signal.NONE
}
```

### RSI 실수 방지

- 추세장에서 과매수/과매도만 보고 역방향 진입 금지
- RSI 50 라인 = 중요한 지지/저항
- 다이버전스 확인은 최소 5개 캔들 필요

---

## MACD

### 계산 공식

```
MACD Line = EMA(12) - EMA(26)
Signal Line = EMA(MACD Line, 9)
Histogram = MACD Line - Signal Line
```

### 암호화폐 최적 설정

| 타임프레임 | Fast | Slow | Signal |
|-----------|------|------|--------|
| 스캘핑 | 5 | 13 | 6 |
| 단타 | 8 | 21 | 5 |
| 스윙 | 12 | 26 | 9 |
| 장기 | 19 | 39 | 9 |

### 전략 1: 시그널 크로스 (65% 승률)

```kotlin
// 골든 크로스 (매수)
val goldenCross = macd.crossedAbove(signal)

// 데드 크로스 (매도)
val deadCross = macd.crossedBelow(signal)
```

### 전략 2: 제로라인 크로스 (70% 승률)

더 강한 추세 전환 신호:

```kotlin
val strongBuy = macd.crossedAbove(0) && signal < 0
val strongSell = macd.crossedBelow(0) && signal > 0
```

### 전략 3: 히스토그램 반전 (조기 진입)

히스토그램이 줄어들기 시작 = 모멘텀 약화

```kotlin
fun histogramReversal(histogram: List<Double>): Boolean {
    val h1 = histogram[histogram.size - 1]
    val h2 = histogram[histogram.size - 2]
    val h3 = histogram[histogram.size - 3]

    // 음수에서 감소폭 줄어듦 = 매수 준비
    return h3 < h2 && h2 < h1 && h1 < 0
}
```

### 전략 4: MACD + RSI 조합 (77% 승률)

```kotlin
val highProbBuy = macd.crossedAbove(signal)
    && rsi in 30.0..50.0
    && price > ema200

val highProbSell = macd.crossedBelow(signal)
    && rsi in 50.0..70.0
    && price < ema200
```

### MACD 실수 방지

- 횡보장에서 빈번한 크로스 = 손실 발생
- 히스토그램만 보고 진입 금지 (라인 확인 필수)
- 제로라인 근처 크로스 = 노이즈 많음

---

## Bollinger Bands

### 계산 공식

```
Middle Band = SMA(20)
Upper Band = Middle Band + (2 × σ)
Lower Band = Middle Band - (2 × σ)

σ = 20일 표준편차
```

### 파생 지표

**%B**: 현재 가격의 밴드 내 상대 위치
```
%B = (Price - Lower Band) / (Upper Band - Lower Band)
```
- %B > 1: 상단 밴드 돌파
- %B < 0: 하단 밴드 돌파
- %B = 0.5: 중심선

**Bandwidth**: 변동성 측정
```
Bandwidth = (Upper Band - Lower Band) / Middle Band × 100
```
- 낮은 Bandwidth = 스퀴즈 (큰 움직임 예고)
- 높은 Bandwidth = 확장 (추세 지속)

### 전략 1: 밴드 바운스 (평균회귀)

```kotlin
val buyAtLower = price < lowerBand && rsi < 30
val sellAtUpper = price > upperBand && rsi > 70
```

### 전략 2: 밴드 스퀴즈 돌파 (78% 승률)

```kotlin
fun detectSqueeze(bandwidth: List<Double>, lookback: Int = 20): Boolean {
    val currentBW = bandwidth.last()
    val minBW = bandwidth.takeLast(lookback).minOrNull() ?: currentBW
    return currentBW <= minBW * 1.05  // 역사적 최저치 근처
}

val squeezeBuySignal = detectSqueeze(bandwidth)
    && price > upperBand
    && volume > avgVolume * 1.5
```

### 전략 3: W 바텀 패턴

1. 첫 번째 저점: 하단밴드 터치 또는 돌파
2. 반등 후 두 번째 저점: 하단밴드 내부 유지
3. 중심선 돌파 = 매수 신호

```kotlin
val wBottom = previousLow < lowerBand
    && currentLow > lowerBand
    && currentLow > previousLow
    && price.crossedAbove(middleBand)
```

### 전략 4: 볼린저 + MACD (78% 승률)

```kotlin
val strongBuy = percentB <= 0.0
    && macd.crossedAbove(signal)

val strongSell = percentB >= 1.0
    && macd.crossedBelow(signal)
```

### 볼린저 실수 방지

- 추세장에서 상단/하단 역추세 진입 금지
- 스퀴즈 방향은 예측 불가 (돌파 방향만 추종)
- 밴드 터치 = 즉시 반전 아님 (밴드 위/아래 달릴 수 있음)

---

## ATR

### 계산 공식

```
True Range = max(
    High - Low,
    abs(High - Previous Close),
    abs(Low - Previous Close)
)

ATR = EMA(True Range, 14) 또는 Wilder Smoothing
```

### 용도별 ATR 배수

| 용도 | 배수 | 설명 |
|------|------|------|
| 타이트 손절 | 1.5x | 횡보장, 스캘핑 |
| 일반 손절 | 2-2.5x | 기본 설정 |
| 여유 손절 | 3-4x | 추세장, 스윙 |
| 변동성 필터 | N/A | 진입 가능 여부 |

### 전략 1: ATR 트레일링 스탑

```kotlin
class ATRTrailingStop(private val atrMultiplier: Double) {
    private var stopPrice: Double = 0.0
    private var highestSinceEntry: Double = 0.0

    fun update(currentPrice: Double, atr: Double): Double {
        highestSinceEntry = maxOf(highestSinceEntry, currentPrice)
        val newStop = highestSinceEntry - (atr * atrMultiplier)
        stopPrice = maxOf(stopPrice, newStop)  // 스탑은 상승만
        return stopPrice
    }

    fun shouldExit(currentPrice: Double): Boolean = currentPrice < stopPrice
}
```

### 전략 2: 변동성 기반 포지션 사이징

```kotlin
fun calculatePositionSize(
    capital: Double,
    riskPercent: Double,
    entryPrice: Double,
    atr: Double,
    atrMultiplier: Double
): Double {
    val riskAmount = capital * riskPercent
    val stopDistance = atr * atrMultiplier
    val stopPercent = stopDistance / entryPrice
    return riskAmount / stopDistance
}
```

### ATR 해석

| ATR 상태 | 의미 | 액션 |
|---------|------|------|
| ATR 상승 | 변동성 증가 | 포지션 축소, 손절 확대 |
| ATR 하락 | 변동성 감소 | 스퀴즈 준비, 돌파 대기 |
| ATR 역사적 고점 | 패닉/유포리아 | 추세 끝 근접 가능 |

---

## Ichimoku

### 구성 요소

| 요소 | 계산 | 역할 |
|------|------|------|
| 전환선 (Tenkan) | (9H + 9L) / 2 | 단기 모멘텀 |
| 기준선 (Kijun) | (26H + 26L) / 2 | 중기 추세 |
| 선행스팬A | (Tenkan + Kijun) / 2, 26일 앞 | 구름 상단 |
| 선행스팬B | (52H + 52L) / 2, 26일 앞 | 구름 하단 |
| 후행스팬 (Chikou) | 종가, 26일 뒤 | 확인 |

### 암호화폐 설정 (24/7 시장)

| 설정 | 전통 | 암호화폐 |
|------|------|----------|
| 전환선 | 9 | 20 |
| 기준선 | 26 | 60 |
| 선행스팬B | 52 | 120 |
| 변위 | 26 | 30 |

### 전략 1: 구름 돌파 (Kumo Breakout)

```kotlin
val bullishBreakout = price > senkou_span_a
    && price > senkou_span_b
    && previousPrice <= maxOf(senkou_span_a, senkou_span_b)
    && tenkan > kijun

val bearishBreakout = price < senkou_span_a
    && price < senkou_span_b
    && previousPrice >= minOf(senkou_span_a, senkou_span_b)
    && tenkan < kijun
```

### 전략 2: TK 크로스

```kotlin
val tkBullish = tenkan.crossedAbove(kijun) && price > kumo
val tkBearish = tenkan.crossedBelow(kijun) && price < kumo
```

### 전략 3: 구름 두께 분석

```kotlin
val cloudThickness = abs(senkou_span_a - senkou_span_b)
val avgCloudThickness = cloudThickness.average(20)

val strongSupport = cloudThickness > avgCloudThickness * 1.5
val weakSupport = cloudThickness < avgCloudThickness * 0.5
```

### 이치모쿠 체크리스트

5가지 조건 만족 = 강한 신호:
1. 가격 > 구름 (추세 방향)
2. 전환선 > 기준선 (모멘텀)
3. 후행스팬 > 26일 전 가격 (확인)
4. 미래 구름이 강세 (선행스팬A > B)
5. 가격이 기준선 위에서 지지

### 이치모쿠 실수 방지

- 평평한 기준선 = 횡보장, 신호 무시
- 얇은 구름 = 쉬운 돌파/실패
- 가격이 구름 안에 있을 때 = 방향성 없음

---

## Fibonacci

### 핵심 비율

```
황금비 (Golden Ratio) = 1.618
역황금비 = 0.618

리트레이스먼트:
0.236 = 1 - 0.618^3
0.382 = 0.618^2
0.500 = 심리적 수준
0.618 = 황금비 역수
0.786 = 0.618^0.5

익스텐션:
1.272 = 0.618^-0.5
1.618 = 황금비
2.618 = 1.618^2
```

### 전략 1: 골든 존 진입 (50-61.8%)

```kotlin
val goldenZoneEntry = price in fibLevel50..fibLevel618
    && rsi < 50
    && candlePattern == CandlePattern.BULLISH_ENGULFING
```

### 전략 2: 피보나치 + 볼린저 컨플루언스

```kotlin
val strongSupport = abs(fibLevel618 - lowerBollingerBand) / price < 0.01
    // 1% 이내에서 두 레벨 수렴
```

### 전략 3: 익스텐션 목표가

```kotlin
fun calculateTarget(swing: SwingMove): Double {
    val range = abs(swing.high - swing.low)
    return when {
        rsi > 80 -> swing.high + range * 0.272   // 보수적
        rsi in 50.0..80.0 -> swing.high + range * 0.618  // 일반
        else -> swing.high + range * 1.0         // 공격적 (ATH 돌파)
    }
}
```

### 피보나치 실수 방지

- 기준점(스윙 고/저) 선정이 핵심
- 여러 스윙에서 피보 중첩 = 강한 레벨
- 익스텐션은 추세 강도에 따라 조정

---

## Moving Averages

### EMA vs SMA

| 특성 | SMA | EMA |
|------|-----|-----|
| 반응 속도 | 느림 | 빠름 |
| 노이즈 | 적음 | 많음 |
| 지연 | 많음 | 적음 |
| 추세장 | ★★★★ | ★★★★★ |
| 횡보장 | ★★★★★ | ★★★ |

### 핵심 이동평균

| 기간 | 용도 |
|------|------|
| 9 EMA | 초단기 추세, 스캘핑 |
| 21 EMA | 단기 추세, 단타 |
| 50 EMA | 중기 추세 |
| 200 EMA | 장기 추세, 불/베어 마켓 구분 |

### 전략 1: 골든/데드 크로스

```kotlin
val goldenCross = ema50.crossedAbove(ema200)  // 강세 전환
val deadCross = ema50.crossedBelow(ema200)    // 약세 전환
```

### 전략 2: EMA 리본

```kotlin
val bullishRibbon = ema9 > ema21 && ema21 > ema50 && ema50 > ema200
val bearishRibbon = ema9 < ema21 && ema21 < ema50 && ema50 < ema200
val compression = abs(ema9 - ema200) / price < 0.02  // 수렴
```

### 전략 3: 동적 지지/저항

```kotlin
val dynamicSupport = price > ema21 && price.touches(ema21)
val buyOnPullback = dynamicSupport && rsi < 50 && volume > avgVolume
```

---

## Volume Profile

### 개념

가격 레벨별 거래량 분포 = 지지/저항 식별

### 핵심 영역

| 영역 | 의미 | 트레이딩 |
|------|------|----------|
| POC (Point of Control) | 최다 거래 가격 | 강한 자석, 지지/저항 |
| VAH (Value Area High) | 상위 70% 거래량 상단 | 저항 |
| VAL (Value Area Low) | 상위 70% 거래량 하단 | 지지 |
| HVN (High Volume Node) | 고거래량 구간 | 정체 예상 |
| LVN (Low Volume Node) | 저거래량 구간 | 빠른 통과 예상 |

### 전략: 볼륨 프로파일 돌파

```kotlin
val bullishPOCBreak = price.crossedAbove(poc) && volume > avgVolume * 2
val targetAfterBreak = vah  // 다음 저항 레벨
```

---

*참조: RSI 91% 승률 전략 = 레인지 돌파 + 200 EMA 필터 조합*
*참조: MACD 77% 승률 = RSI 30-50 구간 진입 + 추세 확인*
*참조: 볼린저 78% 승률 = MACD 조합 + 스퀴즈 확인*
