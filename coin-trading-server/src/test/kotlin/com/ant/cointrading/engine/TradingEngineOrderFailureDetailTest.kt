package com.ant.cointrading.engine

import com.ant.cointrading.order.OrderRejectionReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TradingEngine Order Failure Detail Mapping")
class TradingEngineOrderFailureDetailTest {

    @Test
    @DisplayName("주문 거부 사유별 상세 메시지를 고정 매핑한다")
    fun mapsRejectionReasonToReadableDetail() {
        val expectedDetails = mapOf(
            OrderRejectionReason.MARKET_CONDITION to "시장 상태 불량 - 스프레드/유동성 확인",
            OrderRejectionReason.API_ERROR to "API 장애 - 거래소 상태 확인",
            OrderRejectionReason.VERIFICATION_FAILED to "주문 상태 확인 실패 - 수동 확인 필요",
            OrderRejectionReason.NO_FILL to "체결 없음 - 유동성 부족 의심",
            OrderRejectionReason.EXCEPTION to "시스템 예외",
            OrderRejectionReason.CIRCUIT_BREAKER to "서킷 브레이커 발동",
            OrderRejectionReason.BELOW_MIN_ORDER_AMOUNT to "최소 주문 금액(5000원) 미달",
            OrderRejectionReason.MARKET_SUSPENDED to "거래 정지/상장 폐지 코인"
        )

        expectedDetails.forEach { (reason, expectedDetail) ->
            assertEquals(
                expectedDetail,
                TradingEngine.resolveOrderFailureDetail(reason, "fallback"),
                "reason=$reason"
            )
        }
    }

    @Test
    @DisplayName("거부 사유가 없으면 fallback 메시지를 반환한다")
    fun returnsFallbackWhenReasonIsNull() {
        assertEquals(
            "원본 메시지",
            TradingEngine.resolveOrderFailureDetail(null, "원본 메시지")
        )
    }
}
