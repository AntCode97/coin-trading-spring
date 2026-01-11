# HTTP API 호출 가이드

API 호출 예시 모음. Postman 또는 IntelliJ HTTP Client 사용 가능.

---

## Postman 사용법 (권장)

1. Postman 실행
2. **Import** 버튼 클릭
3. `postman-collection.json` 파일 선택
4. Collection 목록에서 "Coin Trading API" 확인
5. 변수 설정: Collection 선택 → Variables 탭 → `baseUrl` 수정

```
baseUrl: http://localhost:8080  (로컬)
baseUrl: http://your-server:8080  (운영)
```

---

## IntelliJ HTTP Client 사용법 (Ultimate 필요)

1. IntelliJ IDEA에서 `.http` 파일 열기
2. 환경 선택 (우측 상단): `local`, `docker`, `prod`
3. 실행하고 싶은 요청 옆의 실행 버튼 클릭

## 파일 구조

| 파일 | 설명 |
|------|------|
| `01-health.http` | 시스템 상태 및 헬스체크 |
| `02-settings.http` | KeyValue 설정 관리 |
| `03-llm-model.http` | LLM 모델 관리 |
| `04-regime.http` | 레짐 감지기 관리 |
| `05-trading.http` | 트레이딩 상태 및 분석 |
| `06-strategy-params.http` | 전략 파라미터 변경 |
| `07-circuit-breaker.http` | 서킷 브레이커 및 리스크 설정 |

## 환경 설정

`http-client.env.json`에서 환경별 호스트 설정:

```json
{
  "local": { "host": "http://localhost:8080" },
  "docker": { "host": "http://localhost:8080" },
  "prod": { "host": "http://your-production-server:8080" }
}
```

## 주요 운영 시나리오

### 1. 거래 시작 전 체크리스트

```
1. GET /actuator/health - 시스템 정상 여부
2. GET /api/trading/enabled - 현재 거래 상태
3. GET /api/settings - 전체 설정 확인
4. GET /api/trading/status - 마켓 상태 확인
```

### 2. 거래 활성화

```
POST /api/settings
{ "key": "trading.enabled", "value": "true" }
```

### 3. LLM 모델 변경

```
POST /api/settings/model
{ "provider": "anthropic", "modelName": "claude-sonnet-4-20250514" }
```

### 4. 레짐 감지기 변경

```
POST /api/settings/regime
{ "type": "hmm" }
```

### 5. 긴급 정지

```
POST /api/settings
{ "key": "trading.enabled", "value": "false" }

POST /api/settings
{ "key": "system.maintenance", "value": "true" }
```

## 키 목록 (자주 사용)

| 키 | 설명 | 기본값 |
|----|------|--------|
| `trading.enabled` | 실거래 활성화 | false |
| `llm.enabled` | LLM 최적화 활성화 | false |
| `llm.model.provider` | LLM 제공자 | anthropic |
| `llm.model.name` | LLM 모델명 | claude-sonnet-4-20250514 |
| `regime.detector.type` | 레짐 감지기 | simple |
| `system.maintenance` | 점검 모드 | false |
