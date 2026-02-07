# Grid Trading 전략 가이드

## 개요

가격 구간을 그리드(Grid)로 나누어 자동 매매하는 전략으로, 횡보장에서 작은 수익을 반복적으로 획득합니다.

## 작동 원리

### 기본 원리

```
[횡보장] 5100만원 ~ 4900만원 구간
          ↓
   [그리드 설정]
   5200만원: 매도  (상단)
   5100만원: ---
   5000만원: 기준점  (50%)
   4900만원: 매수  (하단)
   4800만원: 매수
          ↓
   [자동 매매]
   5200→5000: -20만원 수익 (매도 후 기준점으로 매수)
   4800→4900: -10만원 수익 (매수 후 기준점으로 매도)
   4900→5200: +30만원 수익 (기준점에서 매수 후 상단 매도)
```

### 메커니즘

| 단계 | 동작 | 가격 변동 |
|------|------|----------|
| 1. | 기준점 설정 | 최근 50캔들 기반 |
| 2. | 그리드 생성 | ±레벨수 × 간격% |
| 3. | 주문 진입 | 그리드 레벨 도달 시 |
| 4. | 포지션 청산 | 반대 레벨 도달 시 |

---

## 진입/청산 조건

### 진입 조건

| 조건 | 값 | 설명 |
|------|-----|------|
| 그리드 생성 완료 | - | 기준점 ± 레벨×간격% 설정 완료 |
| 시장 상태 활성화 | `trading.enabled=true` | 실거래 모드 |
| OPEN 포지션 없음 | - | 같은 마켓에 진입 포지션 없음 |

### 청산 조건 (자동)

| 조건 | 설명 |
|------|------|
| **매도 청산** | 상단 레벨(SELL) 도달 시 기준점으로 매수 |
| **매수 청산** | 하단 레벨(BUY) 도달 시 기준점으로 매도 |
| **사용자 수동** | 사용자 직접 포지션 청산 |

---

## 설정 파라미터

### application.yml

```yaml
trading:
  # Grid 전략 활성화
  strategy:
    type: GRID

  # Grid 설정
  grid-levels: 5              # 그리드 레벨 수 (상하단 포함 기준점)
  grid-spacing: 1.0           # 그리드 간격 (%), 1.0% = 100만원 기준 ±1만원
```

### 런타임 API 변경

```bash
# 그리드 레벨 수 변경 (5 → 7)
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.strategy.gridLevels", "value": "7"}'

# 그리드 간격 변경 (1% → 2%)
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.strategy.gridSpacing", "value": "2.0"}'
```

### 예시 (100만원 기준)

| 설정 | 값 | 그리드 (만원) |
|------|-----|---------------|
| 레벨 수 | 5 | 4800, 4900, 5000, 5100, 5200 |
| 간격 | 1% | ±10만원 |

---

## 사용법

### 1. 전략 활성화

```yaml
# application.yml
trading:
  enabled: true
  strategy:
    type: GRID
```

### 2. 마켓 설정

```yaml
trading:
  markets:
    - KRW-BTC
    - KRW-ETH
```

### 3. 상태 확인

```bash
# 전체 트레이딩 상태
curl http://localhost:8080/api/trading/status

# 특정 마켓 분석
curl http://localhost:8080/api/trading/analyze/KRW-BTC

# Grid 상태 확인
curl http://localhost:8080/api/settings/category/grid
```

---

## 리스크 관리

### 내장 리스크 관리

| 기능 | 설명 |
|------|------|
| **최대 포지션 수** | 1개로 제한 (과매매 방지) |
| **서킷브레이커** | 연속 손실 시 자동 거래 중단 |
| **시장 상태 체크** | 스프레드/유동성 확인 후 주문 |
| **마진 관리** | 손절/익절 자동 청산 |

### 사용자 권장 사항

| 상황 | 권장 동작 |
|-------|------------|
| 추세 발생 | Grid 중지 → 다른 전략(DCA) 전환 |
| 큰 폭락 | Grid 중지 → 안전 보유 |
| 급등 반복 | Grid 레벨/간격 재설정 |

---

## 엣지케이스 대응

### 1. 서비스 재시작

- **문제:** 그리드 상태 소실
- **대응:** `@PostConstruct`에서 DB 복원
  ```kotlin
  @PostConstruct
  fun restoreState() {
      val savedJson = keyValueService.get("grid.state.$market")
      if (savedJson != null) {
          val state = jacksonObjectMapper.readValue(savedJson, GridState::class.java)
          gridStates[market] = state
      }
  }
  ```

### 2. 동시 주문 충돌

- **문제:** 여러 워커가 동시에 같은 레벨 도달
- **대응:** 상태 저장 후 중복 체크
  ```kotlin
  if (gridState.lastAction == SignalAction.BUY && action == SignalAction.BUY) {
      return // 중복 차단
  }
  ```

### 3. 급격 등락

- **문제:** 빠르게 상단 통과 → 포지션 없음
- **대응:** 스톱로스 사용 + 상태 확인
  ```kotlin
  ReentrantLock("grid-entry-$market").withLock {
      // 진입 로직
  }
  ```

### 4. 가격 급등 후 횡보장 전환

- **문제:** 그리드 범위 밖으로 벗어남
- **대응:** 자동 기준점 재설정
  ```kotlin
  val maxPrice = maxOf(currentPrice, gridState.basePrice * 1.05)
  val newBase = (maxPrice + gridState.basePrice) / 2
  ```

---

## API 엔드포인트

| Method | Endpoint | 설명 |
|--------|-----------|------|
| GET | `/api/trading/status` | 전체 트레이딩 상태 |
| GET | `/api/trading/analyze/{market}` | 특정 마켓 분석 |
| GET | `/api/settings/category/grid` | Grid 상태 조회 |
| POST | `/api/settings` | 런타임 설정 변경 |

---

## 성과 지표

### 효율 척도

| 지표 | 값 | 설명 |
|------|-----|------|
| 횡보장 승률 | 65-75% | 횡보장에서의 매우 높은 승률 |
| 평균 수익률 | 0.5-1.0% | 1회 거래 평균 수익률 |
| 최대 하락 털런스 | -30% | 1회 거래 최대 하락 |

---

## 요약

### 장점

- ✅ 횡보장 최적 (반복 수익)
- ✅ 감정적 매매 제거 (규칙 기반)
- ✅ 시장 상태 지속 모니터링
- ✅ 자동 리스크 관리

### 단점

- ❌ 상승장/하락장에서 수익 감소
- ❌ 큰 추세 발생 시 손실 가능
- ❌ 포지션 관리 복잡 (중복 주문 방지)
- ❌ 수익률 낮음 (횡보장에서도)

### 적합한 시장

| 시장 상황 | 적합도 |
|-----------|--------|
| 횡보장 (±5%) | ⭐⭐⭐⭐⭐ 최적 |
| 약세 추세 (-3%) | ⭐⭐⭐⭐ 부적합 (매도만 가능) |
| 약세 추세 (+3%) | ⭐⭐⭐⭐ 부적합 (매수만 가능) |
| 급격 등락 | ⭐⭐ 불가능 (범위 이탈) |

---

*마지막 업데이트: 2026-02-08*
