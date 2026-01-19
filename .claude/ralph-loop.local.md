# Ralph Loop 작업 기록

## 반복 1 (2026-01-19) - 완료

### 분석 결과

#### 1. TradingEngine 무한 루프 위험 발견 (치명적)

**문제**:
- TradingEngine에서 포지션 상태 관리 없음
- 매수 후 즉시 매도 가능 → 수수료 손실
- 연속 매수/매도 시 0.08% × N 누적 손실

**해결**:
- 최소 보유 시간 5분 추가
- 거래 쿨다운 5분 추가
- 중복 매수 방지 (5000원 이상 보유 시)

**커밋**: c411582

#### 2. VolumeSurge/MemeScalper 엔진 - 안전

두 엔진 모두 포지션 상태를 DB에서 관리하여 중복 방지됨:
- `findByMarketAndStatus(market, "OPEN")` 확인
- 쿨다운 기간 설정
- 최소 보유 시간 (MemeScalper: 10초)

#### 3. CircuitBreaker - 강력한 안전장치

검증된 트리거:
- 연속 3회 PnL 손실
- 일일 손실 5% 초과
- 연속 5회 주문 실행 실패
- 슬리피지 2% 초과 3회 연속
- 1분 내 API 에러 10회
- 총 자산 10% 감소

---

## 반복 2 (2026-01-19) - 완료

### 분석 결과

#### 1. 매수 가격 0으로 기록되는 버그 발견 (심각)

**문제**:
- trades 테이블의 BUY 주문 중 대부분이 `price = 0.0`
- PnL 계산 불가 → 회고 시스템 분석 실패
- 100개 최근 거래 중 BUY 대부분이 price=0

**원인 분석**:
- `OrderExecutor.saveTradeRecord()`에서 `(executedPrice ?: signal.price).toDouble()` 계산
- `executedPrice`가 null이고 `signal.price`도 0이면 가격이 0으로 저장
- 특히 지정가 주문이 PendingOrderManager로 위임되는 경우 발생

**해결**:
```kotlin
// OrderExecutor.kt - saveTradeRecord()
if (tradePrice <= 0) {
    // marketConditionChecker로 현재가 조회하여 대체
    val midPrice = marketConditionChecker.checkMarketCondition(market, positionSize).midPrice
    if (midPrice > 0) {
        tradePrice = midPrice.toDouble()
    }
    // 여전히 0이면 저장 스킵 (데이터 오염 방지)
    if (tradePrice <= 0) return
}
```

**테스트 추가**: `PriceZeroBugPreventionTest.kt`

---

## 반복 3 (2026-01-19) - 완료

### 분석 결과

#### 1. AI 회고 시스템 비교 분석

| 기능 | VolumeSurgeReflector | MemeScalperReflector |
|------|---------------------|---------------------|
| 백테스트 | ✅ `backtestParameterChange` | ❌ 없음 → ✅ 추가 |
| 상관관계 분석 | ✅ `analyzeMarketCorrelation` | ❌ 없음 |
| 시간대 패턴 | ✅ `analyzeHistoricalPatterns` | ❌ 없음 |
| 청산 사유 분석 | ❌ 없음 | ❌ 없음 → ✅ `analyzeExitPatterns` 추가 |
| 시스템 개선 제안 | ✅ `suggestSystemImprovement` | ✅ |

**결론**: VolumeSurgeReflector가 더 성숙한 회고 시스템. MemeScalper에 핵심 기능 추가 필요.

#### 2. MemeScalper `trailingActive` null 버그 (치명적)

**문제**:
- DB에 `trailing_active = NULL` 값 존재
- Entity는 `Boolean = false` (non-nullable)
- MCP 도구 호출 시 에러: "Can not set boolean field ... to null value"

**해결**:
- Entity에 `@Column(nullable = false)` 어노테이션 추가
- DB 마이그레이션 스크립트: `docs/fix-trailing-active-null.sql`

#### 3. MemeScalperReflectorTools 기능 추가

**새로 추가된 도구**:

1. `backtestParameterChange` - 파라미터 변경 전 백테스트
   - 진입 조건 변경 시 필터링되는 트레이드 수 분석
   - 50% 이상 감소 예상 시 경고

2. `analyzeExitPatterns` - 청산 사유별 패턴 분석
   - TAKE_PROFIT, TRAILING_STOP, STOP_LOSS, TIMEOUT별 통계
   - TIMEOUT > 30% 시 진입 조건 강화 권장
   - STOP_LOSS > 40% 시 손절 조건 검토 권장

**시스템 프롬프트 업데이트**:
- 파라미터 변경 전 백테스트 필수 규칙 추가

**테스트 추가**: `MemeScalperReflectorToolsTest.kt`

---

## 반복 4 (2026-01-19) - 완료

### 현재 성과 분석

