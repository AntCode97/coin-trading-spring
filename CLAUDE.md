# Coin Trading Spring - AI Agent 가이드

## 프로젝트 개요

Bithumb 암호화폐 거래소를 위한 **Spring Boot** 기반 자동화 트레이딩 시스템.
룰 기반 실시간 트레이딩 + LLM 주기적 최적화 하이브리드 아키텍처.

---

## 아키텍처

```
[coin-trading-server - 단일 서비스]
│
├── 실시간 트레이딩 (룰 기반)
│   ├── DCA (Dollar Cost Averaging)
│   ├── Grid Trading
│   ├── Mean Reversion
│   └── Order Book Imbalance (NEW - 초단기 전략)
│
├── 리스크 관리 (QUANT_RESEARCH.md 기반)
│   ├── CircuitBreaker       - 연속 손실/실행 실패/슬리피지 감시
│   ├── MarketConditionChecker - 스프레드/유동성/변동성 검사
│   ├── OrderExecutor        - 부분체결/슬리피지 검증
│   └── RegimeDelayLogic     - 잦은 전략 변경 방지
│
├── 동적 설정 (KeyValue Store - Redis 대체)
│   ├── MySQL 기반 키-값 저장소
│   ├── 메모리 캐시 (읽기 성능)
│   └── REST API로 런타임 변경
│
├── MCP Tools (LLM 연동)
│   ├── MarketDataTools       - 시장 데이터 조회
│   ├── TechnicalAnalysisTools - RSI, MACD, 볼린저밴드
│   ├── TradingTools          - 주문/잔고
│   ├── StrategyTools         - 전략 파라미터 조정
│   └── PerformanceTools      - 성과 분석
│
└── LLM Optimizer (Spring AI Tool Calling)
    ├── 동적 모델 선택 (Anthropic/OpenAI/Google)
    ├── ModelSelector로 런타임 모델 변경
    ├── Tool Calling으로 LLM이 직접 함수 호출
    └── 주 1회 변경 제한 (과적합 방지)
```

---

## 기술 스택

| 항목 | 버전 |
|------|------|
| JDK | 25 |
| Kotlin | 2.3.0 |
| Spring Boot | 4.0.1 |
| Spring AI | 1.1.1 (Anthropic + OpenAI + Gemini) |
| Gradle | 9.2.1 (Kotlin DSL) |
| MySQL | 8.x |

---

## 모듈 구조

```
coin-trading-spring/
├── coin-trading-server/           # 통합 서버 (Kotlin)
│   └── src/main/kotlin/com/ant/cointrading/
│       ├── CoinTradingApplication.kt
│       ├── api/bithumb/
│       │   ├── BithumbModels.kt       # API 모델
│       │   ├── BithumbPublicApi.kt    # 공개 API
│       │   └── BithumbPrivateApi.kt   # 비공개 API (JWT)
│       ├── config/
│       │   ├── BithumbProperties.kt
│       │   ├── TradingProperties.kt
│       │   └── LlmProperties.kt
│       ├── controller/
│       │   └── SettingsController.kt  # 설정 관리 API (NEW)
│       ├── service/
│       │   ├── KeyValueService.kt     # Redis 대체 (NEW)
│       │   └── ModelSelector.kt       # 동적 모델 선택 (NEW)
│       ├── engine/
│       │   └── TradingEngine.kt       # 메인 트레이딩 엔진
│       ├── order/
│       │   └── OrderExecutor.kt       # 주문 실행기 (완전 재작성)
│       ├── strategy/
│       │   ├── DcaStrategy.kt
│       │   ├── GridStrategy.kt
│       │   ├── MeanReversionStrategy.kt
│       │   ├── OrderBookImbalanceStrategy.kt  # (NEW)
│       │   └── StrategySelector.kt
│       ├── regime/
│       │   ├── RegimeDetector.kt      # ADX/ATR 기반
│       │   └── HmmRegimeDetector.kt   # (NEW) HMM 기반
│       ├── risk/
│       │   ├── RiskManager.kt
│       │   ├── CircuitBreaker.kt      # (확장됨)
│       │   └── MarketConditionChecker.kt  # (NEW)
│       ├── optimizer/
│       │   ├── LlmOptimizer.kt        # ModelSelector 연동
│       │   └── OptimizerTools.kt
│       └── repository/
│           ├── TradeEntity.kt         # slippage, isPartialFill 추가
│           ├── KeyValueEntity.kt      # (NEW)
│           └── KeyValueRepository.kt  # (NEW)
│
├── docs/
│   └── QUANT_RESEARCH.md             # 퀀트 연구 노트
└── CLAUDE.md
```

