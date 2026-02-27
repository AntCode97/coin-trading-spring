# Coin Trading Spring - AI Agent 가이드

## 프로젝트 개요

Bithumb 암호화폐 거래소를 위한 **Spring Boot** 기반 자동화 트레이딩 시스템.
룰 기반 실시간 트레이딩 + LLM 주기적 최적화 하이브리드 아키텍처.
추가로 Electron 기반 데스크톱 클라이언트에서 Guided 수동 트레이딩과 오토파일럿 모드를 지원.

---

## 아키텍처

```
[coin-trading-server - 단일 서비스]
│
├── 실시간 트레이딩 (룰 기반)
│   ├── DCA (Dollar Cost Averaging)  - 상태 DB 저장
│   ├── Grid Trading                 - 상태 DB 저장
│   ├── Mean Reversion
│   └── Order Book Imbalance (초단기 전략)
│
├── 리스크 관리
│   ├── CircuitBreaker       - 연속 손실/실행 실패/슬리피지 감시
│   ├── MarketConditionChecker - 스프레드/유동성/변동성 검사
│   ├── OrderExecutor        - 부분체결/슬리피지 검증
│   └── RegimeDelayLogic     - 잦은 전략 변경 방지
│
├── 동적 설정 (KeyValue Store)
│   ├── MySQL 기반 키-값 저장소
│   ├── 메모리 캐시 (읽기 성능)
│   ├── 전략 상태 저장 (DCA, Grid)
│   └── REST API로 런타임 변경
│
├── MCP Server (Claude Code 연동)
│   ├── MarketDataTools       - 시장 데이터 조회
│   ├── TechnicalAnalysisTools - RSI, MACD, 볼린저밴드
│   ├── TradingTools          - 주문/잔고
│   ├── StrategyTools         - 전략 파라미터 조정
│   └── PerformanceTools      - 성과 분석 (data class 응답)
│
├── Guided Trading (Desktop API)
│   ├── GuidedTradingService  - 추천 진입/승률/포지션 관리
│   ├── GuidedTradingController - 데스크톱 Guided REST API
│   ├── OrderLifecycleTelemetryService - 주문 생명주기 집계
│   └── ManualTradingController - 수동 주문 API (텔레메트리 연동)
│
├── LLM Optimizer (Spring AI)
│   ├── LlmConfig            - ChatClient 빈 등록
│   ├── ModelSelector        - 동적 모델 선택 (@Qualifier)
│   └── Tool Calling         - LLM이 직접 함수 호출
│
└── 알림 시스템
    └── SlackNotifier        - Bot API (chat.postMessage)

[coin-trading-client - Electron + React]
│
├── ManualTraderWorkspace     - Guided 수동 트레이딩 UI
├── AutopilotOrchestrator     - 오토파일럿 의사결정/워커 오케스트레이션
├── AutopilotLiveDock         - 실시간 후보/액션/주문 퍼널 도크
└── electron/mcp              - MCP 허브 + Playwright MCP 프로세스 관리
```

---

## 기술 스택

| 항목 | 버전 |
|------|------|
| JDK | 25 |
| Kotlin | 2.3.0 |
| Spring Boot | 3.5.9 |
| Spring AI | 1.1.2 (Anthropic + OpenAI) |
| Gradle | 9.2.1 (Kotlin DSL) |
| MySQL | 8.x |

---

## 모듈 구조

```
coin-trading-spring/
├── coin-trading-server/
│   └── src/main/kotlin/com/ant/cointrading/
│       ├── CoinTradingApplication.kt
│       ├── api/bithumb/
│       │   ├── BithumbModels.kt
│       │   ├── BithumbPublicApi.kt
│       │   └── BithumbPrivateApi.kt
│       ├── config/
│       │   ├── BithumbProperties.kt
│       │   ├── TradingProperties.kt
│       │   ├── LlmProperties.kt
│       │   └── LlmConfig.kt            # ChatClient 빈 등록
│       ├── controller/
│       │   ├── SettingsController.kt
│       │   ├── TradingController.kt
│       │   ├── OptimizerController.kt  # LLM 수동 최적화 API
│       │   ├── GuidedTradingController.kt
│       │   └── ManualTradingController.kt
│       ├── guided/
│       │   └── GuidedTradingService.kt
│       ├── service/
│       │   ├── KeyValueService.kt
│       │   ├── ModelSelector.kt        # @Qualifier 기반 모델 선택
│       │   └── OrderLifecycleTelemetryService.kt
│       ├── strategy/
│       │   ├── DcaStrategy.kt          # 상태 DB 저장 (@PostConstruct 복원)
│       │   ├── GridStrategy.kt         # 상태 DB 저장 (JSON 직렬화)
│       │   ├── MeanReversionStrategy.kt
│       │   └── OrderBookImbalanceStrategy.kt
│       ├── mcp/tool/                   # MCP Server Tools
│       │   ├── MarketDataTools.kt      # @McpTool 어노테이션
│       │   ├── TechnicalAnalysisTools.kt
│       │   ├── TradingTools.kt
│       │   ├── StrategyTools.kt
│       │   └── PerformanceTools.kt     # data class 응답
│       ├── notification/
│       │   └── SlackNotifier.kt        # Bot API 방식
│       └── ...
│
├── coin-trading-client/
│   ├── src/components/ManualTraderWorkspace.tsx
│   ├── src/components/autopilot/AutopilotLiveDock.tsx
│   ├── src/lib/autopilot/AutopilotOrchestrator.ts
│   └── electron/mcp/
│       ├── mcp-hub.cjs
│       └── playwright-manager.cjs
│
├── http/                               # HTTP Client 테스트 파일
├── docs/
│   ├── QUANT_RESEARCH.md              # 퀀트 연구 노트
│   └── add-table-comments.sql         # 테이블 주석 SQL
├── docker-compose.yml                  # 로컬 개발용
├── docker-compose.nas.yml              # NAS 배포용 (TZ=Asia/Seoul)
├── docker-build-push.sh                # Docker Hub 빌드/푸시
├── .mcp.json                           # Claude Code MCP 연결 설정
└── CLAUDE.md
```

---

## 최근 변경사항 (2026-02-25)

## 최근 변경사항 (2026-02-27)

### 오토파일럿 기회 포착/수익 빈도 강화 (Expectancy 중심)

- 신규 API `GET /api/guided-trading/autopilot/opportunities` 추가.
  - 기본 축: `minute1`(진입) + `minute10`(확인)
  - 기본 유니버스: 거래대금 상위 `15`
  - 응답 후보 필드: `expectancyPct`, `score`, `riskReward1m`, `entryGapPct1m`, `stage`, `reason`
