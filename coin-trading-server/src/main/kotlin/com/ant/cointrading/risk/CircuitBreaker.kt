package com.ant.cointrading.risk

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 서킷 브레이커 - 연속 손실 및 시스템 이상 시 자동 거래 중단
 *
 * 20년차 퀀트의 교훈 (QUANT_RESEARCH.md 기반):
 * 1. PnL 손실만 보면 안 된다 - 시스템 장애도 손실이다
 * 2. API 에러가 연속되면 시장 상황이 비정상이다
 * 3. 주문 실패가 반복되면 유동성 문제다
 * 4. 슬리피지가 커지면 호가창이 얇아진 것이다
 *
 * 트리거 조건:
 * - 연속 3회 PnL 손실
 * - 일일 손실 5% 초과
 * - 24시간 내 10회 이상 손실
 * - 연속 5회 주문 실행 실패 (NEW)
 * - 슬리피지 2% 초과 3회 연속 (NEW)
 * - 1분 내 API 에러 10회 (NEW)
 * - 총 자산 10% 감소 (NEW)
 */
@Component
class CircuitBreaker(
    private val tradeRepository: TradeRepository,
    private val slackNotifier: SlackNotifier
) {

    private val log = LoggerFactory.getLogger(CircuitBreaker::class.java)

    // 마켓별 서킷 브레이커 상태
    private val circuitStates = ConcurrentHashMap<String, CircuitState>()

    // 글로벌 서킷 브레이커 상태
    @Volatile
    private var globalCircuitOpen = false
    private var globalCircuitOpenTime: Instant? = null
    private var globalCircuitReason: String = ""

    // API 에러 추적 (1분 윈도우)
    private val apiErrorTimestamps = ConcurrentHashMap<String, MutableList<Instant>>()
    private val globalApiErrorCount = AtomicInteger(0)
    private val lastGlobalApiErrorTime = AtomicLong(0)

    // 초기 자산 추적 (총 자산 감소 감지용)
    private var initialTotalAsset: Double? = null
    private var peakTotalAsset: Double = 0.0

    data class CircuitState(
        var consecutiveLosses: Int = 0,
        var consecutiveExecutionFailures: Int = 0,    // NEW: 연속 실행 실패
        var consecutiveHighSlippage: Int = 0,         // NEW: 연속 고슬리피지
        var dailyLossPercent: Double = 0.0,
        var dailyLossCount: Int = 0,
        var isOpen: Boolean = false,
        var openTime: Instant? = null,
        var reason: String = "",
        var lastTradeTime: Instant? = null
    )

    companion object {
        // PnL 기반 트리거
        const val MAX_CONSECUTIVE_LOSSES = 3
        const val MAX_DAILY_LOSS_PERCENT = 5.0
        const val MAX_DAILY_LOSS_COUNT = 10

        // 실행 실패 기반 트리거 (NEW)
        const val MAX_CONSECUTIVE_EXECUTION_FAILURES = 5
        const val MAX_CONSECUTIVE_HIGH_SLIPPAGE = 3
        const val HIGH_SLIPPAGE_THRESHOLD_PERCENT = 2.0

        // API 에러 기반 트리거 (NEW)
        const val MAX_API_ERRORS_PER_MINUTE = 10
        const val API_ERROR_WINDOW_SECONDS = 60L

        // 자산 기반 트리거 (NEW)
        const val MAX_TOTAL_ASSET_DRAWDOWN_PERCENT = 10.0

        // 쿨다운
        const val COOLDOWN_HOURS = 4L
        const val GLOBAL_COOLDOWN_HOURS = 24L
        const val EXECUTION_FAILURE_COOLDOWN_HOURS = 1L  // 실행 실패는 빠른 복구
    }

    /**
     * 거래 가능 여부 확인
     */
    fun canTrade(market: String): CircuitCheckResult {
        // 1. 글로벌 서킷 브레이커 체크
        if (globalCircuitOpen) {
            val elapsed = globalCircuitOpenTime?.let {
                ChronoUnit.HOURS.between(it, Instant.now())
            } ?: 0

            if (elapsed < GLOBAL_COOLDOWN_HOURS) {
                return CircuitCheckResult(
                    canTrade = false,
                    reason = "글로벌 서킷 발동: $globalCircuitReason (${GLOBAL_COOLDOWN_HOURS - elapsed}시간 후 재개)",
                    severity = CircuitSeverity.CRITICAL
                )
            } else {
                resetGlobalCircuit()
            }
        }

        // 2. 마켓별 서킷 브레이커 체크
        val state = circuitStates[market] ?: return CircuitCheckResult(canTrade = true)

        if (state.isOpen) {
            val elapsed = state.openTime?.let {
                ChronoUnit.HOURS.between(it, Instant.now())
            } ?: 0

            // 실행 실패로 인한 서킷은 빠른 복구
            val cooldown = if (state.reason.contains("실행 실패")) {
                EXECUTION_FAILURE_COOLDOWN_HOURS
            } else {
                COOLDOWN_HOURS
            }

            if (elapsed < cooldown) {
                return CircuitCheckResult(
                    canTrade = false,
                    reason = "[$market] 서킷 발동: ${state.reason} (${cooldown - elapsed}시간 후 재개)",
                    severity = if (state.reason.contains("실행 실패")) CircuitSeverity.HIGH else CircuitSeverity.HIGH
                )
            } else {
                resetMarketCircuit(market)
            }
        }

        return CircuitCheckResult(canTrade = true)
    }

    /**
     * 거래 결과 기록 및 서킷 브레이커 평가
     */
    fun recordTradeResult(market: String, pnlPercent: Double) {
        val state = circuitStates.getOrPut(market) { CircuitState() }
        state.lastTradeTime = Instant.now()

        if (pnlPercent < 0) {
            state.consecutiveLosses++
            state.dailyLossPercent += kotlin.math.abs(pnlPercent)
            state.dailyLossCount++

            // 수익 거래 시 실행 실패/슬리피지 카운터는 리셋하지 않음 (별개 이슈)

            log.warn("[$market] 손실 기록: ${String.format("%.2f", pnlPercent)}%, 연속 손실: ${state.consecutiveLosses}")

            // 연속 손실 체크
            if (state.consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
                tripCircuit(market, state, "연속 ${state.consecutiveLosses}회 손실")
            }

            // 일일 손실률 체크
            if (state.dailyLossPercent >= MAX_DAILY_LOSS_PERCENT) {
                tripCircuit(market, state, "일일 손실 ${String.format("%.2f", state.dailyLossPercent)}% 초과")
            }

        } else {
            // 수익 시 연속 손실 카운터만 리셋
            state.consecutiveLosses = 0
            // 실행 성공이므로 실행 실패/슬리피지 카운터도 리셋
            state.consecutiveExecutionFailures = 0
            state.consecutiveHighSlippage = 0
        }
    }

    /**
     * 주문 실행 실패 기록 (NEW)
     */
    fun recordExecutionFailure(market: String, reason: String) {
        val state = circuitStates.getOrPut(market) { CircuitState() }
        state.consecutiveExecutionFailures++

        log.warn("[$market] 주문 실행 실패: $reason (연속 ${state.consecutiveExecutionFailures}회)")

        if (state.consecutiveExecutionFailures >= MAX_CONSECUTIVE_EXECUTION_FAILURES) {
            tripCircuit(market, state, "연속 ${state.consecutiveExecutionFailures}회 주문 실행 실패: $reason")
        }
    }

    /**
     * 주문 실행 성공 기록 (NEW) - 실행 실패/슬리피지 카운터 리셋
     * [버그 수정] consecutiveHighSlippage도 리셋 (기존 실행 실패만 리셋)
     */
    fun recordExecutionSuccess(market: String) {
        val state = circuitStates[market] ?: return
        state.consecutiveExecutionFailures = 0
        state.consecutiveHighSlippage = 0
    }

    /**
     * 슬리피지 기록 (NEW)
     */
    fun recordSlippage(market: String, slippagePercent: Double) {
        val state = circuitStates.getOrPut(market) { CircuitState() }

        if (slippagePercent > HIGH_SLIPPAGE_THRESHOLD_PERCENT) {
            state.consecutiveHighSlippage++

            log.warn("[$market] 고슬리피지: ${String.format("%.2f", slippagePercent)}% (연속 ${state.consecutiveHighSlippage}회)")

            if (state.consecutiveHighSlippage >= MAX_CONSECUTIVE_HIGH_SLIPPAGE) {
                tripCircuit(market, state, "연속 ${state.consecutiveHighSlippage}회 고슬리피지 (${String.format("%.2f", slippagePercent)}%)")
            }
        } else {
            // 정상 슬리피지 시 카운터 리셋
            state.consecutiveHighSlippage = 0
        }
    }

    /**
     * API 에러 기록 (NEW)
     */
    fun recordApiError(market: String) {
        val now = Instant.now()
        val cutoff = now.minusSeconds(API_ERROR_WINDOW_SECONDS)

        // 마켓별 에러 기록
        val timestamps = apiErrorTimestamps.getOrPut(market) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeIf { it.isBefore(cutoff) }

        // 글로벌 카운터 업데이트
        globalApiErrorCount.incrementAndGet()
        lastGlobalApiErrorTime.set(now.toEpochMilli())

        log.warn("[$market] API 에러 기록 (1분 내 ${timestamps.size}회)")

        // 1분 내 에러 과다 시 글로벌 서킷
        val totalRecentErrors = apiErrorTimestamps.values.sumOf { list ->
            list.count { it.isAfter(cutoff) }
        }

        if (totalRecentErrors >= MAX_API_ERRORS_PER_MINUTE) {
            tripGlobalCircuit("1분 내 API 에러 ${totalRecentErrors}회 - 거래소 장애 의심")
        }
    }

    /**
     * 총 자산 변화 기록 (NEW)
     */
    fun recordTotalAsset(totalAssetKrw: Double) {
        if (initialTotalAsset == null) {
            initialTotalAsset = totalAssetKrw
            peakTotalAsset = totalAssetKrw
            log.info("초기 자산 설정: ${String.format("%.0f", totalAssetKrw)} KRW")
            return
        }

        // 최고점 갱신
        if (totalAssetKrw > peakTotalAsset) {
            peakTotalAsset = totalAssetKrw
        }

        // 최고점 대비 낙폭 계산
        val drawdownPercent = if (peakTotalAsset > 0) {
            (peakTotalAsset - totalAssetKrw) / peakTotalAsset * 100
        } else {
            0.0
        }

        if (drawdownPercent >= MAX_TOTAL_ASSET_DRAWDOWN_PERCENT) {
            tripGlobalCircuit("총 자산 ${String.format("%.1f", drawdownPercent)}% 감소 (최고점 대비)")
        }
    }

    /**
     * 서킷 브레이커 발동
     */
    private fun tripCircuit(market: String, state: CircuitState, reason: String) {
        if (state.isOpen) return  // 이미 발동 중이면 무시

        state.isOpen = true
        state.openTime = Instant.now()
        state.reason = reason

        log.error("=== 서킷 브레이커 발동 [$market] ===")
        log.error("사유: $reason")
        log.error("쿨다운: ${COOLDOWN_HOURS}시간")

        slackNotifier.sendError(market, """
            서킷 브레이커 발동

            사유: $reason
            연속 PnL 손실: ${state.consecutiveLosses}회
            연속 실행 실패: ${state.consecutiveExecutionFailures}회
            연속 고슬리피지: ${state.consecutiveHighSlippage}회
            일일 누적 손실: ${String.format("%.2f", state.dailyLossPercent)}%

            거래가 ${COOLDOWN_HOURS}시간 동안 중단됩니다.
            수동 재개: /api/circuit-breaker/reset/$market
        """.trimIndent())

        // 다수 마켓에서 서킷 발동 시 글로벌 서킷 발동 (비율 기반)
        val openCircuits = circuitStates.count { it.value.isOpen }
        val totalMarkets = circuitStates.size
        val tripRatio = if (totalMarkets > 0) openCircuits.toDouble() / totalMarkets else 0.0

        // 50% 이상 마켓에서 서킷 발동 시 글로벌 서킷 발동
        if (openCircuits >= 2 && tripRatio >= 0.5) {
            tripGlobalCircuit("${openCircuits}개/${totalMarkets}개 마켓 (${String.format("%.0f", tripRatio * 100)}%)에서 서킷 발동")
        }
    }

    /**
     * 글로벌 서킷 브레이커 발동
     */
    private fun tripGlobalCircuit(reason: String) {
        if (globalCircuitOpen) return  // 이미 발동 중이면 무시

        globalCircuitOpen = true
        globalCircuitOpenTime = Instant.now()
        globalCircuitReason = reason

        log.error("=== 글로벌 서킷 브레이커 발동 ===")
        log.error("사유: $reason")

        slackNotifier.sendSystemNotification("글로벌 서킷 브레이커 발동", """
            사유: $reason

            모든 거래가 ${GLOBAL_COOLDOWN_HOURS}시간 동안 중단됩니다.

            즉시 시스템 점검이 필요합니다.
            수동 재개: /api/circuit-breaker/reset-global
        """.trimIndent())
    }

    /**
     * 일일 통계 기반 서킷 브레이커 평가
     */
    @Scheduled(fixedRate = 300000)  // 5분마다
    fun evaluateDailyStats() {
        val since = Instant.now().minus(24, ChronoUnit.HOURS)

        for (market in circuitStates.keys) {
            try {
                val trades = tradeRepository.findByMarketAndSimulatedAndCreatedAtAfter(market, false, since)
                val losses = trades.filter { (it.pnl ?: 0.0) < 0 }

                if (losses.size >= MAX_DAILY_LOSS_COUNT) {
                    val state = circuitStates.getOrPut(market) { CircuitState() }
                    if (!state.isOpen) {
                        tripCircuit(market, state, "24시간 내 ${losses.size}회 손실")
                    }
                }
            } catch (e: Exception) {
                log.error("일일 통계 평가 실패 [$market]: ${e.message}")
            }
        }
    }

    /**
     * 일일 손실 카운터 리셋 (매일 자정)
     */
    @Scheduled(cron = "0 0 0 * * *")
    fun resetDailyCounters() {
        circuitStates.values.forEach { state ->
            state.dailyLossPercent = 0.0
            state.dailyLossCount = 0
        }
        log.info("일일 손실 카운터 리셋")
    }

    /**
     * 마켓 서킷 브레이커 리셋
     */
    fun resetMarketCircuit(market: String) {
        circuitStates[market] = CircuitState()
        log.info("[$market] 서킷 브레이커 리셋")
    }

    /**
     * 글로벌 서킷 브레이커 리셋
     */
    fun resetGlobalCircuit() {
        globalCircuitOpen = false
        globalCircuitOpenTime = null
        globalCircuitReason = ""
        log.info("글로벌 서킷 브레이커 리셋")
    }

    /**
     * API 에러 카운터 정리 (오래된 기록 삭제)
     */
    @Scheduled(fixedRate = 60000)  // 1분마다
    fun cleanupApiErrors() {
        val cutoff = Instant.now().minusSeconds(API_ERROR_WINDOW_SECONDS)
        apiErrorTimestamps.values.forEach { list ->
            list.removeIf { it.isBefore(cutoff) }
        }
    }

    /**
     * 현재 상태 조회
     */
    fun getStatus(): Map<String, Any?> {
        return mapOf(
            "globalCircuitOpen" to globalCircuitOpen,
            "globalCircuitOpenTime" to globalCircuitOpenTime?.toString(),
            "globalCircuitReason" to globalCircuitReason,
            "initialTotalAsset" to initialTotalAsset,
            "peakTotalAsset" to peakTotalAsset,
            "marketStates" to circuitStates.mapValues { (_, state) ->
                mapOf(
                    "isOpen" to state.isOpen,
                    "consecutiveLosses" to state.consecutiveLosses,
                    "consecutiveExecutionFailures" to state.consecutiveExecutionFailures,
                    "consecutiveHighSlippage" to state.consecutiveHighSlippage,
                    "dailyLossPercent" to state.dailyLossPercent,
                    "dailyLossCount" to state.dailyLossCount,
                    "openTime" to state.openTime?.toString(),
                    "reason" to state.reason,
                    "lastTradeTime" to state.lastTradeTime?.toString()
                )
            },
            "apiErrorCounts" to apiErrorTimestamps.mapValues { it.value.size }
        )
    }

    /**
     * 특정 마켓의 연속 실패 횟수 조회
     */
    fun getConsecutiveFailures(market: String): Int {
        return circuitStates[market]?.consecutiveExecutionFailures ?: 0
    }
}

data class CircuitCheckResult(
    val canTrade: Boolean,
    val reason: String = "",
    val severity: CircuitSeverity = CircuitSeverity.NONE
)

enum class CircuitSeverity {
    NONE,
    LOW,
    HIGH,
    CRITICAL
}
