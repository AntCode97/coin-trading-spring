# 호가창 분석 가이드

## 개요

호가창(Order Book) 분석 = 실시간 매수/매도 주문 분포에서 수급 파악

### 2025 연구 발견

| 발견 | 내용 | 출처 |
|------|------|------|
| 시간별 유동성 | 21:00 UTC 유동성 42% 감소 | Amberdata 2025 |
| Imbalance 예측력 | 10초 내 방향 예측 가능 | Academic 2025 |
| 세션별 편향 | 오후 매수 편향 2배 | Binance 분석 |

---

## 핵심 지표

### 1. Order Book Imbalance (OBI)

**공식**:
```
OBI = (Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)
```

**범위**: -1.0 ~ +1.0

| OBI | 해석 | 신호 |
|-----|------|------|
| > +0.5 | 강한 매수 압력 | **강세** |
| +0.2 ~ +0.5 | 매수 우세 | 약강세 |
| -0.2 ~ +0.2 | 균형 | 중립 |
| -0.5 ~ -0.2 | 매도 우세 | 약약세 |
| < -0.5 | 강한 매도 압력 | **약세** |

```kotlin
data class OrderBookImbalance(
    val imbalance: Double,
    val bidVolume: Double,
    val askVolume: Double,
    val interpretation: String
)

fun calculateImbalance(orderbook: OrderBook, depth: Int = 10): OrderBookImbalance {
    val bidVolume = orderbook.bids.take(depth).sumOf { it.quantity }
    val askVolume = orderbook.asks.take(depth).sumOf { it.quantity }

    val imbalance = (bidVolume - askVolume) / (bidVolume + askVolume)

    val interpretation = when {
        imbalance > 0.5 -> "강한 매수 압력"
        imbalance > 0.2 -> "매수 우세"
        imbalance > -0.2 -> "균형"
        imbalance > -0.5 -> "매도 우세"
        else -> "강한 매도 압력"
    }

    return OrderBookImbalance(imbalance, bidVolume, askVolume, interpretation)
}
```

### 2. Weighted Mid Price (WMP)

단순 중간가격보다 정확한 공정가격:

```kotlin
fun calculateWeightedMidPrice(orderbook: OrderBook): Double {
    val bestBid = orderbook.bids.first()
    val bestAsk = orderbook.asks.first()

    // 호가 수량으로 가중
    val wmp = (bestBid.price * bestAsk.quantity + bestAsk.price * bestBid.quantity) /
              (bestBid.quantity + bestAsk.quantity)

    return wmp
}
```

### 3. Spread (스프레드)

```kotlin
data class Spread(
    val absolute: Double,
    val percent: Double,
    val isNormal: Boolean
)

fun calculateSpread(orderbook: OrderBook): Spread {
    val bestBid = orderbook.bids.first().price
    val bestAsk = orderbook.asks.first().price

    val absolute = bestAsk - bestBid
    val percent = absolute / bestBid * 100

    // 암호화폐 정상 범위: 0.05% ~ 0.3%
    val isNormal = percent in 0.05..0.3

    return Spread(absolute, percent, isNormal)
}
```

### 4. Market Depth

```kotlin
data class MarketDepth(
    val bidDepthKRW: Double,
    val askDepthKRW: Double,
    val totalDepthKRW: Double,
    val liquidityRatio: Double  // 예정 거래 대비 유동성
)

fun analyzeMarketDepth(
    orderbook: OrderBook,
    depthLevels: Int = 20,
    intendedTradeKRW: Double
): MarketDepth {
    val bidDepth = orderbook.bids.take(depthLevels)
        .sumOf { it.price * it.quantity }

    val askDepth = orderbook.asks.take(depthLevels)
        .sumOf { it.price * it.quantity }

    val totalDepth = bidDepth + askDepth
    val liquidityRatio = minOf(bidDepth, askDepth) / intendedTradeKRW

    return MarketDepth(
        bidDepthKRW = bidDepth,
        askDepthKRW = askDepth,
        totalDepthKRW = totalDepth,
        liquidityRatio = liquidityRatio
    )
}
```

---

## 시간대별 유동성 패턴 (2025)

