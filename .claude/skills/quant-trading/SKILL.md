---
name: quant-trading
description: 20년 경력 퀀트 트레이더의 암호화폐 트레이딩 완벽 가이드. 기술적 분석(RSI, MACD, 볼린저밴드, 이치모쿠, 피보나치, ATR), 컨플루언스 전략(85% 승률), 포지션 사이징(Kelly Criterion), 리스크 관리, 온체인 분석, 뉴스 감성 분석, 펌프앤덤프 탐지, 시장 레짐 감지 포함. 장기투자/스윙/단타/스캘핑 모든 전략 지원.
---

# 퀀트 트레이딩 마스터 가이드

연간 40%+ 수익률을 목표로 하는 체계적 암호화폐 트레이딩 프레임워크.

---

## 핵심 원칙

### 1. 단일 지표는 무의미하다

| 지표 수 | 백테스트 승률 | 실전 승률 |
|--------|-------------|----------|
| 1개 | 50-55% | 45-50% |
| 2개 | 65-70% | 60-65% |
| 3개 | 75-80% | 70-75% |
| 4개+ | 85-90% | 80-85% |

**항상 컨플루언스(Confluence)를 추구한다.**

### 2. 리스크 관리가 수익보다 중요하다

- 단일 거래 리스크: 자본의 **1-2%**
- 일일 최대 손실: 자본의 **5%**
- 동시 포지션: **최대 3개**
- 손절은 필수, 익절은 선택

### 3. 시장 레짐에 맞는 전략을 사용한다

| 레짐 | 특성 | 적합 전략 |
|------|------|----------|
| **추세장** | 방향성 명확 | 추세추종, MACD, 이치모쿠 |
| **횡보장** | 범위 제한 | 평균회귀, 볼린저밴드, RSI |
| **고변동** | 급등락 | ATR 기반 손절, 포지션 축소 |

---

## 기술적 지표 요약

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

### 이치모쿠 클라우드 (Ichimoku Cloud)

**암호화폐 설정**: 20/60/120/30 (24/7 시장 반영)

**핵심 전략**:
- 가격이 구름 위 돌파 + 전환선 > 기준선 = **매수**
- 두꺼운 구름 = 강한 지지/저항
- 평평한 구름 = 횡보장, 진입 회피

