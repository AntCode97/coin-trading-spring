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

## 다음 반복 계획

### 4. 새로운 수익 창출 엔진 아이디어

퀀트 스킬 기반 아이디어:
- **Momentum Breakout Engine**: 돌파 전략 + 거래량 확인
- **Correlation Arbitrage**: 코인 간 상관관계 이용
- **Fear & Greed Index Trading**: 시장 감성 기반 역발상

### 5. 회고 효과 측정 시스템

파라미터 변경 전/후 성과 비교 기능:
- 변경 전 7일 성과 vs 변경 후 7일 성과
- 자동 롤백 기능 (성과 악화 시)
