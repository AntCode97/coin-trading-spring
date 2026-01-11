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
│   └── REST API로 런타임 변경
│
├── MCP Server (Claude Code 연동)
│   ├── MarketDataTools       - 시장 데이터 조회
│   ├── TechnicalAnalysisTools - RSI, MACD, 볼린저밴드
│   ├── TradingTools          - 주문/잔고
│   ├── StrategyTools         - 전략 파라미터 조정
│   └── PerformanceTools      - 성과 분석
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
│       │   └── SettingsController.kt
│       ├── service/
│       │   ├── KeyValueService.kt
│       │   └── ModelSelector.kt        # @Qualifier 기반 모델 선택
│       ├── mcp/tool/                   # MCP Server Tools
│       │   ├── MarketDataTools.kt      # @McpTool 어노테이션
│       │   ├── TechnicalAnalysisTools.kt
│       │   ├── TradingTools.kt
│       │   ├── StrategyTools.kt
│       │   └── PerformanceTools.kt
│       ├── notification/
│       │   └── SlackNotifier.kt        # Bot API 방식
│       └── ...
│
├── http/                               # HTTP Client 테스트 파일
├── docker-compose.yml                  # 로컬 개발용
├── docker-compose.nas.yml              # NAS 배포용
├── docker-build-push.sh                # Docker Hub 빌드/푸시
├── .mcp.json                           # Claude Code MCP 연결 설정
└── CLAUDE.md
```

---

## 최근 변경사항 (2026-01-11)

### 1. MCP Server 설정 완료

Spring AI MCP Server 어노테이션 적용:

```kotlin
// org.springaicommunity.mcp.annotation 패키지 사용
@McpTool(description = "RSI를 계산합니다")
fun calculateRsi(
    @McpToolParam(description = "마켓 ID") market: String
): Map<String, Any>
```

Claude Code 연결 설정 (`.mcp.json`):
```json
{
  "mcpServers": {
    "coin-trading": {
      "type": "sse",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### 2. LLM 모델 선택 개선

생성자 주입 + @Qualifier 방식:

```kotlin
// LlmConfig.kt - ChatClient 빈 등록
@Bean("anthropicChatClient")
@ConditionalOnBean(AnthropicChatModel::class)
fun anthropicChatClient(chatModel: AnthropicChatModel): ChatClient {
    return ChatClient.create(chatModel)
}

// ModelSelector.kt - @Qualifier로 주입
@Service
class ModelSelector(
    private val keyValueService: KeyValueService,
    @param:Qualifier("anthropicChatClient") private val anthropicClient: ChatClient? = null,
    @param:Qualifier("openAiChatClient") private val openAiClient: ChatClient? = null
)
```

### 3. Slack Bot API 전환

Webhook → Bot Token 방식:

```kotlin
// chat.postMessage API 사용
webClient.post()
    .uri("https://slack.com/api/chat.postMessage")
    .header("Authorization", "Bearer $token")
    .bodyValue(mapOf("channel" to channel, "text" to text))
```

### 4. Docker 멀티플랫폼 빌드

NAS (Intel N100) 배포를 위한 amd64 빌드:

```bash
docker build --platform linux/amd64 \
    -t dbswns97/coin-trading-server:latest \
    -f coin-trading-server/Dockerfile .
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
   - Spring AI MCP Stateless Server
   - Claude Code 연결 설정

2. ~~**LLM 모델 선택 개선**~~ ✅ 완료
   - @Qualifier 기반 생성자 주입
   - LlmConfig.kt 빈 등록

3. ~~**Slack Bot API 전환**~~ ✅ 완료
   - Webhook → Bot Token 방식

4. **김치 프리미엄 모니터링**
   - 해외 거래소 가격 연동 (Binance API)
   - 프리미엄 계산 및 알림

5. **실시간 수수료 조회**
   - 현재 0.25% 하드코딩
   - Bithumb API에서 실제 수수료율 조회

### Phase 3: 확장

6. **Funding Rate Arbitrage**
   - 현물 Long + 무기한 선물 Short
   - Binance/Bybit 선물 API 연동

7. **백테스팅 프레임워크**
   - 과거 데이터로 전략 검증
   - Walk-forward 최적화

8. **다중 거래소 차익거래**
   - Upbit, Coinone 연동

### Phase 4: 운영 고도화

9. **Prometheus/Grafana 모니터링**
   - Actuator 메트릭 수집
   - 실시간 대시보드

10. **분산 락 (다중 인스턴스)**
    - MySQL 기반 분산 락

11. **자동 복구 시스템**
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
