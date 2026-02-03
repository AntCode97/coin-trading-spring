package com.ant.cointrading.portfolio

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Execution Optimizer
 *
 * 시장 영향력(Market Impact) 최소화 및 최적 실행 전략
 * - Almgren-Chriss Market Impact Model
 * - TWAP (Time-Weighted Average Price) 실행
 * - VWAP (Volume-Weighted Average Price) 추정
 */
@Service
class ExecutionOptimizer {
    private val log = LoggerFactory.getLogger(ExecutionOptimizer::class.java)

    companion object {
        // Almgren-Chriss Model 파라미터
        private const val DEFAULT_ALPHA = 0.1   // Temporary impact coefficient
        private const val DEFAULT_BETA = 0.01   // Permanent impact coefficient
        private const val DEFAULT_ALPHA_CRYPTO = 0.2  // 암호화폐용 (더 높은 영향력)
        private const val DEFAULT_BETA_CRYPTO = 0.02

        // TWAP 기본 설정
        private const val DEFAULT_SLICE_INTERVAL = 5  // 5분 간격
    }

    /**
     * Order Book 데이터
     */
    data class OrderBookLevel(
        val price: Double,
        val quantity: Double
    )

    data class OrderBook(
        val bids: List<OrderBookLevel>,  // 매수 호가
        val asks: List<OrderBookLevel>   // 매도 호가
    )

    /**
     * Market Impact 추정 결과
     */
    data class MarketImpactResult(
        val temporaryImpact: Double,     // 일시적 영향 (bp)
        val permanentImpact: Double,     // 영구적 영향 (bp)
        val totalImpact: Double,         // 총 영향 (bp)
        val estimatedPrice: Double,      // 추정 실행 평균가
        val impactCost: Double           // 영향력 비용 (KRW)
    )

    /**
     * TWAP 실행 슬라이스
     */
    data class ExecutionSlice(
        val sliceIndex: Int,
        val quantity: Double,
        val targetTime: Int,  // 분 단위
        val cumulativeQuantity: Double
    )

    /**
     * VWAP 추정 결과
     */
    data class VWAPResult(
        val vwap: Double,
        val totalQuantity: Double,
        val totalValue: Double,
        val averageSlippage: Double,  // 평균 슬리피지 (bp)
        val levelsUsed: Int
    )

    /**
     * Almgren-Chriss Model 기반 Market Impact 추정
     *
     * Total Cost = Temporary Impact + Permanent Impact
     *
     * Temporary Impact = a × (X/V)^(1-α)
     * Permanent Impact = b × (X/V)
     *
     * X: 실행 수량
     * V: 일일 거래량
     * α: 0.5~1.0 (암호화폐는 0.7~0.9)
     *
     * @param orderSize 주문 수량
     * @param dailyVolume 일일 거래량
     * @param currentPrice 현재가
     * @param isCrypto 암호화폐 여부
     * @return Market Impact 결과
     */
    fun estimateMarketImpact(
        orderSize: Double,
        dailyVolume: Double,
        currentPrice: Double,
        isCrypto: Boolean = true,
        alpha: Double = 0.8,  // 호가 영향력 지수
        customA: Double? = null,
        customB: Double? = null
    ): MarketImpactResult {
        val a = customA ?: if (isCrypto) DEFAULT_ALPHA_CRYPTO else DEFAULT_ALPHA
        val b = customB ?: if (isCrypto) DEFAULT_BETA_CRYPTO else DEFAULT_BETA

        val participationRate = orderSize / dailyVolume

        // 일시적 영향: a × (X/V)^(1-α)
        val exponent = 1 - alpha
        val tempBase = if (participationRate > 0) participationRate else 0.001
        val temporaryImpact = if (exponent == 0.5) {
            a * sqrt(tempBase)
        } else if (exponent == 1.0) {
            a * tempBase
        } else {
            a * tempBase.pow(exponent)
        }

        // 영구적 영향: b × (X/V)
        val permanentImpact = b * participationRate

        // 총 영향력 (bp 단위)
        val totalImpact = temporaryImpact + permanentImpact

        // 추정 실행 평균가
        val priceAdjustment = currentPrice * (totalImpact / 10000)  // bp를 %
        val estimatedPrice = if (orderSize > 0) {
            currentPrice + priceAdjustment  // 매수: 가격 상승
        } else {
            currentPrice - priceAdjustment  // 매도: 가격 하락
        }

        // 영향력 비용 (KRW)
        val impactCost = abs(orderSize * priceAdjustment)

        log.info("Market Impact 추정: Size=$orderSize, Volume=$dailyVolume, " +
                "Temp=${temporaryImpact}bp, Perm=${permanentImpact}bp, Total=${totalImpact}bp")

        return MarketImpactResult(
            temporaryImpact = temporaryImpact,
            permanentImpact = permanentImpact,
            totalImpact = totalImpact,
            estimatedPrice = estimatedPrice,
            impactCost = impactCost
        )
    }

