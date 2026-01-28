package com.ant.cointrading.memescalper

import com.ant.cointrading.api.bithumb.BithumbPrivateApi
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.config.TradingConstants
import com.ant.cointrading.engine.GlobalPositionManager
import com.ant.cointrading.model.SignalAction
import com.ant.cointrading.model.TradingSignal
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.order.OrderExecutor
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.MemeScalperDailyStatsEntity
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.*
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Meme Scalper 트레이딩 엔진
 *
 * 세력 코인 초단타 전략:
 * - 펌프 감지 → 즉시 진입
 * - 타이트한 손절/익절
 * - 5분 타임아웃
 *
 * 리스크 관리:
 * - 일일 손실 한도: 2만원
 * - 일일 거래 한도: 50회
 * - 연속 손실 3회 시 중단
 */
@Component
class MemeScalperEngine(
    private val properties: MemeScalperProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val bithumbPrivateApi: BithumbPrivateApi,
    private val detector: MemeScalperDetector,
    private val orderExecutor: OrderExecutor,
    private val tradeRepository: MemeScalperTradeRepository,
    private val statsRepository: MemeScalperDailyStatsRepository,
    private val slackNotifier: SlackNotifier,
    private val globalPositionManager: GlobalPositionManager,
    private val regimeDetector: RegimeDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 마켓별 쿨다운 추적
    private val cooldowns = ConcurrentHashMap<String, Instant>()

    // 마켓별 진입 락 (Race Condition 방지)
    private val entryLocks = ConcurrentHashMap<String, ReentrantLock>()

    // 피크 데이터 추적 (청산 판단용)
    private val peakVolumes = ConcurrentHashMap<Long, Double>()
    private val peakPrices = ConcurrentHashMap<Long, Double>()

    // 일일 통계
    private var dailyTradeCount = 0
    private var dailyPnl = 0.0
    private var consecutiveLosses = 0
    private var lastResetDate = LocalDate.now()

    companion object {
        // MemeScalper 전용 상수 (초단타 전략)
        private const val CLOSE_RETRY_BACKOFF_SECONDS = 5L    // 더 빠른 재시도
        private const val MAX_CLOSE_ATTEMPTS = 3               // 더 적은 시도

        // 최소 수익률 (%) - 수수료 0.08% × 2 = 0.16% 이상이어야 실익
        private const val MIN_PROFIT_PERCENT = 0.1
    }

    @PostConstruct
    fun init() {
        if (properties.enabled) {
            log.info("=== Meme Scalper Engine 시작 ===")
            log.info("설정: positionSize=${properties.positionSizeKrw}, stopLoss=${properties.stopLossPercent}%, takeProfit=${properties.takeProfitPercent}%")
            log.info("제외 마켓: ${properties.excludeMarkets}")

            // 열린 포지션 복원
            restoreOpenPositions()

            // 일일 통계 초기화
            initializeDailyStats()
        }
    }

    /**
     * 서버 재시작 시 열린 포지션 복원
     */
    private fun restoreOpenPositions() {
        val openPositions = tradeRepository.findByStatus("OPEN")
        if (openPositions.isEmpty()) {
            log.info("복원할 열린 포지션 없음")
            return
        }

        log.info("열린 포지션 ${openPositions.size}건 복원")
        openPositions.forEach { position ->
            peakPrices[position.id!!] = position.peakPrice ?: position.entryPrice
            peakVolumes[position.id!!] = position.peakVolume ?: 0.0
            log.info("[${position.market}] 포지션 복원 완료")
        }
    }

    /**
     * 일일 통계 초기화 (서버 재시작 시 복원)
     */
    private fun initializeDailyStats() {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()

        dailyTradeCount = tradeRepository.countTodayTrades(startOfDay)
        dailyPnl = tradeRepository.sumTodayPnl(startOfDay)

        // 서킷브레이커 상태 복원 (연속 손실)
        val todayStats = statsRepository.findByDate(today)
        if (todayStats != null) {
            consecutiveLosses = todayStats.consecutiveLosses
            log.info("서킷브레이커 상태 복원: 연속손실=${consecutiveLosses}회")
        }

        log.info("일일 통계 초기화: 거래=${dailyTradeCount}회, 손익=${dailyPnl}원, 연속손실=${consecutiveLosses}회")
    }

    /**
     * 펌프 스캔 (5초마다)
     */
    @Scheduled(fixedDelayString = "\${memescalper.polling-interval-ms:5000}")
    fun scanAndTrade() {
        if (!properties.enabled) return

        // 일일 리셋 체크
        checkDailyReset()

        // 거래 가능 여부 체크
        if (!canTrade()) {
            return
        }

        // 동시 포지션 수 체크
        val openPositions = tradeRepository.countByStatus("OPEN")
        if (openPositions >= properties.maxPositions) {
            log.debug("최대 포지션 도달 ($openPositions/${properties.maxPositions})")
            return
        }

        // 펌프 스캔
        try {
            val signals = detector.scanForPumps()
            if (signals.isEmpty()) {
                // TRACE 레벨로 변경 - 너무 잦은 로그 출력 방지
                log.trace("펌프 신호 없음")
                return
            }

            // 최상위 신호로 진입 시도 (락으로 Race Condition 방지)
            for (signal in signals.take(3)) {
                val lock = entryLocks.computeIfAbsent(signal.market) { ReentrantLock() }
                val entered = lock.withLock {
                    if (shouldEnter(signal)) {
                        enterPosition(signal)
                        true
                    } else {
                        false
                    }
                }
                if (entered) break  // 한 번에 하나만 진입
            }
        } catch (e: Exception) {
            log.error("펌프 스캔 중 오류: ${e.message}", e)
        }
    }

    /**
     * 포지션 모니터링 (1초마다)
     */
    @Scheduled(fixedDelay = 1000)
    fun monitorPositions() {
        if (!properties.enabled) return

        // OPEN 포지션 모니터링
        val openPositions = tradeRepository.findByStatus("OPEN")
        openPositions.forEach { position ->
            try {
                monitorSinglePosition(position)
            } catch (e: Exception) {
                log.error("[${position.market}] 모니터링 오류: ${e.message}")
            }
        }

        // CLOSING 포지션 모니터링
        val closingPositions = tradeRepository.findByStatus("CLOSING")
        closingPositions.forEach { position ->
            try {
                monitorClosingPosition(position)
            } catch (e: Exception) {
                log.error("[${position.market}] 청산 모니터링 오류: ${e.message}")
            }
        }
    }

    /**
     * 진입 조건 체크
     *
     * 중복 진입 방지:
     * 1. DB 포지션 체크 (OPEN, CLOSING 상태)
     * 2. 실제 잔고 체크 (이미 해당 코인 보유 시 진입 불가)
     * 3. [엔진 간 충돌 방지] 다른 엔진의 포지션 확인
     */
    private fun shouldEnter(signal: PumpSignal): Boolean {
        val market = signal.market

        // 쿨다운 체크
        val cooldownEnd = cooldowns[market]
        if (cooldownEnd != null && Instant.now().isBefore(cooldownEnd)) {
            log.debug("[$market] 쿨다운 중")
            return false
        }

        // 이미 열린 포지션 체크 (OPEN, CLOSING 모두)
        val existingOpen = tradeRepository.findByMarketAndStatus(market, "OPEN")
        val existingClosing = tradeRepository.findByMarketAndStatus(market, "CLOSING")
        if (existingOpen.isNotEmpty() || existingClosing.isNotEmpty()) {
            log.debug("[$market] 이미 포지션 존재 (OPEN=${existingOpen.size}, CLOSING=${existingClosing.size})")
            return false
        }

        // [엔진 간 충돌 방지] 다른 엔진(TradingEngine, VolumeSurge)에서 포지션 확인
        if (globalPositionManager.hasOpenPosition(market)) {
            log.debug("[$market] 다른 엔진에서 열린 포지션 존재 - MemeScalper 진입 차단")
            return false
        }

        // 실제 잔고 체크 - KRW 충분한지 + 이미 해당 코인 보유 시 진입 불가
        val coinSymbol = market.removePrefix("KRW-")
        val requiredKrw = BigDecimal(properties.positionSizeKrw)
        try {
            val balances = bithumbPrivateApi.getBalances()

            // 1. KRW 잔고 체크 - 포지션 크기 + 여유분(10%)
            val krwBalance = balances?.find { it.currency == "KRW" }?.balance ?: BigDecimal.ZERO
            val minRequired = requiredKrw.multiply(BigDecimal("1.1"))  // 10% 여유
            if (krwBalance < minRequired) {
                log.warn("[$market] KRW 잔고 부족: ${krwBalance}원 < ${minRequired}원")
                return false
            }

            // 2. 코인 잔고 체크 - 이미 보유 시 진입 불가 (ABANDONED 케이스 방지)
            val coinBalance = balances?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
            if (coinBalance > BigDecimal.ZERO) {
                log.warn("[$market] 이미 $coinSymbol 잔고 보유 중: $coinBalance - 중복 진입 방지")
                return false
            }
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, 진입 보류: ${e.message}")
            return false  // 잔고 확인 안 되면 진입하지 않음
        }

        // RSI 과매수 체크
        if (signal.rsi > properties.maxRsi) {
            log.debug("[$market] RSI 과매수 (${signal.rsi} > ${properties.maxRsi})")
            return false
        }

        // 최소 점수 체크 (MemeScalperDetector.MIN_ENTRY_SCORE와 동일)
        if (signal.score < 70) {
            log.debug("[$market] 점수 부족 (${signal.score} < 70)")
            return false
        }

        return true
    }

    /**
     * 포지션 진입
     */
    private fun enterPosition(signal: PumpSignal) {
        val market = signal.market

        log.info("[$market] 포지션 진입 시도 - 점수=${signal.score}, 거래량=${signal.volumeSpikeRatio}x")

        val positionSize = BigDecimal(properties.positionSizeKrw)

        // 시장 레짐 감지 (RegimeDetector 통합)
        val regime = try {
            val candles = bithumbPublicApi.getOhlcv(market, "minute60", 100)
            if (!candles.isNullOrEmpty() && candles.size >= 15) {
                val regimeAnalysis = regimeDetector.detectFromBithumb(candles)
                regimeAnalysis.regime.name
            } else {
                log.debug("[$market] 캔들 데이터 부족으로 레짐 감지 스킵")
                null
            }
        } catch (e: Exception) {
            log.warn("[$market] 레짐 감지 실패: ${e.message}")
            null
        }

        // 매수 주문 실행
        val buySignal = TradingSignal(
            market = market,
            action = SignalAction.BUY,
            confidence = signal.score.toDouble(),
            price = signal.currentPrice,
            reason = "Meme Scalper 진입: 펌프 감지${regime?.let { ", 레짐: $it" } ?: ""}",
            strategy = "MEME_SCALPER",
            regime = regime
        )

        val orderResult = orderExecutor.execute(buySignal, positionSize)

        if (!orderResult.success) {
            log.error("[$market] 매수 주문 실패: ${orderResult.message}")
            cooldowns[market] = Instant.now().plus(30, ChronoUnit.SECONDS)
            return
        }

        // 체결 수량 검증 - 0이면 실패로 간주
        val executedQuantity = orderResult.executedQuantity?.toDouble() ?: 0.0
        if (executedQuantity <= 0) {
            log.error("[$market] 체결 수량 0 - 주문 실패로 간주")
            cooldowns[market] = Instant.now().plus(30, ChronoUnit.SECONDS)
            return
        }

        // 체결가 결정 - 우선순위: orderResult > API 현재가 > signal
        val executedPrice = orderResult.price?.toDouble()?.takeIf { it > 0 }
            ?: bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
            ?: signal.currentPrice.toDouble()

        // 진입가 검증 - 0이면 포지션 생성 불가
        if (executedPrice <= 0) {
            log.error("[$market] 유효하지 않은 진입가: $executedPrice - 포지션 생성 취소")
            // 체결되었지만 가격을 모르면 위험 - 수동 확인 필요
            slackNotifier.sendWarning(market, "매수 체결되었으나 체결가 확인 불가 - 수동 확인 필요")
            cooldowns[market] = Instant.now().plus(60, ChronoUnit.SECONDS)
            return
        }

        // 체결 금액 검증 - 최소 금액 이하면 의미 없음
        val executedAmount = BigDecimal(executedPrice * executedQuantity)
        if (executedAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 체결 금액 미달 (${executedAmount}원 < ${TradingConstants.MIN_ORDER_AMOUNT_KRW}원)")
            // 이미 체결되었으므로 포지션은 생성하되 경고
        }

        // 슬리피지 경고 - 예상가 대비 2% 이상 차이나면 경고
        val expectedPrice = signal.currentPrice.toDouble()
        if (expectedPrice > 0) {
            val slippagePercent = ((executedPrice - expectedPrice) / expectedPrice) * 100
            if (kotlin.math.abs(slippagePercent) > 2.0) {
                log.warn("[$market] 슬리피지 경고: ${String.format("%.2f", slippagePercent)}% (예상=${expectedPrice}, 체결=${executedPrice})")
                slackNotifier.sendWarning(market, "슬리피지 ${String.format("%.2f", slippagePercent)}% 발생")
            }
        }

        // 최종 동시 포지션 수 체크 (Race Condition 방지용 2차 검증)
        val currentOpenCount = tradeRepository.countByStatus("OPEN")
        if (currentOpenCount >= properties.maxPositions) {
            log.warn("[$market] 최대 포지션 초과 (Race Condition 발생) - 포지션 생성 취소")
            slackNotifier.sendWarning(market, "매수 체결되었으나 최대 포지션 초과 - 수동 매도 필요")
            cooldowns[market] = Instant.now().plus(60, ChronoUnit.SECONDS)
            return
        }

        // 트레이드 엔티티 생성
        val trade = MemeScalperTradeEntity(
            market = market,
            entryPrice = executedPrice,
            quantity = executedQuantity,
            entryTime = Instant.now(),
            entryVolumeSpikeRatio = signal.volumeSpikeRatio,
            entryPriceSpikePercent = signal.priceSpikePercent,
            entryImbalance = signal.bidImbalance,
            entryRsi = signal.rsi,
            entryMacdSignal = signal.macdSignal,
            entrySpread = signal.spreadPercent,
            peakPrice = executedPrice,
            trailingActive = false,
            peakVolume = signal.volumeSpikeRatio,
            status = "OPEN",
            regime = regime  // 레짐 저장
        )

        val savedTrade = tradeRepository.save(trade)
        peakPrices[savedTrade.id!!] = executedPrice
        peakVolumes[savedTrade.id!!] = signal.volumeSpikeRatio

        // 쿨다운 설정
        cooldowns[market] = Instant.now().plus(properties.cooldownSec.toLong(), ChronoUnit.SECONDS)

        // 일일 카운트 증가
        dailyTradeCount++

        // Slack 알림
        slackNotifier.sendSystemNotification(
            "Meme Scalper 진입",
            """
            마켓: $market
            체결가: ${executedPrice}원
            수량: $executedQuantity
            점수: ${signal.score}
            거래량 스파이크: ${String.format("%.1f", signal.volumeSpikeRatio)}x
            가격 스파이크: ${String.format("%.1f", signal.priceSpikePercent)}%
            호가 Imbalance: ${String.format("%.2f", signal.bidImbalance)}
            RSI: ${String.format("%.1f", signal.rsi)}
            """.trimIndent()
        )

        log.info("[$market] 진입 완료: 가격=$executedPrice, 수량=$executedQuantity")
    }

    /**
     * 단일 포지션 모니터링
     *
     * 20년차 퀀트의 포지션 모니터링:
     * 1. 진입가 무결성 검증 - 0원이면 거래 불가
     * 2. 최소 보유 시간 - 수수료 고려 최소 10초
     * 3. 안전한 손익률 계산 - NaN/Infinity 방지
     */
    private fun monitorSinglePosition(position: MemeScalperTradeEntity) {
        val market = position.market

        // 0. 진입가 무결성 검증 - 진입가가 0이면 거래 불가능
        if (position.entryPrice <= 0) {
            log.error("[$market] 진입가 무효(${position.entryPrice}) - 포지션 정리 필요")
            // 현재가로 진입가 보정 시도
            val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
            if (ticker != null) {
                position.entryPrice = ticker.tradePrice.toDouble()
                position.entryTime = Instant.now()  // 진입 시간도 리셋
                tradeRepository.save(position)
                log.info("[$market] 진입가 보정: ${position.entryPrice}원")
            }
            return  // 이번 사이클은 스킵
        }

        // 1. 최소 보유 시간 체크 - 수수료(0.08%) 고려 최소 10초 보유
        val holdingSeconds = ChronoUnit.SECONDS.between(position.entryTime, Instant.now())
        if (holdingSeconds < TradingConstants.MIN_HOLDING_SECONDS) {
            return  // 아직 판단하지 않음 - 너무 빠른 청산은 수수료 손실
        }

        // 2. 현재가 조회
        val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull() ?: return
        val currentPrice = ticker.tradePrice.toDouble()

        // 3. 안전한 손익률 계산 - NaN/Infinity 방지
        val pnlPercent = safePnlPercent(position.entryPrice, currentPrice)

        // 4. 피크 업데이트
        val peakPrice = peakPrices.getOrDefault(position.id!!, position.entryPrice)
        if (currentPrice > peakPrice) {
            peakPrices[position.id!!] = currentPrice
            position.peakPrice = currentPrice
            tradeRepository.save(position)
        }

        // 5. 손절 체크 (손절은 최소 보유 시간 이후에만)
        if (pnlPercent <= properties.stopLossPercent) {
            closePosition(position, currentPrice, "STOP_LOSS")
            return
        }

        // 6. 트레일링 스탑 로직
        val triggerPercent = properties.trailingStopTrigger
        val offsetPercent = properties.trailingStopOffset

        // 트레일링 활성화 조건: 수익이 trigger% 이상
        if (!position.trailingActive && pnlPercent >= triggerPercent) {
            position.trailingActive = true
            log.info("[$market] 트레일링 스탑 활성화 (수익 ${String.format("%.2f", pnlPercent)}%)")
        }

        // 트레일링 중: 고점 대비 offset% 하락 시 청산
        if (position.trailingActive) {
            val peakPriceVal = peakPrices.getOrDefault(position.id!!, position.entryPrice)
            val drawdownPercent = ((peakPriceVal - currentPrice) / peakPriceVal) * 100

            if (drawdownPercent >= offsetPercent) {
                closePosition(position, currentPrice, "TRAILING_STOP")
                return
            }
        }

        // 7. 익절 체크 (수수료 0.08% 고려 - 최소 0.1% 이상 수익 시에만)
        if (pnlPercent >= properties.takeProfitPercent && pnlPercent > MIN_PROFIT_PERCENT) {
            closePosition(position, currentPrice, "TAKE_PROFIT")
            return
        }

        // 8. 타임아웃 체크
        if (holdingSeconds >= properties.positionTimeoutMin * 60) {
            closePosition(position, currentPrice, "TIMEOUT")
            return
        }

        // 9. 청산 신호 체크 (거래량 급감, 호가창 반전) - 30초 이후에만
        if (holdingSeconds >= 30) {
            val peakVol = peakVolumes.getOrDefault(position.id!!, 1.0)
            val exitSignal = detector.detectExitSignal(market, peakVol)
            if (exitSignal != null) {
                closePosition(position, currentPrice, exitSignal.reason)
                return
            }
        }
    }

    /**
     * CLOSING 포지션 모니터링
     */
    private fun monitorClosingPosition(position: MemeScalperTradeEntity) {
        val market = position.market
        val closeOrderId = position.closeOrderId

        if (closeOrderId.isNullOrBlank()) {
            position.status = "OPEN"
            position.closeAttemptCount = 0
            tradeRepository.save(position)
            return
        }

        try {
            val orderStatus = bithumbPrivateApi.getOrder(closeOrderId)

            when (orderStatus?.state) {
                "done" -> {
                    val actualPrice = orderStatus.price?.toDouble() ?: position.exitPrice ?: 0.0
                    finalizeClose(position, actualPrice, position.exitReason ?: "UNKNOWN")
                }
                "cancel" -> {
                    position.status = "OPEN"
                    position.closeOrderId = null
                    position.closeAttemptCount = 0
                    tradeRepository.save(position)
                }
                "wait" -> {
                    val elapsed = java.time.Duration.between(
                        position.lastCloseAttempt ?: Instant.now(),
                        Instant.now()
                    ).seconds

                    if (elapsed > 15) {  // 15초 대기 후 취소
                        try {
                            bithumbPrivateApi.cancelOrder(closeOrderId)
                        } catch (e: Exception) {
                            log.warn("[$market] 주문 취소 실패: ${e.message}")
                        }
                        position.status = "OPEN"
                        position.closeOrderId = null
                        tradeRepository.save(position)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("[$market] 청산 상태 조회 실패: ${e.message}")
            val elapsed = java.time.Duration.between(
                position.lastCloseAttempt ?: Instant.now(),
                Instant.now()
            ).toMinutes()

            if (elapsed >= 2) {
                position.status = "OPEN"
                position.closeOrderId = null
                position.closeAttemptCount = 0
                tradeRepository.save(position)
            }
        }
    }

    /**
     * 포지션 청산
     *
     * 버그 수정:
     * 1. 실제 잔고 확인 후 매도
     * 2. 손절 포함 모든 케이스에 최소 금액 검증
     */
    private fun closePosition(position: MemeScalperTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market

        if (position.status == "CLOSING") {
            return
        }

        // 백오프 체크
        val lastAttempt = position.lastCloseAttempt
        if (lastAttempt != null) {
            val elapsed = java.time.Duration.between(lastAttempt, Instant.now()).seconds
            if (elapsed < CLOSE_RETRY_BACKOFF_SECONDS) {
                return
            }
        }

        // 최대 시도 횟수 체크
        if (position.closeAttemptCount >= MAX_CLOSE_ATTEMPTS) {
            handleAbandonedPosition(position, exitPrice, "MAX_ATTEMPTS")
            return
        }

        log.info("[$market] 청산 시도 #${position.closeAttemptCount + 1}: $reason")

        // [BUG FIX] 실제 잔고 확인
        val coinSymbol = market.removePrefix("KRW-")
        val actualBalance = try {
            bithumbPrivateApi.getBalances()?.find { it.currency == coinSymbol }?.balance ?: BigDecimal.ZERO
        } catch (e: Exception) {
            log.warn("[$market] 잔고 조회 실패, DB 수량 사용: ${e.message}")
            BigDecimal(position.quantity)
        }

        // 실제 잔고 없으면 이미 청산됨
        if (actualBalance <= BigDecimal.ZERO) {
            log.warn("[$market] 실제 잔고 없음 - ABANDONED 처리")
            handleAbandonedPosition(position, exitPrice, "NO_BALANCE")
            return
        }

        // 실제 매도 수량 결정
        val sellQuantity = actualBalance.toDouble().coerceAtMost(position.quantity)
        val positionAmount = BigDecimal(sellQuantity * exitPrice)

        // 최소 금액 미달 체크 (손절 포함 모든 케이스에 적용)
        if (positionAmount < TradingConstants.MIN_ORDER_AMOUNT_KRW) {
            log.warn("[$market] 최소 주문 금액 미달 (${positionAmount.toPlainString()}원)")
            handleAbandonedPosition(position, exitPrice, "MIN_AMOUNT")
            return
        }

        position.status = "CLOSING"
        position.exitReason = reason
        position.exitPrice = exitPrice
        position.lastCloseAttempt = Instant.now()
        position.closeAttemptCount++
        // 실제 잔고로 수량 업데이트
        if (sellQuantity != position.quantity) {
            log.info("[$market] 매도 수량 조정: ${position.quantity} -> $sellQuantity")
            position.quantity = sellQuantity
        }
        tradeRepository.save(position)

        val sellSignal = TradingSignal(
            market = market,
            action = SignalAction.SELL,
            confidence = 100.0,
            price = BigDecimal(exitPrice),
            reason = "Meme Scalper 청산: $reason",
            strategy = "MEME_SCALPER"
        )

        val orderResult = orderExecutor.execute(sellSignal, positionAmount)

        if (orderResult.success) {
            val actualPrice = orderResult.price?.toDouble() ?: exitPrice

            if (orderResult.executedQuantity != null && orderResult.executedQuantity > BigDecimal.ZERO) {
                finalizeClose(position, actualPrice, reason)
            } else {
                position.closeOrderId = orderResult.orderId
                tradeRepository.save(position)
            }
        } else {
            val errorMessage = orderResult.message ?: ""

            if (errorMessage.contains("insufficient") ||
                errorMessage.contains("부족") ||
                errorMessage.contains("최소")) {
                handleAbandonedPosition(position, exitPrice, "API_ERROR")
            } else {
                position.status = "OPEN"
                position.closeOrderId = null
                tradeRepository.save(position)
            }
        }
    }

    /**
     * 청산 완료 처리
     */
    private fun finalizeClose(position: MemeScalperTradeEntity, actualPrice: Double, reason: String) {
        val market = position.market

        val safePnlAmount = safePnlAmount(position.entryPrice, actualPrice, position.quantity)
        val safePnlPercent = safePnlPercent(position.entryPrice, actualPrice)

        position.exitPrice = actualPrice
        position.exitTime = Instant.now()
        position.exitReason = reason
        position.pnlAmount = safePnlAmount
        position.pnlPercent = safePnlPercent
        position.status = "CLOSED"

        tradeRepository.save(position)

        // 피크 데이터 정리
        peakPrices.remove(position.id)
        peakVolumes.remove(position.id)

        // 일일 통계 업데이트
        dailyPnl += safePnlAmount
        if (safePnlAmount < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }

        // 서킷브레이커 상태 DB 저장 (재시작 시 복원용)
        saveCircuitBreakerState()

        // Slack 알림
        val emoji = if (safePnlAmount >= 0) "+" else ""
        val holdingSeconds = ChronoUnit.SECONDS.between(position.entryTime, Instant.now())
        slackNotifier.sendSystemNotification(
            "Meme Scalper 청산",
            """
            마켓: $market
            사유: $reason
            진입가: ${position.entryPrice}원
            청산가: ${actualPrice}원
            손익: $emoji${String.format("%.0f", safePnlAmount)}원 (${String.format("%.2f", safePnlPercent)}%)
            보유시간: ${holdingSeconds}초
            일일 손익: ${String.format("%.0f", dailyPnl)}원
            연속 손실: ${consecutiveLosses}회
            """.trimIndent()
        )

        log.info("[$market] 청산 완료: $reason, 손익=${safePnlAmount}원 (${safePnlPercent}%)")
    }

    /**
     * ABANDONED 처리
     *
     * 청산 실패 시에도 실제 손익을 계산하여 기록한다.
     * ABANDONED = 시스템 청산 실패지만 실제로는 포지션 종료 상태
     */
    private fun handleAbandonedPosition(position: MemeScalperTradeEntity, exitPrice: Double, reason: String) {
        val market = position.market

        // 실제 손익 계산 (현재가 기준)
        val safePnlAmount = safePnlAmount(position.entryPrice, exitPrice, position.quantity)
        val safePnlPercent = safePnlPercent(position.entryPrice, exitPrice)

        position.exitPrice = exitPrice
        position.exitTime = Instant.now()
        position.exitReason = "ABANDONED_$reason"
        position.pnlAmount = safePnlAmount
        position.pnlPercent = safePnlPercent
        position.status = "ABANDONED"

        tradeRepository.save(position)
        peakPrices.remove(position.id)
        peakVolumes.remove(position.id)

        // 일일 통계 업데이트 (ABANDONED도 실제 손익 반영)
        dailyPnl += safePnlAmount
        if (safePnlAmount < 0) {
            consecutiveLosses++
        } else {
            consecutiveLosses = 0
        }
        saveCircuitBreakerState()

        val emoji = if (safePnlAmount >= 0) "+" else ""
        slackNotifier.sendWarning(
            market,
            """
            Meme Scalper 포지션 ABANDONED: $reason
            진입가: ${position.entryPrice}원 → 현재가: ${exitPrice}원
            추정 손익: $emoji${String.format("%.0f", safePnlAmount)}원 (${String.format("%.2f", safePnlPercent)}%)
            수동 확인 필요
            """.trimIndent()
        )
        log.warn("[$market] ABANDONED 처리: $reason, 추정 손익=${safePnlAmount}원 (${safePnlPercent}%)")
    }

    /**
     * 거래 가능 여부 체크
     */
    private fun canTrade(): Boolean {
        // 일일 거래 횟수 한도
        if (dailyTradeCount >= properties.dailyMaxTrades) {
            log.debug("일일 거래 한도 도달 ($dailyTradeCount/${properties.dailyMaxTrades})")
            return false
        }

        // 일일 손실 한도
        if (dailyPnl <= -properties.dailyMaxLossKrw) {
            log.debug("일일 손실 한도 도달 (${dailyPnl}원)")
            return false
        }

        // 연속 손실 한도
        if (consecutiveLosses >= properties.maxConsecutiveLosses) {
            log.debug("연속 손실 한도 도달 (${consecutiveLosses}회)")
            return false
        }

        return true
    }

    /**
     * 일일 리셋 체크
     */
    private fun checkDailyReset() {
        val today = LocalDate.now()
        if (today != lastResetDate) {
            // 전일 통계 저장
            saveDailyStats(lastResetDate)

            // 리셋
            dailyTradeCount = 0
            dailyPnl = 0.0
            consecutiveLosses = 0
            lastResetDate = today

            log.info("=== Meme Scalper 일일 리셋 ===")
        }
    }

    /**
     * 일일 통계 저장
     *
     * CLOSED + ABANDONED 모두 포함하여 저장
     */
    private fun saveDailyStats(date: LocalDate) {
        try {
            val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            // CLOSED + ABANDONED 모두 포함
            val allTrades = tradeRepository.findByCreatedAtAfter(startOfDay)
                .filter { it.status == "CLOSED" || it.status == "ABANDONED" }

            if (allTrades.isEmpty()) return

            val winningTrades = allTrades.count { (it.pnlAmount ?: 0.0) > 0 }
            val losingTrades = allTrades.count { (it.pnlAmount ?: 0.0) < 0 }
            val totalPnl = allTrades.sumOf { it.pnlAmount ?: 0.0 }
            val winRate = if (allTrades.isNotEmpty()) winningTrades.toDouble() / allTrades.size else 0.0

            val avgHolding = allTrades
                .mapNotNull { trade ->
                    trade.exitTime?.let { ChronoUnit.SECONDS.between(trade.entryTime, it).toDouble() }
                }
                .average()

            val stats = statsRepository.findByDate(date) ?: MemeScalperDailyStatsEntity(date = date)
            stats.totalTrades = allTrades.size
            stats.winningTrades = winningTrades
            stats.losingTrades = losingTrades
            stats.totalPnl = totalPnl
            stats.winRate = winRate
            stats.avgHoldingSeconds = avgHolding
            stats.maxSingleLoss = allTrades.mapNotNull { it.pnlAmount }.minOrNull()
            stats.maxSingleProfit = allTrades.mapNotNull { it.pnlAmount }.maxOrNull()
            stats.consecutiveLosses = consecutiveLosses
            stats.circuitBreakerUpdatedAt = Instant.now()

            statsRepository.save(stats)
            log.info("일일 통계 저장: $date, 거래=${allTrades.size}, 승률=${String.format("%.1f", winRate * 100)}%, 손익=$totalPnl, 연속손실=$consecutiveLosses")
        } catch (e: Exception) {
            log.error("일일 통계 저장 실패: ${e.message}")
        }
    }

    /**
     * 상태 조회 (API용)
     */
    fun getStatus(): Map<String, Any> {
        val openPositions = tradeRepository.findByStatus("OPEN")
        return mapOf(
            "enabled" to properties.enabled,
            "openPositions" to openPositions.size,
            "dailyTradeCount" to dailyTradeCount,
            "dailyPnl" to dailyPnl,
            "consecutiveLosses" to consecutiveLosses,
            "canTrade" to canTrade()
        )
    }

    /**
     * 서킷 브레이커 리셋
     */
    fun resetCircuitBreaker(): Map<String, Any> {
        consecutiveLosses = 0
        log.info("서킷 브레이커 리셋")
        return mapOf("success" to true, "consecutiveLosses" to 0)
    }

    /**
     * 수동 청산
     */
    fun manualClose(market: String): Map<String, Any?> {
        val positions = tradeRepository.findByMarketAndStatus(market, "OPEN")
        if (positions.isEmpty()) {
            return mapOf("success" to false, "error" to "열린 포지션 없음")
        }

        val results = positions.map { position ->
            val ticker = bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()
            val exitPrice = ticker?.tradePrice?.toDouble() ?: position.entryPrice

            closePosition(position, exitPrice, "MANUAL")

            mapOf("positionId" to position.id, "exitPrice" to exitPrice)
        }

        return mapOf("success" to true, "closedPositions" to results)
    }

    /**
     * 서킷브레이커 상태 DB 저장 (재시작 시 복원용)
     */
    private fun saveCircuitBreakerState() {
        try {
            val today = LocalDate.now()
            val stats = statsRepository.findByDate(today) ?: MemeScalperDailyStatsEntity(date = today)
            stats.consecutiveLosses = consecutiveLosses
            stats.circuitBreakerUpdatedAt = Instant.now()
            statsRepository.save(stats)
        } catch (e: Exception) {
            log.error("서킷브레이커 상태 저장 실패: ${e.message}")
        }
    }
}
