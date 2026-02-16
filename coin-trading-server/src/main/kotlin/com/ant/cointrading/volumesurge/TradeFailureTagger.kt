package com.ant.cointrading.volumesurge

import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 청산 직후 호출 - 손실 거래에 실패 패턴을 자동 태깅
 *
 * 수익 거래: 패턴 연속 실패 카운터 리셋
 * 손실 거래: 패턴 분류 → DB 저장 → 연속 실패 기록
 */
@Component
class TradeFailureTagger(
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val patternTracker: PatternFailureTracker
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun tagIfFailed(trade: VolumeSurgeTradeEntity) {
        if ((trade.pnlPercent ?: 0.0) >= 0) {
            patternTracker.recordSuccess(trade)
            return
        }

        val pattern = FailurePattern.classify(trade)
        val tag = buildFailureTag(trade, pattern)

        trade.failurePattern = pattern.name
        trade.failureTag = tag
        tradeRepository.save(trade)

        patternTracker.recordFailure(trade.market, pattern)

        log.info("[TradeFailureTagger] {} #{} 태깅: {} (PnL: {}%)",
            trade.market, trade.id, pattern.label, trade.pnlPercent)
    }

    private fun buildFailureTag(trade: VolumeSurgeTradeEntity, pattern: FailurePattern): String {
        val holdMin = trade.exitTime?.let { Duration.between(trade.entryTime, it).toMinutes() } ?: -1
        return """{"pattern":"${pattern.name}","rsi":${trade.entryRsi},"macd":"${trade.entryMacdSignal}","bb":"${trade.entryBollingerPosition}","volRatio":${trade.entryVolumeRatio},"confluence":${trade.confluenceScore},"exitReason":"${trade.exitReason}","holdMin":$holdMin,"pnl":${trade.pnlPercent}}"""
    }
}
