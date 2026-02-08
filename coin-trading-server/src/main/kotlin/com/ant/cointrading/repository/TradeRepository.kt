package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface TradeRepository : JpaRepository<TradeEntity, Long> {
    fun findByMarketOrderByCreatedAtDesc(market: String): List<TradeEntity>
    fun findByMarketAndCreatedAtAfter(market: String, after: Instant): List<TradeEntity>
    fun findByMarketAndCreatedAtBetween(market: String, start: Instant, end: Instant): List<TradeEntity>
    fun findByCreatedAtBetween(start: Instant, end: Instant): List<TradeEntity>
    fun findTop100ByOrderByCreatedAtDesc(): List<TradeEntity>
    fun findBySimulated(simulated: Boolean): List<TradeEntity>
    fun findTopByOrderIdOrderByCreatedAtDesc(orderId: String): TradeEntity?
    fun findByStrategy(strategy: String): List<TradeEntity>
    fun findByStrategyAndCreatedAtBetween(strategy: String, start: Instant, end: Instant): List<TradeEntity>

    @Query(
        value = "SELECT * FROM trades t WHERE t.market = :market AND t.side = 'BUY' ORDER BY t.created_at DESC LIMIT 1",
        nativeQuery = true
    )
    fun findLastBuyByMarket(@Param("market") market: String): TradeEntity?

    @Query("SELECT t.strategy, COUNT(t), SUM(CASE WHEN t.pnl > 0 THEN 1 ELSE 0 END), SUM(COALESCE(t.pnl, 0)) FROM TradeEntity t WHERE t.createdAt >= :since GROUP BY t.strategy")
    fun getStrategyStats(@Param("since") since: Instant): List<Array<Any>>

    @Query("SELECT COUNT(t) FROM TradeEntity t WHERE t.createdAt >= :since")
    fun countSince(@Param("since") since: Instant): Long

    @Query("SELECT SUM(COALESCE(t.pnl, 0)) FROM TradeEntity t WHERE t.createdAt >= :since")
    fun sumPnlSince(@Param("since") since: Instant): Double?
}

@Repository
interface DailyStatsRepository : JpaRepository<DailyStatsEntity, Long> {
    fun findByDate(date: String): DailyStatsEntity?
    fun findByMarketAndDate(market: String, date: String): DailyStatsEntity?
    fun findByDateBetweenOrderByDateDesc(startDate: String, endDate: String): List<DailyStatsEntity>
    fun findTop30ByOrderByDateDesc(): List<DailyStatsEntity>
}