| 전략 | 30일 거래수 | 총 PnL | 승률 | 문제점 |
|------|------------|--------|------|--------|
| VOLUME_SURGE | 8 | -124원 | 62.5% | 손실 > 이익 (R:R 불균형) |
| MEME_SCALPER | 41 | +38원 | 100%* | DB null 버그로 조회 실패 |
| DCA | 14 | 0원 | N/A | 매도 없음 (홀딩 중) |
| GRID | 5 | 0원 | N/A | 매도 없음 |

**핵심 문제**: Volume Surge는 승률은 높지만 손절 손실이 이익을 초과.

### 새로운 엔진 아이디어 (우선순위순)

#### 1. Market Regime Adaptive Engine (최우선)

**컨셉**: 시장 레짐 자동 감지 후 최적 전략 선택

| 레짐 | 특성 | 사용 전략 |
|------|------|----------|
| 추세장 | 방향성 명확 | Momentum Breakout |
| 횡보장 | 범위 제한 | Mean Reversion, Grid |
| 고변동 | 급등락 | 포지션 축소, ATR 손절만 |

**레짐 감지 방법**:
- ADX > 25 = 추세장
- ADX < 20 + 볼린저밴드 수축 = 횡보장
- ATR > 20일 평균 × 2 = 고변동

**구현 우선순위**: 높음 (전략 효율성 극대화)

#### 2. Momentum Breakout Engine

**컨셉**: 돌파 + 거래량 확인 추세추종

**진입 조건**:
```
1. 가격 > 20 EMA (추세 방향)
2. 거래량 > 20일 평균 × 2
3. RSI 50-70 (모멘텀 있지만 과매수 아님)
4. MACD 히스토그램 상승 중
```

**청산**:
- ATR × 3 트레일링 스탑
- 거래량 급감 시 조기 청산

**구현 우선순위**: 높음

#### 3. RSI Divergence Engine

**컨셉**: 숨은 다이버전스 탐지로 반전 예측

**강세 다이버전스 (매수)**:
- 가격: Lower Low
- RSI: Higher Low
- 확인: MACD 크로스오버

**약세 다이버전스 (매도)**:
- 가격: Higher High
- RSI: Lower High

**구현 우선순위**: 중간

#### 4. Ichimoku Cloud Engine

**컨셉**: 이치모쿠 구름 기반 추세 추종

**암호화폐 설정**: 20/60/120/30 (24/7 시장)

**진입 조건**:
- 가격 > 구름 상단 돌파
- 전환선 > 기준선
- 구름이 두꺼움 (지지력 강함)

**회피 조건**:
- 평평한 구름 (횡보장)
- 구름 내부 가격 (불확실)

**구현 우선순위**: 중간

#### 5. Funding Rate Arbitrage Phase 2

**현재 상태**: Phase 1 모니터링 완료

**Phase 2 구현 필요**:
- 자동 진입/청산 로직
- 현물 매수 + 선물 숏 동시 실행
- Bybit API 연동 (선물)

**구현 우선순위**: 낮음 (선물 거래소 API 필요)

### 권장 구현 순서

1. **Volume Surge R:R 개선** (즉시)
   - 손절 -2% → -3% (ATR 기반 동적 조정)
   - 익절 목표 상향 (손익비 2:1 확보)

2. **Market Regime Detector 추가** (1주)
   - ADX, ATR 기반 레짐 감지
   - 레짐별 전략 가중치 조정

3. **Momentum Breakout Engine 신규** (2주)
   - 추세장에서만 활성화
   - Volume Surge 대안

---

## 다음 반복 계획

### 5. Volume Surge R:R 개선 실험 (분석 완료)

**현재 상태 분석**:
- ATR 기반 동적 손절 이미 활성화 (`useDynamicStopLoss = true`)
- `StopLossCalculator.kt:42` - 최대 손절: 10%
- `VolumeSurgeProperties.kt:30` - 익절: 5%

**문제 원인**:
- 고변동 시장에서 ATR 손절이 4x까지 허용 → 최대 10% 손실
- 익절은 고정 5% → **R:R = 0.5:1 (불리)**

**권장 개선안**:
1. **즉시**: 익절 목표 상향 (5% → 10%)
2. **중기**: 동적 익절 구현 (ATR × 2 = 익절 목표)
3. **고려**: 레짐별 진입 필터 (고변동장 진입 회피)

```kotlin
// 개선 코드 예시: VolumeSurgeProperties
takeProfitPercent: Double = 10.0  // 5% → 10%

// 또는 동적 익절 (R:R 2:1 확보)
val takeProfitPercent = stopLossResult.stopLossPercent * 2
```

### 6. 백테스팅 프레임워크

과거 데이터 기반 전략 검증:
- Candle 히스토리 DB 저장
- Walk-forward 최적화
- Sharpe Ratio, Max Drawdown 계산
