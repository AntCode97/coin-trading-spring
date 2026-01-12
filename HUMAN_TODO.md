# 사람이 해야 할 작업 목록

이 문서는 AI가 구현할 수 없는 작업들을 정리한 것이다.
아래 작업들을 완료해야 시스템이 실제로 동작한다.

---

## 1. Bithumb API 키 발급 (필수)

### 발급 방법
1. https://www.bithumb.com 접속 및 로그인
2. 우측 상단 프로필 → **API 관리**
3. **API 키 발급** 클릭
4. 권한 설정:
   - [x] 자산 조회
   - [x] 주문 조회
   - [x] 주문 실행 (실거래 시)
   - [ ] 출금 (절대 체크하지 말 것)
5. IP 화이트리스트 설정 (선택, 보안 강화)

### 설정 파일
프로젝트 루트의 `.env` 파일에 설정:

```bash
# Bithumb API (필수)
BITHUMB_ACCESS_KEY=발급받은_ACCESS_KEY
BITHUMB_SECRET_KEY=발급받은_SECRET_KEY

# MySQL (이미 설정됨)
MYSQL_URL=jdbc:mysql://183.101.185.112:1005/coin_trading?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
MYSQL_USER=yunjun
MYSQL_PASSWORD=jXXcMMRwdjK8XrXsxGCG

# Slack (선택)
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/xxx/xxx/xxx

# LLM 최적화 - Spring AI Tool Calling (선택)
# Anthropic, OpenAI, Google Gemini 중 하나만 설정해도 됨
LLM_OPTIMIZER_ENABLED=false
SPRING_AI_ANTHROPIC_API_KEY=your_anthropic_api_key
SPRING_AI_OPENAI_API_KEY=your_openai_api_key
SPRING_AI_GOOGLE_API_KEY=your_google_api_key
```

---

## 2. MySQL 데이터베이스 생성 (필수)

MySQL 서버는 이미 설정되어 있다. `coin_trading` 데이터베이스만 생성하면 된다.

```sql
-- MySQL 접속
mysql -h 183.101.185.112 -P 1005 -u yunjun -p

-- 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS coin_trading
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 확인
USE coin_trading;
```

JPA `ddl-auto: update` 설정으로 테이블이 자동 생성된다.

자동 생성되는 테이블:
- `trades`: 거래 기록
- `audit_logs`: LLM 결정 감사 로그

---

## 3. Slack Webhook 발급 (선택)

거래 신호 및 서킷 브레이커 알림을 받으려면 Slack Webhook이 필요하다.

1. https://api.slack.com/apps 접속
2. **Create New App** → **From scratch**
3. App 이름: `Coin Trading Bot`
4. **Incoming Webhooks** → **Activate**
5. **Add New Webhook to Workspace**
6. 알림받을 채널 선택
7. Webhook URL 복사 → `.env`에 저장

### 알림 종류
- 거래 체결 알림
- 서킷 브레이커 발동 알림
- LLM 최적화 결과 알림
- 오류 알림

---

## 4. 실행 전 체크리스트

### 필수 확인
- [ ] `.env` 파일에 `BITHUMB_ACCESS_KEY` 설정됨
- [ ] `.env` 파일에 `BITHUMB_SECRET_KEY` 설정됨
- [ ] JDK 25 설치됨 (`java -version`으로 확인)
- [ ] MySQL `coin_trading` 데이터베이스 생성됨

### 선택 확인
- [ ] Slack Webhook URL 설정됨
- [ ] Spring AI API 키 설정됨 (LLM 최적화 사용 시, 하나만 필요)
  - Anthropic Claude: `SPRING_AI_ANTHROPIC_API_KEY`
  - OpenAI GPT: `SPRING_AI_OPENAI_API_KEY`
  - Google Gemini: `SPRING_AI_GOOGLE_API_KEY`

---

## 5. 실행 방법

### 로컬 실행
```bash
# 빌드
./gradlew :coin-trading-server:build -x test

# 실행
./gradlew :coin-trading-server:bootRun
```

### Docker 실행
```bash
# Docker Hub에서 가져오기
docker pull dbswns97/coin-trading-server:latest

# 또는 로컬 빌드
docker-compose build

# 실행
docker-compose up -d

# 로그 확인
docker-compose logs -f
```

