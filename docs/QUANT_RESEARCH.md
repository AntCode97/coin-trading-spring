# 퀀트 트레이딩 연구 노트

개인 투자자가 암호화폐 자동매매로 수익을 낼 수 있는지에 대한 심층 연구 결과.

---

## 1. 현실 인식

### 1.1 개인 vs 기관

| 요소 | 기관 | 개인 |
|------|------|------|
| 지연시간 | < 1ms (코로케이션) | 50-200ms |
| 자본 | 수조 원 | 수백만 원 |
| 인력 | 퀀트 박사급 수십 명 | 혼자 |
| 데이터 | 대체 데이터, 주문 흐름 | 공개 데이터만 |

### 1.2 개인의 실제 장점

1. **시장 충격 없음**: 소규모 포지션은 가격에 영향 안 줌
2. **시간 제약 없음**: 기관은 분기 실적 압박, 개인은 수년 대기 가능
3. **틈새 시장**: 기관이 무시하는 저유동성 마켓
4. **규제 자유**: 자체 리스크 관리 방법론 사용 가능

### 1.3 성공 확률

- 90-95%의 개인 퀀트 트레이더가 손실
- 현실적 연간 수익률: 10-15% (성공 시)
- 대부분 첫 1년 내 손실 후 철수

---

## 2. 실제 통하는 전략

### 2.1 Funding Rate Arbitrage

**메커니즘**:
```
1. 현물 매수 (Long Spot)
2. 무기한 선물 매도 (Short Perpetual)
3. 펀딩비 수취 (보통 8시간마다 0.01%)
```

**수익률**: 연 19-38% (2024-2025 백테스트)

**리스크**:
- 펀딩비 방향 전환
- 청산 위험
- 거래소 리스크

**구현 핵심**:
```kotlin
data class FundingOpportunity(
    val symbol: String,
    val fundingRate: Double,          // 현재 펀딩비
    val annualizedReturn: Double,     // 연환산 수익률
    val nextFundingTime: Instant      // 다음 정산 시간
)

// 연환산 수익률 = fundingRate * 3 * 365 * 100
// (8시간마다 3회 × 365일)
```