### Binance BTC/FDUSD 분석

| 시간 (UTC) | 유동성 (10bps 내) | Imbalance |
|-----------|------------------|-----------|
| 00:00 | $3.86M | +1.5% |
| 06:00 | $3.50M | +1.2% |
| 11:00 | **$3.86M (최고)** | +1.5% |
| 15:00 | $3.20M | +2.0% |
| 21:00 | **$2.71M (최저)** | +3.2% |

### 트레이딩 시사점

- **11:00 UTC (20:00 KST)**: 유동성 최고 → 대량 거래 적합
- **21:00 UTC (06:00 KST)**: 유동성 최저 → 슬리피지 주의

```kotlin
fun isOptimalTradingHour(utcHour: Int): Boolean {
    // 유동성 높은 시간대
    val optimalHours = listOf(8, 9, 10, 11, 12, 13, 14, 15)
    return utcHour in optimalHours
}

fun getSlippageMultiplier(utcHour: Int): Double {
    return when (utcHour) {
        in 11..15 -> 1.0    // 정상
        in 8..10 -> 1.1     // 약간 증가
        in 16..20 -> 1.2    // 증가
        else -> 1.5         // 주의 (새벽)
    }
}
```

---

## 대량 주문 감지

### 지지벽 / 저항벽 (Walls)

```kotlin
data class OrderWall(
    val price: Double,
    val volume: Double,
    val volumeKRW: Double,
    val type: WallType,
    val strength: Strength
)

enum class WallType { BID_WALL, ASK_WALL }
enum class Strength { WEAK, MODERATE, STRONG, MASSIVE }

fun detectWalls(
    orderbook: OrderBook,
    avgVolumePerLevel: Double
): List<OrderWall> {
    val walls = mutableListOf<OrderWall>()

    // 매수벽 (지지)
    orderbook.bids.forEach { level ->
        val ratio = level.quantity / avgVolumePerLevel
        if (ratio > 3) {
            walls.add(OrderWall(
                price = level.price,
                volume = level.quantity,
                volumeKRW = level.price * level.quantity,
                type = WallType.BID_WALL,
                strength = when {
                    ratio > 10 -> Strength.MASSIVE
                    ratio > 7 -> Strength.STRONG
                    ratio > 5 -> Strength.MODERATE
                    else -> Strength.WEAK
                }
            ))
        }
    }

    // 매도벽 (저항)
    orderbook.asks.forEach { level ->
        val ratio = level.quantity / avgVolumePerLevel
        if (ratio > 3) {
            walls.add(OrderWall(
                price = level.price,
                volume = level.quantity,
                volumeKRW = level.price * level.quantity,
                type = WallType.ASK_WALL,
                strength = when {
                    ratio > 10 -> Strength.MASSIVE
                    ratio > 7 -> Strength.STRONG
                    ratio > 5 -> Strength.MODERATE
                    else -> Strength.WEAK
                }
            ))
        }
    }

    return walls
}
```

### 아이스버그 주문 탐지

대량 주문을 분할하여 숨기는 패턴:

```kotlin
data class IcebergDetection(
    val detected: Boolean,
    val estimatedSize: Double,
    val confidence: Double
)

fun detectIceberg(
    trades: List<Trade>,
    orderbook: OrderBook,
    lookback: Int = 50
): IcebergDetection {
    val recentTrades = trades.takeLast(lookback)

    // 같은 가격에서 반복적으로 체결되는 패턴
    val priceGroups = recentTrades.groupBy { it.price }
    val suspiciousPrices = priceGroups.filter { it.value.size > 5 }

    if (suspiciousPrices.isEmpty()) {
        return IcebergDetection(false, 0.0, 0.0)
    }

    // 가장 의심스러운 가격
    val mostSuspicious = suspiciousPrices.maxByOrNull { it.value.size }!!
    val totalVolume = mostSuspicious.value.sumOf { it.quantity }

    return IcebergDetection(
        detected = true,
        estimatedSize = totalVolume * 2,  // 추정 (보수적)
        confidence = minOf(1.0, mostSuspicious.value.size / 10.0)
    )
}
```

---

## 호가창 기반 전략

### 1. Imbalance 기반 초단기

