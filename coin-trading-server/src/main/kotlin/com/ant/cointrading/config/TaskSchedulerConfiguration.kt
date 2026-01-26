package com.ant.cointrading.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.util.concurrent.Executors

/**
 * Virtual Thread 기반 스케줄러 설정
 *
 * Spring Boot 3.2+ Virtual Thread 활용:
 * - @Scheduled 메서드는 Virtual Thread에서 실행
 * - I/O 대기 시간이 긴 작업에 최적
 * - 수십만 개의 예약 작업 처리 가능
 *
 * application.yml에 이미 설정되어 있음:
 * spring.threads.virtual.enabled: true
 */
@Configuration
class TaskSchedulerConfiguration : SchedulingConfigurer {

    private val log = LoggerFactory.getLogger(TaskSchedulerConfiguration::class.java)

    override fun configureTasks(scheduledTaskRegistrar: ScheduledTaskRegistrar) {
        log.info("=== Virtual Thread 스케줄러 초기화 ===")
        log.info("@Scheduled 메서드는 Virtual Thread에서 실행됩니다")
        log.info("메모리 효율: Platform Thread ~1MB vs Virtual Thread 몇 KB")
    }
}
