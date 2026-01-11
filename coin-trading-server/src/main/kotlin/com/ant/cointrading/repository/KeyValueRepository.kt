package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface KeyValueRepository : JpaRepository<KeyValueEntity, Long> {

    fun findByKey(key: String): KeyValueEntity?

    fun existsByKey(key: String): Boolean

    fun findByCategory(category: String): List<KeyValueEntity>

    fun findByCategoryIn(categories: List<String>): List<KeyValueEntity>

    @Modifying
    @Query("UPDATE KeyValueEntity k SET k.value = :value, k.updatedAt = CURRENT_TIMESTAMP WHERE k.key = :key")
    fun updateValue(key: String, value: String): Int

    @Modifying
    @Query("DELETE FROM KeyValueEntity k WHERE k.key = :key")
    fun deleteByKey(key: String): Int

    @Query("SELECT k FROM KeyValueEntity k WHERE k.key LIKE :prefix%")
    fun findByKeyPrefix(prefix: String): List<KeyValueEntity>
}
