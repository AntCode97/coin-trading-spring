package com.ant.cointrading.config

import com.ant.cointrading.notification.SlackNotifier
import com.ant.cointrading.service.KeyValueService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 애플리케이션 시작 시 배포 정보를 Slack으로 전송
 */
@Component
class DeploymentStartupRunner(
    private val buildProperties: BuildProperties?,
    private val environment: Environment,
    private val slackNotifier: SlackNotifier,
    private val keyValueService: KeyValueService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            // 프로덕션 환경에서만 배포 알림 전송
            if (!isProductionProfile()) {
                log.info("Non-production environment, skip deployment notification")
                return
            }

            val deploymentInfo = buildDeploymentInfo()
            slackNotifier.sendSystemNotification(
                "🚀 배포 완료",
                deploymentInfo
            )

            // 배포 카운트 증가
            incrementDeploymentCount()

            // 로그에도 기록
            log.info("=== 배포 정보 ===")
            log.info(deploymentInfo)

        } catch (e: Exception) {
            log.error("배포 알림 전송 실패: ${e.message}", e)
        }
    }

    private fun isProductionProfile(): Boolean {
        val profiles = environment.activeProfiles
        return profiles.isEmpty() || profiles.contains("default") || profiles.contains("prod")
    }

    private fun buildDeploymentInfo(): String {
        val now = Instant.now()
        val kstTime = now.atZone(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val hostname = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }

        val commitId = buildProperties?.get("git.commit.id") ?: "unknown"
        val commitIdAbbrev = buildProperties?.get("git.commit.id.abbrev") ?: "unknown"
        val branch = buildProperties?.get("git.branch") ?: "unknown"
        val commitTime = buildProperties?.get("git.commit.time") ?: "unknown"
        val dirty = buildProperties?.get("git.dirty")?.toBoolean() ?: false

        // 배포 카운트 조회
        val deploymentCount = getDeploymentCount() + 1

        val dirtyMark = if (dirty) " (수정 있음)" else ""

        return """
            🚀 배포 완료 #${deploymentCount}

            📅 배포 시간: $kstTime
            🖥️  서버: $hostname
            🌿 브랜치: $branch
            💎 커밋: $commitIdAbbrev$dirtyMark

            ℹ️ 정보:
            - 전체 커밋: $commitId
            - 커밋 시간: $commitTime
            - 프로필: ${environment.activeProfiles.joinToString().ifEmpty { "default" }}

            확인: curl http://localhost:8080/actuator/health
        """.trimIndent()
    }

    private fun getDeploymentCount(): Int {
        return keyValueService.get("deployment.count", "0").toIntOrNull() ?: 0
    }

    private fun incrementDeploymentCount() {
        val currentCount = getDeploymentCount()
        keyValueService.set("deployment.count", (currentCount + 1).toString())
    }
}