- stage 규칙 고정:
  - `AUTO_PASS`: `score >= 64` and `expectancyPct >= 0.12`
  - `BORDERLINE`: `score >= 56` and `expectancyPct >= 0.02`
  - `RULE_FAIL`: 위 기준 미달 또는 RR/괴리 규칙 위반
- 오케스트레이터 정책 변경:
  - `AUTO_PASS` 즉시 워커 생성
  - `BORDERLINE`만 LLM 진입 심사
  - LLM 일 240회는 차단 없이 소프트 경고만 노출
- 워커 LLM 호출 정책 변경:
  - 주기적 포지션 리뷰 제거
  - 이벤트 트리거(`pnl <= -0.6%`, `pnl >= +1.6%`, trailing 이후 피크 대비 `-0.7%` 되돌림)에서만 호출
- 기본 운영값 변경:
  - `dailyLossLimitKrw=-30000`
  - `maxConcurrentPositions=6`
  - `pendingEntryTimeoutSec=45`
- `GuidedTradeEntity`에 `entrySource`, `strategyCode` 컬럼 저장 반영.

---

## 최근 변경사항 (2026-02-25)

### Meme Scalper 진입 Imbalance 유지 검증 필터

- `MemeScalperDetector.validateEntryImbalancePersistence()` 추가.
- 진입 직전 1~2분(1분봉 기준) Imbalance 평균이 기준 미달이면 진입 취소:
  - `averageImbalance < entry-imbalance-persistence-min(기본 0.5)`
- 진입 신호 대비 Imbalance 급감 시 진입 취소:
  - `entryImbalance - currentImbalance >= entry-imbalance-drop-threshold(기본 0.2)`
- 신규 설정 키:
  - `memescalper.entry-imbalance-persistence-enabled`
  - `memescalper.entry-imbalance-persistence-min`
  - `memescalper.entry-imbalance-drop-threshold`
  - `memescalper.entry-imbalance-lookback-candles`

---

## 최근 변경사항 (2026-02-24)

### 1. 오토파일럿 라이브 도크 UX 추가 (Desktop)

- 토글 ON 시 하단 전체폭 라이브 도크를 자동 오픈.
- 실시간 노출 항목:
  - 후보 코인 상위 + stage(`AUTO_PASS`, `BORDERLINE`, `RULE_FAIL`, `SLOT_FULL`, `COOLDOWN`, `LLM_REJECT`, `PLAYWRIGHT_WARN`, `ENTERED`)
  - Playwright/LLM/워커/주문 타임라인
  - 워커 상태 카드
  - 주문 퍼널 KPI (`매수 요청 → 매수 체결 → 매도 요청 → 매도 체결`)

관련 파일:
- `coin-trading-client/src/components/autopilot/AutopilotLiveDock.tsx`
- `coin-trading-client/src/lib/autopilot/AutopilotOrchestrator.ts`
- `coin-trading-client/src/lib/autopilot/MarketWorker.ts`

### 2. 주문 생명주기 텔레메트리 추가 (Server)

- 신규 엔티티/서비스:
  - `OrderLifecycleEventEntity` (eventType, strategyGroup, orderId, market, side, createdAt)
  - `OrderLifecycleTelemetryService` (집계 + reconcile)
- 시간 기준: **KST 오늘 00:00 ~ 현재**
- 전략 그룹 분리:
  - `MANUAL`, `GUIDED`, `AUTOPILOT_MCP`, `CORE_ENGINE`
- 체결 idempotent 규칙:
  - 동일 `orderId + BUY_FILLED/SELL_FILLED` 이벤트는 1회만 기록

관련 파일:
- `coin-trading-server/src/main/kotlin/com/ant/cointrading/repository/OrderLifecycleEventEntity.kt`
- `coin-trading-server/src/main/kotlin/com/ant/cointrading/service/OrderLifecycleTelemetryService.kt`

### 3. 신규 Guided API

- `GET /api/guided-trading/autopilot/live`
  - 응답: `orderSummary`, `orderEvents`, `autopilotEvents`, `candidates`
  - 쿼리: `thresholdMode`, `minMarketWinRate`(고정 모드 현재가 승률 기준), `minRecommendedWinRate`(하위 호환)
- `GET /api/guided-trading/autopilot/opportunities`
  - 응답: `generatedAt`, `primaryInterval`, `confirmInterval`, `mode`, `appliedUniverseLimit`, `opportunities[]`
  - 후보 stage: `AUTO_PASS` | `BORDERLINE` | `RULE_FAIL`
- `GuidedTradingService.getAutopilotLive(interval, mode, thresholdMode, minMarketWinRate)` 추가.
- `GuidedTradingService.getAutopilotOpportunities(interval, confirmInterval, mode, universeLimit)` 추가.

### 4. 텔레메트리 기록 포인트 확장

- `ManualTradingController`: 수동 매수/매도 요청 및 즉시 reconcile
- `GuidedTradingService`: 진입/부분익절/전량청산/취소/재조정 경로에서 요청/체결/실패 기록
- `TradingTools`: MCP 직접 주문(`AUTOPILOT_MCP`) 요청/체결/취소/실패 기록

### 5. 테스트

- `OrderLifecycleTelemetryServiceTest` 추가
- `GuidedTradingControllerTest` 확장 (`autopilot/live` 응답 검증)
- `GuidedTradingServiceTest` 생성자 의존성 반영

---

## 최근 변경사항 (2026-01-16)

### 퀀트 최적화: 매매 빈도 개선

**문제 진단:**
- 30일간 총 5건 거래 (DCA 3건, GRID 2건)
- MEAN_REVERSION: 0건 (활성 전략인데 거래 없음)
- VOLUME_SURGE: 0건 (경보는 감지되지만 진입 못함)

**핵심 원인:**
1. **Volume Surge 컨플루언스가 역추세 기준** - RSI 과매도/볼린저 하단에서 높은 점수
   - 거래량 급등 = 가격 상승 = RSI 상승 = 볼린저 상단 → 점수 낮음
2. **변동률 30% 필터가 너무 엄격** - 거래량 급등 종목은 이미 변동률 높음
3. **진입 조건 과도** - RSI 70 이하, 컨플루언스 60점 이상

**변경 내용:**

#### 1. VolumeSurgeAnalyzer - 모멘텀 기반 컨플루언스로 변경

```kotlin
// 변경 전 (역추세 기준 - 잘못됨)
rsi <= 30 -> 25점
bollingerPosition == "LOWER" -> 25점

// 변경 후 (모멘텀 기준)
rsi in 50.0..65.0 -> 25점  // 상승 중이지만 과매수 전
volumeRatio >= 5.0 -> 30점 // 거래량이 핵심 (가중치 상향)
bollingerPosition == "UPPER" -> 20점  // 돌파도 긍정 신호
```

