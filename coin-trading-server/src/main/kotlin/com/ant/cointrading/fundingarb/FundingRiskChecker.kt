package com.ant.cointrading.fundingarb

import com.ant.cointrading.api.binance.BinanceFuturesApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.FundingArbPositionEntity
import com.ant.cointrading.repository.FundingArbPositionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class FundingRiskChecker(
    private val bithumbPublicApi: BithumbPublicApi,
    private val binanceFuturesApi: BinanceFuturesApi,
    private val positionRepository: FundingArbPositionRepository,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val riskHistory = ConcurrentHashMap<Long, List<RiskEvent>>()

    data class RiskEvent(
        val type: RiskType,
        val severity: RiskSeverity,
        val message: String,
        val timestamp: Instant
    )

    enum class RiskType {
        DELTA_IMBALANCE,
        MARGIN_LIQUIDATION,
        FUNDING_RATE_REVERSAL,
        MAX_HOLDING_PERIOD,
        EXCESSIVE_FUNDING_PAYMENT
    }

    enum class RiskSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    @Scheduled(fixedDelay = 60_000)
    fun checkAllPositions() {
        val openPositions = positionRepository.findByStatus("OPEN")

        openPositions.forEach { position ->
            checkPosition(position)
        }
    }

    fun checkPosition(position: FundingArbPositionEntity): List<RiskEvent> {
        val events = mutableListOf<RiskEvent>()
        val currentPerpPrice = getCurrentPerpPrice(position.symbol)
        val currentSpotPrice = getCurrentSpotPrice(position.symbol)

        val spotPnl = calculateSpotPnl(position, currentSpotPrice, currentPerpPrice)
        val perpPnl = calculatePerpPnl(position, currentPerpPrice)
        val deltaPnl = spotPnl + perpPnl

        val hoursHeld = Duration.between(position.entryTime, Instant.now()).toHours()

        if (checkDeltaImbalance(spotPnl, perpPnl)) {
            val event = RiskEvent(
                type = RiskType.DELTA_IMBALANCE,
                severity = calculateImbalanceSeverity(spotPnl, perpPnl),
                message = "델타 불균형 감지 - 현물 PnL: $spotPnl, 선물 PnL: $perpPnl",
                timestamp = Instant.now()
            )
            events.add(event)
            handleRisk(position, event)
        }

        if (checkMarginLiquidationRisk(position, perpPnl)) {
            val event = RiskEvent(
                type = RiskType.MARGIN_LIQUIDATION,
                severity = RiskSeverity.CRITICAL,
                message = "마진 청산 리스크 - 선물 PnL: $perpPnl",
                timestamp = Instant.now()
            )
            events.add(event)
            handleRisk(position, event)
        }

        if (hoursHeld > 48 && checkFundingRateReversal(position)) {
            val event = RiskEvent(
                type = RiskType.FUNDING_RATE_REVERSAL,
                severity = RiskSeverity.HIGH,
                message = "펀딩 비율 반전 감지 (${hoursHeld.toInt()}시간 보유)",
                timestamp = Instant.now()
            )
            events.add(event)
            handleRisk(position, event)
        }

        if (hoursHeld > 72) {
            val event = RiskEvent(
                type = RiskType.MAX_HOLDING_PERIOD,
                severity = RiskSeverity.MEDIUM,
                message = "최대 보유 기간 도달 (${hoursHeld.toInt()}시간)",
                timestamp = Instant.now()
            )
            events.add(event)
            handleRisk(position, event)
        }

        val fundingPerHour = position.totalFundingReceived / maxOf(hoursHeld, 1)
        if (fundingPerHour < 0) {
            val event = RiskEvent(
                type = RiskType.EXCESSIVE_FUNDING_PAYMENT,
                severity = RiskSeverity.MEDIUM,
                message = "펀딩 비용 지불 - 시간당 $fundingPerHour",
                timestamp = Instant.now()
            )
            events.add(event)
            handleRisk(position, event)
        }

        riskHistory[position.id ?: 0] = events

        return events
    }

    private fun handleRisk(position: FundingArbPositionEntity, event: RiskEvent) {
        val alert = when (event.severity) {
            RiskSeverity.LOW -> false
            RiskSeverity.MEDIUM -> true
            RiskSeverity.HIGH -> true
            RiskSeverity.CRITICAL -> true
        }

        if (alert) {
            log.warn("[${position.symbol}] 리스크 감지 [${event.type}]: ${event.message}")

            slackNotifier.sendSystemNotification(
                "Funding Arbitrage 리스크 경고",
                "[${position.symbol}] ${event.type}\n" +
                "심각도: ${event.severity}\n" +
                "내용: ${event.message}"
            )
        }
    }

    fun shouldClosePosition(position: FundingArbPositionEntity): Boolean {
        val events = riskHistory[position.id ?: 0] ?: return false

        return events.any { event ->
            when (event.type) {
                RiskType.MARGIN_LIQUIDATION -> true
                RiskType.DELTA_IMBALANCE -> event.severity == RiskSeverity.CRITICAL
                RiskType.FUNDING_RATE_REVERSAL -> true
                RiskType.MAX_HOLDING_PERIOD -> true
                RiskType.EXCESSIVE_FUNDING_PAYMENT -> event.severity == RiskSeverity.CRITICAL
            }
        }
    }

    fun getPositionRiskScore(position: FundingArbPositionEntity): Int {
        val events = riskHistory[position.id ?: 0] ?: return 0

        return events.sumOf { event ->
            when (event.severity) {
                RiskSeverity.LOW -> 10
                RiskSeverity.MEDIUM -> 25
                RiskSeverity.HIGH -> 50
                RiskSeverity.CRITICAL -> 100
            }
        }
    }

    private fun checkDeltaImbalance(spotPnl: Double, perpPnl: Double): Boolean {
        if (spotPnl == 0.0 || perpPnl == 0.0) return false

        val totalPnl = spotPnl + perpPnl
        val maxPnl = maxOf(spotPnl, perpPnl)

        val imbalanceRatio = kotlin.math.abs(totalPnl) / maxPnl
        return imbalanceRatio > 0.15
    }

    private fun calculateImbalanceSeverity(spotPnl: Double, perpPnl: Double): RiskSeverity {
        if (spotPnl == 0.0 || perpPnl == 0.0) return RiskSeverity.LOW

        val totalPnl = spotPnl + perpPnl
        val maxPnl = maxOf(spotPnl, perpPnl)
        val imbalanceRatio = kotlin.math.abs(totalPnl) / maxPnl

        return when {
            imbalanceRatio > 0.30 -> RiskSeverity.CRITICAL
            imbalanceRatio > 0.20 -> RiskSeverity.HIGH
            imbalanceRatio > 0.15 -> RiskSeverity.MEDIUM
            else -> RiskSeverity.LOW
        }
    }

    private fun checkMarginLiquidationRisk(position: FundingArbPositionEntity, perpPnl: Double): Boolean {
        val positionValue = position.perpEntryPrice?.times(position.perpQuantity) ?: 0.0
        val pnlPercent = if (positionValue != 0.0) (perpPnl / positionValue) * 100 else 0.0

        return pnlPercent < -20
    }

    private fun checkFundingRateReversal(position: FundingArbPositionEntity): Boolean {
        val currentRate = getCurrentFundingRate(position.symbol)
        val entryRate = position.entryFundingRate ?: 0.0

        if (currentRate == 0.0) return false

        val rateChange = currentRate - entryRate
        return rateChange < -0.0001
    }

    private fun calculateSpotPnl(position: FundingArbPositionEntity, spotPrice: Double, perpPrice: Double): Double {
        val entryPrice = position.spotEntryPrice ?: 0.0
        return (spotPrice - entryPrice) * position.spotQuantity
    }

    private fun calculatePerpPnl(position: FundingArbPositionEntity, perpPrice: Double): Double {
        val entryPrice = position.perpEntryPrice ?: 0.0
        return (entryPrice - perpPrice) * (position.perpQuantity ?: position.spotQuantity)
    }

    private fun getCurrentSpotPrice(symbol: String): Double {
        val bithumbMarket = symbol.replace("USDT", "_KRW")

        return try {
            val tickers = bithumbPublicApi.getCurrentPrice(bithumbMarket)
            tickers?.firstOrNull()?.tradePrice?.toDouble() ?: 0.0
        } catch (e: Exception) {
            log.error("현물 시가 조회 실패 [$bithumbMarket]: ${e.message}")
            0.0
        }
    }

    private fun getCurrentPerpPrice(symbol: String): Double {
        return try {
            val premiumIndex = binanceFuturesApi.getPremiumIndex(symbol)
            premiumIndex?.indexPrice?.toDouble() ?: 0.0
        } catch (e: Exception) {
            log.error("선물 시가 조회 실패 [$symbol]: ${e.message}")
            0.0
        }
    }

    private fun getCurrentFundingRate(symbol: String): Double {
        return try {
            val premiumIndex = binanceFuturesApi.getPremiumIndex(symbol)
            premiumIndex?.lastFundingRate?.toDouble() ?: 0.0
        } catch (e: Exception) {
            log.error("펀딩 비율 조회 실패 [$symbol]: ${e.message}")
            0.0
        }
    }

    fun calculatePositionConfidence(position: FundingArbPositionEntity): Double {
        val riskScore = getPositionRiskScore(position)
        return (100.0 - riskScore).coerceIn(0.0, 100.0)
    }
}
