package com.ant.cointrading.service

import com.ant.cointrading.config.MemeScalperProperties
import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.config.VolumeSurgeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 전략별 거래 금액 서비스
 *
 * KeyValueService를 래핑하여 전략별 금액 조회/변경 인터페이스 제공.
 * DB값이 없으면 Properties 기본값을 fallback으로 사용.
 * 매 주문 시 getAmount()를 호출하므로 API로 변경한 금액이 즉시 반영된다.
 */
@Service
class TradingAmountService(
    private val keyValueService: KeyValueService,
    private val tradingProperties: TradingProperties,
    private val volumeSurgeProperties: VolumeSurgeProperties,
    private val memeScalperProperties: MemeScalperProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "trading.amount."
        const val MIN_ORDER_AMOUNT_KRW = 5100L

        val STRATEGY_CODES = mapOf(
            "dca" to "DCA (분할매수)",
            "volumesurge" to "거래량 급등",
            "memescalper" to "단타 매매",
        )
    }

    fun getAmount(strategyCode: String): BigDecimal {
        val key = KEY_PREFIX + strategyCode
        val stored = keyValueService.getLong(key, 0L)
        if (stored > 0) return BigDecimal.valueOf(stored)

        return getDefaultAmount(strategyCode)
    }

    fun setAmount(strategyCode: String, amountKrw: Long): Boolean {
        require(strategyCode in STRATEGY_CODES) { "지원하지 않는 전략: $strategyCode" }
        require(amountKrw >= MIN_ORDER_AMOUNT_KRW) {
            "최소 주문 금액은 ${MIN_ORDER_AMOUNT_KRW}원입니다 (입력: ${amountKrw}원)"
        }

        val key = KEY_PREFIX + strategyCode
        val success = keyValueService.set(key, amountKrw.toString(), "trading", "전략별 거래 금액")
        if (success) {
            log.info("전략 거래 금액 변경: $strategyCode = ${amountKrw}원")
        }
        return success
    }

    fun getAllAmounts(): List<StrategyAmount> {
        return STRATEGY_CODES.map { (code, label) ->
            StrategyAmount(
                strategyCode = code,
                label = label,
                amountKrw = getAmount(code).toLong(),
                defaultAmountKrw = getDefaultAmount(code).toLong(),
            )
        }
    }

    private fun getDefaultAmount(strategyCode: String): BigDecimal {
        return when (strategyCode) {
            "dca" -> tradingProperties.orderAmountKrw
            "volumesurge" -> BigDecimal.valueOf(volumeSurgeProperties.positionSizeKrw.toLong())
            "memescalper" -> BigDecimal.valueOf(memeScalperProperties.positionSizeKrw.toLong())
            else -> BigDecimal.valueOf(10000)
        }
    }
}

data class StrategyAmount(
    val strategyCode: String,
    val label: String,
    val amountKrw: Long,
    val defaultAmountKrw: Long,
)
