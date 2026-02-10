package com.ant.cointrading.volumesurge

import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.TradeRepository
import com.ant.cointrading.repository.VolumeSurgeAlertRepository
import com.ant.cointrading.repository.VolumeSurgeDailySummaryRepository
import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.service.KeyValueService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.Instant
import kotlin.test.assertContains

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VolumeSurgeReflectorTools")
class VolumeSurgeReflectorToolsTest {

    @Mock
    private lateinit var alertRepository: VolumeSurgeAlertRepository

    @Mock
    private lateinit var tradeRepository: VolumeSurgeTradeRepository

    @Mock
    private lateinit var summaryRepository: VolumeSurgeDailySummaryRepository

    @Mock
    private lateinit var keyValueService: KeyValueService

    @Mock
    private lateinit var slackNotifier: SlackNotifier

    @Mock
    private lateinit var volumeSurgeProperties: VolumeSurgeProperties

    @Mock
    private lateinit var generalTradeRepository: TradeRepository

    private lateinit var tools: VolumeSurgeReflectorTools

    @BeforeEach
    fun setup() {
        tools = VolumeSurgeReflectorTools(
            alertRepository = alertRepository,
            tradeRepository = tradeRepository,
            summaryRepository = summaryRepository,
            keyValueService = keyValueService,
            objectMapper = ObjectMapper(),
            slackNotifier = slackNotifier,
            volumeSurgeProperties = volumeSurgeProperties,
            generalTradeRepository = generalTradeRepository
        )
    }

    @Nested
    @DisplayName("통계 조회")
    inner class StatsTest {

        @Test
        @DisplayName("오늘 통계를 포맷해서 반환한다")
        fun returnsFormattedTodayStats() {
            whenever(alertRepository.countByDetectedAtBetween(any(), any())).thenReturn(12)
            whenever(alertRepository.countApprovedBetween(any(), any())).thenReturn(5)
            whenever(tradeRepository.sumPnlBetween(any(), any())).thenReturn(12345.0)
            whenever(tradeRepository.countWinningBetween(any(), any())).thenReturn(4)
            whenever(tradeRepository.countLosingBetween(any(), any())).thenReturn(3)
            whenever(tradeRepository.avgHoldingMinutesBetween(any(), any())).thenReturn(11.2)

            val result = tools.getTodayStats()

            assertContains(result, "총 경보: 12건")
            assertContains(result, "승인된 경보: 5건")
            assertContains(result, "총 손익: 12345원")
            assertContains(result, "승리/패배: 4 / 3")
        }
    }

    @Nested
    @DisplayName("파라미터 변경")
    inner class ParameterChangeTest {

        @Test
        @DisplayName("허용되지 않은 파라미터는 거부한다")
        fun rejectsUnknownParameter() {
            val result = tools.suggestParameterChange("unknownParam", 1.0, "test")

            assertContains(result, "허용되지 않은 파라미터")
        }

        @Test
        @DisplayName("현재값과 동일한 값이면 변경하지 않는다")
        fun skipsWhenValueIsSame() {
            whenever(volumeSurgeProperties.maxRsi).thenReturn(70.0)

            val result = tools.suggestParameterChange("maxRsi", 70.0, "same value")

            assertContains(result, "동일하여 변경하지 않았습니다")
        }
    }

    @Nested
    @DisplayName("백테스트")
    inner class BacktestTest {

        @Test
        @DisplayName("트레이드가 없으면 안내 메시지를 반환한다")
        fun returnsEmptyMessageWhenNoTrades() {
            whenever(tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(emptyList())

            val result = tools.backtestParameterChange("minVolumeRatio", 2.0, 7)

            assertContains(result, "트레이드가 없습니다")
        }

        @Test
        @DisplayName("minVolumeRatio 변경 시 필터링 효과를 계산한다")
        fun analyzesMinVolumeRatioChange() {
            whenever(volumeSurgeProperties.minVolumeRatio).thenReturn(1.5)
            whenever(tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(
                    listOf(
                        createTrade(volumeRatio = 1.2, pnl = 100.0),
                        createTrade(volumeRatio = 2.1, pnl = -50.0),
                        createTrade(volumeRatio = 3.0, pnl = 80.0)
                    )
                )

            val result = tools.backtestParameterChange("minVolumeRatio", 2.0, 7)

            assertContains(result, "분석 기간 트레이드: 3건")
            assertContains(result, "진입 가능 트레이드: 2 건")
            assertContains(result, "필터링된 트레이드: 1 건")
        }
    }

    private fun createTrade(volumeRatio: Double, pnl: Double): VolumeSurgeTradeEntity {
        return VolumeSurgeTradeEntity(
            market = "KRW-TEST",
            entryPrice = 1000.0,
            exitPrice = 1010.0,
            quantity = 1.0,
            entryTime = Instant.now().minusSeconds(600),
            exitTime = Instant.now(),
            pnlAmount = pnl,
            pnlPercent = pnl / 100.0,
            entryVolumeRatio = volumeRatio,
            confluenceScore = 60,
            status = "CLOSED"
        )
    }
}
