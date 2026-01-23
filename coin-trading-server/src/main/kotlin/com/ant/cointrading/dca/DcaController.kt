package com.ant.cointrading.dca

import org.springframework.web.bind.annotation.*

/**
 * DCA 엔진 관리 API
 */
@RestController
@RequestMapping("/api/dca")
class DcaController(
    private val dcaEngine: DcaEngine
) {

    /**
     * DCA 엔진 상태 조회
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any> {
        return dcaEngine.getStatus()
    }

    /**
     * 수동 청산
     */
    @PostMapping("/manual-close/{market}")
    fun manualClose(@PathVariable market: String): Map<String, Any?> {
        return dcaEngine.manualClose(market)
    }
}
