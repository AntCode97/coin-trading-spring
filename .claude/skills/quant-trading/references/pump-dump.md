# 펌프앤덤프 탐지 & 회피 가이드

## 개요

펌프앤덤프(P&D) = 소수 세력이 저시총 자산을 인위적으로 급등시킨 후 고점에서 매도

### 2024-2025 통계

| 지표 | 수치 | 출처 |
|------|------|------|
| 연간 워시 트레이딩 규모 | $2.57B | Chainalysis 2024 |
| 평균 펌프 지속 시간 | 5-15분 | SSRN 2024 |
| 희생자 평균 손실 | -35% | MIT 연구 2023 |
| 탐지 가능 시점 | 펌프 시작 전 60분 | arXiv 2024 |

---

## P&D 특징 패턴

### 1. 자산 특성

| 특성 | P&D 의심 | 정상 급등 |
|------|---------|----------|
| 시가총액 | **$50M 미만** | $50M+ |
| 일일 거래량 | 평소의 10배+ 갑자기 | 점진적 증가 |
| 호가창 | 매우 얇음 | 적정 유동성 |
| 상장 거래소 | 소규모 거래소 | 주요 거래소 |
| 프로젝트 정보 | 불명확/신규 | 검증된 팀 |

### 2. 가격 패턴

**P&D 전형적 패턴**:
```
1. 사전 축적 (1-7일): 조용한 매집, 거래량 변화 없음
2. 펌프 시작 (5-15분): 갑자기 10-50%+ 급등
3. 정점 (1-5분): 최고가 도달
4. 덤프 (5-10분): 급격한 하락, 80%+ 되돌림
5. 잔여 (1시간+): 펌프 전 가격 또는 그 이하
```

### 3. 소셜 미디어 패턴

| 단계 | 텔레그램/디스코드 | 트위터/X |
|------|-----------------|---------|
| 사전 | VIP 그룹 암호 신호 | 조용함 |
| 펌프 | "지금 매수!" 대량 메시지 | 해시태그 폭발 |
| 덤프 | "더 오른다" 유지 | FOMO 조장 |
| 이후 | 그룹 삭제/잠잠 | 피해자 불만 |

---

## ML 기반 탐지 (2024-2025 연구)

### 1. 텔레그램 NLP 기반 (arXiv 2024)

**BERTweet + GPT-4o 파이프라인**:
- 2,079개 과거 펌프 이벤트 식별
- 실시간 펌프 탐지 55.81% 정확도 (상위 5개 코인 중)
- 텔레그램 메시지 분류로 사전 경고

```kotlin
// 텔레그램 메시지 위험 점수
data class TelegramRiskScore(
    val channel: String,
    val messageCount: Int,
    val urgencyKeywords: Int,    // "지금", "빨리", "마지막 기회" 등
    val dollarSigns: Int,        // $$$ 사용
    val rocketEmojis: Int,       // 🚀 사용
    val pumpProbability: Double
)

fun analyzeTelegramChannel(messages: List<String>): TelegramRiskScore {
    val urgencyWords = listOf("지금", "빨리", "마지막", "놓치지", "폭등", "급등")
    var urgencyCount = 0
    var dollarCount = 0
    var rocketCount = 0

    messages.forEach { msg ->
        urgencyCount += urgencyWords.count { msg.contains(it) }
        dollarCount += msg.count { it == '$' }
        rocketCount += msg.count { it == '🚀' }
    }

    val probability = calculatePumpProbability(urgencyCount, dollarCount, rocketCount)

    return TelegramRiskScore(
        channel = "analyzed",
        messageCount = messages.size,
        urgencyKeywords = urgencyCount,
        dollarSigns = dollarCount,
        rocketEmojis = rocketCount,
        pumpProbability = probability
    )
}
```

### 2. 앙상블 모델 + SMOTE (arXiv 2025)

**클래스 불균형 해결**:
- SMOTE: 소수 클래스(P&D) 오버샘플링
- XGBoost: 94.87% Recall
- LightGBM: 93.59% Recall

**핵심 특성 (Features)**:
```
- 거래량 변화율 (5분/1시간/24시간)
- 가격 변화율 (동일)
- 호가창 불균형 (Bid-Ask Imbalance)
- 스프레드 확대율
- 거래 빈도 급증률
- 대량 주문 비율
- 시간당 신규 주소 수
```

