package com.ant.cointrading.risk

import com.ant.cointrading.repository.DailyStatsRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 일일 손실 추적 결과
 */
data class DailyLossStatus(
    val date: LocalDate,
    val totalPnl: BigDecimal,       // 당일 총 PnL
    val totalPnlPercent: Double,    // 총 PnL 비율 (%)
    val maxLossLimit: Double,       // 최대 손실 한도 (%)
    val isLimitExceeded: Boolean,   // 한도 초과 여부
    val remainingLossBudget: Double, // 남은 손실 예산 (%)
    val tradingAllowed: Boolean     // 트레이딩 허용 여부
)

/**
 * 일일 손실 추적기
 *
 * quant-trading 스킬 기반 리스크 관리:
 * - 일일 최대 손실: 자본의 5%
 * - 5% 초과 시 자동 트레이딩 중지
 * - Slack 알림으로 관리자 통보
 * - 자정 기준으로 복구 (KST)
 *
 * DailyStatsEntity를 사용하여 당일 손실 추적.
 */
@Component
class DailyLossTracker(
    private val dailyStatsRepository: DailyStatsRepository,
    private val tradingProperties: com.ant.cointrading.config.TradingProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MAX_DAILY_LOSS_PERCENT = 5.0  // 일일 최대 손실 5%
        val ZONE_ID = ZoneId.of("Asia/Seoul")
    }

    // 상태 캐시
    private var cachedStatus: DailyLossStatus? = null
    private var lastUpdateTime: LocalDate? = null

    // 일일 손실 한도 초과 플래그
    @Volatile
    private var dailyLimitExceeded = false

    /**
     * 일일 손실 상태 조회
     */
    fun getDailyLossStatus(): DailyLossStatus {
        val today = LocalDate.now(ZONE_ID)
        val todayStr = today.toString()

        // 캐시된 상태가 있고 오늘 날짜면 재사용
        if (cachedStatus?.date == today && lastUpdateTime == today) {
            return cachedStatus!!
        }

        // 오늘의 일일 통계 조회
        val dailyStats = dailyStatsRepository.findByDate(todayStr)

        // 총 PnL (Number를 BigDecimal로 변환)
        val totalPnlNumber = dailyStats?.totalPnl ?: BigDecimal.ZERO
        val totalPnl = when (totalPnlNumber) {
            is BigDecimal -> totalPnlNumber as BigDecimal
            else -> BigDecimal(totalPnlNumber.toString())
        }

        // 자본 (투자 원금)
        val capital = getCapital()

        // PnL 비율 계산
        val totalPnlPercent = if (capital > BigDecimal.ZERO) {
            (totalPnl.toDouble() / capital.toDouble()) * 100
        } else {
            0.0
        }

        // 최대 손실 한도
        val maxLossLimit = tradingProperties.maxDrawdownPercent ?: DEFAULT_MAX_DAILY_LOSS_PERCENT

        // 한도 초과 여부
        val isLimitExceeded = totalPnlPercent < -maxLossLimit
        dailyLimitExceeded = isLimitExceeded

        // 남은 손실 예산
        val remainingLossBudget = if (totalPnlPercent >= 0) {
            maxLossLimit  // 수익 중이면 전체 한도 사용 가능
        } else {
            maxLossLimit - abs(totalPnlPercent)
        }

        // 트레이딩 허용 여부
        val tradingAllowed = !isLimitExceeded && remainingLossBudget > 0

        val status = DailyLossStatus(
            date = today,
            totalPnl = totalPnl,
            totalPnlPercent = totalPnlPercent,
            maxLossLimit = maxLossLimit,
            isLimitExceeded = isLimitExceeded,
            remainingLossBudget = remainingLossBudget,
            tradingAllowed = tradingAllowed
        )

        // 캐시 업데이트
        cachedStatus = status
        lastUpdateTime = today

        return status
    }

    /**
     * 트레이딩 허용 여부 확인
     */
    fun isTradingAllowed(): Boolean {
        val status = getDailyLossStatus()

        if (!status.tradingAllowed) {
            log.warn("""
                [일일 손실 한도 초과] 트레이딩 중지됨
                날짜: ${status.date}
                총 PnL: ${String.format("%.2f", status.totalPnlPercent)}%
                한도: -${status.maxLossLimit}%
                남은 예산: ${String.format("%.2f", status.remainingLossBudget)}%
            """.trimIndent())
        }

        return status.tradingAllowed
    }

    /**
     * 거래 전 손실 한도 체크
     *
     * 예상 손실을 포함하여 한도 초과 여부 확인.
     */
    fun checkBeforeTrade(
        potentialLoss: Double,    // 예상 손실 (KRW)
        capital: Double           // 현재 자본
    ): Boolean {
        val status = getDailyLossStatus()

        // 현재 PnL을 금액으로 변환
        val currentPnlAmount = status.totalPnl.toDouble()

        // 예상 손실 포함 총 PnL
        val projectedPnl = currentPnlAmount - potentialLoss

        // 예상 손실 비율
        val projectedPnlPercent = (projectedPnl / capital) * 100

        // 한도 초과 여부
        val wouldExceedLimit = projectedPnlPercent < -status.maxLossLimit

        if (wouldExceedLimit) {
            log.warn("""
                [일일 손실 한도 예상 초과] 거래 거부됨
                현재 PnL: ${String.format("%.2f", status.totalPnlPercent)}%
                예상 손실: ${String.format("%.0f", potentialLoss)}원
                예상 PnL: ${String.format("%.2f", projectedPnlPercent)}%
                한도: -${status.maxLossLimit}%
            """.trimIndent())
        }

        return !wouldExceedLimit
    }

    /**
     * 자본 조회 (투자 원금)
     *
     * 실제로는 별도로 관리해야 하지만 여기서는 단순화
     */
    private fun getCapital(): BigDecimal {
        // TODO: 실제 자본 조회 로직 구현
        // 일단 100만원으로 고정
        return BigDecimal("1000000")
    }

    /**
     * 일일 손실 한도 초과 여부 확인
     */
    fun isDailyLimitExceeded(): Boolean = dailyLimitExceeded

    /**
     * 매일 자정에 상태 초기화
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Seoul")
    fun resetDailyLoss() {
        val today = LocalDate.now(ZONE_ID)
        log.info("[일일 손실 초기화] 날짜: $today")

        // 캐시 초기화
        cachedStatus = null
        lastUpdateTime = null

        // 한도 초과 플래그 초기화
        dailyLimitExceeded = false
    }

    /**
     * 1시간마다 상태 로깅
     */
    @Scheduled(cron = "0 0 * * * ?", zone = "Asia/Seoul")
    fun logHourlyStatus() {
        val status = getDailyLossStatus()

        log.info("""
            [일일 손실 현황] ${status.date}
            총 PnL: ${String.format("%.2f", status.totalPnlPercent)}%
            한도: -${status.maxLossLimit}%
            남은 예산: ${String.format("%.2f", status.remainingLossBudget)}%
            트레이딩: ${if (status.tradingAllowed) "가능" else "중지"}
        """.trimIndent())
    }
}