    /**
     * TWAP (Time-Weighted Average Price) 실행 슬라이스 계산
     *
     * @param totalQuantity 총 주문 수량
     * @param executionWindow 실행 윈도우 (분 단위)
     * @param sliceInterval 슬라이스 간격 (분 단위)
     * @return TWAP 실행 슬라이스 목록
     */
    fun calculateTWAPSlices(
        totalQuantity: Double,
        executionWindow: Int,  // 분 단위
        sliceInterval: Int = DEFAULT_SLICE_INTERVAL
    ): List<ExecutionSlice> {
        val nSlices = executionWindow / sliceInterval
        val sliceSize = totalQuantity / nSlices

        val slices = mutableListOf<ExecutionSlice>()
        var cumulativeQty = 0.0

        for (i in 0 until nSlices) {
            cumulativeQty += sliceSize
            slices.add(
                ExecutionSlice(
                    sliceIndex = i + 1,
                    quantity = sliceSize,
                    targetTime = (i + 1) * sliceInterval,
                    cumulativeQuantity = cumulativeQty
                )
            )
        }

        log.info("TWAP 슬라이스 생성: Total=$totalQuantity, Window=${executionWindow}분, " +
                "${nSlices}개 슬라이스")

        return slices
    }

    /**
     * VWAP (Volume-Weighted Average Price) 추정
     *
     * @param orderBook 호가창 데이터
     * @param quantity 실행하려는 수량
     * @param isBuy 매수 여부
     * @return VWAP 결과
     */
    fun estimateVWAP(
        orderBook: OrderBook,
        quantity: Double,
        isBuy: Boolean = true
    ): VWAPResult {
        val levels = if (isBuy) orderBook.asks else orderBook.bids
        var remainingQty = abs(quantity)
        var totalValue = 0.0
        var totalExecuted = 0.0
        var levelsUsed = 0

        for ((index, level) in levels.withIndex()) {
            if (remainingQty <= 0) break

            val execQty = if (remainingQty < level.quantity) remainingQty else level.quantity
            totalValue += execQty * level.price
            totalExecuted += execQty
            remainingQty -= execQty
            levelsUsed = index + 1
        }

        val vwap = if (totalExecuted > 0) totalValue / totalExecuted else 0.0

        // 평균 슬리피지 계산
        val bestPrice = if (isBuy && levels.isNotEmpty()) levels.first().price else 0.0
        val avgSlippage = if (bestPrice > 0) {
            if (isBuy) {
                ((vwap - bestPrice) / bestPrice) * 10000  // bp
            } else {
                ((bestPrice - vwap) / bestPrice) * 10000
            }
        } else {
            0.0
        }

        log.info("VWAP 추정: Qty=$quantity, VWAP=$vwap, " +
                "Slippage=${avgSlippage}bp, Levels=$levelsUsed")

        return VWAPResult(
            vwap = vwap,
            totalQuantity = totalExecuted,
            totalValue = totalValue,
            averageSlippage = avgSlippage,
            levelsUsed = levelsUsed
        )
    }

    /**
     * 최적 실행 크기 계산 (Market Impact 최소화)
     *
     * @param targetValue 목표 실행 금액 (KRW)
     * @param currentPrice 현재가
     * @param dailyVolume 일일 거래량 (개수)
     * @param maxImpactBp 허용 가능한 최대 영향력 (bp)
     * @return 최적 실행 크기
     */
    fun calculateOptimalExecutionSize(
        targetValue: Double,
        currentPrice: Double,
        dailyVolume: Double,
        maxImpactBp: Double = 10.0,  // 기본 10bp
        isCrypto: Boolean = true
    ): Double {
        val targetQuantity = targetValue / currentPrice

        // Market Impact 추정
        val impact = estimateMarketImpact(targetQuantity, dailyVolume, currentPrice, isCrypto)

        // 영향력이 허용 범위 내인지 확인
        return if (impact.totalImpact <= maxImpactBp) {
            targetQuantity  // 전량 실행 가능
        } else {
            // 영향력 제한 내에서 최대 실행 가능 수량
            // 간단 근사: 선형 비례 축소
            val scaleFactor = maxImpactBp / impact.totalImpact
            targetQuantity * scaleFactor
        }
    }

