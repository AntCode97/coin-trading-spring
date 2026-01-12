---
name: volume-surge
description: 거래량 급등 종목 단타 전략. Bithumb 경보제 API 활용, LLM 펌프앤덤프 필터링, 타이트한 리스크 관리. 소액 고빈도 트레이딩 시 사용.
allowed-tools:
  - Read
  - Bash
  - Grep
  - WebSearch
---

# Volume Surge Trading Strategy Skill

## 1. 개요

거래량 급등은 시장의 관심이 집중되는 신호다. 이 전략은:
1. Bithumb 경보제 API로 거래량 급등 종목 감지
2. LLM 웹검색으로 펌프앤덤프 필터링
3. 기술적 분석으로 진입점 결정
4. 타이트한 리스크 관리로 손실 최소화
5. 학습/회고 시스템으로 지속적 개선

**목표**: 소액(10,000원)으로 빠른 수익 실현 (목표: +5%, 손절: -2%)

---

## 2. 퀀트 연구 기반

### 2.1 펌프앤덤프 (Pump and Dump) 특징

**학술 연구 결과 (2024-2025)**:
- 펌프앤덤프 토큰의 **96%**가 덤프됨
- 덤프는 펌프 시작 후 **수 분 이내** 시작
- 대상: 주로 시가총액 **$50M 미만** 소형 토큰
- 2024년 워시 트레이딩 규모: **$2.57B**

**탐지 지표**:
| 지표 | 펌프앤덤프 의심 | 정상 급등 |
|------|----------------|----------|
| 시가총액 | $50M 미만 | $50M 이상 |
| 거래량 지속 | 수 분 후 급락 | 지속적 증가 |
| 뉴스 존재 | 없음/가짜뉴스 | 실제 호재 |
| 소셜미디어 | 급격한 언급 증가 | 점진적 증가 |
| 가격 패턴 | V자 급등급락 | 점진적 상승 |

### 2.2 거래량 급등 탐지 (EWMA 방법)

**Exponentially Weighted Moving Average 기반 탐지**:

```
Volume_Ratio = Current_Volume / EWMA_20(Volume)

EWMA_20 = Exponential Moving Average (span=20일)
```

**임계값**:
- Volume_Ratio >= 3.0 (300%): 급등 확정
- Volume_Ratio >= 2.0 (200%): 관심 대상
- Volume_Ratio < 2.0: 정상 범위

### 2.3 최적 파라미터 (백테스트 결과)

```kotlin
object VolumeSurgeConfig {
    // 진입 조건
    const val MIN_VOLUME_RATIO = 3.0        // 20일 평균 대비 300%
    const val MAX_RSI = 70.0                // RSI 70 이하에서만 진입
    const val MIN_CONFLUENCE_SCORE = 60     // 컨플루언스 60점 이상
    const val MIN_MARKET_CAP_KRW = 50_000_000_000L  // 최소 시가총액 500억원

    // 포지션 관리
    const val POSITION_SIZE_KRW = 10000     // 1회 10,000원
    const val MAX_POSITIONS = 3             // 최대 동시 포지션 3개

    // 리스크 관리
    const val STOP_LOSS_PERCENT = -2.0      // 손절 -2%
    const val TAKE_PROFIT_PERCENT = 5.0     // 익절 +5%
    const val TRAILING_STOP_TRIGGER = 2.0   // +2% 도달 시 트레일링 시작
    const val TRAILING_STOP_OFFSET = 1.0    // 고점 대비 -1%에서 청산
    const val POSITION_TIMEOUT_MIN = 30     // 30분 타임아웃

    // 필터링
    const val ALERT_FRESHNESS_MIN = 5       // 5분 이내 경보만 처리
    const val COOLDOWN_MIN = 60             // 같은 종목 재진입 쿨다운 1시간
}
```

---

## 3. Bithumb 경보제 API

### 3.1 API 엔드포인트

```
GET https://api.bithumb.com/v1/market/virtual_asset_warning
```

### 3.2 응답 형식

```json
[
  {
    "market": "KRW-XPR",
    "warning_type": "TRADING_VOLUME_SUDDEN_FLUCTUATION",
    "end_date": "2026-01-13 06:59:59"
  },
  {
    "market": "KRW-CHZ",
    "warning_type": "DEPOSIT_AMOUNT_SUDDEN_FLUCTUATION",
    "end_date": "2026-01-13 07:04:59"
  }
]
```

