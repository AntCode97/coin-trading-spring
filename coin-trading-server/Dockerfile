# coin-trading-server Dockerfile
# Multi-stage build: React Frontend + Spring Boot Backend

# ===========================================
# Stage 1: Build React Frontend
# ===========================================
FROM node:20.18.0-alpine AS react-builder

WORKDIR /app

# Copy React project files
COPY coin-trading-client/package*.json ./
COPY coin-trading-client/package-lock.json* ./

# Install dependencies
RUN npm ci

# Copy React source and build
COPY coin-trading-client ./
RUN npm run build

# ===========================================
# Stage 2: Build Spring Boot Backend + React
# ===========================================
FROM eclipse-temurin:25-jdk-alpine AS backend-builder

WORKDIR /app

# Copy gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY coin-trading-server/build.gradle.kts coin-trading-server/

# Install git (required for build-info)
RUN apk add --no-cache git

# Copy React build output (already built in Stage 1)
COPY --from=react-builder /app/dist coin-trading-client/dist/

# Copy source and .git (for build-info git metadata)
COPY coin-trading-server/src coin-trading-server/src
COPY .git .git

# Create static directory and copy React build
RUN mkdir -p coin-trading-server/src/main/resources/static && \
    cp -r coin-trading-client/dist/* coin-trading-server/src/main/resources/static/

# Download dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Build Spring Boot
RUN ./gradlew :coin-trading-server:bootJar --no-daemon -x test

# ===========================================
# Stage 3: Runtime
# ===========================================
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy jar (includes React static files)
COPY --from=backend-builder /app/coin-trading-server/build/libs/*.jar app.jar

# Environment
# 힙 1GB, GC 명시하지 않고 JVM 판단에 맡김
# (Java 25 기본 G1 GC, 힙 크기에 따라 자동 조절)
ENV JAVA_OPTS="-Xms512m -Xmx1024m"
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
