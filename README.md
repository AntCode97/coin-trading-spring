# Coin Trading Spring

Bithumb 암호화폐 자동 트레이딩 시스템 (Spring Boot + Kotlin)

---

## 목차

1. [시스템 개요](#시스템-개요)
2. [아키텍처](#아키텍처)
3. [동작 플로우](#동작-플로우)
4. [핵심 컴포넌트](#핵심-컴포넌트)
5. [시작하기](#시작하기)
6. [설정 관리](#설정-관리)
7. [API 엔드포인트](#api-엔드포인트)
8. [트러블슈팅](#트러블슈팅)

---

## 시스템 개요

### 무엇을 하는 시스템인가?

- Bithumb 거래소에서 **암호화폐를 자동으로 매매**하는 시스템
- **1분마다** 시장 분석 → 매수/매도 신호 생성 → 주문 실행
- 시장 상황(레짐)에 따라 **자동으로 전략 전환**
- LLM(Claude/GPT/Gemini)이 **주기적으로 전략 파라미터 최적화**

### 핵심 특징

| 특징 | 설명 |
|------|------|
| 룰 기반 트레이딩 | 정해진 규칙으로 빠르게 매매 (1분 주기) |
| 레짐 적응형 | 상승장/하락장/횡보/고변동성 자동 감지 후 전략 전환 |
| 다중 안전장치 | 서킷브레이커, 시장상태 검사, 슬리피지 감시 |
| LLM 최적화 | 주 1회 AI가 성과 분석 후 파라미터 자동 조정 |
| 동적 설정 | API로 런타임에 전략/모델/파라미터 변경 가능 |

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         coin-trading-server                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     TradingEngine (1분 주기)                       │   │
│  │  ┌─────────┐    ┌─────────────┐    ┌─────────────┐               │   │
│  │  │ Bithumb │───▶│   Regime    │───▶│  Strategy   │               │   │
│  │  │   API   │    │  Detector   │    │  Selector   │               │   │
│  │  └─────────┘    └─────────────┘    └─────────────┘               │   │
│  │       │              │                    │                       │   │
│  │       │         ┌────┴────┐         ┌────┴────┐                  │   │
│  │       │         │  Simple │         │  DCA    │                  │   │
│  │       │         │   HMM   │         │  Grid   │                  │   │
│  │       │         └─────────┘         │  Mean   │                  │   │
│  │       │                             │  OBI    │                  │   │
│  │       │                             └────┬────┘                  │   │
│  │       ▼                                  ▼                       │   │
│  │  ┌─────────┐    ┌─────────────┐    ┌─────────────┐              │   │
│  │  │ Market  │◀──▶│    Risk     │◀──▶│   Order     │              │   │
│  │  │Condition│    │   Check     │    │  Executor   │              │   │
│  │  │ Checker │    └─────────────┘    └─────────────┘              │   │
│  │  └─────────┘          │                   │                      │   │
│  │                  ┌────┴────┐              ▼                      │   │
│  │                  │ Circuit │        ┌───────────┐                │   │
│  │                  │ Breaker │        │  Bithumb  │                │   │
│  │                  └─────────┘        │  주문 API │                │   │
│  │                                     └───────────┘                │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                   LlmOptimizer (매일 자정)                         │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐           │   │
│  │  │   Model     │───▶│  Optimizer  │───▶│  Strategy   │           │   │
│  │  │  Selector   │    │    Tools    │    │   Config    │           │   │
│  │  │ (Claude/    │    │ (Spring AI  │    │  (KeyValue  │           │   │
│  │  │  GPT/Gemini)│    │  Tool Call) │    │   Store)    │           │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘           │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                       저장소 (MySQL)                               │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐             │   │
│  │  │ Trades  │  │ Audit   │  │KeyValue │  │ Orders  │             │   │
│  │  │ (거래)  │  │  Logs   │  │ (설정)  │  │ (주문)  │             │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 디렉토리 구조

```
coin-trading-spring/
├── coin-trading-server/
│   ├── src/main/kotlin/com/ant/cointrading/
│   │   │
│   │   ├── engine/
│   │   │   └── TradingEngine.kt          # 메인 엔진 (1분 주기 분석)
│   │   │
│   │   ├── regime/                        # 시장 레짐 감지
│   │   │   ├── RegimeDetector.kt         # ADX/ATR 기반 (기본)
│   │   │   └── HmmRegimeDetector.kt      # HMM 기반 (고급)
│   │   │
│   │   ├── strategy/                      # 트레이딩 전략
│   │   │   ├── DcaStrategy.kt            # 분할 매수
│   │   │   ├── GridStrategy.kt           # 격자 매매
│   │   │   ├── MeanReversionStrategy.kt  # 평균 회귀
│   │   │   ├── OrderBookImbalanceStrategy.kt  # 호가 불균형
│   │   │   └── StrategySelector.kt       # 레짐별 전략 선택
│   │   │
│   │   ├── volumesurge/                   # 거래량 급등 단타 전략
│   │   │   ├── VolumeSurgeEngine.kt      # 메인 엔진
│   │   │   ├── VolumeSurgeFilter.kt      # LLM 필터 (펌프앤덤프 감지)
│   │   │   ├── VolumeSurgeAnalyzer.kt    # 기술적 분석
│   │   │   └── VolumeSurgeReflector.kt   # 학습/회고 시스템
│   │   │
│   │   ├── memescalper/                   # 밈코인 스캘핑 전략
│   │   │   └── MemeScalperEngine.kt      # 초단기 매매 엔진
│   │   │
│   │   ├── risk/                          # 리스크 관리
│   │   │   ├── RiskManager.kt            # 포지션 사이징
│   │   │   ├── CircuitBreaker.kt         # 연속 손실/에러 차단
│   │   │   └── MarketConditionChecker.kt # 스프레드/유동성 검사
│   │   │
│   │   ├── order/
│   │   │   ├── OrderExecutor.kt          # 주문 실행/체결 확인
│   │   │   └── PendingOrderManager.kt    # 미체결 주문 관리
│   │   │
│   │   ├── optimizer/                     # LLM 최적화
│   │   │   ├── LlmOptimizer.kt           # AI 최적화 엔진
│   │   │   └── OptimizerTools.kt         # LLM용 도구 정의
│   │   │
│   │   ├── service/                       # 서비스
│   │   │   ├── KeyValueService.kt        # 동적 설정 (Redis 대체)
│   │   │   └── ModelSelector.kt          # LLM 모델 선택
│   │   │
│   │   ├── api/bithumb/                   # 빗썸 API
│   │   │   ├── BithumbPublicApi.kt       # 공개 API (시세)
│   │   │   └── BithumbPrivateApi.kt      # 비공개 API (주문)
│   │   │
│   │   ├── controller/                    # REST API
│   │   │   ├── TradingController.kt      # 트레이딩 API
│   │   │   └── SettingsController.kt     # 설정 API
│   │   │
│   │   └── repository/                    # DB
│   │       ├── TradeEntity.kt            # 거래 기록
│   │       ├── KeyValueEntity.kt         # 설정 저장
│   │       └── AuditLogEntity.kt         # 감사 로그
│   │
│   └── src/test/kotlin/com/ant/cointrading/  # 테스트
│       ├── order/
│       │   └── OrderExecutorTest.kt      # 주문 실행 테스트
│       ├── volumesurge/
│       │   └── VolumeSurgeEngineTest.kt  # 거래량 급등 테스트
│       └── domain/
│           └── TradingRulesTest.kt       # 도메인 규칙 테스트
│
├── .claude/skills/                        # Claude 스킬
│   ├── quant-trading/                    # 퀀트 트레이딩 가이드
│   ├── spring-ai/                        # Spring AI 가이드
│   └── bithumb-api/                      # Bithumb API 가이드
│
├── docs/
│   └── QUANT_RESEARCH.md                  # 퀀트 연구 노트
│
├── CLAUDE.md                              # AI 에이전트용 가이드
└── README.md                              # 이 파일
```

---

## 동작 플로우

### 1. 실시간 트레이딩 (1분 주기)

```
[매 1분마다 실행]

1. 데이터 수집
   └─▶ Bithumb API에서 캔들 데이터 100개 조회
   └─▶ 현재가, 호가창 데이터 수집

2. 레짐 감지
   └─▶ RegimeDetector 또는 HmmRegimeDetector 선택 (설정에 따라)
   └─▶ 현재 시장 상태 판단:
       • BULL_TREND (상승장)
       • BEAR_TREND (하락장)
       • SIDEWAYS (횡보)
       • HIGH_VOLATILITY (고변동성)

3. 전략 선택
   └─▶ StrategySelector가 레짐에 맞는 전략 선택:
       • 상승장 → MeanReversion (평균 회귀)
       • 하락장 → DCA (분할 매수)
       • 횡보장 → Grid (격자 매매)
       • 고변동성 → 거래 중단

4. 신호 생성
   └─▶ 선택된 전략이 BUY/SELL/HOLD 신호 생성
   └─▶ 신뢰도(0~100%) 함께 반환

5. 리스크 체크
   ├─▶ CircuitBreaker: 연속 3회 손실? → 4시간 거래 중단
   ├─▶ MarketConditionChecker: 스프레드 > 0.5%? → 거래 거부
   └─▶ RiskManager: 포지션 크기 계산 (Kelly Criterion)

6. 주문 실행
   └─▶ OrderExecutor가 Bithumb API로 주문
   └─▶ 체결 확인 (3회 재시도)
   └─▶ 슬리피지 계산 및 기록

7. 결과 처리
   └─▶ 거래 내역 DB 저장
   └─▶ Slack 알림 전송
   └─▶ CircuitBreaker 상태 업데이트
```

### 2. LLM 최적화 (매일 자정)

```
[매일 자정 실행]

1. 현재 모델 선택
   └─▶ ModelSelector가 설정된 LLM 선택 (Claude/GPT/Gemini)

2. 성과 데이터 수집
   └─▶ OptimizerTools.getPerformanceSummary(30) 호출
   └─▶ 최근 30일 거래 성과 분석

3. LLM 분석
   └─▶ ChatClient에 시스템 프롬프트 + 사용자 프롬프트 전달
   └─▶ LLM이 Tool Calling으로 데이터 조회/분석

4. 파라미터 조정 (선택적)
   └─▶ LLM 판단에 따라 전략 파라미터 변경
   └─▶ 예: RSI 임계값 조정, Grid 레벨 변경

5. 결과 저장
   └─▶ AuditLog에 변경 내역 기록
   └─▶ Slack으로 최적화 결과 알림
```

### 3. 서킷 브레이커 동작

```
[트리거 조건]

연속 3회 PnL 손실        ──▶ 해당 마켓 4시간 거래 중단
연속 5회 주문 실패       ──▶ 해당 마켓 1시간 거래 중단
연속 3회 슬리피지 > 2%   ──▶ 해당 마켓 4시간 거래 중단
1분내 API 에러 10회      ──▶ 전체 마켓 24시간 거래 중단
총 자산 10% 감소         ──▶ 전체 마켓 24시간 거래 중단
```

---

## 핵심 컴포넌트

### TradingEngine

메인 트레이딩 엔진. 1분마다 모든 마켓을 분석하고 주문을 실행.

**위치:** `engine/TradingEngine.kt`

**확인할 때:**
- 전체 트레이딩 로직 이해가 필요할 때
- 마켓 분석 주기를 변경하고 싶을 때

### RegimeDetector vs HmmRegimeDetector

| 구분 | RegimeDetector | HmmRegimeDetector |
|------|---------------|-------------------|
| 방식 | ADX/ATR 기반 규칙 | Hidden Markov Model |
| 장점 | 단순, 빠름 | 과거 패턴 기반 정교한 감지 |
| 단점 | 노이즈에 민감 | 계산량 많음 |
| 기본값 | O | X (설정으로 전환) |

**전환 방법:**
```bash
curl -X POST http://localhost:8080/api/settings/regime \
  -H "Content-Type: application/json" \
  -d '{"type": "hmm"}'
```

### 전략 (Strategy)

| 전략 | 적용 레짐 | 동작 방식 |
|------|----------|----------|
| DCA | 하락장 | 일정 간격으로 분할 매수 |
| Grid | 횡보장 | 가격대별 격자 주문 |
| MeanReversion | 상승장 | RSI 과매도 시 매수, 과매수 시 매도 |
| OrderBookImbalance | 전체 | 호가 불균형 감지 시 단타 |
| VolumeSurge | 전체 | 거래량 급등 감지 → LLM 필터 → 단타 |
| MemeScalper | 전체 | 밈코인 급등 감지 → 초단기 스캘핑 |

**위치:** `strategy/` 디렉토리

### CircuitBreaker

연속 손실이나 시스템 오류 발생 시 자동으로 거래를 중단하는 안전장치.

**위치:** `risk/CircuitBreaker.kt`

**상태 확인:**
```bash
curl http://localhost:8080/api/trading/circuit-breaker/status
```

### KeyValueService

MySQL 기반 키-값 저장소. Redis 없이 동적 설정 관리.

**위치:** `service/KeyValueService.kt`

**주요 설정 키:**

| 키 | 기본값 | 설명 |
|----|--------|------|
| `trading.enabled` | false | 실거래 활성화 |
| `llm.enabled` | false | LLM 최적화 활성화 |
| `llm.model.provider` | anthropic | LLM 제공자 |
| `regime.detector.type` | simple | 레짐 감지기 (simple/hmm) |

---

## 시작하기

### 사전 요구사항

- JDK 25
- MySQL 8.x
- Bithumb API 키 (Access Key + Secret Key)
- (선택) LLM API 키 (Anthropic/OpenAI/Google)

### 설치

```bash
# 1. 저장소 클론
git clone https://github.com/your-repo/coin-trading-spring.git
cd coin-trading-spring

# 2. 환경 변수 설정
cp .env.example .env
# .env 파일 편집하여 API 키 입력

# 3. 데이터베이스 생성
mysql -u root -p -e "CREATE DATABASE coin_trading CHARACTER SET utf8mb4;"

# 4. 빌드
./gradlew :coin-trading-server:build -x test

# 5. 실행
./gradlew :coin-trading-server:bootRun
```

### 환경 변수 (.env)

```bash
# 필수 - Bithumb
BITHUMB_ACCESS_KEY=your_access_key
BITHUMB_SECRET_KEY=your_secret_key

# 필수 - MySQL
MYSQL_URL=jdbc:mysql://localhost:3306/coin_trading
MYSQL_USER=root
MYSQL_PASSWORD=your_password

# 선택 - 트레이딩
TRADING_ENABLED=false          # true로 변경 시 실거래 시작
ORDER_AMOUNT_KRW=10000         # 1회 주문 금액 (원)
STRATEGY_TYPE=MEAN_REVERSION   # 기본 전략

# 선택 - LLM (사용 시)
SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...
SPRING_AI_OPENAI_API_KEY=sk-...
SPRING_AI_GOOGLE_API_KEY=...
LLM_OPTIMIZER_ENABLED=false

# 선택 - Slack 알림
SLACK_WEBHOOK_URL=https://hooks.slack.com/...
```

### 처음 실행 체크리스트

1. **서버 상태 확인**
   ```bash
   curl http://localhost:8080/actuator/health
   # {"status":"UP"} 확인
   ```

2. **설정 상태 확인**
   ```bash
   curl http://localhost:8080/api/settings
   # 현재 설정 목록 확인
   ```

3. **마켓 분석 테스트** (거래 없이 분석만)
   ```bash
   curl http://localhost:8080/api/trading/analyze/BTC_KRW
   # 레짐, 전략, 신호 확인
   ```

4. **실거래 활성화** (주의!)
   ```bash
   curl -X POST http://localhost:8080/api/settings \
     -H "Content-Type: application/json" \
     -d '{"key": "trading.enabled", "value": "true"}'
   ```

---

## 설정 관리

모든 설정은 REST API로 런타임에 변경 가능.

### 전체 설정 조회

```bash
curl http://localhost:8080/api/settings
```

### 특정 설정 조회/변경

```bash
# 조회
curl http://localhost:8080/api/settings/trading.enabled

# 변경
curl -X POST http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"key": "trading.enabled", "value": "true"}'
```

### LLM 모델 변경

```bash
# 현재 모델 상태
curl http://localhost:8080/api/settings/model

# Claude → GPT로 변경
curl -X POST http://localhost:8080/api/settings/model \
  -H "Content-Type: application/json" \
  -d '{"provider": "openai"}'

# 특정 모델 지정
curl -X POST http://localhost:8080/api/settings/model \
  -H "Content-Type: application/json" \
  -d '{"provider": "anthropic", "modelName": "claude-sonnet-4-20250514"}'
```

### 레짐 감지기 변경

```bash
# HMM 감지기로 변경
curl -X POST http://localhost:8080/api/settings/regime \
  -H "Content-Type: application/json" \
  -d '{"type": "hmm"}'

# 기본(Simple) 감지기로 복귀
curl -X POST http://localhost:8080/api/settings/regime \
  -H "Content-Type: application/json" \
  -d '{"type": "simple"}'
```

---

## API 엔드포인트

### 트레이딩

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/trading/status` | 전체 마켓 상태 |
| GET | `/api/trading/analyze/{market}` | 특정 마켓 분석 |
| GET | `/api/trading/circuit-breaker/status` | 서킷브레이커 상태 |
| POST | `/api/trading/trigger/{market}` | 수동 분석 트리거 |

### 설정

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/settings` | 전체 설정 조회 |
| GET | `/api/settings/{key}` | 특정 설정 조회 |
| POST | `/api/settings` | 설정 변경 |
| GET | `/api/settings/model` | LLM 모델 상태 |
| POST | `/api/settings/model` | LLM 모델 변경 |
| GET | `/api/settings/regime` | 레짐 감지기 상태 |
| POST | `/api/settings/regime` | 레짐 감지기 변경 |

### 시스템

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/actuator/health` | 헬스 체크 |
| POST | `/mcp` | MCP 도구 호출 (LLM용) |

---

## 트러블슈팅

### 거래가 실행되지 않음

1. **trading.enabled 확인**
   ```bash
   curl http://localhost:8080/api/settings/trading.enabled
   # "false"이면 거래 비활성화 상태
   ```

2. **서킷브레이커 확인**
   ```bash
   curl http://localhost:8080/api/trading/circuit-breaker/status
   # 발동된 마켓 확인
   ```

3. **시장 상태 확인**
   ```bash
   curl http://localhost:8080/api/trading/analyze/BTC_KRW
   # regime이 HIGH_VOLATILITY면 거래 중단 상태
   ```

### LLM 최적화가 실행되지 않음

1. **llm.enabled 확인**
   ```bash
   curl http://localhost:8080/api/settings/llm.enabled
   ```

2. **API 키 확인**
   ```bash
   curl http://localhost:8080/api/settings/model
   # isModelAvailable: true 확인
   ```

### API 에러 발생

1. **Bithumb API 상태 확인**
   - 공식 상태 페이지 확인
   - API 키 유효성 확인

2. **에러 로그 확인**
   ```bash
   tail -f logs/application.log | grep ERROR
   ```

### 슬리피지가 높음

1. **스프레드 확인**
   ```bash
   curl http://localhost:8080/api/trading/analyze/BTC_KRW
   # spread 값 확인 (0.5% 이상이면 위험)
   ```

2. **주문 금액 조정**
   - ORDER_AMOUNT_KRW 값을 줄여서 유동성 영향 최소화

---

## 기술 스택

| 구분 | 기술 | 버전 |
|------|------|------|
| Language | Kotlin | 2.3.0 |
| JDK | OpenJDK | 25 |
| Framework | Spring Boot | 3.5.9 |
| AI | Spring AI | 1.1.2 |
| Database | MySQL | 8.x |
| Build | Gradle | 9.2.1 |
| Exchange | Bithumb | Private/Public API |
| Test | JUnit 5, Mockito | Latest |

---

## 관련 문서

- [CLAUDE.md](CLAUDE.md) - AI 에이전트용 상세 기술 가이드
- [docs/QUANT_RESEARCH.md](docs/QUANT_RESEARCH.md) - 퀀트 전략 연구 노트

---

## 라이선스

MIT License
