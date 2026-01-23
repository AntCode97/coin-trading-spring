package com.ant.cointrading.mcp.tool

import com.ant.cointrading.memescalper.MemeScalperEngine
import com.ant.cointrading.repository.MemeScalperTradeRepository
import com.ant.cointrading.repository.VolumeSurgeTradeRepository
import com.ant.cointrading.volumesurge.VolumeSurgeEngine
import org.springaicommunity.mcp.annotation.McpTool
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 시스템 상태 모니터링 MCP 도구
 *
 * 트레이딩 시스템의 전체 상태를 조회하고 모니터링합니다.
 */
@Component
class SystemTools(
    private val memeScalperEngine: MemeScalperEngine,
    private val memeScalperRepository: MemeScalperTradeRepository,
    private val volumeSurgeRepository: VolumeSurgeTradeRepository
) {

    // ========================================
    // 응답 Data Classes
    // ========================================

    /** 시스템 상태 요약 */
    data class SystemStatusSummary(
        val timestamp: String,
        val memeScalperStatus: Map<String, Any?>,
        val volumeSurgeOpenPositions: Int,
        val totalOpenPositions: Int,
        val recentPerformance: Map<String, Double>,
        val systemHealth: SystemHealth
    )

    /** 시스템 건전성 */
    data class SystemHealth(
        val status: String,           // HEALTHY, WARNING, CRITICAL
        val issues: List<String>,     // 발견된 문제 목록
        val recommendations: List<String>  // 권장사항
    )

    /** 열린 포지션 상세 */
    data class OpenPositionDetail(
        val strategy: String,
        val market: String,
        val entryPrice: Double,
        val currentPrice: Double,
        val pnlPercent: Double,
        val holdingMinutes: Long,
        val entryTime: String
    )

    /** 일일 요약 */
    data class DailySummary(
        val date: String,
        val memeScalperTrades: Int,
        val memeScalperPnl: Double,
        val memeScalperWinRate: Double,
        val volumeSurgeTrades: Int,
        val volumeSurgePnl: Double,
        val totalPnl: Double,
        val recommendations: List<String>
    )

    // ========================================
    // 시스템 상태 도구
    // ========================================

    @McpTool(description = """
        시스템 전체 상태를 조회합니다.

        MemeScalper, VolumeSurge 등 모든 전략의 상태와
        열린 포지션, 최근 성과를 종합적으로 반환합니다.
    """)
    fun getSystemStatus(): SystemStatusSummary {
        val now = Instant.now()

        // MemeScalper 상태
        val memeScalperStatus = memeScalperEngine.getStatus()

        // 열린 포지션 수집
        val memeScalperOpen = memeScalperRepository.findByStatus("OPEN").size
        val volumeSurgeOpen = volumeSurgeRepository.findByStatus("OPEN").size
        val totalOpen = memeScalperOpen + volumeSurgeOpen

        // 최근 성과 (오늘)
        val startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        val memeTrades = memeScalperRepository.findTodayClosedTrades(startOfDay)
        val memePnl = memeTrades.sumOf { it.pnlAmount ?: 0.0 }

        val recentPerformance = mapOf(
            "memeScalperPnl" to memePnl,
            "memeScalperTrades" to memeTrades.size.toDouble(),
            "memeScalperWinRate" to if (memeTrades.isNotEmpty()) {
                memeTrades.count { (it.pnlAmount ?: 0.0) > 0 }.toDouble() / memeTrades.size * 100
            } else 0.0
        )

        // 시스템 건전성 체크
        val health = checkSystemHealth(memeScalperStatus, totalOpen, memePnl)

        return SystemStatusSummary(
            timestamp = now.toString(),
            memeScalperStatus = memeScalperStatus,
            volumeSurgeOpenPositions = volumeSurgeOpen,
            totalOpenPositions = totalOpen,
            recentPerformance = recentPerformance,
            systemHealth = health
        )
    }

    @McpTool(description = """
        시스템 건전성을 체크하고 문제를 진단합니다.
    """)
    fun diagnoseSystemHealth(): SystemHealth {
        val status = getSystemStatus()
        return status.systemHealth
    }

    @McpTool(description = """
        모든 열린 포지션의 상세 정보를 조회합니다.
    """)
    fun getAllOpenPositions(): List<OpenPositionDetail> {
        val now = Instant.now()
        val positions = mutableListOf<OpenPositionDetail>()

        // MemeScalper 열린 포지션
        memeScalperRepository.findByStatus("OPEN").forEach { trade ->
            // 현재가는 DB에 없으므로 진입가 사용 (실제로는 API 호출 필요)
            positions.add(OpenPositionDetail(
                strategy = "MEME_SCALPER",
                market = trade.market,
                entryPrice = trade.entryPrice,
                currentPrice = trade.entryPrice,  // TODO: API로 현재가 조회
                pnlPercent = 0.0,
                holdingMinutes = ChronoUnit.MINUTES.between(trade.entryTime, now),
                entryTime = trade.entryTime.toString()
            ))
        }

        // VolumeSurge 열린 포지션
        volumeSurgeRepository.findByStatus("OPEN").forEach { trade ->
            positions.add(OpenPositionDetail(
                strategy = "VOLUME_SURGE",
                market = trade.market,
                entryPrice = trade.entryPrice,
                currentPrice = trade.entryPrice,  // TODO: API로 현재가 조회
                pnlPercent = 0.0,
                holdingMinutes = ChronoUnit.MINUTES.between(trade.entryTime, now),
                entryTime = trade.entryTime.toString()
            ))
        }

        return positions
    }

    @McpTool(description = """
        오늘의 트레이딩 요약을 조회합니다.
    """)
    fun getDailySummary(): DailySummary {
        val now = Instant.now()
        val startOfDay = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS)

        // MemeScalper 오늘 통계
        val memeTrades = memeScalperRepository.findTodayClosedTrades(startOfDay)
        val memePnl = memeTrades.sumOf { it.pnlAmount ?: 0.0 }
        val memeWinRate = if (memeTrades.isNotEmpty()) {
            memeTrades.count { (it.pnlAmount ?: 0.0) > 0 }.toDouble() / memeTrades.size
        } else 0.0

        // VolumeSurge 오늘 통계
        val volumeSurgeTrades = volumeSurgeRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startOfDay, now)
            .filter { it.status == "CLOSED" }
        val volumePnl = volumeSurgeTrades.sumOf { it.pnlAmount ?: 0.0 }

        // 총 손익
        val totalPnl = memePnl + volumePnl

        // 권장사항
        val recommendations = mutableListOf<String>()
        if (memeWinRate < 0.5 && memeTrades.size >= 5) {
            recommendations.add("MemeScalper 승률이 50% 미만입니다. 진입 조건 검토가 필요합니다.")
        }
        if (totalPnl < -50000) {
            recommendations.add("일일 손실이 5만원을 초과했습니다. 거래를 일시 중단하는 것을 고려하세요.")
        }
        if (memeTrades.size > 50) {
            recommendations.add("거래 횟수가 과도합니다. 진입 조건을 강화하세요.")
        }

        return DailySummary(
            date = startOfDay.toString().substring(0, 10),
            memeScalperTrades = memeTrades.size,
            memeScalperPnl = memePnl,
            memeScalperWinRate = memeWinRate * 100,
            volumeSurgeTrades = volumeSurgeTrades.size,
            volumeSurgePnl = volumePnl,
            totalPnl = totalPnl,
            recommendations = recommendations
        )
    }

    // ========================================
    // 내부 헬퍼 메서드
    // ========================================

    private fun checkSystemHealth(
        memeScalperStatus: Map<String, Any?>,
        totalOpenPositions: Int,
        memePnl: Double
    ): SystemHealth {
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        var status = "HEALTHY"

        // MemeScalper 상태 체크
        val canTrade = memeScalperStatus["canTrade"] as? Boolean ?: true
        if (!canTrade) {
            issues.add("MemeScalper 거래가 중단되었습니다: ${memeScalperStatus["consecutiveLosses"]}회 연속 손실")
            status = "WARNING"
            recommendations.add("서킷브레이커 리셋을 고려하세요.")
        }

        // 포지션 수 체크
        if (totalOpenPositions > 10) {
            issues.add("열린 포지션이 너무 많습니다: $totalOpenPositions")
            status = "WARNING"
            recommendations.add("일부 포지션을 수동으로 청산하세요.")
        }

        // 손실 체크
        if (memePnl < -100000) {
            issues.add("일일 손실이 10만원을 초과했습니다: ${String.format("%.0f", memePnl)}원")
            status = "CRITICAL"
            recommendations.add("즉시 모든 포지션을 청산하고 거래를 중단하세요.")
        } else if (memePnl < -50000) {
            issues.add("일일 손실이 5만원을 초과했습니다: ${String.format("%.0f", memePnl)}원")
            if (status != "CRITICAL") status = "WARNING"
            recommendations.add("새로운 진입을 멈추고 기존 포지션을 모니터링하세요.")
        }

        if (issues.isEmpty()) {
            recommendations.add("시스템이 정상 작동 중입니다.")
        }

        return SystemHealth(
            status = status,
            issues = issues,
            recommendations = recommendations
        )
    }
}
