package com.ant.cointrading.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 정적 리소스 서빙 (CSS, JS, 이미지 등)
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(3600) // 1시간 캐시
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        // SPA 라우팅: 모든 경로를 index.html로 포워딩
        registry.addViewController("/")
            .setViewName("forward:/index.html")

        registry.addViewController("/dashboard")
            .setViewName("forward:/index.html")

        // 추후 추가될 SPA 경로
        registry.addViewController("/settings")
            .setViewName("forward:/index.html")
    }
}
