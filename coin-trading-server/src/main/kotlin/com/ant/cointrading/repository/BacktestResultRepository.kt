package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * 백테스트 결과 Repository
 */
@Repository
interface BacktestResultRepository : JpaRepository<BacktestResultEntity, Long> {

    /**
     * 특정 전략의 결과 조회
     */
    fun findByStrategyNameOrderByCreatedAtDesc(
        strategyName: String
    ): List<BacktestResultEntity>

    /**
     * 전략+마켓 조합의 결과 조회
     */
    fun findByStrategyNameAndMarketOrderByCreatedAtDesc(
        strategyName: String,
        market: String
    ): List<BacktestResultEntity>

    /**
     * 특정 기간 이후의 결과 조회
     */
    fun findByStartDateAfterOrderByCreatedAtDesc(
        startDate: LocalDate
    ): List<BacktestResultEntity>

    /**
     * 최고 샤프 비율 결과 조회
     */
    fun findTopByStrategyNameAndMarketOrderBySharpeRatioDesc(
        strategyName: String,
        market: String
    ): BacktestResultEntity?

    /**
     * 최고 수익률 결과 조회
     */
    fun findTopByStrategyNameAndMarketOrderByTotalReturnDesc(
        strategyName: String,
        market: String
    ): BacktestResultEntity?

    /**
     * 전략별 최적 결과 조회 (샤프 비율 기준)
     */
    fun findFirstByStrategyNameOrderBySharpeRatioDesc(
        strategyName: String
    ): BacktestResultEntity?
}