---

## 새로 추가된 기능 (Phase 1 완료)

### 1. MarketConditionChecker

주문 전 시장 상태 검사:

| 항목 | 한도 | 초과 시 |
|------|------|---------|
| 스프레드 | 0.5% | 거래 금지 |
| 유동성 | 주문량 3x | 거래 금지 |
| 변동성 (1분) | 2% | 경고 |
| API 에러 | 5회 연속 | 거래 금지 |

### 2. OrderExecutor (완전 재작성)

| 기능 | 설명 |
|------|------|
| 주문 상태 확인 | 3회 재시도 (exponential backoff) |
| 부분 체결 감지 | 90% 이상 체결 시 성공 처리 |
| 슬리피지 계산 | 기준가 대비 체결가 차이 |
| 실제 수수료 | API 응답에서 paidFee 사용 |
| CircuitBreaker 연동 | 실패/슬리피지 자동 기록 |

### 3. CircuitBreaker (확장)

| 트리거 | 쿨다운 |
|--------|--------|
| 연속 3회 PnL 손실 | 4시간 |
| 연속 5회 주문 실행 실패 | 1시간 |
| 연속 3회 고슬리피지 (2%+) | 4시간 |
| 1분 내 API 에러 10회 | 24시간 (글로벌) |
| 총 자산 10% 감소 | 24시간 (글로벌) |

### 4. Order Book Imbalance 전략

호가창 불균형 기반 초단기 전략:

```
Imbalance = (Bid - Ask) / (Bid + Ask)
- +0.3 이상: 매수 압력 → BUY
- -0.3 이하: 매도 압력 → SELL
- 연속 3회 확인 시 신호 강화
```

### 5. HMM 레짐 감지기

Hidden Markov Model 기반 정교한 시장 레짐 감지:

| 항목 | 설명 |
|------|------|
| 은닉 상태 | SIDEWAYS, BULL_TREND, BEAR_TREND, HIGH_VOLATILITY |
| 관측값 | 수익률(5레벨) × 변동성(3레벨) × 거래량(3레벨) = 45개 |
| 알고리즘 | Viterbi (가장 가능성 높은 상태 시퀀스 추정) |
| 폴백 | 데이터 부족 시 기존 ADX/ATR 기반 감지기 사용 |

```bash
# 레짐 감지기 상태 확인
curl http://localhost:8080/api/settings/regime

# HMM 레짐 감지기로 변경
curl -X POST http://localhost:8080/api/settings/regime \
  -H "Content-Type: application/json" \
  -d '{"type": "hmm"}'

# Simple(ADX/ATR) 감지기로 복귀
curl -X POST http://localhost:8080/api/settings/regime \
  -H "Content-Type: application/json" \
  -d '{"type": "simple"}'

# HMM 전이 확률 조회
curl http://localhost:8080/api/settings/regime/hmm/transitions
```

### 6. 동적 LLM 모델 선택

KeyValue 저장소를 통한 런타임 모델 변경:

```bash
# 현재 모델 상태 확인
curl http://localhost:8080/api/settings/model

# Google Gemini로 변경
curl -X POST http://localhost:8080/api/settings/model \
  -H "Content-Type: application/json" \
  -d '{"provider": "google"}'

# Anthropic Claude로 변경
curl -X POST http://localhost:8080/api/settings/model \
  -H "Content-Type: application/json" \
  -d '{"provider": "anthropic"}'
```

---

## 설정 관리 API

### KeyValue 설정

```bash
# 전체 설정 조회
curl http://localhost:8080/api/settings

# 특정 키 조회
curl http://localhost:8080/api/settings/llm.model.provider

# 설정 변경
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.enabled", "value": "true"}'

# 카테고리별 조회
curl http://localhost:8080/api/settings/category/llm
```

### 기본 키 목록

