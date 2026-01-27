package com.ant.cointrading.controller

import com.ant.cointrading.position.CreatePositionRequest
import com.ant.cointrading.position.PositionService
import com.ant.cointrading.position.PositionSummary
import com.ant.cointrading.repository.PositionEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 통합 포지션 관리 REST API
 *
 * 모든 전략(DCA, Grid, MeanReversion, VolumeSurge, MemeScalper 등)의 포지션을
 * PositionEntity를 통해 통합 관리하는 API.
 *
 * @since 2026-01-28
 */
@RestController
@RequestMapping("/api/positions")
class PositionController(
    private val positionService: PositionService
) {

    /**
     * 새 포지션 생성
     */
    @PostMapping
    fun createPosition(@RequestBody request: CreatePositionRequest): PositionEntity {
        return positionService.createPosition(request)
    }

    /**
     * 진입 주문 연결
     */
    @PostMapping("/{id}/link-entry")
    fun linkEntryOrder(
        @PathVariable id: Long,
        @RequestBody orderId: String
    ): PositionEntity? {
        return positionService.linkEntryOrder(id, orderId)
    }

    /**
     * 부분 체결 업데이트
     */
    @PostMapping("/{id}/partial-fill")
    fun updatePartialFill(
        @PathVariable id: Long,
        @RequestParam filledQuantity: BigDecimal,
        @RequestParam fillPrice: BigDecimal
    ): PositionEntity? {
        return positionService.updatePartialFill(id, filledQuantity, fillPrice)
    }

    /**
     * 부분 청산 업데이트
     */
    @PostMapping("/{id}/partial-exit")
    fun updatePartialExit(
        @PathVariable id: Long,
        @RequestParam filledQuantity: BigDecimal,
        @RequestParam fillPrice: BigDecimal
    ): PositionEntity? {
        return positionService.updatePartialExit(id, filledQuantity, fillPrice)
    }

    /**
     * 열린 포지션 목록 조회
     */
    @GetMapping("/open")
    fun getOpenPositions(): List<PositionEntity> {
        return positionService.getOpenPositions()
    }

    /**
     * 특정 마켓의 열린 포지션 조회
     */
    @GetMapping("/open/market/{market}")
    fun getOpenPositionsByMarket(@PathVariable market: String): List<PositionEntity> {
        return positionService.getOpenPositionsByMarket(market)
    }

    /**
     * 특정 전략의 열린 포지션 조회
     */
    @GetMapping("/open/strategy/{strategy}")
    fun getOpenPositionsByStrategy(@PathVariable strategy: String): List<PositionEntity> {
        return positionService.getOpenPositionsByStrategy(strategy)
    }

    /**
     * 포지션 ID로 조회
     */
    @GetMapping("/{id}")
    fun getPosition(@PathVariable id: Long): PositionEntity? {
        return positionService.getPosition(id)
    }

    /**
     * 진입 주문 ID로 포지션 조회
     */
    @GetMapping("/entry-order/{orderId}")
    fun getPositionByEntryOrderId(@PathVariable orderId: String): PositionEntity? {
        return positionService.getPositionByEntryOrderId(orderId)
    }

    /**
     * 청산 주문 ID로 포지션 조회
     */
    @GetMapping("/exit-order/{orderId}")
    fun getPositionByExitOrderId(@PathVariable orderId: String): PositionEntity? {
        return positionService.getPositionByExitOrderId(orderId)
    }

    /**
     * 포지션 청산
     */
    @PostMapping("/{id}/close")
    fun closePosition(
        @PathVariable id: Long,
        @RequestParam exitReason: String,
        @RequestParam(required = false) exitPrice: BigDecimal?
    ): PositionEntity? {
        return positionService.closePosition(id, exitReason, exitPrice)
    }

    /**
     * 포지션 실패 처리
     */
    @PostMapping("/{id}/fail")
    fun failPosition(
        @PathVariable id: Long,
        @RequestParam reason: String
    ): PositionEntity? {
        return positionService.failPosition(id, reason)
    }

    /**
     * 손절 가격 재계산
     */
    @PostMapping("/{id}/stop-loss")
    fun updateStopLoss(
        @PathVariable id: Long,
        @RequestParam stopLossPrice: BigDecimal,
        @RequestParam(required = false) stopLossPercent: BigDecimal?
    ): PositionEntity? {
        return positionService.updateStopLoss(id, stopLossPrice, stopLossPercent)
    }

    /**
     * 트레일링 스탑 활성화
     */
    @PostMapping("/{id}/trailing-stop")
    fun activateTrailingStop(
        @PathVariable id: Long,
        @RequestParam offsetPercent: BigDecimal,
        @RequestParam currentPrice: BigDecimal
    ): PositionEntity? {
        return positionService.activateTrailingStop(id, offsetPercent, currentPrice)
    }

    /**
     * 전체 포지션 통계
     */
    @GetMapping("/summary")
    fun getPositionSummary(): PositionSummary {
        return positionService.getPositionSummary()
    }
}