> 상세: [references/indicators.md](references/indicators.md#ichimoku)

### 피보나치 (Fibonacci)

**핵심 레벨**: 23.6%, 38.2%, **50%**, **61.8%**, 78.6%

**골든 존 (Golden Zone)**: 50% ~ 61.8% 구간 = 고확률 반전 영역

**익스텐션**: 127.2%, **161.8%**, 261.8% = 가격 목표

> 상세: [references/indicators.md](references/indicators.md#fibonacci)

---

## 컨플루언스 전략 (85% 승률)

### 4중 컨플루언스 매수 조건

모든 조건 충족 시 진입:

```
1. RSI ≤ 30 또는 상향 다이버전스     [25점]
2. MACD 시그널 상향 크로스            [25점]
3. %B ≤ 0.2 또는 하단밴드 반등        [25점]
4. 거래량 ≥ 20일 평균 × 150%         [25점]
```

**진입 기준**:
- 100점: **강한 신호** (포지션 1.5배)
- 75점: **보통 신호** (기본 포지션)
- 50점: **약한 신호** (포지션 0.5배)
- 50점 미만: **진입 불가**

> 상세: [references/confluence.md](references/confluence.md)

### 멀티 타임프레임 분석

```
일봉   → 대추세 확인 (200 EMA 방향)
4시간  → 중기 추세, 지지/저항
1시간  → 진입 타이밍 (컨플루언스 대기)
15분   → 정밀 진입 (캔들 패턴)
```

**정렬 보너스**:
- 3개 정렬: +15% 신뢰도
- 2개 정렬: +5%
- 역방향: 진입 불가

---

## 포지션 사이징 & 리스크 관리

### Kelly Criterion

```
f* = (bp - q) / b

f* = 최적 베팅 비율
b  = 손익비 (Risk-Reward Ratio)
p  = 승률
q  = 1 - p
```

**Half Kelly 권장** (변동성 감소):
```kotlin
fun calculatePositionSize(winRate: Double, riskReward: Double, capital: Double): Double {
    val kelly = (winRate * riskReward - (1 - winRate)) / riskReward
    val halfKelly = kelly / 2
    return (capital * halfKelly.coerceIn(0.0, 0.05)).coerceAtMost(capital * 0.05)
}
```

### ATR 기반 동적 손절

```kotlin
fun calculateStopLoss(entry: Double, atr: Double, multiplier: Double, isBuy: Boolean): Double {
    return if (isBuy) entry - (atr * multiplier)
    else entry + (atr * multiplier)
}
```

### 손익비 가이드라인

| 신뢰도 | 최소 R:R | 손절 % | 익절 % |
|--------|---------|--------|--------|
| 90%+ | 1.5:1 | -2% | +3% |
| 70-89% | 2:1 | -2% | +4% |
| 50-69% | 3:1 | -2% | +6% |

> 상세: [references/risk-management.md](references/risk-management.md)

---

## 펌프앤덤프 탐지 & 회피

### 위험 신호

| 지표 | 펌프앤덤프 의심 | 정상 급등 |
|------|----------------|----------|
| 시가총액 | **$50M 미만** | $50M+ |
| 거래량 패턴 | 수 분 후 급락 | 지속적 증가 |
| 뉴스 | 없음/가짜 | 실제 호재 |
| 소셜미디어 | 급격한 언급 증가 | 점진적 증가 |
| 가격 패턴 | V자 급등급락 | 점진적 상승 |

### ML 기반 탐지 (2024-2025 연구)

- **XGBoost/LightGBM + SMOTE**: 94% Recall
- **BERTweet + GPT-4o**: 실시간 텔레그램 감지
- **특징**: 거래량 급등, 호가창 불균형, 소셜 언급 급증

> 상세: [references/pump-dump.md](references/pump-dump.md)

---

## 온체인 분석 & 웨일 추적

### 핵심 지표

| 지표 | 의미 | 신호 |
|------|------|------|
| 거래소 유입 | 매도 압력 | **약세** |
| 거래소 유출 | 홀딩 심리 | **강세** |
| 웨일 축적 | 대형 투자자 매집 | **강세** |
| MVRV Ratio | 시장/실현 가치 비율 | >3.5 과열, <1 저평가 |
| NUPL | 미실현 손익 | >0.75 과열, <0 공포 |

### 2025 시장 인사이트

- Glassnode 축적 점수 0.99/1.0 = 웨일 강한 매집
- 웨일 집중도 낮을수록 가격 안정성 35% 향상

> 상세: [references/onchain.md](references/onchain.md)

---

## 뉴스 & 감성 분석

### 감성 점수 가중치

```
감성 점수 = Σ(개별 점수 × 소스 가중치) / 총 가중치

소스 가중치:
- 전문 분석: 4
- 주요 뉴스: 3
- 온체인 데이터: 2
- 소셜 미디어: 1
```

### 점수 해석

| 점수 | 해석 | 액션 |
|------|------|------|
| 1.5 ~ 2.0 | 극도 낙관 | **매도 고려** (과열) |
| 0.5 ~ 1.5 | 낙관 | 매수 유지 |
| -0.5 ~ 0.5 | 중립 | 기술적 분석 따름 |
| -1.5 ~ -0.5 | 비관 | 매도 유지 |
| -2.0 ~ -1.5 | 극도 비관 | **매수 고려** (공포) |

### 키워드 영향

**강세**: ETF 승인(+++), 기관 투자(++), 파트너십(++), 업그레이드(+)
**약세**: 규제 강화(---), 해킹(---), 소송(--), 거래소 파산(---)

> 상세: [references/sentiment.md](references/sentiment.md)

---

## 시장 레짐 감지

### 변동성 클러스터링 (GARCH)

암호화폐 특성:
- 높은 ARCH 효과 (시간 의존 분산)
- 레짐 전환: 고변동 → 저변동 → 고변동

### GMM (Gaussian Mixture Model) 레짐 분류

```
레짐 1: 저변동 횡보 → 평균회귀 전략
레짐 2: 고변동 추세 → 추세추종 전략
레짐 3: 급락 위기 → 포지션 축소/헷지
```

### 레짐별 파라미터 조정

| 레짐 | ATR 배수 | 포지션 크기 | 주요 전략 |
|------|---------|-----------|----------|
| 저변동 | 1.5x | 100% | 볼린저, RSI |
| 중변동 | 2.5x | 75% | MACD, 이치모쿠 |
| 고변동 | 4x | 50% | ATR 손절만 |

> 상세: [references/regime.md](references/regime.md)

---

## 호가창 분석 (Order Book)

### Order Book Imbalance

```
Imbalance = (Bid Volume - Ask Volume) / (Bid Volume + Ask Volume)
```

- Imbalance > 0.3: 매수 압력, 상승 가능
- Imbalance < -0.3: 매도 압력, 하락 가능

### 시간대별 유동성 패턴 (2025 연구)

- **00:00-12:00 UTC**: 평균 Imbalance +1.54%
- **12:00-24:00 UTC**: 평균 Imbalance +3.18% (매수 압력 2배)
- **21:00 UTC**: 유동성 42% 감소 (스프레드 확대)

### 대량 주문 감지

- **지지벽 (Bid Wall)**: 하락 저항
- **저항벽 (Ask Wall)**: 상승 저항
- **아이스버그**: 숨겨진 대량 주문 (분할 매매 신호)

> 상세: [references/orderbook.md](references/orderbook.md)

---

## 전략 유형별 가이드

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

## 체크리스트

### 진입 전 확인

```
□ 시장 레짐 확인 (추세/횡보/고변동)
□ 멀티 타임프레임 정렬 확인
□ 컨플루언스 점수 ≥ 75
□ 리스크 = 자본의 1-2%
□ R:R ≥ 2:1
□ 뉴스/이벤트 확인
□ 펌프앤덤프 필터 통과
```

### 진입 후 관리

```
□ 손절 주문 즉시 설정
□ 트레일링 스탑 조건 설정
□ 익절 목표 설정 (분할 청산)
□ 포지션 기록 (회고용)
```

---

## 참조 파일

| 파일 | 내용 |
|------|------|
| [references/indicators.md](references/indicators.md) | RSI, MACD, 볼린저, ATR, 이치모쿠, 피보나치 상세 |
| [references/confluence.md](references/confluence.md) | 컨플루언스 전략 상세, 코드 예시 |
| [references/risk-management.md](references/risk-management.md) | Kelly 공식, 포지션 사이징, 손절/익절 |
| [references/pump-dump.md](references/pump-dump.md) | 펌프앤덤프 탐지, ML 연구 |
| [references/onchain.md](references/onchain.md) | 온체인 지표, 웨일 추적 |
| [references/sentiment.md](references/sentiment.md) | 뉴스 감성 분석, LLM 활용 |
| [references/regime.md](references/regime.md) | 시장 레짐 감지, GARCH/GMM |
| [references/orderbook.md](references/orderbook.md) | 호가창 분석, 유동성 패턴 |
| [references/strategies.md](references/strategies.md) | 전략 유형별 상세 가이드 |

---

*2025-2026 학술 연구 및 실전 백테스트 기반*