```kotlin
data class ImbalanceSignal(
    val direction: Direction,
    val strength: Double,
    val expectedMove: Double  // 예상 이동폭 %
)

fun generateImbalanceSignal(
    currentImbalance: Double,
    historicalImbalances: List<Double>
): ImbalanceSignal {
    val avgImbalance = historicalImbalances.average()
    val deviation = currentImbalance - avgImbalance

    val direction = when {
        currentImbalance > 0.3 -> Direction.LONG
        currentImbalance < -0.3 -> Direction.SHORT
        else -> Direction.NEUTRAL
    }

    val strength = kotlin.math.abs(currentImbalance)
    val expectedMove = deviation * 0.1  // 경험적 계수

    return ImbalanceSignal(direction, strength, expectedMove)
}
```

### 2. 지지/저항 기반 진입

```kotlin
fun findEntryNearWall(
    currentPrice: Double,
    walls: List<OrderWall>
): EntryOpportunity? {
    // 가까운 매수벽 찾기
    val nearbyBidWalls = walls.filter {
        it.type == WallType.BID_WALL &&
        it.price < currentPrice &&
        (currentPrice - it.price) / currentPrice < 0.02  // 2% 이내
    }

    val strongestWall = nearbyBidWalls.maxByOrNull { it.volumeKRW }
        ?: return null

    if (strongestWall.strength !in listOf(Strength.STRONG, Strength.MASSIVE)) {
        return null
    }

    return EntryOpportunity(
        entryPrice = strongestWall.price * 1.001,  // 벽 바로 위
        stopLoss = strongestWall.price * 0.99,     // 벽 하향 돌파 시
        target = currentPrice * 1.02,              // +2%
        confidence = when (strongestWall.strength) {
            Strength.MASSIVE -> 0.8
            Strength.STRONG -> 0.7
            else -> 0.5
        }
    )
}
```

### 3. 스프레드 기반 체결 전략

```kotlin
enum class OrderType { MARKET, LIMIT_AGGRESSIVE, LIMIT_PASSIVE }

fun determineOrderType(
    spread: Spread,
    urgency: Urgency,
    expectedMove: Double
): OrderType {
    return when {
        // 긴급 + 좁은 스프레드 = 시장가
        urgency == Urgency.HIGH && spread.percent < 0.1 -> OrderType.MARKET

        // 넓은 스프레드 = 지정가
        spread.percent > 0.3 -> OrderType.LIMIT_PASSIVE

        // 보통 = 최유리 지정가
        else -> OrderType.LIMIT_AGGRESSIVE
    }
}

fun calculateLimitPrice(
    orderbook: OrderBook,
    side: Side,
    aggression: Double = 0.5  // 0=Passive, 1=Aggressive
): Double {
    val bestBid = orderbook.bids.first().price
    val bestAsk = orderbook.asks.first().price
    val spread = bestAsk - bestBid

    return when (side) {
        Side.BUY -> bestBid + (spread * aggression)
        Side.SELL -> bestAsk - (spread * aggression)
    }
}
```

---

## 슬리피지 예측

### 예상 슬리피지 계산

```kotlin
data class SlippageEstimate(
    val expectedSlippage: Double,  // %
    val worstCase: Double,         // %
    val fillLevels: List<FillLevel>
)

data class FillLevel(
    val price: Double,
    val quantity: Double,
    val cumulativeQuantity: Double,
    val slippage: Double
)

fun estimateSlippage(
    orderbook: OrderBook,
    side: Side,
    quantity: Double
): SlippageEstimate {
    val levels = if (side == Side.BUY) orderbook.asks else orderbook.bids
    val referencePrice = levels.first().price

    var remainingQty = quantity
    var totalCost = 0.0
    val fillLevels = mutableListOf<FillLevel>()
    var cumQty = 0.0

    for (level in levels) {
        if (remainingQty <= 0) break

        val fillQty = minOf(level.quantity, remainingQty)
        totalCost += level.price * fillQty
        remainingQty -= fillQty
        cumQty += fillQty

        val slippage = (level.price - referencePrice) / referencePrice * 100

        fillLevels.add(FillLevel(
            price = level.price,
            quantity = fillQty,
            cumulativeQuantity = cumQty,
            slippage = slippage
        ))
    }

    val avgPrice = totalCost / quantity
    val expectedSlippage = (avgPrice - referencePrice) / referencePrice * 100
    val worstCase = fillLevels.maxOfOrNull { it.slippage } ?: 0.0

    return SlippageEstimate(expectedSlippage, worstCase, fillLevels)
}
```

