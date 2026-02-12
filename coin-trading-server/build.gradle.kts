plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
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

// 프론트엔드는 별도 배포되므로 서버 JAR에 정적 SPA 파일을 포함하지 않음
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    // src/main/resources/static/** 제외
    exclude("static/**")
}
