package com.ant.cointrading.controller

import com.ant.cointrading.optimizer.LlmOptimizer
import com.ant.cointrading.optimizer.OptimizationGateStatus
import com.ant.cointrading.optimizer.OptimizationValidationService
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
    private val optimizationValidationService: OptimizationValidationService,
    private val modelSelector: ModelSelector
) {
    private val log = LoggerFactory.getLogger(OptimizerController::class.java)
    private val unavailableMessage = "LLM 서비스 사용 불가: API 키가 설정되지 않았습니다. " +
            "SPRING_AI_ANTHROPIC_API_KEY 또는 SPRING_AI_OPENAI_API_KEY 환경변수를 설정하세요."

    /**
     * 수동 최적화 실행
     */
    @PostMapping("/run")
    fun runOptimization(): ResponseEntity<OptimizationResult> {
        // ChatClient 가용 여부 확인
        if (!modelSelector.isAvailable()) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, unavailableMessage)
        }

        return runCatching { llmOptimizer.optimize() }
            .fold(
                onSuccess = { success(it) },
                onFailure = { e ->
                    log.error("최적화 실행 실패: ${e.message}", e)
                    failure(HttpStatus.INTERNAL_SERVER_ERROR, "최적화 실행 실패: ${e.message}")
                }
            )
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

    @GetMapping("/validation-gate")
    fun getValidationGate(
        @RequestParam(defaultValue = "false") forceRefresh: Boolean
    ): OptimizationGateStatus {
        return optimizationValidationService.getGateStatus(forceRefresh = forceRefresh)
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

    private fun success(result: String): ResponseEntity<OptimizationResult> {
        return ResponseEntity.ok(
            OptimizationResult(
                success = true,
                result = result
            )
        )
    }

    private fun failure(status: HttpStatus, result: String): ResponseEntity<OptimizationResult> {
        return ResponseEntity.status(status).body(
            OptimizationResult(
                success = false,
                result = result
            )
        )
    }
}

data class OptimizationResult(
    val success: Boolean,
    val result: String,
    val message: String = result
)
