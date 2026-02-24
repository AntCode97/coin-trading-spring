package com.ant.cointrading.controller

import com.ant.cointrading.guided.GuidedStartRequest
import com.ant.cointrading.guided.GuidedAdoptPositionRequest
import com.ant.cointrading.guided.GuidedTradingService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@DisplayName("GuidedTradingController")
class GuidedTradingControllerTest {

    private val guidedTradingService: GuidedTradingService = mock()
    private val controller = GuidedTradingController(guidedTradingService)

    @Test
    @DisplayName("startTrading은 서비스 입력 오류를 400으로 변환한다")
    fun startTradingMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.startAutoTrading(any()))
            .thenThrow(IllegalArgumentException("잘못된 주문"))

        val exception = assertThrows<ResponseStatusException> {
            controller.startTrading(
                GuidedStartRequest(
                    market = "KRW-BTC",
                    amountKrw = 10000,
                )
            )
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    @DisplayName("partialTakeProfit은 서비스 입력 오류를 400으로 변환한다")
    fun partialTakeProfitMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.partialTakeProfit("KRW-BTC", -1.0))
            .thenThrow(IllegalArgumentException("ratio 오류"))

        val exception = assertThrows<ResponseStatusException> {
            controller.partialTakeProfit("KRW-BTC", -1.0)
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    @DisplayName("adoptPosition은 서비스 입력 오류를 400으로 변환한다")
    fun adoptPositionMapsIllegalArgumentToBadRequest() {
        whenever(guidedTradingService.adoptExternalPosition(any()))
            .thenThrow(IllegalArgumentException("adopt 오류"))

        val exception = assertThrows<ResponseStatusException> {
            controller.adoptPosition(GuidedAdoptPositionRequest(market = "KRW-BTC"))
        }

        kotlin.test.assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }
}
