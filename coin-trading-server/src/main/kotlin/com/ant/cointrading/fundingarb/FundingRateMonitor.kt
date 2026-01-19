package com.ant.cointrading.fundingarb

import com.ant.cointrading.api.binance.BinanceFuturesApi
import com.ant.cointrading.api.binance.FundingOpportunity
import com.ant.cointrading.config.FundingArbitrageProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.FundingRateEntity
import com.ant.cointrading.repository.FundingRateRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Funding Rate Monitor
 *
 * Phase 1: 모니터링 전용
 * - 1분마다 주요 코인의 펀딩 비율 조회
 * - 연환산 15% 이상 기회 발견 시 Slack 알림
 * - 펀딩 히스토리 DB 저장 (8시간마다)
 *
 * 퀀트 인사이트:
 * - 펀딩 비율 > 0: Long 포지션이 Short에게 지불 → Short 유리
 * - 펀딩 비율 < 0: Short 포지션이 Long에게 지불 → Long 유리
 * - 델타 중립 전략: 현물 Long + 선물 Short → 펀딩 수령
 */
@Component
class FundingRateMonitor(
    private val binanceFuturesApi: BinanceFuturesApi,
    private val fundingRateRepository: FundingRateRepository,
    private val slackNotifier: SlackNotifier,
    private val properties: FundingArbitrageProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 마지막 알림 시간 (중복 알림 방지)
    private val lastAlertTime = ConcurrentHashMap<String, Instant>()

    // 마지막 저장 시간 (히스토리 저장 주기 관리)
    private val lastSaveTime = ConcurrentHashMap<String, Instant>()

    // 알림 쿨다운 (같은 심볼에 대해 1시간 이내 재알림 방지)
    private val ALERT_COOLDOWN_MINUTES = 60L

    /**
     * 1분마다 펀딩 비율 모니터링
     */
    @Scheduled(fixedDelayString = "\${funding.monitoring-interval-ms:60000}")
    fun monitorFundingRates() {
        if (!properties.enabled) return

        try {
            val opportunities = binanceFuturesApi.scanFundingOpportunities(properties.minAnnualizedRate)

            if (opportunities.isNotEmpty()) {
                opportunities.forEach { opportunity ->
                    handleOpportunity(opportunity)
                }

                log.info("펀딩 기회 스캔 완료: ${opportunities.size}개 발견")
            }
        } catch (e: Exception) {
            log.error("펀딩 비율 모니터링 실패: ${e.message}")
        }
    }

    /**
     * 8시간마다 펀딩 히스토리 저장
     */
    @Scheduled(cron = "0 0 0,8,16 * * *")  // 00:00, 08:00, 16:00 UTC
    fun saveFundingHistory() {
        if (!properties.enabled) return

        log.info("펀딩 히스토리 저장 시작")

        properties.symbols.forEach { symbol ->
            try {
                val history = binanceFuturesApi.getFundingRateHistory(symbol, 1)
                history?.firstOrNull()?.let { rate ->
                    val fundingTime = Instant.ofEpochMilli(rate.fundingTime)

                    // 중복 저장 방지
                    if (!fundingRateRepository.existsBySymbolAndFundingTime(symbol, fundingTime)) {
                        val entity = FundingRateEntity(
                            exchange = "BINANCE",
                            symbol = symbol,
                            fundingRate = rate.fundingRate.toDouble(),
                            fundingTime = fundingTime,
                            annualizedRate = rate.fundingRate.toDouble() * 3 * 365 * 100,
                            markPrice = rate.markPrice?.toDouble()
                        )
                        fundingRateRepository.save(entity)
                        log.info("펀딩 히스토리 저장: $symbol ${rate.fundingRate}")
                    }
                }
            } catch (e: Exception) {
                log.error("펀딩 히스토리 저장 실패 [$symbol]: ${e.message}")
            }
        }
    }

    /**
     * 펀딩 기회 처리
     */
    private fun handleOpportunity(opportunity: FundingOpportunity) {
        val symbol = opportunity.symbol

        // 중복 알림 방지
        val lastAlert = lastAlertTime[symbol]
        if (lastAlert != null && ChronoUnit.MINUTES.between(lastAlert, Instant.now()) < ALERT_COOLDOWN_MINUTES) {
            return
        }

        // 진입 권장 조건 체크
        if (opportunity.isRecommendedEntry()) {
            sendHighRateAlert(opportunity)
            lastAlertTime[symbol] = Instant.now()
        } else if (opportunity.annualizedRate >= properties.highRateAlertThreshold) {
            // 높은 수익률 알림 (진입 권장 아니어도)
            sendHighRateAlert(opportunity)
            lastAlertTime[symbol] = Instant.now()
        }

        // DB에 기록
        saveFundingRate(opportunity)
    }

    /**
     * 높은 펀딩 비율 알림
     */
    private fun sendHighRateAlert(opportunity: FundingOpportunity) {
        val minutesUntil = opportunity.minutesUntilFunding()
        val isRecommended = opportunity.isRecommendedEntry()

        val message = buildString {
            if (isRecommended) {
                appendLine("*:rocket: 펀딩 수익 기회 (진입 권장)*")
            } else {
                appendLine("*:chart_with_upwards_trend: 높은 펀딩 비율 감지*")
            }
            appendLine()
            appendLine("심볼: `${opportunity.symbol}`")
            appendLine("펀딩 비율: `${opportunity.fundingRatePercent()}`")
            appendLine("연환산 수익률: `${opportunity.annualizedRateFormatted()}`")
            appendLine("다음 펀딩: `${minutesUntil}분 후`")
            appendLine("마크 가격: `$${String.format("%,.2f", opportunity.markPrice)}`")
            appendLine()
            if (isRecommended) {
                appendLine("_전략: 빗썸 현물 매수 + Binance 선물 숏_")
            }
        }

        slackNotifier.sendSystemNotification(
            if (isRecommended) "Funding Rate Opportunity" else "High Funding Rate",
            message
        )

        log.info("펀딩 알림 전송: ${opportunity.symbol} ${opportunity.annualizedRateFormatted()}")
    }

    /**
     * 펀딩 비율 DB 저장
     */
    private fun saveFundingRate(opportunity: FundingOpportunity) {
        val symbol = opportunity.symbol
        val lastSave = lastSaveTime[symbol]

        // 같은 심볼에 대해 5분 이내 재저장 방지
        if (lastSave != null && ChronoUnit.MINUTES.between(lastSave, Instant.now()) < 5) {
            return
        }

        try {
            val entity = FundingRateEntity(
                exchange = opportunity.exchange,
                symbol = symbol,
                fundingRate = opportunity.fundingRate,
                fundingTime = opportunity.nextFundingTime.minus(8, ChronoUnit.HOURS),  // 현재 펀딩
                annualizedRate = opportunity.annualizedRate,
                markPrice = opportunity.markPrice,
                indexPrice = opportunity.indexPrice
            )
            fundingRateRepository.save(entity)
            lastSaveTime[symbol] = Instant.now()
        } catch (e: Exception) {
            log.error("펀딩 비율 저장 실패 [$symbol]: ${e.message}")
        }
    }

    /**
     * 수동 스캔 트리거 (API용)
     */
    fun manualScan(): List<FundingOpportunity> {
        return binanceFuturesApi.scanFundingOpportunities(properties.minAnnualizedRate)
    }

    /**
     * 특정 심볼 펀딩 정보 조회
     */
    fun getFundingInfo(symbol: String) = binanceFuturesApi.getFundingInfo(symbol)

    /**
     * 현재 모니터링 상태
     */
    fun getStatus(): FundingMonitorStatus {
        val opportunities = binanceFuturesApi.scanFundingOpportunities(0.0)  // 모든 심볼
        val highRateCount = opportunities.count { it.annualizedRate >= properties.minAnnualizedRate }

        return FundingMonitorStatus(
            enabled = properties.enabled,
            autoTradingEnabled = properties.autoTradingEnabled,
            monitoredSymbols = properties.symbols.size,
            currentOpportunities = highRateCount,
            lastCheckTime = Instant.now(),
            topOpportunity = opportunities.maxByOrNull { it.annualizedRate }
        )
    }
}

/**
 * 펀딩 모니터 상태
 */
data class FundingMonitorStatus(
    val enabled: Boolean,
    val autoTradingEnabled: Boolean,
    val monitoredSymbols: Int,
    val currentOpportunities: Int,
    val lastCheckTime: Instant,
    val topOpportunity: FundingOpportunity?
)
