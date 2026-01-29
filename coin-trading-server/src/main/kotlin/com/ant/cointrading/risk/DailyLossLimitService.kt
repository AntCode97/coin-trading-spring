package com.ant.cointrading.risk

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE
import com.ant.cointrading.util.DateTimeUtils.todayString
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 일일 손실 한도 서비스
 *
 * quant-trading 스킬 기반 리스크 관리:
 * - 일일 최대 손실: 자본의 5%
 * - 손절은 필수, 익절은 선택
 * - 한도 도달 시 트레이딩 중지
 */
@Component
class DailyLossLimitService(
    private val keyValueService: KeyValueService,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_DAILY_LOSS = "risk.daily_loss"
        private const val KEY_DAILY_LOSS_DATE = "risk.daily_loss_date"
        private const val KEY_TRADING_HALTED = "risk.trading_halted"
        private const val KEY_TRADING_HALTED_REASON = "risk.trading_halted_reason"
        private const val DEFAULT_DAILY_LOSS_LIMIT_PERCENT = 5.0  // 자본의 5%
    }

    /** 일일 손실 한도 (%) - 자본 대비 */
    var dailyLossLimitPercent: Double = DEFAULT_DAILY_LOSS_LIMIT_PERCENT

    /** 초기 자본 (KRW) - 손실률 계산 기준 */
    private var initialCapital: Double = 1_000_000.0  // 기본 100만원

    /** 현재 일일 손실액 (KRW) */
    private var currentDailyLoss: Double = 0.0

    /** 오늘 날짜 (YYYY-MM-DD) */
    private var todayDate: String? = null

    /** 트레이딩 중지 여부 */
    var isTradingHalted: Boolean = false
        internal set

    /** 트레이딩 중지 사유 */
    var tradingHaltedReason: String? = null
        internal set

    @PostConstruct
    fun init() {
        restoreState()
        log.info("일일 손실 한도 서비스 초기화: 한도=${dailyLossLimitPercent}%, 현재 손실=${currentDailyLoss}원, 중지=${isTradingHalted}")
    }

    /**
     * DB에서 상태 복원
     */
    private fun restoreState() {
        val savedDate = keyValueService.get(KEY_DAILY_LOSS_DATE)
        val savedLoss = keyValueService.get(KEY_DAILY_LOSS)
        val savedHalted = keyValueService.get(KEY_TRADING_HALTED)
        val savedReason = keyValueService.get(KEY_TRADING_HALTED_REASON)

        val today = todayString()

        if (savedDate == today) {
            // 오늘 데이터면 복원
            currentDailyLoss = savedLoss?.toDoubleOrNull() ?: 0.0
            isTradingHalted = savedHalted?.toBoolean() ?: false
            tradingHaltedReason = savedReason
            todayDate = today
            log.info("일일 손실 상태 복원: 손실=${currentDailyLoss}원, 중지=${isTradingHalted}")
        } else {
            // 날짜가 바뀌었으면 초기화
            resetDailyLoss()
        }
    }

    /**
     * 매일 자정에 일일 손실 초기화
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun resetDailyLoss() {
        val today = todayString()
        todayDate = today
        currentDailyLoss = 0.0
        isTradingHalted = false
        tradingHaltedReason = null

        // DB 저장
        keyValueService.set(KEY_DAILY_LOSS_DATE, today, "risk", "일일 손실 날짜")
        keyValueService.set(KEY_DAILY_LOSS, "0", "risk", "일일 손실액")
        keyValueService.delete(KEY_TRADING_HALTED)
        keyValueService.delete(KEY_TRADING_HALTED_REASON)

        log.info("일일 손실 초기화: $today")
    }

    /**
     * 손익 기록 (음수=손실, 양수=이익)
     *
     * @return true=트레이딩 가능, false=손실 한도 도달으로 트레이딩 중지
     */
    fun recordPnl(pnl: Double): Boolean {
        if (pnl >= 0) {
            // 이익이면 그냥 기록
            if (currentDailyLoss > 0) {
                // 손실이 있었으면 이익으로 상계
                currentDailyLoss = maxOf(0.0, currentDailyLoss - pnl)
                saveState()
            }
            return true
        }

        // 손실이면 누적
        val loss = -pnl
        currentDailyLoss += loss

        // 손실 한도 체크
        val lossLimit = initialCapital * (dailyLossLimitPercent / 100.0)
        if (currentDailyLoss >= lossLimit) {
            isTradingHalted = true
            tradingHaltedReason = "일일 손실 한도 도달: ${String.format("%.0f", currentDailyLoss)}원 / ${String.format("%.0f", lossLimit)}원 (${String.format("%.1f", currentDailyLoss / initialCapital * 100)}%)"

            keyValueService.set(KEY_TRADING_HALTED, "true", "risk", "트레이딩 중지")
            keyValueService.set(KEY_TRADING_HALTED_REASON, tradingHaltedReason ?: "", "risk", "트레이딩 중지 사유")

            log.warn("일일 손실 한도 도달! 트레이딩 중지: $tradingHaltedReason")
            slackNotifyHalted()
            return false
        }

        saveState()
        return true
    }

    /**
     * 트레이딩 가능 여부 확인
     */
    fun canTrade(): Boolean {
        if (isTradingHalted) {
            log.debug("트레이딩 중지됨: $tradingHaltedReason")
            return false
        }

        // 손실 한도 근접 경고 (80% 도달)
        val lossLimit = initialCapital * (dailyLossLimitPercent / 100.0)
        if (currentDailyLoss >= lossLimit * 0.8) {
            log.warn("일일 손실 한도 근접: ${String.format("%.0f", currentDailyLoss)}원 / ${String.format("%.0f", lossLimit)}원 (${String.format("%.1f", currentDailyLoss / lossLimit * 100)}%)")
        }

        return true
    }

    /**
     * 초기 자본 설정 (DB에서 복원 또는 설정)
     */
    fun setInitialCapital(capital: Double) {
        initialCapital = capital
        log.info("초기 자본 설정: ${String.format("%.0f", capital)}원")
    }

    /**
     * 상태 저장
     */
    private fun saveState() {
        val today = todayDate ?: todayString()
        keyValueService.set(KEY_DAILY_LOSS_DATE, today, "risk", "일일 손실 날짜")
        keyValueService.set(KEY_DAILY_LOSS, currentDailyLoss.toString(), "risk", "일일 손실액")
    }

    /**
     * 현재 상태 조회
     */
    fun getStatus(): Map<String, Any?> {
        val lossLimit = initialCapital * (dailyLossLimitPercent / 100.0)
        val lossPercentOfLimit = if (lossLimit > 0) currentDailyLoss / lossLimit * 100 else 0.0

        return mapOf(
            "dailyLossLimit" to mapOf(
                "amount" to lossLimit,
                "percent" to dailyLossLimitPercent
            ),
            "currentDailyLoss" to currentDailyLoss,
            "lossPercentOfLimit" to lossPercentOfLimit,
            "isTradingHalted" to isTradingHalted,
            "tradingHaltedReason" to tradingHaltedReason,
            "todayDate" to todayDate
        )
    }

    /**
     * 수동으로 트레이딩 중지 해제 (관리자용)
     */
    fun resetTradingHalt() {
        isTradingHalted = false
        tradingHaltedReason = null
        keyValueService.delete(KEY_TRADING_HALTED)
        keyValueService.delete(KEY_TRADING_HALTED_REASON)
        log.warn("트레이딩 중지 수동 해제됨")
    }

    /**
     * Slack 알림 (트레이딩 중지 시)
     */
    private fun slackNotifyHalted() {
        slackNotifier.sendError(
            "TRADING HALTED",
            """
            ===== 트레이딩 중지 알림 =====
            사유: $tradingHaltedReason
            조치: 관리자가 수동 해제 전까지 트레이딩 중지
            일일 손실: ${String.format("%.0f", currentDailyLoss)}원
            손실률: ${String.format("%.2f", (currentDailyLoss / initialCapital) * 100)}%
            =============================
            """.trimIndent()
        )
    }
}
