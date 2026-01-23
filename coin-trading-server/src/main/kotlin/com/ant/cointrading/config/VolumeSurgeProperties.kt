package com.ant.cointrading.config

import com.ant.cointrading.service.KeyValueService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Volume Surge 전략 설정
 *
 * 퀀트 연구 기반 기본값:
 * - 거래량 비율 300% 이상에서 의미 있는 급등
 * - RSI 70 이하에서 진입 (과매수 회피)
 * - 컨플루언스 60점 이상 (최소 2~3개 지표 일치)
 * - 손절: ATR 기반 동적 (횡보 1.5x, 추세 3x, 고변동 4x)
 * - 익절: 동적 (손절 × 2 = R:R 2:1 확보)
 *
 * LLM 회고 시스템이 파라미터를 자동 변경하면 KeyValueService에 저장되고,
 * 재시작 시 @PostConstruct에서 복원된다.
 */
@Component
@ConfigurationProperties(prefix = "volumesurge")
class VolumeSurgeProperties {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var keyValueService: KeyValueService

    /** 전략 활성화 여부 */
    var enabled: Boolean = false

    /** 경보 폴링 간격 (밀리초) */
    var pollingIntervalMs: Long = 30000

    // === 진입 조건 ===

    /** 최소 거래량 비율 (20일 평균 대비) */
    var minVolumeRatio: Double = 1.5

    /** 최대 RSI (과매수 영역 진입 제한) - 모멘텀 전략이므로 80까지 허용 */
    var maxRsi: Double = 80.0

    /** 최소 컨플루언스 점수 - 거래량이 충분하면 완화됨 */
    var minConfluenceScore: Int = 40

    /** 최소 시가총액 (KRW) */
    var minMarketCapKrw: Long = 50_000_000_000

    // === 포지션 관리 ===

    /** 1회 포지션 금액 (KRW) */
    var positionSizeKrw: Int = 10000

    /** 최대 동시 포지션 수 */
    var maxPositions: Int = 5

    // === 리스크 관리 ===

    /** 손절 (%) - 고정 손절 사용 시 */
    var stopLossPercent: Double = -2.0

    /** 익절 (%) - 고정 익절 사용 시 */
    var takeProfitPercent: Double = 6.0

    /** ATR 기반 동적 손절 사용 여부 */
    var useDynamicStopLoss: Boolean = true

    /** 동적 익절 사용 여부 (손절 × 배수 = 익절) */
    var useDynamicTakeProfit: Boolean = true

    /** 동적 익절 배수 (R:R 비율, 기본 2.0 = 손절의 2배) */
    var takeProfitMultiplier: Double = 2.0

    /** 트레일링 스탑 트리거 (%) - 이 수익률 이상에서 활성화 */
    var trailingStopTrigger: Double = 2.0

    /** 트레일링 스탑 오프셋 (%) - 고점 대비 이 비율 하락 시 청산 */
    var trailingStopOffset: Double = 1.0

    /** 포지션 타임아웃 (분) - 모멘텀 유지 시간 고려하여 60분으로 확대 */
    var positionTimeoutMin: Int = 60

    // === 필터링 ===

    /** 경보 신선도 (분) - 이 시간 이내 경보만 처리 */
    var alertFreshnessMin: Int = 5

    /** 같은 종목 재진입 쿨다운 (분) - 30분로 단축하여 더 많은 기회 포착 */
    var cooldownMin: Int = 30

    /** LLM 필터 쿨다운 (분) - LLM 검증 후 이 시간 동안 재호출 안 함 */
    var llmCooldownMin: Int = 240

    // === 서킷 브레이커 ===

    /** 연속 손실 횟수 제한 */
    var maxConsecutiveLosses: Int = 3

    /** 일일 최대 손실 (KRW) */
    var dailyMaxLossKrw: Int = 30000

    // === LLM 설정 ===

    /** LLM 필터 활성화 */
    var llmFilterEnabled: Boolean = true

