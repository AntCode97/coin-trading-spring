package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Repository
interface FundingRateRepository : JpaRepository<FundingRateEntity, Long> {

    fun findBySymbolOrderByFundingTimeDesc(symbol: String): List<FundingRateEntity>

    fun findBySymbolAndFundingTimeAfterOrderByFundingTimeDesc(
        symbol: String,
        after: Instant
    ): List<FundingRateEntity>

    fun findTop10BySymbolOrderByFundingTimeDesc(symbol: String): List<FundingRateEntity>

    @Query("""
        SELECT f FROM FundingRateEntity f
        WHERE f.fundingTime = (
            SELECT MAX(f2.fundingTime) FROM FundingRateEntity f2 WHERE f2.symbol = f.symbol
        )
        ORDER BY f.annualizedRate DESC NULLS LAST
    """)
    fun findLatestBySymbol(): List<FundingRateEntity>

    fun existsBySymbolAndFundingTime(symbol: String, fundingTime: Instant): Boolean
}

@Repository
interface FundingArbPositionRepository : JpaRepository<FundingArbPositionEntity, Long> {

    fun findByStatus(status: String): List<FundingArbPositionEntity>

    fun findBySymbolAndStatus(symbol: String, status: String): List<FundingArbPositionEntity>

    fun findTop20ByOrderByCreatedAtDesc(): List<FundingArbPositionEntity>

    @Query("""
        SELECT p FROM FundingArbPositionEntity p
        WHERE p.status = 'CLOSED'
        AND p.exitTime >= :since
        ORDER BY p.exitTime DESC
    """)
    fun findClosedSince(since: Instant): List<FundingArbPositionEntity>

    @Query("SELECT COUNT(p) FROM FundingArbPositionEntity p WHERE p.status = 'OPEN'")
    fun countOpenPositions(): Int

    @Query("SELECT SUM(p.netPnl) FROM FundingArbPositionEntity p WHERE p.status = 'CLOSED'")
    fun sumNetPnl(): Double?
}

@Repository
interface FundingPaymentRepository : JpaRepository<FundingPaymentEntity, Long> {

    fun findByPositionIdOrderByPaymentTimeDesc(positionId: Long): List<FundingPaymentEntity>

    fun findByPaymentTimeAfterOrderByPaymentTimeDesc(after: Instant): List<FundingPaymentEntity>

    @Query("SELECT SUM(p.paymentAmount) FROM FundingPaymentEntity p WHERE p.paymentTime >= :since")
    fun sumPaymentsSince(since: Instant): Double?

    @Query("SELECT SUM(p.paymentAmount) FROM FundingPaymentEntity p WHERE p.positionId = :positionId")
    fun sumPaymentsByPosition(positionId: Long): Double?
}

@Repository
interface FundingDailyStatsRepository : JpaRepository<FundingDailyStatsEntity, Long> {

    fun findByDate(date: LocalDate): FundingDailyStatsEntity?

    fun findTop30ByOrderByDateDesc(): List<FundingDailyStatsEntity>

    @Query("SELECT SUM(s.netPnl) FROM FundingDailyStatsEntity s WHERE s.date >= :since")
    fun sumNetPnlSince(since: LocalDate): Double?
}