#### 2. VolumeSurgeFilter - 변동률 필터 완화

```kotlin
// 변경 전
isTooSmall = tradingVolume < 5억원
isAlreadySurged = changeRate > 30%

// 변경 후
isTooSmall = tradingVolume < 3억원  // 5억→3억
isExtremelyHigh = changeRate > 50%  // 30%→50%
```

#### 3. VolumeSurgeEngine - 진입 조건 최적화

```kotlin
// RSI 허용 범위 확대: 70 → 80
// 거래량이 충분하면 컨플루언스 기준 완화:
volumeRatio >= 5.0 -> 컨플루언스 30점 이상
volumeRatio >= 3.0 -> 컨플루언스 40점 이상
volumeRatio >= 2.0 -> 컨플루언스 50점 이상
```

#### 4. MeanReversionStrategy - 기준 완화

```kotlin
// 변경 전
RSI_OVERSOLD = 30.0
RSI_OVERBOUGHT = 70.0
VOLUME_MULTIPLIER = 1.5
MIN_CONFLUENCE_SCORE = 50

// 변경 후
RSI_OVERSOLD = 25.0       // 범위 확대
RSI_OVERBOUGHT = 75.0     // 범위 확대
VOLUME_MULTIPLIER = 1.2   // 완화
MIN_CONFLUENCE_SCORE = 40 // 완화
```

#### 5. application.yml - 전략 파라미터 최적화

```yaml
trading:
  markets: [BTC_KRW, ETH_KRW, XRP_KRW, SOL_KRW]  # 4개로 확대
  strategy:
    mean-reversion-threshold: 1.2  # 2.0→1.2
    rsi-oversold: 25               # 30→25
    rsi-overbought: 75             # 70→75
    bollinger-std-dev: 1.8         # 2.0→1.8

volumesurge:
  max-positions: 5                 # 3→5
  stop-loss-percent: -3.0          # -2.0→-3.0
  take-profit-percent: 6.0         # 5.0→6.0
  position-timeout-min: 60         # 30→60
  cooldown-min: 3                  # 5→3
  llm-cooldown-min: 60             # 240→60
  min-confluence-score: 40         # 60→40
  max-rsi: 80                      # 70→80
  min-volume-ratio: 1.5            # 2.0→1.5
```

**예상 효과:**
- Volume Surge 진입률 대폭 상승 (0% → 예상 30-50%)
- Mean Reversion 거래 빈도 증가 (Z-Score 1.2로 완화)
- 거래 대상 4개 코인으로 확대 (BTC, ETH, XRP, SOL)

---

## 변경사항 (2026-01-19)

### 1. closeAttemptCount null 문제 해결

DB에서 `closeAttemptCount` 컬럼에 null 값이 저장되어 LLM 회고 시스템의 분석 기능이 실패하던 문제 수정:

**수정 파일:**
- `VolumeSurgeEntity.kt:187` - `@Column(nullable = false)` 추가
- `MemeScalperEntity.kt:115` - `@Column(nullable = false)` 추가

**마이그레이션 스크립트:** `docs/fix-close-attempt-count-null.sql`

### 2. MACD BEARISH 패널티 적용

LLM 일일 회고 결과 반영. KRW-SPURS 손실 케이스(-4.35%) 분석 후 컨플루언스 점수 계산 수정:

```kotlin
// VolumeSurgeAnalyzer.kt
// 변경 전: MACD BEARISH → 5점 (약한 허용)
// 변경 후: MACD BEARISH → -10점 (패널티)

score += when (macdSignal) {
    "BULLISH" -> 25
    "NEUTRAL" -> 15
    "BEARISH" -> -10  // 기존 5점 → -10점
    else -> 0
}
```

**효과:** MACD BEARISH + 거래량비율 2.26 케이스가 65점 → 50점으로 하락하여 60점 기준 미달로 진입 차단.

### 3. Funding Rate Arbitrage API 구현

**Phase 1 모니터링 완료:**

| 엔드포인트 | 메소드 | 설명 |
|-----------|--------|------|
| `/api/funding/status` | GET | 모니터링 상태 조회 |
| `/api/funding/opportunities` | GET | 현재 펀딩 기회 목록 |
| `/api/funding/scan` | POST | 수동 스캔 실행 |
| `/api/funding/info/{symbol}` | GET | 특정 심볼 펀딩 정보 |
| `/api/funding/history/{symbol}` | GET | 펀딩 비율 히스토리 |
| `/api/funding/config` | GET | 전략 설정 조회 |
| `/api/funding/rates/latest` | GET | 최신 펀딩 비율 (심볼별) |
| `/api/funding/positions` | GET | 포지션 목록 |
| `/api/funding/stats/daily` | GET | 일일 통계 |
| `/api/funding/performance` | GET | 성과 요약 |

### 4. MCP 도구 추가 (Funding Rate)

`PerformanceTools.kt`에 4개 MCP 도구 추가:

| 도구 | 설명 |
|------|------|
| `scanFundingOpportunities()` | 현재 펀딩 비율 기회 스캔 (연환산 15%+) |
| `getFundingRateHistory(symbol, limit)` | 특정 심볼 펀딩 비율 히스토리 |
| `getFundingPositions(status)` | 펀딩 차익거래 포지션 목록 |
| `getFundingMonitorStatus()` | 모니터링 상태 조회 |

---

## 변경사항 (2026-01-13)

### 1. LLM 필터 4시간 쿨다운 추가

동일 마켓에 대해 4시간 내에 LLM 필터 결과가 있으면 DB에서 재사용하여 API 비용 절감:

```kotlin
// VolumeSurgeFilter.kt
private fun getCachedFilterResult(market: String): FilterResult? {
    val cooldownMinutes = properties.llmCooldownMin.toLong()  // 기본 240분 (4시간)
    val cutoffTime = Instant.now().minus(cooldownMinutes, ChronoUnit.MINUTES)

    val cachedAlert = alertRepository
        .findTopByMarketAndLlmFilterResultIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(market, cutoffTime)

    // 캐시된 결과가 있으면 재사용
    return cachedAlert?.let {
        FilterResult(
            decision = it.llmFilterResult!!,
            confidence = it.llmConfidence ?: 0.5,
            reason = "[캐시] ${it.llmFilterReason}"
        )
    }
}
```

**효과:**
- 15건 경보 × LLM 호출 → 신규 마켓만 LLM 호출
- 비용 대폭 절감 (동일 마켓 반복 경보 시)

### 2. LLM 필터 동시성 버그 수정

