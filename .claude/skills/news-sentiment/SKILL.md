---
name: news-sentiment
description: 암호화폐 뉴스 및 소셜 미디어 감성 분석. 뉴스 수집, 감성 점수 계산, 시장 심리 분석, AI 기반 트레이딩 신호 생성 시 사용. 2025년 ChatGPT 기반 전략 1640% 수익률 검증.
allowed-tools:
  - Read
  - Bash
  - WebFetch
  - WebSearch
  - Grep
---

# 뉴스 감성 분석 (News Sentiment Analysis) Skill

## 개요

2025년 기준 AI가 전체 암호화폐 거래량의 89%를 처리한다.
감성 분석은 뉴스, 소셜 미디어, 온체인 데이터를 분석하여 시장 심리를 예측하는 핵심 기술이다.

## 2025년 검증된 성과

ChatGPT 기반 감성 분석 전략이 2018-2024년 기간 **1640.32% 수익률** 달성:
- ML 기반 전략: 304.77%
- Buy-and-Hold: 223.40%

## 감성 분석 데이터 소스

### 1차 소스 (실시간)
| 소스 | 특성 | 신뢰도 |
|------|------|--------|
| Twitter/X | 실시간 감성, 노이즈 많음 | 중 |
| Reddit | 커뮤니티 감성, 밈 영향 | 중 |
| Telegram | 프라이빗 그룹 동향 | 높음 |
| Discord | 프로젝트별 커뮤니티 | 높음 |

### 2차 소스 (분석)
| 소스 | 특성 | 신뢰도 |
|------|------|--------|
| CoinDesk | 메이저 뉴스 | 높음 |
| CoinTelegraph | 업계 뉴스 | 높음 |
| The Block | 리서치 중심 | 매우 높음 |
| 블록미디어 | 한국 뉴스 | 높음 |

### 온체인 데이터
| 지표 | 의미 | 신호 |
|------|------|------|
| 웨일 이동 | 대형 투자자 동향 | 선행 |
| 거래소 유입 | 매도 압력 | 약세 |
| 거래소 유출 | 홀딩 심리 | 강세 |
| 펀딩비 | 선물 시장 편향 | 과열 |

## 감성 점수 계산

### NLP 기반 감성 분류

```python
sentiment_categories = {
    "very_bullish": 2,    # 매우 긍정
    "bullish": 1,         # 긍정
    "neutral": 0,         # 중립
    "bearish": -1,        # 부정
    "very_bearish": -2    # 매우 부정
}
```

### 종합 감성 점수

```
감성 점수 = Σ(개별 점수 × 소스 가중치) / 총 가중치

소스 가중치:
- 주요 뉴스: 3
- 소셜 미디어: 1
- 온체인 데이터: 2
- 전문 분석: 4
```

### 감성 점수 해석

| 점수 범위 | 해석 | 액션 |
|----------|------|------|
| 1.5 ~ 2.0 | 극도 낙관 | 매도 고려 (과열) |
| 0.5 ~ 1.5 | 낙관 | 매수 유지 |
| -0.5 ~ 0.5 | 중립 | 기술적 분석 따름 |
| -1.5 ~ -0.5 | 비관 | 매도 유지 |
| -2.0 ~ -1.5 | 극도 비관 | 매수 고려 (공포) |

## AI 감성 분석 워크플로우

### 1단계: 뉴스 수집

```java
public List<NewsArticle> collectNews(String symbol) {
    List<NewsArticle> news = new ArrayList<>();

    // CoinGecko 뉴스 API
    news.addAll(fetchCoinGeckoNews(symbol));

    // 웹 검색 (최근 24시간)
    news.addAll(webSearchNews(symbol + " 암호화폐 뉴스"));

    return news;
}
```

### 2단계: 감성 분석 (LLM)

```
프롬프트 템플릿:

다음 암호화폐 뉴스의 감성을 분석하세요:

뉴스: {news_content}

분석 형식:
1. 감성: [very_bullish/bullish/neutral/bearish/very_bearish]
2. 신뢰도: [1-10]
3. 주요 키워드: [키워드 리스트]
4. 가격 영향: [상승/하락/무영향]
5. 영향 기간: [단기/중기/장기]
```

### 3단계: 트레이딩 신호 생성

```java
public TradingSignal generateSignal(
    SentimentScore sentiment,
    TechnicalIndicators technical
) {
    // 감성과 기술적 지표 결합
    double combinedScore = sentiment.score() * 0.4 + technical.score() * 0.6;

    if (combinedScore >= 0.7 && sentiment.score() > 0) {
        return TradingSignal.buy("감성+기술 강세");
    } else if (combinedScore <= -0.7 && sentiment.score() < 0) {
        return TradingSignal.sell("감성+기술 약세");
    }

    return TradingSignal.hold();
}
```

## 주요 뉴스 키워드 영향

### 강세 키워드
| 키워드 | 영향 강도 |
|--------|----------|
| ETF 승인 | +++ |
| 기관 투자 | ++ |
| 파트너십 | ++ |
| 업그레이드 | + |
| 채택 확대 | + |

### 약세 키워드
| 키워드 | 영향 강도 |
|--------|----------|
| 규제 강화 | --- |
| 해킹 | --- |
| 소송 | -- |
| 거래소 파산 | --- |
| 대량 매도 | -- |

## 실시간 모니터링 설정

### 알림 트리거

```yaml
alerts:
  sentiment_change:
    threshold: 0.5  # 점수 변화량
    timeframe: 1h
  whale_alert:
    min_amount: 10000000  # $10M
  funding_rate:
    extreme: 0.1  # 10%
```

## 주의사항

1. **봇 노이즈**: 트위터 계정의 ~15%가 봇 계정
2. **조작 가능성**: 펌프앤덤프 그룹의 조작된 감성
3. **지연**: 뉴스 반영까지 수분~수시간 소요
4. **암호화폐 특화 용어**: 일반 NLP 모델의 오분류

## 한국 시장 특화

### 한국 뉴스 소스
- 블록미디어 (blockmedia.co.kr)
- 코인데스크코리아 (coindeskkorea.com)
- 디센터 (decenter.kr)
- 코인니스 (coinness.com)

### 한국어 감성 키워드
| 긍정 | 부정 |
|------|------|
| 상장, 호재, 급등 | 규제, 악재, 급락 |
| 투자 확대, 채택 | 해킹, 사기, 폐지 |
| 파트너십, 협력 | 소송, 조사, 경고 |

## 관련 프로젝트 파일

이 Skill은 향후 구현 예정:
- `coin-mcp-client/src/main/java/com/ant/coinmcp/client/news/NewsSentimentService.java`
- `coin-mcp-client/src/main/java/com/ant/coinmcp/client/news/NewsCollector.java`
