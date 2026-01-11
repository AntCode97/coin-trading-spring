rootProject.name = "coin-trading-spring"

// 통합 서버 (새 아키텍처)
include("coin-trading-server")

// 레거시 모듈 (마이그레이션 후 제거 예정)
// include("coin-mcp-server")
// include("coin-mcp-client")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}
