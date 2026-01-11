package com.ant.cointrading.controller

import com.ant.cointrading.optimizer.LlmOptimizer
import com.ant.cointrading.repository.AuditLogEntity
import com.ant.cointrading.service.ModelSelector
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * LLM Optimizer REST API
 */
@RestController
@RequestMapping("/api/optimizer")
class OptimizerController(
    private val llmOptimizer: LlmOptimizer,
    private val modelSelector: ModelSelector
) {
    private val log = LoggerFactory.getLogger(OptimizerController::class.java)

    /**
     * 수동 최적화 실행
     */
    @PostMapping("/run")
    fun runOptimization(): ResponseEntity<OptimizationResult> {
        // ChatClient 가용 여부 확인
        if (!modelSelector.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                OptimizationResult(
                    success = false,
                    result = "LLM 서비스 사용 불가: API 키가 설정되지 않았습니다. " +
                            "SPRING_AI_ANTHROPIC_API_KEY 또는 SPRING_AI_OPENAI_API_KEY 환경변수를 설정하세요."
                )
            )
        }

        return try {
            val result = llmOptimizer.optimize()
            ResponseEntity.ok(
                OptimizationResult(
                    success = true,
                    result = result
                )
            )
        } catch (e: Exception) {
            log.error("최적화 실행 실패: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                OptimizationResult(
                    success = false,
                    result = "최적화 실행 실패: ${e.message}"
                )
            )
        }
    }

    /**
     * 마지막 최적화 결과 조회
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any?> {
        return llmOptimizer.getLastOptimizationResult() + mapOf(
            "llmServiceAvailable" to modelSelector.isAvailable()
        )
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
