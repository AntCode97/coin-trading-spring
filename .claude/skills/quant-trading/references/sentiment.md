# 뉴스 & 감성 분석 가이드

## 개요

감성 분석 = 뉴스/소셜 미디어에서 시장 심리 정량화

### 2025 연구 결과

| 방법 | 연간 수익률 | 출처 |
|------|------------|------|
| ChatGPT 기반 감성 전략 | +1640% | Academic 2025 |
| 뉴스 감성 + 기술적 분석 | +85% 초과 수익 | Quant Research |
| 트위터 감성 분석 | 방향 예측 68% | MIT 2024 |

---

## 감성 점수 체계

### 점수 범위

| 점수 | 해석 | 시장 상태 | 역발상 액션 |
|------|------|----------|-----------|
| +1.5 ~ +2.0 | 극도 낙관 | 유포리아 | **매도 고려** |
| +0.5 ~ +1.5 | 낙관 | 강세장 | 보유 유지 |
| -0.5 ~ +0.5 | 중립 | 횡보장 | 기술적 분석 |
| -1.5 ~ -0.5 | 비관 | 약세장 | 관망 |
| -2.0 ~ -1.5 | 극도 비관 | 공포 | **매수 고려** |

### 역발상 원칙

> "탐욕에 공포를, 공포에 탐욕을"

- 극도 낙관 → 고점 근처 → 분할 매도
- 극도 비관 → 저점 근처 → 분할 매수

---

## 소스별 가중치

### 신뢰도 기반 가중치

| 소스 유형 | 가중치 | 이유 |
|----------|--------|------|
| 전문 분석 | 4 | 깊은 분석, 검증된 트랙레코드 |
| 주요 뉴스 | 3 | 신뢰성, 팩트체크 |
| 온체인 데이터 | 2 | 객관적 데이터 |
| 소셜 미디어 | 1 | 노이즈 많음, 조작 가능 |

### 주요 뉴스 소스

**Tier 1 (가중치 4)**:
- Bloomberg, Reuters, WSJ
- Glassnode, Messari, The Block

**Tier 2 (가중치 3)**:
- CoinDesk, CoinTelegraph
- Decrypt, CryptoSlate

**Tier 3 (가중치 2)**:
- 일반 언론 (CNBC, Forbes 등)
- 프로젝트 공식 블로그

**Tier 4 (가중치 1)**:
- Twitter/X
- Reddit, Telegram
- YouTube, TikTok

---

## 키워드 영향도

### 강세 키워드

| 키워드 | 영향도 | 예시 |
|--------|--------|------|
| ETF 승인 | +++ | "SEC approves Bitcoin ETF" |
| 기관 투자 | ++ | "BlackRock adds Bitcoin exposure" |
| 파트너십 | ++ | "Visa partners with..." |
| 네트워크 업그레이드 | + | "Ethereum upgrades to..." |
| 채택 증가 | + | "PayPal enables crypto" |

### 약세 키워드

| 키워드 | 영향도 | 예시 |
|--------|--------|------|
| 해킹 | --- | "Exchange hacked" |
| 규제 강화 | --- | "SEC files lawsuit" |
| 거래소 파산 | --- | "Exchange files bankruptcy" |
| 러그풀 | -- | "Developers drain liquidity" |
| 소송 | -- | "Class action filed" |
| FUD | - | "Tether concerns resurface" |

### 코드 구현

