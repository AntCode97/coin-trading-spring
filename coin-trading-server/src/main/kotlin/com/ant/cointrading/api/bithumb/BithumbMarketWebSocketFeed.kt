package com.ant.cointrading.api.bithumb

import com.ant.cointrading.config.BithumbProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class RealtimeMarketPulse(
    val market: String,
    val currentPrice: Double,
    val priceSpike10sPercent: Double,
    val tradeNotional10sKrw: Double,
    val tradeNotionalPrev60sKrw: Double,
    val notionalSpikeRatio: Double,
    val bidImbalance: Double,
    val spreadPercent: Double,
    val signedChangeRatePercent: Double,
    val updatedAt: Instant,
    val compositeScore: Double
)

/**
 * 빗썸 Public WebSocket 피드 (KRW 전체 마켓)
 *
 * - ticker/trade/orderbook 채널 구독
 * - 마켓별 초단기 모멘텀 지표를 메모리에 유지
 * - Meme Scalper/Volume Surge에서 후보 추출용으로 사용
 */
@Component
class BithumbMarketWebSocketFeed(
    private val bithumbPublicApi: BithumbPublicApi,
    private val objectMapper: ObjectMapper,
    bithumbProperties: BithumbProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val wsConfig = bithumbProperties.websocket

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val subscribed = AtomicBoolean(false)
    private val lastMessageAtMs = AtomicLong(0)
    private val trackedMarketCount = AtomicLong(0)

    private val states = ConcurrentHashMap<String, MarketRealtimeState>()
    private val channels = listOf("ticker", "trade", "orderbook")

    companion object {
        private const val PRICE_WINDOW_MS = 10_000L
        private const val BASELINE_WINDOW_MS = 70_000L   // 최근 10초 + 이전 60초
        private const val TRADE_RETENTION_MS = 180_000L  // 메모리 버퍼 (3분)
    }

    @PostConstruct
    fun init() {
        if (!wsConfig.enabled) {
            log.info("Bithumb WebSocket 피드 비활성화")
            return
        }
        connectAsync("startup")
    }

    @PreDestroy
    fun shutdown() {
        val socket = webSocketRef.getAndSet(null) ?: return
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join() }
            .onFailure { socket.abort() }
    }

    @Scheduled(fixedDelayString = "\${bithumb.websocket.reconnect-delay-ms:5000}")
    fun ensureConnected() {
        if (!wsConfig.enabled) return
        if (isHealthy()) return

        val staleMs = System.currentTimeMillis() - lastMessageAtMs.get()
        if (connected.get() || subscribed.get()) {
            log.warn(
                "Bithumb WS 상태 비정상 - 재연결 수행 (connected={}, subscribed={}, stale={}ms)",
                connected.get(),
                subscribed.get(),
                staleMs
            )
            webSocketRef.getAndSet(null)?.abort()
            connected.set(false)
            subscribed.set(false)
        }

        connectAsync("health-check")
    }

    fun isReady(): Boolean = wsConfig.enabled && isHealthy()

    fun latestPrice(market: String): Double? {
        if (!wsConfig.enabled) return null
        return states[market.uppercase()]?.latestPrice()
    }

    fun topPulses(
        limit: Int,
        minNotional10sKrw: Double,
        minPriceSpikePercent: Double,
        minNotionalSpikeRatio: Double,
        minBidImbalance: Double,
        maxSpreadPercent: Double
    ): List<RealtimeMarketPulse> {
        if (!isReady()) return emptyList()

        val nowMs = System.currentTimeMillis()
        val safeLimit = limit.coerceAtLeast(1)

        return states.values.asSequence()
            .mapNotNull { it.toPulse(nowMs) }
            .filter { nowMs - it.updatedAt.toEpochMilli() <= wsConfig.staleThresholdMs }
            .filter { it.tradeNotional10sKrw >= minNotional10sKrw }
            .filter { it.priceSpike10sPercent >= minPriceSpikePercent }
            .filter { it.notionalSpikeRatio >= minNotionalSpikeRatio }
            .filter { it.bidImbalance >= minBidImbalance }
            .filter { it.spreadPercent <= maxSpreadPercent }
            .sortedByDescending { it.compositeScore }
            .take(safeLimit)
            .toList()
    }

    private fun isHealthy(): Boolean {
        if (!connected.get() || !subscribed.get()) return false
        val lastMessageAt = lastMessageAtMs.get()
        if (lastMessageAt <= 0) return false
        return System.currentTimeMillis() - lastMessageAt <= wsConfig.staleThresholdMs
    }

    private fun connectAsync(reason: String) {
        if (!connecting.compareAndSet(false, true)) {
            return
        }

        Thread.startVirtualThread {
            try {
                if (!wsConfig.enabled) return@startVirtualThread

                httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsConfig.url), FeedWebSocketListener())
                    .join()

                log.info("Bithumb WS 연결 성공 (reason={})", reason)
            } catch (e: Exception) {
                connected.set(false)
                subscribed.set(false)
                log.warn("Bithumb WS 연결 실패 (reason={}): {}", reason, e.message)
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun subscribeAll(webSocket: WebSocket) {
        Thread.startVirtualThread {
            val markets = resolveKrwMarkets()
            if (markets.isEmpty()) {
                log.warn("Bithumb WS 구독 실패 - KRW 마켓 조회 결과 없음")
                return@startVirtualThread
            }

            val chunkSize = wsConfig.maxCodesPerSubscribe.coerceAtLeast(1)
            try {
                channels.forEach { channel ->
                    markets.chunked(chunkSize).forEachIndexed { idx, codes ->
                        val payload = buildSubscribePayload(channel, codes, idx)
                        webSocket.sendText(payload, true).join()
                    }
                }
                trackedMarketCount.set(markets.size.toLong())
                subscribed.set(true)
                log.info("Bithumb WS 구독 완료: {} markets, channels={}", markets.size, channels.joinToString(","))
            } catch (e: Exception) {
                subscribed.set(false)
                log.warn("Bithumb WS 구독 실패: {}", e.message)
            }
        }
    }

    private fun resolveKrwMarkets(): List<String> {
        return bithumbPublicApi.getMarketAll()
            ?.asSequence()
            ?.map { it.market.uppercase() }
            ?.filter { it.startsWith("KRW-") }
            ?.distinct()
            ?.toList()
            ?: emptyList()
    }

    private fun buildSubscribePayload(channel: String, codes: List<String>, chunkIndex: Int): String {
        val payload = listOf(
            mapOf("ticket" to "coin-trading-${channel}-${chunkIndex}-${UUID.randomUUID()}"),
            mapOf(
                "type" to channel,
                "codes" to codes,
                "isOnlyRealtime" to false
            ),
            mapOf("format" to "DEFAULT")
        )
        return objectMapper.writeValueAsString(payload)
    }

    private fun handleMessage(rawMessage: String) {
        val node = try {
            objectMapper.readTree(rawMessage)
        } catch (_: Exception) {
            return
        }

        if (!node.isObject) return

        val type = node.path("type").asText("")
        if (type.isBlank()) return

        when (type) {
            "ticker" -> handleTicker(node)
            "trade" -> handleTrade(node)
            "orderbook" -> handleOrderbook(node)
            else -> return
        }
        lastMessageAtMs.set(System.currentTimeMillis())
    }

    private fun handleTicker(node: JsonNode) {
        val market = extractMarket(node) ?: return
        val state = states.computeIfAbsent(market) { MarketRealtimeState(market) }
        state.updateTicker(
            price = extractDouble(node, "trade_price"),
            signedChangeRate = extractDouble(node, "signed_change_rate"),
            updatedAtMs = extractLong(node, "timestamp") ?: System.currentTimeMillis()
        )
    }

    private fun handleTrade(node: JsonNode) {
        val market = extractMarket(node) ?: return
        val price = extractDouble(node, "trade_price") ?: return
        val volume = extractDouble(node, "trade_volume") ?: return
        val timestampMs = extractLong(node, "trade_timestamp") ?: System.currentTimeMillis()
        val state = states.computeIfAbsent(market) { MarketRealtimeState(market) }
        state.recordTrade(price, volume, timestampMs)
    }

    private fun handleOrderbook(node: JsonNode) {
        val market = extractMarket(node) ?: return
        val state = states.computeIfAbsent(market) { MarketRealtimeState(market) }
        val units = node.get("orderbook_units")
        val firstUnit = if (units != null && units.isArray && units.size() > 0) units.get(0) else null

        state.updateOrderbook(
            totalBidSize = extractDouble(node, "total_bid_size"),
            totalAskSize = extractDouble(node, "total_ask_size"),
            bestBid = firstUnit?.let { extractDouble(it, "bid_price") },
            bestAsk = firstUnit?.let { extractDouble(it, "ask_price") },
            updatedAtMs = extractLong(node, "timestamp") ?: System.currentTimeMillis()
        )
    }

    private fun extractMarket(node: JsonNode): String? {
        val code = node.path("code").asText("").ifBlank { node.path("market").asText("") }
        if (code.isBlank()) return null
        return code.uppercase()
    }

    private fun extractDouble(node: JsonNode, field: String): Double? {
        val value = node.get(field) ?: return null
        return when {
            value.isNumber -> value.asDouble()
            value.isTextual -> value.asText().toDoubleOrNull()
            else -> null
        }
    }

    private fun extractLong(node: JsonNode, field: String): Long? {
        val value = node.get(field) ?: return null
        return when {
            value.isNumber -> value.asLong()
            value.isTextual -> value.asText().toLongOrNull()
            else -> null
        }
    }

    private inner class FeedWebSocketListener : WebSocket.Listener {
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocketRef.set(webSocket)
            connected.set(true)
            subscribed.set(false)
            lastMessageAtMs.set(System.currentTimeMillis())
            log.info("Bithumb WS 연결됨")
            subscribeAll(webSocket)
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            textBuffer.append(data)
            if (last) {
                handleMessage(textBuffer.toString())
                textBuffer.setLength(0)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            connected.set(false)
            subscribed.set(false)
            log.warn(
                "Bithumb WS 종료: code={}, reason={}, markets={}",
                statusCode,
                reason,
                trackedMarketCount.get()
            )
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            connected.set(false)
            subscribed.set(false)
            log.warn("Bithumb WS 오류: {}", error.message)
        }
    }

    private data class TradePoint(
        val timestampMs: Long,
        val price: Double,
        val notionalKrw: Double
    )

    private class MarketRealtimeState(private val market: String) {
        @Volatile
        private var currentPrice: Double = 0.0

        @Volatile
        private var signedChangeRatePercent: Double = 0.0

        @Volatile
        private var bidImbalance: Double = 0.0

        @Volatile
        private var spreadPercent: Double = Double.MAX_VALUE

        @Volatile
        private var lastUpdatedAtMs: Long = 0

        private val trades = ArrayDeque<TradePoint>()

        fun latestPrice(): Double? = currentPrice.takeIf { it > 0.0 }

        fun updateTicker(price: Double?, signedChangeRate: Double?, updatedAtMs: Long) {
            if (price != null && price > 0.0) {
                currentPrice = price
            }
            if (signedChangeRate != null) {
                signedChangeRatePercent = signedChangeRate * 100.0
            }
            if (updatedAtMs > 0) {
                lastUpdatedAtMs = maxOf(lastUpdatedAtMs, updatedAtMs)
            }
        }

        fun updateOrderbook(
            totalBidSize: Double?,
            totalAskSize: Double?,
            bestBid: Double?,
            bestAsk: Double?,
            updatedAtMs: Long
        ) {
            if (totalBidSize != null && totalAskSize != null) {
                val total = totalBidSize + totalAskSize
                if (total > 0.0) {
                    bidImbalance = (totalBidSize - totalAskSize) / total
                }
            }

            if (bestBid != null && bestAsk != null && bestBid > 0.0 && bestAsk >= bestBid) {
                spreadPercent = ((bestAsk - bestBid) / bestBid) * 100.0
            }

            if (updatedAtMs > 0) {
                lastUpdatedAtMs = maxOf(lastUpdatedAtMs, updatedAtMs)
            }
        }

        @Synchronized
        fun recordTrade(price: Double, volume: Double, timestampMs: Long) {
            if (price <= 0.0 || volume <= 0.0) return
            currentPrice = price
            lastUpdatedAtMs = maxOf(lastUpdatedAtMs, timestampMs)
            trades.addLast(TradePoint(timestampMs, price, price * volume))
            evictOldTrades(timestampMs)
        }

        @Synchronized
        fun toPulse(nowMs: Long): RealtimeMarketPulse? {
            val latest = currentPrice
            if (latest <= 0.0) return null

            evictOldTrades(nowMs)

            val recentThreshold = nowMs - PRICE_WINDOW_MS
            val baselineStart = nowMs - BASELINE_WINDOW_MS

            var notional10s = 0.0
            var notionalPrev60s = 0.0
            var price10sAgo = latest
            var hasRecentAnchor = false

            for (trade in trades) {
                when {
                    trade.timestampMs >= recentThreshold -> {
                        if (!hasRecentAnchor) {
                            price10sAgo = trade.price
                            hasRecentAnchor = true
                        }
                        notional10s += trade.notionalKrw
                    }
                    trade.timestampMs >= baselineStart -> {
                        notionalPrev60s += trade.notionalKrw
                    }
                }
            }

            if (!hasRecentAnchor && trades.isNotEmpty()) {
                price10sAgo = trades.first().price
            }

            val priceSpikePercent = if (price10sAgo > 0.0) {
                ((latest - price10sAgo) / price10sAgo) * 100.0
            } else {
                0.0
            }

            val baselinePer10s = notionalPrev60s / 6.0
            val spikeRatio = when {
                baselinePer10s > 0.0 -> notional10s / baselinePer10s
                notional10s > 0.0 -> 3.0
                else -> 0.0
            }

            val score = (notional10s / 1_000_000.0) *
                (1.0 + priceSpikePercent.coerceAtLeast(0.0) / 100.0) *
                (1.0 + spikeRatio.coerceAtLeast(0.0)) *
                (1.0 + bidImbalance.coerceAtLeast(0.0))

            val updatedMs = if (lastUpdatedAtMs > 0) lastUpdatedAtMs else nowMs
            return RealtimeMarketPulse(
                market = market,
                currentPrice = latest,
                priceSpike10sPercent = priceSpikePercent,
                tradeNotional10sKrw = notional10s,
                tradeNotionalPrev60sKrw = notionalPrev60s,
                notionalSpikeRatio = spikeRatio,
                bidImbalance = bidImbalance,
                spreadPercent = spreadPercent,
                signedChangeRatePercent = signedChangeRatePercent,
                updatedAt = Instant.ofEpochMilli(updatedMs),
                compositeScore = score
            )
        }

        private fun evictOldTrades(nowMs: Long) {
            val threshold = nowMs - TRADE_RETENTION_MS
            while (trades.isNotEmpty() && trades.first().timestampMs < threshold) {
                trades.removeFirst()
            }
        }
    }
}