    /**
     * 여러 날에 걸친 실행 스케줄 생성 (Large Order)
     *
     * @param totalQuantity 총 실행 수량
     * @param dailyVolume 일일 거래량
     * @param maxDailyParticipationRate 일일 최대 참여율 (기본 5%)
     * @return 일일 실행 수량 목록
     */
    fun calculateMultiDayExecutionSchedule(
        totalQuantity: Double,
        dailyVolume: Double,
        maxDailyParticipationRate: Double = 0.05
    ): List<Double> {
        val dailyExecQty = dailyVolume * maxDailyParticipationRate
        val daysRequired = kotlin.math.ceil(totalQuantity / dailyExecQty).toInt()

        val schedule = mutableListOf<Double>()
        var remaining = totalQuantity

        for (day in 0 until daysRequired) {
            val execQty = minOf(remaining, dailyExecQty)
            schedule.add(execQty)
            remaining -= execQty
        }

        log.info("멀티데이 실행 스케줄: Total=$totalQuantity, " +
                "${daysRequired}일 예상, 일일 평균=${dailyExecQty}")

        return schedule
    }

    /**
     * 실시간 호가 유동성 분석
     *
     * @param orderBook 호가창 데이터
     * @param depth 분석 깊이 (호가 레벨 수)
     * @return 유동성 메트릭
     */
    fun analyzeLiquidity(
        orderBook: OrderBook,
        depth: Int = 5
    ): Map<String, Double> {
        val bids = orderBook.bids.take(depth)
        val asks = orderBook.asks.take(depth)

        val bidVolume = bids.sumOf { it.quantity }
        val askVolume = asks.sumOf { it.quantity }
        val totalVolume = bidVolume + askVolume

        val spread = if (asks.isNotEmpty() && bids.isNotEmpty()) {
            (asks.first().price - bids.first().price) / bids.first().price * 100
        } else {
            0.0
        }

        val bidValue = bids.sumOf { it.price * it.quantity }
        val askValue = asks.sumOf { it.price * it.quantity }

        // Imbalance = (Bid - Ask) / (Bid + Ask)
        val imbalance = if (totalVolume > 0) {
            (bidVolume - askVolume) / totalVolume
        } else {
            0.0
        }

        return mapOf(
            "bidVolume" to bidVolume,
            "askVolume" to askVolume,
            "totalVolume" to totalVolume,
            "spreadPercent" to spread,
            "bidValue" to bidValue,
            "askValue" to askValue,
            "imbalance" to imbalance,
            "depthAnalyzed" to depth.toDouble()
        )
    }

    /**
     * 최적 실행 시점 추천
     *
     * @param liquidityMetrics 유동성 메트릭
     * @param currentVolatility 현재 변동성
     * @return 실행 추천 (0~100 점수)
     */
    fun recommendExecutionTiming(
        liquidityMetrics: Map<String, Double>,
        currentVolatility: Double
    ): Map<String, Any> {
        var score = 50.0  // 기본 점수

        // 유동성 보너스
        val totalVolume = liquidityMetrics["totalVolume"] ?: 0.0
        if (totalVolume > 100.0) score += 20
        else if (totalVolume > 50.0) score += 10
        else if (totalVolume < 10.0) score -= 20

        // 스프레드 페널티
        val spread = liquidityMetrics["spreadPercent"] ?: 0.0
        if (spread < 0.05) score += 10
        else if (spread > 0.2) score -= 15

        // Imbalance 고려
        val imbalance = liquidityMetrics["imbalance"] ?: 0.0
        if (abs(imbalance) > 0.3) {
            // 극단적 불균형: 기다릴 필요 있음
            score -= 10
        }

        // 변동성 페널티
        if (currentVolatility > 0.05) score -= 15  // 고변동: 실행 보류
        else if (currentVolatility < 0.01) score += 10  // 저변동: 적절

        score = score.coerceIn(0.0, 100.0)

        val recommendation = when {
            score >= 70 -> "지금 실행 추천"
            score >= 50 -> "보통: 주의 깊게 실행"
            score >= 30 -> "보류: 더 좋은 타이밍 대기"
            else -> "불가: 시장 상태 악화"
        }

        return mapOf(
            "score" to score,
            "recommendation" to recommendation,
            "factors" to mapOf(
                "liquidityScore" to (totalVolume.coerceIn(0.0, 100.0)),
                "spreadScore" to maxOf(0.0, 100 - spread * 500),
                "volatilityScore" to maxOf(0.0, 100 - currentVolatility * 1000)
            )
        )
    }
}
