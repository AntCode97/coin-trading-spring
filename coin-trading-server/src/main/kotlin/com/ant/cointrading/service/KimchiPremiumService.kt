package com.ant.cointrading.service

import com.ant.cointrading.api.binance.BinancePublicApi
import com.ant.cointrading.api.binance.KimchiPremiumInfo
import com.ant.cointrading.api.binance.PremiumStatus
import com.ant.cointrading.api.bithumb.BithumbPublicApi
import com.ant.cointrading.api.exchangerate.ExchangeRateApi
import com.ant.cointrading.api.exchangerate.ExchangeRateCacheStatus
import com.ant.cointrading.notification.SlackNotifier
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 김치 프리미엄 모니터링 서비스
 *
 * 국내(빗썸)와 해외(바이낸스) 가격 차이를 모니터링하여
 * 시장 과열/패닉 상태를 감지한다.
 *
 * 프리미엄 계산식:
 * 김치 프리미엄(%) = (국내가격 - 해외가격×환율) / (해외가격×환율) × 100
 */
@Service
class KimchiPremiumService(
    private val bithumbPublicApi: BithumbPublicApi,
    private val binancePublicApi: BinancePublicApi,
    private val slackNotifier: SlackNotifier,
    private val exchangeRateApi: ExchangeRateApi
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 최근 프리미엄 캐시
    private val premiumCache = ConcurrentHashMap<String, KimchiPremiumInfo>()

    // 알림 발송 기록 (중복 방지)
    private val lastAlertStatus = ConcurrentHashMap<String, PremiumStatus>()

    // 주요 모니터링 대상 코인
    private val mainCoins = listOf("BTC", "ETH", "XRP", "SOL", "ADA", "DOGE")

    @PostConstruct
    fun init() {
        log.info("=== 김치 프리미엄 모니터링 시작 ===")
        // 초기 환율 조회
        val rate = exchangeRateApi.getUsdKrwRate()
        log.info("기준 환율: ${rate}원/USD (실시간 조회)")
        log.info("모니터링 대상: $mainCoins")
    }

    /**
     * 현재 환율 조회
     *
     * ExchangeRateApi를 통해 실시간 환율 조회.
     * 1시간 캐싱 적용되어 있어 자주 호출해도 성능 문제 없음.
     */
    fun getExchangeRate(): BigDecimal = exchangeRateApi.getUsdKrwRate()

    /**
     * 환율 캐시 상태 조회
     */
    fun getExchangeRateCacheStatus(): ExchangeRateCacheStatus = exchangeRateApi.getCacheStatus()

    /**
     * 환율 캐시 강제 갱신
     */
    fun refreshExchangeRate(): BigDecimal {
        exchangeRateApi.invalidateCache()
        return exchangeRateApi.getUsdKrwRate()
    }

    /**
     * 단일 코인 김치 프리미엄 계산
     */
    fun calculatePremium(symbol: String): KimchiPremiumInfo? {
        try {
            val bithumbMarket = "KRW-$symbol"
            val binanceSymbol = "${symbol}USDT"

            // 빗썸 현재가 (KRW)
            val bithumbTicker = bithumbPublicApi.getCurrentPrice(bithumbMarket)?.firstOrNull()
            val domesticPrice = bithumbTicker?.tradePrice ?: return null

            // 바이낸스 현재가 (USDT)
            val foreignPrice = binancePublicApi.getPrice(binanceSymbol) ?: return null

            // 현재 환율 조회 (캐싱됨)
            val currentExchangeRate = getExchangeRate()

            // 바이낸스 가격 KRW 환산
            val foreignPriceKrw = foreignPrice.multiply(currentExchangeRate)
                .setScale(0, RoundingMode.HALF_UP)

            // 프리미엄 계산
            val premiumAmount = domesticPrice.subtract(foreignPriceKrw)
            val premiumPercent = premiumAmount.divide(foreignPriceKrw, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)

            val premium = KimchiPremiumInfo(
                symbol = symbol,
                domesticPrice = domesticPrice,
                foreignPrice = foreignPrice,
                exchangeRate = currentExchangeRate,
                foreignPriceKrw = foreignPriceKrw,
                premiumAmount = premiumAmount,
                premiumPercent = premiumPercent
            )

            premiumCache[symbol] = premium
            return premium

        } catch (e: Exception) {
            log.error("[$symbol] 김치 프리미엄 계산 오류: ${e.message}")
            return null
        }
    }

    /**
     * 모든 주요 코인 프리미엄 조회
     */
    fun getAllPremiums(): List<KimchiPremiumInfo> {
        return mainCoins.mapNotNull { calculatePremium(it) }
    }

    /**
     * 캐시된 프리미엄 조회 (API 호출 없음)
     */
    fun getCachedPremiums(): Map<String, KimchiPremiumInfo> {
        return premiumCache.toMap()
    }

    /**
     * 1분마다 프리미엄 모니터링 및 알림
     */
    @Scheduled(fixedDelay = 60000)
    fun monitorPremiums() {
        mainCoins.forEach { symbol ->
            try {
                val premium = calculatePremium(symbol) ?: return@forEach

                // 상태 변화 감지 및 알림
                val lastStatus = lastAlertStatus[symbol]
                val currentStatus = premium.status

                // 상태가 변경되었고, 극단적인 상태인 경우 알림
                if (lastStatus != currentStatus) {
                    when (currentStatus) {
                        PremiumStatus.EXTREME_HIGH -> {
                            sendPremiumAlert(premium, "극도의 과열 주의")
                        }
                        PremiumStatus.EXTREME_DISCOUNT -> {
                            sendPremiumAlert(premium, "극도의 할인 (매수 기회?)")
                        }
                        PremiumStatus.HIGH -> {
                            if (lastStatus == PremiumStatus.EXTREME_HIGH) {
                                sendPremiumAlert(premium, "과열 진정 중")
                            }
                        }
                        PremiumStatus.DISCOUNT -> {
                            if (lastStatus == PremiumStatus.EXTREME_DISCOUNT) {
                                sendPremiumAlert(premium, "할인 정상화 중")
                            }
                        }
                        else -> {}
                    }
                    lastAlertStatus[symbol] = currentStatus
                }

            } catch (e: Exception) {
                log.error("[$symbol] 프리미엄 모니터링 오류: ${e.message}")
            }
        }
    }

    /**
     * 프리미엄 알림 발송
     */
    private fun sendPremiumAlert(premium: KimchiPremiumInfo, message: String) {
        val emoji = if (premium.premiumPercent >= BigDecimal.ZERO) "🔺" else "🔻"
        val text = """
            $emoji 김치 프리미엄 알림: $message

            코인: ${premium.symbol}
            프리미엄: ${premium.premiumPercent}%
            상태: ${premium.status.description}

            국내가(빗썸): ${formatKrw(premium.domesticPrice)}원
            해외가(바이낸스): ${premium.foreignPrice} USDT
            해외가(환산): ${formatKrw(premium.foreignPriceKrw)}원

            환율: ${getExchangeRate()}원/USD
        """.trimIndent()

        slackNotifier.sendSystemNotification("김치 프리미엄", text)
        log.info("[${premium.symbol}] 김치 프리미엄 알림: $message (${premium.premiumPercent}%)")
    }

    /**
     * 평균 프리미엄 계산
     */
    fun getAveragePremium(): BigDecimal {
        val premiums = getCachedPremiums().values
        if (premiums.isEmpty()) return BigDecimal.ZERO

        return premiums
            .map { it.premiumPercent }
            .reduce { acc, p -> acc.add(p) }
            .divide(BigDecimal(premiums.size), 2, RoundingMode.HALF_UP)
    }

    private fun formatKrw(amount: BigDecimal): String {
        return String.format("%,.0f", amount)
    }
}
