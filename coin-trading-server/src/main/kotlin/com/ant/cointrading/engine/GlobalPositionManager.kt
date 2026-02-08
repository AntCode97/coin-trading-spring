package com.ant.cointrading.engine

import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 글로벌 포지션 관리자
 *
 * 문제: 각 엔진이 서로 다른 DB 테이블을 사용하여 포지션 충돌 가능성
 * - TradingEngine → TradeEntity
 * - VolumeSurgeEngine → VolumeSurgeTradeEntity
 * - MemeScalperEngine → MemeScalperTradeEntity
 *
 * 해결: 모든 엔진의 포지션 상태를 중앙 집중 관리
 *        진입 락 (tryAcquirePosition)으로 경합 조건 해결
 *
 * 동시성: ReentrantLock으로 원자성 보장
 */
@Component
class GlobalPositionManager(
    private val tradeRepository: TradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository,
    private val memeScalperRepository: MemeScalperTradeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // [버그 수정] 하드코딩된 마켓 목록 상수로 추출 (중복 제거)
    companion object {
        val TRADING_ENGINE_MARKETS = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL")
    }

    // 동시성 제어를 위한 락
    private val lock = ReentrantLock()

    // 포지션 점유 추적 (엔진별 마켓 점유)
    private val positionOwners = ConcurrentHashMap<String, String>()  // market -> engineName

    // 캐시 (마켓별 포지션 존재 여부)
    private val positionCache = ConcurrentHashMap<String, Set<String>>()
    private var lastCacheUpdate = 0L
    private val CACHE_TTL_MS = 5000L  // 5초 캐시

    /**
     * 포지션 진입 시도 (원자적)
     *
     * 여러 엔진이 동시에 진입 시도 시도해도 단 하나만 성공
     * ReentrantLock의 tryLock()으로 락 획득 시도
     *
     * @param market 마켓 코드
     * @param engineName 진입 시도 엔진 이름
     * @return 진입 성공 시 true, 이미 포지션 있거나 락 획득 실패 시 false
     */
    fun tryAcquirePosition(market: String, engineName: String): Boolean {
        val normalizedMarket = normalizeMarket(market)

        // 락 획득 시도 (비블로킹, 즉시 false 반환)
        if (!lock.tryLock()) {
            log.debug("[$market] 포지션 진입 시도 실패: 락 획득 중 (엔진: $engineName)")
            return false
        }

        try {
            // 이미 다른 엔진이 점유 중인지 확인
            val currentOwner = positionOwners[normalizedMarket]
            if (currentOwner != null) {
                log.debug("[$market] 포지션 진입 실패: 이미 $currentOwner 엔진이 점유 중")
                return false
            }

            // DB에 포지션 존재 확인
            val hasInTrades = tradeRepository.findLastBuyByMarketAndSimulated(normalizedMarket, false) != null
            val hasInVolumeSurge = volumeSurgeRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()
            val hasInMemeScalper = memeScalperRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()

            if (hasInTrades || hasInVolumeSurge || hasInMemeScalper) {
                log.debug("[$market] 포지션 진입 실패: DB에 이미 포지션 존재 (Trades=$hasInTrades, VolumeSurge=$hasInVolumeSurge, MemeScalper=$hasInMemeScalper)")
                return false
            }

            // 포지션 점유 등록
            positionOwners[normalizedMarket] = engineName
            log.info("[$market] 포지션 진입 성공: 엔진=$engineName")

            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 포지션 해제 (진입 엔진에서 호출)
     *
     * @param market 마켓 코드
     * @param engineName 해제 요청 엔진 이름 (검증용)
     */
    fun releasePosition(market: String, engineName: String) {
        val normalizedMarket = normalizeMarket(market)

        lock.lock()
        try {
            val currentOwner = positionOwners[normalizedMarket]
            if (currentOwner == engineName) {
                positionOwners.remove(normalizedMarket)
                invalidateCache(normalizedMarket)
                log.info("[$market] 포지션 해제: 엔진=$engineName")
            } else {
                log.warn("[$market] 포지션 해제 실패: 현재 owner=$currentOwner, 요청=$engineName")
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 마켓에 열린 포지션이 있는지 확인 (모든 엔진 통합)
     *
     * ReentrantLock으로 동시성 제어: 여러 엔진이 동시에 호출해도 안전
     *
     * @param market 마켓 코드 (예: KRW-BTC, BTC_KRW)
     * @return 해당 마켓에 열린 포지션이 있으면 true
     */
    fun hasOpenPosition(market: String): Boolean {
        lock.lock()
        try {
            val normalizedMarket = normalizeMarket(market)

            // 캐시 확인
            if (System.currentTimeMillis() - lastCacheUpdate < CACHE_TTL_MS) {
                return positionCache[normalizedMarket]?.isNotEmpty() == true
            }

            // TradingEngine: 마지막 BUY가 있는데 매칭되는 SELL이 없으면 포지션으로 간주
            val hasInTrades = tradeRepository.findLastBuyByMarketAndSimulated(normalizedMarket, false) != null

            val hasInVolumeSurge = volumeSurgeRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()

            val hasInMemeScalper = memeScalperRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()

            val hasPosition = hasInTrades || hasInVolumeSurge || hasInMemeScalper

            // 캐시 업데이트
            updateCache(normalizedMarket, hasInTrades, hasInVolumeSurge, hasInMemeScalper)

            if (hasPosition) {
                log.debug("[$market] 열린 포지션 감지: Trades=$hasInTrades, VolumeSurge=$hasInVolumeSurge, MemeScalper=$hasInMemeScalper")
            }

            return hasPosition
        } finally {
            lock.unlock()
        }
    }

    /**
     * 마켓별 열린 포지션 개수 (모든 엔진 합산)
     */
    fun getOpenPositionCount(market: String): Int {
        val normalizedMarket = normalizeMarket(market)

        val countInVolumeSurge = volumeSurgeRepository.countByMarketAndStatus(normalizedMarket, "OPEN").toInt()
        val countInMemeScalper = memeScalperRepository.countByMarketAndStatus(normalizedMarket, "OPEN").toInt()

        // TradingEngine은 포지션당 하나만 가정 (복수 포지션 없음)
        val hasInTrades = tradeRepository.findLastBuyByMarketAndSimulated(normalizedMarket, false) != null

        return countInVolumeSurge + countInMemeScalper + if (hasInTrades) 1 else 0
    }

    /**
     * 전체 열린 포지션 수 (모든 마켓, 모든 엔진 합산)
     * [버그 수정] 하드코딩된 마켓 목록을 상수로 변경 (중복 제거)
     */
    fun getTotalOpenPositions(): Int {
        val volumeSurgeCount = volumeSurgeRepository.countByStatus("OPEN").toInt()
        val memeScalperCount = memeScalperRepository.countByStatus("OPEN")

        // TradingEngine은 BUY 포지션 수
        val tradesCount = TRADING_ENGINE_MARKETS.count { market ->
            tradeRepository.findLastBuyByMarketAndSimulated(market, false) != null
        }

        return volumeSurgeCount + memeScalperCount + tradesCount
    }

    /**
     * 포지션 캐시 무효화 (포지션 진입/청산 시 호출)
     *
     * ReentrantLock으로 캐시 무효화 작업 원자성 보장
     */
    fun invalidateCache(market: String? = null) {
        lock.lock()
        try {
            if (market != null) {
                positionCache.remove(normalizeMarket(market))
            } else {
                positionCache.clear()
            }
            lastCacheUpdate = 0L
            log.debug("포지션 캐시 무효화: ${market ?: "ALL"}")
        } finally {
            lock.unlock()
        }
    }

    /**
     * 특정 엔진의 포지션만 확인
     */
    fun hasOpenPositionInEngine(market: String, engine: EngineType): Boolean {
        val normalizedMarket = normalizeMarket(market)
        return when (engine) {
            EngineType.TRADING -> tradeRepository.findLastBuyByMarketAndSimulated(normalizedMarket, false) != null
            EngineType.VOLUME_SURGE -> volumeSurgeRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()
            EngineType.MEME_SCALPER -> memeScalperRepository.findByMarketAndStatus(normalizedMarket, "OPEN").isNotEmpty()
        }
    }

    /**
     * 포지션 상태 요약 (모니터링용)
     * [버그 수정] 하드코딩된 마켓 목록을 상수로 변경 (중복 제거)
     */
    fun getPositionSummary(): Map<String, Any> {
        val volumeSurgePositions = volumeSurgeRepository.findByStatus("OPEN")
        val memeScalperPositions = memeScalperRepository.findByStatus("OPEN")

        // TradingEngine 포지션 (모든 마켓의 마지막 BUY)
        val tradesPositions = TRADING_ENGINE_MARKETS.filter { market ->
            tradeRepository.findLastBuyByMarketAndSimulated(market, false) != null
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

    private fun doNormalizeMarket(market: String): String {
        // KRW-BTC, BTC_KRW, KRW_BTC 등 다양한 형식을 KRW-BTC로 통일
        return PositionHelper.normalizeMarket(market)
    }

    /**
     * 마켓명 정규화 (테스트용 public)
     */
    fun normalizeMarket(market: String): String = doNormalizeMarket(market)
}

/**
 * 트레이딩 엔진 종류
 */
enum class EngineType {
    TRADING,      // 메인 트레이딩 엔진 (DCA, Grid, MeanReversion)
    VOLUME_SURGE, // Volume Surge 엔진
    MEME_SCALPER  // Meme Scalper 엔진
}