### 3. EWMA 이상 탐지

**지수가중이동평균 기반**:
```kotlin
class EWMAAnomaly(
    private val span: Int = 20,
    private val threshold: Double = 3.0
) {
    private var mean: Double = 0.0
    private var variance: Double = 0.0
    private val alpha = 2.0 / (span + 1)

    fun update(value: Double): Boolean {
        if (mean == 0.0) {
            mean = value
            variance = 0.0
            return false
        }

        val diff = value - mean
        mean += alpha * diff
        variance = (1 - alpha) * (variance + alpha * diff * diff)

        val std = kotlin.math.sqrt(variance)
        val zScore = kotlin.math.abs(diff) / (std + 0.0001)

        return zScore > threshold
    }
}

// 거래량 이상 탐지
fun detectVolumeAnomaly(volumes: List<Double>): Boolean {
    val detector = EWMAAnomaly(span = 20, threshold = 3.0)
    volumes.dropLast(1).forEach { detector.update(it) }
    return detector.update(volumes.last())
}
```

---

## 실전 필터링 로직

### 1. 1차 필터: 기본 조건

```kotlin
data class CoinBasicCheck(
    val marketCap: Double,
    val dailyVolume: Double,
    val exchangeCount: Int,
    val projectAge: Int  // days
)

fun passBasicFilter(coin: CoinBasicCheck): Boolean {
    return coin.marketCap >= 50_000_000_000  // 500억원 이상
        && coin.exchangeCount >= 3           // 3개 이상 거래소
        && coin.projectAge >= 90             // 90일 이상
}
```

### 2. 2차 필터: 거래량 패턴

```kotlin
fun passVolumeFilter(
    currentVolume: Double,
    avgVolume20: Double,
    volumeHistory: List<Double>  // 최근 1시간 5분봉
): FilterResult {
    val volumeRatio = currentVolume / avgVolume20

    // 급격한 거래량 증가 패턴 확인
    val recentSpike = volumeHistory.takeLast(3).any { it > avgVolume20 * 5 }
    val sustainedGrowth = volumeHistory.zipWithNext().count { (a, b) -> b > a } > 8

    return when {
        volumeRatio > 10 && recentSpike -> FilterResult.REJECT("급격한 거래량 스파이크")
        volumeRatio > 5 && !sustainedGrowth -> FilterResult.CAUTION("비정상 거래량 패턴")
        volumeRatio in 3.0..5.0 && sustainedGrowth -> FilterResult.PASS("건강한 거래량 증가")
        volumeRatio < 3 -> FilterResult.PASS("정상 범위")
        else -> FilterResult.CAUTION("모니터링 필요")
    }
}
```

### 3. 3차 필터: 호가창 분석

```kotlin
fun passOrderbookFilter(
    bidDepth: Double,   // 매수 호가 총량 (KRW)
    askDepth: Double,   // 매도 호가 총량 (KRW)
    spread: Double,     // 스프레드 %
    tradeSize: Double   // 예정 거래 금액
): FilterResult {
    val imbalance = (bidDepth - askDepth) / (bidDepth + askDepth)
    val liquidityRatio = minOf(bidDepth, askDepth) / tradeSize

    return when {
        spread > 1.0 -> FilterResult.REJECT("스프레드 1% 초과")
        liquidityRatio < 5 -> FilterResult.REJECT("유동성 부족")
        imbalance > 0.7 -> FilterResult.CAUTION("극단적 매수 편향")
        imbalance < -0.7 -> FilterResult.CAUTION("극단적 매도 편향")
        else -> FilterResult.PASS("정상 호가창")
    }
}
```

### 4. 4차 필터: 뉴스/소셜 검증

```kotlin
fun passNewsFilter(
    news: List<NewsItem>,
    socialMentions: Int,
    mentionGrowthRate: Double  // 24시간 대비 증가율
): FilterResult {
    // 실제 뉴스 존재 여부
    val hasLegitimateNews = news.any {
        it.source in listOf("Reuters", "Bloomberg", "CoinDesk", "The Block") &&
        it.publishedAt > Instant.now().minus(24, ChronoUnit.HOURS)
    }

    return when {
        !hasLegitimateNews && mentionGrowthRate > 500 ->
            FilterResult.REJECT("뉴스 없이 소셜 급증 - P&D 의심")
        !hasLegitimateNews && mentionGrowthRate > 200 ->
            FilterResult.CAUTION("소셜 급증, 뉴스 미확인")
        hasLegitimateNews ->
            FilterResult.PASS("합법적 뉴스 확인됨")
        else ->
            FilterResult.PASS("특이사항 없음")
    }
}
```

