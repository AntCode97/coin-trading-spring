package com.ant.cointrading.api.binance

import org.springframework.stereotype.Component
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class BinanceFuturesAuth(
    private val properties: BinanceFuturesProperties
) {
    fun generateSignature(queryString: String): String {
        val secretKeyBytes = properties.apiSecret.toByteArray(Charsets.UTF_8)
        val secretKeySpec = SecretKeySpec(secretKeyBytes, "HmacSHA256")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(queryString.toByteArray(Charsets.UTF_8))

        return String.format("%064x", BigInteger(1, hash))
    }

    fun getHeaders(): Map<String, String> = mapOf(
        "X-MBX-APIKEY" to properties.apiKey,
        "X-MBX-TIMESTAMP" to System.currentTimeMillis().toString()
    )

    fun getHeadersWithSignature(queryString: String): Map<String, String> {
        val baseHeaders = getHeaders()
        val signature = generateSignature(queryString)
        return baseHeaders + ("X-MBX-SIGNATURE" to signature)
    }
}