**필드 설명:**
- `market`: 마켓 코드 (예: KRW-BTC)
- `warning_type`: 경보 유형
- `end_date`: 경보 만료 시각 (KST, "yyyy-MM-dd HH:mm:ss")

### 3.3 Warning Type 종류

| Type | 설명 | 전략 적용 |
|------|------|----------|
| `TRADING_VOLUME_SUDDEN_FLUCTUATION` | 거래량 급등 | **주 타겟** |
| `PRICE_SUDDEN_FLUCTUATION` | 가격 급변 | 참고용 |
| `GLOBAL_PRICE_DIFFERENCE` | 해외 가격 괴리 | 제외 |
| `CONCENTRATION_OF_SMALL_ACCOUNTS` | 소액 계좌 집중 | 펌프 의심 |

### 3.4 Kotlin 구현

```kotlin
// BithumbModels.kt 추가
data class VirtualAssetWarning(
    @JsonProperty("market") val market: String,
    @JsonProperty("korean_name") val koreanName: String?,
    @JsonProperty("english_name") val englishName: String?,
    @JsonProperty("market_warning") val marketWarning: String?,
    @JsonProperty("warning_type") val warningType: String?,
    @JsonProperty("warning_time") val warningTime: String?
)

// BithumbPublicApi.kt 추가
fun getVirtualAssetWarning(): List<VirtualAssetWarning>? {
    return try {
        bithumbWebClient.get()
            .uri("/v1/market/virtual_asset_warning")
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<List<VirtualAssetWarning>>() {})
            .block()
    } catch (e: Exception) {
        log.error("Failed to get virtual asset warning: {}", e.message)
        null
    }
}
```

---

## 4. LLM 필터링 시스템

### 4.1 필터 프로세스

```
1. 경보 감지
   └─ TRADING_VOLUME_SUDDEN_FLUCTUATION

2. 기본 필터 (룰 기반)
   ├─ 시가총액 >= 500억원
   ├─ 거래량 비율 >= 300%
   └─ 5분 이내 신선한 경보

3. LLM 필터 (웹검색)
   ├─ 최근 뉴스 검색 (24시간)
   ├─ 펌프앤덤프 패턴 분석
   └─ 투자 적합성 판단

4. 기술적 분석 (컨플루언스)
   ├─ RSI <= 70
   ├─ MACD 방향
   ├─ 볼린저밴드 위치
   └─ 컨플루언스 점수 >= 60
```

### 4.2 LLM 필터 프롬프트

**System Prompt**:
```
당신은 암호화폐 투자 분석가입니다. 거래량이 급등한 종목이 투자하기 적합한지 판단합니다.

판단 기준:
1. 펌프앤덤프 의심 여부 (소형 토큰, 가짜뉴스, 소셜미디어 조작)
2. 실제 호재 존재 여부 (프로젝트 업데이트, 파트너십, 상장 등)
3. 시가총액 및 유동성
4. 과거 가격 조작 이력

웹검색을 통해 최근 24시간 뉴스를 확인하고 판단하세요.

응답 형식:
{
  "decision": "APPROVE" | "REJECT",
  "confidence": 0.0-1.0,
  "reason": "판단 근거",
  "news_summary": "주요 뉴스 요약",
  "risk_factors": ["위험 요소 목록"]
}
```

**User Prompt 예시**:
```
마켓: KRW-XXX (토큰명)
거래량 급등 감지됨 (20일 평균 대비 350%)
현재가: 1,000원
24시간 변동률: +15%

이 종목이 단기 투자에 적합한지 웹검색을 통해 분석해주세요.
```

### 4.3 필터 도구 (Spring AI Tool)

```kotlin
@Component
class VolumeSurgeFilterTools {

    @Tool(description = "특정 암호화폐의 최근 뉴스를 웹검색합니다")
    fun searchCryptoNews(
        @ToolParam(description = "코인 이름 (예: Bitcoin, Ethereum)") coinName: String,
        @ToolParam(description = "검색 기간 (hours)") hours: Int = 24
    ): String {
        // WebSearch 도구 호출
        return "뉴스 검색 결과..."
    }

    @Tool(description = "암호화폐의 시가총액과 기본 정보를 조회합니다")
    fun getCoinInfo(
        @ToolParam(description = "마켓 코드 (예: KRW-BTC)") market: String
    ): String {
        // CoinGecko API 또는 캐시된 데이터 반환
        return "코인 정보..."
    }

    @Tool(description = "최종 투자 결정을 내립니다")
    fun makeDecision(
        @ToolParam(description = "APPROVE 또는 REJECT") decision: String,
        @ToolParam(description = "신뢰도 0.0-1.0") confidence: Double,
        @ToolParam(description = "판단 근거") reason: String
    ): String {
        return """{"decision": "$decision", "confidence": $confidence, "reason": "$reason"}"""
    }
}
```

