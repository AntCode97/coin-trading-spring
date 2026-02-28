package com.ant.cointrading.repository

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "guided_autopilot_decisions",
    indexes = [
        Index(name = "idx_guided_autopilot_decisions_created", columnList = "createdAt"),
        Index(name = "idx_guided_autopilot_decisions_market", columnList = "market,createdAt"),
        Index(name = "idx_guided_autopilot_decisions_stage", columnList = "stage,createdAt"),
    ]
)
class GuidedAutopilotDecisionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 80)
    var runId: String = "",

    @Column(nullable = false, length = 20)
    var market: String = "",

    @Column(nullable = false, length = 20)
    var intervalValue: String = "minute30",

    @Column(nullable = false, length = 20)
    var mode: String = "SWING",

    @Column(nullable = false, length = 30)
    var stage: String = "RULE_FAIL",

    @Column(nullable = false)
    var approve: Boolean = false,

    @Column(precision = 10, scale = 4)
    var confidence: BigDecimal? = null,

    @Column(precision = 10, scale = 4)
    var score: BigDecimal? = null,

    @Column(precision = 20, scale = 2)
    var entryAmountKrw: BigDecimal? = null,

    @Column(nullable = false)
    var executed: Boolean = false,

    @Column(length = 600)
    var reason: String? = null,

    @Column(columnDefinition = "TEXT")
    var payloadJson: String? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Repository
interface GuidedAutopilotDecisionRepository : JpaRepository<GuidedAutopilotDecisionEntity, Long> {
    fun findByCreatedAtAfterOrderByCreatedAtAsc(after: Instant): List<GuidedAutopilotDecisionEntity>
    fun findTop500ByCreatedAtAfterOrderByCreatedAtDesc(after: Instant): List<GuidedAutopilotDecisionEntity>
}