여러 워커가 동시에 LLM 필터를 호출할 때 결과가 뒤섞이는 버그 수정:

```kotlin
// 수정 전 (버그)
@Volatile
private var lastDecision: FilterResult? = null

// 수정 후
private val decisionsByMarket = ConcurrentHashMap<String, FilterResult>()
fun getLastDecision(market: String): FilterResult? = decisionsByMarket.remove(market)
```

**makeDecision 도구에 market 파라미터 추가:**
```kotlin
fun makeDecision(
    @ToolParam(description = "마켓 코드") market: String,  // 추가됨
    @ToolParam(description = "APPROVED 또는 REJECTED") decision: String,
    ...
)
```

---

## 변경사항 (2026-01-12)

### 3. 전략 상태 지속성 (재시작 시 복원)

서버 재시작 후에도 이전 포지션/상태를 이어서 트레이딩:

**DcaStrategy**:
```kotlin
// KeyValueService를 통해 마지막 매수 시간 저장
// 키: dca.last_buy_time.{market}
// 값: ISO-8601 timestamp

@PostConstruct
fun restoreState() {
    tradingProperties.markets.forEach { market ->
        val savedTime = keyValueService.get("dca.last_buy_time.$market", null)
        if (savedTime != null) {
            lastBuyTime[market] = Instant.parse(savedTime)
        }
    }
}
```

**GridStrategy**:
```kotlin
// 그리드 상태 JSON 직렬화 저장
// 키: grid.state.{market}
// 값: {"basePrice": "...", "levels": [...], "lastAction": "..."}

@PostConstruct
fun restoreState() {
    // DB에서 그리드 상태 복원
    val savedJson = keyValueService.get("grid.state.$market", null)
    // JSON → GridState 역직렬화
}
```

### 2. 빗썸 수수료 반영

수수료: **0.04%** (하드코딩)

```kotlin
// TradeEntity.kt, OrderExecutor.kt
fee = amount.multiply(BigDecimal("0.0004"))  // 빗썸 수수료 0.04%
```

### 3. 시간대 설정

NAS 배포 시 한국 시간대 적용:

```yaml
# docker-compose.nas.yml
environment:
  - TZ=Asia/Seoul
```

### 4. MCP 응답 data class 변환

PerformanceTools 응답을 `Map<String, Any>` → `data class`로 변경:

```kotlin
data class PerformanceSummary(
    val period: String,
    val totalTrades: Int,
    val winRate: String,
    val strategyBreakdown: Map<String, StrategyStats>
)
```

### 5. LLM Optimizer 수동 실행 API

스케줄러 외에 수동으로 최적화 실행 가능:

```bash
# 수동 최적화 실행
curl -X POST http://localhost:8080/api/optimizer/run

# 마지막 최적화 결과 조회
curl http://localhost:8080/api/optimizer/status

# 감사 로그 조회
curl http://localhost:8080/api/optimizer/audit-logs?limit=50
```

### 6. 지정가 기본 주문 (퀀트 지식 기반)

**기본: 지정가 주문 (최유리 호가)**
- 매수: 최우선 매도호가(ask)로 지정가 → 슬리피지 최소화
- 매도: 최우선 매수호가(bid)로 지정가
- 3초 대기 후 미체결 시 → 취소 후 시장가 전환

**시장가 사용 조건 (2개 이상 충족 시):**
- 고변동성: 1분 변동성 > 1.5%
- 강한 신호: 신뢰도 > 85%
- 얇은 호가창: 유동성 배율 < 5x

**무조건 시장가:**
- 초단기 전략: `ORDER_BOOK_IMBALANCE`, `MOMENTUM`, `BREAKOUT`

```kotlin
// OrderExecutor.kt 상수
LIMIT_ORDER_WAIT_MS = 3000L              // 지정가 대기 시간
HIGH_VOLATILITY_THRESHOLD = 1.5          // 고변동성 임계값
HIGH_CONFIDENCE_THRESHOLD = 85.0         // 고신뢰도 임계값
THIN_LIQUIDITY_THRESHOLD = 5.0           // 얇은호가 임계값
```

### 7. Spring Boot 3.5.9 + Spring AI 1.1.2 호환성

Spring Boot 4.0.x와 Spring AI 1.1.x의 호환성 문제로 다운그레이드:

```
Spring Boot: 4.0.1 → 3.5.9
Spring AI: 1.1.1 → 1.1.2
```

**LlmConfig 변경**: `@ConditionalOnBean` → `ObjectProvider` 방식

```kotlin
@Bean("anthropicChatClient")
fun anthropicChatClient(chatModelProvider: ObjectProvider<AnthropicChatModel>): ChatClient? {
    val chatModel = chatModelProvider.ifAvailable
    return chatModel?.let { ChatClient.create(it) }
}
```

**bootRun 환경변수 자동 로드**: `build.gradle.kts`에서 `.env` 파일 파싱

```kotlin
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}
```

### 8. 미체결 주문 비동기 관리 (PendingOrderManager)

지정가 주문 후 즉시 체결되지 않으면 백그라운드에서 관리:

```kotlin
// PendingOrderEntity - 미체결 주문 DB 저장
@Entity
data class PendingOrderEntity(
    val orderId: String,
    val market: String,
    val orderType: String,  // BUY/SELL
    var status: String,     // PENDING/FILLED/CANCELLED/TIMEOUT
    val limitPrice: BigDecimal,
    val quantity: BigDecimal
)

// PendingOrderManager - 주기적 체결 확인
@Scheduled(fixedDelay = 5000)
suspend fun checkPendingOrders() {
    // 미체결 주문 체결 여부 확인
    // 타임아웃 시 취소 → 시장가 전환
}
```

---

## 배포

### 자동 배포 (Watchtower)

**git push 후 약 15분 후 자동 배포됩니다.**

1. **커밋 & 푸시**:
   ```bash
   git add .
   git commit -m "feat: 새 기능 추가"
   git push origin main
   ```

2. **GitHub Actions 자동화**:
   - Docker Hub에 `dbswns97/coin-trading-server:latest` 푸시
   - 빌드 시간: 약 2-3분

3. **NAS Watchtower 자동 업데이트**:
   - 5분마다 Docker Hub 체크
   - 새 이미지 감지 시 자동으로 컨테이너 재시작
   - Zero-downtime 배포 (롤링업 없는 업데이트)

**전체 소요 시간: 약 15분** (빌드 2-3분 + Watchtower 체크 5분 이내)

### Docker Hub 빌드 & 푸시 (수동)

```bash
./docker-build-push.sh              # latest 태그
./docker-build-push.sh v1.0.0       # 버전 태그
./docker-build-push.sh --no-cache   # 캐시 없이 빌드
```

