package com.ant.cointrading.controller

import com.ant.cointrading.regime.HmmRegimeDetector
import com.ant.cointrading.service.KeyValueService
import com.ant.cointrading.service.ModelSelector
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 시스템 설정 관리 REST API
 *
 * KeyValue 저장소와 LLM 모델 설정을 관리.
 * 런타임에 동적으로 설정 변경 가능.
 *
 * 엔드포인트:
 * - GET  /api/settings              - 전체 설정 조회
 * - GET  /api/settings/{key}        - 특정 키 조회
 * - POST /api/settings              - 설정 추가/수정
 * - DELETE /api/settings/{key}      - 설정 삭제
 * - GET  /api/settings/model        - LLM 모델 상태 조회
 * - POST /api/settings/model        - LLM 모델 변경
 */
@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val keyValueService: KeyValueService,
    private val modelSelector: ModelSelector,
    private val hmmRegimeDetector: HmmRegimeDetector
) {

    private val log = LoggerFactory.getLogger(SettingsController::class.java)

    // ===========================================
    // KeyValue 설정 API
    // ===========================================

    /**
     * 전체 설정 조회
     */
    @GetMapping
    fun getAllSettings(): ResponseEntity<Map<String, Any>> {
        val settings = keyValueService.getAllEntities().map { entity ->
            mapOf(
                "key" to entity.key,
                "value" to entity.value,
                "category" to entity.category,
                "description" to entity.description,
                "updatedAt" to entity.updatedAt.toString()
            )
        }

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "count" to settings.size,
            "settings" to settings
        ))
    }

    /**
     * 특정 키 조회
     */
    @GetMapping("/{key}")
    fun getSetting(@PathVariable key: String): ResponseEntity<Map<String, Any?>> {
        val value = keyValueService.get(key)

        return if (value != null) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "key" to key,
                "value" to value
            ))
        } else {
            ResponseEntity.ok(mapOf(
                "success" to false,
                "key" to key,
                "error" to "Key not found"
            ))
        }
    }

    /**
     * 설정 추가/수정
     */
    @PostMapping
    fun setSetting(@RequestBody request: SetSettingRequest): ResponseEntity<Map<String, Any>> {
        log.info("설정 변경 요청: ${request.key} = ${request.value}")

        val success = keyValueService.set(
            key = request.key,
            value = request.value,
            category = request.category,
            description = request.description
        )

        return ResponseEntity.ok(mapOf(
            "success" to success,
            "key" to request.key,
            "value" to request.value,
            "message" to if (success) "설정이 저장되었습니다" else "설정 저장 실패"
        ))
    }

    /**
     * 설정 삭제
     */
    @DeleteMapping("/{key}")
    fun deleteSetting(@PathVariable key: String): ResponseEntity<Map<String, Any>> {
        log.info("설정 삭제 요청: $key")

        val success = keyValueService.delete(key)

        return ResponseEntity.ok(mapOf(
            "success" to success,
            "key" to key,
            "message" to if (success) "설정이 삭제되었습니다" else "설정 삭제 실패"
        ))
    }

    /**
     * 카테고리별 설정 조회
     */
    @GetMapping("/category/{category}")
    fun getByCategory(@PathVariable category: String): ResponseEntity<Map<String, Any>> {
        val settings = keyValueService.getByCategory(category)

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "category" to category,
            "count" to settings.size,
            "settings" to settings
        ))
    }

    // ===========================================
    // LLM 모델 설정 API
    // ===========================================

    /**
     * LLM 모델 상태 조회
     */
    @GetMapping("/model")
    fun getModelStatus(): ResponseEntity<Map<String, Any?>> {
        val status = modelSelector.getStatus()
        val providerModels = modelSelector.getProviderModels()

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "status" to status,
            "providerModels" to providerModels
        ))
    }

    /**
     * LLM 모델 변경
     */
    @PostMapping("/model")
    fun setModel(@RequestBody request: SetModelRequest): ResponseEntity<Map<String, Any>> {
        log.info("LLM 모델 변경 요청: provider=${request.provider}, model=${request.modelName}")

        var success = true
        val messages = mutableListOf<String>()

        // Provider 변경
        if (request.provider != null) {
            if (modelSelector.setProvider(request.provider)) {
                messages.add("Provider가 '${request.provider}'(으)로 변경되었습니다")
            } else {
                success = false
                messages.add("Provider 변경 실패: ${request.provider}")
            }
        }

        // Model name 변경
        if (request.modelName != null) {
            if (modelSelector.setModelName(request.modelName)) {
                messages.add("Model이 '${request.modelName}'(으)로 변경되었습니다")
            } else {
                success = false
                messages.add("Model 변경 실패: ${request.modelName}")
            }
        }

        val currentStatus = modelSelector.getStatus()

        return ResponseEntity.ok(mapOf(
            "success" to success,
            "messages" to messages,
            "currentStatus" to currentStatus
        ))
    }

    /**
     * 사용 가능한 Provider 목록
     */
    @GetMapping("/model/providers")
    fun getAvailableProviders(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "availableProviders" to modelSelector.getAvailableProviders(),
            "allProviders" to ModelSelector.AVAILABLE_PROVIDERS,
            "providerModels" to modelSelector.getProviderModels()
        ))
    }

    // ===========================================
    // 캐시 관리 API
    // ===========================================

    /**
     * 캐시 갱신
     */
    @PostMapping("/cache/refresh")
    fun refreshCache(): ResponseEntity<Map<String, Any>> {
        keyValueService.refreshCache()

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "message" to "캐시가 갱신되었습니다",
            "cacheStats" to keyValueService.getCacheStats()
        ))
    }

    /**
     * 캐시 통계
     */
    @GetMapping("/cache/stats")
    fun getCacheStats(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "stats" to keyValueService.getCacheStats()
        ))
    }

    // ===========================================
    // 레짐 감지 API
    // ===========================================

    /**
     * 레짐 감지기 설정 조회
     */
    @GetMapping("/regime")
    fun getRegimeDetectorStatus(): ResponseEntity<Map<String, Any?>> {
        val currentType = keyValueService.get("regime.detector.type", "simple")
        val hmmStatus = hmmRegimeDetector.getStatus()

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "currentDetector" to currentType,
            "availableDetectors" to listOf("simple", "hmm"),
            "hmm" to hmmStatus
        ))
    }

    /**
     * 레짐 감지기 변경
     */
    @PostMapping("/regime")
    fun setRegimeDetector(@RequestBody request: SetRegimeDetectorRequest): ResponseEntity<Map<String, Any>> {
        val validTypes = listOf("simple", "hmm")

        if (request.type !in validTypes) {
            return ResponseEntity.ok(mapOf(
                "success" to false,
                "error" to "Invalid detector type. Available: $validTypes"
            ))
        }

        keyValueService.set("regime.detector.type", request.type, "regime", "레짐 감지기 유형")
        log.info("레짐 감지기 변경: ${request.type}")

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "currentDetector" to request.type,
            "message" to "레짐 감지기가 '${request.type}'(으)로 변경되었습니다"
        ))
    }

    /**
     * HMM 전이 확률 조회
     */
    @GetMapping("/regime/hmm/transitions")
    fun getHmmTransitions(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "success" to true,
            "transitions" to hmmRegimeDetector.getTransitionProbabilities()
        ))
    }
}

// Request DTOs
data class SetSettingRequest(
    val key: String,
    val value: String,
    val category: String? = null,
    val description: String? = null
)

data class SetModelRequest(
    val provider: String? = null,
    val modelName: String? = null
)

data class SetRegimeDetectorRequest(
    val type: String
)