### 5. 종합 필터

```kotlin
data class PumpDumpFilterResult(
    val symbol: String,
    val overallResult: FilterResult,
    val riskScore: Int,  // 0-100, 높을수록 위험
    val details: Map<String, FilterResult>,
    val recommendation: String
)

fun comprehensivePumpDumpFilter(
    coin: CoinData,
    orderbook: OrderBook,
    news: List<NewsItem>,
    socialData: SocialData
): PumpDumpFilterResult {
    val results = mutableMapOf<String, FilterResult>()
    var riskScore = 0

    // 1차 필터
    val basicResult = passBasicFilter(coin.basicCheck)
    results["basic"] = if (basicResult) FilterResult.PASS("기본 조건 충족") else FilterResult.REJECT("기본 조건 미충족")
    if (!basicResult) riskScore += 40

    // 2차 필터
    val volumeResult = passVolumeFilter(coin.currentVolume, coin.avgVolume20, coin.volumeHistory)
    results["volume"] = volumeResult
    riskScore += when (volumeResult) {
        is FilterResult.REJECT -> 30
        is FilterResult.CAUTION -> 15
        else -> 0
    }

    // 3차 필터
    val orderbookResult = passOrderbookFilter(orderbook.bidDepth, orderbook.askDepth, orderbook.spread, 10000.0)
    results["orderbook"] = orderbookResult
    riskScore += when (orderbookResult) {
        is FilterResult.REJECT -> 20
        is FilterResult.CAUTION -> 10
        else -> 0
    }

    // 4차 필터
    val newsResult = passNewsFilter(news, socialData.mentionCount, socialData.growthRate)
    results["news"] = newsResult
    riskScore += when (newsResult) {
        is FilterResult.REJECT -> 30
        is FilterResult.CAUTION -> 15
        else -> 0
    }

    val overallResult = when {
        results.values.any { it is FilterResult.REJECT } -> FilterResult.REJECT("P&D 위험")
        riskScore >= 50 -> FilterResult.REJECT("높은 위험 점수")
        riskScore >= 30 -> FilterResult.CAUTION("주의 필요")
        else -> FilterResult.PASS("진입 가능")
    }

    return PumpDumpFilterResult(
        symbol = coin.symbol,
        overallResult = overallResult,
        riskScore = riskScore,
        details = results,
        recommendation = when (overallResult) {
            is FilterResult.REJECT -> "진입 불가: P&D 위험 높음"
            is FilterResult.CAUTION -> "진입 가능하나 포지션 50% 축소 권장"
            else -> "정상 진입 가능"
        }
    )
}
```

---

## LLM 웹검색 필터 (Spring AI)

### Tool Calling 구현

