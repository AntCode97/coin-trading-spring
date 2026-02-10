package com.ant.cointrading.memescalper

import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.repository.MemeScalperDailyStatsRepository
import com.ant.cointrading.repository.MemeScalperTradeEntity
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.service.KeyValueService
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
import kotlin.test.assertTrue

/**
 * MemeScalperReflectorTools 테스트
 *
 * 회고 시스템의 핵심 도구들 검증:
 * 1. backtestParameterChange - 파라미터 변경 전 백테스트
 * 2. analyzeExitPatterns - 청산 사유별 패턴 분석
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemeScalperReflectorTools")
class MemeScalperReflectorToolsTest {

    @Mock
    private lateinit var tradeRepository: MemeScalperTradeRepository

    @Mock
    private lateinit var statsRepository: MemeScalperDailyStatsRepository

    @Mock
    private lateinit var keyValueService: KeyValueService

    @Mock
    private lateinit var slackNotifier: SlackNotifier

    @Mock
    private lateinit var properties: MemeScalperProperties

    private lateinit var tools: MemeScalperReflectorTools

    @BeforeEach
    fun setup() {
        tools = MemeScalperReflectorTools(
            tradeRepository,
            statsRepository,
            keyValueService,
            slackNotifier,
            properties
        )
    }

    @Nested
    @DisplayName("백테스트 기능")
    inner class BacktestTest {

        @Test
        @DisplayName("트레이드가 없으면 적절한 메시지 반환")
        fun returnsEmptyMessageWhenNoTrades() {
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(emptyList())

            val result = tools.backtestParameterChange("volumeSpikeRatio", 3.0, 7)

            assertContains(result, "트레이드가 없습니다")
        }

        @Test
        @DisplayName("volumeSpikeRatio 변경 시 필터링 효과 분석")
        fun analyzesVolumeSpikeRatioChange() {
            val trades = listOf(
                createTrade(volumeSpike = 2.0, pnl = 100.0),
                createTrade(volumeSpike = 3.5, pnl = 200.0),
                createTrade(volumeSpike = 4.0, pnl = -50.0),
                createTrade(volumeSpike = 5.0, pnl = 150.0)
            )
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(trades)
            whenever(properties.volumeSpikeRatio).thenReturn(2.0)

            val result = tools.backtestParameterChange("volumeSpikeRatio", 3.0, 7)

            assertContains(result, "분석 기간 트레이드: 4건")
            assertContains(result, "진입 가능 트레이드: 3")  // 3.5, 4.0, 5.0
            assertContains(result, "필터링된 트레이드: 1")  // 2.0
        }

        @Test
        @DisplayName("트레이드 50% 이상 감소 시 경고")
        fun warnsWhenTradesReducedSignificantly() {
            val trades = listOf(
                createTrade(volumeSpike = 2.0, pnl = 100.0),
                createTrade(volumeSpike = 2.5, pnl = 200.0),
                createTrade(volumeSpike = 3.0, pnl = -50.0),
                createTrade(volumeSpike = 3.5, pnl = 150.0)
            )
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(trades)
            whenever(properties.volumeSpikeRatio).thenReturn(2.0)

            val result = tools.backtestParameterChange("volumeSpikeRatio", 4.0, 7)

            // 4.0 이상은 0건이므로 대폭 감소
            assertContains(result, "대폭 감소 예상")
        }
    }

    @Nested
    @DisplayName("청산 사유 분석")
    inner class ExitPatternTest {

        @Test
        @DisplayName("청산 사유별 통계 제공")
        fun providesExitReasonStats() {
            val trades = listOf(
                createTrade(exitReason = "TAKE_PROFIT", pnl = 300.0),
                createTrade(exitReason = "TAKE_PROFIT", pnl = 250.0),
                createTrade(exitReason = "STOP_LOSS", pnl = -150.0),
                createTrade(exitReason = "TIMEOUT", pnl = 10.0),
                createTrade(exitReason = "TIMEOUT", pnl = -20.0),
                createTrade(exitReason = "TIMEOUT", pnl = 5.0)
            )
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(trades)

            val result = tools.analyzeExitPatterns(7)

            assertContains(result, "TAKE_PROFIT (2건)")
            assertContains(result, "STOP_LOSS (1건)")
            assertContains(result, "TIMEOUT (3건)")
        }

        @Test
        @DisplayName("TIMEOUT 비율 30% 초과 시 인사이트 제공")
        fun providesInsightWhenTimeoutRatioHigh() {
            val trades = listOf(
                createTrade(exitReason = "TAKE_PROFIT", pnl = 100.0),
                createTrade(exitReason = "TIMEOUT", pnl = 10.0),
                createTrade(exitReason = "TIMEOUT", pnl = -20.0),
                createTrade(exitReason = "TIMEOUT", pnl = 5.0)
            )
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(trades)

            val result = tools.analyzeExitPatterns(7)

            // TIMEOUT 75% > 30%
            assertContains(result, "TIMEOUT 비율이")
            assertContains(result, "진입 조건 강화")
        }

        @Test
        @DisplayName("STOP_LOSS 비율 40% 초과 시 인사이트 제공")
        fun providesInsightWhenStopLossRatioHigh() {
            val trades = listOf(
                createTrade(exitReason = "STOP_LOSS", pnl = -100.0),
                createTrade(exitReason = "STOP_LOSS", pnl = -150.0),
                createTrade(exitReason = "STOP_LOSS", pnl = -80.0),
                createTrade(exitReason = "TAKE_PROFIT", pnl = 200.0)
            )
            whenever(tradeRepository.findByCreatedAtAfter(any())).thenReturn(trades)

            val result = tools.analyzeExitPatterns(7)

            // STOP_LOSS 75% > 40%
            assertContains(result, "STOP_LOSS 비율이")
            assertContains(result, "손절 조건 검토")
        }
    }

    @Nested
    @DisplayName("파라미터 변경")
    inner class ParameterChangeTest {

        @Test
        @DisplayName("허용되지 않은 파라미터는 변경하지 않는다")
        fun rejectsUnknownParameter() {
            val result = tools.suggestParameterChange("unknownParam", 1.0, "test")

            assertContains(result, "허용되지 않은 파라미터")
        }

        @Test
        @DisplayName("현재값과 동일하면 변경하지 않는다")
        fun skipsWhenValueIsSame() {
            whenever(properties.takeProfitPercent).thenReturn(3.0)

            val result = tools.suggestParameterChange("takeProfitPercent", 3.0, "same value")

            assertContains(result, "동일하여 변경하지 않았습니다")
        }
    }

    private fun createTrade(
        market: String = "KRW-TEST",
        volumeSpike: Double = 3.0,
        priceSpike: Double = 1.5,
        imbalance: Double = 0.3,
        rsi: Double = 55.0,
        pnl: Double = 100.0,
        exitReason: String = "TAKE_PROFIT"
    ): MemeScalperTradeEntity {
        return MemeScalperTradeEntity(
            id = null,
            market = market,
            entryPrice = 1000.0,
            exitPrice = 1000.0 + pnl / 10,
            quantity = 10.0,
            entryTime = Instant.now().minusSeconds(300),
            exitTime = Instant.now(),
            exitReason = exitReason,
            pnlAmount = pnl,
            pnlPercent = pnl / 100,
            entryVolumeSpikeRatio = volumeSpike,
            entryPriceSpikePercent = priceSpike,
            entryImbalance = imbalance,
            entryRsi = rsi,
            entryMacdSignal = "BULLISH",
            entrySpread = 0.1,
            status = "CLOSED"
        )
    }
}