---

## 실시간 모니터링

### 호가창 스냅샷 저장

```kotlin
data class OrderBookSnapshot(
    val timestamp: Instant,
    val symbol: String,
    val bidDepth10: Double,
    val askDepth10: Double,
    val imbalance: Double,
    val spread: Double,
    val wallsDetected: Int
)

class OrderBookMonitor(
    private val repository: OrderBookSnapshotRepository
) {
    fun captureSnapshot(orderbook: OrderBook): OrderBookSnapshot {
        val imb = calculateImbalance(orderbook)
        val spr = calculateSpread(orderbook)
        val depth = analyzeMarketDepth(orderbook, 10, 1000000.0)
        val walls = detectWalls(orderbook, depth.bidDepthKRW / 10)

        val snapshot = OrderBookSnapshot(
            timestamp = Instant.now(),
            symbol = orderbook.symbol,
            bidDepth10 = depth.bidDepthKRW,
            askDepth10 = depth.askDepthKRW,
            imbalance = imb.imbalance,
            spread = spr.percent,
            wallsDetected = walls.size
        )

        repository.save(snapshot)
        return snapshot
    }

    fun detectAnomalies(recent: List<OrderBookSnapshot>): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        val current = recent.last()
        val avg = recent.dropLast(1)

        // 유동성 급감
        val avgDepth = avg.map { it.bidDepth10 + it.askDepth10 }.average()
        val currentDepth = current.bidDepth10 + current.askDepth10
        if (currentDepth < avgDepth * 0.5) {
            anomalies.add(Anomaly.LIQUIDITY_DROP)
        }

        // Imbalance 급변
        val avgImb = avg.map { kotlin.math.abs(it.imbalance) }.average()
        if (kotlin.math.abs(current.imbalance) > avgImb * 3) {
            anomalies.add(Anomaly.IMBALANCE_SPIKE)
        }

        // 스프레드 확대
        val avgSpread = avg.map { it.spread }.average()
        if (current.spread > avgSpread * 2) {
            anomalies.add(Anomaly.SPREAD_WIDENING)
        }

        return anomalies
    }
}

enum class Anomaly {
    LIQUIDITY_DROP,
    IMBALANCE_SPIKE,
    SPREAD_WIDENING,
    WALL_APPEARANCE,
    WALL_DISAPPEARANCE
}
```

---

## 거래소별 특성 (2025)

| 거래소 | BTC 유동성 | ETH 유동성 | 특성 |
|--------|-----------|-----------|------|
| Binance | **최고** | 높음 | 유동성 집중 |
| Coinbase | 높음 | 높음 | 기관 선호 |
| Kraken | 보통 | **최고** | ETH 강점 |
| Bithumb | 보통 | 보통 | KRW 마켓 |

### Bithumb 특이사항

- 원화 마켓 → 해외 대비 프리미엄/디스카운트
- 유동성 시간대: 09:00-18:00 KST 집중
- 스프레드: 0.1-0.3% 일반적

---

## 체크리스트

### 진입 전 호가창 확인

```
□ Imbalance 확인 (±0.3 이상?)
□ 스프레드 확인 (0.3% 미만?)
□ 유동성 비율 확인 (거래금액 5배+?)
□ 지지/저항벽 위치 확인
□ 슬리피지 예상치 계산
□ 최적 주문 유형 결정
```

### 대량 거래 전

```
□ 현재 시간대 유동성 확인
□ 분할 주문 계획 수립
□ 아이스버그 사용 고려
□ 예상 평균 체결가 계산
□ 최악의 슬리피지 허용 범위
```

---

*참조: Amberdata Orderbook Analysis 2025*
*참조: Binance Research - Market Microstructure*
*참조: HFT Backtest - Order Book Imbalance Tutorial*
