package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface PendingOrderRepository : JpaRepository<PendingOrderEntity, Long> {

    fun findByOrderId(orderId: String): PendingOrderEntity?

    fun findByStatus(status: PendingOrderStatus): List<PendingOrderEntity>

    fun findByStatusIn(statuses: List<PendingOrderStatus>): List<PendingOrderEntity>

    fun findByMarketAndStatus(market: String, status: PendingOrderStatus): List<PendingOrderEntity>

    fun findByMarketAndStatusIn(market: String, statuses: List<PendingOrderStatus>): List<PendingOrderEntity>

    /**
     * 활성 미체결 주문 조회 (PENDING, PARTIALLY_FILLED)
     */
    @Query("SELECT p FROM PendingOrderEntity p WHERE p.status IN ('PENDING', 'PARTIALLY_FILLED') ORDER BY p.createdAt ASC")
    fun findActivePendingOrders(): List<PendingOrderEntity>

    /**
     * 특정 마켓의 활성 미체결 주문 조회
     */
    @Query("SELECT p FROM PendingOrderEntity p WHERE p.market = :market AND p.status IN ('PENDING', 'PARTIALLY_FILLED') ORDER BY p.createdAt ASC")
    fun findActivePendingOrdersByMarket(market: String): List<PendingOrderEntity>

    /**
     * 만료된 미체결 주문 조회
     */
    @Query("SELECT p FROM PendingOrderEntity p WHERE p.status IN ('PENDING', 'PARTIALLY_FILLED') AND p.expiresAt < :now ORDER BY p.expiresAt ASC")
    fun findExpiredOrders(now: Instant): List<PendingOrderEntity>

    /**
     * 오래된 미체결 주문 조회 (체결 확인 우선순위)
     */
    @Query("SELECT p FROM PendingOrderEntity p WHERE p.status IN ('PENDING', 'PARTIALLY_FILLED') AND p.lastCheckedAt < :cutoff ORDER BY p.lastCheckedAt ASC")
    fun findOrdersNeedingCheck(cutoff: Instant): List<PendingOrderEntity>

    /**
     * 특정 기간 내 완료된 주문 통계
     */
    @Query("SELECT p.status, COUNT(p) FROM PendingOrderEntity p WHERE p.createdAt >= :since GROUP BY p.status")
    fun getStatusStats(since: Instant): List<Array<Any>>

    /**
     * 최근 N개 주문 조회
     */
    fun findTop100ByOrderByCreatedAtDesc(): List<PendingOrderEntity>

    /**
     * 전략별 미체결 주문 조회
     */
    fun findByStrategyAndStatusIn(strategy: String, statuses: List<PendingOrderStatus>): List<PendingOrderEntity>
}