```kotlin
data class KeywordImpact(
    val keyword: String,
    val impact: Double,  // -3.0 ~ +3.0
    val category: String
)

val KEYWORD_IMPACTS = listOf(
    // 강세
    KeywordImpact("etf approved", 3.0, "regulatory"),
    KeywordImpact("etf approval", 3.0, "regulatory"),
    KeywordImpact("institutional investment", 2.5, "adoption"),
    KeywordImpact("blackrock", 2.0, "institutional"),
    KeywordImpact("fidelity", 2.0, "institutional"),
    KeywordImpact("partnership", 1.5, "business"),
    KeywordImpact("upgrade", 1.0, "technical"),
    KeywordImpact("adoption", 1.0, "adoption"),

    // 약세
    KeywordImpact("hacked", -3.0, "security"),
    KeywordImpact("hack", -3.0, "security"),
    KeywordImpact("sec lawsuit", -3.0, "regulatory"),
    KeywordImpact("bankruptcy", -3.0, "business"),
    KeywordImpact("rug pull", -2.5, "scam"),
    KeywordImpact("regulation", -1.5, "regulatory"),
    KeywordImpact("investigation", -1.5, "legal"),
    KeywordImpact("lawsuit", -2.0, "legal"),
    KeywordImpact("concern", -0.5, "sentiment"),
    KeywordImpact("fear", -0.5, "sentiment")
)

fun analyzeKeywords(text: String): Double {
    val lowerText = text.lowercase()
    var totalImpact = 0.0
    var matchCount = 0

    KEYWORD_IMPACTS.forEach { ki ->
        if (lowerText.contains(ki.keyword)) {
            totalImpact += ki.impact
            matchCount++
        }
    }

    return if (matchCount > 0) totalImpact / matchCount else 0.0
}
```

---

## 감성 점수 계산

### 가중 평균 공식

```
감성점수 = Σ(개별점수 × 소스가중치) / Σ(소스가중치)
```

### 종합 분석 구현

```kotlin
data class SentimentSource(
    val source: String,
    val sourceType: SourceType,
    val headline: String,
    val sentiment: Double,  // -1.0 ~ +1.0
    val timestamp: Instant
)

enum class SourceType(val weight: Int) {
    EXPERT_ANALYSIS(4),
    MAJOR_NEWS(3),
    ONCHAIN_DATA(2),
    SOCIAL_MEDIA(1)
}

data class SentimentResult(
    val overallScore: Double,
    val interpretation: String,
    val bullishSources: Int,
    val bearishSources: Int,
    val neutralSources: Int,
    val dominantThemes: List<String>,
    val actionableInsight: String
)

class SentimentAnalyzer {
    fun analyze(sources: List<SentimentSource>): SentimentResult {
        if (sources.isEmpty()) {
            return SentimentResult(
                overallScore = 0.0,
                interpretation = "데이터 부족",
                bullishSources = 0,
                bearishSources = 0,
                neutralSources = 0,
                dominantThemes = emptyList(),
                actionableInsight = "더 많은 데이터 필요"
            )
        }

        var weightedSum = 0.0
        var totalWeight = 0.0
        var bullish = 0
        var bearish = 0
        var neutral = 0

        sources.forEach { source ->
            val weight = source.sourceType.weight
            weightedSum += source.sentiment * weight
            totalWeight += weight

            when {
                source.sentiment > 0.2 -> bullish++
                source.sentiment < -0.2 -> bearish++
                else -> neutral++
            }
        }

        val overallScore = weightedSum / totalWeight

        val interpretation = when {
            overallScore > 0.75 -> "극도 낙관 (Extreme Greed)"
            overallScore > 0.25 -> "낙관 (Greed)"
            overallScore > -0.25 -> "중립 (Neutral)"
            overallScore > -0.75 -> "비관 (Fear)"
            else -> "극도 비관 (Extreme Fear)"
        }

        val actionableInsight = when {
            overallScore > 0.75 -> "역발상: 고점 경계, 분할 매도 고려"
            overallScore > 0.5 -> "주의: 탐욕 증가, 리스크 관리 강화"
            overallScore > -0.5 -> "중립: 기술적 분석 우선"
            overallScore > -0.75 -> "관심: 공포 증가, 기회 탐색"
            else -> "역발상: 저점 근접 가능, 분할 매수 고려"
        }

        return SentimentResult(
            overallScore = overallScore,
            interpretation = interpretation,
            bullishSources = bullish,
            bearishSources = bearish,
            neutralSources = neutral,
            dominantThemes = extractThemes(sources),
            actionableInsight = actionableInsight
        )
    }

    private fun extractThemes(sources: List<SentimentSource>): List<String> {
        val themeCounts = mutableMapOf<String, Int>()
        // 키워드 분석으로 주요 테마 추출
        sources.forEach { source ->
            val text = source.headline.lowercase()
            when {
                text.contains("etf") -> themeCounts.merge("ETF", 1, Int::plus)
                text.contains("regulat") -> themeCounts.merge("규제", 1, Int::plus)
                text.contains("institution") -> themeCounts.merge("기관", 1, Int::plus)
                text.contains("hack") -> themeCounts.merge("보안", 1, Int::plus)
                text.contains("partnership") -> themeCounts.merge("파트너십", 1, Int::plus)
            }
        }
        return themeCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }
}
```

