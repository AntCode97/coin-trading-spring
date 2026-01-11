package com.ant.cointrading.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntity, Long> {

    fun findByEventTypeOrderByCreatedAtDesc(eventType: String): List<AuditLogEntity>

    fun findByMarketOrderByCreatedAtDesc(market: String): List<AuditLogEntity>

    fun findByCreatedAtAfterOrderByCreatedAtDesc(since: Instant): List<AuditLogEntity>

    fun findByEventTypeAndCreatedAtAfter(eventType: String, since: Instant): List<AuditLogEntity>

    fun findByAppliedAndCreatedAtAfter(applied: Boolean, since: Instant): List<AuditLogEntity>

    fun findTop100ByOrderByCreatedAtDesc(): List<AuditLogEntity>
}