### NAS 초기 설정 (최초 1회)

```bash
# NAS에서 docker-compose.nas.yml로 시작
cd /path/to/coin-trading-spring
docker-compose -f docker-compose.nas.yml up -d

# 이후 Watchtower가 자동 업데이트 담당
```

### 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `BITHUMB_ACCESS_KEY` | Bithumb API Access Key | (필수) |
| `BITHUMB_SECRET_KEY` | Bithumb API Secret Key | (필수) |
| `MYSQL_URL` | MySQL 접속 URL | localhost:3306 |
| `MYSQL_USER` | MySQL 사용자 | root |
| `MYSQL_PASSWORD` | MySQL 비밀번호 | (필수) |
| `SLACK_TOKEN` | Slack Bot Token (xoxb-...) | (선택) |
| `SLACK_CHANNEL` | Slack 채널명 | coin-bot-alert |
| `TRADING_ENABLED` | 실거래 활성화 | false |
| `ORDER_AMOUNT_KRW` | 1회 주문 금액 | 10000 |
| `STRATEGY_TYPE` | 기본 전략 | MEAN_REVERSION |
| `LLM_OPTIMIZER_ENABLED` | LLM 최적화 활성화 | false |
| `SPRING_AI_ANTHROPIC_API_KEY` | Anthropic API 키 | (LLM 사용 시) |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI API 키 | (LLM 사용 시) |

---

## 데이터베이스 직접 조회

AI 에이전트가 MySQL 데이터베이스에 직접 접속하여 쿼리를 실행할 수 있습니다.

### 접속 정보

**보안 중요:** DB 접속 정보는 `.mysql_info` 파일에 별도 관리하며 **이 파일은 절대 커밋하지 않습니다.**

```bash
# .mysql_info 파일에서 환경 변수 로드
source .mysql_info
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE
```

### Bash 명령어로 직접 조회

```bash
# .mysql_info 로드 후 쿼리 실행
source .mysql_info
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SHOW TABLES;"

# 테이블 목록 조회
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SHOW TABLES;"

# 트레이딩 기록 조회
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SELECT * FROM trades ORDER BY created_at DESC LIMIT 10;"

# Volume Surge 포지션 조회
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SELECT * FROM volume_surge_trades WHERE status = 'OPEN';"

# Meme Scalper 포지션 조회
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SELECT * FROM meme_scalper_trades WHERE status = 'OPEN';"

# 일일 성과 조회
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE -e "SELECT DATE(created_at) as date, COUNT(*) as trades, SUM(CASE WHEN pnl_amount > 0 THEN 1 ELSE 0 END) as wins FROM volume_surge_trades WHERE status = 'CLOSED' GROUP BY DATE(created_at) ORDER BY date DESC LIMIT 7;"
```

### 주요 테이블 구조

| 테이블 | 설명 |
|--------|------|
| `trades` | 메인 트레이딩 엔진 거래 기록 |
| `volume_surge_alerts` | Volume Surge 경보 기록 |
| `volume_surge_trades` | Volume Surge 포지션 및 거래 |
| `meme_scalper_trades` | Meme Scalper 포지션 및 거래 |
| `key_values` | 동적 설정 저장소 |
| `circuit_breaker_states` | 서킷 브레이커 상태 |

---

## 전략 상태 관리

### 저장되는 상태

| 전략 | 키 패턴 | 저장 내용 |
|------|---------|----------|
| DCA | `dca.last_buy_time.{market}` | 마지막 매수 시간 (ISO-8601) |
| Grid | `grid.state.{market}` | 기준가, 그리드 레벨, 체결 상태 (JSON) |

### 조회 API

```bash
# DCA 상태 조회
curl http://localhost:8080/api/settings/category/dca

# Grid 상태 조회
curl http://localhost:8080/api/settings/category/grid
```

### 상태 초기화

```bash
# Grid 상태 리셋 (API 또는 DB에서 직접 삭제)
curl -X DELETE http://localhost:8080/api/settings/grid.state.BTC_KRW
```

---

## MCP Tools 사용법

Claude Code에서 MCP 연결 후 사용 가능한 도구:

| 도구 | 설명 |
|------|------|
| `getOhlcv` | OHLCV 캔들 데이터 조회 |
| `getCurrentPrice` | 현재가 조회 |
| `calculateRsi` | RSI 지표 계산 |
| `calculateMacd` | MACD 지표 계산 |
| `calculateBollingerBands` | 볼린저밴드 계산 |
| `getBalances` | 전체 잔고 조회 |
| `buyLimitOrder` | 지정가 매수 |
| `sellLimitOrder` | 지정가 매도 |
| `getStrategyConfig` | 전략 설정 조회 |
| `setStrategy` | 전략 변경 |

---

## MCP 서버 연결

### 로컬 개발 환경

```bash
# 로컬 MCP 서버 연결
claude mcp add coin-trading-spring --transport stdio
```

### 원격 서버 연결 (Live 배포 환경)

```bash
# 원격 MCP 서버 연결 (Live 배포된 서버)
# IP와 PORT는 .mysql_info 또는 배포 환경 설정을 참조
source .mysql_info
claude mcp add coin-trading-spring --transport http http://$MYSQL_HOST:$MCP_PORT/mcp
```

### MCP Tool 어노테이션

MCP 도구를 생성할 때 두 가지 어노테이션을 모두 사용하여 로컬/원격 환경 모두 지원:

```kotlin
@McpTool(description = "도구 설명")
@Tool(description = "동일한 도구 설명 (Spring AI 호환)")
fun myTool(...): String {
    // ...
}
```

- `@McpTool`: Claude Code 전용 (MCP 직접 연결 시)
- `@Tool`: Spring AI LLM용 (Spring AI MCP Adapter 사용 시)
| `getPerformanceSummary` | 성과 요약 |
| `getOptimizationReport` | 최적화 리포트 |

---

## 진행 중인 작업: Volume Surge Trading Strategy (2026-01-13~)

### 개요

**거래량 급등 종목 단타 전략**: Bithumb 경보제 API를 활용하여 거래량이 급등한 종목을 감지하고, LLM이 웹검색을 통해 펌프앤덤프 여부를 필터링한 후 기술적 분석을 통해 단타 트레이딩을 수행.

**핵심 아이디어:**
- 거래량 급등 = 시장 관심 집중 → 빠른 가격 변동 기회
- LLM 웹검색 필터 = 펌프앤덤프/세력주 배제
- 타이트한 리스크 관리 = 손실 최소화 (-2% 손절)
- 학습/회고 시스템 = 케이스 축적 및 지속적 개선

### 아키텍처

