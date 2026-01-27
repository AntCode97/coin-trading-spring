package com.ant.cointrading.config

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.TaskScheduler
import java.util.concurrent.Executors

/**
 * Spring 스케줄러 설정
 *
 * 스케줄링 중심 시스템이므로 전용 스케줄러 사용
 */
@Configuration
@EnableAsync
class SchedulerConfig : AsyncConfigurer {

    @Bean
    fun taskScheduler(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.setPoolSize(1)
        scheduler.setThreadNamePrefix("scheduled-")
        scheduler.initialize()
        return scheduler
    }

    @Bean
    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler {
        return AsyncUncaughtExceptionHandler { throwable, method, args ->
            val className = method.declaringClass.simpleName
            val methodName = method.name
            println("Exception in async method: $className.$methodName() - ${throwable.message}")
            throwable.printStackTrace()
        }
    }
}
