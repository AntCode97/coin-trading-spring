package com.ant.cointrading.controller

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.dca.DcaEngine
import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.slf4j.LoggerFactory
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
     * - 체결 내역이 없으면 자동 처리하지 않고 리포트만
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

            // 실제로 잔고가 있는 코인만 필터링 (수량 > 0)
            val coinsWithBalance = actualBalances
                .filter { it.currency != "KRW" && it.balance > BigDecimal.ZERO }
                .associateBy { it.currency }

            log.info("실제 잔고 코인: ${coinsWithBalance.keys}")

            // 완료된 주문 목록 조회 (state=done, 최근 100개)
            val doneOrders = try {
                bithumbPrivateApi.getOrders(null, "done", 0, 100) ?: emptyList()
            } catch (e: Exception) {
                log.error("체결 내역 조회 실패: ${e.message}")
                emptyList()
            }

            // 매도 주문만 필터링 (side=ask)
            val sellOrders = doneOrders
                .filter { it.side == "ask" && it.state == "done" && it.executedVolume != null && it.executedVolume > BigDecimal.ZERO }
                .associateBy { it.market ?: "" }

            log.info("체결된 매도 주문: ${sellOrders.keys}")

            // Meme Scalper 포지션 체크
            memeScalperRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    // 잔고 없음 -> 체결 내역 확인
                    val sellOrder = sellOrders[position.market]

                    if (sellOrder != null && isMatchingOrder(sellOrder, position.quantity)) {
                        // 실제 매도 체결 확인 -> 실제 정보로 CLOSED 처리
                        val executedPrice = sellOrder.price?.toDouble() ?: position.entryPrice
                        val executedTime = parseInstant(sellOrder.createdAt)

                        log.info("[Meme Scalper] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${sellOrder.createdAt}")

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
                            actualQuantity = sellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        // 체결 내역 없음 -> 자동 처리하지 않음
                        log.warn("[Meme Scalper] ${position.market} DB에 있지만 잔고 없음, 체결 내역 없음 -> 수동 확인 필요")
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Meme Scalper",
                            action = "MANUAL_REVIEW",
                            reason = "잔고 없음, 체결 내역 없음. 빗썸에서 수동 확인 필요.",
                            dbQuantity = position.quantity,
                            actualQuantity = 0.0
                        ))
                        skippedCount++
                    }
                } else {
                    // 잔고 있음 -> 검증
                    val dbQuantity = BigDecimal(position.quantity)
                    val actualQuantity = actualBalanceObj.balance

                    if ((dbQuantity - actualQuantity).abs() > BigDecimal("0.0001")) {
                        log.warn("[Meme Scalper] ${position.market} 수량 불일치 DB=$dbQuantity, 실제=$actualQuantity")
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Meme Scalper",
                            action = "QUANTITY_MISMATCH",
                            reason = "수량 불일치",
                            dbQuantity = position.quantity,
                            actualQuantity = actualQuantity.toDouble()
                        ))
                    } else {
                        verifiedCount++
                    }
                }
            }

            // Volume Surge 포지션 체크
            volumeSurgeRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    val sellOrder = sellOrders[position.market]

                    if (sellOrder != null && isMatchingOrder(sellOrder, position.quantity)) {
                        val executedPrice = sellOrder.price?.toDouble() ?: position.entryPrice
                        val executedTime = parseInstant(sellOrder.createdAt)

                        log.info("[Volume Surge] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${sellOrder.createdAt}")

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
                            actualQuantity = sellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        log.warn("[Volume Surge] ${position.market} DB에 있지만 잔고 없음, 체결 내역 없음 -> 수동 확인 필요")
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Volume Surge",
                            action = "MANUAL_REVIEW",
                            reason = "잔고 없음, 체결 내역 없음. 빗썸에서 수동 확인 필요.",
                            dbQuantity = position.quantity,
                            actualQuantity = 0.0
                        ))
                        skippedCount++
                    }
                } else {
                    val dbQuantity = BigDecimal(position.quantity)
                    val actualQuantity = actualBalanceObj.balance

                    if ((dbQuantity - actualQuantity).abs() > BigDecimal("0.0001")) {
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "Volume Surge",
                            action = "QUANTITY_MISMATCH",
                            reason = "수량 불일치",
                            dbQuantity = position.quantity,
                            actualQuantity = actualQuantity.toDouble()
                        ))
                    } else {
                        verifiedCount++
                    }
                }
            }

            // DCA 포지션 체크
            dcaPositionRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    val sellOrder = sellOrders[position.market]

                    if (sellOrder != null && isMatchingOrder(sellOrder, position.totalQuantity)) {
                        val executedPrice = sellOrder.price?.toDouble() ?: position.averagePrice
                        val executedTime = parseInstant(sellOrder.createdAt)

                        log.info("[DCA] ${position.market} 실제 체결 확인 - 가격=$executedPrice, 시간=${sellOrder.createdAt}")

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
                            actualQuantity = sellOrder.executedVolume?.toDouble() ?: 0.0
                        ))
                        fixedCount++
                    } else {
                        log.warn("[DCA] ${position.market} DB에 있지만 잔고 없음, 체결 내역 없음 -> 수동 확인 필요")
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "DCA",
                            action = "MANUAL_REVIEW",
                            reason = "잔고 없음, 체결 내역 없음. 빗썸에서 수동 확인 필요.",
                            dbQuantity = position.totalQuantity,
                            actualQuantity = 0.0
                        ))
                        skippedCount++
                    }
                } else {
                    val dbQuantity = BigDecimal(position.totalQuantity)
                    val actualQuantity = actualBalanceObj.balance

                    if ((dbQuantity - actualQuantity).abs() > BigDecimal("0.0001")) {
                        results.add(SyncAction(
                            market = position.market,
                            strategy = "DCA",
                            action = "QUANTITY_MISMATCH",
                            reason = "수량 불일치",
                            dbQuantity = position.totalQuantity,
                            actualQuantity = actualQuantity.toDouble()
                        ))
                    } else {
                        verifiedCount++
                    }
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
     * 주문이 포지션과 일치하는지 확인
     * 체결 수량이 포지션 수량과 비슷하면 일치로 간주 (10% 오차 허용)
     */
    private fun isMatchingOrder(order: com.ant.cointrading.api.bithumb.OrderResponse, positionQty: Double): Boolean {
        val executedQty = order.executedVolume ?: return false
        val positionQty = BigDecimal(positionQty)

        // 수량이 10% 이내로 일치하면 같은 주문으로 간주
        val diff = (executedQty - positionQty).abs()
        val ratio = diff.divide(positionQty, 4, java.math.RoundingMode.HALF_UP)

        return ratio <= BigDecimal("0.1")
    }

    /**
     * ISO-8601 형식의 시간을 Instant로 변환
     */
    private fun parseInstant(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()

        return try {
            Instant.parse(dateTimeStr)
        } catch (e: Exception) {
            log.warn("시간 파싱 실패: $dateTimeStr, 현재 시간 사용")
            Instant.now()
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
                        market = order.market ?: "UNKNOWN",
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
        return market.removePrefix("KRW-")
    }
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