```
[Volume Surge Trading Module]
│
├── Alert Polling Service
│   ├── Bithumb 경보제 API 주기적 조회 (30초)
│   │   └── GET /v1/market/virtual_asset_warning
│   └── TRADING_VOLUME_SUDDEN_FLUCTUATION 필터링
│
├── LLM Filter (Spring AI)
│   ├── 웹검색으로 종목 뉴스 수집
│   ├── 펌프앤덤프 판단 (시가총액, 거래량 패턴, 뉴스 진위)
│   └── 진입 가능 여부 결정
│
├── Technical Analysis
│   ├── Confluence Analysis (RSI + MACD + 볼린저 + 거래량)
│   ├── Volume Spike Ratio 계산 (20일 평균 대비)
│   └── Entry/Exit 신호 생성
│
├── Position Manager
│   ├── 포지션 진입 (소액: 10,000 KRW)
│   ├── Stop Loss: -2%
│   ├── Take Profit: +5%
│   ├── Trailing Stop: +2% 이후 -1%
│   └── Timeout: 30분 후 강제 청산
│
├── Learning System (DB 저장)
│   ├── TradeCaseEntity: 모든 트레이드 케이스 저장
│   ├── 성공/실패 분류 및 원인 분석
│   └── LLM 일일 회고 (LlmOptimizer 패턴)
│
└── Reflection Engine
    ├── 일일 케이스 리뷰
    ├── 패턴 학습 (어떤 거래량 급등이 성공했나?)
    └── 필터 임계값 자동 조정
```

### 퀀트 연구 결과 (2025~2026)

#### 펌프앤덤프 특징
- **시가총액**: 대부분 $50M 미만 소형주
- **덤프 타이밍**: 펌프 시작 후 수 분 이내 덤프 시작
- **워시 트레이딩**: 2024년 $2.57B 규모 발생
- **탐지 방법**: EWMA 기반 이상 거래량 탐지 (학술 연구 검증)

#### 적용 파라미터
```kotlin
object VolumeSurgeConfig {
    const val MIN_VOLUME_RATIO = 3.0        // 20일 평균 대비 300% 이상
    const val MAX_RSI = 70.0                // 과매수 영역 진입 제한
    const val MIN_CONFLUENCE_SCORE = 60     // 컨플루언스 점수 60점 이상
    const val POSITION_SIZE_KRW = 10000     // 1회 10,000원
    const val STOP_LOSS_PERCENT = -2.0      // 손절 -2%
    const val TAKE_PROFIT_PERCENT = 5.0     // 익절 +5%
    const val TRAILING_STOP_TRIGGER = 2.0   // +2% 도달 시 트레일링 시작
    const val TRAILING_STOP_OFFSET = 1.0    // 고점 대비 -1%에서 청산
    const val POSITION_TIMEOUT_MIN = 30     // 30분 타임아웃
    const val ALERT_FRESHNESS_MIN = 5       // 5분 이내 신선한 경보만 처리
    const val MIN_MARKET_CAP_KRW = 50_000_000_000  // 최소 시가총액 500억원
}
```

### DB 스키마

#### 1. volume_surge_alerts (경보 기록)
```sql
CREATE TABLE volume_surge_alerts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    market VARCHAR(20) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    volume_ratio DOUBLE,                    -- 20일 평균 대비 비율
    detected_at TIMESTAMP NOT NULL,
    llm_filter_result VARCHAR(20),          -- APPROVED/REJECTED/SKIPPED
    llm_filter_reason TEXT,
    processed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_alerts_market (market),
    INDEX idx_alerts_detected (detected_at)
);
```

#### 2. volume_surge_trades (트레이드 케이스)
```sql
CREATE TABLE volume_surge_trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT,                        -- FK to volume_surge_alerts
    market VARCHAR(20) NOT NULL,
    entry_price DOUBLE NOT NULL,
    exit_price DOUBLE,
    quantity DOUBLE NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    exit_reason VARCHAR(30),                -- STOP_LOSS/TAKE_PROFIT/TRAILING/TIMEOUT/MANUAL
    pnl_amount DOUBLE,
    pnl_percent DOUBLE,

    -- 분석 데이터 (회고용)
    entry_rsi DOUBLE,
    entry_macd_signal VARCHAR(10),          -- BULLISH/BEARISH/NEUTRAL
    entry_bollinger_position VARCHAR(10),   -- LOWER/MIDDLE/UPPER
    entry_volume_ratio DOUBLE,
    confluence_score INT,

    -- LLM 판단 기록
    llm_entry_reason TEXT,
    llm_confidence DOUBLE,

    -- 회고 결과
    reflection_notes TEXT,
    lesson_learned TEXT,

    status VARCHAR(20) DEFAULT 'OPEN',      -- OPEN/CLOSED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_trades_market (market),
    INDEX idx_trades_status (status),
    INDEX idx_trades_created (created_at),
    FOREIGN KEY (alert_id) REFERENCES volume_surge_alerts(id)
);
```