    /** 회고 크론 표현식 (기본: 매일 새벽 1시) */
    var reflectionCron: String = "0 0 1 * * *"

    override fun toString(): String {
        return """
            VolumeSurgeProperties(
                enabled=$enabled,
                minVolumeRatio=$minVolumeRatio,
                positionSizeKrw=$positionSizeKrw,
                stopLossPercent=$stopLossPercent,
                takeProfitPercent=$takeProfitPercent,
                llmFilterEnabled=$llmFilterEnabled
            )
        """.trimIndent()
    }

    /**
     * KeyValueService에서 저장된 파라미터 복원
     * LLM 회고 시스템이 변경한 파라미터가 재시작 후에도 유지된다.
     */
    @PostConstruct
    fun restoreFromKeyValue() {
        log.info("=== VolumeSurgeProperties KeyValue 복원 시작 ===")

        var restoredCount = 0

        // 각 파라미터를 KeyValueService에서 로드
        keyValueService.getDouble("volumesurge.minVolumeRatio", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: minVolumeRatio = $minVolumeRatio -> $it")
            minVolumeRatio = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.maxRsi", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: maxRsi = $maxRsi -> $it")
            maxRsi = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.minConfluenceScore", -1).takeIf { it > 0 }?.let {
            log.info("복원: minConfluenceScore = $minConfluenceScore -> $it")
            minConfluenceScore = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.stopLossPercent", 1.0).takeIf { it < 0 }?.let {
            log.info("복원: stopLossPercent = $stopLossPercent -> $it")
            stopLossPercent = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.takeProfitPercent", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: takeProfitPercent = $takeProfitPercent -> $it")
            takeProfitPercent = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.takeProfitMultiplier", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: takeProfitMultiplier = $takeProfitMultiplier -> $it")
            takeProfitMultiplier = it
            restoredCount++
        }

        keyValueService.getBoolean("volumesurge.useDynamicTakeProfit")?.let {
            log.info("복원: useDynamicTakeProfit = $useDynamicTakeProfit -> $it")
            useDynamicTakeProfit = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.trailingStopTrigger", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: trailingStopTrigger = $trailingStopTrigger -> $it")
            trailingStopTrigger = it
            restoredCount++
        }

        keyValueService.getDouble("volumesurge.trailingStopOffset", -1.0).takeIf { it > 0 }?.let {
            log.info("복원: trailingStopOffset = $trailingStopOffset -> $it")
            trailingStopOffset = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.positionTimeoutMin", -1).takeIf { it > 0 }?.let {
            log.info("복원: positionTimeoutMin = $positionTimeoutMin -> $it")
            positionTimeoutMin = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.cooldownMin", -1).takeIf { it > 0 }?.let {
            log.info("복원: cooldownMin = $cooldownMin -> $it")
            cooldownMin = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.maxConsecutiveLosses", -1).takeIf { it > 0 }?.let {
            log.info("복원: maxConsecutiveLosses = $maxConsecutiveLosses -> $it")
            maxConsecutiveLosses = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.dailyMaxLossKrw", -1).takeIf { it > 0 }?.let {
            log.info("복원: dailyMaxLossKrw = $dailyMaxLossKrw -> $it")
            dailyMaxLossKrw = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.positionSizeKrw", -1).takeIf { it > 0 }?.let {
            log.info("복원: positionSizeKrw = $positionSizeKrw -> $it")
            positionSizeKrw = it
            restoredCount++
        }

        keyValueService.getInt("volumesurge.alertFreshnessMin", -1).takeIf { it > 0 }?.let {
            log.info("복원: alertFreshnessMin = $alertFreshnessMin -> $it")
            alertFreshnessMin = it
            restoredCount++
        }

        log.info("=== VolumeSurgeProperties KeyValue 복원 완료: ${restoredCount}개 파라미터 ===")
        log.info("현재 설정: $this")
    }
}
