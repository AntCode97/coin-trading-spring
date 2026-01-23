package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
interface MemeScalperTradeRepository : JpaRepository<MemeScalperTradeEntity, Long> {

    fun findByStatus(status: String): List<MemeScalperTradeEntity>

    fun findByMarketAndStatus(market: String, status: String): List<MemeScalperTradeEntity>

    fun countByStatus(status: String): Int

    fun countByMarketAndStatus(market: String, status: String): Long

    fun findTopByMarketOrderByCreatedAtDesc(market: String): MemeScalperTradeEntity?

    fun findByCreatedAtAfter(time: Instant): List<MemeScalperTradeEntity>

    @Query("SELECT COUNT(t) FROM MemeScalperTradeEntity t WHERE t.createdAt >= :startOfDay")
    fun countTodayTrades(startOfDay: Instant): Int

    @Query("SELECT COALESCE(SUM(t.pnlAmount), 0) FROM MemeScalperTradeEntity t WHERE t.createdAt >= :startOfDay AND t.status = 'CLOSED'")
    fun sumTodayPnl(startOfDay: Instant): Double

    @Query("SELECT t FROM MemeScalperTradeEntity t WHERE t.createdAt >= :startOfDay AND t.status = 'CLOSED' ORDER BY t.createdAt DESC")
    fun findTodayClosedTrades(startOfDay: Instant): List<MemeScalperTradeEntity>
}

@Repository
interface MemeScalperDailyStatsRepository : JpaRepository<MemeScalperDailyStatsEntity, Long> {

    fun findByDate(date: LocalDate): MemeScalperDailyStatsEntity?

    fun findTop30ByOrderByDateDesc(): List<MemeScalperDailyStatsEntity>
}
