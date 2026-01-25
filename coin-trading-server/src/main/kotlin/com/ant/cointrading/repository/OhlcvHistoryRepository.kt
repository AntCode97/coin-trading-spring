package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * OHLCV 히스토리 Repository
 */
@Repository
interface OhlcvHistoryRepository : JpaRepository<OhlcvHistoryEntity, Long> {

    /**
     * 마켓 및 간격별 데이터 조회 (타임스탬프 오름차순)
     */
    fun findByMarketAndIntervalOrderByTimestampAsc(
        market: String,
        interval: String
    ): List<OhlcvHistoryEntity>

    /**
     * 마켓 및 간격별 데이터 조회 (타임스탬프 범위)
     */
    fun findByMarketAndIntervalAndTimestampBetweenOrderByTimestampAsc(
        market: String,
        interval: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<OhlcvHistoryEntity>

    /**
     * 마켓 및 간격별 최신 N개 데이터 조회
     */
    fun findTop100ByMarketAndIntervalOrderByTimestampDesc(
        market: String,
        interval: String
    ): List<OhlcvHistoryEntity>

    /**
     * 중복 체크 (마켓 + 간격 + 타임스탬프)
     */
    fun existsByMarketAndIntervalAndTimestamp(
        market: String,
        interval: String,
        timestamp: Long
    ): Boolean

    /**
     * 특정 타임스탬프 이후의 최신 데이터 개수
     */
    fun countByMarketAndIntervalAndTimestampAfter(
        market: String,
        interval: String,
        timestamp: Long
    ): Long

    /**
     * 가장 오래된 타임스탬프 조회
     */
    fun findFirstByMarketAndIntervalOrderByTimestampAsc(
        market: String,
        interval: String
    ): OhlcvHistoryEntity?

    /**
     * 가장 최신 타임스탬프 조회
     */
    fun findFirstByMarketAndIntervalOrderByTimestampDesc(
        market: String,
        interval: String
    ): OhlcvHistoryEntity?
}
