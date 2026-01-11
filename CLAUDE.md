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
├── LLM Optimizer (Spring AI)
│   ├── LlmConfig            - ChatClient 빈 등록
│   ├── ModelSelector        - 동적 모델 선택 (@Qualifier)
│   └── Tool Calling         - LLM이 직접 함수 호출
│
└── 알림 시스템
    └── SlackNotifier        - Bot API (chat.postMessage)
```

---

## 기술 스택

| 항목 | 버전 |
|------|------|
| JDK | 25 |
| Kotlin | 2.3.0 |
| Spring Boot | 4.0.1 |
| Spring AI | 1.1.1 (Anthropic + OpenAI) |
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
│       │   └── OptimizerController.kt  # LLM 수동 최적화 API
│       ├── service/
│       │   ├── KeyValueService.kt
│       │   └── ModelSelector.kt        # @Qualifier 기반 모델 선택
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

## 최근 변경사항 (2026-01-12)

### 1. 전략 상태 지속성 (재시작 시 복원)

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

---

## 배포

### Docker Hub 빌드 & 푸시

```bash
./docker-build-push.sh              # latest 태그
./docker-build-push.sh v1.0.0       # 버전 태그
./docker-build-push.sh --no-cache   # 캐시 없이 빌드
```

### NAS 배포

```bash
# NAS에서 실행
docker pull dbswns97/coin-trading-server:latest
docker-compose -f docker-compose.nas.yml up -d
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
| `getPerformanceSummary` | 성과 요약 |
| `getOptimizationReport` | 최적화 리포트 |

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

8. **김치 프리미엄 모니터링**
   - 해외 거래소 가격 연동 (Binance API)
   - 프리미엄 계산 및 알림

9. **MCP 도구 응답 개선**
   - TechnicalAnalysisTools data class 변환
   - TradingTools data class 변환
   - StrategyTools data class 변환

### Phase 3: 확장

10. **Funding Rate Arbitrage**
    - 현물 Long + 무기한 선물 Short
    - Binance/Bybit 선물 API 연동

11. **백테스팅 프레임워크**
    - 과거 데이터로 전략 검증
    - Walk-forward 최적화

12. **다중 거래소 차익거래**
    - Upbit, Coinone 연동

### Phase 4: 운영 고도화

13. **Prometheus/Grafana 모니터링**
    - Actuator 메트릭 수집
    - 실시간 대시보드

14. **분산 락 (다중 인스턴스)**
    - MySQL 기반 분산 락

15. **자동 복구 시스템**
    - 미체결 주문 자동 처리
    - CircuitBreaker 상태 영속화

---

## 주의사항

1. **API 키 보안**: `.env` 파일은 절대 Git에 커밋하지 말 것
2. **실거래 전 검증**: `TRADING_ENABLED=false` 상태에서 충분히 테스트
3. **소액 시작**: 처음엔 최소 금액(1만원)으로 시작
4. **정기 점검**: 주 1회 수익률/손실 확인
5. **서킷 브레이커 모니터링**: Slack 알림 확인
6. **재시작 시**: DCA/Grid 상태가 자동 복원되는지 로그 확인

---

*마지막 업데이트: 2026-01-12*
