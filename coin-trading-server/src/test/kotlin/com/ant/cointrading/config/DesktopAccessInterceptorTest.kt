package com.ant.cointrading.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@DisplayName("DesktopAccessInterceptor")
class DesktopAccessInterceptorTest {

    @Test
    @DisplayName("desktop access disabled면 요청을 통과시킨다")
    fun allowsWhenDisabled() {
        val interceptor = DesktopAccessInterceptor(
            DesktopAccessProperties(enabled = false, token = "")
        )
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
    }

    @Test
    @DisplayName("토큰 미설정이면 503을 반환한다")
    fun blocksWhenTokenMissing() {
        val interceptor = DesktopAccessInterceptor(
            DesktopAccessProperties(enabled = true, token = "")
        )
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(503, response.status)
    }

    @Test
    @DisplayName("토큰 불일치면 401을 반환한다")
    fun blocksWhenTokenMismatch() {
        val interceptor = DesktopAccessInterceptor(
            DesktopAccessProperties(enabled = true, token = "secret-token")
        )
        val request = MockHttpServletRequest()
        request.addHeader("X-Desktop-Token", "wrong-token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(401, response.status)
    }

    @Test
    @DisplayName("토큰 일치면 요청을 통과시킨다")
    fun allowsWhenTokenMatches() {
        val interceptor = DesktopAccessInterceptor(
            DesktopAccessProperties(enabled = true, token = "secret-token")
        )
        val request = MockHttpServletRequest()
        request.addHeader("X-Desktop-Token", "secret-token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
    }
}
