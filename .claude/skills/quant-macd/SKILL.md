---
name: quant-macd
description: MACD(이동평균 수렴확산) 기술적 분석 및 매매 신호 생성. MACD 크로스오버, 다이버전스, 히스토그램 분석, 골든/데드 크로스 시 사용. RSI와 조합 시 77% 승률 검증됨.
allowed-tools:
  - Read
  - Bash
  - Grep
---

# MACD (Moving Average Convergence Divergence) 분석 Skill

## 개요

MACD는 두 이동평균 간의 관계를 보여주는 추세 추종 모멘텀 지표다.
추세의 방향, 지속 시간, 강도, 모멘텀을 판단하는 데 사용된다.

## MACD 계산

```
MACD Line = 12일 EMA - 26일 EMA
Signal Line = MACD Line의 9일 EMA
Histogram = MACD Line - Signal Line
```

### 표준 설정
- **빠른 EMA**: 12
- **느린 EMA**: 26
- **시그널 EMA**: 9

## 2025년 검증된 MACD 전략

### 전략 1: MACD + RSI (77% 승률)

**매수 조건**:
1. MACD 선이 시그널 선 상향 돌파
2. RSI가 30-50 구간 (과매도에서 회복 중)
3. 히스토그램이 음수에서 양수로 전환

**매도 조건**:
1. MACD 선이 시그널 선 하향 돌파
2. RSI가 50-70 구간 (과매수로 진입 중)
3. 히스토그램이 양수에서 음수로 전환

### 전략 2: MACD 다이버전스

**강세 다이버전스 (매수)**:
- 가격: 저점 하락
- MACD: 저점 상승
- 히스토그램: 바닥이 얕아짐

**약세 다이버전스 (매도)**:
- 가격: 고점 상승
- MACD: 고점 하락
- 히스토그램: 피크가 낮아짐

### 전략 3: 제로라인 크로스

- MACD가 0선 상향 돌파: 강세 추세 시작
- MACD가 0선 하향 돌파: 약세 추세 시작
- 0선 크로스 후 시그널 크로스로 확인

### 전략 4: 히스토그램 반전

**히스토그램 상승 반전 (매수)**:
1. 히스토그램이 음수 영역에서 하락
2. 첫 번째 상승 막대 출현
3. 연속 2-3개 상승 막대로 확인

**히스토그램 하락 반전 (매도)**:
1. 히스토그램이 양수 영역에서 상승
2. 첫 번째 하락 막대 출현
3. 연속 2-3개 하락 막대로 확인

## 암호화폐 시장 적용 설정

| 설정 | 전통 시장 | 암호화폐 시장 |
|------|----------|--------------|
| 빠른 EMA | 12 | 8-12 |
| 느린 EMA | 26 | 21-26 |
| 시그널 | 9 | 9 |
| 확인 캔들 | 1 | 2 |

### 타임프레임별 권장 설정

| 타임프레임 | 빠른/느린/시그널 | 용도 |
|-----------|-----------------|------|
| 1분 | 5/13/6 | 스캘핑 |
| 15분 | 8/21/5 | 단타 |
| 1시간 | 12/26/9 | 스윙 |
| 4시간 | 12/26/9 | 포지션 |
| 일봉 | 12/26/9 | 장기 |

## 리스크 관리

- **손절**: 시그널 재크로스 시 즉시 청산
- **익절**: 히스토그램 반전 시 부분 익절
- **추가 진입**: 0선 크로스 후 풀백 시

## 코드 예시 (Java)

```java
public record MacdResult(
    double macdLine, double signalLine, double histogram
) {}

public MacdResult calculate(List<Double> prices, int fast, int slow, int signal) {
    if (prices.size() < slow + signal) return null;

    double fastEma = calculateEma(prices, fast);
    double slowEma = calculateEma(prices, slow);
    double macdLine = fastEma - slowEma;

    // MACD 라인들의 EMA로 시그널 계산
    List<Double> macdHistory = calculateMacdHistory(prices, fast, slow);
    double signalLine = calculateEma(macdHistory, signal);

    return new MacdResult(macdLine, signalLine, macdLine - signalLine);
}

private double calculateEma(List<Double> prices, int period) {
    double multiplier = 2.0 / (period + 1);
    double ema = prices.get(0);
    for (int i = 1; i < prices.size(); i++) {
        ema = (prices.get(i) - ema) * multiplier + ema;
    }
    return ema;
}
```

## MACD vs 다른 지표 성능 비교

| 지표 조합 | 단독 승률 | 조합 승률 |
|----------|----------|----------|
| MACD | 50-55% | - |
| MACD + RSI | - | 77% |
| MACD + 볼린저 | - | 78% |
| MACD + RSI + 볼린저 | - | 85% |

## 주의사항

1. 횡보장에서 잦은 가짜 신호 발생
2. 지연 지표이므로 빠른 움직임 포착 어려움
3. 강한 추세에서는 크로스 없이 추세 지속 가능
4. 반드시 가격 액션 및 다른 지표로 확인

## 관련 프로젝트 파일

- `coin-mcp-server/src/main/java/com/ant/coinmcp/tool/TechnicalAnalysisTools.java`
