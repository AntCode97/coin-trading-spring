# AGENTS.md

이 문서는 `coin-trading-spring` 저장소에서 작업하는 에이전트를 위한 **실행 규칙**이다.

## 1) 프로젝트 한 줄 요약
- 서버: Spring Boot + Kotlin 기반 자동매매/Guided 트레이딩 API + MCP 도구 서버
- 클라이언트: Electron + React 데스크톱 트레이딩 워크스페이스 (수동 + 오토파일럿)

## 2) 현재 운영 핵심 (2026-02-24 기준)
- Guided 마켓 보드는 승률 정렬(`RECOMMENDED_ENTRY_WIN_RATE`, `MARKET_ENTRY_WIN_RATE`)을 지원한다.
- 오토파일럿 라이브 도크는 하단 전체폭 UI로 후보/타임라인/워커/주문 퍼널을 실시간 노출한다.
- 주문 생명주기 집계는 `KST 오늘 00:00 ~ 현재` 기준으로 제공한다.
- 전략 그룹은 고정값으로 분리한다:
  - `MANUAL`, `GUIDED`, `AUTOPILOT_MCP`, `CORE_ENGINE`

## 3) 필수 작업 원칙
1. 변경은 항상 **서버-클라이언트 계약 일치**를 유지한다.
2. API/DTO 변경 시 다음을 함께 업데이트한다.
   - 서버 Controller/Service/DTO
   - 클라이언트 `src/api/index.ts` 타입 + 호출부
   - 관련 UI 컴포넌트
3. 오토파일럿/주문 관련 실패는 전체 중단보다 **격리 + 경고 + 지속** 정책을 우선한다.
4. 미계산/실패 데이터는 가능한 `null` 또는 이벤트 경고로 노출하고 목록 자체는 유지한다.
5. 비밀값(토큰/API 키)은 하드코딩하거나 로그로 노출하지 않는다.

## 4) 코드 검색 규칙
- 우선: `mgrep` (semantic search)
- 실패/타임아웃 시: `rg`로 즉시 대체

예시:
```bash
mgrep search -r -s "guided trading autopilot live api"
rg -n "getAutopilotLive|OrderLifecycleTelemetryService" coin-trading-server/src/main/kotlin
```

## 5) 주요 파일 위치
- 서버 Guided API
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/controller/GuidedTradingController.kt`
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/guided/GuidedTradingService.kt`
- 서버 주문 생명주기 텔레메트리
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/service/OrderLifecycleTelemetryService.kt`
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/repository/OrderLifecycleEventEntity.kt`
- 주문 기록 포인트
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/controller/ManualTradingController.kt`
  - `coin-trading-server/src/main/kotlin/com/ant/cointrading/mcp/tool/TradingTools.kt`
- 클라이언트 오토파일럿
  - `coin-trading-client/src/components/ManualTraderWorkspace.tsx`
  - `coin-trading-client/src/components/autopilot/AutopilotLiveDock.tsx`
  - `coin-trading-client/src/lib/autopilot/AutopilotOrchestrator.ts`
  - `coin-trading-client/src/lib/autopilot/MarketWorker.ts`
  - `coin-trading-client/src/api/index.ts`

## 6) API 계약 메모 (고정 규칙)
### `/api/guided-trading/autopilot/live`
응답은 아래 필드를 유지한다.
- `orderSummary`
  - `total`
  - `groups` (전략 그룹별)
- `orderEvents` (최신순)
- `autopilotEvents` (선택/보조)
- `candidates` (상위 후보 + stage/reason)

주문 퍼널 카운터 의미:
- `buyRequested` → `buyFilled` → `sellRequested` → `sellFilled`
- `pending`, `cancelled`는 별도 보조 지표

체결 카운트 규칙:
- `state=done` 또는 `executedVolume>0` 최초 감지 시 1회만 반영(idempotent)

## 7) 오토파일럿 UX 규칙
1. 오토파일럿 ON 시 라이브 도크를 자동 오픈한다.
2. 타임라인에는 Playwright/LLM/Worker/Order 이벤트를 통합한다.
3. Playwright 액션 실패는 경고로 남기고 오케스트레이션은 계속한다.
4. 성능 보관 정책:
   - 이벤트 ring buffer: 400
   - 스크린샷 LRU: 150

## 8) 변경 후 검증 명령
### 서버
```bash
./gradlew test --tests "*GuidedTrading*" --tests "*OrderLifecycleTelemetryServiceTest"
```

### 클라이언트
```bash
cd coin-trading-client
npm run build
```

### 데스크톱 DMG (요청 시)
```bash
cd coin-trading-client
npm run desktop:dmg
```
산출물:
- `coin-trading-client/release/*.dmg`

## 9) 커밋 전 체크리스트
- [ ] 서버/클라이언트 타입 불일치 없음
- [ ] 위 빌드/테스트 명령 실행 또는 미실행 사유 기록
- [ ] 변경 범위 외 파일 불필요 수정 없음
- [ ] 신규 API는 README/CLAUDE 문서 반영

## 10) 문서 동기화 규칙
다음 항목이 바뀌면 `README.md`와 `CLAUDE.md`를 함께 업데이트한다.
1. 공개 API 경로/파라미터/응답 스키마
2. 오토파일럿 의사결정 규칙/안전 정책
3. 주요 런타임 명령(`build`, `test`, `desktop:dmg`)
4. 핵심 컴포넌트 파일 경로 변경
