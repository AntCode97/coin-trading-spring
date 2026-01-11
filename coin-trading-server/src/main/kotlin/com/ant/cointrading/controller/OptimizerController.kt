package com.ant.cointrading.controller

import com.ant.cointrading.optimizer.LlmOptimizer
import com.ant.cointrading.repository.AuditLogEntity
import org.springframework.web.bind.annotation.*

/**
 * LLM Optimizer REST API
 */
@RestController
@RequestMapping("/api/optimizer")
class OptimizerController(
    private val llmOptimizer: LlmOptimizer
) {

    /**
     * 수동 최적화 실행
     */
    @PostMapping("/run")
    fun runOptimization(): OptimizationResult {
        val result = llmOptimizer.optimize()
        return OptimizationResult(
            success = true,
            result = result
        )
    }

    /**
     * 마지막 최적화 결과 조회
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any?> {
        return llmOptimizer.getLastOptimizationResult()
    }

    /**
     * 감사 로그 조회
     */
    @GetMapping("/audit-logs")
    fun getAuditLogs(
        @RequestParam(defaultValue = "100") limit: Int
    ): List<AuditLogEntity> {
        return llmOptimizer.getAuditLogs(limit)
    }
}

data class OptimizationResult(
    val success: Boolean,
    val result: String
)
