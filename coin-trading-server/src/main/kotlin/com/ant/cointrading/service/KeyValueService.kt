package com.ant.cointrading.service

import com.ant.cointrading.repository.KeyValueEntity
import com.ant.cointrading.repository.KeyValueRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Key-Value 저장소 서비스 (Redis 대체)
 *
 * MySQL 기반이지만 메모리 캐시를 사용하여 빠른 읽기 성능 제공.
 * 쓰기 시 DB와 캐시 동시 갱신.
 *
 * 기본값 정의:
 * - llm.model.provider: openai
 * - llm.model.name: gpt-5-mini
 */
@Service
class KeyValueService(
    private val keyValueRepository: KeyValueRepository
) {
    private val log = LoggerFactory.getLogger(KeyValueService::class.java)

    // 메모리 캐시 (읽기 성능 향상)
    private val cache = ConcurrentHashMap<String, String>()

    companion object {
        // 시스템 기본값
        val DEFAULTS = mapOf(
            "llm.model.provider" to "openai",
            "llm.model.name" to "gpt-5-mini",
            "llm.enabled" to "false",
            "trading.enabled" to "false",
            "system.maintenance" to "false",
            "regime.detector.type" to "simple"  // hmm 또는 simple
        )

        // 카테고리 정의
        const val CATEGORY_LLM = "llm"
        const val CATEGORY_TRADING = "trading"
        const val CATEGORY_SYSTEM = "system"
    }

    @PostConstruct
    fun init() {
        log.info("KeyValueService 초기화 - 기본값 설정")
        initializeDefaults()
        loadCacheFromDb()
    }

    /**
     * 기본값 초기화 (존재하지 않는 키만)
     */
    @Transactional
    fun initializeDefaults() {
        DEFAULTS.forEach { (key, defaultValue) ->
            if (!keyValueRepository.existsByKey(key)) {
                val category = key.substringBefore(".")
                val entity = KeyValueEntity(
                    key = key,
                    value = defaultValue,
                    category = category,
                    description = "System default"
                )
                keyValueRepository.save(entity)
                log.info("기본값 생성: $key = $defaultValue")
            }
        }
    }

    /**
     * DB에서 캐시 로드
     */
    private fun loadCacheFromDb() {
        val all = keyValueRepository.findAll()
        all.forEach { entity ->
            cache[entity.key] = entity.value
        }
        log.info("캐시 로드 완료: ${cache.size}개 키")
    }

    /**
     * 값 조회 (캐시 우선)
     */
    fun get(key: String): String? {
        return cache[key] ?: keyValueRepository.findByKey(key)?.value?.also {
            cache[key] = it
        }
    }

    /**
     * 값 조회 (기본값 제공)
     */
    fun get(key: String, defaultValue: String): String {
        return get(key) ?: defaultValue
    }

    /**
     * 값 설정
     */
    @Transactional
    fun set(key: String, value: String, category: String? = null, description: String? = null): Boolean {
        return try {
            val existing = keyValueRepository.findByKey(key)

            if (existing != null) {
                existing.value = value
                existing.updatedAt = Instant.now()
                keyValueRepository.save(existing)
            } else {
                val entity = KeyValueEntity(
                    key = key,
                    value = value,
                    category = category ?: key.substringBefore("."),
                    description = description
                )
                keyValueRepository.save(entity)
            }

            // 캐시 갱신
            cache[key] = value
            log.info("KeyValue 설정: $key = $value")
            true
        } catch (e: Exception) {
            log.error("KeyValue 설정 실패: $key = $value, error: ${e.message}")
            false
        }
    }

    /**
     * 값 삭제
     */
    @Transactional
    fun delete(key: String): Boolean {
        return try {
            keyValueRepository.deleteByKey(key)
            cache.remove(key)
            log.info("KeyValue 삭제: $key")
            true
        } catch (e: Exception) {
            log.error("KeyValue 삭제 실패: $key, error: ${e.message}")
            false
        }
    }

    /**
     * 키 존재 여부
     */
    fun exists(key: String): Boolean {
        return cache.containsKey(key) || keyValueRepository.existsByKey(key)
    }

    /**
     * 카테고리별 조회
     */
    fun getByCategory(category: String): Map<String, String> {
        val entities = keyValueRepository.findByCategory(category)
        return entities.associate { it.key to it.value }
    }

    /**
     * 접두사로 조회
     */
    fun getByPrefix(prefix: String): Map<String, String> {
        val entities = keyValueRepository.findByKeyPrefix(prefix)
        return entities.associate { it.key to it.value }
    }

    /**
     * 전체 조회
     */
    fun getAll(): Map<String, String> {
        return cache.toMap()
    }

    /**
     * 전체 엔티티 조회 (상세 정보 포함)
     */
    fun getAllEntities(): List<KeyValueEntity> {
        return keyValueRepository.findAll()
    }

    /**
     * Boolean 값 조회
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return get(key)?.lowercase()?.toBooleanStrictOrNull() ?: defaultValue
    }

    /**
     * Int 값 조회
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return get(key)?.toIntOrNull() ?: defaultValue
    }

    /**
     * Long 값 조회
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return get(key)?.toLongOrNull() ?: defaultValue
    }

    /**
     * Double 값 조회
     */
    fun getDouble(key: String, defaultValue: Double = 0.0): Double {
        return get(key)?.toDoubleOrNull() ?: defaultValue
    }

    /**
     * 캐시 갱신 (DB에서 다시 로드)
     */
    fun refreshCache() {
        cache.clear()
        loadCacheFromDb()
        log.info("캐시 갱신 완료")
    }

    /**
     * 캐시 통계
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to cache.size,
            "keys" to cache.keys.toList()
        )
    }
}
