package com.ant.cointrading.risk

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.OrderbookInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 시장 상태 검사기
 *
 * 20년차 퀀트의 교훈:
 * 1. 주문 전에 시장 상태를 반드시 확인해야 한다
 * 2. 스프레드가 넓으면 슬리피지 손실이 확정적이다
 * 3. 유동성이 없으면 원하는 가격에 체결이 안 된다
 * 4. 변동성이 급등하면 모든 모델이 무너진다
 *
 * 체크 항목:
 * - 스프레드: 0.5% 초과 시 거래 금지
 * - 유동성: 주문량 대비 호가 깊이 3배 미만 시 거래 금지
 * - 변동성: 1분 내 2% 이상 급변 시 경고
 * - API 상태: 연속 실패 시 거래 중단
 */
@Component
class MarketConditionChecker(
    private val bithumbPublicApi: BithumbPublicApi
) {
    private val log = LoggerFactory.getLogger(MarketConditionChecker::class.java)

    // 최근 가격 캐시 (변동성 계산용)
    private val priceHistory = ConcurrentHashMap<String, MutableList<PricePoint>>()

    // API 상태 추적
    private val apiErrorCounts = ConcurrentHashMap<String, Int>()
    private val lastApiSuccess = ConcurrentHashMap<String, Instant>()

    data class PricePoint(
        val price: BigDecimal,
        val timestamp: Instant
    )

    companion object {
        // 리스크 한도 (QUANT_RESEARCH.md 기반)
        const val MAX_SPREAD_PERCENT = 0.5       // 최대 스프레드 0.5%
        const val MIN_LIQUIDITY_RATIO = 3.0      // 주문량 대비 호가 깊이 최소 3배
        const val MAX_VOLATILITY_1MIN = 2.0      // 1분 내 최대 변동률 2%
        const val MAX_API_ERRORS = 5             // 연속 API 에러 허용 횟수
        const val PRICE_HISTORY_SIZE = 60        // 최근 60개 가격 저장 (1분)
        const val ERROR_RESET_MINUTES = 5L        // 에러 카운터 리셋 대기 시간 (분)
    }

    /**
     * 종합 시장 상태 검사
     */
    fun checkMarketCondition(market: String, orderAmountKrw: BigDecimal): MarketConditionResult {
        val apiMarket = convertToApiMarket(market)
        val issues = mutableListOf<String>()
        var canTrade = true
        var severity = ConditionSeverity.NORMAL

        // 1. API 상태 확인
        val apiCheck = checkApiHealth(market)
        if (!apiCheck.healthy) {
            return MarketConditionResult(
                canTrade = false,
                severity = ConditionSeverity.CRITICAL,
                issues = listOf(apiCheck.reason),
                spread = null,
                liquidityRatio = null,
                volatility1Min = null
            )
        }

        // 2. 호가창 조회
        val orderbook = try {
            bithumbPublicApi.getOrderbook(apiMarket)?.firstOrNull()
        } catch (e: Exception) {
            recordApiError(market)
            log.error("[$market] 호가창 조회 실패: ${e.message}")
            return MarketConditionResult(
                canTrade = false,
                severity = ConditionSeverity.CRITICAL,
                issues = listOf("호가창 조회 실패: ${e.message}"),
                spread = null,
                liquidityRatio = null,
                volatility1Min = null
            )
        }

        if (orderbook == null || orderbook.orderbookUnits.isNullOrEmpty()) {
            recordApiError(market)
            return MarketConditionResult(
                canTrade = false,
                severity = ConditionSeverity.CRITICAL,
                issues = listOf("호가창 데이터 없음"),
                spread = null,
                liquidityRatio = null,
                volatility1Min = null
            )
        }

        recordApiSuccess(market)

        // 3. 스프레드 계산
        val spreadCheck = calculateSpread(orderbook)
        if (spreadCheck.spreadPercent > MAX_SPREAD_PERCENT) {
            canTrade = false
            severity = ConditionSeverity.HIGH
            issues.add("스프레드 과다: ${String.format("%.3f", spreadCheck.spreadPercent)}% (한도: ${MAX_SPREAD_PERCENT}%)")
        }

        // 4. 유동성 확인
        val liquidityCheck = checkLiquidity(orderbook, orderAmountKrw)
        if (liquidityCheck.liquidityRatio < MIN_LIQUIDITY_RATIO) {
            canTrade = false
            if (severity != ConditionSeverity.HIGH) {
                severity = ConditionSeverity.HIGH
            }
            issues.add("유동성 부족: 비율 ${String.format("%.2f", liquidityCheck.liquidityRatio)}x (필요: ${MIN_LIQUIDITY_RATIO}x)")
        }

        // 5. 변동성 확인
        val volatilityCheck = checkVolatility(market, spreadCheck.midPrice)
        if (volatilityCheck.volatility1Min > MAX_VOLATILITY_1MIN) {
            // 변동성은 경고만, 거래 금지는 안 함 (하지만 기록)
            if (severity == ConditionSeverity.NORMAL) {
                severity = ConditionSeverity.WARNING
            }
            issues.add("고변동성 경고: 1분 ${String.format("%.2f", volatilityCheck.volatility1Min)}% (한도: ${MAX_VOLATILITY_1MIN}%)")
        }

        // 결과 로깅
        if (!canTrade || issues.isNotEmpty()) {
            log.warn("[$market] 시장 상태 체크: canTrade=$canTrade, issues=$issues")
        }

        return MarketConditionResult(
            canTrade = canTrade,
            severity = severity,
            issues = issues,
            spread = spreadCheck.spreadPercent,
            midPrice = spreadCheck.midPrice,
            bestBid = spreadCheck.bestBid,
            bestAsk = spreadCheck.bestAsk,
            liquidityRatio = liquidityCheck.liquidityRatio,
            bidDepthKrw = liquidityCheck.bidDepthKrw,
            askDepthKrw = liquidityCheck.askDepthKrw,
            volatility1Min = volatilityCheck.volatility1Min,
            orderBookImbalance = calculateImbalance(orderbook)
        )
    }

    /**
     * 스프레드 계산
     */
    private fun calculateSpread(orderbook: OrderbookInfo): SpreadCheck {
        val units = orderbook.orderbookUnits ?: return SpreadCheck(
            spreadPercent = Double.MAX_VALUE,
            midPrice = BigDecimal.ZERO,
            bestBid = BigDecimal.ZERO,
            bestAsk = BigDecimal.ZERO
        )

        val bestBid = units.first().bidPrice
        val bestAsk = units.first().askPrice

        if (bestBid <= BigDecimal.ZERO || bestAsk <= BigDecimal.ZERO) {
            return SpreadCheck(Double.MAX_VALUE, BigDecimal.ZERO, bestBid, bestAsk)
        }

        val midPrice = (bestBid + bestAsk).divide(BigDecimal(2), 8, RoundingMode.HALF_UP)
        val spread = bestAsk - bestBid
        val spreadPercent = spread.divide(midPrice, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()

        return SpreadCheck(spreadPercent, midPrice, bestBid, bestAsk)
    }

    /**
     * 유동성 확인
     */
    private fun checkLiquidity(orderbook: OrderbookInfo, orderAmountKrw: BigDecimal): LiquidityCheck {
        val units = orderbook.orderbookUnits ?: return LiquidityCheck(0.0, BigDecimal.ZERO, BigDecimal.ZERO)

        // 상위 5호가의 총 금액 계산
        val topUnits = units.take(5)

        var bidDepthKrw = BigDecimal.ZERO
        var askDepthKrw = BigDecimal.ZERO

        for (unit in topUnits) {
            bidDepthKrw += unit.bidPrice.multiply(unit.bidSize)
            askDepthKrw += unit.askPrice.multiply(unit.askSize)
        }

        // 매수 시 매도 호가 깊이, 매도 시 매수 호가 깊이가 중요
        // 보수적으로 더 작은 쪽 기준
        val minDepth = minOf(bidDepthKrw, askDepthKrw)

        val liquidityRatio = if (orderAmountKrw > BigDecimal.ZERO) {
            minDepth.divide(orderAmountKrw, 2, RoundingMode.HALF_DOWN).toDouble()
        } else {
            Double.MAX_VALUE
        }

        return LiquidityCheck(liquidityRatio, bidDepthKrw, askDepthKrw)
    }

    /**
     * 변동성 확인 (1분)
     */
    private fun checkVolatility(market: String, currentPrice: BigDecimal): VolatilityCheck {
        val history = priceHistory.getOrPut(market) { mutableListOf() }

        // 가격 기록 추가
        history.add(PricePoint(currentPrice, Instant.now()))

        // 오래된 데이터 제거 (60초 이상)
        val cutoff = Instant.now().minusSeconds(60)
        history.removeIf { it.timestamp.isBefore(cutoff) }

        // 최대 PRICE_HISTORY_SIZE 유지
        while (history.size > PRICE_HISTORY_SIZE) {
            history.removeAt(0)
        }

        if (history.size < 2) {
            return VolatilityCheck(0.0)
        }

        // 1분 내 최고/최저 대비 변동률 계산
        val prices = history.map { it.price }
        val minPrice = prices.minOrNull() ?: currentPrice
        val maxPrice = prices.maxOrNull() ?: currentPrice

        if (minPrice <= BigDecimal.ZERO) {
            return VolatilityCheck(0.0)
        }

        val volatility = (maxPrice - minPrice)
            .divide(minPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()

        return VolatilityCheck(volatility)
    }

    /**
     * Order Book Imbalance 계산 (QUANT_RESEARCH.md 기반)
     */
    private fun calculateImbalance(orderbook: OrderbookInfo): Double {
        val units = orderbook.orderbookUnits?.take(5) ?: return 0.0

        var bidVolume = BigDecimal.ZERO
        var askVolume = BigDecimal.ZERO

        for (unit in units) {
            bidVolume += unit.bidPrice.multiply(unit.bidSize)
            askVolume += unit.askPrice.multiply(unit.askSize)
        }

        val total = bidVolume + askVolume
        if (total <= BigDecimal.ZERO) return 0.0

        return (bidVolume - askVolume)
            .divide(total, 6, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * API 상태 확인 (시간 기반 에러 카운터 리셋)
     */
    private fun checkApiHealth(market: String): ApiHealthCheck {
        val errorCount = apiErrorCounts[market] ?: 0
        val lastErrorTime = lastApiSuccess[market]

        // 마지막 성공 후 ERROR_RESET_MINUTES 경과 시 에러 카운터 리셋
        if (errorCount > 0 && lastErrorTime != null) {
            val minutesSinceLastSuccess = ChronoUnit.MINUTES.between(lastErrorTime, Instant.now())
            if (minutesSinceLastSuccess >= ERROR_RESET_MINUTES) {
                apiErrorCounts[market] = 0
                log.info("[$market] API 에러 카운터 리셋 (${ERROR_RESET_MINUTES}분 경과)")
            }
        }

        if (errorCount >= MAX_API_ERRORS) {
            return ApiHealthCheck(
                healthy = false,
                reason = "API 연속 에러 ${errorCount}회 - 안정화 대기 필요"
            )
        }

        return ApiHealthCheck(healthy = true, reason = "")
    }

    /**
     * API 에러 기록
     */
    fun recordApiError(market: String) {
        val current = apiErrorCounts.getOrDefault(market, 0)
        apiErrorCounts[market] = current + 1
        log.warn("[$market] API 에러 기록: ${current + 1}회")
    }

    /**
     * API 성공 기록 (에러 카운터 리셋)
     */
    fun recordApiSuccess(market: String) {
        apiErrorCounts[market] = 0
        lastApiSuccess[market] = Instant.now()
    }

    /**
     * 마켓 형식 변환 (BTC_KRW -> KRW-BTC)
     */
    private fun convertToApiMarket(market: String): String {
        return market.split("_").let { parts ->
            if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
        }
    }

    /**
     * 특정 마켓의 최근 변동성 조회
     */
    fun getRecentVolatility(market: String): Double {
        val history = priceHistory[market] ?: return 0.0
        if (history.size < 2) return 0.0

        val prices = history.map { it.price }
        val minPrice = prices.minOrNull() ?: return 0.0
        val maxPrice = prices.maxOrNull() ?: return 0.0

        if (minPrice <= BigDecimal.ZERO) return 0.0

        return (maxPrice - minPrice)
            .divide(minPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()
    }

    /**
     * 상태 조회
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "apiErrorCounts" to apiErrorCounts.toMap(),
            "lastApiSuccess" to lastApiSuccess.mapValues { it.value.toString() },
            "priceHistorySizes" to priceHistory.mapValues { it.value.size }
        )
    }

    // 내부 데이터 클래스
    private data class SpreadCheck(
        val spreadPercent: Double,
        val midPrice: BigDecimal,
        val bestBid: BigDecimal,
        val bestAsk: BigDecimal
    )

    private data class LiquidityCheck(
        val liquidityRatio: Double,
        val bidDepthKrw: BigDecimal,
        val askDepthKrw: BigDecimal
    )

    private data class VolatilityCheck(
        val volatility1Min: Double
    )

    private data class ApiHealthCheck(
        val healthy: Boolean,
        val reason: String
    )
}

/**
 * 시장 상태 검사 결과
 */
data class MarketConditionResult(
    val canTrade: Boolean,
    val severity: ConditionSeverity,
    val issues: List<String>,
    val spread: Double?,
    val midPrice: BigDecimal? = null,
    val bestBid: BigDecimal? = null,
    val bestAsk: BigDecimal? = null,
    val liquidityRatio: Double?,
    val bidDepthKrw: BigDecimal? = null,
    val askDepthKrw: BigDecimal? = null,
    val volatility1Min: Double?,
    val orderBookImbalance: Double = 0.0
) {
    fun toSummary(): String {
        val parts = mutableListOf<String>()
        spread?.let { parts.add("스프레드: ${String.format("%.3f", it)}%") }
        liquidityRatio?.let { parts.add("유동성: ${String.format("%.1f", it)}x") }
        volatility1Min?.let { parts.add("변동성: ${String.format("%.2f", it)}%") }
        if (orderBookImbalance != 0.0) {
            parts.add("불균형: ${String.format("%.2f", orderBookImbalance)}")
        }
        return parts.joinToString(", ")
    }
}

enum class ConditionSeverity {
    NORMAL,
    WARNING,
    HIGH,
    CRITICAL
}
