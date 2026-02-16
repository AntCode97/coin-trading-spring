package com.ant.cointrading.engine

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.config.ActivePositionManagerProperties
import com.ant.cointrading.config.EngineProfile
import com.ant.cointrading.indicator.DivergenceDetector
import com.ant.cointrading.indicator.DivergenceResult
import com.ant.cointrading.indicator.DivergenceStrength
import com.ant.cointrading.indicator.DivergenceType
import com.ant.cointrading.indicator.RsiCalculator
import com.ant.cointrading.model.Candle
import com.ant.cointrading.model.MarketRegime
import com.ant.cointrading.model.RegimeAnalysis
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.regime.RegimeDetector
import com.ant.cointrading.repository.DcaPositionEntity
import com.ant.cointrading.repository.DcaPositionRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.ConfluenceScoreCalculator
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Active Position Manager
 *
 * 포지션 보유 중에도 시장을 계속 분석하여 TP/SL을 동적으로 조정한다.
 * 기존 1초 모니터링 루프는 건드리지 않고, DB의 TP/SL 값만 갱신한다.
 *
 * 켄트백: 핵심 로직(evaluate)과 엔진별 어댑터(apply*)를 분리하여
 * 단일 재평가 알고리즘을 3개 엔진이 공유한다.
 */
@Component
class ActivePositionManager(
    private val properties: ActivePositionManagerProperties,
    private val bithumbPublicApi: BithumbPublicApi,
    private val regimeDetector: RegimeDetector,
    private val divergenceDetector: DivergenceDetector,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val dcaPositionRepository: DcaPositionRepository,
    private val slackNotifier: SlackNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 마켓별 분석 캐시 - 동일 마켓 복수 포지션 시 API 호출 1회로 공유 */
    private val analysisCache = ConcurrentHashMap<String, CachedAnalysis>()

    /** 마켓+전략별 마지막 재평가 결과 (대시보드 조회용) */
    private val lastReassessments = ConcurrentHashMap<String, PositionReassessment>()

    // ========== 스케줄링 ==========

    @Scheduled(fixedDelay = 60_000)
    fun reassessVolumeSurgePositions() {
        if (!properties.enabled) return
        val positions = volumeSurgeRepository.findByStatus("OPEN")
        if (positions.isEmpty()) return

        positions.forEach { position ->
            try {
                reassessVolumeSurge(position)
            } catch (e: Exception) {
                log.error("[${position.market}] VS 재평가 오류: ${e.message}")
            }
        }
        evictStaleCache()
    }

    @Scheduled(fixedDelay = 30_000)
    fun reassessMemeScalperPositions() {
        if (!properties.enabled) return
        val positions = memeScalperRepository.findByStatus("OPEN")
        if (positions.isEmpty()) return

        positions.forEach { position ->
            try {
                reassessMemeScalper(position)
            } catch (e: Exception) {
                log.error("[${position.market}] MS 재평가 오류: ${e.message}")
            }
        }
        evictStaleCache()
    }

    @Scheduled(fixedDelay = 300_000)
    fun reassessDcaPositions() {
        if (!properties.enabled) return
        val positions = dcaPositionRepository.findByStatus("OPEN")
        if (positions.isEmpty()) return

        positions.forEach { position ->
            try {
                reassessDca(position)
            } catch (e: Exception) {
                log.error("[${position.market}] DCA 재평가 오류: ${e.message}")
            }
        }
        evictStaleCache()
    }

    // ========== 대시보드 조회 ==========

    fun getLastReassessment(market: String, strategy: String): PositionReassessment? {
        return lastReassessments["$market:$strategy"]
    }

    fun getCachedAnalysis(market: String): CachedAnalysis? {
        return analysisCache[market]
    }

    // ========== 엔진별 재평가 ==========

    private fun reassessVolumeSurge(position: VolumeSurgeTradeEntity) {
        val market = position.market
        val profile = properties.volumeSurge

        val cached = getOrFetchAnalysis(market) ?: return
        val currentPrice = fetchCurrentPrice(market) ?: return
        val pnlPercent = safePnlPercent(position.entryPrice, currentPrice)

        val entryRegime = position.regime?.let { parseRegimeSafe(it) }
        val currentSL = position.appliedStopLossPercent ?: abs(VOLUME_SURGE_DEFAULT_SL)
        val entryConfluence = position.confluenceScore

        val result = evaluate(
            profile = profile,
            pnlPercent = pnlPercent,
            currentSL = currentSL,
            entryRegime = entryRegime,
            currentRegime = cached.regime.regime,
            divergence = cached.divergence,
            confluenceScore = cached.confluenceScore,
            entryConfluenceScore = entryConfluence
        )

        lastReassessments["$market:VOLUME_SURGE"] = result

        if (result.action == ReassessmentAction.HOLD) return

        log.info("[ActivePositionManager][$market] VS: ${result.action} - ${result.reason}")

        result.newStopLossPercent?.let { newSL ->
            position.appliedStopLossPercent = newSL
        }
        result.newTakeProfitPercent?.let { newTP ->
            position.appliedTakeProfitPercent = newTP
        }
        volumeSurgeRepository.save(position)

        if (result.action == ReassessmentAction.IMMEDIATE_EXIT) {
            sendImmediateExitAlert(market, "Volume Surge", result.reason)
        }
    }

    private fun reassessMemeScalper(position: MemeScalperTradeEntity) {
        val market = position.market
        val profile = properties.memeScalper

        val cached = getOrFetchAnalysis(market) ?: return
        val currentPrice = fetchCurrentPrice(market) ?: return
        val pnlPercent = safePnlPercent(position.entryPrice, currentPrice)

        val entryRegime = position.regime?.let { parseRegimeSafe(it) }
        // MemeScalper의 SL: 0이면 미조정(properties 기본값 사용)
        val currentSL = if (position.appliedStopLossPercent > 0) position.appliedStopLossPercent else abs(MEME_SCALPER_DEFAULT_SL)

        val result = evaluate(
            profile = profile,
            pnlPercent = pnlPercent,
            currentSL = currentSL,
            entryRegime = entryRegime,
            currentRegime = cached.regime.regime,
            divergence = cached.divergence,
            confluenceScore = cached.confluenceScore,
            entryConfluenceScore = null // MemeScalper는 진입 컨플루언스 없음
        )

        lastReassessments["$market:MEME_SCALPER"] = result

        if (result.action == ReassessmentAction.HOLD) return

        log.info("[ActivePositionManager][$market] MS: ${result.action} - ${result.reason}")

        result.newStopLossPercent?.let { newSL ->
            position.appliedStopLossPercent = newSL
        }
        result.newTakeProfitPercent?.let { newTP ->
            position.appliedTakeProfitPercent = newTP
        }
        memeScalperRepository.save(position)

        if (result.action == ReassessmentAction.IMMEDIATE_EXIT) {
            sendImmediateExitAlert(market, "Meme Scalper", result.reason)
        }
    }

    private fun reassessDca(position: DcaPositionEntity) {
        val market = position.market
        val profile = properties.dca

        val cached = getOrFetchAnalysis(market) ?: return
        val currentPrice = fetchCurrentPrice(market) ?: return
        val pnlPercent = safePnlPercent(position.averagePrice, currentPrice)

        val entryRegime = parseRegimeSafe(position.entryRegime)
        // DCA의 SL은 음수로 저장 (예: -3.0), 양수로 변환
        val currentSL = abs(position.stopLossPercent)

        val result = evaluate(
            profile = profile,
            pnlPercent = pnlPercent,
            currentSL = currentSL,
            entryRegime = entryRegime,
            currentRegime = cached.regime.regime,
            divergence = cached.divergence,
            confluenceScore = cached.confluenceScore,
            entryConfluenceScore = null // DCA는 진입 컨플루언스 없음
        )

        lastReassessments["$market:DCA"] = result

        if (result.action == ReassessmentAction.HOLD) return

        log.info("[ActivePositionManager][$market] DCA: ${result.action} - ${result.reason}")

        // DCA는 stopLossPercent가 음수 체계 (예: -3.0)
        result.newStopLossPercent?.let { newSL ->
            position.stopLossPercent = -newSL
        }
        result.newTakeProfitPercent?.let { newTP ->
            position.takeProfitPercent = newTP
        }
        dcaPositionRepository.save(position)

        if (result.action == ReassessmentAction.IMMEDIATE_EXIT) {
            sendImmediateExitAlert(market, "DCA", result.reason)
        }
    }

    // ========== 핵심 재평가 로직 ==========

    /**
     * 포지션 재평가 - 우선순위순 판단
     *
     * 모든 SL 값은 양수(거리) 체계로 통일.
     * 예: currentSL = 3.0 → 진입가 대비 -3%에서 손절
     */
    private fun evaluate(
        profile: EngineProfile,
        pnlPercent: Double,
        currentSL: Double,
        entryRegime: MarketRegime?,
        currentRegime: MarketRegime,
        divergence: DivergenceResult?,
        confluenceScore: Int?,
        entryConfluenceScore: Int?
    ): PositionReassessment {

        // 1. 레짐 전환 감지 (최우선)
        if (profile.regimeShiftExit && entryRegime != null) {
            if (isAdverseRegimeShift(entryRegime, currentRegime)) {
                return PositionReassessment(
                    action = ReassessmentAction.IMMEDIATE_EXIT,
                    newStopLossPercent = 0.01,
                    newTakeProfitPercent = null,
                    reason = "레짐 전환: $entryRegime -> $currentRegime",
                    currentRegime = currentRegime,
                    confluenceScore = confluenceScore
                )
            }
        }

        // 2. Progressive Stop Tightening (수익 구간별)
        // 수익 잠금: TP를 현재 수익의 최소 보존 수준으로 하향 설정
        // 1초 루프가 pnl >= TP 시 TAKE_PROFIT 청산하므로 별도 SL 수정 불필요
        if (pnlPercent >= profile.profitLockTrigger) {
            val minTakeProfit = pnlPercent - profile.profitLockMin
            if (minTakeProfit > 0) {
                return PositionReassessment(
                    action = ReassessmentAction.PROFIT_LOCK,
                    newStopLossPercent = 0.1,
                    newTakeProfitPercent = minTakeProfit,
                    reason = "수익 잠금: +${fmt(pnlPercent)}% → TP=${fmt(minTakeProfit)}%, SL=0.1%",
                    currentRegime = currentRegime,
                    confluenceScore = confluenceScore
                )
            }
        }

        if (pnlPercent >= profile.breakEvenTrigger && pnlPercent < profile.profitLockTrigger) {
            if (currentSL > 0.1) {
                return PositionReassessment(
                    action = ReassessmentAction.BREAK_EVEN_STOP,
                    newStopLossPercent = 0.1,
                    newTakeProfitPercent = null,
                    reason = "본전 방어: +${fmt(pnlPercent)}% → SL ${fmt(currentSL)}% -> 0.1%",
                    currentRegime = currentRegime,
                    confluenceScore = confluenceScore
                )
            }
        }

        // 3. 약세 다이버전스 감지
        if (divergence != null && divergence.hasDivergence &&
            divergence.type == DivergenceType.BEARISH &&
            (divergence.strength == DivergenceStrength.MODERATE || divergence.strength == DivergenceStrength.STRONG)
        ) {
            val tightenAmount = if (divergence.strength == DivergenceStrength.STRONG) {
                profile.divergenceStopTighten * 2
            } else {
                profile.divergenceStopTighten
            }
            val newSL = (currentSL - tightenAmount).coerceAtLeast(0.3)

            if (newSL < currentSL) {
                return PositionReassessment(
                    action = ReassessmentAction.TIGHTEN_STOP,
                    newStopLossPercent = newSL,
                    newTakeProfitPercent = null,
                    reason = "약세 다이버전스(${divergence.strength}): SL ${fmt(currentSL)}% -> ${fmt(newSL)}%",
                    currentRegime = currentRegime,
                    confluenceScore = confluenceScore
                )
            }
        }

        // 4. 레짐별 동적 재조정
        if (currentRegime == MarketRegime.HIGH_VOLATILITY && currentSL < 4.0) {
            // 고변동성: SL을 확장하여 노이즈로 인한 조기 청산 방지
            val widenedSL = (currentSL * 1.3).coerceAtMost(5.0)
            if (widenedSL > currentSL) {
                return PositionReassessment(
                    action = ReassessmentAction.WIDEN_STOP,
                    newStopLossPercent = widenedSL,
                    newTakeProfitPercent = null,
                    reason = "고변동성 레짐: SL ${fmt(currentSL)}% -> ${fmt(widenedSL)}% (노이즈 방지)",
                    currentRegime = currentRegime,
                    confluenceScore = confluenceScore
                )
            }
        }

        if (currentRegime == MarketRegime.SIDEWAYS) {
            // 횡보장: TP를 70%로 축소 (수익 실현 기회 감소 대비)
            // TP 축소는 1회만 적용 (이미 축소된 경우 무시 - newTakeProfitPercent가 이미 있으면 HOLD)
            // 여기서는 TP를 직접 반환하지 않고 HOLD (TP 축소는 별도 로직 필요)
        }

        // 5. 컨플루언스 급락
        if (entryConfluenceScore != null && confluenceScore != null) {
            val degradation = entryConfluenceScore - confluenceScore
            if (degradation >= profile.confluenceDegradation) {
                val newSL = (currentSL * 0.5).coerceAtLeast(0.3)
                if (newSL < currentSL) {
                    return PositionReassessment(
                        action = ReassessmentAction.TIGHTEN_STOP,
                        newStopLossPercent = newSL,
                        newTakeProfitPercent = null,
                        reason = "컨플루언스 급락: ${entryConfluenceScore}점 -> ${confluenceScore}점 (차이 ${degradation}), SL ${fmt(currentSL)}% -> ${fmt(newSL)}%",
                        currentRegime = currentRegime,
                        confluenceScore = confluenceScore
                    )
                }
            }
        }

        return PositionReassessment(
            action = ReassessmentAction.HOLD,
            newStopLossPercent = null,
            newTakeProfitPercent = null,
            reason = "변경 없음",
            currentRegime = currentRegime,
            confluenceScore = confluenceScore
        )
    }

    // ========== 분석 캐시 ==========

    private fun getOrFetchAnalysis(market: String): CachedAnalysis? {
        val cached = analysisCache[market]
        if (cached != null && cached.timestamp.isAfter(Instant.now().minusSeconds(55))) {
            return cached
        }

        return try {
            val candleResponses = bithumbPublicApi.getOhlcv(market, "1h", 50)
            if (candleResponses.isNullOrEmpty()) {
                log.warn("[ActivePositionManager][$market] 캔들 데이터 없음")
                return null
            }

            val regime = regimeDetector.detectFromBithumb(candleResponses)

            val candles = candleResponses.map { r ->
                Candle(
                    timestamp = parseInstantSafe(r.candleDateTimeUtc),
                    open = r.openingPrice,
                    high = r.highPrice,
                    low = r.lowPrice,
                    close = r.tradePrice,
                    volume = r.candleAccTradeVolume
                )
            }
            val closes = candles.map { it.close.toDouble() }

            val rsi = if (closes.size >= 15) RsiCalculator.calculate(closes) else null
            val rsiAll = if (closes.size >= 15) RsiCalculator.calculateAll(closes) else null
            val divergence = if (rsiAll != null && closes.size >= 15) {
                divergenceDetector.detectRsiDivergence(closes, rsiAll)
            } else null

            // 간이 컨플루언스 점수 (MTF 호출 생략하여 API 절약)
            val confluenceScore = calculateSimpleConfluence(candles, rsi)

            val analysis = CachedAnalysis(
                market = market,
                regime = regime,
                divergence = divergence,
                confluenceScore = confluenceScore,
                rsi = rsi,
                macdSignal = detectMacdSignal(closes),
                timestamp = Instant.now()
            )
            analysisCache[market] = analysis
            analysis
        } catch (e: Exception) {
            log.error("[ActivePositionManager][$market] 분석 실패: ${e.message}")
            null
        }
    }

    private fun evictStaleCache() {
        val cutoff = Instant.now().minusSeconds(120)
        analysisCache.entries.removeIf { it.value.timestamp.isBefore(cutoff) }
    }

    // ========== 보조 메서드 ==========

    private fun isAdverseRegimeShift(entry: MarketRegime, current: MarketRegime): Boolean {
        return (entry == MarketRegime.BULL_TREND || entry == MarketRegime.SIDEWAYS) &&
                current == MarketRegime.BEAR_TREND
    }

    private fun parseRegimeSafe(regime: String): MarketRegime? {
        return try {
            MarketRegime.valueOf(regime)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCurrentPrice(market: String): Double? {
        return bithumbPublicApi.getCurrentPrice(market)?.firstOrNull()?.tradePrice?.toDouble()
    }

    private fun safePnlPercent(entryPrice: Double, currentPrice: Double): Double {
        if (entryPrice <= 0) return 0.0
        return ((currentPrice - entryPrice) / entryPrice) * 100
    }

    private fun sendImmediateExitAlert(market: String, strategy: String, reason: String) {
        slackNotifier.sendSystemNotification(
            "APM 긴급 청산",
            """
            전략: $strategy
            마켓: $market
            사유: $reason
            SL을 0.01%로 설정하여 1초 루프에서 즉시 청산됩니다.
            """.trimIndent()
        )
    }

    /**
     * 간이 컨플루언스 점수 계산
     *
     * API 호출 절약을 위해 MTF 분석 없이 1시간봉 데이터만으로 점수 산출.
     */
    private fun calculateSimpleConfluence(candles: List<Candle>, rsi: Double?): Int? {
        if (candles.size < 30 || rsi == null) return null

        val closes = candles.map { it.close.toDouble() }
        val volumes = candles.map { it.volume.toDouble() }

        val macdSignal = detectMacdSignal(closes)
        val bollingerPosition = detectBollingerPosition(closes)
        val volumeRatio = if (volumes.size >= 21) {
            val avgVolume = volumes.dropLast(1).takeLast(20).average()
            if (avgVolume > 0) volumes.last() / avgVolume else 1.0
        } else 1.0

        return ConfluenceScoreCalculator.calculate(
            rsi = rsi,
            macdSignal = macdSignal,
            macdHistogramReversal = false,
            bollingerPosition = bollingerPosition,
            volumeRatio = volumeRatio,
            rsiDivergence = DivergenceType.NONE,
            divergenceStrength = null
        )
    }

    private fun detectMacdSignal(closes: List<Double>): String {
        if (closes.size < 27) return "NEUTRAL"
        val ema12 = calculateEma(closes, 12)
        val ema26 = calculateEma(closes, 26)
        val macdLine = ema12 - ema26
        val signalLine = calculateEmaFromEnd(closes, 12, 26, 9)
        return when {
            macdLine > signalLine -> "BULLISH"
            macdLine < signalLine -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    private fun detectBollingerPosition(closes: List<Double>): String {
        if (closes.size < 20) return "MIDDLE"
        val recent = closes.takeLast(20)
        val mean = recent.average()
        val std = kotlin.math.sqrt(recent.map { (it - mean) * (it - mean) }.average())
        val upper = mean + 2 * std
        val lower = mean - 2 * std
        val current = closes.last()
        return when {
            current >= upper -> "UPPER"
            current <= lower -> "LOWER"
            else -> "MIDDLE"
        }
    }

    private fun calculateEma(data: List<Double>, period: Int): Double {
        if (data.size < period) return data.lastOrNull() ?: 0.0
        val multiplier = 2.0 / (period + 1)
        var ema = data.take(period).average()
        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
        }
        return ema
    }

    private fun calculateEmaFromEnd(closes: List<Double>, fast: Int, slow: Int, signalPeriod: Int): Double {
        if (closes.size < slow + signalPeriod) return 0.0
        val macdValues = mutableListOf<Double>()
        for (i in slow until closes.size) {
            val subData = closes.subList(0, i + 1)
            val emaFast = calculateEma(subData, fast)
            val emaSlow = calculateEma(subData, slow)
            macdValues.add(emaFast - emaSlow)
        }
        return calculateEma(macdValues, signalPeriod)
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)

    private fun parseInstantSafe(dateTimeStr: String?): Instant {
        if (dateTimeStr.isNullOrBlank()) return Instant.now()
        return try {
            Instant.parse(dateTimeStr)
        } catch (_: Exception) {
            try {
                Instant.parse("${dateTimeStr}Z")
            } catch (_: Exception) {
                Instant.now()
            }
        }
    }

    companion object {
        private const val VOLUME_SURGE_DEFAULT_SL = 1.5
        private const val MEME_SCALPER_DEFAULT_SL = 1.5
    }
}
