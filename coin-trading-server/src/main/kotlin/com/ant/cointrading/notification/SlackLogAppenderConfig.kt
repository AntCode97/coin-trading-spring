package com.ant.cointrading.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

/**
 * Slack 로그 Appender 자동 설정
 *
 * Spring Boot 시작 시 Logback에 Slack appender를 프로그래밍 방식으로 등록
 */
@Component
class SlackLogAppenderConfig(
    private val slackNotifier: SlackNotifier
) {

    @PostConstruct
    fun init() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return

        // Slack Appender 생성
        val slackAppender = object : UnsynchronizedAppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent?) {
                if (!isStarted || event == null) return

                try {
                    val level = event.level.levelStr
                    val logger = event.loggerName
                    val message = event.formattedMessage

                    // 레벨별 이모지
                    val emoji = when (level) {
                        "ERROR" -> ":x:"
                        "WARN" -> ":warning:"
                        "INFO" -> ":information_source:"
                        else -> ":page_facing_up:"
                    }

                    // 로거 이름 간소화
                    val shortLogger = logger.substringAfterLast(".")

                    val slackMessage = """
                        $emoji [$level] $shortLogger
                        $message
                    """.trimIndent()

                    slackNotifier.sendSystemNotification("[$level]", slackMessage)

                } catch (e: Exception) {
                    // 무한 루프 방지 - System.err만 사용
                    System.err.println("Slack 로그 전송 실패: ${e.message}")
                }
            }
        }

        slackAppender.name = "SLACK_APPENDER"
        slackAppender.context = loggerContext
        slackAppender.start()

        // Root logger에 appender 추가
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.addAppender(slackAppender)

        println("=== Slack Log Appender 등록 완료 ===")
    }
}
