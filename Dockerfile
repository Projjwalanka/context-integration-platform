# =============================================================================
# Multi-stage Dockerfile for AI Assistant POC
# Stage 1: build (Maven + Node/npm for React)
# Stage 2: runtime (slim JRE)
# =============================================================================

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Install Node.js 20 (for React build via frontend-maven-plugin)
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

# Cache Maven dependencies first
COPY pom.xml .
RUN --mount=type=cache,id=s/e9e985be-b855-42e2-b20e-bb94dc3ccc0d-/root/.m2,target=/root/.m2 \
    mvn dependency:go-offline -B --no-transfer-progress 2>/dev/null || true

# Copy source
COPY src ./src
COPY frontend ./frontend

# Build (Maven builds React then packages everything)
RUN --mount=type=cache,id=s/e9e985be-b855-42e2-b20e-bb94dc3ccc0d-/root/.m2,target=/root/.m2 \
    mvn clean package -DskipTests -B --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

# Non-root user for security
RUN groupadd --system aiapp && useradd --system --gid aiapp aiapp

# Install curl for health-check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Copy fat JAR
COPY --from=builder /build/target/ai-assistant-poc-*.jar app.jar

# Artifact storage
RUN mkdir -p /app/artifacts && chown -R aiapp:aiapp /app

USER aiapp

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --retries=5 --start-period=60s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