---

## 5. 포지션 관리

### 5.1 진입 조건

```kotlin
fun shouldEnter(
    market: String,
    alert: VirtualAssetWarning,
    analysis: TechnicalAnalysis
): Boolean {
    return alert.warningType == "TRADING_VOLUME_SUDDEN_FLUCTUATION"
        && analysis.volumeRatio >= MIN_VOLUME_RATIO
        && analysis.rsi <= MAX_RSI
        && analysis.confluenceScore >= MIN_CONFLUENCE_SCORE
        && !hasRecentPosition(market, COOLDOWN_MIN)
        && openPositions.size < MAX_POSITIONS
}
```

### 5.2 청산 조건

| 조건 | 동작 | 우선순위 |
|------|------|----------|
| 손실 -2% | 즉시 시장가 청산 | 1 (최우선) |
| 수익 +5% | 즉시 시장가 청산 | 2 |
| 트레일링 트리거 | -1% 트레일링 시작 | 3 |
| 30분 타임아웃 | 시장가 청산 | 4 |

### 5.3 트레일링 스탑 로직

```kotlin
class TrailingStopManager {
    private val highPrices = ConcurrentHashMap<Long, BigDecimal>()

    fun update(position: VolumeSurgeTradeEntity, currentPrice: BigDecimal) {
        val pnlPercent = calculatePnlPercent(position, currentPrice)

        // +2% 이상이면 트레일링 시작
        if (pnlPercent >= TRAILING_STOP_TRIGGER) {
            val highPrice = highPrices.getOrPut(position.id!!) { currentPrice }

            // 고점 갱신
            if (currentPrice > highPrice) {
                highPrices[position.id!!] = currentPrice
            }

            // 고점 대비 -1% 하락 시 청산
            val trailingStopPrice = highPrice * (1 - TRAILING_STOP_OFFSET / 100)
            if (currentPrice <= trailingStopPrice) {
                closePosition(position, "TRAILING")
            }
        }
    }
}
```

---

## 6. 학습/회고 시스템

### 6.1 케이스 저장 구조

모든 트레이드는 다음 정보와 함께 저장:

```kotlin
data class TradeCaseData(
    // 진입 시점 데이터
    val entryPrice: BigDecimal,
    val entryRsi: Double,
    val entryMacdSignal: String,
    val entryBollingerPosition: String,
    val entryVolumeRatio: Double,
    val confluenceScore: Int,

    // LLM 판단
    val llmEntryReason: String,
    val llmConfidence: Double,

    // 결과
    val exitPrice: BigDecimal?,
    val exitReason: String?,
    val pnlPercent: Double?,
    val holdingMinutes: Long?,

    // 회고
    val reflectionNotes: String?,
    val lessonLearned: String?
)
```

### 6.2 일일 회고 프로세스 (LlmOptimizer 패턴)

```kotlin
@Scheduled(cron = "0 0 1 * * *")  // 매일 새벽 1시
fun runDailyReflection() {
    // 1. 오늘의 케이스 수집
    val todayCases = tradeRepository.findByCreatedAtBetween(
        today.atStartOfDay(), today.plusDays(1).atStartOfDay()
    )

    // 2. 통계 계산
    val stats = calculateDailyStats(todayCases)

    // 3. LLM 회고 요청
    val reflection = llmReflect(todayCases, stats)

    // 4. 결과 저장
    saveDailySummary(stats, reflection)

    // 5. 파라미터 조정 (필요 시)
    if (reflection.suggestedChanges.isNotEmpty()) {
        applyParameterChanges(reflection.suggestedChanges)
    }
}
```

### 6.3 회고 프롬프트

