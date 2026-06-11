# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Install Node.js for Kotlin/Wasm webpack build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew linkReleaseExecutableLinuxX64 wasmJsBrowserDistribution --no-daemon

FROM debian:bookworm-slim
WORKDIR /app

RUN adduser --system --uid 1000 jellystructure

COPY --from=builder /app/build/bin/linuxX64/releaseExecutable/jellystructure.kexe /app/jellystructure
COPY --from=builder /app/build/dist/wasmJs/productionExecutable/ /app/frontend/

RUN chmod +x /app/jellystructure && chown -R jellystructure /app

USER jellystructure
EXPOSE 9505

ENV FRONTEND_DIR=/app/frontend
ENV CONFIG_FILE=/config/config.toml
ENV SESSIONS_FILE=/config/sessions.json
ENV SERVER_PORT=9505

CMD ["/app/jellystructure"]
