package com.ant.cointrading.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class DesktopAccessInterceptor(
    private val properties: DesktopAccessProperties
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!properties.enabled) {
            return true
        }

        val expectedToken = properties.token.trim()
        if (expectedToken.isEmpty()) {
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"success\":false,\"error\":\"Desktop access token is not configured\"}")
            return false
        }

        val headerName = properties.headerName.trim().ifEmpty { "X-Desktop-Token" }
        val incoming = request.getHeader(headerName)?.trim().orEmpty()

        if (incoming != expectedToken) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"success\":false,\"error\":\"Desktop token unauthorized\"}")
            return false
        }

        return true
    }
}