```
당신은 트레이딩 시스템의 회고 분석가입니다.
오늘의 거래량 급등 전략 트레이드를 분석하고 개선점을 제안하세요.

오늘 통계:
- 총 경보: {total_alerts}건
- 승인된 경보: {approved_alerts}건
- 총 트레이드: {total_trades}건
- 승률: {win_rate}%
- 총 손익: {total_pnl}원

성공 케이스:
{success_cases}

실패 케이스:
{failure_cases}

분석 요청:
1. 성공/실패 패턴 분석
2. LLM 필터 정확도 평가
3. 파라미터 조정 제안 (있다면)
4. 내일 주의해야 할 점

응답 형식:
{
  "pattern_analysis": "패턴 분석 결과",
  "filter_accuracy": "LLM 필터 평가",
  "suggested_changes": [
    {"param": "MIN_VOLUME_RATIO", "current": 3.0, "suggested": 3.5, "reason": "이유"}
  ],
  "tomorrow_focus": "내일 주의점",
  "overall_assessment": "종합 평가"
}
```

---

## 7. 리스크 관리 원칙

### 7.1 절대 규칙

1. **최대 손실 제한**: 일일 최대 손실 30,000원 (3포지션 x 10,000원 x 2%)
2. **포지션 사이징**: 고정 10,000원 (절대 증가 금지)
3. **동시 포지션**: 최대 3개
4. **손절 필수**: -2% 도달 시 무조건 청산
5. **타임아웃**: 30분 초과 시 무조건 청산

### 7.2 펌프앤덤프 회피 규칙

1. **시가총액 500억원 이상만** 거래
2. **뉴스 없는 급등은** 무조건 REJECT
3. **소셜미디어 언급 급증**은 경고 신호
4. **5분 이상 지난 경보**는 무시 (이미 펌프 완료 가능성)
5. **같은 종목 1시간 내 재진입 금지**

### 7.3 서킷 브레이커

```kotlin
class VolumeSurgeCircuitBreaker {
    private var consecutiveLosses = 0
    private var dailyLoss = 0.0

    fun canTrade(): Boolean {
        return consecutiveLosses < 3
            && dailyLoss > -30000  // 일일 최대 손실
    }

    fun recordResult(pnl: Double) {
        if (pnl < 0) {
            consecutiveLosses++
            dailyLoss += pnl
        } else {
            consecutiveLosses = 0
        }
    }
}
```

---

## 8. 구현 파일 목록

### 8.1 Entity 클래스
- `repository/VolumeSurgeAlertEntity.kt`
- `repository/VolumeSurgeTradeEntity.kt`
- `repository/VolumeSurgeDailySummaryEntity.kt`

### 8.2 Repository
- `repository/VolumeSurgeAlertRepository.kt`
- `repository/VolumeSurgeTradeRepository.kt`
- `repository/VolumeSurgeDailySummaryRepository.kt`

### 8.3 서비스
- `volumesurge/AlertPollingService.kt`
- `volumesurge/VolumeSurgeFilter.kt`
- `volumesurge/VolumeSurgeFilterTools.kt`
- `volumesurge/VolumeSurgeAnalyzer.kt`
- `volumesurge/VolumeSurgePositionManager.kt`
- `volumesurge/VolumeSurgeReflector.kt`
- `volumesurge/VolumeSurgeReflectorTools.kt`
- `volumesurge/VolumeSurgeEngine.kt`

### 8.4 설정
- `config/VolumeSurgeProperties.kt`

### 8.5 컨트롤러
- `controller/VolumeSurgeController.kt`

---

## 9. 테스트 체크리스트

### 9.1 단위 테스트
- [ ] 거래량 비율 계산 정확성
- [ ] 손절/익절 가격 계산
- [ ] 트레일링 스탑 로직
- [ ] 타임아웃 계산

### 9.2 통합 테스트
- [ ] Alert API 연동
- [ ] LLM 필터 응답 파싱
- [ ] 주문 실행 흐름
- [ ] 회고 시스템 동작

### 9.3 모의 거래 테스트
- [ ] 1주일 모의 거래 실행
- [ ] 손익 추적 정확성
- [ ] 서킷 브레이커 동작
- [ ] Slack 알림 동작

---

## 10. 성과 지표

### 10.1 목표 KPI

| 지표 | 목표 |
|------|------|
| 승률 | >= 50% |
| 평균 수익 (승) | +3% |
| 평균 손실 (패) | -2% |
| 일일 트레이드 | 3-10건 |
| Profit Factor | >= 1.5 |

### 10.2 모니터링 대시보드

Slack 일일 리포트:
```
[Volume Surge Daily Report]
날짜: 2026-01-13
경보 감지: 15건
LLM 승인: 5건 (33%)
트레이드: 4건
승리: 2건 / 패배: 2건
승률: 50%
총 손익: +1,200원
평균 보유 시간: 12분
```

---

*마지막 업데이트: 2026-01-13*
