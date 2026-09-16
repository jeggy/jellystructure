# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Install Node.js (for Ravilo-web's own webpack build) plus two libraries the bare builder image
# is otherwise missing:
#   - libsqlite3-dev: the linuxX64 executable{} target links against -lsqlite3 (build.gradle.kts:33,
#     SQLDelight-generated code) via -L/usr/lib/x86_64-linux-gnu -- works on a dev machine that
#     already has sqlite3 installed, but ld.lld fails with "unable to find library -lsqlite3"
#     without it. Needs the -dev package specifically (not just the runtime -0 one) for the
#     unversioned libsqlite3.so symlink -lsqlite3 resolves against at link time.
#   - libatomic1: Gradle downloads its OWN Node.js (v25, separate from the apt-installed one below,
#     used for Kotlin/Wasm's yarn/npm tooling) which dynamically links libatomic.so.1 -- absent from
#     this minimal image, so kotlinWasmNpmInstall fails with "error while loading shared libraries".
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libsqlite3-dev libatomic1 && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
# settings.gradle.kts unconditionally includes :shared, :ravilo-ui, :ravilo-web, :ravilo-tizen, and
# (FR-167-5) :web-static-server (the Android-only modules stay out since no Android SDK is present
# here) -- Gradle configures the WHOLE project graph before running any task, so every included
# module's directory has to exist before even a dependency-resolution pass, let alone the real build
# below. Without these, ./gradlew fails immediately with "Configuring project ':shared' without an
# existing directory".
COPY shared ./shared
COPY ravilo-ui ./ravilo-ui
COPY ravilo-web ./ravilo-web
COPY ravilo-tizen ./ravilo-tizen
COPY ravilo-cast ./ravilo-cast
COPY cast-receiver ./cast-receiver
COPY web-static-server ./web-static-server
# wasmJsBrowserDistribution's doLast block (build.gradle.kts) copies wf.css/app.css/detail.css/
# metadata.css/seeding.css/seeding.js + flags.css/flags/** from design/ into the production dist
# so the admin frontend ships styled -- Gradle's Copy task silently no-ops when its `from` source
# doesn't exist rather than failing the build, so without this the whole app renders completely
# unstyled (every settings-tab section visible at once, since the CSS that hides inactive tabs
# never loads) with no build-time error to point at the cause.
COPY design ./design
# FR-167-5 — explicit cache mount `id=`s (not just `target=`) so building this image and
# ravilo-web/Dockerfile concurrently doesn't collide on the same shared BuildKit cache storage: two
# Gradle processes writing the same unnamed /root/.gradle cache hit Gradle's own file lock
# (journal-1.lock) and one build fails outright. Confirmed live building both images at once.
RUN --mount=type=cache,id=gradle-jellystructure,target=/root/.gradle \
    --mount=type=cache,id=konan-jellystructure,target=/root/.konan \
    --mount=type=cache,id=npm-jellystructure,target=/root/.npm \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,id=gradle-jellystructure,target=/root/.gradle \
    --mount=type=cache,id=konan-jellystructure,target=/root/.konan \
    --mount=type=cache,id=npm-jellystructure,target=/root/.npm \
    ./gradlew linkReleaseExecutableLinuxX64 --no-daemon
# R245 — the Chromecast receiver bundle (Kotlin/JS), dropped next to cast-receiver/index.html.
RUN --mount=type=cache,id=gradle-jellystructure,target=/root/.gradle \
    --mount=type=cache,id=konan-jellystructure,target=/root/.konan \
    --mount=type=cache,id=npm-jellystructure,target=/root/.npm \
    ./gradlew :ravilo-cast:syncCastReceiver --no-daemon
RUN --mount=type=cache,id=gradle-jellystructure,target=/root/.gradle \
    --mount=type=cache,id=konan-jellystructure,target=/root/.konan \
    --mount=type=cache,id=npm-jellystructure,target=/root/.npm \
    ./gradlew wasmJsBrowserDistribution --no-daemon

FROM debian:bookworm-slim
WORKDIR /app

# libsqlite3-0 (runtime library, not the -dev package needed only at link time in the builder
# stage) -- the binary dynamically links libsqlite3.so.0 and fails to even start without it.
# libchromaprint-tools (fpcalc) -- FR-167-2. FfmpegRunner.computeFingerprint shells out to fpcalc for
# cross-episode intro/credits fingerprinting (Phase 150/159) and documents it as "must be present on
# PATH", but it was missing here. It fails closed (returns null, never throws), so without this the
# feature doesn't error -- it just silently stops detecting anything, with zero symptoms.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg mkvtoolnix wget libsqlite3-0 libchromaprint-tools && \
    rm -rf /var/lib/apt/lists/*

RUN adduser --system --uid 1000 jellystructure

# --chmod/--chown on the COPY itself (FR-167-2) instead of a separate RUN chmod/chown layer -- the old
# form duplicated the whole copied content (33MB) into a second layer just to flip permissions.
COPY --from=builder --chmod=755 --chown=jellystructure /app/build/bin/linuxX64/releaseExecutable/jellystructure.kexe /app/jellystructure
COPY --from=builder --chown=jellystructure /app/build/dist/wasmJs/productionExecutable/ /app/frontend/
# Phase 218 (FR-218-1) — the Chromecast receiver bundle, served at /cast/ (a plain static directory).
COPY --chown=jellystructure cast-receiver/ /app/cast/

USER jellystructure
EXPOSE 9505

ENV FRONTEND_DIR=/app/frontend
ENV CAST_DIR=/app/cast
ENV CONFIG_FILE=/config/config.toml
ENV SESSIONS_FILE=/config/sessions.json
# FR-167-2 — real bug found live-testing this image as its own non-root USER (the default; also what
# docker-compose.yml's `user: "${PUID:-1000}:${PGID:-1000}"` runs as): DB_FILE had no default here,
# so it fell back to Main.kt's "./data/jellystructure.db" -- relative to WORKDIR /app, which is owned
# by root and not writable by uid 1000. First boot crashed outright on `mkdir failed: Permission
# denied` before ever reaching the HTTP listener. docker-compose.test.yml never caught this because it
# runs the container as root (`user: "0:0"`). ACTIVITY_LOG_FILE derives its own default from DB_FILE's
# directory (Main.kt), so this one variable fixes both.
ENV DB_FILE=/config/jellystructure.db
ENV SERVER_PORT=9505

# /api/health is deliberately unauthenticated (a liveness probe for exactly this) -- see
# AuthPlugin.kt's exact-match carve-out.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:9505/api/health || exit 1

CMD ["/app/jellystructure"]