---

## LLM 기반 감성 분석 (Spring AI)

### Tool Calling 구현

```kotlin
@Component
class SentimentTools {

    @Tool(description = "코인 관련 최신 뉴스를 검색합니다")
    fun searchNews(
        @ToolParam(description = "코인 심볼") symbol: String,
        @ToolParam(description = "검색 쿼리") query: String
    ): List<NewsItem> {
        // Brave Search API 호출
        return braveSearchApi.search("$symbol cryptocurrency $query news 2025")
            .results.take(5).map { NewsItem(it.title, it.url, it.snippet) }
    }

    @Tool(description = "감성 분석 결과를 저장합니다")
    fun saveSentimentResult(
        @ToolParam(description = "마켓 코드") market: String,
        @ToolParam(description = "감성 점수 -1.0 ~ +1.0") score: Double,
        @ToolParam(description = "분석 요약") summary: String
    ): Boolean {
        sentimentRepository.save(SentimentRecord(market, score, summary, Instant.now()))
        return true
    }
}

@Component
class LLMSentimentAnalyzer(
    private val modelSelector: ModelSelector,
    private val tools: SentimentTools
) {
    private val systemPrompt = """
당신은 암호화폐 시장 감성 분석 전문가입니다.

## 분석 기준

감성 점수 (-1.0 ~ +1.0):
- +0.7 ~ +1.0: 극도 낙관 (ETF 승인, 대형 기관 진입 등)
- +0.3 ~ +0.7: 낙관 (긍정적 뉴스, 채택 증가)
- -0.3 ~ +0.3: 중립 (특이사항 없음)
- -0.7 ~ -0.3: 비관 (부정적 뉴스, 규제 우려)
- -1.0 ~ -0.7: 극도 비관 (해킹, 파산, 대규모 소송)

## 역발상 원칙

- 극도 낙관 시 = 고점 경계 권고
- 극도 비관 시 = 저점 기회 권고

반드시 searchNews를 호출하여 최신 뉴스를 확인하고,
saveSentimentResult를 호출하여 결과를 저장하세요.
""".trimIndent()

    suspend fun analyzeSentiment(market: String, coinName: String): SentimentResult {
        val chatClient = modelSelector.getChatClient()
            .mutate()
            .defaultTools(tools)
            .build()

        val response = chatClient.prompt()
            .system(systemPrompt)
            .user("$coinName ($market) 의 시장 감성을 분석해주세요.")
            .call()
            .content()

        return tools.getLastResult(market)
    }
}
```

---

## Fear & Greed Index 활용

### Alternative.me 공포탐욕지수

| 점수 | 해석 | 역발상 액션 |
|------|------|-----------|
| 0-25 | 극도 공포 | **매수 고려** |
| 25-40 | 공포 | 관심 |
| 40-60 | 중립 | 기술적 분석 |
| 60-75 | 탐욕 | 주의 |
| 75-100 | 극도 탐욕 | **매도 고려** |

### API 연동

```kotlin
data class FearGreedIndex(
    val value: Int,
    val classification: String,
    val timestamp: Long
)

suspend fun getFearGreedIndex(): FearGreedIndex {
    val response = httpClient.get("https://api.alternative.me/fng/")
    return response.body<FearGreedResponse>().data.first().let {
        FearGreedIndex(
            value = it.value.toInt(),
            classification = it.valueClassification,
            timestamp = it.timestamp.toLong()
        )
    }
}
```

---

## 소셜 미디어 분석

### 트위터/X 지표

