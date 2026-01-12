---
name: bithumb-api
description: Bithumb 거래소 API 완벽 가이드. Public/Private API, JWT 인증, 주문/취소/잔고 조회 등. Bithumb API 연동 코드 작성 시 반드시 참조.
allowed-tools:
  - Read
  - Bash
  - Grep
  - Edit
  - Write
---

# Bithumb API 완벽 가이드

## 개요

빗썸(Bithumb)은 한국 1위 암호화폐 거래소로, REST API를 통해 시세 조회, 주문, 잔고 관리 등의 기능을 제공한다.
이 문서는 Context7 MCP를 통해 Bithumb 공식 API 문서를 기반으로 작성되었다.

**Base URL:** `https://api.bithumb.com`

---

## 1. API 구조

### 1.1 API 유형

| 유형 | 인증 | 용도 |
|------|------|------|
| **Public API** | 불필요 | 시세, 호가, 체결 내역 조회 |
| **Private API** | JWT 필요 | 주문, 취소, 잔고, 입출금 |

### 1.2 Market Code 형식

```
{결제통화}-{주문통화}
예: KRW-BTC, KRW-ETH, BTC-ETH
```

---

## 2. Public API (인증 불필요)

### 2.1 마켓 코드 조회 (종목 조회)

```
GET /v1/market/all?isDetails={boolean}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `isDetails` | X | 상세정보 포함 여부 (기본값: false) |

**요청:**
```kotlin
val markets = webClient.get()
    .uri("/v1/market/all?isDetails=true")
    .retrieve()
    .bodyToFlux<MarketInfo>()
    .collectList()
    .block()
```

**응답:**
```json
{
  "status": "0000",
  "data": [
    {
      "market": "KRW-BTC",
      "korean_name": "비트코인",
      "english_name": "Bitcoin",
      "market_warning": "NONE"
    },
    {
      "market": "KRW-ETH",
      "korean_name": "이더리움",
      "english_name": "Ethereum",
      "market_warning": "CAUTION"
    }
  ]
}
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `market` | 마켓 코드 |
| `korean_name` | 한글 종목명 |
| `english_name` | 영문 종목명 |
| `market_warning` | 투자유의 상태 (`NONE`, `CAUTION`) |

---

### 2.3 현재가 조회 (Ticker)

```
GET /v1/ticker?markets={market_codes}
```

**요청:**
```kotlin
// Kotlin + Spring WebClient
val response = webClient.get()
    .uri("/v1/ticker?markets=KRW-BTC,KRW-ETH")
    .retrieve()
    .bodyToMono<TickerResponse>()
    .block()
```

**응답:**
```json
{
  "status": "0000",
  "data": {
    "KRW-BTC": {
      "opening_price": "50000000",
      "closing_price": "50500000",
      "min_price": "49800000",
      "max_price": "50600000",
      "acc_trade_volume_24h": "1234.5678",
      "acc_trade_price_24h": "62000000000",
      "prev_closing_price": "50000000",
      "24_hour_fluctuation": "500000",
      "24_hour_fluctuation_percentage": "1.0",
      "timestamp": 1704109200000
    }
  }
}
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `opening_price` | 시가 |
| `closing_price` | 현재가 (종가) |
| `min_price` | 저가 |
| `max_price` | 고가 |
| `acc_trade_volume_24h` | 24시간 거래량 |
| `acc_trade_price_24h` | 24시간 거래대금 |
| `prev_closing_price` | 전일 종가 |
| `24_hour_fluctuation` | 24시간 변동 금액 |
| `24_hour_fluctuation_percentage` | 24시간 변동률 (%) |

---

### 2.4 호가 정보 조회 (Orderbook)

```
GET /v1/orderbook?markets={market_codes}
```

**요청:**
```kotlin
val response = webClient.get()
    .uri("/v1/orderbook?markets=KRW-BTC")
    .retrieve()
    .bodyToMono<List<OrderbookResponse>>()
    .block()
```

**응답:**
```json
[
  {
    "market": "KRW-BTC",
    "timestamp": 1678886400000,
    "total_ask_size": 10.5,
    "total_bid_size": 5.2,
    "orderbook_units": [
      {
        "ask_price": 50100000,
        "bid_price": 50000000,
        "ask_size": 1.2,
        "bid_size": 0.8
      }
    ]
  }
]
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `total_ask_size` | 총 매도 잔량 |
| `total_bid_size` | 총 매수 잔량 |
| `ask_price` | 매도 호가 |
| `bid_price` | 매수 호가 |
| `ask_size` | 매도 잔량 |
| `bid_size` | 매수 잔량 |

---

### 2.5 체결 내역 조회 (Trades/Ticks)

