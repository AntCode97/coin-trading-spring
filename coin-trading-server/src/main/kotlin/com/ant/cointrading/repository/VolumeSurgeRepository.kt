package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

/**
 * 거래량 급등 경보 Repository
 */
@Repository
interface VolumeSurgeAlertRepository : JpaRepository<VolumeSurgeAlertEntity, Long> {

    /** 처리되지 않은 경보 조회 */
    fun findByProcessedFalseOrderByDetectedAtDesc(): List<VolumeSurgeAlertEntity>

    /** 특정 마켓의 최근 경보 조회 */
    fun findByMarketOrderByDetectedAtDesc(market: String): List<VolumeSurgeAlertEntity>

    /** 특정 기간의 경보 조회 */
    fun findByDetectedAtBetweenOrderByDetectedAtDesc(
        start: Instant,
        end: Instant
    ): List<VolumeSurgeAlertEntity>

    /** 특정 마켓의 특정 기간 내 경보 존재 여부 */
    fun existsByMarketAndDetectedAtAfter(market: String, after: Instant): Boolean

    /** 승인된 경보 수 (기간별) */
    @Query("SELECT COUNT(a) FROM VolumeSurgeAlertEntity a WHERE a.llmFilterResult = 'APPROVED' AND a.detectedAt BETWEEN :start AND :end")
    fun countApprovedBetween(start: Instant, end: Instant): Long

    /** 전체 경보 수 (기간별) */
    fun countByDetectedAtBetween(start: Instant, end: Instant): Long
}

/**
 * 거래량 급등 트레이드 Repository
 */
@Repository
interface VolumeSurgeTradeRepository : JpaRepository<VolumeSurgeTradeEntity, Long> {

    /** 열린 포지션 조회 */
    fun findByStatus(status: String): List<VolumeSurgeTradeEntity>

    /** 특정 마켓의 열린 포지션 조회 */
    fun findByMarketAndStatus(market: String, status: String): List<VolumeSurgeTradeEntity>

    /** 특정 기간의 트레이드 조회 */
    fun findByCreatedAtBetweenOrderByCreatedAtDesc(
        start: Instant,
        end: Instant
    ): List<VolumeSurgeTradeEntity>

    /** 특정 마켓의 최근 트레이드 조회 (쿨다운 체크용) */
    fun findTopByMarketOrderByCreatedAtDesc(market: String): VolumeSurgeTradeEntity?

    /** 승리 트레이드 수 (기간별) */
    @Query("SELECT COUNT(t) FROM VolumeSurgeTradeEntity t WHERE t.pnlAmount > 0 AND t.createdAt BETWEEN :start AND :end")
    fun countWinningBetween(start: Instant, end: Instant): Long

    /** 패배 트레이드 수 (기간별) */
    @Query("SELECT COUNT(t) FROM VolumeSurgeTradeEntity t WHERE t.pnlAmount <= 0 AND t.status = 'CLOSED' AND t.createdAt BETWEEN :start AND :end")
    fun countLosingBetween(start: Instant, end: Instant): Long

    /** 총 손익 (기간별) */
    @Query("SELECT COALESCE(SUM(t.pnlAmount), 0) FROM VolumeSurgeTradeEntity t WHERE t.status = 'CLOSED' AND t.createdAt BETWEEN :start AND :end")
    fun sumPnlBetween(start: Instant, end: Instant): Double

    /** 평균 보유 시간 (분, 기간별) */
    @Query("""
        SELECT AVG(TIMESTAMPDIFF(MINUTE, t.entryTime, t.exitTime))
        FROM VolumeSurgeTradeEntity t
        WHERE t.status = 'CLOSED' AND t.exitTime IS NOT NULL AND t.createdAt BETWEEN :start AND :end
    """)
    fun avgHoldingMinutesBetween(start: Instant, end: Instant): Double?

    /** 회고 안된 트레이드 조회 */
    @Query("SELECT t FROM VolumeSurgeTradeEntity t WHERE t.status = 'CLOSED' AND t.reflectionNotes IS NULL ORDER BY t.createdAt DESC")
    fun findUnreflectedTrades(): List<VolumeSurgeTradeEntity>

    /** 열린 포지션 수 */
    fun countByStatus(status: String): Long

    /** 최근 N개 트레이드 조회 */
    fun findTop20ByOrderByCreatedAtDesc(): List<VolumeSurgeTradeEntity>
}

/**
 * 거래량 급등 전략 일일 요약 Repository
 */
@Repository
interface VolumeSurgeDailySummaryRepository : JpaRepository<VolumeSurgeDailySummaryEntity, Long> {

    /** 특정 날짜 요약 조회 */
    fun findByDate(date: LocalDate): VolumeSurgeDailySummaryEntity?

    /** 최근 N일 요약 조회 */
    fun findTop30ByOrderByDateDesc(): List<VolumeSurgeDailySummaryEntity>

    /** 특정 기간 요약 조회 */
    fun findByDateBetweenOrderByDateDesc(
        start: LocalDate,
        end: LocalDate
    ): List<VolumeSurgeDailySummaryEntity>

    /** 기간별 총 손익 */
    @Query("SELECT COALESCE(SUM(s.totalPnl), 0) FROM VolumeSurgeDailySummaryEntity s WHERE s.date BETWEEN :start AND :end")
    fun sumPnlBetween(start: LocalDate, end: LocalDate): Double

    /** 기간별 평균 승률 */
    @Query("SELECT AVG(s.winRate) FROM VolumeSurgeDailySummaryEntity s WHERE s.date BETWEEN :start AND :end")
    fun avgWinRateBetween(start: LocalDate, end: LocalDate): Double?
}
