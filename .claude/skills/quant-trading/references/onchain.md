# 온체인 분석 & 웨일 추적 가이드

## 개요

온체인 분석 = 블록체인 데이터에서 시장 심리 및 대형 투자자 행동 파악

### 2025 정확도 검증

| 방법 | 가격 예측 정확도 | 출처 |
|------|----------------|------|
| 온체인 + ML | 82.68% | Nansen 2025 |
| 웨일 추적 + Q-learning | +22% 변동성 예측 개선 | 학술 연구 2025 |
| Glassnode 축적 점수 | 시장 바닥 탐지 90%+ | 경험적 데이터 |

---

## 핵심 온체인 지표

### 1. MVRV (Market Value to Realized Value)

**공식**: MVRV = 시가총액 / 실현시가총액

| MVRV | 해석 | 액션 |
|------|------|------|
| > 3.5 | 극도 과열 | **매도 강력 권장** |
| 2.5-3.5 | 과열 | 분할 매도 시작 |
| 1.0-2.5 | 적정 | 보유 유지 |
| 0.75-1.0 | 저평가 | 분할 매수 시작 |
| < 0.75 | 극도 저평가 | **매수 강력 권장** |

```kotlin
fun interpretMVRV(mvrv: Double): TradingSignal {
    return when {
        mvrv > 3.5 -> TradingSignal.STRONG_SELL
        mvrv > 2.5 -> TradingSignal.SELL
        mvrv > 1.0 -> TradingSignal.HOLD
        mvrv > 0.75 -> TradingSignal.BUY
        else -> TradingSignal.STRONG_BUY
    }
}
```

### 2. NUPL (Net Unrealized Profit/Loss)

**공식**: NUPL = (시가총액 - 실현시가총액) / 시가총액

| NUPL | 시장 단계 | 투자자 심리 |
|------|----------|-----------|
| > 0.75 | 유포리아 | 극도의 탐욕 → **매도** |
| 0.5-0.75 | 믿음 | 낙관 → 주의 |
| 0.25-0.5 | 낙관 | 건강한 상승 |
| 0-0.25 | 희망 | 회복 단계 |
| -0.25-0 | 불안 | 약세 시작 |
| < -0.25 | 항복 | 공포 → **매수** |

### 3. SOPR (Spent Output Profit Ratio)

**공식**: SOPR = 매도된 코인의 현재가치 / 매수시 가치

| SOPR | 해석 |
|------|------|
| > 1.0 | 수익 실현 중 (매도 압력) |
| = 1.0 | 손익분기 (중요 지지/저항) |
| < 1.0 | 손절 매도 중 (항복) |

**aSOPR** (adjusted): 1시간 미만 보유 코인 제외 → 더 정확

### 4. 거래소 유입/유출

| 지표 | 의미 | 신호 |
|------|------|------|
| 유입 증가 | 매도 준비 | **약세** |
| 유출 증가 | 장기 보유 | **강세** |
| 거래소 보유량 감소 | 공급 축소 | **강세** |
| 거래소 보유량 증가 | 매도 압력 증가 | **약세** |

```kotlin
data class ExchangeFlow(
    val netFlow: Double,        // 유입 - 유출
    val flowRatio: Double,      // 유입 / 유출
    val reserveChange24h: Double // 24시간 보유량 변화
)

fun interpretExchangeFlow(flow: ExchangeFlow): Signal {
    return when {
        flow.netFlow < -1000 && flow.reserveChange24h < -0.01 ->
            Signal.BULLISH("대량 유출, 보유량 감소")
        flow.netFlow > 1000 && flow.reserveChange24h > 0.01 ->
            Signal.BEARISH("대량 유입, 보유량 증가")
        else -> Signal.NEUTRAL("특이사항 없음")
    }
}
```

### 5. 활성 주소 수

| 지표 | 해석 |
|------|------|
| 활성 주소 증가 + 가격 상승 | 건강한 상승 |
| 활성 주소 감소 + 가격 상승 | 지속 불가, 조심 |
| 활성 주소 증가 + 가격 하락 | 축적 단계, 바닥 근처 |
| 활성 주소 감소 + 가격 하락 | 관심 감소, 추가 하락 가능 |

### 6. NVT (Network Value to Transactions)

**공식**: NVT = 시가총액 / 일일 거래량(온체인)

| NVT | 해석 |
|------|------|
| > 100 | 과대평가, 버블 가능 |
| 50-100 | 적정 또는 약간 고평가 |
| < 50 | 저평가, 매수 기회 |

---

## 웨일 추적

### 웨일 정의

| 자산 | 웨일 기준 |
|------|----------|
| BTC | 1,000+ BTC |
| ETH | 10,000+ ETH |
| 알트코인 | 전체 공급량의 0.1%+ |

### 2025 주요 웨일 이벤트

