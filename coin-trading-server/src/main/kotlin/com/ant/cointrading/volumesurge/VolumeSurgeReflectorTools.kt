package com.ant.cointrading.volumesurge

import com.ant.cointrading.config.VolumeSurgeProperties
import com.ant.cointrading.repository.*
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.util.DateTimeUtils.SEOUL_ZONE
import com.ant.cointrading.util.DateTimeUtils.today
import com.ant.cointrading.util.DateTimeUtils.todayRange
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Volume Surge 회고용 LLM 도구
 */
@Component
class VolumeSurgeReflectorTools(
    private val alertRepository: VolumeSurgeAlertRepository,
    private val tradeRepository: VolumeSurgeTradeRepository,
    private val summaryRepository: VolumeSurgeDailySummaryRepository,
    private val keyValueService: KeyValueService,
    private val objectMapper: ObjectMapper,
    private val slackNotifier: SlackNotifier,
    private val volumeSurgeProperties: VolumeSurgeProperties,
    private val generalTradeRepository: TradeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 파라미터 접근자 (전략 패턴)
     */
    private interface ParamAccessor {
        fun get(): Double
        fun set(value: Double)
    }

    private val paramMap by lazy {
        mapOf(
            "minVolumeRatio" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.minVolumeRatio
                override fun set(value: Double) { volumeSurgeProperties.minVolumeRatio = value }
            },
            "maxRsi" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.maxRsi
                override fun set(value: Double) { volumeSurgeProperties.maxRsi = value }
            },
            "minConfluenceScore" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.minConfluenceScore.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.minConfluenceScore = value.toInt() }
            },
            "stopLossPercent" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.stopLossPercent
                override fun set(value: Double) { volumeSurgeProperties.stopLossPercent = value }
            },
            "takeProfitPercent" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.takeProfitPercent
                override fun set(value: Double) { volumeSurgeProperties.takeProfitPercent = value }
            },
            "trailingStopTrigger" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.trailingStopTrigger
                override fun set(value: Double) { volumeSurgeProperties.trailingStopTrigger = value }
            },
            "trailingStopOffset" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.trailingStopOffset
                override fun set(value: Double) { volumeSurgeProperties.trailingStopOffset = value }
            },
            "positionTimeoutMin" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.positionTimeoutMin.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.positionTimeoutMin = value.toInt() }
            },
            "cooldownMin" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.cooldownMin.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.cooldownMin = value.toInt() }
            },
            "maxConsecutiveLosses" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.maxConsecutiveLosses.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.maxConsecutiveLosses = value.toInt() }
            },
            "dailyMaxLossKrw" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.dailyMaxLossKrw.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.dailyMaxLossKrw = value.toInt() }
            },
            "positionSizeKrw" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.positionSizeKrw.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.positionSizeKrw = value.toInt() }
            },
            "alertFreshnessMin" to object : ParamAccessor {
                override fun get() = volumeSurgeProperties.alertFreshnessMin.toDouble()
                override fun set(value: Double) { volumeSurgeProperties.alertFreshnessMin = value.toInt() }
            }
        )
    }

    companion object {
        /** 변경 가능한 파라미터 목록과 KeyValue 키 매핑 */
        val ALLOWED_PARAMS = mapOf(
            "minVolumeRatio" to "volumesurge.minVolumeRatio",
            "maxRsi" to "volumesurge.maxRsi",
            "minConfluenceScore" to "volumesurge.minConfluenceScore",
            "stopLossPercent" to "volumesurge.stopLossPercent",
            "takeProfitPercent" to "volumesurge.takeProfitPercent",
            "trailingStopTrigger" to "volumesurge.trailingStopTrigger",
            "trailingStopOffset" to "volumesurge.trailingStopOffset",
            "positionTimeoutMin" to "volumesurge.positionTimeoutMin",
            "cooldownMin" to "volumesurge.cooldownMin",
            "maxConsecutiveLosses" to "volumesurge.maxConsecutiveLosses",
            "dailyMaxLossKrw" to "volumesurge.dailyMaxLossKrw",
            "positionSizeKrw" to "volumesurge.positionSizeKrw",
            "alertFreshnessMin" to "volumesurge.alertFreshnessMin"
        )
    }

    private data class BacktestFilterResult(
        val filteredIn: Int,
        val filteredOut: Int
    )

    private data class MarketPerformance(
        val market: String,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val totalPnl: Double,
        val avgRsi: Double,
        val avgVolumeRatio: Double,
        val avgConfluence: Double
    )

    private data class EntryPattern(
        val avgRsi: Double,
        val avgVolumeRatio: Double,
        val avgConfluence: Double,
        val commonExitReason: String?
    )

    private data class TimeBucketStats(
        val count: Int,
        val wins: Int,
        val winRate: Double,
        val totalPnl: Double
    )

    private data class ExitReasonStats(
        val count: Int,
        val avgPnl: Double
    )

    @Tool(description = "오늘의 Volume Surge 전략 통계를 조회합니다")
    fun getTodayStats(): String {
        log.info("[Tool] getTodayStats")

        val today = today()
        val (startOfDay, endOfDay) = todayRange()

        val totalAlerts = alertRepository.countByDetectedAtBetween(startOfDay, endOfDay)
        val approvedAlerts = alertRepository.countApprovedBetween(startOfDay, endOfDay)
        val totalPnl = tradeRepository.sumPnlBetween(startOfDay, endOfDay)
        val winCount = tradeRepository.countWinningBetween(startOfDay, endOfDay)
        val loseCount = tradeRepository.countLosingBetween(startOfDay, endOfDay)
        val avgHolding = tradeRepository.avgHoldingMinutesBetween(startOfDay, endOfDay)

        return """
            === 오늘 통계 ($today) ===
            총 경보: ${totalAlerts}건
            승인된 경보: ${approvedAlerts}건
            총 손익: ${String.format("%.0f", totalPnl)}원
            승리/패배: $winCount / $loseCount
            평균 보유시간: ${avgHolding?.let { String.format("%.1f", it) } ?: "N/A"}분
        """.trimIndent()
    }

    @Tool(description = "오늘의 트레이드 목록을 조회합니다")
    fun getTodayTrades(
        @ToolParam(description = "조회할 트레이드 수 (기본 20)") limit: Int = 20
    ): String {
        log.info("[Tool] getTodayTrades: limit=$limit")

        val today = today()
        val (startOfDay, endOfDay) = todayRange()

        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfDay, endOfDay)
            .take(limit)

        if (trades.isEmpty()) {
            return "오늘 트레이드가 없습니다."
        }

        return trades.joinToString("\n\n") { trade ->
            val result = if ((trade.pnlAmount ?: 0.0) > 0) "성공" else "실패"
            """
                [$result] ${trade.market} (ID: ${trade.id})
                진입: ${trade.entryPrice}원 → 청산: ${trade.exitPrice ?: "미청산"}원
                손익: ${trade.pnlAmount?.let { String.format("%.0f", it) } ?: "N/A"}원
                RSI: ${trade.entryRsi} | MACD: ${trade.entryMacdSignal}
                BB: ${trade.entryBollingerPosition} | 거래량: ${trade.entryVolumeRatio}
                컨플루언스: ${trade.confluenceScore} | 청산사유: ${trade.exitReason ?: "N/A"}
            """.trimIndent()
        }
    }

    @Tool(description = """
        오늘의 회고 결과를 저장합니다.
        반드시 분석 완료 후 호출하세요.
    """)
    fun saveReflection(
        @ToolParam(description = "회고 요약") summary: String,
        @ToolParam(description = "주요 발견 사항") findings: String,
        @ToolParam(description = "내일 주의점") tomorrowFocus: String
    ): String {
        log.info("[Tool] saveReflection")

        val today = today()

        // 기존 요약이 있으면 업데이트, 없으면 생성
        val existingSummary = summaryRepository.findByDate(today)
        val summaryEntity = existingSummary ?: VolumeSurgeDailySummaryEntity(date = today)

        summaryEntity.reflectionSummary = """
            === 회고 요약 ===
            $summary

            === 주요 발견 ===
            $findings

            === 내일 주의점 ===
            $tomorrowFocus
        """.trimIndent()

        summaryRepository.save(summaryEntity)

        return "회고가 저장되었습니다."
    }

    @Tool(description = """
        파라미터를 변경합니다.
        변경이 필요한 경우에만 호출하세요.
        실제 파라미터가 변경되고 Slack 알림이 발송됩니다.

        허용된 파라미터:
        - minVolumeRatio: 최소 거래량 비율 (기본 3.0)
        - maxRsi: 최대 RSI (기본 70.0)
        - minConfluenceScore: 최소 컨플루언스 점수 (기본 60)
        - stopLossPercent: 손절 퍼센트 (기본 -2.0)
        - takeProfitPercent: 익절 퍼센트 (기본 5.0)
        - trailingStopTrigger: 트레일링 스탑 시작 (기본 2.0)
        - trailingStopOffset: 트레일링 스탑 오프셋 (기본 1.0)
        - positionTimeoutMin: 포지션 타임아웃 분 (기본 30)
        - cooldownMin: 재진입 쿨다운 분 (기본 60)
        - maxConsecutiveLosses: 연속 손실 제한 (기본 3)
        - dailyMaxLossKrw: 일일 최대 손실 원 (기본 30000)
        - positionSizeKrw: 포지션 크기 원 (기본 10000)
        - alertFreshnessMin: 경보 신선도 분 (기본 5)
    """)
    fun suggestParameterChange(
        @ToolParam(description = "파라미터 이름 (예: minVolumeRatio, maxRsi, minConfluenceScore)") paramName: String,
        @ToolParam(description = "새로운 값") newValue: Double,
        @ToolParam(description = "변경 이유") reason: String
    ): String {
        log.info("[Tool] suggestParameterChange: $paramName -> $newValue")

        // 허용된 파라미터인지 검증
        val keyValueKey = ALLOWED_PARAMS[paramName]
        if (keyValueKey == null) {
            log.warn("허용되지 않은 파라미터: $paramName")
            return """
                오류: 허용되지 않은 파라미터입니다.
                파라미터명: $paramName

                허용된 파라미터 목록:
                ${ALLOWED_PARAMS.keys.joinToString(", ")}
            """.trimIndent()
        }

        // 현재 값 조회
        val currentValue = paramMap[paramName]?.get() ?: 0.0

        // 값이 동일하면 변경 불필요
        if (currentValue == newValue) {
            return "파라미터 $paramName 의 현재값($currentValue)과 새 값($newValue)이 동일하여 변경하지 않았습니다."
        }

        try {
            // 1. KeyValueService에 저장 (영속성 - 재시작 후에도 유지)
            keyValueService.set(
                key = keyValueKey,
                value = newValue.toString(),
                category = "volumesurge",
                description = "LLM 자동 변경: $reason"
            )

            // 2. VolumeSurgeProperties 필드 직접 변경 (즉시 반영)
            paramMap[paramName]?.set(newValue)

            // 3. DB 요약에 변경 기록 저장
            val today = today()
            val existingSummary = summaryRepository.findByDate(today)
                ?: VolumeSurgeDailySummaryEntity(date = today)

            val changeRecord = mapOf(
                "param" to paramName,
                "oldValue" to currentValue,
                "newValue" to newValue,
                "reason" to reason,
                "timestamp" to Instant.now().toString(),
                "applied" to true
            )

            val existingChanges = existingSummary.parameterChanges?.let {
                try {
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(it, List::class.java) as List<Map<String, Any>>
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            val updatedChanges = existingChanges + changeRecord
            existingSummary.parameterChanges = objectMapper.writeValueAsString(updatedChanges)
            summaryRepository.save(existingSummary)

            // 4. Slack 알림 발송
            slackNotifier.sendSystemNotification(
                "[자동] 파라미터 변경 완료",
                """
                    파라미터: $paramName
                    변경: $currentValue → $newValue
                    이유: $reason

                    KeyValue 키: $keyValueKey
                    즉시 반영됨 (재시작 후에도 유지)
                """.trimIndent()
            )

            log.info("파라미터 변경 완료: $paramName = $currentValue -> $newValue")

            return """
                파라미터 변경 완료:
                - $paramName: $currentValue → $newValue
                - 이유: $reason
                - KeyValue 저장: $keyValueKey
                - 즉시 반영됨
            """.trimIndent()

        } catch (e: Exception) {
            log.error("파라미터 변경 실패: $paramName, error: ${e.message}", e)

            slackNotifier.sendError(
                "VOLUME_SURGE",
                "파라미터 변경 실패: $paramName -> $newValue, 오류: ${e.message}"
            )

            return """
                오류: 파라미터 변경 실패
                - 파라미터: $paramName
                - 오류: ${e.message}
            """.trimIndent()
        }
    }

    @Tool(description = """
        시스템 개선 아이디어를 제안합니다.
        현재 시스템에 없지만 있으면 좋을 기능이나 개선점이 있으면 호출하세요.
        Slack 알림이 발송되어 개발자가 검토할 수 있습니다.
    """)
    fun suggestSystemImprovement(
        @ToolParam(description = "제안 제목 (간결하게)") title: String,
        @ToolParam(description = "상세 설명 (왜 필요한지, 어떤 효과가 있을지)") description: String,
        @ToolParam(description = "우선순위 (HIGH/MEDIUM/LOW)") priority: String,
        @ToolParam(description = "카테고리 (FEATURE/DATA/AUTOMATION/INTEGRATION)") category: String
    ): String {
        log.info("[Tool] suggestSystemImprovement: $title ($priority)")

        val today = today()

        // DB에 제안 기록 저장
        val existingSummary = summaryRepository.findByDate(today)
            ?: VolumeSurgeDailySummaryEntity(date = today)

        // 기존 반영 노트에 추가
        val existingNotes = existingSummary.reflectionSummary ?: ""
        existingSummary.reflectionSummary = """
            $existingNotes

            === 시스템 개선 제안 ===
            [$priority] $title
            카테고리: $category
            $description
        """.trimIndent()

        summaryRepository.save(existingSummary)

        // Slack 알림 발송 (개발자가 바로 확인)
        val priorityEmoji = when (priority.uppercase()) {
            "HIGH" -> "[긴급]"
            "MEDIUM" -> "[보통]"
            else -> "[참고]"
        }

        slackNotifier.sendSystemNotification(
            "$priorityEmoji 시스템 개선 제안",
            """
                제목: $title
                카테고리: $category
                우선순위: $priority

                $description

                ---
                LLM 회고 시스템이 자동 생성한 제안입니다.
            """.trimIndent()
        )

        return """
            시스템 개선 제안이 기록되고 Slack 알림이 발송되었습니다:
            - 제목: $title
            - 카테고리: $category
            - 우선순위: $priority
        """.trimIndent()
    }

    @Tool(description = """
        파라미터 변경 전 과거 데이터로 백테스트를 수행합니다.
        파라미터 변경이 과거에 적용되었다면 어떤 결과가 나왔을지 시뮬레이션합니다.
        suggestParameterChange 호출 전에 반드시 이 도구로 검증하세요.
    """)
    fun backtestParameterChange(
        @ToolParam(description = "파라미터 이름") paramName: String,
        @ToolParam(description = "새로운 값") newValue: Double,
        @ToolParam(description = "백테스트 기간 (일)") historicalDays: Int = 7
    ): String {
        log.info("[Tool] backtestParameterChange: $paramName=$newValue, days=$historicalDays")

        val startOfPeriod = Instant.now().minus(historicalDays.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        // 기간 내 트레이드 조회
        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "백테스트 기간($historicalDays 일) 내 트레이드가 없습니다."
        }

        val currentValue = paramMap[paramName]?.get() ?: 0.0
        val filterResult = simulateBacktestFilter(paramName, newValue, currentValue, trades)

        val winningTrades = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }
        val totalPnl = trades.sumOf { it.pnlAmount ?: 0.0 }
        val winRate = if (trades.isNotEmpty()) winningTrades.size.toDouble() / trades.size * 100 else 0.0

        val estimatedImpact = estimateTradeImpact(filterResult.filteredIn, trades.size)
        val recommendation = buildBacktestRecommendation(paramName, filterResult.filteredIn, trades.size)

        return """
            === 백테스트 결과 ($historicalDays 일) ===

            파라미터: $paramName
            현재값: $currentValue → 새 값: $newValue

            분석 기간 트레이드: ${trades.size}건
            승리: ${winningTrades.size}건 / 패배: ${losingTrades.size}건
            승률: ${String.format("%.1f", winRate)}%
            총 손익: ${String.format("%.0f", totalPnl)}원

            새 파라미터 적용 시:
            - 진입 가능 트레이드: ${filterResult.filteredIn} 건
            - 필터링된 트레이드: ${filterResult.filteredOut} 건
            - 예상 영향: $estimatedImpact

            권장사항: $recommendation
        """.trimIndent()
    }

    private fun simulateBacktestFilter(
        paramName: String,
        newValue: Double,
        currentValue: Double,
        trades: List<VolumeSurgeTradeEntity>
    ): BacktestFilterResult {
        return when (paramName) {
            "minVolumeRatio" -> {
                val passedNew = trades.count { (it.entryVolumeRatio ?: 0.0) >= newValue }
                BacktestFilterResult(passedNew, trades.size - passedNew)
            }
            "maxRsi" -> {
                val passedNew = trades.count { (it.entryRsi ?: 100.0) <= newValue }
                BacktestFilterResult(passedNew, trades.size - passedNew)
            }
            "minConfluenceScore" -> {
                val passedNew = trades.count { (it.confluenceScore ?: 0) >= newValue.toInt() }
                BacktestFilterResult(passedNew, trades.size - passedNew)
            }
            "stopLossPercent" -> {
                val stoppedEarlier = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent < 0 && pnlPercent > newValue && pnlPercent <= currentValue
                }
                val stoppedLater = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent < 0 && pnlPercent <= newValue && pnlPercent > currentValue
                }
                BacktestFilterResult(stoppedEarlier, stoppedLater)
            }
            "takeProfitPercent" -> {
                val wouldHaveTakenProfit = trades.count { trade ->
                    val pnlPercent = trade.pnlPercent ?: 0.0
                    pnlPercent > 0 && pnlPercent >= newValue
                }
                BacktestFilterResult(wouldHaveTakenProfit, trades.size - wouldHaveTakenProfit)
            }
            else -> BacktestFilterResult(trades.size, 0)
        }
    }

    private fun estimateTradeImpact(filteredIn: Int, totalTrades: Int): String {
        return when {
            filteredIn > totalTrades * 0.9 -> "거의 변화 없음"
            filteredIn > totalTrades * 0.7 -> "소폭 감소 예상 (-10~30%)"
            filteredIn > totalTrades * 0.5 -> "중간 감소 예상 (-30~50%)"
            else -> "대폭 감소 예상 (-50% 이상)"
        }
    }

    private fun buildBacktestRecommendation(
        paramName: String,
        filteredIn: Int,
        totalTrades: Int
    ): String {
        return when {
            paramName in listOf("stopLossPercent", "takeProfitPercent") ->
                "손절/익절 변경은 기존 수익률과 비교하여 신중하게 결정하세요."
            filteredIn < totalTrades * 0.5 ->
                "변경 시 트레이드 수가 절반 이하로 감소합니다. 신중하게 결정하세요."
            else -> "변경 적용 가능합니다."
        }
    }

    @Tool(description = """
        BTC 가격과의 상관관계를 분석합니다.
        특정 마켓의 트레이드가 BTC 가격 변동과 어떤 관계가 있는지 분석합니다.
    """)
    fun analyzeMarketCorrelation(
        @ToolParam(description = "분석 기간 (일)") days: Int = 7,
        @ToolParam(description = "분석할 지표 (btc_price, volume, volatility)") metric: String = "btc_price"
    ): String {
        log.info("[Tool] analyzeMarketCorrelation: days=$days, metric=$metric")

        val startOfPeriod = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        // 기간 내 모든 트레이드 조회
        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "분석 기간($days 일) 내 트레이드가 없습니다."
        }

        val winningTrades = trades.filter { (it.pnlAmount ?: 0.0) > 0 }
        val losingTrades = trades.filter { (it.pnlAmount ?: 0.0) <= 0 }
        val marketStats = analyzeMarketPerformance(trades)
        val winningPattern = summarizeEntryPattern(winningTrades)
        val losingPattern = summarizeEntryPattern(losingTrades)

        return """
            === 시장 상관관계 분석 ($days 일) ===

            총 트레이드: ${trades.size}건
            승리: ${winningTrades.size}건 / 패배: ${losingTrades.size}건

            === 마켓별 성과 ===
            ${marketStats.take(5).joinToString("\n") { formatMarketPerformance(it) }}

            === 성공 트레이드 패턴 ===
            ${winningPattern?.let { formatEntryPattern(it) } ?: "데이터 없음"}

            === 실패 트레이드 패턴 ===
            ${losingPattern?.let { formatEntryPattern(it) } ?: "데이터 없음"}

            === 인사이트 ===
            ${generateInsights(winningPattern, losingPattern)}
        """.trimIndent()
    }

    private fun analyzeMarketPerformance(trades: List<VolumeSurgeTradeEntity>): List<MarketPerformance> {
        return trades.groupBy { it.market }
            .map { (market, marketTrades) ->
                val wins = marketTrades.count { (it.pnlAmount ?: 0.0) > 0 }
                val losses = marketTrades.size - wins
                val totalPnl = marketTrades.sumOf { it.pnlAmount ?: 0.0 }
                MarketPerformance(
                    market = market,
                    trades = marketTrades.size,
                    wins = wins,
                    losses = losses,
                    winRate = if (marketTrades.isNotEmpty()) wins.toDouble() / marketTrades.size * 100 else 0.0,
                    totalPnl = totalPnl,
                    avgRsi = averageOrZero(marketTrades.mapNotNull { it.entryRsi }),
                    avgVolumeRatio = averageOrZero(marketTrades.mapNotNull { it.entryVolumeRatio }),
                    avgConfluence = averageOrZero(marketTrades.mapNotNull { it.confluenceScore?.toDouble() })
                )
            }
            .sortedByDescending { it.totalPnl }
    }

    private fun summarizeEntryPattern(trades: List<VolumeSurgeTradeEntity>): EntryPattern? {
        if (trades.isEmpty()) {
            return null
        }
        return EntryPattern(
            avgRsi = averageOrZero(trades.mapNotNull { it.entryRsi }),
            avgVolumeRatio = averageOrZero(trades.mapNotNull { it.entryVolumeRatio }),
            avgConfluence = averageOrZero(trades.mapNotNull { it.confluenceScore?.toDouble() }),
            commonExitReason = trades.groupBy { it.exitReason }.maxByOrNull { it.value.size }?.key
        )
    }

    private fun formatMarketPerformance(stat: MarketPerformance): String {
        return """
            ${stat.market}:
              거래: ${stat.trades}건 (승률: ${String.format("%.1f", stat.winRate)}%)
              손익: ${String.format("%.0f", stat.totalPnl)}원
              평균 RSI: ${String.format("%.1f", stat.avgRsi)}
              평균 거래량비율: ${String.format("%.1f", stat.avgVolumeRatio)}x
        """.trimIndent()
    }

    private fun formatEntryPattern(pattern: EntryPattern): String {
        return """
            평균 RSI: ${String.format("%.1f", pattern.avgRsi)}
            평균 거래량비율: ${String.format("%.1f", pattern.avgVolumeRatio)}x
            평균 컨플루언스: ${String.format("%.0f", pattern.avgConfluence)}
            주요 청산사유: ${pattern.commonExitReason}
        """.trimIndent()
    }

    private fun averageOrZero(values: List<Double>): Double {
        return values.average().takeIf { !it.isNaN() } ?: 0.0
    }

    /**
     * 패턴 기반 인사이트 생성
     */
    private fun generateInsights(
        winningPattern: EntryPattern?,
        losingPattern: EntryPattern?
    ): String {
        if (winningPattern == null || losingPattern == null) {
            return "충분한 데이터가 없어 인사이트를 생성할 수 없습니다."
        }

        val insights = mutableListOf<String>()

        // RSI 비교
        val winRsi = winningPattern.avgRsi
        val loseRsi = losingPattern.avgRsi
        if (kotlin.math.abs(winRsi - loseRsi) > 5) {
            if (winRsi < loseRsi) {
                insights.add("성공 트레이드는 낮은 RSI(${String.format("%.0f", winRsi)})에서 진입하는 경향이 있습니다.")
            } else {
                insights.add("성공 트레이드는 높은 RSI(${String.format("%.0f", winRsi)})에서 진입하는 경향이 있습니다.")
            }
        }

        // 거래량 비교
        val winVolume = winningPattern.avgVolumeRatio
        val loseVolume = losingPattern.avgVolumeRatio
        if (winVolume > loseVolume * 1.2) {
            insights.add("성공 트레이드는 더 높은 거래량 비율(${String.format("%.1f", winVolume)}x)에서 진입합니다.")
        }

        // 컨플루언스 비교
        val winConf = winningPattern.avgConfluence
        val loseConf = losingPattern.avgConfluence
        if (winConf > loseConf + 5) {
            insights.add("성공 트레이드는 더 높은 컨플루언스 점수(${String.format("%.0f", winConf)})를 가집니다.")
        }

        return if (insights.isEmpty()) {
            "성공/실패 트레이드 간 명확한 패턴 차이가 발견되지 않았습니다."
        } else {
            insights.joinToString("\n")
        }
    }

    @Tool(description = """
        시간대별/요일별 트레이드 패턴을 분석합니다.
        최적의 트레이딩 시간대를 파악하는 데 도움됩니다.
    """)
    fun analyzeHistoricalPatterns(
        @ToolParam(description = "분석 기간 (일)") days: Int = 14
    ): String {
        log.info("[Tool] analyzeHistoricalPatterns: days=$days")

        val startOfPeriod = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val endOfPeriod = Instant.now()

        val trades = tradeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfPeriod, endOfPeriod)

        if (trades.isEmpty()) {
            return "분석 기간($days 일) 내 트레이드가 없습니다."
        }

        val hourlyStats = buildHourlyStats(trades)
        val dayOfWeekStats = buildDayOfWeekStats(trades)
        val bestHours = selectHoursByWinRate(hourlyStats, descending = true)
        val worstHours = selectHoursByWinRate(hourlyStats, descending = false)
        val exitReasonStats = buildExitReasonStats(trades)

        return """
            === 시간대/요일별 패턴 분석 ($days 일) ===

            총 트레이드: ${trades.size}건

            === 최적 시간대 (KST) ===
            ${bestHours.joinToString("\n") { (hour, stats) ->
                "${hour}시: ${stats.count}건, 승률 ${String.format("%.0f", stats.winRate)}%"
            }}

            === 회피 시간대 (KST) ===
            ${worstHours.joinToString("\n") { (hour, stats) ->
                "${hour}시: ${stats.count}건, 승률 ${String.format("%.0f", stats.winRate)}%"
            }}

            === 요일별 성과 ===
            ${dayOfWeekStats.entries.sortedByDescending { it.value.totalPnl }
                .joinToString("\n") { (day, stats) ->
                    "$day: ${stats.count}건, 승률 ${String.format("%.0f", stats.winRate)}%, " +
                    "손익 ${String.format("%.0f", stats.totalPnl)}원"
                }}

            === 청산 사유별 분석 ===
            ${exitReasonStats.entries.sortedByDescending { it.value.count }
                .joinToString("\n") { (reason, stats) ->
                    "$reason: ${stats.count}건, 평균 손익 ${String.format("%.0f", stats.avgPnl)}원"
                }}

            === 권장사항 ===
            ${generateTimeBasedRecommendations(bestHours, worstHours)}
        """.trimIndent()
    }

    private fun buildHourlyStats(trades: List<VolumeSurgeTradeEntity>): Map<Int, TimeBucketStats> {
        return trades.groupBy { trade -> trade.entryTime.atZone(SEOUL_ZONE).hour }
            .mapValues { (_, hourTrades) -> buildTimeBucketStats(hourTrades) }
            .toSortedMap()
    }

    private fun buildDayOfWeekStats(trades: List<VolumeSurgeTradeEntity>): Map<String, TimeBucketStats> {
        return trades.groupBy { trade -> trade.entryTime.atZone(SEOUL_ZONE).dayOfWeek.name }
            .mapValues { (_, dayTrades) -> buildTimeBucketStats(dayTrades) }
    }

    private fun buildTimeBucketStats(trades: List<VolumeSurgeTradeEntity>): TimeBucketStats {
        val wins = trades.count { (it.pnlAmount ?: 0.0) > 0 }
        val totalPnl = trades.sumOf { it.pnlAmount ?: 0.0 }
        return TimeBucketStats(
            count = trades.size,
            wins = wins,
            winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0,
            totalPnl = totalPnl
        )
    }

    private fun selectHoursByWinRate(
        hourlyStats: Map<Int, TimeBucketStats>,
        descending: Boolean
    ): List<Map.Entry<Int, TimeBucketStats>> {
        val candidates = hourlyStats.entries.filter { it.value.count >= 2 }
        val sorted = if (descending) {
            candidates.sortedByDescending { it.value.winRate }
        } else {
            candidates.sortedBy { it.value.winRate }
        }
        return sorted.take(3)
    }

    private fun buildExitReasonStats(trades: List<VolumeSurgeTradeEntity>): Map<String, ExitReasonStats> {
        return trades.filter { it.exitReason != null }
            .groupBy { it.exitReason!! }
            .mapValues { (_, reasonTrades) ->
                ExitReasonStats(
                    count = reasonTrades.size,
                    avgPnl = averageOrZero(reasonTrades.mapNotNull { it.pnlAmount })
                )
            }
    }

    /**
     * 시간대 기반 권장사항 생성
     */
    private fun generateTimeBasedRecommendations(
        bestHours: List<Map.Entry<Int, TimeBucketStats>>,
        worstHours: List<Map.Entry<Int, TimeBucketStats>>
    ): String {
        val recommendations = mutableListOf<String>()

        if (bestHours.isNotEmpty()) {
            val bestHoursList = bestHours.map { it.key }
            recommendations.add("최적 시간대(${bestHoursList.joinToString(", ")}시)에 트레이딩 집중 권장")
        }

        if (worstHours.isNotEmpty()) {
            val worstWinRate = worstHours.firstOrNull()?.value?.winRate ?: 0.0
            if (worstWinRate < 30.0) {
                val worstHour = worstHours.first().key
                recommendations.add("${worstHour}시 시간대는 승률이 매우 낮아 트레이딩 회피 권장")
            }
        }

        return if (recommendations.isEmpty()) {
            "특별한 시간대 편향이 발견되지 않았습니다."
        } else {
            recommendations.joinToString("\n")
        }
    }
}
