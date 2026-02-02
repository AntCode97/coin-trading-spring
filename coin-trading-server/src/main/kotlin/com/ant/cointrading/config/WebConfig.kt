package com.ant.cointrading.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // API 경로는 리소스 핸들러에서 제외
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(SpaPathResourceResolver())
    }

    /**
     * SPA Path Resource Resolver
     * API 요청이 아닌 모든 요청을 index.html로 포워딩
     */
    class SpaPathResourceResolver : PathResourceResolver() {
        override fun getResource(resourceLocation: Resource, resourcePath: String): Resource? {
            val requestedResource = resourceLocation.createRelative(resourcePath)

            // 요청한 리소스가 존재하고 읽을 수 있으면 반환
            return if (requestedResource.exists() && requestedResource.isReadable) {
                requestedResource
            } else {
                // 그 외 경우(React Router 등) index.html 반환
                // 단, API 요청은 제외
                if (isApiRequest(resourcePath)) {
                    null // API 요청은 404 처리 (컨트롤러로 위임)
                } else {
                    ClassPathResource("/static/index.html")
                }
            }
        }

        private fun isApiRequest(path: String): Boolean {
            return path.startsWith("api/") ||
                   path.startsWith("actuator/") ||
                   path.endsWith(".js") ||
                   path.endsWith(".css") ||
                   path.endsWith(".json") ||
                   path.endsWith(".png") ||
                   path.endsWith(".jpg") ||
                   path.endsWith(".svg") ||
                   path.endsWith(".ico")
        }
    }
}