```kotlin
@Component
class PumpDumpFilterTools {

    @Tool(description = "코인의 최근 뉴스를 웹검색합니다")
    fun searchCoinNews(
        @ToolParam(description = "코인 심볼 (예: BTC)") symbol: String,
        @ToolParam(description = "코인 이름 (예: Bitcoin)") name: String
    ): List<NewsResult> {
        // Brave Search API 또는 다른 검색 API 호출
        val query = "$name cryptocurrency news"
        return braveSearchApi.search(query).results.take(5).map {
            NewsResult(
                title = it.title,
                source = it.source,
                url = it.url,
                snippet = it.snippet
            )
        }
    }

    @Tool(description = "P&D 위험 점수를 결정합니다. 분석 후 반드시 호출해야 합니다.")
    fun makeDecision(
        @ToolParam(description = "마켓 코드") market: String,
        @ToolParam(description = "APPROVED 또는 REJECTED") decision: String,
        @ToolParam(description = "신뢰도 0.0-1.0") confidence: Double,
        @ToolParam(description = "판단 이유") reason: String
    ): FilterDecision {
        return FilterDecision(market, decision, confidence, reason)
    }
}

@Component
class LLMPumpDumpFilter(
    private val modelSelector: ModelSelector,
    private val filterTools: PumpDumpFilterTools
) {
    private val systemPrompt = """
당신은 암호화폐 펌프앤덤프 탐지 전문가입니다.

다음 기준으로 코인을 평가하세요:

## APPROVED 조건 (모두 충족)
- 시가총액 500억원+ 또는 주요 거래소 3곳 이상 상장
- 실제 뉴스/공시가 있고 신뢰할 수 있는 출처
- 거래량 증가가 점진적이고 지속적

## REJECTED 조건 (하나라도 해당)
- 시가총액 100억원 미만의 신규/무명 코인
- 뉴스 없이 소셜미디어만 급증
- "지금 매수", "폭등 임박" 등 FOMO 유발 언급
- 텔레그램 펌프 그룹 연관 의심

반드시 searchCoinNews를 호출하여 뉴스를 확인하고,
분석 후 makeDecision을 호출하여 결정을 내리세요.
""".trimIndent()

    suspend fun filter(market: String, coinName: String): FilterResult {
        val chatClient = modelSelector.getChatClient()
            .mutate()
            .defaultTools(filterTools)
            .build()

        val userPrompt = """
마켓: $market
코인: $coinName

이 코인의 거래량이 급등했습니다.
웹검색을 통해 최근 뉴스를 확인하고, P&D 여부를 판단해주세요.
"""

        chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content()

        // Tool calling 결과에서 decision 추출
        return filterTools.getLastDecision(market)
            ?: FilterResult.CAUTION("LLM 판단 실패", 0.5)
    }
}
```

---

## 실시간 모니터링 대시보드

### 핵심 지표

```kotlin
data class PumpDumpMonitor(
    val symbol: String,
    val currentPrice: Double,
    val price5minAgo: Double,
    val price1hourAgo: Double,
    val volumeRatio: Double,        // 현재/20일평균
    val volumeSpike: Boolean,       // 5분 내 5배 이상 급증
    val spreadPercent: Double,
    val bidAskImbalance: Double,
    val socialMentionGrowth: Double,
    val riskLevel: RiskLevel,
    val lastUpdate: Instant
)

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

fun calculateRiskLevel(monitor: PumpDumpMonitor): RiskLevel {
    var riskScore = 0

    // 가격 급등
    val priceChange5m = (monitor.currentPrice - monitor.price5minAgo) / monitor.price5minAgo * 100
    val priceChange1h = (monitor.currentPrice - monitor.price1hourAgo) / monitor.price1hourAgo * 100

    if (priceChange5m > 10) riskScore += 30
    else if (priceChange5m > 5) riskScore += 15

    // 거래량 급증
    if (monitor.volumeSpike) riskScore += 25
    else if (monitor.volumeRatio > 5) riskScore += 15

    // 호가창 이상
    if (monitor.spreadPercent > 1) riskScore += 15
    if (kotlin.math.abs(monitor.bidAskImbalance) > 0.7) riskScore += 10

    // 소셜 급증
    if (monitor.socialMentionGrowth > 500) riskScore += 20
    else if (monitor.socialMentionGrowth > 200) riskScore += 10

    return when {
        riskScore >= 60 -> RiskLevel.CRITICAL
        riskScore >= 40 -> RiskLevel.HIGH
        riskScore >= 20 -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}
```

---

## 체크리스트

### 진입 전 확인

```
□ 시가총액 500억원+ 확인
□ 주요 거래소 3곳 이상 상장
□ 프로젝트 출시 90일 이상
□ 최근 합법적 뉴스 존재
□ 거래량 증가 패턴 점진적
□ 호가창 유동성 충분 (거래금액 5배+)
□ 스프레드 1% 미만
□ 소셜 급증 시 뉴스 확인
□ 텔레그램 펌프 그룹 연관 없음
□ LLM 필터 APPROVED
```

### P&D 의심 시 대응

```
1. 즉시 진입 중단
2. 기존 포지션 점검
3. 트레일링 스탑 타이트하게 조정
4. 관련 코인 상관관계 확인
5. 30분 모니터링 후 재평가
```

---

*참조: arXiv 2412.18848 - Machine Learning-Based Detection of Pump-and-Dump Schemes*
*참조: arXiv 2510.00836 - Ensemble-Based Models and SMOTE*
*참조: MDPI Future Internet 15(8) - Survey on P&D Detection*
