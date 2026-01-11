---
name: trading-decision
description: AI 기반 종합 트레이딩 결정. 기술적 분석, 감성 분석, 리스크 관리를 종합하여 매수/매도/홀드 결정. 포지션 사이징, 손절/익절 설정, 빗썸 API 연동 자동 주문 시 사용.
allowed-tools:
  - Read
  - Bash
  - WebFetch
  - WebSearch
  - Grep
---

# AI 트레이딩 결정 (Trading Decision) Skill

## 개요

이 Skill은 모든 분석을 종합하여 최종 트레이딩 결정을 내린다.
기술적 분석, 감성 분석, 리스크 관리를 통합하여 자동 또는 반자동 거래를 수행한다.

## 결정 프레임워크

### 3단계 필터링

```
1단계: 매크로 필터
   └─ 시장 전체 추세, 규제 환경, 글로벌 이벤트

2단계: 기술적 필터
   └─ 컨플루언스 점수, 추세 정렬, 지지/저항

3단계: 감성 필터
   └─ 뉴스 감성, 소셜 감성, 온체인 데이터
```

## 종합 점수 계산

```java
public record TradingDecision(
    String action,      // BUY, SELL, HOLD
    double confidence,  // 0-100%
    double positionSize,
    double entryPrice,
    double stopLoss,
    double takeProfit,
    String reason
) {}

public TradingDecision decide(
    ConfluenceScore technical,  // 기술적 분석
    SentimentScore sentiment,   // 감성 분석
    MarketCondition market,     // 시장 상황
    RiskProfile risk            // 리스크 프로필
) {
    // 가중치 적용
    double score = 0;
    score += technical.totalScore() * 0.5;   // 50%
    score += sentiment.score() * 30;         // 30%
    score += market.trendScore() * 0.2;      // 20%

    String action;
    if (score >= 70 && technical.buySignal()) {
        action = "BUY";
    } else if (score <= 30 && technical.sellSignal()) {
        action = "SELL";
    } else {
        action = "HOLD";
    }

    double positionSize = calculatePositionSize(score, risk);
    double[] levels = calculateSLTP(action, market.currentPrice(), risk);

    return new TradingDecision(
        action, score, positionSize,
        market.currentPrice(), levels[0], levels[1],
        buildReason(technical, sentiment, market)
    );
}
```

## 포지션 사이징

### 켈리 공식 적용

```
f* = (bp - q) / b

f* = 최적 베팅 비율
b = 승리 시 배당률 (손익비)
p = 승률
q = 패배율 (1 - p)
```

### 실전 적용 (Half Kelly)

```java
public double calculatePositionSize(double winRate, double riskReward, double capital) {
    double kellyFraction = (winRate * riskReward - (1 - winRate)) / riskReward;
    double halfKelly = kellyFraction / 2;  // 안전을 위해 절반만 사용

    // 최대 5% 제한
    double maxPosition = capital * 0.05;
    double calculatedPosition = capital * Math.max(0, halfKelly);

    return Math.min(calculatedPosition, maxPosition);
}
```

### 신뢰도 기반 조정

| 신뢰도 | 포지션 배수 |
|--------|-----------|
| 90-100% | 1.5x |
| 70-89% | 1.0x |
| 50-69% | 0.5x |
| < 50% | 진입 불가 |

## 손절/익절 설정

### ATR 기반 손절

```java
public double calculateStopLoss(
    String action, double entry, double atr, double multiplier
) {
    if ("BUY".equals(action)) {
        return entry - (atr * multiplier);  // 매수: 아래에 손절
    } else {
        return entry + (atr * multiplier);  // 매도: 위에 손절
    }
}
```

### 손익비 기반 익절

| 신뢰도 | 최소 손익비 |
|--------|-----------|
| 90%+ | 1.5:1 |
| 70-89% | 2:1 |
| 50-69% | 3:1 |

## 빗썸 API 연동 자동 주문

### 주문 실행 흐름