| 지표 | 의미 | 해석 |
|------|------|------|
| 멘션 수 증가 | 관심 증가 | 방향 무관 |
| 긍정 멘션 비율 | 감성 | 강세/약세 |
| 인플루언서 언급 | 영향력 | 주의 필요 |
| 해시태그 트렌딩 | 바이럴 | 단기 영향 |

### 레딧 분석

| 서브레딧 | 특성 |
|----------|------|
| r/Bitcoin | BTC 전용, 맥시멀리스트 |
| r/CryptoCurrency | 다양한 코인 |
| r/ethfinance | ETH 전용 |
| r/altcoin | 알트코인 |

---

## 뉴스 이벤트 임팩트 시간

### 이벤트별 영향 지속 시간

| 이벤트 | 즉각 영향 | 지속 시간 |
|--------|---------|----------|
| ETF 승인/거부 | +/- 15-30% | 1-7일 |
| 해킹 | -10-30% | 1-3일 |
| 규제 발표 | +/- 10-20% | 1-7일 |
| 파트너십 | +5-10% | 1-3일 |
| 기술 업그레이드 | +5-15% | 사전 빌드업 |
| 고래 매도 | -5-15% | 수 시간 |

### 진입 타이밍

```kotlin
fun getEventBasedEntry(event: NewsEvent): EntryTiming {
    return when (event.type) {
        EventType.ETF_APPROVAL -> EntryTiming(
            immediate = false,
            waitHours = 4,  // 초기 스파이크 후 조정 대기
            stopLossPercent = 5.0
        )
        EventType.HACK -> EntryTiming(
            immediate = false,
            waitHours = 24,  // 피해 규모 파악 후
            stopLossPercent = 10.0
        )
        EventType.UPGRADE -> EntryTiming(
            immediate = true,
            waitHours = 0,  // 사전 빌드업 활용
            stopLossPercent = 3.0
        )
        else -> EntryTiming(immediate = false, waitHours = 1, stopLossPercent = 2.0)
    }
}
```

---

## 종합 감성 전략

### 진입 조건

```kotlin
data class SentimentEntryCondition(
    val sentimentScore: Double,
    val fearGreedIndex: Int,
    val technicalSignal: TechnicalSignal
)

fun shouldEnterBased OnSentiment(condition: SentimentEntryCondition): EntryDecision {
    // 역발상 전략
    val contrarian = when {
        condition.sentimentScore < -0.7 && condition.fearGreedIndex < 25 ->
            ContrarianSignal.STRONG_BUY  // 극도 공포 = 매수 기회
        condition.sentimentScore > 0.7 && condition.fearGreedIndex > 75 ->
            ContrarianSignal.STRONG_SELL  // 극도 탐욕 = 매도 고려
        else -> ContrarianSignal.NEUTRAL
    }

    // 기술적 분석과 결합
    return when {
        contrarian == ContrarianSignal.STRONG_BUY &&
        condition.technicalSignal == TechnicalSignal.BULLISH ->
            EntryDecision.BUY_AGGRESSIVE

        contrarian == ContrarianSignal.STRONG_SELL &&
        condition.technicalSignal == TechnicalSignal.BEARISH ->
            EntryDecision.SELL_AGGRESSIVE

        contrarian == ContrarianSignal.NEUTRAL ->
            EntryDecision.FOLLOW_TECHNICAL  // 기술적 신호만 따름

        else -> EntryDecision.WAIT  // 신호 불일치
    }
}
```

---

## 체크리스트

### 진입 전 감성 확인

```
□ 최근 24시간 주요 뉴스 확인
□ Fear & Greed Index 확인
□ 트위터 트렌딩 확인
□ 대형 인플루언서 언급 확인
□ 예정된 이벤트 확인 (업그레이드, 발표 등)
□ 감성 점수와 기술적 신호 비교
```

### 역발상 진입 조건

```
□ 극도 비관 (점수 < -0.7) + 기술적 과매도
□ Fear & Greed Index < 25
□ 온체인 축적 신호 (웨일 매집)
□ 주요 지지선 근처
```

---

*참조: 2025 ChatGPT 기반 트레이딩 연구 (1640% 수익률)*
*참조: Alternative.me Fear & Greed Index*
