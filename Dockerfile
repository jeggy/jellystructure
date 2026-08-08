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
# settings.gradle.kts unconditionally includes :shared, :ravilo-ui, :ravilo-web, and :ravilo-tizen
# (the Android-only modules stay out since no Android SDK is present here) -- Gradle configures
# the WHOLE project graph before running any task, so every included module's directory has to
# exist before even a dependency-resolution pass, let alone the real build below. Without these,
# ./gradlew fails immediately with "Configuring project ':shared' without an existing directory".
COPY shared ./shared
COPY ravilo-ui ./ravilo-ui
COPY ravilo-web ./ravilo-web
COPY ravilo-tizen ./ravilo-tizen
# wasmJsBrowserDistribution's doLast block (build.gradle.kts) copies wf.css/app.css/detail.css/
# metadata.css/seeding.css/seeding.js + flags.css/flags/** from design/ into the production dist
# so the admin frontend ships styled -- Gradle's Copy task silently no-ops when its `from` source
# doesn't exist rather than failing the build, so without this the whole app renders completely
# unstyled (every settings-tab section visible at once, since the CSS that hides inactive tabs
# never loads) with no build-time error to point at the cause.
COPY design ./design
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew linkReleaseExecutableLinuxX64 --no-daemon
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew wasmJsBrowserDistribution --no-daemon

FROM debian:bookworm-slim
WORKDIR /app

# libsqlite3-0 (runtime library, not the -dev package needed only at link time in the builder
# stage) -- the binary dynamically links libsqlite3.so.0 and fails to even start without it.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg mkvtoolnix wget libsqlite3-0 && \
    rm -rf /var/lib/apt/lists/*

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