```java
public void executeDecision(TradingDecision decision) {
    // 1. 잔고 확인
    Balance balance = bithumbApi.getBalance(decision.currency());

    // 2. 주문 가능 금액 계산
    double available = calculateAvailable(balance, decision);

    // 3. 주문 실행
    if (decision.isBuy()) {
        bithumbApi.buyMarketOrder(
            decision.market(),
            available
        );
    } else if (decision.isSell()) {
        bithumbApi.sellMarketOrder(
            decision.market(),
            decision.positionSize()
        );
    }

    // 4. 손절/익절 주문 설정 (지정가)
    if (decision.stopLoss() > 0) {
        bithumbApi.sellLimitOrder(
            decision.market(),
            decision.positionSize(),
            decision.stopLoss()
        );
    }
}
```

## 리스크 관리 규칙

### 필수 규칙

1. **단일 거래 리스크**: 총 자본의 1-2%
2. **일일 최대 손실**: 총 자본의 5%
3. **동시 포지션**: 최대 3개
4. **상관관계**: 상관 코인 동시 진입 금지

### 긴급 청산 조건

```java
public boolean shouldEmergencyExit(Position position, MarketCondition market) {
    // 1. 일일 손실 한도 초과
    if (dailyLoss > maxDailyLoss) return true;

    // 2. 급격한 변동성 증가
    if (market.volatility() > normalVolatility * 3) return true;

    // 3. 블랙스완 이벤트 감지
    if (market.priceChange1h() < -0.15) return true;  // 1시간 내 15% 하락

    return false;
}
```

## 결정 로깅

### 모든 결정 기록

```java
public record TradingLog(
    Instant timestamp,
    String market,
    TradingDecision decision,
    ConfluenceScore technical,
    SentimentScore sentiment,
    String outcome  // 실행 결과
) {}
```

### 성과 추적

| 지표 | 계산 |
|------|------|
| 승률 | 수익 거래 / 총 거래 |
| 평균 수익 | 총 수익 / 수익 거래 수 |
| 평균 손실 | 총 손실 / 손실 거래 수 |
| 손익비 | 평균 수익 / 평균 손실 |
| 샤프 비율 | (수익률 - 무위험수익률) / 변동성 |

## 시나리오별 결정 예시

### 시나리오 1: 강한 매수 신호

```
기술적: RSI=25, MACD 상향 크로스, BB 하단 이탈, 거래량 200%
감성: 점수 1.2 (낙관)
시장: 상승 추세, 비트코인 동반 상승

결정: BUY
신뢰도: 92%
포지션: 자본의 4%
손절: 진입가 -3%
익절: 진입가 +6%
```

### 시나리오 2: 약한 매도 신호

```
기술적: RSI=68, MACD 약세, BB 상단 근접
감성: 점수 -0.3 (약한 비관)
시장: 횡보, 비트코인 약세

결정: HOLD (기존 포지션 유지, 신규 진입 불가)
신뢰도: 45%
이유: 컨플루언스 부족
```

### 시나리오 3: 긴급 청산

```
이벤트: 주요 거래소 해킹 뉴스
감성: 급락 (-1.8)
가격: 1시간 내 12% 하락

결정: EMERGENCY_SELL
이유: 블랙스완 이벤트, 손실 제한 우선
```

## 빗썸 연동 설정

### 환경 변수

```bash
# .env
BITHUMB_ACCESS_KEY=your_access_key
BITHUMB_SECRET_KEY=your_secret_key
TRADING_ENABLED=false  # 실제 거래 활성화
DRY_RUN=true          # 시뮬레이션 모드
```

### API 엔드포인트

```
빗썸 Private API:
- 잔고 조회: POST /info/balance
- 시장가 매수: POST /trade/market_buy
- 시장가 매도: POST /trade/market_sell
- 지정가 주문: POST /trade/place
- 주문 취소: POST /trade/cancel
```

## 관련 프로젝트 파일

- `coin-mcp-server/src/main/java/com/ant/coinmcp/tool/TradingTools.java`
- `coin-mcp-client/src/main/java/com/ant/coinmcp/client/trading/AutoTradingService.java`
- `coin-mcp-server/src/main/java/com/ant/coinmcp/api/bithumb/BithumbPrivateApi.java`
