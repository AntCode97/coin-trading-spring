package com.ant.cointrading.volumesurge

import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.bithumb.VirtualAssetWarning
import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.VolumeSurgeAlertEntity
import com.ant.cointrading.repository.VolumeSurgeAlertRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 거래량 급등 경보 폴링 서비스
 *
 * Bithumb 경보제 API를 주기적으로 조회하여
 * TRADING_VOLUME_SUDDEN_FLUCTUATION 경보를 감지하고 저장한다.
 */
@Service
class AlertPollingService(
    private val bithumbPublicApi: BithumbPublicApi,
    private val alertRepository: VolumeSurgeAlertRepository,
    private val volumeSurgeProperties: VolumeSurgeProperties,
    private val volumeSurgeEngine: VolumeSurgeEngine
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val ALERT_TYPE_VOLUME_SURGE = "TRADING_VOLUME_SUDDEN_FLUCTUATION"
        // Bithumb API 응답 형식: "2026-01-13 06:59:59"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    @PostConstruct
    fun init() {
        if (volumeSurgeProperties.enabled) {
            log.info("=== Volume Surge Alert Polling 활성화 ===")
            log.info("폴링 간격: ${volumeSurgeProperties.pollingIntervalMs}ms")
            log.info("경보 신선도: ${volumeSurgeProperties.alertFreshnessMin}분")
        } else {
            log.info("Volume Surge 전략 비활성화")
        }
    }

    /**
     * 경보 폴링 (30초마다)
     */
    @Scheduled(fixedDelayString = "\${volumesurge.polling-interval-ms:30000}")
    fun pollAlerts() {
        if (!volumeSurgeProperties.enabled) {
            return
        }

        try {
            val warnings = bithumbPublicApi.getVirtualAssetWarning()
            if (warnings.isNullOrEmpty()) {
                log.debug("경보 없음")
                return
            }

            // 거래량 급등 경보만 필터링
            val volumeSurgeAlerts = warnings.filter {
                it.warningType == ALERT_TYPE_VOLUME_SURGE
            }

            if (volumeSurgeAlerts.isEmpty()) {
                log.debug("거래량 급등 경보 없음")
                return
            }

            log.info("거래량 급등 경보 감지: ${volumeSurgeAlerts.size}건")

            volumeSurgeAlerts.forEach { warning ->
                processWarning(warning)
            }

        } catch (e: Exception) {
            log.error("경보 폴링 실패: ${e.message}", e)
        }
    }

    /**
     * 개별 경보 처리
     *
     * endDate는 경보 만료 시각이므로, 현재 시각 기준으로 신선도를 체크한다.
     * endDate가 현재 시각 이후이면 아직 유효한 경보.
     */
    private fun processWarning(warning: VirtualAssetWarning) {
        val market = warning.market

        // 경보 만료 시각 파싱
        val endTime = parseEndDate(warning.endDate)
        val now = Instant.now()

        // 만료된 경보 무시
        if (endTime != null && endTime.isBefore(now)) {
            log.debug("[$market] 만료된 경보 무시: endDate=${warning.endDate}")
            return
        }

        // REJECTED된 코인은 llmCooldownMin 동안 새 경보 insert 자체를 스킵
        // (DB 오염 방지 - 동일 코인이 5분마다 반복 insert되는 문제 해결)
        val llmCooldownThreshold = now.minusSeconds(volumeSurgeProperties.llmCooldownMin * 60L)
        val recentRejected = alertRepository.findTopByMarketAndLlmFilterResultAndDetectedAtAfterOrderByDetectedAtDesc(
            market, "REJECTED", llmCooldownThreshold
        )
        if (recentRejected != null) {
            log.debug("[$market] REJECTED 쿨다운 중 (${volumeSurgeProperties.llmCooldownMin}분), 경보 무시")
            return
        }

        // 중복 체크 (같은 마켓에서 최근 경보가 있는지)
        val freshnessThreshold = now.minusSeconds(volumeSurgeProperties.alertFreshnessMin * 60L)
        if (alertRepository.existsByMarketAndDetectedAtAfter(market, freshnessThreshold)) {
            log.debug("[$market] 최근 처리된 경보 있음, 스킵")
            return
        }

        // APPROVED된 코인도 llmCooldownMin 동안 LLM 재호출 안 함 (비용 절감)
        if (alertRepository.existsByMarketAndLlmFilterResultIsNotNullAndDetectedAtAfter(market, llmCooldownThreshold)) {
            log.info("[$market] LLM 쿨다운 중 (${volumeSurgeProperties.llmCooldownMin}분), LLM 호출 스킵")
            return
        }

        // 경보 저장 (현재 시각을 감지 시각으로 사용)
        val alertEntity = VolumeSurgeAlertEntity(
            market = market,
            alertType = warning.warningType ?: ALERT_TYPE_VOLUME_SURGE,
            detectedAt = now,
            processed = false
        )
        val savedAlert = alertRepository.save(alertEntity)

        log.info("[$market] 새 경보 저장: id=${savedAlert.id}, endDate=${warning.endDate}")

        // 엔진에 처리 요청
        try {
            volumeSurgeEngine.processAlert(savedAlert)
        } catch (e: Exception) {
            log.error("[$market] 경보 처리 실패: ${e.message}", e)
            savedAlert.processed = true
            savedAlert.llmFilterResult = "ERROR"
            savedAlert.llmFilterReason = e.message
            alertRepository.save(savedAlert)
        }
    }

    /**
     * 경보 만료 시각 파싱
     * 형식: "2026-01-13 06:59:59" (KST)
     */
    private fun parseEndDate(endDateStr: String?): Instant? {
        if (endDateStr.isNullOrBlank()) return null

        return try {
            val localDateTime = java.time.LocalDateTime.parse(endDateStr, DATE_FORMATTER)
            // KST (UTC+9) 기준으로 파싱
            localDateTime.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
        } catch (e: Exception) {
            log.warn("경보 만료 시각 파싱 실패: $endDateStr")
            null
        }
    }
}
