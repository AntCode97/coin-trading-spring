# coin-trading-server Dockerfile
# 통합 트레이딩 서버 (MCP + 자동 매매)

# Build stage
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

# Copy gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY coin-trading-server/build.gradle.kts coin-trading-server/

# Install git (required for build-info)
RUN apk add --no-cache git

# Copy source and .git (for build-info git metadata)
COPY coin-trading-server/src coin-trading-server/src
COPY .git .git

# Download dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Build
RUN ./gradlew :coin-trading-server:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy jar
COPY --from=builder /app/coin-trading-server/build/libs/*.jar app.jar

# Environment
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