#### 3. volume_surge_daily_summary (일일 요약)
```sql
CREATE TABLE volume_surge_daily_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    total_alerts INT DEFAULT 0,
    approved_alerts INT DEFAULT 0,
    total_trades INT DEFAULT 0,
    winning_trades INT DEFAULT 0,
    losing_trades INT DEFAULT 0,
    total_pnl DOUBLE DEFAULT 0,
    win_rate DOUBLE DEFAULT 0,
    avg_holding_minutes DOUBLE,

    -- LLM 회고 결과
    reflection_summary TEXT,
    parameter_changes TEXT,                  -- JSON: 변경된 파라미터들

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 구현 Phase

#### Phase 1: 기반 구축 ✅ 완료
- [x] CLAUDE.md에 계획 문서화
- [x] DB Entity 클래스 생성 (`VolumeSurgeEntity.kt`, `VolumeSurgeRepository.kt`)
- [x] Bithumb Alert API 연동 (`VirtualAssetWarning`, `getVirtualAssetWarning()`)
- [x] VolumeSurgeProperties 설정 클래스

#### Phase 2: 핵심 로직 ✅ 완료
- [x] AlertPollingService: 경보 주기적 조회 (30초)
- [x] VolumeSurgeFilter: LLM 웹검색 필터 (Tool Calling)
- [x] VolumeSurgeAnalyzer: 기술적 분석 (RSI, MACD, 볼린저, 컨플루언스)
- [x] VolumeSurgeEngine: 포지션 관리 (손절/익절/트레일링/타임아웃)

#### Phase 3: 학습 시스템 ✅ 완료
- [x] VolumeSurgeTradeEntity: 트레이드 케이스 DB 저장
- [x] VolumeSurgeReflector: LLM 일일 회고 (매일 새벽 1시)
- [x] VolumeSurgeReflectorTools: 회고용 LLM Tool Calling

#### Phase 4: 통합 및 테스트 ✅ 완료
- [x] VolumeSurgeEngine: 메인 엔진 구현 완료
- [x] Slack 알림 연동 완료
- [x] VolumeSurgeController: 수동 트리거 API 완료
- [x] 모의 거래 테스트 완료 (12건 경보 감지, LLM 필터 정상 작동)

### API 엔드포인트

| 엔드포인트 | 메소드 | 설명 |
|-----------|--------|------|
| `/api/volume-surge/status` | GET | 전략 상태 및 열린 포지션 조회 |
| `/api/volume-surge/stats/today` | GET | 오늘 통계 조회 |
| `/api/volume-surge/alerts` | GET | 최근 경보 목록 |
| `/api/volume-surge/trades` | GET | 최근 트레이드 목록 |
| `/api/volume-surge/summaries` | GET | 일일 요약 목록 |
| `/api/volume-surge/reflect` | POST | 수동 회고 실행 |
| `/api/volume-surge/config` | GET | 설정 조회 |

### 핵심 컴포넌트

#### 1. AlertPollingService
```kotlin
@Component
class AlertPollingService(
    private val bithumbPublicApi: BithumbPublicApi,
    private val alertRepository: VolumeSurgeAlertRepository
) {
    @Scheduled(fixedDelay = 30000)  // 30초마다
    suspend fun pollAlerts() {
        val alerts = bithumbPublicApi.getVirtualAssetWarning()
        alerts?.filter { it.warningType == "TRADING_VOLUME_SUDDEN_FLUCTUATION" }
            ?.forEach { processAlert(it) }
    }
}
```

#### 2. VolumeSurgeFilter (LLM Tool Calling)
```kotlin
@Component
class VolumeSurgeFilter(
    private val modelSelector: ModelSelector,
    private val filterTools: VolumeSurgeFilterTools
) {
    suspend fun shouldEnter(market: String, alert: VolumeSurgeAlert): FilterResult {
        val chatClient = modelSelector.getChatClient()
            .mutate()
            .defaultTools(filterTools)
            .build()

        val response = chatClient.prompt()
            .system(filterSystemPrompt)
            .user("마켓 $market 의 거래량이 급등했습니다. 웹검색을 통해 이 종목이 투자하기 적합한지 판단해주세요.")
            .call()
            .content()

        return parseFilterResult(response)
    }
}
```

#### 3. VolumeSurgePositionManager
```kotlin
@Component
class VolumeSurgePositionManager(
    private val orderExecutor: OrderExecutor,
    private val tradeRepository: VolumeSurgeTradeRepository
) {
    // 실시간 포지션 모니터링 (1초마다)
    @Scheduled(fixedDelay = 1000)
    suspend fun monitorPositions() {
        val openPositions = tradeRepository.findByStatus("OPEN")
        openPositions.forEach { position ->
            val currentPrice = getCurrentPrice(position.market)
            val pnlPercent = calculatePnlPercent(position, currentPrice)

            when {
                pnlPercent <= STOP_LOSS_PERCENT -> closePosition(position, "STOP_LOSS")
                pnlPercent >= TAKE_PROFIT_PERCENT -> closePosition(position, "TAKE_PROFIT")
                isTrailingStopTriggered(position, currentPrice) -> closePosition(position, "TRAILING")
                isTimeout(position) -> closePosition(position, "TIMEOUT")
            }
        }
    }
}
```

### 관련 스킬

이 전략 구현 시 다음 스킬들을 참조한다:

| 스킬 | 용도 |
|------|------|
| `/bithumb-api` | Alert API 연동, 주문 실행 |
| `/spring-ai` | LLM Tool Calling, ChatClient |
| `/quant-confluence` | 컨플루언스 분석 (85% 승률) |
| `/trading-decision` | 최종 매매 결정 |
| `/news-sentiment` | 뉴스 감성 분석 (선택적) |

### 새로 생성할 스킬

#### `/volume-surge` 스킬
거래량 급등 단타 전략의 퀀트 지식과 구현 가이드를 담은 스킬.
- 펌프앤덤프 탐지 방법
- 최적 진입/청산 타이밍
- 리스크 관리 파라미터
- 학습/회고 시스템 패턴

---

## 다음 단계 (TODO)

### Phase 2: 엣지 확보

1. ~~**MCP Server 연동**~~ ✅ 완료
2. ~~**LLM 모델 선택 개선**~~ ✅ 완료
3. ~~**Slack Bot API 전환**~~ ✅ 완료
4. ~~**전략 상태 지속성**~~ ✅ 완료
   - DCA/Grid 상태 DB 저장
   - 재시작 시 @PostConstruct 복원
5. ~~**빗썸 수수료 반영**~~ ✅ 완료 (0.04%)
6. ~~**LLM Optimizer 수동 실행 API**~~ ✅ 완료
   - POST /api/optimizer/run
   - GET /api/optimizer/status
7. ~~**지정가 기본 주문**~~ ✅ 완료
   - 최유리 호가 지정가 주문 (슬리피지 최소화)
   - 퀀트 지식 기반 시장가 전환 조건
8. ~~**Spring AI 호환성**~~ ✅ 완료
   - Spring Boot 3.5.9 + Spring AI 1.1.2
   - ObjectProvider 기반 ChatClient 생성
9. ~~**미체결 주문 관리**~~ ✅ 완료
   - PendingOrderManager 비동기 처리
   - DB 저장 및 재시작 시 복원

10. ~~**김치 프리미엄 모니터링**~~ ✅ 완료
    - BinancePublicApi: 바이낸스 현재가 조회
    - KimchiPremiumService: 프리미엄 계산 및 1분 주기 모니터링
    - KimchiPremiumController: REST API
    - 주요 코인: BTC, ETH, XRP, SOL, ADA, DOGE

11. ~~**MCP 도구 응답 개선**~~ ✅ 완료
   - TechnicalAnalysisTools data class 변환
   - TradingTools data class 변환
   - StrategyTools data class 변환
   - PerformanceTools data class 변환

### Phase 3: 확장

12. **Funding Rate Arbitrage** (Phase 1 모니터링 완료)
    - ~~Binance Futures API 클라이언트 구현~~ ✅
    - ~~FundingRateMonitor 스케줄러 구현~~ ✅
    - ~~FundingArbitrageController REST API~~ ✅
    - ~~MCP 도구 추가 (scanFundingOpportunities 등)~~ ✅
    - Phase 2: 자동 거래 로직 (TODO)
    - Bybit 선물 API 연동 (TODO)

13. **백테스팅 프레임워크**
    - 과거 데이터로 전략 검증
    - Walk-forward 최적화

14. **다중 거래소 차익거래**
    - Upbit, Coinone 연동

### Phase 4: 운영 고도화

15. **Prometheus/Grafana 모니터링**
    - Actuator 메트릭 수집
    - 실시간 대시보드

16. **분산 락 (다중 인스턴스)**
    - MySQL 기반 분산 락

17. **자동 복구 시스템**
    - ~~미체결 주문 자동 처리~~ ✅ PendingOrderManager
    - CircuitBreaker 상태 영속화

### Phase 5: 퀀트 고도화 (2026-01-13 리뷰 결과)

18. **포지션별 손절/익절 관리 테이블**
    - 부분 체결 포지션 자동 추적
    - PositionEntity 신규 생성 (TradeEntity와 별도)
    - 포지션별 개별 손절/익절/트레일링 관리
    - 현재는 VolumeSurgeTradeEntity만 포지션 관리, 일반 전략은 미지원

19. **ATR 기반 동적 손절**
    - 현재 고정 -2% → 변동성 기반 조정
    - ATR 배수로 손절 거리 설정 (횡보 1.5x, 추세 3x)
    - StopLossCalculator 서비스 신규 생성
    - 레짐별 ATR 배수 자동 조정

20. **백테스팅 프레임워크** (Phase 3 #13 확장)
    - 과거 데이터 수집 및 저장 (Candle 히스토리 DB)
    - Walk-forward 최적화
    - 전략별 Sharpe Ratio, Max Drawdown 계산
    - 슬리피지/수수료 시뮬레이션 포함

21. **다중 거래소 차익거래** (Phase 3 #14 확장)
    - Upbit, Coinone 연동
    - 거래소 간 호가 비교
    - 전송 시간/수수료 고려한 실시간 차익 계산

---

## AI 에이전트 코드 작성 지침

### 필수 스킬 사용

이 프로젝트에서 코드를 작성할 때 다음 스킬을 **반드시** 참조해야 한다:

#### 1. Spring AI 스킬 (`/spring-ai`)

Spring AI 관련 코드 작성 시 **반드시** 이 스킬을 먼저 호출한다:
- ChatClient API 사용
- Tool Calling (@Tool, @ToolParam)
- Advisors 구현
- MCP Server (@McpTool, @McpResource)
- Anthropic/OpenAI 모델 설정

```
/spring-ai
```

**적용 대상 파일:**
- `config/LlmConfig.kt`
- `service/ModelSelector.kt`
- `mcp/tool/*.kt`
- LLM 관련 모든 코드

#### 2. Bithumb API 스킬 (`/bithumb-api`)

Bithumb 거래소 API 연동 코드 작성 시 **반드시** 이 스킬을 먼저 호출한다:
- Public API (Ticker, Orderbook, Trades, Candles)
- Private API (JWT 인증, 잔고 조회)
- 주문 API (지정가/시장가 매수/매도, 취소)
- WebClient 기반 REST API 호출

```
/bithumb-api
```

**적용 대상 파일:**
- `api/bithumb/BithumbPublicApi.kt`
- `api/bithumb/BithumbPrivateApi.kt`
- `api/bithumb/BithumbModels.kt`
- `service/BithumbTradingService.kt`
- Bithumb API 연동 모든 코드

#### 3. 퀀트 분석 스킬

기술적 분석 및 트레이딩 전략 코드 작성 시 해당 스킬을 호출한다:

| 스킬 | 용도 | 호출 |
|------|------|------|
| RSI 분석 | RSI 계산, 과매수/과매도, 다이버전스 | `/quant-rsi` |
| MACD 분석 | MACD 크로스오버, 다이버전스 | `/quant-macd` |
| 볼린저밴드 | 밴드 스퀴즈, %B, 밴드폭 | `/quant-bollinger` |
| 컨플루언스 | 다중 지표 통합 (85% 승률) | `/quant-confluence` |
| 뉴스 감성 | 뉴스 수집, 감성 점수 | `/news-sentiment` |
| 트레이딩 결정 | 종합 매매 결정, 자동 주문 | `/trading-decision` |

**적용 대상 파일:**
- `strategy/*.kt`
- `indicator/*.kt`
- `mcp/tool/TechnicalAnalysisTools.kt`

### 스킬 사용 예시

**Spring AI 코드 작성 전:**
```
사용자: ChatClient를 수정해줘
AI: /spring-ai 스킬을 먼저 참조한 후 코드를 작성합니다.
```

**기술적 분석 코드 작성 전:**
```
사용자: RSI 전략을 개선해줘
AI: /quant-rsi 스킬을 먼저 참조한 후 코드를 작성합니다.
```

### 주의사항

1. **스킬 미참조 금지**: Spring AI, Bithumb API, 퀀트 관련 코드를 스킬 참조 없이 작성하면 안 된다
2. **최신 API 사용**: Spring AI 1.1.x API, Bithumb API를 사용해야 하며, 스킬에 정의된 패턴을 따른다
3. **검증된 전략**: 퀀트 스킬의 검증된 승률 전략을 우선 적용한다
4. **JWT 인증**: Bithumb Private API 사용 시 JWT 토큰 생성 패턴을 반드시 따른다

---

## 주의사항

1. **API 키 보안**: `.env` 파일은 절대 Git에 커밋하지 말 것
2. **실거래 전 검증**: `TRADING_ENABLED=false` 상태에서 충분히 테스트
3. **소액 시작**: 처음엔 최소 금액(1만원)으로 시작
4. **정기 점검**: 주 1회 수익률/손실 확인
5. **서킷 브레이커 모니터링**: Slack 알림 확인
6. **재시작 시**: DCA/Grid 상태가 자동 복원되는지 로그 확인

---

### 새로 추가된 파일

```
coin-trading-server/src/main/kotlin/com/ant/cointrading/
├── config/
│   └── VolumeSurgeProperties.kt        # 전략 설정
├── controller/
│   └── VolumeSurgeController.kt        # REST API
├── repository/
│   ├── VolumeSurgeEntity.kt            # DB Entity 3개
│   └── VolumeSurgeRepository.kt        # Repository 3개
└── volumesurge/
    ├── AlertPollingService.kt          # 경보 폴링 (30초)
    ├── VolumeSurgeAnalyzer.kt          # 기술적 분석
    ├── VolumeSurgeEngine.kt            # 메인 엔진
    ├── VolumeSurgeFilter.kt            # LLM 필터
    └── VolumeSurgeReflector.kt         # 학습/회고
```

---

*마지막 업데이트: 2026-01-13*