### 상태 확인
```bash
# 헬스 체크
curl http://localhost:8080/actuator/health

# MCP 도구 목록
curl http://localhost:8080/mcp
```

---

## 6. 아키텍처 개요

```
[coin-trading-server - 단일 서비스]
│
├── 실시간 트레이딩 (룰 기반)
│   ├── DCA (Dollar Cost Averaging)
│   ├── Grid Trading
│   └── Mean Reversion
│
├── 안전장치
│   ├── CircuitBreaker     - 연속 손실 시 자동 거래 중단
│   ├── RegimeDelayLogic   - 잦은 전략 변경 방지 (3회 확인, 1시간 쿨다운)
│   └── AuditLog           - LLM 결정 추적성 확보
│
├── MCP Tools (LLM 연동)
│   ├── MarketDataTools     - 시장 데이터 조회
│   ├── TechnicalAnalysisTools - RSI, MACD, 볼린저밴드
│   ├── TradingTools        - 주문/잔고
│   ├── StrategyTools       - 전략 파라미터 조정
│   └── PerformanceTools    - 성과 분석
│
└── LLM Optimizer (Spring AI Tool Calling)
    ├── Anthropic Claude / OpenAI GPT / Google Gemini 지원
    ├── Tool Calling으로 LLM이 직접 분석/조정 함수 호출
    ├── 30일 데이터 분석 (과적합 방지)
    ├── 90% 신뢰도 임계값 (보수적 적용)
    └── 주 1회 변경 제한
```

---

## 7. 안전장치 설명

### CircuitBreaker (서킷 브레이커)
연속 손실 시 자동으로 거래 중단:

| 조건 | 쿨다운 |
|------|--------|
| 연속 3회 손실 | 4시간 |
| 일일 손실 5% 초과 | 4시간 |
| 24시간 내 10회 손실 | 4시간 |
| 2개 이상 마켓 서킷 발동 | 24시간 (글로벌) |

### LLM Optimizer 안전장치
과적합 및 급격한 변경 방지:

| 항목 | 설정값 |
|------|--------|
| 최소 분석 기간 | 30일 |
| 신뢰도 임계값 | 90% 이상만 자동 적용 |
| 변경 속도 제한 | 주 1회 이상 변경 금지 |
| 파라미터 변경폭 | 현재값의 20% 이내 |

---

## 8. 환경 변수 전체 목록

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
| `MAX_DRAWDOWN_PERCENT` | 최대 낙폭 제한 | 10.0 |
| `STRATEGY_TYPE` | 기본 전략 | MEAN_REVERSION |
| `LLM_OPTIMIZER_ENABLED` | LLM 최적화 활성화 | false |
| `SPRING_AI_ANTHROPIC_API_KEY` | Anthropic Claude API 키 | (LLM 사용 시) |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI GPT API 키 | (LLM 사용 시) |
| `SPRING_AI_GOOGLE_API_KEY` | Google Gemini API 키 | (LLM 사용 시) |

---

## 9. 문제 해결

### API 키 오류
```
Error: Invalid API Key
```
→ `.env` 파일의 키가 정확한지 확인
→ Bithumb에서 키 상태 확인 (만료 여부)

### MySQL 연결 오류
```
Error: Communications link failure
```
→ MySQL 서버 접근 가능 여부 확인
→ 방화벽/네트워크 설정 확인

### MCP 연결 오류
```
Error: MCP endpoint not responding
```
→ 서버가 정상 실행 중인지 확인
→ 포트 8080이 열려있는지 확인

### 서킷 브레이커 발동
```
서킷 브레이커 발동: 연속 3회 손실
```
→ 4시간 후 자동 재개
→ 수동 리셋: `curl -X POST http://localhost:8080/api/circuit-breaker/reset/{market}`

---

## 10. 보안 권장사항

1. **API 키 분리**: 테스트용/실거래용 키 분리 사용
2. **IP 화이트리스트**: Bithumb API 설정에서 특정 IP만 허용
3. **거래 금액 제한**: 처음엔 최소 금액(1만원)으로 시작
4. **모니터링**: Slack 알림으로 모든 거래 및 서킷 브레이커 추적
5. **정기 점검**: 주 1회 수익률/손실 확인
6. **감사 로그 확인**: LLM 결정 이력 주기적 검토

---

---

## 11. Volume Surge 전략 설정 (2026-01-13 추가)

### 11.1 application.yml에 설정 추가 (필수)