| 키 | 기본값 | 설명 |
|----|--------|------|
| `llm.model.provider` | anthropic | LLM 제공자 |
| `llm.model.name` | claude-sonnet-4-20250514 | 모델명 |
| `llm.enabled` | false | LLM 최적화 활성화 |
| `trading.enabled` | false | 실거래 활성화 |
| `system.maintenance` | false | 점검 모드 |
| `regime.detector.type` | simple | 레짐 감지기 (simple/hmm) |

---

## 빌드 및 실행

### 빌드

```bash
./gradlew :coin-trading-server:build -x test
```

### 로컬 실행

```bash
./gradlew :coin-trading-server:bootRun
```

### 상태 확인

```bash
# 헬스 체크
curl http://localhost:8080/actuator/health

# 설정 상태
curl http://localhost:8080/api/settings

# 모델 상태
curl http://localhost:8080/api/settings/model
```

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `BITHUMB_ACCESS_KEY` | Bithumb API Access Key | (필수) |
| `BITHUMB_SECRET_KEY` | Bithumb API Secret Key | (필수) |
| `MYSQL_URL` | MySQL 접속 URL | localhost:3306 |
| `MYSQL_USER` | MySQL 사용자 | root |
| `MYSQL_PASSWORD` | MySQL 비밀번호 | (필수) |
| `SLACK_WEBHOOK_URL` | Slack Webhook URL | (선택) |
| `TRADING_ENABLED` | 실거래 활성화 | false |
| `ORDER_AMOUNT_KRW` | 1회 주문 금액 | 10000 |
| `STRATEGY_TYPE` | 기본 전략 | MEAN_REVERSION |
| `LLM_OPTIMIZER_ENABLED` | LLM 최적화 활성화 | false |
| `SPRING_AI_ANTHROPIC_API_KEY` | Anthropic API 키 | (LLM 사용 시) |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI API 키 | (LLM 사용 시) |
| `SPRING_AI_GOOGLE_API_KEY` | Google Gemini API 키 | (LLM 사용 시) |

---

## 참고 문서

- `docs/QUANT_RESEARCH.md` - 퀀트 연구 노트 (전략, 엣지케이스, 실패 원인)

---

## 다음 단계 (TODO)

### Phase 2: 엣지 확보

1. ~~**HMM 레짐 감지 구현**~~ ✅ 완료
   - Viterbi 알고리즘 직접 구현
   - 45개 이산 관측값 (수익률 × 변동성 × 거래량)
   - API로 simple/hmm 선택 가능

2. **김치 프리미엄 모니터링**
   - 해외 거래소 가격 연동 (Binance API)
   - 프리미엄 계산 및 알림
   - 프리미엄 급등/급락 시 전략 조정

3. **실시간 수수료 조회**
   - 현재 0.25% 하드코딩
   - Bithumb API에서 실제 수수료율 조회
   - VIP 등급별 수수료 반영

### Phase 3: 확장

4. **Funding Rate Arbitrage (선물 거래소 연동)**
   - 현물 Long + 무기한 선물 Short
   - Binance/Bybit 선물 API 연동
   - 펀딩비 자동 수취

5. **백테스팅 프레임워크**
   - 과거 데이터로 전략 검증
   - Walk-forward 최적화
   - Monte Carlo 시뮬레이션

6. **다중 거래소 차익거래**
   - Upbit, Coinone 연동
   - 거래소 간 가격 차이 감지
   - 자동 차익거래 (원화 통제로 제한적)

### Phase 4: 운영 고도화

7. **Prometheus/Grafana 모니터링**
   - Actuator 메트릭 수집
   - 실시간 대시보드
   - 알림 규칙 설정

8. **분산 락 (다중 인스턴스 대비)**
   - 현재 단일 인스턴스 가정
   - MySQL 기반 분산 락 구현
   - 같은 마켓 중복 주문 방지

9. **자동 복구 시스템**
   - 서버 재시작 시 상태 복원
   - 미체결 주문 자동 처리
   - CircuitBreaker 상태 영속화

---

## 주의사항

1. **API 키 보안**: `.env` 파일은 절대 Git에 커밋하지 말 것
2. **실거래 전 검증**: `TRADING_ENABLED=false` 상태에서 충분히 테스트
3. **소액 시작**: 처음엔 최소 금액(1만원)으로 시작
4. **정기 점검**: 주 1회 수익률/손실 확인
5. **서킷 브레이커 모니터링**: Slack 알림 확인

---

*마지막 업데이트: 2026-01-11*
