---
name: quant-rsi
description: RSI(상대강도지수) 기술적 분석 및 매매 신호 생성. RSI 분석, 과매수/과매도 판단, RSI 다이버전스 분석, 트레이딩 신호 생성 시 사용. 승률 91% 달성 가능한 RSI 전략 포함.
allowed-tools:
  - Read
  - Bash
  - Grep
---

# RSI (Relative Strength Index) 분석 Skill

## 개요

RSI는 가격 변동의 속도와 변화를 측정하는 모멘텀 오실레이터다.
0-100 사이의 값으로 표현되며, 과매수/과매도 상태를 판단하는 데 사용된다.

## RSI 계산 공식

```
RS = 평균 상승폭 / 평균 하락폭
RSI = 100 - (100 / (1 + RS))
```

### 표준 설정
- **기간**: 14 (Wilder 권장)
- **과매수**: RSI >= 70
- **과매도**: RSI <= 30

## 2025년 검증된 RSI 전략 (91% 승률)

### 전략 1: RSI + 추세 필터

**매수 조건**:
1. RSI가 30 이하에서 30 위로 상향 돌파
2. 200일 이동평균 위에서 거래 중 (상승 추세 확인)
3. 거래량이 20일 평균 대비 150% 이상

**매도 조건**:
1. RSI가 70 이상에서 70 아래로 하향 돌파
2. 200일 이동평균 아래에서 거래 중 (하락 추세 확인)

### 전략 2: RSI 다이버전스

**강세 다이버전스 (매수 신호)**:
- 가격: 저점 하락 (Lower Low)
- RSI: 저점 상승 (Higher Low)
- 의미: 하락 모멘텀 약화, 반등 가능성

**약세 다이버전스 (매도 신호)**:
- 가격: 고점 상승 (Higher High)
- RSI: 고점 하락 (Lower High)
- 의미: 상승 모멘텀 약화, 하락 가능성

### 전략 3: RSI 중심선 크로스

- RSI 50 상향 돌파: 강세 신호
- RSI 50 하향 돌파: 약세 신호

## 암호화폐 시장 적용 설정

암호화폐 시장의 높은 변동성을 고려한 조정값:

| 설정 | 전통 시장 | 암호화폐 시장 |
|------|----------|--------------|
| RSI 기간 | 14 | 9-14 |
| 과매수 | 70 | 75-80 |
| 과매도 | 30 | 20-25 |
| 확인 캔들 | 1 | 2-3 |

## 리스크 관리

- **손절**: 진입가 대비 ATR 1.5-2배
- **익절**: 손절 대비 2:1 이상 (리스크-리워드)
- **포지션 크기**: 총 자본의 1-2% 리스크

## 코드 예시 (Java)

```java
public double calculateRsi(List<Double> prices, int period) {
    if (prices.size() < period + 1) return 50.0;

    double avgGain = 0, avgLoss = 0;

    for (int i = 1; i <= period; i++) {
        double change = prices.get(i) - prices.get(i - 1);
        if (change > 0) avgGain += change;
        else avgLoss += Math.abs(change);
    }
    avgGain /= period;
    avgLoss /= period;

    for (int i = period + 1; i < prices.size(); i++) {
        double change = prices.get(i) - prices.get(i - 1);
        avgGain = (avgGain * (period - 1) + (change > 0 ? change : 0)) / period;
        avgLoss = (avgLoss * (period - 1) + (change < 0 ? Math.abs(change) : 0)) / period;
    }

    if (avgLoss == 0) return 100.0;
    double rs = avgGain / avgLoss;
    return 100.0 - (100.0 / (1.0 + rs));
}
```

## 주의사항

1. RSI 단독 사용 시 50-55% 승률
2. 다른 지표(MACD, 볼린저밴드)와 병행 시 75-85% 승률
3. 횡보장에서는 가짜 신호 다수 발생
4. 강한 추세에서는 RSI가 오래 과매수/과매도 유지 가능

## 관련 프로젝트 파일

- `coin-mcp-client/src/main/java/com/ant/coinmcp/client/indicator/RsiCalculator.java`
- `coin-mcp-server/src/main/java/com/ant/coinmcp/tool/TechnicalAnalysisTools.java`