**2025년 8월 사례**:
- 웨일 24,000 BTC ($2.7B) 매도
- 플래시 크래시 발생
- $500M 강제 청산
- AI 도구 사용자 = 수 시간 전 탐지

### 웨일 행동 패턴

| 행동 | 의미 | 대응 |
|------|------|------|
| 거래소 입금 | 매도 준비 | 경계, 손절 타이트 |
| 거래소 출금 | 장기 보유 | 강세 신호 |
| 콜드월렛 이동 | 축적 | 매수 고려 |
| DeFi 예치 | 스테이킹/유동성 제공 | 중립-강세 |
| 웨일 간 이동 | OTC 거래 | 모니터링 |

### 웨일 추적 도구

| 플랫폼 | 특징 |
|--------|------|
| Whale Alert | 실시간 대량 이체 알림 |
| Glassnode | 축적 점수, 온체인 지표 |
| Nansen | 스마트 머니 추적 |
| Arkham | 800M+ 지갑 라벨링 |
| CryptoQuant | 거래소 플로우 |

### 축적 점수 (Glassnode)

**범위**: 0.0 - 1.0

| 점수 | 해석 |
|------|------|
| 0.9-1.0 | 극적인 축적 (웨일 매집) |
| 0.7-0.9 | 강한 축적 |
| 0.5-0.7 | 보통 |
| 0.3-0.5 | 약한 축적/분배 혼재 |
| 0.0-0.3 | 분배 (매도 압력) |

**2025년 1월**: 축적 점수 0.99 → 역사적 고점, 강한 매집 신호

---

## 코드 구현

### 온체인 지표 수집

```kotlin
data class OnChainMetrics(
    val mvrv: Double,
    val nupl: Double,
    val sopr: Double,
    val asopr: Double,
    val exchangeNetFlow: Double,
    val exchangeReserve: Double,
    val activeAddresses: Long,
    val nvt: Double,
    val accumulationScore: Double,
    val whaleCount: Int,
    val timestamp: Instant
)

interface OnChainDataProvider {
    suspend fun getMetrics(symbol: String): OnChainMetrics
    suspend fun getWhaleAlerts(symbol: String, hours: Int = 24): List<WhaleAlert>
    suspend fun getExchangeFlows(symbol: String, hours: Int = 24): List<ExchangeFlow>
}

// Glassnode API 구현 예시
class GlassnodeProvider(
    private val apiKey: String,
    private val httpClient: HttpClient
) : OnChainDataProvider {
    override suspend fun getMetrics(symbol: String): OnChainMetrics {
        val mvrv = fetchMetric("mvrv", symbol)
        val nupl = fetchMetric("nupl", symbol)
        // ... 기타 지표
        return OnChainMetrics(
            mvrv = mvrv,
            nupl = nupl,
            // ...
        )
    }

    private suspend fun fetchMetric(metric: String, symbol: String): Double {
        val response = httpClient.get("https://api.glassnode.com/v1/metrics/$metric") {
            parameter("a", symbol.uppercase())
            parameter("api_key", apiKey)
        }
        return response.body<GlassnodeResponse>().data.last().value
    }
}
```

### 종합 온체인 신호

```kotlin
data class OnChainSignal(
    val direction: Direction,
    val strength: Strength,
    val confidence: Double,
    val keyFactors: List<String>
)

enum class Direction { BULLISH, BEARISH, NEUTRAL }
enum class Strength { STRONG, MODERATE, WEAK }

fun analyzeOnChain(metrics: OnChainMetrics): OnChainSignal {
    var bullishScore = 0
    var bearishScore = 0
    val factors = mutableListOf<String>()

    // MVRV
    when {
        metrics.mvrv < 0.75 -> { bullishScore += 3; factors.add("MVRV 극저 (${metrics.mvrv})") }
        metrics.mvrv < 1.0 -> { bullishScore += 2; factors.add("MVRV 저평가") }
        metrics.mvrv > 3.5 -> { bearishScore += 3; factors.add("MVRV 극과열 (${metrics.mvrv})") }
        metrics.mvrv > 2.5 -> { bearishScore += 2; factors.add("MVRV 과열") }
    }

    // NUPL
    when {
        metrics.nupl < -0.25 -> { bullishScore += 3; factors.add("NUPL 항복 단계") }
        metrics.nupl < 0 -> { bullishScore += 1; factors.add("NUPL 불안 단계") }
        metrics.nupl > 0.75 -> { bearishScore += 3; factors.add("NUPL 유포리아") }
        metrics.nupl > 0.5 -> { bearishScore += 1; factors.add("NUPL 믿음 단계") }
    }

    // 거래소 플로우
    when {
        metrics.exchangeNetFlow < -5000 -> { bullishScore += 2; factors.add("대량 거래소 유출") }
        metrics.exchangeNetFlow > 5000 -> { bearishScore += 2; factors.add("대량 거래소 유입") }
    }

    // 축적 점수
    when {
        metrics.accumulationScore > 0.9 -> { bullishScore += 2; factors.add("극적인 축적 진행") }
        metrics.accumulationScore < 0.3 -> { bearishScore += 2; factors.add("분배 진행 중") }
    }

    val netScore = bullishScore - bearishScore
    val direction = when {
        netScore >= 3 -> Direction.BULLISH
        netScore <= -3 -> Direction.BEARISH
        else -> Direction.NEUTRAL
    }

    val strength = when (kotlin.math.abs(netScore)) {
        in 5..Int.MAX_VALUE -> Strength.STRONG
        in 3..4 -> Strength.MODERATE
        else -> Strength.WEAK
    }

    val confidence = minOf(1.0, kotlin.math.abs(netScore) * 0.12 + 0.4)

    return OnChainSignal(
        direction = direction,
        strength = strength,
        confidence = confidence,
        keyFactors = factors
    )
}
```