```
GET /v1/trades/ticks?market={market}&count={count}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | O | 마켓 코드 (예: KRW-BTC) |
| `count` | X | 조회 개수 (1-500, 기본값 1) |
| `to` | X | 마지막 체결 시간 (HHmmss) |
| `daysAgo` | X | 과거 데이터 조회 (1-7일) |

**응답:**
```json
[
  {
    "market": "KRW-BTC",
    "trade_date_utc": "2024-01-01",
    "trade_time_utc": "10:30:00",
    "timestamp": 1704109800000,
    "trade_price": 50000000.0,
    "trade_volume": 0.01,
    "prev_closing_price": 49900000.0,
    "change_price": 100000.0,
    "ask_bid": "ASK",
    "sequential_id": 1234567890
  }
]
```

---

### 2.6 캔들 (OHLCV) 조회

#### 분봉

```
GET /v1/candles/minutes/{unit}?market={market}&count={count}
```

**Path 파라미터:**
| 파라미터 | 값 |
|---------|-----|
| `unit` | 1, 3, 5, 10, 15, 30, 60, 240 |

**Query 파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | O | 마켓 코드 |
| `count` | X | 조회 개수 (최대 200) |
| `to` | X | 마지막 캔들 시간 (yyyy-MM-dd HH:mm:ss) |

**요청:**
```kotlin
// 1분봉 200개 조회
val candles = webClient.get()
    .uri("/v1/candles/minutes/1?market=KRW-BTC&count=200")
    .retrieve()
    .bodyToFlux<CandleResponse>()
    .collectList()
    .block()
```

**응답:**
```json
[
  {
    "market": "KRW-BTC",
    "candle_date_time_utc": "2024-01-01T10:00:00",
    "candle_date_time_kst": "2024-01-01T19:00:00",
    "opening_price": 50000000,
    "high_price": 50500000,
    "low_price": 49800000,
    "trade_price": 50200000,
    "timestamp": 1704109200000,
    "candle_acc_trade_price": 10000000000,
    "candle_acc_trade_volume": 200,
    "unit": 1
  }
]
```

#### 일봉

```
GET /v1/candles/days?market={market}&count={count}
```

#### 주봉

```
GET /v1/candles/weeks?market={market}&count={count}
```

#### 월봉

```
GET /v1/candles/months?market={market}&count={count}
```

---

## 3. JWT 인증 (Private API)

### 3.1 인증 흐름

```
1. Query Parameters → URL Encode
2. URL Encoded Query → SHA-512 Hash
3. JWT Payload 생성 (access_key, nonce, timestamp, query_hash, query_hash_alg)
4. JWT 서명 (HMAC-SHA256 with Secret Key)
5. Authorization Header: "Bearer {JWT_TOKEN}"
```

### 3.2 JWT Payload 구조

```json
{
  "access_key": "발급받은 API KEY",
  "nonce": "UUID v4",
  "timestamp": 1704109200000,
  "query_hash": "SHA-512 해시값",
  "query_hash_alg": "SHA512"
}
```

### 3.3 Kotlin 구현

```kotlin
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

