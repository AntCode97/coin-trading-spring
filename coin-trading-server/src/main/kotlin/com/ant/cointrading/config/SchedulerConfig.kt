package com.ant.cointrading.config

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.Executors

/**
 * Spring 스케줄러 Virtual Thread 설정
 *
 * Java 21+ Virtual Thread를 사용하여 @Scheduled 메서드 실행
 * - 가볍 양의 스레드로 I/O 작업 중에도 CPU 효율적 사용
 * - 수천 개의 동시 작업 가능
 */
@Configuration
@EnableAsync
class SchedulerConfig : AsyncConfigurer {

    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.threadNamePrefix="scheduled-vt-"
        scheduler.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor())
        scheduler.initialize()
        return scheduler
    }

    @Bean
    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler {
        return AsyncUncaughtExceptionHandler { throwable, method ->
            val className = method.declaringClass.simpleName
            val methodName = method.name
            println("Exception in async method: $className.$methodName() - ${throwable.message}")
            throwable.printStackTrace()
        }
    }
}
