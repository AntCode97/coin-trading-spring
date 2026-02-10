package com.ant.cointrading.risk

import com.ant.cointrading.config.RiskThrottleProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.repository.TradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

data class RiskThrottleDecision(
    val multiplier: Double,
    val reason: String,
    val sampleSize: Int,
    val winRate: Double,
    val avgPnlPercent: Double,
    val enabled: Boolean,
    val cached: Boolean
)

@Component
class RiskThrottleService(
    private val tradeRepository: TradeRepository,
    private val tradingProperties: TradingProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CachedDecision(
        val decision: RiskThrottleDecision,
        val evaluatedAt: Instant
    )

    private val cache = ConcurrentHashMap<String, CachedDecision>()

    fun getDecision(
        market: String,
        strategy: String,
        forceRefresh: Boolean = false
    ): RiskThrottleDecision {
        val config = tradingProperties.riskThrottle
        if (!config.enabled) {
            return RiskThrottleDecision(
                multiplier = 1.0,
                reason = "리스크 스로틀 비활성화",
                sampleSize = 0,
                winRate = 0.0,
                avgPnlPercent = 0.0,
                enabled = false,
                cached = false
            )
        }

        val cacheKey = "${market.uppercase()}|${strategy.uppercase()}"
        val currentCached = cache[cacheKey]
        if (!forceRefresh && currentCached != null && isFresh(currentCached.evaluatedAt, config.cacheMinutes)) {
            return currentCached.decision.copy(cached = true)
        }

        val evaluated = evaluate(market = market, strategy = strategy, config = config)
        cache[cacheKey] = CachedDecision(
            decision = evaluated.copy(cached = false),
            evaluatedAt = Instant.now()
        )
        return evaluated
    }

    private fun evaluate(
        market: String,
        strategy: String,
        config: RiskThrottleProperties
    ): RiskThrottleDecision {
        val closedTrades = tradeRepository
            .findTop200ByMarketAndSimulatedOrderByCreatedAtDesc(market, false)
            .asSequence()
            .filter { it.side.equals("SELL", ignoreCase = true) }
            .filter { it.strategy.equals(strategy, ignoreCase = true) }
            .mapNotNull { it.pnlPercent }
            .take(config.lookbackTrades)
            .toList()

        val sampleSize = closedTrades.size
        if (sampleSize < config.minClosedTrades) {
            return RiskThrottleDecision(
                multiplier = 1.0,
                reason = "샘플 부족(${sampleSize}/${config.minClosedTrades}) - 기본 크기 유지",
                sampleSize = sampleSize,
                winRate = 0.0,
                avgPnlPercent = 0.0,
                enabled = true,
                cached = false
            )
        }

        val winCount = closedTrades.count { it > 0.0 }
        val winRate = winCount.toDouble() / sampleSize
        val avgPnlPercent = closedTrades.average()
        val weakMultiplier = config.weakMultiplier.coerceIn(0.2, 1.0)
        val criticalMultiplier = config.criticalMultiplier.coerceIn(0.1, weakMultiplier)

        val decision = when {
            winRate <= config.criticalWinRate || avgPnlPercent <= config.criticalAvgPnlPercent -> {
                RiskThrottleDecision(
                    multiplier = criticalMultiplier,
                    reason = "최근 성과 악화(강한 축소): win=${formatPercent(winRate * 100)}, avg=${formatPercent(avgPnlPercent)}",
                    sampleSize = sampleSize,
                    winRate = winRate,
                    avgPnlPercent = avgPnlPercent,
                    enabled = true,
                    cached = false
                )
            }
            winRate <= config.weakWinRate || avgPnlPercent <= config.weakAvgPnlPercent -> {
                RiskThrottleDecision(
                    multiplier = weakMultiplier,
                    reason = "최근 성과 둔화(완화 축소): win=${formatPercent(winRate * 100)}, avg=${formatPercent(avgPnlPercent)}",
                    sampleSize = sampleSize,
                    winRate = winRate,
                    avgPnlPercent = avgPnlPercent,
                    enabled = true,
                    cached = false
                )
            }
            else -> {
                RiskThrottleDecision(
                    multiplier = 1.0,
                    reason = "최근 성과 양호: win=${formatPercent(winRate * 100)}, avg=${formatPercent(avgPnlPercent)}",
                    sampleSize = sampleSize,
                    winRate = winRate,
                    avgPnlPercent = avgPnlPercent,
                    enabled = true,
                    cached = false
                )
            }
        }

        if (decision.multiplier < 1.0) {
            log.warn(
                "[{}][{}] 리스크 스로틀 적용: x{}, sample={}, reason={}",
                market,
                strategy,
                String.format("%.2f", decision.multiplier),
                sampleSize,
                decision.reason
            )
        }

        return decision
    }

    private fun isFresh(evaluatedAt: Instant, cacheMinutes: Long): Boolean {
        return ChronoUnit.MINUTES.between(evaluatedAt, Instant.now()) < cacheMinutes
    }

    private fun formatPercent(value: Double): String = String.format("%.2f%%", value)
}
