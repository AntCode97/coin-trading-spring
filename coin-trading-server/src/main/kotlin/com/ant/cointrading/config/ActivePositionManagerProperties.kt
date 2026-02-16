package com.ant.cointrading.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Active Position Manager 설정
 *
 * 엔진별 재평가 프로파일을 외부화.
 * VolumeSurge/MemeScalper/DCA 각각 다른 주기와 임계값을 가진다.
 */
@Component
@ConfigurationProperties(prefix = "active-position-manager")
class ActivePositionManagerProperties {

    /** APM 전체 활성화 여부 */
    var enabled: Boolean = true

    /** Volume Surge 프로파일 */
    var volumeSurge: EngineProfile = EngineProfile(
        analyzeIntervalSeconds = 60,
        regimeShiftExit = true,
        breakEvenTrigger = 1.0,
        profitLockTrigger = 3.0,
        profitLockMin = 1.0,
        confluenceDegradation = 30,
        divergenceStopTighten = 0.5
    )

    /** Meme Scalper 프로파일 */
    var memeScalper: EngineProfile = EngineProfile(
        analyzeIntervalSeconds = 30,
        regimeShiftExit = false,
        breakEvenTrigger = 0.5,
        profitLockTrigger = 1.5,
        profitLockMin = 0.3,
        confluenceDegradation = 20,
        divergenceStopTighten = 0.3
    )

    /** DCA 프로파일 */
    var dca: EngineProfile = EngineProfile(
        analyzeIntervalSeconds = 300,
        regimeShiftExit = true,
        breakEvenTrigger = 3.0,
        profitLockTrigger = 8.0,
        profitLockMin = 3.0,
        confluenceDegradation = 40,
        divergenceStopTighten = 1.0
    )
}

/**
 * 엔진별 재평가 프로파일
 */
class EngineProfile(
    /** 분석 주기 (초) */
    var analyzeIntervalSeconds: Long = 60,

    /** 레짐 전환 시 즉시 청산 여부 */
    var regimeShiftExit: Boolean = true,

    /** 본전 이동 트리거 (%) - 이 수익률 도달 시 SL을 본전 근처로 이동 */
    var breakEvenTrigger: Double = 1.0,

    /** 수익 잠금 트리거 (%) - 이 수익률 도달 시 SL을 수익 보존 수준으로 이동 */
    var profitLockTrigger: Double = 3.0,

    /** 수익 잠금 최소 확보 (%) - profitLockTrigger 도달 시 최소 이만큼 수익 확보 */
    var profitLockMin: Double = 1.0,

    /** 컨플루언스 급락 임계값 - 진입 대비 이 점수 이하로 떨어지면 SL 축소 */
    var confluenceDegradation: Int = 30,

    /** 다이버전스 감지 시 SL 축소 폭 (%) */
    var divergenceStopTighten: Double = 0.5
)
