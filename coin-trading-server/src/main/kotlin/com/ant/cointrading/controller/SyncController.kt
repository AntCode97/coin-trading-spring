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

/**
 * 빗썸 실제 잔고/주문 내역과 DB 동기화 컨트롤러
 *
 * DB에 포지션이 있지만 실제 잔고가 없는 경우를 처리합니다.
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
     */
    @PostMapping("/positions")
    fun syncPositions(): SyncResult {
        val results = mutableListOf<SyncAction>()
        var fixedCount = 0
        var verifiedCount = 0

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

            // Meme Scalper 포지션 체크
            memeScalperRepository.findByStatus("OPEN").forEach { position ->
                val coinSymbol = extractCoinSymbol(position.market)
                val actualBalanceObj = coinsWithBalance[coinSymbol]

                if (actualBalanceObj == null) {
                    // 실제 잔고 없음 -> CLOSED로 변경
                    log.warn("[Meme Scalper] ${position.market} DB에 있지만 실제 잔고 없음 -> CLOSED 처리")
                    position.status = "CLOSED"
                    position.exitPrice = position.entryPrice
                    position.exitTime = Instant.now()
                    position.exitReason = "SYNC_NO_BALANCE"
                    position.pnlAmount = -((position.entryPrice * position.quantity) * 0.001)
                    position.pnlPercent = -0.1
                    memeScalperRepository.save(position)

                    results.add(SyncAction(
                        market = position.market,
                        strategy = "Meme Scalper",
                        action = "CLOSED",
                        reason = "실제 잔고 없음",
                        dbQuantity = position.quantity,
                        actualQuantity = 0.0
                    ))
                    fixedCount++
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
                    log.warn("[Volume Surge] ${position.market} DB에 있지만 실제 잔고 없음 -> CLOSED 처리")
                    position.status = "CLOSED"
                    position.exitPrice = position.entryPrice
                    position.exitTime = Instant.now()
                    position.exitReason = "SYNC_NO_BALANCE"
                    position.pnlAmount = -((position.entryPrice * position.quantity) * 0.001)
                    position.pnlPercent = -0.1
                    volumeSurgeRepository.save(position)

                    results.add(SyncAction(
                        market = position.market,
                        strategy = "Volume Surge",
                        action = "CLOSED",
                        reason = "실제 잔고 없음",
                        dbQuantity = position.quantity,
                        actualQuantity = 0.0
                    ))
                    fixedCount++
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
                    log.warn("[DCA] ${position.market} DB에 있지만 실제 잔고 없음 -> CLOSED 처리")
                    position.status = "CLOSED"
                    position.exitPrice = position.averagePrice
                    position.exitedAt = Instant.now()
                    position.exitReason = "SYNC_NO_BALANCE"
                    position.realizedPnl = -((position.averagePrice * position.totalQuantity) * 0.001)
                    position.realizedPnlPercent = -0.1
                    dcaPositionRepository.save(position)

                    results.add(SyncAction(
                        market = position.market,
                        strategy = "DCA",
                        action = "CLOSED",
                        reason = "실제 잔고 없음",
                        dbQuantity = position.totalQuantity,
                        actualQuantity = 0.0
                    ))
                    fixedCount++
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

            return SyncResult(
                success = true,
                message = "동기화 완료: ${fixedCount}개 수정, ${verifiedCount}개 검증",
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
