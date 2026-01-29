package com.ant.cointrading.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * Slack 로그 Appender 자동 설정
 *
 * Spring Boot 시작 시 Logback에 Slack appender를 프로그래밍 방식으로 등록
 *
 * Rate Limit 방지:
 * - ERROR 레벨만 전송
 * - 동일 메시지 1분 내 중복 전송 방지
 */
@Component
class SlackLogAppenderConfig(
    private val slackNotifier: SlackNotifier
) {

    // 메시지 중복 방지 (메시지 해시 -> 마지막 전송 시간)
    private val lastSentTimes = ConcurrentHashMap<String, Long>()

    @PostConstruct
    fun init() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return

        // Slack Appender 생성
        val slackAppender = object : UnsynchronizedAppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent?) {
                if (!isStarted || event == null) return

                // ERROR 레벨만 전송 (rate limit 방지)
                if (event.level != Level.ERROR) return

                try {
                    val logger = event.loggerName
                    val message = event.formattedMessage

                    // 중복 메시지 필터 (1분 내 동일 메시지 스킵)
                    val messageKey = "$logger:$message"
                    val now = Instant.now().toEpochMilli()
                    val lastSent = lastSentTimes[messageKey] ?: 0
                    val oneMinuteMs = 60_000L

                    if (now - lastSent < oneMinuteMs) {
                        return // 1분 내에 이미 전송함
                    }

                    lastSentTimes[messageKey] = now

                    // 로거 이름 간소화
                    val shortLogger = logger.substringAfterLast(".")

                    val slackMessage = """
                        :x: [ERROR] $shortLogger
                        $message
                    """.trimIndent()

                    slackNotifier.sendSystemNotification("[ERROR]", slackMessage)

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

        println("=== Slack Log Appender 등록 완료 (ERROR 레벨만) ===")
    }
}
