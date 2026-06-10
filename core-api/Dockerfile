# syntax=docker/dockerfile:1

# ---- Stage 1: build the bootJar with the Gradle wrapper on a JDK 21 base ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy the wrapper first so dependency resolution can cache across source-only changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# Copy sources and build. Tests need a live Postgres (Testcontainers), so skip them
# here — the compose smoke check verifies the running app end-to-end instead.
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- Stage 2: run the jar on a slim JRE 21 base ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl is not in the base image; the compose healthcheck probes /actuator/health with it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN useradd --system --uid 10001 --create-home appuser
USER appuser

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