### 웨일 알림 처리

```kotlin
data class WhaleAlert(
    val txHash: String,
    val from: String,
    val to: String,
    val amount: Double,
    val symbol: String,
    val usdValue: Double,
    val alertType: WhaleAlertType,
    val timestamp: Instant
)

enum class WhaleAlertType {
    TO_EXCHANGE,      // 거래소 입금 (약세)
    FROM_EXCHANGE,    // 거래소 출금 (강세)
    TO_COLD_WALLET,   // 콜드월렛 이동 (강세)
    WHALE_TO_WHALE,   // 웨일 간 이동 (중립)
    TO_DEFI,          // DeFi 예치 (중립-강세)
    UNKNOWN           // 분류 불가
}

fun processWhaleAlerts(alerts: List<WhaleAlert>): WhaleImpact {
    var bullishFlow = 0.0
    var bearishFlow = 0.0

    alerts.forEach { alert ->
        when (alert.alertType) {
            WhaleAlertType.TO_EXCHANGE -> bearishFlow += alert.usdValue
            WhaleAlertType.FROM_EXCHANGE -> bullishFlow += alert.usdValue
            WhaleAlertType.TO_COLD_WALLET -> bullishFlow += alert.usdValue * 0.5
            WhaleAlertType.TO_DEFI -> bullishFlow += alert.usdValue * 0.3
            else -> {}
        }
    }

    val netImpact = bullishFlow - bearishFlow
    return WhaleImpact(
        bullishFlow = bullishFlow,
        bearishFlow = bearishFlow,
        netImpact = netImpact,
        signal = when {
            netImpact > 10_000_000 -> Signal.BULLISH
            netImpact < -10_000_000 -> Signal.BEARISH
            else -> Signal.NEUTRAL
        }
    )
}
```

---

## 전략별 온체인 활용

### 장기투자 (HODL)

| 지표 | 매수 조건 | 매도 조건 |
|------|----------|----------|
| MVRV | < 1.0 | > 3.0 |
| NUPL | < 0 | > 0.7 |
| 축적 점수 | > 0.8 | < 0.3 |
| 활성 주소 | 저점 대비 증가 | 고점 대비 감소 |

### 스윙 트레이딩

| 지표 | 매수 조건 | 매도 조건 |
|------|----------|----------|
| SOPR | < 0.98 (손절 급증) | > 1.05 (수익 실현) |
| 거래소 유출 | 24시간 순유출 | 24시간 순유입 |
| 웨일 알림 | 거래소→콜드월렛 | 콜드월렛→거래소 |

### 단타

온체인은 보조적 사용:
- 대량 웨일 이동 시 진입 회피
- 거래소 순유입 급증 시 손절 타이트

---

## API 데이터 소스

### 무료

| 소스 | 특징 | 제한 |
|------|------|------|
| Blockchain.com | BTC 기본 지표 | BTC만 |
| Etherscan | ETH 주소/거래 | ETH만 |
| Whale Alert API | 대량 이체 | 월 제한 |

### 유료

| 소스 | 월 비용 | 특징 |
|------|--------|------|
| Glassnode | $799+ | 종합 온체인 |
| Nansen | $999+ | 스마트머니 추적 |
| CryptoQuant | $299+ | 거래소 플로우 |
| IntoTheBlock | $99+ | 인사이트 |

---

## 체크리스트

### 장기투자 진입 전

```
□ MVRV < 1.5 확인
□ NUPL < 0.25 확인
□ 축적 점수 > 0.7 확인
□ 최근 1주 순유출 확인
□ 웨일 매도 알림 없음
□ 활성 주소 증가 추세
```

### 스윙/단타 진입 전

```
□ 24시간 내 대량 웨일 이동 확인
□ 거래소 순유출/유입 확인
□ SOPR 급등/급락 확인
□ 온체인 신호와 기술적 신호 일치
```

---

*참조: Glassnode Academy, Nansen Research, CryptoQuant Reports*
