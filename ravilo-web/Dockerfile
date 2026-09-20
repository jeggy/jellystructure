# syntax=docker/dockerfile:1
#
# FR-167-5 — standalone image for Ravilo web, additional to (not a replacement for) the existing
# RAVILO_WEB_DIR in-backend serving at /tv/** (see the main Dockerfile). Built from the repo root so it
# can reach the shared/ravilo-ui/ravilo-web modules exactly like the main Dockerfile does —
# `docker build -f ravilo-web/Dockerfile .` from the repo root, not from ravilo-web/.
#
# A plain Kotlin service, no reverse-proxy technology baked in — the operator fronts it with whatever
# they already run (Caddy, nginx, a tunnel, nothing). The static-serving half is :web-static-server, a
# tiny standalone linuxX64 Ktor module built alongside the wasm bundle in this same build stage.
#
# The wasmJsBrowserDistribution output is genuinely self-contained (verified 2026-08-17: skiko.wasm is
# included content-hashed, no absolute/external refs in index.html, fonts bundled via composeResources)
# so :web-static-server has no backend reachability requirement baked in either.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Same Node.js + libatomic1 setup as the main Dockerfile's builder stage (webpack + Gradle's own
# Kotlin/Wasm npm tooling) — see that file's comment for why both are needed. No libsqlite3-dev here:
# this build never touches the main linuxX64 executable{} target.
#   - brotli (FR-235-2): :precompressWasmJsDistribution shells out to this CLI for .br siblings when
#     it's on PATH, silently skipping them (gzip-only) otherwise.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libatomic1 brotli && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
# settings.gradle.kts configures the whole project graph before running any task (see the main
# Dockerfile's identical comment) — every included module's directory must exist even though this build
# only ever runs :ravilo-web and :web-static-server tasks.
COPY shared ./shared
COPY ravilo-ui ./ravilo-ui
COPY ravilo-web ./ravilo-web
# R245 added :ravilo-cast (and R264 :ravilo-screen/:ravilo-receiver-core) to settings.gradle.kts; without
# their directories here Gradle refuses to configure the graph at all ("Configuring project ':ravilo-cast'
# without an existing directory is not allowed"), which turned publish.yml and ci.yml red on 2026-09-16.
# Never built by this image, only configured.
COPY ravilo-cast ./ravilo-cast
COPY ravilo-screen ./ravilo-screen
COPY ravilo-receiver-core ./ravilo-receiver-core
# R279 — :ravilo-i18n is in that same unconditional list, and `i18n/` is its input: the whole Ravilo
# string table is generated from i18n/*.json at build time, so the module directory has to exist for
# Gradle to configure and the JSON has to exist for the generator to run. Missing them fails the
# build at configuration with "Configuring project ':ravilo-i18n' without an existing directory".
COPY ravilo-i18n ./ravilo-i18n
COPY i18n ./i18n
COPY web-static-server ./web-static-server
COPY design ./design
RUN --mount=type=cache,id=gradle-shared,sharing=locked,target=/root/.gradle \
    --mount=type=cache,id=konan-shared,sharing=locked,target=/root/.konan \
    --mount=type=cache,id=npm-shared,sharing=locked,target=/root/.npm \
    ./gradlew :ravilo-web:dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,id=gradle-shared,sharing=locked,target=/root/.gradle \
    --mount=type=cache,id=konan-shared,sharing=locked,target=/root/.konan \
    --mount=type=cache,id=npm-shared,sharing=locked,target=/root/.npm \
    ./gradlew :ravilo-web:wasmJsBrowserDistribution :web-static-server:linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
RUN adduser --system --uid 1000 webstatic

COPY --from=builder --chmod=755 --chown=webstatic \
    /app/web-static-server/build/bin/linuxX64/releaseExecutable/web-static-server.kexe /app/web-static-server
COPY --from=builder --chown=webstatic /app/ravilo-web/build/dist/wasmJs/productionExecutable/ /srv/

USER webstatic
EXPOSE 8080

ENV STATIC_DIR=/srv
ENV SERVER_PORT=8080
# Phase 224 (FR-224-6) — same as the main image: the published version, injected into index.html by
# web-static-server as window.__RAVILO_VERSION__ (R252 reads it). After the compile layers on purpose.
ARG BUILD_VERSION=dev
ENV RAVILO_VERSION=$BUILD_VERSION

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ || exit 1

CMD ["/app/web-static-server"]
