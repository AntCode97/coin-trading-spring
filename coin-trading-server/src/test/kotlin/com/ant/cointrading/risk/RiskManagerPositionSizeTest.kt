package com.ant.cointrading.risk

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.repository.TradeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RiskManager Position Size Tests")
class RiskManagerPositionSizeTest {

    @Mock
    private lateinit var tradeRepository: TradeRepository

    private lateinit var riskManager: RiskManager
    private val market = "KRW-BTC"

    @BeforeEach
    fun setup() {
        riskManager = RiskManager(TradingProperties(), tradeRepository)
        whenever(tradeRepository.findByMarketAndSimulatedOrderByCreatedAtDesc(market, false))
            .thenReturn(emptyList())
    }

    @Test
    @DisplayName("가용 자본이 최소 주문 금액 미만이면 포지션 크기는 0이어야 한다")
    fun shouldReturnZeroWhenBalanceBelowMinOrder() {
        val size = riskManager.calculatePositionSize(
            market = market,
            currentBalance = BigDecimal("3000"),
            signalConfidence = 90.0
        )

        assertEquals(BigDecimal.ZERO, size)
    }

    @Test
    @DisplayName("계산된 포지션이 최소 주문 금액 미만이면 0으로 클램프해야 한다")
    fun shouldClampToZeroWhenCalculatedSizeIsBelowMinOrder() {
        val size = riskManager.calculatePositionSize(
            market = market,
            currentBalance = BigDecimal("10000"),
            signalConfidence = 1.0
        )

        assertEquals(BigDecimal.ZERO, size)
    }

    @Test
    @DisplayName("정상 조건에서는 최소 주문 금액 이상 포지션을 반환해야 한다")
    fun shouldReturnTradableSizeWhenConditionsAreValid() {
        val size = riskManager.calculatePositionSize(
            market = market,
            currentBalance = BigDecimal("1000000"),
            signalConfidence = 100.0
        )

        assertTrue(size >= BigDecimal("5100"), "Expected tradable size, actual=$size")
    }
}
