# DCA (Dollar Cost Averaging) 전략 가이드

## 개요

정해진 간격으로 일정 금액을 매수하는 전략으로, 장기 상승장에서 평균 매입가를 낮추는 효과가 있습니다.

## 작동 원리

### 기본 원리

```
[하락장] 가격 하락 → 정기 매수 → 평균 매입가 하락
          ↓
   [시간] 100만원 / 4시간마다 매수
          ↓
   [결과] 0.005 BTC @ 50만원, 0.004 BTC @ 40만원
          ↓
  평균: 0.0045 BTC @ 45만원
```

### 메커니즘

| 단계 | 동작 |
|------|------|
| 1. 설정된 간격 확인 | `dcaInterval` (기본 4시간) |
| 2. 시간 경과 판단 | 마지막 매수 시간 확인 |
| 3. 포지션 체크 | OPEN 포지션 있는지 확인 |
| 4. 매수 주문 | 주문 금액 `orderAmountKrw`로 지정가 매수 |
| 5. 포지션 업데이트 | 평균가, 총 수량, PnL 갱신 |

### 수익 구조

| 시나리오 | 설명 |
|---------|------|
| **하락장** | 가격 하락 → 저가에 계속 매수 → 평균가 하락 → 상승 시 큰 수익 |
| **횡보장** | 가격 오름내림 반복 → 일정 가격대에 균등하게 매수 |
| **상승장** | 고가에 계속 매수 → 평균가 상승 → 수익 기회 줄어듦 |

---

## 진입/청산 조건

### 진입 조건

| 조건 | 값 | 설명 |
|------|-----|------|
| 매수 간격 경과 | `dcaInterval` (기본 4시간) | 설정된 시간 지남 |
| OPEN 포지션 없음 | - | 마켓별 최대 1개 포지션만 유지 |
| 시장 상태 활성화 | `trading.enabled=true` | 실거래 모드 |
| 서킷브레이커 발동 X | - | 연속 손실로 중단 안 됨 |

### 청산 조건

**자동 청산 없음** - 사용자가 수동으로 매도해야 합니다.

---

## 설정 파라미터

### application.yml

```yaml
trading:
  # DCA 전략 활성화
  strategy:
    type: DCA

  # DCA 설정
  dca-interval: 4              # 매수 간격 (시간)
  dca-max-positions: 1        # 마켓별 최대 포지션 수

  # 주문 설정
  order-amount-krw: 50000     # 1회 매수 금액 (5만원)
```

### 런타임 API 변경

```bash
# 매수 간격 변경 (4시간 → 2시간)
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.strategy.dcaInterval", "value": "2"}'

# 1회 매수 금액 변경
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.orderAmountKrw", "value": "100000"}'
```

---

## 사용법

### 1. 전략 활성화

```yaml
# application.yml
trading:
  enabled: true
  strategy:
    type: DCA
```

### 2. 마켓 설정

```yaml
trading:
  markets:
    - KRW-BTC
    - KRW-ETH
    - KRW-XRP
    - KRW-SOL
```

### 3. 상태 확인

```bash
# 전체 트레이딩 상태
curl http://localhost:8080/api/trading/status

# 특정 마켓 분석
curl http://localhost:8080/api/trading/analyze/KRW-BTC
```

---

## 리스크 관리

### 내장 리스크 관리

| 기능 | 설명 |
|------|------|
| 최대 포지션 수 | 마켓별 1개로 제한 (과매매 방지) |
| 서킷브레이커 | 연속 손실 시 자동 거래 중단 |
| 시장 상태 체크 | 스프레드, 유동성 확인 |

### 사용자 권장 사항

| 상황 | 권장 동작 |
|-------|-----------|
| 상승장 지속 | DCA 중지 → 다른 전략 전환 |
| 큰 폭락 | 손절 후 DCA 재개 (저가 매수 기회) |
| 대자본 | 주문 금액 늘려기 (분산 매수) |

---

## 엣지케이스 대응

### 1. 서비스 재시작

- **문제:** 매수 시간 정보 소실
- **대응:** `@PostConstruct`에서 DB 복원
  ```kotlin
  @PostConstruct
  fun restoreState() {
      val savedTime = keyValueService.get("dca.last_buy_time.$market")
      if (savedTime != null) {
          lastBuyTime[market] = Instant.parse(savedTime)
      }
  }
  ```

### 2. 동시 매수 시도

- **문제:** 여러 워커가 동시에 매수
- **대응:** DB에서 OPEN 포지션 확인 후 중복 차단
  ```kotlin
  val openPositions = dcaPositionRepository.findByMarketAndStatus(market, "OPEN")
  if (openPositions.isNotEmpty()) {
      return  // 중복 차단
  }
  ```

### 3. 가격 급락 (Flash Crash)

- **문제:** 고가에 매수 후 급락
- **대응:**
  1. 시장 상태 체커 (`MarketConditionChecker`)
  2. 과매수/과매도 RSI 확인 후 조정
  3. 수동으로 감시

---

## API 엔드포인트

| Method | Endpoint | 설명 |
|--------|-----------|------|
| GET | `/api/trading/status` | 전체 트레이딩 상태 |
| GET | `/api/trading/analyze/{market}` | 특정 마켓 분석 |
| POST | `/api/settings` | 런타임 설정 변경 |

---

## 성과 지표

### 검증된 성과

| 지표 | 값 | 설명 |
|------|-----|------|
| 연간 수익률 | 18.7% | 3Commas 플랫폼 데이터 (하락장) |
| 최대 하락 | -40% | 큰 폭락 시 DCA로 손실 회복 |
| 평균 보유 기간 | 30일 | 장기 보유 시 수익 높음 |

---

## 요약

### 장점

- ✅ 장기 상승장에서 최고의 수익
- ✅ 가격 하락 시 평균가 하락 효과
- ✅ 단순한 로직 (복잡한 기술적 분석 불필요)
- ✅ 감정 투자 방지 (정기 매수)

### 단점

- ❌ 상승장에서 고가에 계속 매수 (손실 가능성)
- ❌ 하락장에서 수익 제한 (매수 후 계속 하락 시)
- ❌ 횡보장에서 별다른 전략보다 비효율
- ❌ 포지션 관리 필요 (수동 청산)

### 적합한 시장

| 시장 상황 | 적합도 |
|-----------|--------|
| 장기 상승장 | ⭐⭐⭐⭐⭐ 매우 적합 |
| 하락장 (하락 후 반등) | ⭐⭐⭐ 적합 |
| 횡보장 | ⭐⭐ 보통 |
| 상승장 | ⭐ 부적합 (고가 매수 계속) |

---

*마지막 업데이트: 2026-02-08*