**출처**: [Amberdata Guide](https://blog.amberdata.io/the-ultimate-guide-to-funding-rate-arbitrage-amberdata)

---

### 2.2 Order Book Imbalance

**메커니즘**:
```
Imbalance = (Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)

-1에 가까움 → 매도 압력 → 가격 하락 예측
+1에 가까움 → 매수 압력 → 가격 상승 예측
```

**적용 시간대**: 초~분 단위 (매우 단기)

**구현 핵심**:
```kotlin
fun calculateImbalance(orderbook: Orderbook): Double {
    val bids = orderbook.bids.take(5)  // 상위 5호가
    val asks = orderbook.asks.take(5)

    val bidVolume = bids.sumOf { it.quantity * it.price }
    val askVolume = asks.sumOf { it.quantity * it.price }

    return (bidVolume - askVolume) / (bidVolume + askVolume)
}

// Microprice (가중 중간가)
fun calculateMicroprice(bestBid: Level, bestAsk: Level): Double {
    return (bestBid.price * bestAsk.quantity + bestAsk.price * bestBid.quantity) /
           (bestBid.quantity + bestAsk.quantity)
}
```

**출처**: [Cornell Research](https://stoye.economics.cornell.edu/docs/Easley_ssrn-4814346.pdf)

---

### 2.3 Volatility Regime Detection (HMM)

**메커니즘**:
```
Hidden Markov Model로 시장 상태 분류:
- LOW_VOLATILITY: Grid/Mean Reversion 유리
- HIGH_VOLATILITY: 거래 축소/중단
- TRENDING_UP: Momentum/DCA
- TRENDING_DOWN: 현금 보유
- REGIME_SHIFT: 대기
```

**효과**:
- Maximum Drawdown: 56% → 24% 감소
- 2006-2023 백테스트에서 Buy & Hold 대비 우수

**구현 핵심**:
```kotlin
// 간단한 규칙 기반 (HMM 대체)
fun detectRegime(returns: List<Double>): MarketRegime {
    val volatility = calculateRollingVolatility(returns, 20)
    val trend = calculateTrend(returns, 50)

    return when {
        volatility > 3.0 -> HIGH_VOLATILITY
        trend > 0.5 && volatility < 2.0 -> TRENDING_UP
        trend < -0.5 && volatility < 2.0 -> TRENDING_DOWN
        abs(volatility - previousVolatility) > 1.0 -> REGIME_SHIFT
        else -> LOW_VOLATILITY
    }
}

// 고변동성/레짐전환 시 거래 중단
fun shouldTrade(regime: MarketRegime): Boolean {
    return regime !in listOf(HIGH_VOLATILITY, REGIME_SHIFT)
}
```

**출처**: [QuantStart HMM](https://www.quantstart.com/articles/market-regime-detection-using-hidden-markov-models-in-qstrader/)

---

### 2.4 한국 시장 특수성 (김치 프리미엄)

**현황 (2024)**:
- 상반기: 5% 이상 프리미엄 일수 5.6배 증가
- 3월: 10.88% 프리미엄 (2021년 이후 최고)
- 하반기: 역프리미엄 -3% ~ -5% 발생

**실제 차익거래는 불가능**:
- 원화 통제로 해외 송금 제한
- 프리미엄 방향성 베팅만 가능

**활용 가능한 패턴**:
1. 알트코인 신규 상장 시 프리미엄 급등 (AVAIL 1255%)
2. 한국 투자자 심리 과열 시 프리미엄 확대
3. 프리미엄 모멘텀 추종

**출처**: [Tiger Research](https://reports.tiger-research.com/p/kimchi-premium-101-eng)

---

## 3. 엣지 케이스 및 실패 원인

### 3.1 주문 실행 문제

| 문제 | 설명 | 해결책 |
|------|------|--------|
| 부분 체결 | 주문량의 일부만 체결 | 주문 후 상태 조회 필수 |
| 슬리피지 | 예상가와 체결가 차이 | 지정가 주문 우선 사용 |
| API 지연 | 변동성 높을 때 응답 느림 | 타임아웃 + 재시도 로직 |
| Rate Limit | 초당 요청 수 제한 | 백오프 + 큐잉 |

### 3.2 데이터 문제

| 문제 | 설명 | 해결책 |
|------|------|--------|
| 데이터 부족 | 캔들 100개 요청 → 50개만 수신 | 최소 데이터 검증 |
| 시간 동기화 | 서버 시간 ≠ 거래소 시간 | NTP 동기화 |
| 결측치 | OHLCV 일부 누락 | 보간 또는 스킵 |

### 3.3 시스템 문제

| 문제 | 설명 | 해결책 |
|------|------|--------|
| 서버 재시작 | 상태 초기화 | Redis/DB 상태 저장 |
| 동시성 | 같은 마켓 중복 분석 | Lock 또는 순차 처리 |
| 메모리 누수 | 장시간 운영 시 | 주기적 GC + 모니터링 |

### 3.4 거래소 특수 상황

| 상황 | 설명 | 대응 |
|------|------|------|
| 거래소 점검 | 예고 없이 API 중단 | 상태 체크 + graceful 중단 |
| 급격한 변동 | 호가 스프레드 확대 | 스프레드 기반 거래 중단 |
| 유동성 고갈 | 호가창 비어있음 | 주문 전 깊이 확인 |

---

## 4. 필수 안전장치

### 4.1 주문 검증 체크리스트

```kotlin
// 주문 전
□ 잔고 충분한가?
□ 호가 깊이 충분한가? (주문량의 3배 이상)
□ 스프레드 정상인가? (< 0.5%)
□ CircuitBreaker 열려있나?
□ 일일 거래 한도 안 넘었나?

// 주문 후
□ 응답 null 아닌가?
□ 에러 코드 확인
□ 체결 상태 조회 (3회 재시도)
□ 부분 체결 확인
□ 실제 체결가 vs 신호 가격 비교 (슬리피지)
□ DB 저장 성공
```

### 4.2 리스크 한도

```kotlin
object RiskLimits {
    const val MAX_POSITION_PERCENT = 0.25     // 총 자산의 25% 이하
    const val MAX_DAILY_LOSS_PERCENT = 0.03   // 일일 3% 손실 시 중단
    const val MAX_DRAWDOWN_PERCENT = 0.10     // 10% 낙폭 시 중단
    const val MAX_SLIPPAGE_PERCENT = 0.02     // 2% 슬리피지 경고
    const val MIN_LIQUIDITY_RATIO = 3.0       // 주문량 대비 호가 깊이
    const val MAX_SPREAD_PERCENT = 0.005      // 0.5% 스프레드 이상 거래 금지
}
```

### 4.3 CircuitBreaker 트리거

```kotlin
// 개별 마켓
- 연속 3회 손실
- 일일 5% 손실
- 24시간 내 10회 손실
- 연속 5회 주문 실행 실패 (NEW)
- 슬리피지 2% 초과 3회 (NEW)

// 글로벌
- 2개 이상 마켓 서킷 발동
- 1분 내 API 에러 10회 (NEW)
- 총 자산 10% 감소 (NEW)
```

---

## 5. 개발 우선순위

### Phase 1: 생존 (필수)

1. 부분 체결 검증
2. 슬리피지 측정 및 경고
3. API 에러 → CircuitBreaker 연동
4. 실시간 수수료 조회
5. 상태 복구 (Redis)

### Phase 2: 엣지 확보

1. Order Book Imbalance 전략
2. HMM 레짐 감지
3. 스프레드/유동성 기반 거래 필터
4. 김치 프리미엄 모니터링

### Phase 3: 확장

1. Funding Rate Arbitrage (선물 거래소 연동)
2. 다중 거래소 차익거래
3. ML 기반 레짐 감지
4. 백테스팅 프레임워크

---

## 6. 참고 자료

### 필독 논문/문서

1. [QuantStart - Retail Quant Success](https://www.quantstart.com/articles/Can-Algorithmic-Traders-Still-Succeed-at-the-Retail-Level/)
2. [Amberdata - Funding Rate Arbitrage](https://blog.amberdata.io/the-ultimate-guide-to-funding-rate-arbitrage-amberdata)
3. [Cornell - Crypto Microstructure](https://stoye.economics.cornell.edu/docs/Easley_ssrn-4814346.pdf)
4. [QuantStart - HMM Regime Detection](https://www.quantstart.com/articles/market-regime-detection-using-hidden-markov-models-in-qstrader/)
5. [Tiger Research - Kimchi Premium](https://reports.tiger-research.com/p/kimchi-premium-101-eng)
6. [LuxAlgo - Algo Trading Failures](https://www.luxalgo.com/blog/lessons-from-algo-trading-failures/)
7. [PMC - Systemic Failures in Algo Trading](https://pmc.ncbi.nlm.nih.gov/articles/PMC8978471/)

### 추천 도서

1. "Inside The Blackbox" - Rishi Narang
2. "Dynamic Hedging" - Nassim Taleb
3. "Fortune's Formula" - William Poundstone
4. "The Market Structure Crisis" - Haim Bodek

### 오픈소스 도구

1. [hftbacktest](https://github.com/nkaz001/hftbacktest) - HFT 백테스팅
2. [QuantConnect](https://www.quantconnect.com/) - 알고리즘 트레이딩 플랫폼
3. [smile-math](https://haifengl.github.io/smile/) - HMM 구현용

---

## 7. 핵심 교훈

> **"엣지가 없으면 무작위 거래와 같고, 무작위 거래로는 아무도 성공할 수 없다."**
>
> 대부분의 트레이더가 실패하는 이유:
> 1. 엣지를 정량화하지 않음
> 2. 샘플 사이즈 부족 (최소 50회 이상 거래 필요)
> 3. 과적합된 백테스트
> 4. 실거래 비용 무시 (수수료, 슬리피지, 스프레드)

---

*마지막 업데이트: 2026-01-11*
*작성: Claude Opus 4.5 기반 퀀트 연구*
