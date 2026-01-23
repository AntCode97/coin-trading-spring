package com.ant.cointrading.engine

import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 글로벌 포지션 관리자
 *
 * 문제: 각 엔진이 서로 다른 DB 테이블을 사용하여 포지션 충돌 가능성
 * - TradingEngine → TradeEntity
 * - VolumeSurgeEngine → VolumeSurgeTradeEntity
 * - MemeScalperEngine → MemeScalperTradeEntity
 *
 * 해결: 모든 엔진의 포지션 상태를 중앙 집중 관리
 */
@Component
class GlobalPositionManager(
    private val tradeRepository: TradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository,
    private val memeScalperRepository: MemeScalperTradeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 캐시 (마켓별 포지션 존재 여부)
    private val positionCache = ConcurrentHashMap<String, Set<String>>()
    private var lastCacheUpdate = 0L
    private val CACHE_TTL_MS = 5000L  // 5초 캐시

    /**
     * 마켓에 열린 포지션이 있는지 확인 (모든 엔진 통합)
     *
     * @param market 마켓 코드 (예: KRW-BTC, BTC_KRW)
     * @return 해당 마켓에 열린 포지션이 있으면 true
     */
    fun hasOpenPosition(market: String): Boolean {
        val normalizedMarket = normalizeMarket(market)

        // 캐시 확인
        if (System.currentTimeMillis() - lastCacheUpdate < CACHE_TTL_MS) {
            return positionCache[normalizedMarket]?.isNotEmpty() == true
        }

        // TradingEngine: 마지막 BUY가 있는데 매칭되는 SELL이 없으면 포지션으로 간주
        val hasInTrades = tradeRepository.findLastBuyByMarket(normalizedMarket) != null

        val hasInVolumeSurge = volumeSurgeRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()

        val hasInMemeScalper = memeScalperRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()

        val hasPosition = hasInTrades || hasInVolumeSurge || hasInMemeScalper

        // 캐시 업데이트
        updateCache(normalizedMarket, hasInTrades, hasInVolumeSurge, hasInMemeScalper)

        if (hasPosition) {
            log.debug("[$market] 열린 포지션 감지: Trades=$hasInTrades, VolumeSurge=$hasInVolumeSurge, MemeScalper=$hasInMemeScalper")
        }

        return hasPosition
    }

    /**
     * 마켓별 열린 포지션 개수 (모든 엔진 합산)
     */
    fun getOpenPositionCount(market: String): Int {
        val normalizedMarket = normalizeMarket(market)

        val countInVolumeSurge = volumeSurgeRepository.countByMarketAndStatus(normalizedMarket, "OPEN").toInt()
        val countInMemeScalper = memeScalperRepository.countByMarketAndStatus(normalizedMarket, "OPEN").toInt()

        // TradingEngine은 포지션당 하나만 가정 (복수 포지션 없음)
        val hasInTrades = tradeRepository.findLastBuyByMarket(normalizedMarket) != null

        return countInVolumeSurge + countInMemeScalper + if (hasInTrades) 1 else 0
    }

    /**
     * 전체 열린 포지션 수 (모든 마켓, 모든 엔진 합산)
     */
    fun getTotalOpenPositions(): Int {
        val volumeSurgeCount = volumeSurgeRepository.countByStatus("OPEN").toInt()
        val memeScalperCount = memeScalperRepository.countByStatus("OPEN")

        // TradingEngine은 BUY 포지션 수
        val tradesCount = tradeRepository.findLastBuyByMarket("KRW-BTC")?.let { 1 } ?: 0
        val tradesCount2 = tradeRepository.findLastBuyByMarket("KRW-ETH")?.let { 1 } ?: 0
        val tradesCount3 = tradeRepository.findLastBuyByMarket("KRW-XRP")?.let { 1 } ?: 0
        val tradesCount4 = tradeRepository.findLastBuyByMarket("KRW-SOL")?.let { 1 } ?: 0

        return volumeSurgeCount + memeScalperCount + tradesCount + tradesCount2 + tradesCount3 + tradesCount4
    }

    /**
     * 포지션 캐시 무효화 (포지션 진입/청산 시 호출)
     */
    fun invalidateCache(market: String? = null) {
        if (market != null) {
            positionCache.remove(normalizeMarket(market))
        } else {
            positionCache.clear()
        }
        lastCacheUpdate = 0L
        log.debug("포지션 캐시 무효화: ${market ?: "ALL"}")
    }

    /**
     * 특정 엔진의 포지션만 확인
     */
    fun hasOpenPositionInEngine(market: String, engine: EngineType): Boolean {
        val normalizedMarket = normalizeMarket(market)
        return when (engine) {
            EngineType.TRADING -> tradeRepository.findLastBuyByMarket(normalizedMarket) != null
            EngineType.VOLUME_SURGE -> volumeSurgeRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()
            EngineType.MEME_SCALPER -> memeScalperRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()
        }
    }

    /**
     * 포지션 상태 요약 (모니터링용)
     */
    fun getPositionSummary(): Map<String, Any> {
        val volumeSurgePositions = volumeSurgeRepository.findByStatus("OPEN")
        val memeScalperPositions = memeScalperRepository.findByStatus("OPEN")

        // TradingEngine 포지션 (모든 마켓의 마지막 BUY)
        val tradesPositions = mutableListOf<String>()
        listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL").forEach { market ->
            if (tradeRepository.findLastBuyByMarket(market) != null) {
                tradesPositions.add(market)
            }
        }

        val byMarket = mutableMapOf<String, MutableList<String>>()

        volumeSurgePositions.forEach {
            byMarket.getOrPut(it.market) { mutableListOf() }.add("VOLUME_SURGE")
        }
        memeScalperPositions.forEach {
            byMarket.getOrPut(it.market) { mutableListOf() }.add("MEME_SCALPER")
        }
        tradesPositions.forEach {
            byMarket.getOrPut(it) { mutableListOf() }.add("TRADING")
        }

        return mapOf(
            "totalOpenPositions" to getTotalOpenPositions(),
            "byMarket" to byMarket,
            "volumeSurgeCount" to volumeSurgePositions.size,
            "memeScalperCount" to memeScalperPositions.size,
            "tradingCount" to tradesPositions.size
        )
    }

    private fun updateCache(market: String, hasInTrades: Boolean, hasInVolumeSurge: Boolean, hasInMemeScalper: Boolean) {
        val engines = mutableSetOf<String>()
        if (hasInTrades) engines.add("TRADING")
        if (hasInVolumeSurge) engines.add("VOLUME_SURGE")
        if (hasInMemeScalper) engines.add("MEME_SCALPER")

        positionCache[market] = engines
        lastCacheUpdate = System.currentTimeMillis()
    }

    private fun normalizeMarket(market: String): String {
        // KRW-BTC, BTC_KRW, KRW_BTC 등 다양한 형식을 KRW-BTC로 통일
        return when {
            market.contains("-") -> market
            market.contains("_") -> {
                val parts = market.split("_")
                if (parts.size == 2) "${parts[1]}-${parts[0]}" else market
            }
            else -> market
        }
    }

    /**
     * 마켓명 정규화 (테스트용 public)
     */
    fun normalizeMarket(market: String): String = normalizeMarket(market)
}

/**
 * 트레이딩 엔진 종류
 */
enum class EngineType {
    TRADING,      // 메인 트레이딩 엔진 (DCA, Grid, MeanReversion)
    VOLUME_SURGE, // Volume Surge 엔진
    MEME_SCALPER  // Meme Scalper 엔진
}
