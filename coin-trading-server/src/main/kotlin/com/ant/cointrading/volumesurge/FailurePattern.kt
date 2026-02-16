package com.ant.cointrading.volumesurge

import com.ant.cointrading.repository.VolumeSurgeTradeEntity
import java.time.Duration

/**
 * 실패 패턴 분류
 *
 * 청산된 손실 거래를 분석하여 실패 원인을 자동 분류한다.
 * 우선순위 기반: 첫 번째 매칭 패턴이 적용된다.
 */
enum class FailurePattern(val label: String, val description: String) {
    RSI_OVERBOUGHT("RSI 과매수", "진입 RSI >= 65에서 손실"),
    VOLUME_FAKEOUT("거래량 페이크", "거래량 급등 후 즉시 하락 (STOP_LOSS, 보유 <= 5분)"),
    MACD_AGAINST("MACD 역행", "MACD NEUTRAL/BEARISH 상태에서 진입 후 손실"),
    LOW_CONFLUENCE("낮은 컨플루언스", "컨플루언스 40 미만에서 진입 후 손실"),
    TIMEOUT_LOSS("타임아웃 손실", "60분 보유 후 손실 청산"),
    TRAILING_GIVEBACK("수익 반납", "트레일링 스탑이지만 최종 PnL이 마이너스"),
    WEAK_VOLUME("거래량 부족", "거래량비율 2.0 미만에서 진입 후 손실"),
    BOLLINGER_TOP("볼린저 상단", "볼린저 UPPER에서 진입 후 하락"),
    UNKNOWN("미분류", "분류 불가");

    companion object {
        /**
         * 청산된 트레이드 기반 패턴 우선순위 분류
         *
         * 가장 구체적인 패턴부터 매칭하여 첫 번째 결과를 반환한다.
         */
        fun classify(trade: VolumeSurgeTradeEntity): FailurePattern {
            return when {
                // 1. 거래량 페이크아웃 (5분 이내 STOP_LOSS)
                trade.exitReason == "STOP_LOSS" && holdingMinutes(trade) <= 5 -> VOLUME_FAKEOUT

                // 2. RSI 과매수 진입
                (trade.entryRsi ?: 0.0) >= 65.0 -> RSI_OVERBOUGHT

                // 3. MACD 역행 진입
                trade.entryMacdSignal in listOf("NEUTRAL", "BEARISH") -> MACD_AGAINST

                // 4. 볼린저 상단 진입
                trade.entryBollingerPosition == "UPPER" -> BOLLINGER_TOP

                // 5. 약한 거래량
                (trade.entryVolumeRatio ?: 0.0) < 2.0 -> WEAK_VOLUME

                // 6. 낮은 컨플루언스
                (trade.confluenceScore ?: 0) < 40 -> LOW_CONFLUENCE

                // 7. 타임아웃 손실
                trade.exitReason == "TIMEOUT" -> TIMEOUT_LOSS

                // 8. 수익 반납 (트레일링이지만 손실)
                trade.exitReason == "TRAILING_PROFIT" && (trade.pnlPercent ?: 0.0) < 0 -> TRAILING_GIVEBACK

                else -> UNKNOWN
            }
        }

        private fun holdingMinutes(trade: VolumeSurgeTradeEntity): Long {
            val exit = trade.exitTime ?: return Long.MAX_VALUE
            return Duration.between(trade.entryTime, exit).toMinutes()
        }
    }
}