```yaml
# application.yml 또는 application-local.yml
volumesurge:
  enabled: false                    # true로 변경 시 전략 활성화
  polling-interval-ms: 30000        # 경보 폴링 간격 (30초)
  position-size-krw: 10000          # 1회 주문 금액 (1만원)
  max-positions: 3                  # 최대 동시 포지션
  stop-loss-percent: -2.0           # 손절 -2%
  take-profit-percent: 5.0          # 익절 +5%
  trailing-stop-trigger: 2.0        # +2%에서 트레일링 시작
  trailing-stop-offset: 1.0         # 고점 대비 -1%에서 청산
  position-timeout-min: 30          # 30분 타임아웃
  llm-filter-enabled: true          # LLM 필터 활성화 (AI API 키 필요)
  reflection-cron: "0 0 1 * * *"    # 매일 새벽 1시 회고
```

### 11.2 DB 테이블 (JPA 자동 생성)

`ddl-auto: update` 설정이면 **자동 생성됨**. 수동 필요 시:

```sql
-- 경보 테이블
CREATE TABLE IF NOT EXISTS volume_surge_alerts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    market VARCHAR(20) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    volume_ratio DOUBLE,
    detected_at TIMESTAMP NOT NULL,
    llm_filter_result VARCHAR(20),
    llm_filter_reason TEXT,
    llm_confidence DOUBLE,
    processed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_vs_alerts_market (market),
    INDEX idx_vs_alerts_detected (detected_at)
);

-- 트레이드 테이블
CREATE TABLE IF NOT EXISTS volume_surge_trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT,
    market VARCHAR(20) NOT NULL,
    entry_price DOUBLE NOT NULL,
    exit_price DOUBLE,
    quantity DOUBLE NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    exit_reason VARCHAR(30),
    pnl_amount DOUBLE,
    pnl_percent DOUBLE,
    entry_rsi DOUBLE,
    entry_macd_signal VARCHAR(10),
    entry_bollinger_position VARCHAR(10),
    entry_volume_ratio DOUBLE,
    confluence_score INT,
    llm_entry_reason TEXT,
    llm_confidence DOUBLE,
    reflection_notes TEXT,
    lesson_learned TEXT,
    trailing_active BOOLEAN DEFAULT FALSE,
    highest_price DOUBLE,
    status VARCHAR(20) DEFAULT 'OPEN',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vs_trades_status (status)
);

-- 일일 요약 테이블
CREATE TABLE IF NOT EXISTS volume_surge_daily_summary (
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
    reflection_summary TEXT,
    parameter_changes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 11.3 테스트 순서

```bash
# 1. 먼저 비활성화 상태로 실행
volumesurge.enabled=false 상태로 bootRun

# 2. API 동작 확인
curl http://localhost:8080/api/volume-surge/status
curl http://localhost:8080/api/volume-surge/config

# 3. 활성화 후 테스트
volumesurge.enabled=true 로 변경 후 재시작

# 4. 로그 확인 (30초마다 경보 폴링)
grep "거래량 급등" logs/application.log
```

### 11.4 API 엔드포인트

| 엔드포인트 | 메소드 | 설명 |
|-----------|--------|------|
| `/api/volume-surge/status` | GET | 전략 상태 및 열린 포지션 |
| `/api/volume-surge/stats/today` | GET | 오늘 통계 |
| `/api/volume-surge/alerts` | GET | 최근 경보 목록 |
| `/api/volume-surge/trades` | GET | 최근 트레이드 목록 |
| `/api/volume-surge/reflect` | POST | 수동 회고 실행 |

### 11.5 주의사항

- **소액 시작**: 처음엔 1만원(`position-size-krw: 10000`)
- **LLM 비용**: `llm-filter-enabled: true` 시 AI API 호출 발생
- **서킷 브레이커**: 연속 3회 손실 또는 일일 -30,000원 도달 시 자동 중지

---

## 완료 체크

모든 설정을 완료했다면:

```bash
# 1. 빌드
./gradlew :coin-trading-server:build -x test

# 2. 실행
./gradlew :coin-trading-server:bootRun

# 3. 상태 확인
curl http://localhost:8080/actuator/health
```

`TRADING_ENABLED=false` 상태에서는 모든 주문이 시뮬레이션 모드로 실행된다.
최소 24시간 모니터링 후 실거래로 전환할 것.
