package com.ant.cointrading.fundingarb

import com.ant.cointrading.api.binance.BinanceFuturesApi
import com.ant.cointrading.api.binance.BinancePremiumIndex
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.TickerInfo
import com.ant.cointrading.config.FundingArbitrageProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.FundingArbPositionEntity
import com.ant.cointrading.repository.FundingArbPositionRepository
import com.ant.cointrading.repository.FundingRateRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class FundingRateArbitrageEngine(
    private val bithumbPublicApi: BithumbPublicApi,
    private val binanceFuturesApi: BinanceFuturesApi,
    private val fundingRateRepository: FundingRateRepository,
    private val positionRepository: FundingArbPositionRepository,
    private val slackNotifier: SlackNotifier,
    private val properties: FundingArbitrageProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val lastFundingRates = ConcurrentHashMap<String, FundingRateInfo>()
    private val openPositions = ConcurrentHashMap<Long, FundingArbPositionEntity>()

    data class FundingRateInfo(
        val symbol: String,
        val currentRate: Double,
        val annualizedRate: Double,
        val nextFundingTime: Instant
    )

    @PostConstruct
    fun init() {
        if (!properties.enabled) {
            log.info("Funding Rate Arbitrage Engine 비활성화")
            return
        }

        log.info("Funding Rate Arbitrage Engine 초기화")
        log.info("자동 거래: ${properties.autoTradingEnabled}")
        log.info("최소 연환산 수익률: ${properties.minAnnualizedRate}%")
        log.info("최대 자본: ${properties.maxCapitalRatio}%")
        log.info("대상 심볼: ${properties.symbols.size}개")

        if (properties.autoTradingEnabled) {
            restoreOpenPositions()
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun monitorAndTrade() {
        if (!properties.enabled || !properties.autoTradingEnabled) return

        try {
            val opportunities = binanceFuturesApi.getAllPremiumIndex() ?: return

            opportunities.forEach { premiumIndex ->
                val fundingRate = premiumIndex.lastFundingRate.toDouble()
                val annualizedRate = fundingRate * 3 * 365 * 100

                if (annualizedRate >= properties.minAnnualizedRate) {
                    val opportunity = FundingRateInfo(
                        symbol = premiumIndex.symbol,
                        currentRate = fundingRate,
                        annualizedRate = annualizedRate,
                        nextFundingTime = Instant.ofEpochMilli(premiumIndex.nextFundingTime)
                    )
                    processOpportunity(opportunity)
                }
            }

            monitorExistingPositions()

        } catch (e: Exception) {
            log.error("Funding Rate Arbitrage 모니터링 실패: ${e.message}", e)
        }
    }

    private fun processOpportunity(opportunity: FundingRateInfo) {
        val symbol = opportunity.symbol

        val lastInfo = lastFundingRates[symbol]
        val shouldProceed = shouldEnterPosition(opportunity, lastInfo)

        if (!shouldProceed) {
            log.debug("[$symbol] 진입 조건 미충족")
            return
        }

        val existingPositions = positionRepository.findBySymbolAndStatus(symbol, "OPEN")
        if (existingPositions.isNotEmpty()) {
            log.debug("[$symbol] 이미 OPEN 포지션 존재")
            return
        }

        openPosition(opportunity)
    }

    private fun shouldEnterPosition(
        current: FundingRateInfo,
        lastInfo: FundingRateInfo?
    ): Boolean {
        val symbol = current.symbol
        val now = Instant.now()

        if (current.annualizedRate < properties.minAnnualizedRate) {
            log.debug("[$symbol] 연환산 수익률 미달: ${current.annualizedRate}% < ${properties.minAnnualizedRate}%")
            return false
        }

        val minutesUntilFunding = Duration.between(now, current.nextFundingTime).toMinutes()
        if (minutesUntilFunding > properties.maxMinutesUntilFunding) {
            log.debug("[$symbol] 펀딩까지 시간 초과: ${minutesUntilFunding}분 > ${properties.maxMinutesUntilFunding}분")
            return false
        }

        if (lastInfo != null && lastInfo.nextFundingTime == current.nextFundingTime) {
            return false
        }

        val openPositionCount = positionRepository.countOpenPositions()
        if (openPositionCount >= properties.maxPositions) {
            log.warn("최대 포지션 도달: $openPositionCount / ${properties.maxPositions}")
            return false
        }

        return true
    }

    private fun openPosition(opportunity: FundingRateInfo) {
        val symbol = opportunity.symbol
        val premiumIndex = binanceFuturesApi.getPremiumIndex(symbol) ?: run {
            log.error("[$symbol] 프리미엄 인덱스 조회 실패")
            return
        }

        val bithumbSymbol = symbol.replace("USDT", "_KRW")

        val spotPrice = try {
            val tickers = bithumbPublicApi.getCurrentPrice(bithumbSymbol)
            tickers?.firstOrNull()?.tradePrice?.toDouble()
        } catch (e: Exception) {
            log.error("[$bithumbSymbol] 현물 시가 조회 실패: ${e.message}")
            return
        }

        if (spotPrice == null) return

        val positionSizeKrw = calculatePositionSize(opportunity.annualizedRate, spotPrice)

        val spotQuantity = positionSizeKrw / spotPrice
        val perpQuantity = spotQuantity * (premiumIndex.indexPrice.toDouble() / spotPrice)

        if (positionSizeKrw < properties.minPositionKrw) {
            log.warn("[$symbol] 최소 주문 금액 미달: ${positionSizeKrw}원 < ${properties.minPositionKrw}원")
            return
        }

        val position = FundingArbPositionEntity(
            symbol = symbol,
            spotExchange = "BITHUMB",
            perpExchange = "BINANCE",
            spotQuantity = spotQuantity,
            perpQuantity = perpQuantity,
            spotEntryPrice = spotPrice,
            perpEntryPrice = premiumIndex.indexPrice.toDouble(),
            entryFundingRate = opportunity.currentRate,
            entryTime = Instant.now(),
            status = "OPEN"
        )

        val savedPosition = positionRepository.save(position)
        openPositions[savedPosition.id!!] = savedPosition

        log.info("[$symbol] 포지션 진입")
        slackNotifier.sendSystemNotification(
            "Funding Arbitrage 포지션 진입",
            "[$symbol] 펀딩 수익 포지션 진입"
        )

        lastFundingRates[symbol] = opportunity
    }

    private fun calculatePositionSize(annualizedRate: Double, spotPrice: Double): Long {
        val capitalKrw = 10_000_000.0

        val kellyFraction = annualizedRate / 100.0 * properties.maxCapitalRatio
        val maxPositionKrw = capitalKrw * properties.maxCapitalRatio

        val positionKrw = minOf(
            capitalKrw * kellyFraction,
            maxPositionKrw,
            properties.maxSinglePositionKrw.toDouble()
        ).toLong()

        return positionKrw.coerceIn(properties.minPositionKrw, properties.maxSinglePositionKrw)
    }

    private fun monitorExistingPositions() {
        val openPositions = positionRepository.findByStatus("OPEN")

        openPositions.forEach { position ->
            val shouldCollectFunding = shouldCollectFunding(position)

            if (shouldCollectFunding) {
                collectFunding(position)
            }

            val premiumIndex = binanceFuturesApi.getPremiumIndex(position.symbol)
            if (premiumIndex != null) {
                val currentFundingRate = premiumIndex.lastFundingRate.toDouble()

                val shouldClose = shouldClosePosition(
                    position,
                    currentFundingRate,
                    premiumIndex.indexPrice.toDouble()
                )

                if (shouldClose) {
                    closePosition(position, premiumIndex.indexPrice.toDouble(), currentFundingRate)
                }
            }
        }
    }

    private fun shouldCollectFunding(position: FundingArbPositionEntity): Boolean {
        val now = Instant.now()
        val hoursSinceEntry = Duration.between(position.entryTime, now).toHours()

        if (hoursSinceEntry < properties.minHoldingPeriodHours) {
            return false
        }

        if (hoursSinceEntry >= properties.maxHoldingPeriodHours) {
            return true
        }

        val premiumIndex = binanceFuturesApi.getPremiumIndex(position.symbol)
        if (premiumIndex != null) {
            val currentFundingRate = premiumIndex.lastFundingRate.toDouble()

            if (currentFundingRate < properties.fundingRateReversalThreshold) {
                return true
            }
        }

        return false
    }

    private fun collectFunding(position: FundingArbPositionEntity) {
        val fundingPayment = position.perpQuantity * position.entryFundingRate!! * 3 * 365

        position.totalFundingReceived += fundingPayment

        log.info("[$position.symbol] 펀딩 수수료 청산: ${fundingPayment}")

        positionRepository.save(position)
    }

    private fun shouldClosePosition(
        position: FundingArbPositionEntity,
        currentFundingRate: Double,
        currentPerpPrice: Double
    ): Boolean {
        val hoursSinceEntry = Duration.between(position.entryTime, Instant.now()).toHours()

        if (hoursSinceEntry < properties.minHoldingPeriodHours) {
            return false
        }

        if (currentFundingRate < properties.fundingRateReversalThreshold) {
            log.info("[$position.symbol] 펀딩 비율 반전: ${position.entryFundingRate} → $currentFundingRate")
            return true
        }

        if (hoursSinceEntry >= properties.maxHoldingPeriodHours) {
            return true
        }

        return false
    }

    private fun closePosition(
        position: FundingArbPositionEntity,
        currentPerpPrice: Double,
        currentFundingRate: Double
    ) {
        val symbol = position.symbol
        val exitTime = Instant.now()

        val spotPnL = (currentPerpPrice - position.spotEntryPrice!!) * position.spotQuantity
        val perpPnL = (position.perpEntryPrice!! - currentPerpPrice) * position.perpQuantity
        val fundingPnL = position.totalFundingReceived

        val totalPnL = spotPnL + perpPnL + fundingPnL

        val totalTradingCost = kotlin.math.abs(spotPnL) + kotlin.math.abs(perpPnL)

        position.netPnl = totalPnL
        position.totalTradingCost = totalTradingCost
        position.exitTime = exitTime
        position.status = "CLOSED"
        position.exitReason = determineExitReason(position, currentFundingRate)
        position.updatedAt = Instant.now()

        positionRepository.save(position)
        openPositions.remove(position.id)

        val pnlPercent = (totalPnL / totalTradingCost) * 100

        log.info("[$symbol] 포지션 청산")

        slackNotifier.sendSystemNotification(
            "Funding Arbitrage 포지션 청산",
            "[$symbol] 포지션 청산"
        )
    }

    private fun determineExitReason(
        position: FundingArbPositionEntity,
        currentFundingRate: Double
    ): String {
        if (currentFundingRate < properties.fundingRateReversalThreshold) {
            return "FUNDING_RATE_REVERSAL"
        }

        val hoursSinceEntry = Duration.between(position.entryTime, Instant.now()).toHours()
        if (hoursSinceEntry >= properties.maxHoldingPeriodHours) {
            return "MAX_HOLDING_PERIOD"
        }

        val currentPnL = position.netPnl ?: 0.0
        if (currentPnL < 0) {
            return "DELTA_IMBALANCE"
        }

        return "FUNDING_COLLECTED"
    }

    private fun restoreOpenPositions() {
        val openPositions = positionRepository.findByStatus("OPEN")

        if (openPositions.isNotEmpty()) {
            log.info("${openPositions.size}건 OPEN 포지션 복원 중...")

            openPositions.forEach { position ->
                this.openPositions[position.id!!] = position
                log.info("[${position.symbol}] OPEN 포지션 복원 - 진입가: ${position.spotEntryPrice}")
            }

            log.info("=== ${openPositions.size}건 OPEN 포지션 복원 완료 ===")
        } else {
            log.info("복원할 OPEN 포지션 없음")
        }
    }

    fun getStatus(): ArbitrageEngineStatus {
        val openPositions = positionRepository.findByStatus("OPEN")
        val totalPnL = positionRepository.sumNetPnl()

        return ArbitrageEngineStatus(
            enabled = properties.enabled,
            autoTradingEnabled = properties.autoTradingEnabled,
            openPositionsCount = openPositions.size,
            openPositions = openPositions.map { position ->
                ArbitragePositionStatus(
                    id = position.id!!,
                    symbol = position.symbol,
                    spotPrice = position.spotEntryPrice,
                    perpPrice = position.perpEntryPrice,
                    entryTime = position.entryTime,
                    fundingRate = position.entryFundingRate,
                    totalFundingReceived = position.totalFundingReceived,
                    netPnl = position.netPnl,
                    status = position.status
                )
            },
            totalPnL = totalPnL ?: 0.0,
            lastCheckTime = Instant.now()
        )
    }
}

data class ArbitrageEngineStatus(
    val enabled: Boolean,
    val autoTradingEnabled: Boolean,
    val openPositionsCount: Int,
    val openPositions: List<ArbitragePositionStatus>,
    val totalPnL: Double,
    val lastCheckTime: Instant
)

data class ArbitragePositionStatus(
    val id: Long,
    val symbol: String,
    val spotPrice: Double?,
    val perpPrice: Double?,
    val entryTime: Instant,
    val fundingRate: Double?,
    val totalFundingReceived: Double,
    val netPnl: Double?,
    val status: String
)