class BithumbAuthService(
    private val accessKey: String,
    private val secretKey: String
) {
    /**
     * Query 파라미터가 없는 경우 (GET /v1/accounts 등)
     */
    fun generateToken(): String {
        val algorithm = Algorithm.HMAC256(secretKey)
        return JWT.create()
            .withClaim("access_key", accessKey)
            .withClaim("nonce", UUID.randomUUID().toString())
            .withClaim("timestamp", System.currentTimeMillis())
            .sign(algorithm)
    }

    /**
     * Query 파라미터가 있는 경우
     */
    fun generateTokenWithQuery(queryParams: Map<String, Any>): String {
        // 1. Query String 생성
        val query = queryParams.entries
            .joinToString("&") { "${it.key}=${it.value}" }

        // 2. SHA-512 해시
        val md = MessageDigest.getInstance("SHA-512")
        md.update(query.toByteArray(StandardCharsets.UTF_8))
        val queryHash = String.format("%0128x", BigInteger(1, md.digest()))

        // 3. JWT 생성
        val algorithm = Algorithm.HMAC256(secretKey)
        return JWT.create()
            .withClaim("access_key", accessKey)
            .withClaim("nonce", UUID.randomUUID().toString())
            .withClaim("timestamp", System.currentTimeMillis())
            .withClaim("query_hash", queryHash)
            .withClaim("query_hash_alg", "SHA512")
            .sign(algorithm)
    }

    /**
     * Authorization 헤더 값 생성
     */
    fun getAuthorizationHeader(queryParams: Map<String, Any>? = null): String {
        val token = if (queryParams.isNullOrEmpty()) {
            generateToken()
        } else {
            generateTokenWithQuery(queryParams)
        }
        return "Bearer $token"
    }
}
```

### 3.4 Spring WebClient 설정

```kotlin
@Configuration
class BithumbApiConfig(
    @Value("\${bithumb.access-key}") private val accessKey: String,
    @Value("\${bithumb.secret-key}") private val secretKey: String
) {
    @Bean
    fun bithumbWebClient(): WebClient {
        return WebClient.builder()
            .baseUrl("https://api.bithumb.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    @Bean
    fun bithumbAuthService(): BithumbAuthService {
        return BithumbAuthService(accessKey, secretKey)
    }
}
```

---

## 4. Private API (인증 필요)

### 4.1 잔고 조회

```
GET /v1/accounts
Authorization: Bearer {JWT_TOKEN}
```

**요청:**
```kotlin
val token = authService.getAuthorizationHeader()

val accounts = webClient.get()
    .uri("/v1/accounts")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToFlux<AccountResponse>()
    .collectList()
    .block()
```

**응답:**
```json
[
  {
    "currency": "KRW",
    "balance": "10000000.0",
    "locked": "500000.0",
    "avg_buy_price": "0",
    "avg_buy_price_modified": false,
    "unit_currency": "KRW"
  },
  {
    "currency": "BTC",
    "balance": "0.5",
    "locked": "0.1",
    "avg_buy_price": "50000000",
    "avg_buy_price_modified": false,
    "unit_currency": "KRW"
  }
]
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `currency` | 화폐 코드 |
| `balance` | 주문 가능 수량 |
| `locked` | 주문 중 묶인 수량 |
| `avg_buy_price` | 평균 매수가 |
| `unit_currency` | 평단가 기준 화폐 |

---

### 4.2 주문하기

```
POST /v1/orders
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json
```

**요청 파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | O | 마켓 코드 (예: KRW-BTC) |
| `side` | O | 주문 종류 (`bid`: 매수, `ask`: 매도) |
| `volume` | △ | 주문량 (지정가/시장가 매도 시 필수) |
| `price` | △ | 주문 가격 (지정가/시장가 매수 시 필수) |
| `ord_type` | O | 주문 타입 (`limit`: 지정가, `market`: 시장가) |

**지정가 매수:**
```kotlin
val orderParams = mapOf(
    "market" to "KRW-BTC",
    "side" to "bid",
    "volume" to "0.001",
    "price" to "50000000",
    "ord_type" to "limit"
)

val token = authService.getAuthorizationHeader(orderParams)

val response = webClient.post()
    .uri("/v1/orders")
    .header(HttpHeaders.AUTHORIZATION, token)
    .bodyValue(orderParams)
    .retrieve()
    .bodyToMono<OrderResponse>()
    .block()
```

**시장가 매수 (총액 지정):**
```kotlin
val orderParams = mapOf(
    "market" to "KRW-BTC",
    "side" to "bid",
    "price" to "100000",      // 100,000원어치 매수
    "ord_type" to "market"
)
```

**시장가 매도 (수량 지정):**
```kotlin
val orderParams = mapOf(
    "market" to "KRW-BTC",
    "side" to "ask",
    "volume" to "0.001",      // 0.001 BTC 매도
    "ord_type" to "market"
)
```

**응답:**
```json
{
  "uuid": "C0106000032400700021",
  "side": "bid",
  "ord_type": "limit",
  "price": "50000000",
  "state": "wait",
  "market": "KRW-BTC",
  "created_at": "2024-01-01T10:00:00+09:00",
  "volume": "0.001",
  "remaining_volume": "0.001",
  "executed_volume": "0",
  "trades_count": 0
}
```

---

### 4.3 주문 취소

```
DELETE /v1/order?uuid={order_uuid}
Authorization: Bearer {JWT_TOKEN}
```

**요청:**
```kotlin
val queryParams = mapOf("uuid" to "C0106000032400700021")
val token = authService.getAuthorizationHeader(queryParams)

val response = webClient.delete()
    .uri("/v1/order?uuid=${queryParams["uuid"]}")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToMono<CancelOrderResponse>()
    .block()
```

**응답:**
```json
{
  "uuid": "C0106000032400700021",
  "side": "bid",
  "ord_type": "limit",
  "price": "50000000",
  "state": "cancel",
  "market": "KRW-BTC",
  "volume": "0.001",
  "remaining_volume": "0.001",
  "executed_volume": "0"
}
```

---

### 4.4 주문 조회

#### 개별 주문 조회

```
GET /v1/order?uuid={order_uuid}
Authorization: Bearer {JWT_TOKEN}
```

#### 주문 리스트 조회

```
GET /v1/orders?market={market}&state={state}&limit={limit}&page={page}
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | X | 마켓 코드 |
| `state` | X | 주문 상태 (`wait`, `done`, `cancel`) |
| `limit` | X | 조회 개수 (기본 100) |
| `page` | X | 페이지 번호 |
| `order_by` | X | 정렬 (`asc`, `desc`) |

**요청:**
```kotlin
val queryParams = mapOf(
    "market" to "KRW-BTC",
    "state" to "wait",
    "limit" to "100",
    "page" to "1",
    "order_by" to "desc"
)

val queryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
val token = authService.getAuthorizationHeader(queryParams)

val orders = webClient.get()
    .uri("/v1/orders?$queryString")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToFlux<OrderResponse>()
    .collectList()
    .block()
```

---

### 4.5 주문 가능 정보 조회

```
GET /v1/orders/chance?market={market}
Authorization: Bearer {JWT_TOKEN}
```

**응답:**
```json
{
  "bid_fee": "0.0004",
  "ask_fee": "0.0004",
  "market": {
    "id": "KRW-BTC",
    "name": "BTC/KRW",
    "order_types": ["limit", "market"],
    "order_sides": ["ask", "bid"],
    "bid": {
      "currency": "KRW",
      "min_total": "1000"
    },
    "ask": {
      "currency": "BTC",
      "min_total": "1000"
    }
  },
  "bid_account": {
    "currency": "KRW",
    "balance": "10000000",
    "locked": "0"
  },
  "ask_account": {
    "currency": "BTC",
    "balance": "0.5",
    "locked": "0"
  }
}
```

---

## 5. 입출금 API (인증 필요)

### 5.1 코인 입금 리스트 조회

```
GET /v1/deposits?currency={currency}&state={state}&limit={limit}&page={page}
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `currency` | O | 화폐 코드 (예: BTC, ETH) |
| `state` | X | 입금 상태 |
| `uuids[]` | X | 입금 UUID 리스트 |
| `txids[]` | X | 트랜잭션 ID 리스트 |
| `limit` | X | 조회 개수 (기본 100, 최대 100) |
| `page` | X | 페이지 번호 (기본 1) |
| `order_by` | X | 정렬 (`asc`, `desc`, 기본 `desc`) |

**입금 상태 값:**
| 상태 | 설명 |
|------|------|
| `DEPOSIT_ACCEPTED` | 입금 완료 |
| `DEPOSIT_PROCESSING` | 입금 대기 중 |
| `DEPOSIT_CANCELLED` | 입금 취소 |

**응답:**
```json
[
  {
    "type": "deposit",
    "uuid": "b01f1b1a-7a1a-4a1a-8a1a-1a1a1a1a1a1a",
    "currency": "BTC",
    "net_type": "BTC",
    "txid": "a1b2c3d4e5f67890",
    "state": "DEPOSIT_ACCEPTED",
    "created_at": "2024-01-01T10:00:00Z",
    "done_at": "2024-01-01T10:05:00Z",
    "amount": "1.00000000",
    "fee": "0.00000000",
    "transaction_type": "general"
  }
]
```

---

### 5.2 원화 입금 리스트 조회

```
GET /v1/deposits/krw?limit={limit}&page={page}
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `limit` | X | 조회 개수 |
| `page` | X | 페이지 번호 |
| `order_by` | X | 정렬 |
| `uuids[]` | X | UUID 필터 |

---

### 5.3 전체 입금 주소 조회

```
GET /v1/deposits/coin_addresses
Authorization: Bearer {JWT_TOKEN}
```

**요청:**
```kotlin
val token = authService.getAuthorizationHeader()

val addresses = webClient.get()
    .uri("/v1/deposits/coin_addresses")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToFlux<DepositAddress>()
    .collectList()
    .block()
```

**응답:**
```json
[
  {
    "currency": "BTC",
    "net_type": "BTC",
    "deposit_address": "13qdmpBMQVnwF8UGCARw7mfrP6rernoGUW",
    "secondary_address": null
  },
  {
    "currency": "XRP",
    "net_type": "XRP",
    "deposit_address": "rs6qtDrs8qp5qLkUxuzQdxFh5eGrQbL3Ee",
    "secondary_address": "1004775304"
  }
]
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `currency` | 화폐 코드 |
| `net_type` | 네트워크 타입 |
| `deposit_address` | 입금 주소 |
| `secondary_address` | 2차 주소 (XRP 태그 등) |

---

### 5.4 코인 출금 리스트 조회

```
GET /v1/withdraws?currency={currency}&state={state}&limit={limit}
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `currency` | O | 화폐 코드 |
| `state` | X | 출금 상태 (`PROCESSING`, `DONE`, `CANCELED`) |
| `uuids[]` | X | 출금 UUID 리스트 |
| `txids[]` | X | 트랜잭션 ID 리스트 |
| `limit` | X | 조회 개수 (기본 100) |
| `page` | X | 페이지 번호 |
| `order_by` | X | 정렬 |

**응답:**
```json
[
  {
    "type": "withdraw",
    "uuid": "200268229",
    "currency": "XRP",
    "net_type": "XRP",
    "txid": "12345abcde",
    "state": "DONE",
    "created_at": "2024-01-01T10:00:00Z",
    "done_at": "2024-01-01T10:05:00Z",
    "amount": "100.50000000",
    "fee": "0.10000000",
    "transaction_type": "default"
  }
]
```

---

### 5.5 원화 출금 리스트 조회

```
GET /v1/withdraws/krw?limit={limit}&page={page}
Authorization: Bearer {JWT_TOKEN}
```

---

### 5.6 코인 출금하기

```
POST /v1/withdraws/coin
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json
```

**요청 파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `currency` | O | 화폐 코드 (예: XRP) |
| `net_type` | O | 네트워크 타입 (예: XRP) |
| `amount` | O | 출금 수량 |
| `address` | O | 출금 주소 |
| `secondary_address` | X | 2차 주소 (XRP 태그 등) |

**요청:**
```kotlin
val withdrawParams = mapOf(
    "currency" to "XRP",
    "net_type" to "XRP",
    "amount" to "100",
    "address" to "rN7n3...",
    "secondary_address" to "12345678"
)

val token = authService.getAuthorizationHeader(withdrawParams)

val response = webClient.post()
    .uri("/v1/withdraws/coin")
    .header(HttpHeaders.AUTHORIZATION, token)
    .bodyValue(withdrawParams)
    .retrieve()
    .bodyToMono<WithdrawResponse>()
    .block()
```

---

### 5.7 원화 출금하기

```
POST /v1/withdraws/krw
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json
```

**요청 파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `amount` | O | 출금 금액 (KRW) |
| `two_factor_type` | O | 2차 인증 타입 (예: `kakao`) |

**요청:**
```kotlin
val withdrawParams = mapOf(
    "amount" to "60000",
    "two_factor_type" to "kakao"
)

val token = authService.getAuthorizationHeader(withdrawParams)

val response = webClient.post()
    .uri("/v1/withdraws/krw")
    .header(HttpHeaders.AUTHORIZATION, token)
    .bodyValue(withdrawParams)
    .retrieve()
    .bodyToMono<WithdrawResponse>()
    .block()
```

**응답:**
```json
{
  "type": "withdraw",
  "uuid": "12704033",
  "currency": "KRW",
  "net_type": null,
  "txid": "1597452",
  "state": "PROCESSING",
  "created_at": "2024-01-01T10:00:00+09:00",
  "done_at": null,
  "amount": "60000",
  "fee": "1000",
  "transaction_type": "default"
}
```

---

### 5.8 출금 허용 주소 리스트 조회

100만원 이상 출금 시 등록된 주소만 가능. 등록된 출금 허용 주소 목록을 조회한다.

```
GET /v1/withdraws/coin_addresses
Authorization: Bearer {JWT_TOKEN}
```

**응답:**
```json
[
  {
    "currency": "ETH",
    "net_type": "ETH",
    "network_name": "Ethereum",
    "withdraw_address": "0x569ece3d6cd807a31b1a2d85ebfee79f89fe0b87",
    "secondary_address": null,
    "exchange_name": "Binance",
    "owner_type": "personal",
    "owner_ko_name": "홍길동",
    "owner_en_name": "GIL DONG HONG"
  }
]
```

---

## 6. 서비스 정보 API (인증 필요)

### 6.1 API 키 리스트 조회

```
GET /v1/api_keys
Authorization: Bearer {JWT_TOKEN}
```

**요청:**
```kotlin
val token = authService.getAuthorizationHeader()

val apiKeys = webClient.get()
    .uri("/v1/api_keys")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToFlux<ApiKeyInfo>()
    .collectList()
    .block()
```

**응답:**
```json
[
  {
    "access_key": "59683c90185742d69fd8fa1bc0cf27785c392afaa56ece",
    "expire_at": "2025-06-11T09:00:00+09:00"
  },
  {
    "access_key": "3e97926e9b75a6aeb637d2c172a292588502daccfb5cab",
    "expire_at": "2025-06-12T09:00:00+09:00"
  }
]
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `access_key` | API 액세스 키 |
| `expire_at` | API 키 만료 일시 |

---

## 7. TWAP 알고리즘 주문 API (인증 필요)

TWAP (Time-Weighted Average Price): 시간 가중 평균 가격으로 대량 주문을 일정 시간 동안 분할 체결하는 알고리즘 주문.

### 7.1 TWAP 주문내역 조회

```
GET /v1/twap
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | X | 마켓 ID (예: KRW-BTC) |
| `uuids[]` | X | TWAP 주문 ID 목록 |
| `state` | X | 주문 상태 (`progress`, `done`, `cancel`) |
| `next_key` | X | 다음 페이지 커서 값 |
| `limit` | X | 조회 개수 (기본값: 100) |
| `order_by` | X | 정렬 (`asc`, `desc`) |

**주문 상태:**
| 상태 | 설명 |
|------|------|
| `progress` | 진행 중 (기본값) |
| `done` | 완료 |
| `cancel` | 취소 |

**요청:**
```kotlin
val queryParams = mapOf(
    "market" to "KRW-BTC",
    "state" to "progress",
    "limit" to "100"
)

val queryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
val token = authService.getAuthorizationHeader(queryParams)

val twapOrders = webClient.get()
    .uri("/v1/twap?$queryString")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToMono<TwapOrderListResponse>()
    .block()
```

**응답 필드:**
| 필드 | 설명 |
|------|------|
| `uuid` | TWAP 주문 ID |
| `side` | 주문 종류 (`bid`, `ask`) |
| `price` | 주문 당시 가격 |
| `state` | 주문 상태 |
| `market` | 마켓 코드 |
| `created_at` | 주문 생성 시간 |
| `volume` | 주문 수량 |
| `total_executed_amount` | 총 체결 금액 |
| `total_executed_volume` | 총 체결 수량 |
| `avg_trade_price` | 평균 체결 단가 |
| `has_next` | 다음 페이지 존재 여부 |
| `next_key` | 다음 페이지 커서 값 |

---

### 7.2 TWAP 주문하기

```
POST /v1/twap
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json
```

**요청 파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `market` | O | 마켓 코드 (예: KRW-BTC) |
| `side` | O | 주문 종류 (`bid`: 매수, `ask`: 매도) |
| `volume` | O | 총 주문 수량 |
| `price` | X | 가격 제한 (설정 시 해당 가격 이하/이상에서만 체결) |
| `duration` | O | 실행 기간 (분 단위) |

**요청:**
```kotlin
val twapParams = mapOf(
    "market" to "KRW-BTC",
    "side" to "bid",
    "volume" to "0.1",
    "duration" to "60"  // 60분 동안 분할 체결
)

val token = authService.getAuthorizationHeader(twapParams)

val response = webClient.post()
    .uri("/v1/twap")
    .header(HttpHeaders.AUTHORIZATION, token)
    .bodyValue(twapParams)
    .retrieve()
    .bodyToMono<TwapOrderResponse>()
    .block()
```

---

### 7.3 TWAP 주문 취소

```
DELETE /v1/twap?uuid={twap_uuid}
Authorization: Bearer {JWT_TOKEN}
```

**파라미터:**
| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `uuid` | O | 취소할 TWAP 주문 ID |

**요청:**
```kotlin
val queryParams = mapOf("uuid" to "twap-order-uuid-12345")
val token = authService.getAuthorizationHeader(queryParams)

val response = webClient.delete()
    .uri("/v1/twap?uuid=${queryParams["uuid"]}")
    .header(HttpHeaders.AUTHORIZATION, token)
    .retrieve()
    .bodyToMono<TwapCancelResponse>()
    .block()
```

---

## 8. 에러 처리

### 8.1 에러 응답 구조

```json
{
  "error": {
    "name": "invalid_request",
    "message": "The request could not be understood due to malformed syntax."
  }
}
```

### 8.2 주요 에러 코드

| 에러 이름 | 설명 |
|----------|------|
| `invalid_request` | 잘못된 요청 |
| `invalid_parameter` | 잘못된 파라미터 |
| `invalid_access_key` | 잘못된 API 키 |
| `jwt_verification_failed` | JWT 검증 실패 |
| `expired_access_key` | 만료된 API 키 |
| `nonce_used` | 이미 사용된 nonce |
| `no_authorization_ip` | 허용되지 않은 IP |
| `insufficient_funds` | 잔고 부족 |
| `order_not_found` | 주문 없음 |
| `under_min_total` | 최소 주문 금액 미달 |

### 8.3 Kotlin 에러 처리

```kotlin
data class BithumbError(
    val error: ErrorDetail
) {
    data class ErrorDetail(
        val name: String,
        val message: String
    )
}

fun handleBithumbError(response: ClientResponse): Mono<Throwable> {
    return response.bodyToMono<BithumbError>()
        .map { error ->
            when (error.error.name) {
                "insufficient_funds" -> InsufficientFundsException(error.error.message)
                "order_not_found" -> OrderNotFoundException(error.error.message)
                "jwt_verification_failed" -> AuthenticationException(error.error.message)
                else -> BithumbApiException(error.error.name, error.error.message)
            }
        }
}
```

---

## 9. Data Classes (Kotlin)

```kotlin
// 현재가 정보
data class Ticker(
    @JsonProperty("opening_price") val openingPrice: BigDecimal,
    @JsonProperty("closing_price") val closingPrice: BigDecimal,
    @JsonProperty("min_price") val minPrice: BigDecimal,
    @JsonProperty("max_price") val maxPrice: BigDecimal,
    @JsonProperty("acc_trade_volume_24h") val accTradeVolume24h: BigDecimal,
    @JsonProperty("acc_trade_price_24h") val accTradePrice24h: BigDecimal,
    @JsonProperty("prev_closing_price") val prevClosingPrice: BigDecimal,
    val timestamp: Long
)

// 호가 정보
data class Orderbook(
    val market: String,
    val timestamp: Long,
    @JsonProperty("total_ask_size") val totalAskSize: BigDecimal,
    @JsonProperty("total_bid_size") val totalBidSize: BigDecimal,
    @JsonProperty("orderbook_units") val orderbookUnits: List<OrderbookUnit>
)

data class OrderbookUnit(
    @JsonProperty("ask_price") val askPrice: BigDecimal,
    @JsonProperty("bid_price") val bidPrice: BigDecimal,
    @JsonProperty("ask_size") val askSize: BigDecimal,
    @JsonProperty("bid_size") val bidSize: BigDecimal
)

// 캔들 정보
data class Candle(
    val market: String,
    @JsonProperty("candle_date_time_utc") val candleDateTimeUtc: String,
    @JsonProperty("candle_date_time_kst") val candleDateTimeKst: String,
    @JsonProperty("opening_price") val openingPrice: BigDecimal,
    @JsonProperty("high_price") val highPrice: BigDecimal,
    @JsonProperty("low_price") val lowPrice: BigDecimal,
    @JsonProperty("trade_price") val tradePrice: BigDecimal,
    val timestamp: Long,
    @JsonProperty("candle_acc_trade_price") val candleAccTradePrice: BigDecimal,
    @JsonProperty("candle_acc_trade_volume") val candleAccTradeVolume: BigDecimal
)

// 계좌 정보
data class Account(
    val currency: String,
    val balance: BigDecimal,
    val locked: BigDecimal,
    @JsonProperty("avg_buy_price") val avgBuyPrice: BigDecimal,
    @JsonProperty("avg_buy_price_modified") val avgBuyPriceModified: Boolean,
    @JsonProperty("unit_currency") val unitCurrency: String
)

// 주문 정보
data class Order(
    val uuid: String,
    val side: String,           // bid, ask
    @JsonProperty("ord_type") val ordType: String,  // limit, market
    val price: BigDecimal?,
    val state: String,          // wait, done, cancel
    val market: String,
    @JsonProperty("created_at") val createdAt: String,
    val volume: BigDecimal,
    @JsonProperty("remaining_volume") val remainingVolume: BigDecimal,
    @JsonProperty("executed_volume") val executedVolume: BigDecimal,
    @JsonProperty("trades_count") val tradesCount: Int
)

// 마켓 정보
data class MarketInfo(
    val market: String,
    @JsonProperty("korean_name") val koreanName: String,
    @JsonProperty("english_name") val englishName: String,
    @JsonProperty("market_warning") val marketWarning: String  // NONE, CAUTION
)

// 입금 주소
data class DepositAddress(
    val currency: String,
    @JsonProperty("net_type") val netType: String,
    @JsonProperty("deposit_address") val depositAddress: String,
    @JsonProperty("secondary_address") val secondaryAddress: String?
)

// 입금 내역
data class Deposit(
    val type: String,
    val uuid: String,
    val currency: String,
    @JsonProperty("net_type") val netType: String,
    val txid: String,
    val state: String,
    @JsonProperty("created_at") val createdAt: String,
    @JsonProperty("done_at") val doneAt: String?,
    val amount: BigDecimal,
    val fee: BigDecimal,
    @JsonProperty("transaction_type") val transactionType: String
)

// 출금 내역
data class Withdraw(
    val type: String,
    val uuid: String,
    val currency: String,
    @JsonProperty("net_type") val netType: String?,
    val txid: String?,
    val state: String,          // PROCESSING, DONE, CANCELED
    @JsonProperty("created_at") val createdAt: String,
    @JsonProperty("done_at") val doneAt: String?,
    val amount: BigDecimal,
    val fee: BigDecimal,
    @JsonProperty("transaction_type") val transactionType: String
)

// 출금 허용 주소
data class WithdrawAddress(
    val currency: String,
    @JsonProperty("net_type") val netType: String,
    @JsonProperty("network_name") val networkName: String,
    @JsonProperty("withdraw_address") val withdrawAddress: String,
    @JsonProperty("secondary_address") val secondaryAddress: String?,
    @JsonProperty("exchange_name") val exchangeName: String?,
    @JsonProperty("owner_type") val ownerType: String,
    @JsonProperty("owner_ko_name") val ownerKoName: String?,
    @JsonProperty("owner_en_name") val ownerEnName: String?
)

// API 키 정보
data class ApiKeyInfo(
    @JsonProperty("access_key") val accessKey: String,
    @JsonProperty("expire_at") val expireAt: String
)

// TWAP 주문 정보
data class TwapOrder(
    val uuid: String,
    val side: String,           // bid, ask
    val price: BigDecimal?,
    val state: String,          // progress, done, cancel
    val market: String,
    @JsonProperty("created_at") val createdAt: String,
    val volume: BigDecimal,
    @JsonProperty("total_executed_amount") val totalExecutedAmount: BigDecimal,
    @JsonProperty("total_executed_volume") val totalExecutedVolume: BigDecimal,
    @JsonProperty("avg_trade_price") val avgTradePrice: BigDecimal
)

// TWAP 주문 리스트 응답
data class TwapOrderListResponse(
    val orders: List<TwapOrder>,
    @JsonProperty("has_next") val hasNext: Boolean,
    @JsonProperty("next_key") val nextKey: String?
)
```

---

## 10. 주의사항 및 Best Practices

### 10.1 API Rate Limit

- Public API: 초당 10회
- Private API: 초당 8회
- 초과 시 429 Too Many Requests 응답

### 10.2 주문 관련

- **최소 주문 금액**: 1,000 KRW
- **수수료**: 0.04% (기본)
- **지정가 주문**: volume, price 모두 필수
- **시장가 매수**: price만 필수 (총액)
- **시장가 매도**: volume만 필수 (수량)

### 10.3 인증 관련

- **nonce**: 매 요청마다 고유한 UUID 사용
- **timestamp**: 밀리초 단위 Unix timestamp
- **IP 제한**: API 키 생성 시 허용 IP 설정 권장
- **Query Hash**: 파라미터가 있는 경우 반드시 포함

### 10.4 시간대

- API 응답 시간은 기본적으로 **UTC** 기준
- KST(한국 시간) = UTC + 9시간
- 캔들의 `to` 파라미터는 KST 기준

### 10.5 숫자 처리

- 모든 금액/수량은 **BigDecimal** 사용 권장
- JSON 응답의 숫자는 문자열로 오는 경우가 많음
- 부동소수점 오차 방지를 위해 문자열 → BigDecimal 변환

---

## 11. 완전한 서비스 구현 예시

```kotlin
@Service
class BithumbTradingService(
    private val webClient: WebClient,
    private val authService: BithumbAuthService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BithumbTradingService::class.java)
        const val FEE_RATE = 0.0004  // 0.04%
    }

    /**
     * 현재가 조회
     */
    suspend fun getTicker(market: String): Ticker {
        return webClient.get()
            .uri("/v1/ticker?markets=$market")
            .retrieve()
            .awaitBody()
    }

    /**
     * 호가 조회
     */
    suspend fun getOrderbook(market: String): Orderbook {
        return webClient.get()
            .uri("/v1/orderbook?markets=$market")
            .retrieve()
            .awaitBody<List<Orderbook>>()
            .first()
    }

    /**
     * 잔고 조회
     */
    suspend fun getAccounts(): List<Account> {
        val token = authService.getAuthorizationHeader()
        return webClient.get()
            .uri("/v1/accounts")
            .header(HttpHeaders.AUTHORIZATION, token)
            .retrieve()
            .awaitBody()
    }

    /**
     * 지정가 매수
     */
    suspend fun buyLimit(market: String, price: BigDecimal, volume: BigDecimal): Order {
        val params = mapOf(
            "market" to market,
            "side" to "bid",
            "volume" to volume.toPlainString(),
            "price" to price.toPlainString(),
            "ord_type" to "limit"
        )
        return placeOrder(params)
    }

    /**
     * 지정가 매도
     */
    suspend fun sellLimit(market: String, price: BigDecimal, volume: BigDecimal): Order {
        val params = mapOf(
            "market" to market,
            "side" to "ask",
            "volume" to volume.toPlainString(),
            "price" to price.toPlainString(),
            "ord_type" to "limit"
        )
        return placeOrder(params)
    }

    /**
     * 시장가 매수 (총액 지정)
     */
    suspend fun buyMarket(market: String, totalKrw: BigDecimal): Order {
        val params = mapOf(
            "market" to market,
            "side" to "bid",
            "price" to totalKrw.toPlainString(),
            "ord_type" to "market"
        )
        return placeOrder(params)
    }

    /**
     * 시장가 매도 (수량 지정)
     */
    suspend fun sellMarket(market: String, volume: BigDecimal): Order {
        val params = mapOf(
            "market" to market,
            "side" to "ask",
            "volume" to volume.toPlainString(),
            "ord_type" to "market"
        )
        return placeOrder(params)
    }

    /**
     * 주문 취소
     */
    suspend fun cancelOrder(uuid: String): Order {
        val params = mapOf("uuid" to uuid)
        val token = authService.getAuthorizationHeader(params)

        return webClient.delete()
            .uri("/v1/order?uuid=$uuid")
            .header(HttpHeaders.AUTHORIZATION, token)
            .retrieve()
            .awaitBody()
    }

    /**
     * 미체결 주문 조회
     */
    suspend fun getOpenOrders(market: String): List<Order> {
        val params = mapOf(
            "market" to market,
            "state" to "wait",
            "order_by" to "desc"
        )
        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val token = authService.getAuthorizationHeader(params)

        return webClient.get()
            .uri("/v1/orders?$queryString")
            .header(HttpHeaders.AUTHORIZATION, token)
            .retrieve()
            .awaitBody()
    }

    private suspend fun placeOrder(params: Map<String, String>): Order {
        val token = authService.getAuthorizationHeader(params)

        return webClient.post()
            .uri("/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(params)
            .retrieve()
            .awaitBody()
    }
}
```

---

## 참고 문서

- [Bithumb API 공식 문서](https://apidocs.bithumb.com/)
- [인증 헤더 만들기](https://apidocs.bithumb.com/docs/인증-헤더-만들기)
- [시세 API](https://apidocs.bithumb.com/reference/현재가-정보)
- [주문 API](https://apidocs.bithumb.com/reference/주문하기)
- [자산 API](https://apidocs.bithumb.com/reference/자산)
