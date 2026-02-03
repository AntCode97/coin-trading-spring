plugins {
    java
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.spring") version "2.3.0" apply false
    kotlin("plugin.jpa") version "2.3.0" apply false
    id("org.springframework.boot") version "3.5.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.ant.coinmcp"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

// ============================================
// React 빌드 태스크 (루트 프로젝트)
// ============================================

tasks.register("buildReact") {
    group = "build"
    description = "Build React SPA client"

    val reactDir = file("${rootProject.projectDir}/coin-trading-client")
    val nodeModulesDir = file("${reactDir}/node_modules")

    // node_modules가 없으면 npm install 실행
    doFirst {
        if (!nodeModulesDir.exists()) {
            exec {
                workingDir = reactDir
                commandLine = listOf("npm", "install")
            }
        }
    }

    // React 빌드 실행
    doLast {
        exec {
            workingDir = reactDir
            commandLine = listOf("npm", "run", "build")
        }
    }
}
