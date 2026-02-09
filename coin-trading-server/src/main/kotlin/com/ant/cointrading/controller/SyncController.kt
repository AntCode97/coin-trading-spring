package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.dca.DcaEngine
import com.ant.cointrading.engine.PositionHelper
import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 빗썸 실제 잔고/주문 내역과 DB 동기화 컨트롤러
 *
 * DB에 포지션이 있지만 실제 잔고가 없는 경우를 처리합니다.
 * 실제 체결 내역을 확인하여 진짜 매도되었는지 검증합니다.
 */
@RestController
@RequestMapping("/api/sync")
class SyncController(
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val bithumbPublicApi: BithumbPublicApi,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository,
    private val dcaPositionRepository: DcaPositionRepository,
    private val memeScalperEngine: MemeScalperEngine,
    private val volumeSurgeEngine: VolumeSurgeEngine,
    private val dcaEngine: DcaEngine
) {
    private val log = LoggerFactory.getLogger(SyncController::class.java)

    /**
     * 실제 잔고와 DB 포지션 동기화
     * - 빗썸 체결 내역을 확인하여 실제 매도 여부를 검증
     * - 실제 매도 체결이 있으면 해당 정보로 업데이트
     * - 각 마켓별로 최근 500개 주문을 조회하여 수기 매도 누락 방지
     */
    @PostMapping("/positions")
    fun syncPositions(): SyncResult {
        val results = mutableListOf<SyncAction>()
        var fixedCount = 0
        var verifiedCount = 0
        var skippedCount = 0

        try {
            // 실제 잔고 조회
            val actualBalances = try {
                bithumbPrivateApi.getBalances() ?: emptyList()
            } catch (e: Exception) {
                log.error("잔고 조회 실패: ${e.message}")
                return SyncResult(
                    success = false,
                    message = "잔고 조회 실패: ${e.message}",
                    actions = emptyList(),
                    fixedCount = 0,
                    verifiedCount = 0
                )
            }

            // balance(주문 가능) + locked(주문 묶임)을 실제 총 보유 수량으로 간주
            val coinsWithBalance = actualBalances
                .asSequence()
                .filter { it.currency != "KRW" }
                .groupBy { it.currency }
                .mapValues { (_, balances) ->
                    val available = balances.fold(BigDecimal.ZERO) { acc, balance -> acc + balance.balance }
                    val locked = balances.fold(BigDecimal.ZERO) { acc, balance -> acc + (balance.locked ?: BigDecimal.ZERO) }
                    BalanceSnapshot(
                        available = available,
                        locked = locked,
                        total = available + locked
                    )
                }
                .filterValues { it.total > BigDecimal.ZERO }

            log.info("실제 잔고 코인: ${coinsWithBalance.keys}")

            // 모든 OPEN 포지션의 마켓 목록 수집
            val allOpenMarkets = mutableSetOf<String>()
            memeScalperRepository.findByStatus("OPEN").forEach { allOpenMarkets.add(it.market) }
            volumeSurgeRepository.findByStatus("OPEN").forEach { allOpenMarkets.add(it.market) }
            dcaPositionRepository.findByStatus("OPEN").forEach { allOpenMarkets.add(it.market) }

            // 동기화 중 잔고가 존재하는 포지션은 코인 단위로 집계 후 한 번만 비교
            val expectedByCoin = mutableMapOf<String, CoinQuantityExpectation>()

            // 각 마켓별로 체결된 매도 주문 조회 (최근 500개 - 오래된 수기 매도 대응)
            val sellOrdersByMarket = mutableMapOf<String, List<com.ant.cointrading.api.bithumb.OrderResponse>>()

            for (market in allOpenMarkets) {
                val apiMarket = convertToApiMarket(market)
                try {
                    val doneOrders = bithumbPrivateApi.getOrders(apiMarket, "done", 0, 500) ?: emptyList()
                    val sellOrders = doneOrders
                        .filter { it.side == "ask" && it.state == "done" && it.executedVolume != null && it.executedVolume > BigDecimal.ZERO }

                    if (sellOrders.isNotEmpty()) {
                        sellOrdersByMarket[market] = sellOrders
                        log.info("[$market] 체결된 매도 주문 ${sellOrders.size}개 발견")
                    }
                } catch (e: Exception) {
                    log.warn("[$market] 체결 내역 조회 실패: ${e.message}")
                }
            }

            // Meme Scalper 포지션 체크
            memeScalperRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    // 잔고 없음 -> 해당 마켓의 체결 내역 확인
                    val sellOrdersForMarket = sellOrdersByMarket[position.market]

                    // 진입 시간 이후의 매도 주문 찾기 (수기 매도 대응)
                    val matchingSellOrder = sellOrdersForMarket?.find { sellOrder ->
                        val orderTime = parseInstant(sellOrder.createdAt)
                        isMatchingOrder(sellOrder, position.quantity) &&
                        orderTime.isAfter(position.entryTime.minusSeconds(60))  // 진입 60초 전부터 체크
                    }

                    if (matchingSellOrder != null) {
                        // 실제 매도 체결 확인 -> 실제 정보로 CLOSED 처리
                        val executedPrice = matchingSellOrder.price?.toDouble() ?: position.entryPrice
                        val executedTime = parseInstant(matchingSellOrder.createdAt)

                        log.info("[Meme Scalper] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${matchingSellOrder.createdAt}")

                        position.status = "CLOSED"
                        position.exitPrice = executedPrice
                        position.exitTime = executedTime
                        position.exitReason = "SYNC_CONFIRMED"
                        position.pnlAmount = (executedPrice - position.entryPrice) * position.quantity
                        position.pnlPercent = ((executedPrice - position.entryPrice) / position.entryPrice) * 100
                        memeScalperRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Meme Scalper",
                            action = "CLOSED_CONFIRMED",
                            reason = "실제 매도 체결 확인 (가격: ${executedPrice.toInt()}원, 시간: ${formatTime(executedTime)})",
                            dbQuantity = position.quantity,
                            actualQuantity = matchingSellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        // 체결 내역 없음 -> 잔고 0이면 CLOSED로 처리 (수기 매도 후 체결 내역 누락 대응)
                        log.info("[Meme Scalper] ${position.market} 잔고 0, 체결 내역 없음 -> CLOSED로 처리 (수기 매도 의심)")
                        position.status = "CLOSED"
                        position.exitTime = Instant.now()
                        position.exitReason = "SYNC_NO_BALANCE"
                        // PnL은 현재가로 계산 (체결 내역 없으므로)
                        val currentPrice = try {
                            val apiMarket = convertToApiMarket(position.market)
                            bithumbPublicApi.getCurrentPrice(apiMarket)?.firstOrNull()?.tradePrice?.toDouble() ?: position.entryPrice
                        } catch (e: Exception) {
                            position.entryPrice
                        }
                        position.exitPrice = currentPrice
                        position.pnlAmount = (currentPrice - position.entryPrice) * position.quantity
                        position.pnlPercent = ((currentPrice - position.entryPrice) / position.entryPrice) * 100
                        memeScalperRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Meme Scalper",
                            action = "CLOSED_NO_BALANCE",
                            reason = "잔고 0 확인 -> 수기 매도로 간주하고 CLOSED 처리 (추정 PnL: ${String.format("%.2f", position.pnlPercent)}%)",
                            dbQuantity = position.quantity,
                            actualQuantity = 0.0
                        ))
                        fixedCount++
                    }
                } else {
                    registerExpectedQuantity(
                        expectedByCoin = expectedByCoin,
                        coinSymbol = coinSymbol,
                        market = position.market,
                        strategy = "Meme Scalper",
                        quantity = BigDecimal.valueOf(position.quantity)
                    )
                }
            }

            // Volume Surge 포지션 체크
            volumeSurgeRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    // 잔고 없음 -> 해당 마켓의 체결 내역 확인
                    val sellOrdersForMarket = sellOrdersByMarket[position.market]

                    // 진입 시간 이후의 매도 주문 찾기
                    val matchingSellOrder = sellOrdersForMarket?.find { sellOrder ->
                        val orderTime = parseInstant(sellOrder.createdAt)
                        isMatchingOrder(sellOrder, position.quantity) &&
                        orderTime.isAfter(position.entryTime.minusSeconds(60))
                    }

                    if (matchingSellOrder != null) {
                        val executedPrice = matchingSellOrder.price?.toDouble() ?: position.entryPrice
                        val executedTime = parseInstant(matchingSellOrder.createdAt)

                        log.info("[Volume Surge] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${matchingSellOrder.createdAt}")

                        position.status = "CLOSED"
                        position.exitPrice = executedPrice
                        position.exitTime = executedTime
                        position.exitReason = "SYNC_CONFIRMED"
                        position.pnlAmount = (executedPrice - position.entryPrice) * position.quantity
                        position.pnlPercent = ((executedPrice - position.entryPrice) / position.entryPrice) * 100
                        volumeSurgeRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Volume Surge",
                            action = "CLOSED_CONFIRMED",
                            reason = "실제 매도 체결 확인 (가격: ${executedPrice.toInt()}원, 시간: ${formatTime(executedTime)})",
                            dbQuantity = position.quantity,
                            actualQuantity = matchingSellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        // 체결 내역 없음 -> 잔고 0이면 CLOSED로 처리
                        log.info("[Volume Surge] ${position.market} 잔고 0, 체결 내역 없음 -> CLOSED로 처리 (수기 매도 의심)")
                        position.status = "CLOSED"
                        position.exitTime = Instant.now()
                        position.exitReason = "SYNC_NO_BALANCE"
                        val currentPrice = try {
                            val apiMarket = convertToApiMarket(position.market)
                            bithumbPublicApi.getCurrentPrice(apiMarket)?.firstOrNull()?.tradePrice?.toDouble() ?: position.entryPrice
                        } catch (e: Exception) {
                            position.entryPrice
                        }
                        position.exitPrice = currentPrice
                        position.pnlAmount = (currentPrice - position.entryPrice) * position.quantity
                        position.pnlPercent = ((currentPrice - position.entryPrice) / position.entryPrice) * 100
                        volumeSurgeRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Volume Surge",
                            action = "CLOSED_NO_BALANCE",
                            reason = "잔고 0 확인 -> 수기 매도로 간주하고 CLOSED 처리 (추정 PnL: ${String.format("%.2f", position.pnlPercent)}%)",
                            dbQuantity = position.quantity,
                            actualQuantity = 0.0
                        ))
                        fixedCount++
                    }
                } else {
                    registerExpectedQuantity(
                        expectedByCoin = expectedByCoin,
                        coinSymbol = coinSymbol,
                        market = position.market,
                        strategy = "Volume Surge",
                        quantity = BigDecimal.valueOf(position.quantity)
                    )
                }
            }

            // DCA 포지션 체크
            dcaPositionRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    // 잔고 없음 -> 해당 마켓의 체결 내역 확인
                    val sellOrdersForMarket = sellOrdersByMarket[position.market]

                    // 첫 매수 시간 이후의 매도 주문 찾기 (DCA는 createdAt 사용)
                    val matchingSellOrder = sellOrdersForMarket?.find { sellOrder ->
                        val orderTime = parseInstant(sellOrder.createdAt)
                        isMatchingOrderForDca(sellOrder, position.totalQuantity) &&
                        orderTime.isAfter(position.createdAt.minusSeconds(60))
                    }

                    if (matchingSellOrder != null) {
                        val executedPrice = matchingSellOrder.price?.toDouble() ?: position.averagePrice
                        val executedTime = parseInstant(matchingSellOrder.createdAt)

                        log.info("[DCA] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${matchingSellOrder.createdAt}")

                        position.status = "CLOSED"
                        position.exitPrice = executedPrice
                        position.exitedAt = executedTime
                        position.exitReason = "SYNC_CONFIRMED"
                        position.realizedPnl = (executedPrice - position.averagePrice) * position.totalQuantity
                        position.realizedPnlPercent = ((executedPrice - position.averagePrice) / position.averagePrice) * 100
                        dcaPositionRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "DCA",
                            action = "CLOSED_CONFIRMED",
                            reason = "실제 매도 체결 확인 (가격: ${executedPrice.toInt()}원, 시간: ${formatTime(executedTime)})",
                            dbQuantity = position.totalQuantity,
                            actualQuantity = matchingSellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        // 체결 내역 없음 -> 잔고 0이면 CLOSED로 처리
                        log.info("[DCA] ${position.market} 잔고 0, 체결 내역 없음 -> CLOSED로 처리 (수기 매도 의심)")
                        position.status = "CLOSED"
                        position.exitedAt = Instant.now()
                        position.exitReason = "SYNC_NO_BALANCE"
                        val currentPrice = try {
                            val apiMarket = convertToApiMarket(position.market)
                            bithumbPublicApi.getCurrentPrice(apiMarket)?.firstOrNull()?.tradePrice?.toDouble() ?: position.averagePrice
                        } catch (e: Exception) {
                            position.averagePrice
                        }
                        position.exitPrice = currentPrice
                        position.realizedPnl = (currentPrice - position.averagePrice) * position.totalQuantity
                        position.realizedPnlPercent = ((currentPrice - position.averagePrice) / position.averagePrice) * 100
                        dcaPositionRepository.save(position)

                        results.add(SyncAction(
                            market = position.market,
                            strategy = "DCA",
                            action = "CLOSED_NO_BALANCE",
                            reason = "잔고 0 확인 -> 수기 매도로 간주하고 CLOSED 처리 (추정 PnL: ${String.format("%.2f", position.realizedPnlPercent)}%)",
                            dbQuantity = position.totalQuantity,
                            actualQuantity = 0.0
                        ))
                        fixedCount++
                    }
                } else {
                    registerExpectedQuantity(
                        expectedByCoin = expectedByCoin,
                        coinSymbol = coinSymbol,
                        market = position.market,
                        strategy = "DCA",
                        quantity = BigDecimal.valueOf(position.totalQuantity)
                    )
                }
            }

            // 코인 단위로 DB 기대 수량과 실제 수량(available + locked) 비교
            expectedByCoin.forEach { (coinSymbol, expectation) ->
                val actualSnapshot = coinsWithBalance[coinSymbol]
                val actualQuantity = actualSnapshot?.total ?: BigDecimal.ZERO
                val dbQuantity = expectation.expectedQuantity
                val diff = (dbQuantity - actualQuantity).abs()
                val tolerance = calculateQuantityTolerance(dbQuantity, actualQuantity)

                if (diff > tolerance) {
                    val marketLabel = expectation.markets.sorted().joinToString(",")
                    val strategyLabel = expectation.strategies.sorted().joinToString(" + ")
                    val available = actualSnapshot?.available ?: BigDecimal.ZERO
                    val locked = actualSnapshot?.locked ?: BigDecimal.ZERO

                    log.warn(
                        "[Sync] 수량 불일치 coin={}, markets={}, DB={}, 실제={}, available={}, locked={}, diff={}, tolerance={}",
                        coinSymbol,
                        marketLabel,
                        dbQuantity,
                        actualQuantity,
                        available,
                        locked,
                        diff,
                        tolerance
                    )
                    results.add(
                        SyncAction(
                            market = marketLabel,
                            strategy = strategyLabel,
                            action = "QUANTITY_MISMATCH",
                            reason = "수량 불일치(coin=$coinSymbol, available=${available.stripTrailingZeros().toPlainString()}, locked=${locked.stripTrailingZeros().toPlainString()}, tolerance=${tolerance.stripTrailingZeros().toPlainString()})",
                            dbQuantity = dbQuantity.toDouble(),
                            actualQuantity = actualQuantity.toDouble()
                        )
                    )
                } else {
                    verifiedCount += expectation.positionCount
                }
            }

            val message = buildString {
                append("동기화 완료: ")
                append("${fixedCount}개 체결 확인, ")
                append("${verifiedCount}개 검증")
                if (skippedCount > 0) {
                    append(", ${skippedCount}개 수동 확인 필요")
                }
            }

            return SyncResult(
                success = true,
                message = message,
                actions = results,
                fixedCount = fixedCount,
                verifiedCount = verifiedCount
            )

        } catch (e: Exception) {
            log.error("동기화 실패: ${e.message}", e)
            return SyncResult(
                success = false,
                message = "동기화 실패: ${e.message}",
                actions = results,
                fixedCount = fixedCount,
                verifiedCount = verifiedCount
            )
        }
    }

    /**
     * 자동 잔고 싱크 (5분마다 실행)
     * - 수기 매도 후 잔고 0이 된 포지션을 자동으로 CLOSED 처리
     * - ABANDONED 포지션 재시도 무한 루프 방지
     */
    @Scheduled(fixedDelay = 300000)  // 5분마다
    fun autoSyncPositions() {
        try {
            val result = syncPositions()
            if (result.success && result.fixedCount > 0) {
                log.info("[자동 싱크] ${result.fixedCount}개 포지션 자동 정리: ${result.message}")
            }
        } catch (e: Exception) {
            log.error("[자동 싱크] 실패: ${e.message}")
        }
    }

    /**
     * 주문이 포지션과 일치하는지 확인
     * 체결 수량이 포지션 수량과 비슷하면 일치로 간주 (10% 오차 허용)
     */
    private fun isMatchingOrder(order: com.ant.cointrading.api.bithumb.OrderResponse, positionQty: Double): Boolean {
        val executedQty = order.executedVolume ?: return false
        if (positionQty <= 0.0) return false

        val positionQty = BigDecimal.valueOf(positionQty)
        if (positionQty <= BigDecimal.ZERO) return false

        // 수량이 10% 이내로 일치하면 같은 주문으로 간주
        val diff = (executedQty - positionQty).abs()
        val ratio = diff.divide(positionQty, 4, java.math.RoundingMode.HALF_UP)

        return ratio <= BigDecimal("0.1")
    }

    /**
     * ISO-8601 형식의 시간을 Instant로 변환 (시간대 없으면 UTC로 간주)
     */
    private fun parseInstant(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()

        return try {
            Instant.parse(dateTimeStr)
        } catch (e: Exception) {
            // 시간대 정보 없으면 UTC로 간주하고 'Z' 추가
            try {
                Instant.parse("${dateTimeStr}Z")
            } catch (e2: Exception) {
                // 그래도 실패하면 LocalDateTime으로 파싱 후 UTC 변환
                try {
                    val localDateTime = java.time.LocalDateTime.parse(dateTimeStr)
                    localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
                } catch (e3: Exception) {
                    log.warn("시간 파싱 실패: $dateTimeStr, 현재 시간 사용")
                    Instant.now()
                }
            }
        }
    }

    /**
     * Instant를 한국 시간으로 포맷팅
     */
    private fun formatTime(instant: Instant): String {
        val kstZone = java.time.ZoneId.of("Asia/Seoul")
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return instant.atZone(kstZone).format(formatter)
    }

    /**
     * 실제 주문 내역과 DB 동기화 (미체결 주문 확인)
     */
    @PostMapping("/orders")
    fun syncOrders(): SyncResult {
        val results = mutableListOf<SyncAction>()
        var fixedCount = 0

        try {
            // 미체결 주문 조회 (state=wait)
            val openOrders = try {
                bithumbPrivateApi.getOrders(null, "wait", 0, 100) ?: emptyList()
            } catch (e: Exception) {
                log.error("미체결 주문 조회 실패: ${e.message}")
                return SyncResult(
                    success = false,
                    message = "미체결 주문 조회 실패: ${e.message}",
                    actions = emptyList(),
                    fixedCount = 0,
                    verifiedCount = 0
                )
            }

            if (openOrders.isNotEmpty()) {
                log.info("미체결 주문 ${openOrders.size}개 발견")

                openOrders.forEach { order ->
                    results.add(SyncAction(
                        market = order.market,
                        strategy = "ORDER",
                        action = "OPEN_ORDER",
                        reason = "미체결 주문 존재",
                        dbQuantity = 0.0,
                        actualQuantity = order.volume?.toDouble() ?: 0.0
                    ))
                }
            }

            return SyncResult(
                success = true,
                message = "주문 동기화 완료: 미체결 ${openOrders.size}개",
                actions = results,
                fixedCount = fixedCount,
                verifiedCount = 0
            )

        } catch (e: Exception) {
            log.error("주문 동기화 실패: ${e.message}", e)
            return SyncResult(
                success = false,
                message = "주문 동기화 실패: ${e.message}",
                actions = results,
                fixedCount = fixedCount,
                verifiedCount = 0
            )
        }
    }

    private fun extractCoinSymbol(market: String): String {
        return PositionHelper.extractCoinSymbol(market)
    }

    /**
     * DB 마켓 포맷을 API 마켓 포맷으로 변환
     * DB: KRW-BTC/BTC_KRW/KRW_BTC, API: KRW-BTC
     */
    private fun convertToApiMarket(market: String): String {
        return PositionHelper.convertToApiMarket(market)
    }

    /**
     * DCA용 주문 일치 확인
     * DCA는 여러 번 나누어 매수하므로 총 수량보다 큰 주문도 일치로 간주
     */
    private fun isMatchingOrderForDca(order: com.ant.cointrading.api.bithumb.OrderResponse, positionQty: Double): Boolean {
        val executedQty = order.executedVolume ?: return false
        if (positionQty <= 0.0) return false

        val positionQty = BigDecimal.valueOf(positionQty)
        if (positionQty <= BigDecimal.ZERO) return false

        // DCA는 총 수량보다 크거나 같으면 일치로 간주 (부분 청산 가능)
        return executedQty >= positionQty.multiply(BigDecimal("0.9"))
    }

    private fun registerExpectedQuantity(
        expectedByCoin: MutableMap<String, CoinQuantityExpectation>,
        coinSymbol: String,
        market: String,
        strategy: String,
        quantity: BigDecimal
    ) {
        if (quantity <= BigDecimal.ZERO) return

        val expectation = expectedByCoin.getOrPut(coinSymbol) { CoinQuantityExpectation() }
        expectation.expectedQuantity = expectation.expectedQuantity + quantity
        expectation.positionCount += 1
        expectation.markets.add(market)
        expectation.strategies.add(strategy)
    }

    private fun calculateQuantityTolerance(dbQuantity: BigDecimal, actualQuantity: BigDecimal): BigDecimal {
        val base = dbQuantity.max(actualQuantity).abs()
        val relativeTolerance = base.multiply(BigDecimal("0.001")) // 0.1%
        val absoluteTolerance = BigDecimal("0.0001")
        return relativeTolerance.max(absoluteTolerance)
    }

    private data class BalanceSnapshot(
        val available: BigDecimal,
        val locked: BigDecimal,
        val total: BigDecimal
    )

    private data class CoinQuantityExpectation(
        var expectedQuantity: BigDecimal = BigDecimal.ZERO,
        var positionCount: Int = 0,
        val markets: MutableSet<String> = mutableSetOf(),
        val strategies: MutableSet<String> = mutableSetOf()
    )
}

data class SyncResult(
    val success: Boolean,
    val message: String,
    val actions: List<SyncAction>,
    val fixedCount: Int,
    val verifiedCount: Int
)

data class SyncAction(
    val market: String,
    val strategy: String,
    val action: String,
    val reason: String,
    val dbQuantity: Double,
    val actualQuantity: Double
)
