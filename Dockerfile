# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    ./gradlew linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /app/build/bin/linuxX64/releaseExecutable/jellystructure.kexe /app/jellystructure
RUN chmod +x /app/jellystructure
EXPOSE 9505
CMD ["/app/jellystructure"]
