package com.ant.cointrading.config

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executors

/**
 * Virtual Thread 기반 비동기 설정
 *
 * Spring Boot 3.2+ Virtual Thread 활용:
 * - @Async 메서드는 Virtual Thread에서 실행
 * - Platform Thread보다 메모리 사용량 90% 감소
 * - I/O 작업 병목성 대폭 향상
 */
@Configuration
@EnableAsync
class AsyncConfiguration : AsyncConfigurer {

    /**
     * Virtual Thread Executor for @Async methods
     *
     * 특징:
     * - 가벼운 I/O 작업에 최적 (API 호출, DB 쿼리)
     * - Platform Thread보다 생성/소멸 비용 1000배 낮음
     * - 수십만 개의 동시 요청 처리 가능
     */
    @Bean(name = ["taskExecutor"])
    override fun getAsyncExecutor(): org.springframework.core.task.TaskExecutor {
        return org.springframework.core.task.TaskExecutor {
            command -> Executors.newVirtualThreadPerTaskExecutor().execute(command)
        }
    }

    /**
     * Async Exception Handler
     */
    @Bean
    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler {
        return AsyncUncaughtExceptionHandler { throwable, method, params ->
            val log = org.slf4j.LoggerFactory.getLogger(AsyncConfiguration::class.java)
            log.error("Async task failed in method: ${method.name}", throwable)
        }
    }
}
