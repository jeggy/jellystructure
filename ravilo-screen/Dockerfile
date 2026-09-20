# syntax=docker/dockerfile:1
#
# Phase 247 (test-stack only — this image is never published; the real Tizen artefact stays
# deploy-tizen-tv.yml's job, Phase R272). Gives `ravilo-screen` a real, second HTTP origin inside
# docker-compose.test.yml, the same way ravilo-web/Dockerfile already gives the Ravilo web app one — so
# an e2e test can load the actual sideloaded-widget bundle from an origin that is NOT the backend's own,
# and observe for real whether its cross-origin calls are CORS-refused (phase 247's headline finding).
#
# Built from the repo root, exactly like ravilo-web/Dockerfile, for the same reason: settings.gradle.kts
# configures the WHOLE module graph before running any task, so every included module's directory must
# exist even though this build only ever runs :ravilo-screen and :web-static-server tasks.
#   docker build -f ravilo-screen/Dockerfile .

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Same Node.js setup as ravilo-web/Dockerfile — :ravilo-screen is Kotlin/JS (IR) too, and its
# jsBrowserProductionWebpack step needs it. No brotli here: precompression (FR-235-2) is a ravilo-web/
# R263 thing this module has no equivalent task for.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libatomic1 && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
# Every module settings.gradle.kts includes must have a directory present for Gradle to configure the
# graph at all, regardless of which task actually runs — see ravilo-web/Dockerfile's identical comment.
COPY shared ./shared
COPY ravilo-ui ./ravilo-ui
COPY ravilo-web ./ravilo-web
COPY ravilo-cast ./ravilo-cast
COPY ravilo-screen ./ravilo-screen
COPY ravilo-receiver-core ./ravilo-receiver-core
COPY web-static-server ./web-static-server
COPY design ./design
RUN --mount=type=cache,id=gradle-shared,sharing=locked,target=/root/.gradle \
    --mount=type=cache,id=konan-shared,sharing=locked,target=/root/.konan \
    --mount=type=cache,id=npm-shared,sharing=locked,target=/root/.npm \
    ./gradlew :ravilo-screen:dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,id=gradle-shared,sharing=locked,target=/root/.gradle \
    --mount=type=cache,id=konan-shared,sharing=locked,target=/root/.konan \
    --mount=type=cache,id=npm-shared,sharing=locked,target=/root/.npm \
    ./gradlew :ravilo-screen:syncScreenReceiver :web-static-server:linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
RUN adduser --system --uid 1000 webstatic

COPY --from=builder --chmod=755 --chown=webstatic \
    /app/web-static-server/build/bin/linuxX64/releaseExecutable/web-static-server.kexe /app/web-static-server
# ravilo-screen/wgt/ also carries config.xml + icon.png (Tizen packaging metadata, harmless to serve
# alongside the two files that matter) — :syncScreenReceiver writes ravilo-screen.js into this same
# directory, so the COPY above already regenerated it fresh; index.html is checked into git as-is.
COPY --from=builder --chown=webstatic /app/ravilo-screen/wgt/ /srv/

USER webstatic
EXPOSE 8080

ENV STATIC_DIR=/srv
ENV SERVER_PORT=8080
# No DEFAULT_SERVER_URL — the whole point of this image is that ravilo-screen has none baked in
# (R269's runServerSetup() is what the test drives).

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ || exit 1

CMD ["/app/web-static-server"]
