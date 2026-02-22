# Mac Desktop Trading Workspace

퍼블릭 웹 대시보드에서 수동 트레이딩 워크스페이스를 분리하고, 맥북 로컬 앱(Electron)으로만 실행하는 방식이다.

## 1) 보안 목적

- 퍼블릭 웹에 수동 주문 UI를 노출하지 않는다.
- 수동 트레이딩/에이전트 보조 기능은 맥북 로컬 앱에서만 사용한다.

## 2) API 서버

- 기본 API 주소: `http://183.101.185.112:1104/api`
- 데스크톱 모드 실행 시 `VITE_API_BASE_URL`로 주입된다.

## 3) 로컬 개발 실행

`coin-trading-client` 디렉터리에서:

```bash
npm install
npm run desktop:dev
```

- Vite + Electron이 동시에 실행된다.
- 수동 트레이딩 워크스페이스 단독 화면이 뜬다.

## 4) 데스크톱 모드 웹 빌드 + 실행

```bash
npm run desktop:build:web
npm run desktop:start
```

## 5) OpenCode/OpenAI 연동

- 앱 내부에서 OpenCode 서버 주소를 설정하고 OpenAI 연결 상태를 확인한다.
- OpenCode 서버는 로컬에서 실행해야 한다.

예시:

```bash
opencode serve --hostname 127.0.0.1 --port 4096 --cors http://localhost:5173 --cors http://127.0.0.1:5173
```

## 6) 운영 권장

- NAS 서버는 VPN/허용 IP 기반으로 제한한다.
- 퍼블릭 웹은 조회/모니터링 중심으로 유지하고, 주문 실행 UI는 데스크톱 앱 전용으로 유지한다.
