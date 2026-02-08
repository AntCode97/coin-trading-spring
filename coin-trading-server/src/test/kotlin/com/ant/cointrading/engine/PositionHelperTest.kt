package com.ant.cointrading.engine

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("PositionHelper Market Format Tests")
class PositionHelperTest {

    @Test
    @DisplayName("normalizeMarket supports dash and underscore formats")
    fun normalizeMarketSupportsMultipleFormats() {
        assertEquals("KRW-BTC", PositionHelper.normalizeMarket("KRW-BTC"))
        assertEquals("KRW-BTC", PositionHelper.normalizeMarket("BTC_KRW"))
        assertEquals("KRW-BTC", PositionHelper.normalizeMarket("KRW_BTC"))
        assertEquals("KRW-BTC", PositionHelper.normalizeMarket(" krw-btc "))
    }

    @Test
    @DisplayName("extractCoinSymbol works for api and internal market formats")
    fun extractCoinSymbolSupportsMultipleFormats() {
        assertEquals("BTC", PositionHelper.extractCoinSymbol("KRW-BTC"))
        assertEquals("BTC", PositionHelper.extractCoinSymbol("BTC_KRW"))
        assertEquals("BTC", PositionHelper.extractCoinSymbol("KRW_BTC"))
    }

    @Test
    @DisplayName("convertToApiMarket always returns KRW-XXX format")
    fun convertToApiMarketReturnsBithumbFormat() {
        assertEquals("KRW-BTC", PositionHelper.convertToApiMarket("KRW-BTC"))
        assertEquals("KRW-BTC", PositionHelper.convertToApiMarket("BTC_KRW"))
        assertEquals("KRW-BTC", PositionHelper.convertToApiMarket("KRW_BTC"))
    }
}
