package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 백테스트 거래 상세 Repository
 */
@Repository
interface BacktestTradeRepository : JpaRepository<BacktestTradeEntity, Long> {

    /**
     * 백테스트 결과 ID로 거래 내역 조회
     */
    fun findByBacktestResultIdOrderByEntryTimeAsc(
        backtestResultId: Long
    ): List<BacktestTradeEntity>

    /**
     * 마켓별 거래 내역 조회
     */
    fun findByMarketOrderByEntryTimeDesc(
        market: String
    ): List<BacktestTradeEntity>

    /**
     * 백테스트 결과의 거래 횟수
     */
    fun countByBacktestResultId(backtestResultId: Long): Long

    /**
     * 수익 거래만 조회
     */
    fun findByBacktestResultIdAndPnlGreaterThanOrderByEntryTimeAsc(
        backtestResultId: Long,
        minPnl: Double = 0.0
    ): List<BacktestTradeEntity>

    /**
     * 손실 거래만 조회
     */
    fun findByBacktestResultIdAndPnlLessThanOrderByEntryTimeAsc(
        backtestResultId: Long,
        maxPnl: Double = 0.0
    ): List<BacktestTradeEntity>
}
