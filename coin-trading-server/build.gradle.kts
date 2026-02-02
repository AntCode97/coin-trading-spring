plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.github.node-gradle.node") version "7.1.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Spring AI BOM (1.1.2 - Spring Boot 3.5.x 호환)
    implementation(platform("org.springframework.ai:spring-ai-bom:1.1.2"))

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Spring AI MCP Server
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Spring AI Model Providers (starter - Spring Boot 3.5.x 호환)
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    // Google GenAI는 project-id 필수 문제로 비활성화

    // MySQL
    runtimeOnly("com.mysql:mysql-connector-j")

    // JSON Processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // JWT for Bithumb Private API
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Environment Variables (.env 파일 로드)
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // Slack Logging (logback-slack-appender)
    implementation("com.github.maricn:logback-slack-appender:1.4.0")

    // Configuration Processor
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")  // 테스트용 인메모리 DB
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // CI 환경에서는 통합 테스트 제외 (빗썸 API IP 제한)
    val isCI = System.getenv("CI")?.toBoolean() ?: false
    if (isCI) {
        systemProperty("junit.platform.tags.exclude", "integration")
    }
}

// bootRun 시 루트 디렉토리에서 실행 (.env 파일 읽기 위함)
// Spring Boot build-info 생성 (git 메타데이터 포함)
springBoot {
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir

    // .env 파일에서 환경변수 로드
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}

// ===========================================
// React Frontend Build Integration
// ===========================================

// Node.js 설정
configure<com.github.gradle.node.nodetask.NodeExtension> {
    download.set(true)
    version.set("20.18.0")
    workDir.set(file("${rootProject.projectDir}/coin-trading-client"))
    nodeProjectDir.set(file("${rootProject.projectDir}/coin-trading-client"))
}

// React 설치 태스크
tasks.register<com.github.gradle.node.nodetask.NodeTask>("installReact") {
    dependsOn("nodeSetup")
    description = "Install React dependencies"
    workingDir.set(file("${rootProject.projectDir}/coin-trading-client"))
    args.set(listOf("install"))
}

// React 빌드 태스크
tasks.register<com.github.gradle.node.nodetask.NodeTask>("buildReact") {
    dependsOn("installReact")
    description = "Build React frontend"
    workingDir.set(file("${rootProject.projectDir}/coin-trading-client"))
    args.set(listOf("run", "build"))

    doLast {
        // 빌드 결과물을 Spring static 폴더로 복사
        val reactBuildDir = file("${rootProject.projectDir}/coin-trading-client/dist")
        val staticDir = file("${projectDir}/src/main/resources/static")

        // 기존 static 폴더 삭제
        delete(staticDir)

        // static 폴더 생성
        staticDir.mkdirs()

        // React 빌드 결과물 복사
        copy {
            from(reactBuildDir)
            into(staticDir)
        }

        println("React build completed and copied to ${staticDir.absolutePath}")
    }
}

// processResources가 React 빌드 후 실행되도록 설정
tasks.named<ProcessResources>("processResources") {
    dependsOn("buildReact")
}

// 개발용: React 빌드 스킵 (gradlew build -x buildReact)
tasks.named("buildReact") {
    onlyIf { !project.hasProperty("skipReact") }
}
