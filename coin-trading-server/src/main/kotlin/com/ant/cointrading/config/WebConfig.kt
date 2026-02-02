package com.ant.cointrading.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addViewControllers(registry: ViewControllerRegistry) {
        // 루트 경로와 React Router 경로를 index.html로 포워딩
        registry.addViewController("/")
            .setViewName("forward:/index.html")

        // SPA 라우팅을 위한 패턴 (앞으로 더 추가 가능)
        registry.addViewController("/dashboard")
            .setViewName("forward:/index.html")
    }
}
