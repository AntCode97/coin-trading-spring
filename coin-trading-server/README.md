# Coin Trading Server

Spring Boot 백엔드 + React 프론트엔드 자동 매매 시스템

## 로컬 개발

### 1. Spring Boot (포트 8080)
```bash
./gradlew :coin-trading-server:bootRun
```

### 2. React (포트 5173)
```bash
cd coin-trading-client
npm run dev
```

React 개발 서버는 `/api` 요청을 Spring Boot (http://localhost:8080)으로 프록시합니다.

## 프로덕션 빌드

Docker 이미지는 React를 빌드하고 Spring Boot JAR에 포함합니다:

```bash
docker build -t coin-trading-server -f coin-trading-server/Dockerfile .
```

## API 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /api/dashboard` | 대시보드 데이터 조회 |
| `GET /api/dashboard?daysAgo=1` | 어제 거래 내역 조회 |
| `POST /api/dashboard/manual-close` | 수동 매도 |

## 환경 변수

`.env` 파일에 설정 (git 제외됨):
```
BITHUMB_ACCESS_KEY=...
BITHUMB_SECRET_KEY=...
MYSQL_URL=jdbc:mysql://...
MYSQL_USER=...
MYSQL_PASSWORD=...
```
