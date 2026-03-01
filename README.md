# Coin Trading Spring

Bithumb 암호화폐 자동 트레이딩 + 데스크톱 수동/오토파일럿 트레이딩 시스템 (Spring Boot + Kotlin + Electron)

---

## 목차

1. [시스템 개요](#시스템-개요)
2. [아키텍처](#아키텍처)
3. [동작 플로우](#동작-플로우)
4. [핵심 컴포넌트](#핵심-컴포넌트)
5. [시작하기](#시작하기)
6. [설정 관리](#설정-관리)
7. [API 엔드포인트](#api-엔드포인트)
8. [최근 개선사항](#최근-개선사항)
9. [트러블슈팅](#트러블슈팅)

---

## 시스템 개요

### 무엇을 하는 시스템인가?

- Bithumb 거래소에서 **암호화폐를 자동으로 매매**하는 시스템
- **1분마다** 시장 분석 → 매수/매도 신호 생성 → 주문 실행
- 시장 상황(레짐)에 따라 **자동으로 전략 전환**
- LLM(Claude/GPT/Gemini)이 **주기적으로 전략 파라미터 최적화**
- 데스크톱 워크스페이스에서 **Guided 수동 트레이딩**(추천가/손절/익절/승률 기반) 지원
- 오토파일럿 모드에서 **후보 선별 → LLM 검토 → Playwright UI 검증 → 진입/모니터링** 자동 수행

### 핵심 특징

| 특징 | 설명 |
|------|------|
| 룰 기반 트레이딩 | 정해진 규칙으로 빠르게 매매 (1분 주기) |
| 레짐 적응형 | 상승장/하락장/횡보/고변동성 자동 감지 후 전략 전환 |
| 다중 안전장치 | 서킷브레이커, 시장상태 검사, 슬리피지 감시 |
| LLM 최적화 | 주 1회 AI가 성과 분석 후 파라미터 자동 조정 |
| 동적 설정 | API로 런타임에 전략/모델/파라미터 변경 가능 |
| Guided 수동 트레이딩 | 추천 진입가/현재가 승률 기반 정렬, 차트/호가/주문 스냅샷 제공 |
| 오토파일럿 라이브 도크 | 실시간 후보/클릭/주문/워커 상태를 타임라인과 퍼널 KPI로 가시화 |
| Playwright MCP 연동 | Electron CDP에 붙어 실제 앱 UI 스냅샷/클릭 증거를 이벤트로 기록 |

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
│  │       │                             │  VolSur │                  │   │
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
│   │   │   ├── VolatilitySurvivalStrategy.kt  # 급락/급등장 생존형 반등 전략
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
       • 레짐 신뢰도 < 50% → Grid (보수 운용)
       • 상승장 (BULL) → Breakout (추세 돌파)
       • 하락장 (BEAR, ATR < 2.0%) → DCA (분할 매수)
       • 하락장 (BEAR, ATR >= 2.0%) → VolatilitySurvival (패닉 반등)
       • 횡보장 (SIDEWAYS, ATR < 2.0%) → Grid (격자 매매)
       • 횡보장 (SIDEWAYS, ATR >= 2.0%) → Breakout (변동성 돌파)
       • 고변동성 (HIGH_VOLATILITY) → VolatilitySurvival

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
| DCA | 하락장(저~중변동) | 일정 간격으로 분할 매수 |
| Grid | 횡보장 / 레짐 신뢰도 낮음 | 가격대별 격자 주문 |
| Breakout | 상승장 / 고변동 횡보장 | 추세 돌파 시 진입 |
| VolatilitySurvival | 고변동성 / 급락형 하락장 | 패닉 하락 후 반등만 선별 진입, 짧은 손절·빠른 익절 |
| MeanReversion | 수동 운영 / 백테스트 | RSI 과매도 시 매수, 과매수 시 매도 |
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
   curl -X POST http://localhost:8080/api/trading/analyze/BTC_KRW
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
| POST | `/api/trading/analyze/{market}` | 특정 마켓 수동 분석 |
| GET | `/api/trading/strategies` | 사용 가능한 전략 목록 |
| GET | `/api/trading/strategy/config` | 현재 전략 설정 |

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

### 백테스트

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/backtest/mean-reversion/{market}` | 평균 회귀 전략 백테스트 |
| GET | `/api/backtest/volatility-survival/{market}` | 고변동성 생존 전략 백테스트 |

### 시스템

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/actuator/health` | 헬스 체크 |
| POST | `/mcp` | MCP 도구 호출 (LLM용) |

### Guided Trading / Desktop

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/guided-trading/markets` | 마켓 보드 조회 (승률 정렬 지원) |
| GET | `/api/guided-trading/chart` | 차트/포지션/이벤트/호가 스냅샷 조회 |
| GET | `/api/guided-trading/recommendation` | 추천 진입/손절/익절/승률 조회 |
| POST | `/api/guided-trading/start` | Guided 진입 시작 |
| POST | `/api/guided-trading/stop` | Guided 포지션 정지/청산 |
| POST | `/api/guided-trading/partial-take-profit` | Guided 부분 익절 |
| POST | `/api/guided-trading/positions/adopt` | MCP 직접 주문 포지션 편입 |
| GET | `/api/guided-trading/autopilot/live` | 오토파일럿 라이브 도크 데이터(주문 퍼널/이벤트/후보, `strategyCodePrefix` 필터 지원) |
| GET | `/api/guided-trading/autopilot/opportunities` | 오토파일럿 기회 스코어/기대값 후보 조회 |
| POST | `/api/guided-trading/autopilot/decisions` | 클라이언트 Fine-Grained Agent 의사결정 로그 저장 |
| GET | `/api/guided-trading/autopilot/performance` | 오토파일럿 7/30일 성과 및 증액 게이트 상태 조회 (`strategyCodePrefix` 필터 지원) |

`/api/guided-trading/autopilot/live` 쿼리 파라미터:
- `thresholdMode`: `DYNAMIC_P70` 또는 `FIXED`
- `minMarketWinRate`: 고정 모드(`FIXED`)에서 후보 선별용 최소 **현재가 승률**(%)
- `minRecommendedWinRate`: 하위 호환 파라미터(신규 호출은 `minMarketWinRate` 권장)
- `strategyCodePrefix`: 전략 코드 prefix 필터 (예: `GUIDED_AUTOPILOT_SCALP`)

`/api/guided-trading/autopilot/performance` 쿼리 파라미터:
- `windowDays`: 조회 기간(기본 30일)
- `strategyCodePrefix`: 전략 코드 prefix 필터 (예: `GUIDED_AUTOPILOT_SWING`)

`/api/guided-trading/autopilot/opportunities` 쿼리 파라미터:
- `interval`: 기본 `minute1` (진입 축)
- `confirmInterval`: 기본 `minute10` (확인 축)
- `mode`: 기본 `SCALP`
- `universeLimit`: 기본 `15`

`/api/guided-trading/autopilot/opportunities` 응답 핵심 필드:
- `generatedAt`, `primaryInterval`, `confirmInterval`, `mode`, `appliedUniverseLimit`
- `opportunities[]`: `market`, `koreanName`, `recommendedEntryWinRate1m/10m`, `marketEntryWinRate1m/10m`, `riskReward1m`, `entryGapPct1m`, `expectancyPct`, `score`, `stage`, `reason`
- `stage`: `AUTO_PASS` | `BORDERLINE` | `RULE_FAIL`

`/api/guided-trading/agent/context` 응답 확장:
- `featurePack`: `technical`, `microstructure`, `executionRisk`를 포함한 Fine-Grained 입력 세트

`GuidedTradeView`/`GuidedTradePosition` 확장:
- `entrySource`: 진입 출처(`MANUAL`, `AUTOPILOT`, `MCP_DIRECT` 등)
- `strategyCode`: 엔진 분리용 전략 코드(`GUIDED_AUTOPILOT_SCALP/SWING/POSITION` 등)

---

## 최근 개선사항

### LLM 토큰 절감형 오토파일럿 재설계 (2026-03-02)

1. LLM 예산 기준을 호출 횟수에서 토큰으로 전환하고, KST 자정 리셋 기반 전역 거버너를 도입했습니다.
2. 일일 총 상한 `200,000 tokens`를 `Entry 160,000 + Risk Reserve 40,000`으로 분리하고, Entry는 엔진별 `64k/56k/40k`(SCALP/SWING/POSITION)로 고정 배분합니다.
3. 예산 초과 시 오토파일럿 루프를 멈추지 않고 `Quant-only fallback`으로 전환해 `BORDERLINE` LLM 심사를 중단한 채 지속 운영합니다.
4. LLM 호출 전 Quant 게이트를 선행 적용해 저품질 후보를 `QUANT_FILTERED`로 탈락시키고, tick당 엔진별 LLM 진입 심사를 1건으로 제한합니다.
5. FineAgent 기본 범위를 `INVEST(SWING/POSITION)`로 축소하고 기본 모드를 `LITE`로 고정해 후보당 LLM 호출을 `SYNTH+PM` 2회 중심으로 절감했습니다.
6. 진입 실패 쿨다운 기본값을 모든 실패 경로에서 `5분(300초)`으로 통일하고, LLM 제안 재시도/재검토 값도 `300~3600초` 범위로 보정합니다.
7. 포지션 리뷰 응답에 `nextReviewSec`를 반영해 동적 재분석 스케줄을 적용하고, 급락/긴급 청산 룰은 LLM 스케줄과 무관하게 즉시 실행합니다.
8. 라이브 도크에 토큰 사용량/잔여량, reserve 사용량, fallback 상태, `TOKEN_BUDGET_SKIP`·`QUANT_FILTERED`·`RECHECK_SCHEDULED` 카운트를 추가했습니다.

### 멀티 엔진 분리형 데스크 업그레이드 (2026-02-28)

1. 오토파일럿 엔진을 `SCALP`, `SWING`, `POSITION` 3개로 분리하고, 데스크 하단 모니터를 `초단타 시스템`/`투자 시스템` 2개 탭으로 분리했습니다.
2. SCALP는 기존 유동성 상위 유니버스를 유지하고, SWING/POSITION은 메이저 코인(`KRW-BTC/ETH/SOL/XRP/ADA`) allowlist로 고정했습니다.
3. 엔진 예산을 `40/35/25`(SCALP/SWING/POSITION) 고정 비율로 분할하고, 엔진별 `strategyCode` 기준 노출(평균진입가 x 잔량)로 진입 예산 가드를 적용했습니다.
4. `AutopilotOrchestrator`는 엔진별 `strategyCode/strategyCodePrefix`를 주입해 포지션/이벤트/집계를 분리하고, 한 엔진의 차단/실패가 다른 엔진을 중단시키지 않도록 격리했습니다.
5. 신규 투자 전용 도크(`InvestmentLiveDock`)를 추가해 SWING/POSITION 상태 카드, 통합 타임라인, 통합 주문 피드를 동시에 모니터링할 수 있습니다.

### 오토파일럿 Expectancy 중심 기회 포착 강화 (2026-02-27)

1. 신규 API `/api/guided-trading/autopilot/opportunities`를 추가해 거래대금 상위 15종목을 `minute1`(진입) + `minute10`(확인)으로 평가합니다.
2. 후보 평가는 승률 단일 지표 대신 `expectancyPct`와 `score`를 기준으로 고정했으며 stage를 `AUTO_PASS/BORDERLINE/RULE_FAIL`로 분류합니다.
3. 오토파일럿은 `AUTO_PASS`만 즉시 워커 진입, `BORDERLINE`만 LLM 진입 심사를 수행합니다.
4. 포지션 LLM 평가는 주기 호출을 제거하고 이벤트 트리거(`손실 확대`, `수익 구간`, `피크 대비 되돌림`)에서만 실행합니다.
5. 기본 운영값을 `일손실 -30,000원`, `동시 포지션 6`, `pendingEntryTimeout 45초`로 상향 조정했습니다.
6. LLM 일 240회는 차단 없이 소프트 경고만 노출하며, `entrySource/strategyCode`를 포지션 엔티티에 영속화합니다.
7. 선택 코인 단타 루프를 추가해 사용자가 지정한 코인(최대 8개)을 전역 후보와 분리해 독립적으로 반복 진입/청산할 수 있습니다.
8. 선택 코인은 전역 오토파일럿 후보 선별에서 자동 제외되며, 리스크 한도(`maxConcurrentPositions`)는 전역/선택 루프가 공유합니다.
9. 선택 코인 포지션은 보유시간 기준 `90분 경고 + 120분 강제청산` 규칙으로 2시간 내 정리를 강제합니다.

### Fine-Grained Multi-Agent 판단 파이프라인 (2026-02-27)

1. 클라이언트 오케스트레이터에 `Technical / Microstructure / ExecutionRisk / Synth / PM` 계층형 Agent 파이프라인을 추가했습니다.
2. `agent/context`에 `featurePack`을 제공해 프롬프트 입력을 고정 구조화했습니다.
3. 각 후보의 Agent 판단 결과를 `/api/guided-trading/autopilot/decisions`로 저장해 리플레이/감사 추적이 가능해졌습니다.
4. `/api/guided-trading/autopilot/performance`에서 30일 `Sharpe / MaxDD / WinRate / 순손익` 기반 증액 게이트를 제공합니다.

### Meme Scalper 진입 Imbalance 유지 필터 추가 (2026-02-25)

1. 진입 신호 직후 `1~2분` 구간의 Imbalance 평균 유지 여부를 검증하는 필터를 추가했습니다.
2. `entryImbalance - currentImbalance >= 0.2` 급감 또는 평균 Imbalance `< 0.5`이면 진입을 취소합니다.
3. 설정 키 추가: `entry-imbalance-persistence-enabled/min/drop-threshold/lookback-candles`.

### 오토파일럿 UX 2차 + 주문 생명주기 텔레메트리 (2026-02-24)

1. 하단 전체폭 `오토파일럿 라이브 도크` 추가: 후보 상위 10개, 탈락/통과 사유, 워커 상태, 실시간 타임라인을 동시에 표시합니다.
2. Playwright `browser_*` 액션 가시화 강화: 액션 로그와 스냅샷 증거를 이벤트로 기록하고, 실패 시에도 오토파일럿은 계속 진행합니다.
3. 주문 퍼널 집계 추가: `매수 요청 → 매수 체결 → 매도 요청 → 매도 체결` 카운터를 KST 기준 당일(00:00~현재)로 제공합니다.
4. 전략 그룹 분리 집계 추가: `MANUAL`, `GUIDED`, `AUTOPILOT_MCP`, `CORE_ENGINE` 별 카운트/이벤트를 분리 제공합니다.
5. 신규 API 추가: `/api/guided-trading/autopilot/live`로 도크 렌더링에 필요한 `orderSummary/orderEvents/candidates`를 제공합니다.

### 서버 전략 개선 (고변동성 대응)

1. `VOLATILITY_SURVIVAL` 전략 추가: 패닉 급락 후 반등 구간만 선별 진입하고, 손절/익절/트레일링/타임아웃/손절 후 쿨다운을 내장했습니다.
2. `StrategySelector` 레짐 매핑 강화: `HIGH_VOLATILITY`와 `ATR >= 2.0%` 베어 구간에서 `VOLATILITY_SURVIVAL`을 자동 선택하도록 반영했습니다.
3. 백테스트 API 확장: `/api/backtest/mean-reversion/{market}`, `/api/backtest/volatility-survival/{market}` 엔드포인트를 추가했습니다.
4. 회귀 방지 테스트 추가: `VolatilitySurvivalStrategyTest`로 급락-반등(whipsaw) 시나리오를 검증하도록 보강했습니다.

### 프론트 대시보드 개선

1. 인라인 확인 모달/토스트 피드백 도입: 위험 액션(수동 매도, 동기화, 자동거래 토글 등)에 확인 단계를 추가했습니다.
2. 포지션 탐색성 강화: 마켓/전략 검색, 손익/위험도 필터, 정렬(위험도/손익/규모) 기능을 추가했습니다.
3. 리스크 오버뷰 카드 추가: 고위험 포지션 수, 손절선 이탈 건수, 손실 노출 금액을 상단에서 즉시 확인할 수 있습니다.
4. 동기화 결과 리포트 추가: 최근 잔고 동기화에서 수정된 포지션과 사유를 요약 표시합니다.
5. 펀딩 차익거래 운영 UX 개선: 자동 ON/OFF 전환과 기회 스캔 흐름에 확인/상태 반영을 강화했습니다.

---

## 트러블슈팅

### 거래가 실행되지 않음

1. **trading.enabled 확인**
   ```bash
   curl http://localhost:8080/api/settings/trading.enabled
   # "false"이면 거래 비활성화 상태
   ```

2. **현재 마켓 상태 확인**
   ```bash
   curl http://localhost:8080/api/trading/status
   # strategy, lastSignal.reason, dailyPnlPercent 확인
   ```

3. **시장 상태 확인**
   ```bash
   curl -X POST http://localhost:8080/api/trading/analyze/BTC_KRW
   # strategy/reason을 확인해 HOLD 사유(손절 쿨다운, 진입 조건 미달 등) 파악
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
   curl -X POST http://localhost:8080/api/trading/analyze/BTC_KRW
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
