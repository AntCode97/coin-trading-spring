---
name: quant-bollinger
description: 볼린저밴드 기술적 분석 및 매매 신호 생성. 볼린저밴드 분석, 밴드 스퀴즈, %B 지표, 밴드폭 분석, 변동성 돌파 전략 시 사용. MACD와 조합 시 78% 승률 검증됨.
allowed-tools:
  - Read
  - Bash
  - Grep
---

# 볼린저밴드 (Bollinger Bands) 분석 Skill

## 개요

볼린저밴드는 가격의 상대적 고점/저점을 정의하는 변동성 지표다.
중심선(이동평균)과 상/하단 밴드(표준편차)로 구성된다.

## 볼린저밴드 계산

```
중심선 = 20일 단순이동평균 (SMA)
상단밴드 = 중심선 + (표준편차 × 2)
하단밴드 = 중심선 - (표준편차 × 2)
밴드폭 = (상단 - 하단) / 중심선 × 100
%B = (현재가 - 하단) / (상단 - 하단)
```

### 표준 설정
- **기간**: 20
- **표준편차 배수**: 2.0

## 2025년 검증된 볼린저밴드 전략 (78% 승률)

### 전략 1: 볼린저밴드 + MACD (78% 승률)

**매수 조건**:
1. 가격이 하단밴드 터치 또는 이탈 (%B <= 0)
2. MACD 히스토그램 상승 전환
3. 밴드폭(Bandwidth) > 4% (충분한 변동성)

**매도 조건**:
1. 가격이 상단밴드 터치 또는 이탈 (%B >= 1)
2. MACD 히스토그램 하락 전환

### 전략 2: 밴드 스퀴즈 (Squeeze) 돌파

**스퀴즈 식별**:
- 밴드폭이 6개월 최저치 근접
- 밴드가 좁아지면 큰 움직임 예고

**돌파 매매**:
1. 스퀴즈 상태에서 상단밴드 돌파 → 매수
2. 스퀴즈 상태에서 하단밴드 돌파 → 매도
3. 거래량 급증으로 확인

### 전략 3: 더블 바텀/탑 패턴

**W 바텀 (매수)**:
1. 첫 번째 저점: 하단밴드 이탈
2. 반등 후 두 번째 저점: 하단밴드 내부
3. 중심선 돌파 시 매수 진입

**M 탑 (매도)**:
1. 첫 번째 고점: 상단밴드 이탈
2. 조정 후 두 번째 고점: 상단밴드 내부
3. 중심선 이탈 시 매도 진입

## %B 지표 해석

| %B 값 | 의미 | 신호 |
|-------|------|------|
| > 1.0 | 상단밴드 이탈 | 과매수, 매도 고려 |
| 0.8 - 1.0 | 상단밴드 근접 | 매도 준비 |
| 0.5 | 중심선 | 중립 |
| 0.0 - 0.2 | 하단밴드 근접 | 매수 준비 |
| < 0.0 | 하단밴드 이탈 | 과매도, 매수 고려 |

## 암호화폐 시장 적용 설정

| 설정 | 전통 시장 | 암호화폐 시장 |
|------|----------|--------------|
| 기간 | 20 | 20 |
| 표준편차 | 2.0 | 2.0-2.5 |
| 스퀴즈 기준 | 밴드폭 4% | 밴드폭 3% |

## 리스크 관리

- **손절**: 진입 반대 밴드 돌파 시
- **익절**: 반대편 밴드 도달 또는 중심선
- **포지션 크기**: 밴드폭에 반비례 (넓을수록 작게)

## 코드 예시 (Java)

```java
public record BollingerBands(
    double upper, double middle, double lower,
    double bandwidth, double percentB
) {}

public BollingerBands calculate(List<Double> prices, int period, double stdDevMultiplier) {
    if (prices.size() < period) return null;

    List<Double> recent = prices.subList(prices.size() - period, prices.size());

    double middle = recent.stream().mapToDouble(d -> d).average().orElse(0);

    double variance = recent.stream()
        .mapToDouble(p -> Math.pow(p - middle, 2))
        .average().orElse(0);
    double stdDev = Math.sqrt(variance);

    double upper = middle + (stdDev * stdDevMultiplier);
    double lower = middle - (stdDev * stdDevMultiplier);
    double bandwidth = (upper - lower) / middle * 100;
    double percentB = (prices.getLast() - lower) / (upper - lower);

    return new BollingerBands(upper, middle, lower, bandwidth, percentB);
}
```

## 주의사항

1. 횡보장에서 가장 효과적
2. 강한 추세에서는 밴드 타기(Riding the Band) 현상
3. 밴드 터치만으로 진입하면 승률 50% 미만
4. 반드시 모멘텀 지표(MACD, RSI)와 병행

## 관련 프로젝트 파일

- `coin-mcp-client/src/main/java/com/ant/coinmcp/client/indicator/BollingerBandCalculator.java`
- `coin-mcp-server/src/main/java/com/ant/coinmcp/tool/TechnicalAnalysisTools.java`
