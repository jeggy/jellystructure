# Phase 167 — Docker packaging, CSP fixes, and versioned GHCR publishing

> Requested 2026-08-17: *"Let's investigate what's needed to put the jellystructure backend on the
> internet. We want to build docker images that can be hosted on our private github repo… Would be nice
> to provide two images, one for jellystructure… And then an additional image for the ravilo web app."*
> Followed by: *"Let's create specs for anything missing and then after implementing those specs, let's
> publish docker images to github as part of the CI setup."* Followed by: *"we need to start doing
> versions… Let's not use Semver, but rather a 2 digit number. Starting with 1.0."*

**Status:** Planned — dev-authored, not yet built.

## 1. What's there now

Full investigation: `specs/research-reports/internet-exposure-docker-packaging-2026-08-17.md`. Summary of
what it found (all confirmed live, not just read from source):

- `Dockerfile` already builds the backend + admin frontend into one working image; CI already builds it
  on every run via `docker-compose.test.yml`. **No changes needed to the multi-stage structure itself.**
- The 2026-08-02 security review's 21 in-app findings are genuinely fixed — C1 (path-decoding auth
  bypass), C2 (arbitrary file read), H1 (`/ws` unauthenticated) all re-verified live this session.
- `RAVILO_WEB_DIR` already exists and serves Ravilo web under `/tv/**` from inside the same backend
  image. The requested *second* image is additional to this, not a replacement — see FR-167-5.
- `:ravilo-web:wasmJsBrowserDistribution` produces a genuinely self-contained 17 MB static bundle
  (verified by building it this session): `skiko.wasm` is included content-hashed, no absolute/external
  refs in `index.html`, fonts bundled via `composeResources`.
- Four gaps stand between this and a real publish, found and verified live:
  1. **`fpcalc` (Chromaprint) is missing from the runtime image.** `FfmpegRunner.kt:409` documents it as
     required; it silently degrades (`computeFingerprint` returns `null` on failure) rather than erroring,
     so cross-episode intro-fingerprint detection would quietly stop working with zero symptoms.
  2. **No `.dockerignore`.** Build context is 3.4 GB and includes `config/` — live secrets (Jellyfin
     admin token, TMDB key, *arr keys) and `jellystructure.db` (plaintext session tokens). Not leaking
     today (the Dockerfile only `COPY`s specific paths), but one future `COPY . .` bakes it into a
     published layer permanently.
  3. **The CSP the M8 security fix added has never been checked in a real browser and is wrong.**
     Verified live against the running instance:
     - `wf.css:10` `@import`s Space Grotesk/Sora/JetBrains Mono from `fonts.googleapis.com`. The CSP's
       `style-src 'self'` and `font-src 'self' data:` **block both the stylesheet and the font files
       right now** — the admin UI's entire documented type system is silently falling back to system
       fonts in any CSP-enforcing browser, in production, today.
     - There is no `frame-src` directive, so it inherits `default-src 'self'`. Ravilo's YouTube/Vimeo
       trailer `<iframe>` (`TrailerEmbed.kt`, R163) will be refused the moment a CSP-enforcing browser
       hits it — including today, since the CSP header is already present on `/tv/**`.
  4. **No publish workflow exists**, and `ghcr.io` appears nowhere in the repo.

## 2. Functional requirements

### FR-167-1 — `.dockerignore`

New root `.dockerignore`:

```
config/
build/
.git/
.gradle/
.kotlin/
node_modules/
**/build/
**/node_modules/
ravilo-android/
ravilo-phone/
towo-runner/
tests/
*.log
backend.log
qodana-baseline.sarif.json
.qodana/
```

Confirms via `docker build` that the reported context size drops from 3.4 GB to well under 200 MB (the
`COPY` lines in `Dockerfile` already hand-pick `shared/ravilo-ui/ravilo-web/ravilo-tizen/design/src`, so
this is pure hygiene — it should change nothing about what actually lands in the image, only what's sent
to the daemon and what a future careless `COPY .` could accidentally include).

### FR-167-2 — Dockerfile hardening

In the existing `Dockerfile`, runtime stage:

1. Add `libchromaprint-tools` (provides `fpcalc`) to the `apt-get install` line alongside
   `ffmpeg mkvtoolnix wget libsqlite3-0`.
2. Replace the separate `RUN chmod +x /app/jellystructure && chown -R jellystructure /app` layer with
   `COPY --chmod=755 --chown=jellystructure` on both `COPY --from=builder` lines, so the permission
   change doesn't duplicate the copied content into a second layer.
3. Add a `HEALTHCHECK` hitting the existing unauthenticated `/api/health`:
   ```dockerfile
   HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
     CMD wget -qO- http://127.0.0.1:9505/api/health || exit 1
   ```
   (`wget` is already installed for this exact purpose in `docker-compose.test.yml`'s healthcheck.)
4. **Real defect found live-testing this image** (running it as its own non-root `USER jellystructure`
   — the default, and what `docker-compose.yml`'s `user: "${PUID:-1000}:${PGID:-1000}"` runs as, unlike
   `docker-compose.test.yml`'s `user: "0:0"`): `DB_FILE` had no env default set here, so it fell back to
   `Main.kt`'s `./data/jellystructure.db` — relative to `WORKDIR /app`, root-owned, not writable by
   uid 1000. First boot crashed outright (`mkdir failed: Permission denied`) before the HTTP listener
   ever came up — the CI test stack never caught it because it runs as root. Add
   `ENV DB_FILE=/config/jellystructure.db` alongside the existing `CONFIG_FILE`/`SESSIONS_FILE` — this
   also fixes `ACTIVITY_LOG_FILE`, which derives its own default from `DB_FILE`'s directory.

Not doing in this pass (explicitly out of scope, see §3): multi-arch builds, distroless/smaller base
image, stripping the ffmpeg dependency tree down.

### FR-167-3 — Self-hosted fonts, CSP tightened back down

Space Grotesk, Sora, and JetBrains Mono move from a Google Fonts `@import` to locally-hosted `woff2`
files, matching the self-hosting Ravilo's own bundle already does for two of the three
(`ravilo-ui`'s `composeResources/font/`).

- Fetch the exact weights currently requested (`Sora 400/500/600/700`, `Space Grotesk 500/600/700`,
  `JetBrains Mono 400/500/600` — 10 files, `latin` subset, `woff2`) from Google Fonts' public CDN.
- Add them under `design/app/fonts/`.
- Replace `wf.css:10`'s `@import` with local `@font-face` rules pointing at `fonts/<file>.woff2`,
  `font-display: swap` (matching today's `&display=swap`).
- Wire `design/app/fonts/**` into both `syncDesignAssets` (dev) and `wasmJsBrowserDistribution`'s
  `doLast` copy block (prod) in `build.gradle.kts`, alongside the existing `flags/**` pattern — same
  mechanism, new directory.
- **Add a `scripts/check-mobile-css.sh` fence entry** for the new `@font-face` block, per this repo's
  established rule (`feedback-design-sync-wipes-css-fixes` — a `design/app/*.css` edit with no matching
  fence line is one design-project sync away from silently disappearing, and this has happened eight
  times already). Also update `CLAUDE.md`'s design-constraints notes if the font-loading strategy is
  worth recording there.
- Drop the now-unneeded external allowances: CSP's `style-src`/`font-src` stay `'self'`/`'self' data:'`
  (no `fonts.googleapis.com`/`fonts.gstatic.com` addition needed) — this is the whole point of
  self-hosting over allowlisting: one fewer third-party dependency for an internet-facing service, and
  `default-src 'self'` stays meaningfully true.

### FR-167-4 — CSP `frame-src` for trailer embeds

Add to the CSP header in `Server.kt`:

```
frame-src https://www.youtube-nocookie.com https://player.vimeo.com;
```

Matching exactly the two origins `TrailerEmbed.kt`'s `trailerEmbedUrl()` ever constructs.

### FR-167-5 — New `ravilo-web` image: a plain Kotlin service, no baked-in reverse proxy

**Revised mid-implementation (2026-08-17)**, per explicit correction: *"instead of caddy, we just want
simple Kotlin services that people on their own can put behind caddy or whatever."* Both images are
Kotlin services with no reverse-proxy technology baked in — the operator fronts them with whatever they
already run (Caddy, nginx, a tunnel, nothing). The original Caddy-based sketch below is **superseded**;
kept struck through for the record rather than deleted, since the reasoning ("self-contained bundle,
verified live") still stands and just moves to a different server.

New minimal module **`:web-static-server`** — linuxX64 only, Ktor CIO, no dependency on the root
project's backend (which would drag in SQLDelight/sqlite, `ktor-client-curl`, config/media logic — far
too heavy for "serve some static files"). Reuses the exact serving logic already proven in
`Server.kt`'s `serveFrontendFile`/`serveStaticBytes`/`contentTypeFor` (path-traversal guard, ETag,
per-extension content type, `no-cache` on `index.html` vs. `max-age=3600, must-revalidate` elsewhere) —
duplicated rather than shared, deliberately: this new module's whole point is having no dependency on the
big root project, and ~70 lines of stable, already-tested logic is a reasonable place to accept
duplication over that coupling.

```kotlin
// web-static-server/src/linuxX64Main/kotlin/dev/jellystructure/webstatic/Main.kt (shape, not final)
fun main() {
    val dir = env("STATIC_DIR", "/srv")
    val port = env("SERVER_PORT", "8080").toIntOrNull() ?: 8080
    embeddedServer(CIO, configure = { connectors.add(EngineConnectorBuilder().apply { this.port = port }) }) {
        routing { get("{...}") { call.serveStaticFile(dir, call.request.path()) } }
    }.start(wait = true)
}
```

Generic on purpose (`STATIC_DIR`/`SERVER_PORT` env vars, same naming convention as the main backend) —
not hardcoded to Ravilo, so the same tiny binary is reusable if another static bundle ever needs the same
treatment.

`ravilo-web/Dockerfile` becomes two-stage, both stages plain Kotlin/JDK, no `caddy:2-alpine`:

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libatomic1 && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY shared ./shared
COPY ravilo-ui ./ravilo-ui
COPY ravilo-web ./ravilo-web
COPY ravilo-tizen ./ravilo-tizen
COPY web-static-server ./web-static-server
COPY design ./design
COPY src ./src
RUN --mount=type=cache,id=gradle-ravilo-web,target=/root/.gradle \
    --mount=type=cache,id=konan-ravilo-web,target=/root/.konan \
    --mount=type=cache,id=npm-ravilo-web,target=/root/.npm \
    ./gradlew :ravilo-web:wasmJsBrowserDistribution :web-static-server:linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim
RUN adduser --system --uid 1000 webstatic
COPY --from=builder --chmod=755 --chown=webstatic \
    /app/web-static-server/build/bin/linuxX64/releaseExecutable/web-static-server.kexe /app/web-static-server
COPY --from=builder --chown=webstatic /app/ravilo-web/build/dist/wasmJs/productionExecutable/ /srv/
USER webstatic
EXPOSE 8080
ENV STATIC_DIR=/srv
ENV SERVER_PORT=8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ || exit 1
CMD ["/app/web-static-server"]
```

No `wget` needed in the runtime base beyond what `debian:bookworm-slim` — matching the main Dockerfile,
add it explicitly (`apt-get install wget`) since the healthcheck needs it and the slim base doesn't ship
one.

This stays **standalone-servable** — the whole reason the investigation checked the bundle has no
external refs — independent of whether it ends up deployed standalone or the existing `RAVILO_WEB_DIR`
in-backend path is used instead. That remains a deployment-time choice (§3).

~~Caddy-based sketch (superseded, kept for the record):~~ a `caddy:2-alpine` runtime stage with a baked-in
`ravilo-web/Caddyfile` doing gzip/zstd + immutable caching + `Content-Type: application/wasm`. Dropped
because it bakes a specific reverse-proxy technology into the image, which the operator should own.

Drop `ravilo.js.map` (1.7 MB source map) from what ships — added a
`ravilo-web/webpack.config.d/production-no-sourcemap.js` override (`config.devtool = false` when
`config.mode === 'production'`), scoped so `./gradlew runDev`'s dev server keeps source maps.

### FR-167-6 — Two-part version numbers, not SemVer

Plain `MAJOR.MINOR` — two integers, dot-separated, no patch segment, no pre-release/build metadata
suffixes. Starts at **`1.0`**. Next after that is an explicit choice at release time (`1.1` for a small
change, `2.0` for a breaking one) — not automated, not derived from commit history.

The **git tag is the single source of truth** — no `VERSION` file to keep in sync. Tag format: `v1.0`,
`v1.1`, `v2.0`, matched by the publish workflow via the regex `^v[0-9]+\.[0-9]+$`. A tag outside that
shape (e.g. an accidental `v1.0.1` or `v1.0-beta`) does not trigger a publish — see FR-167-7's job-level
`if`.

### FR-167-7 — GHCR publish workflow

New `.github/workflows/publish.yml`:

- **Trigger — revised mid-implementation (2026-08-17), per explicit correction**: `release: types:
  [published]`, not a bare tag push. *"I think we should change it, so the building images and storing
  them in github, will only be a part of a github release. So I should manually go in and create a
  release and then the CI will do it's work."* The version is read from `github.event.release.tag_name`,
  filtered to the `v[0-9]+.[0-9]+` shape (FR-167-6) so a malformed tag fails loudly instead of silently
  no-op'ing or publishing something wrong. Also `workflow_dispatch` with a required `version` input (same
  shape, validated the same way, checks out `v<version>`), for re-publishing an existing release's images
  without cutting a new one. A bare `git tag` + `git push` no longer triggers anything — only actually
  publishing a release through the GitHub UI (or `gh release create`) does.
- **Does not trigger on every push to `main`, or on a tag push alone.** `ci.yml`'s existing
  build-and-test-only Docker build (via `docker-compose.test.yml`) already validates every commit builds;
  this workflow is publish-only and gated behind a deliberate release action, per the user's "not always
  just pushing at latest" instruction, tightened further from "any version tag" to "a published release."
- **Two images built and pushed, both from this one workflow:**
  - `ghcr.io/jeggy/jellystructure` (existing `Dockerfile`)
  - `ghcr.io/jeggy/ravilo-web` (new `ravilo-web/Dockerfile`, FR-167-5)
- **Tags applied to each image:** `<version>` (e.g. `1.0`) and `latest`. `latest` only ever moves on a
  tagged release, never on every `main` commit — this is the concrete mechanism behind "not always
  pushing at latest."
- **Auth:** `docker/login-action` against `ghcr.io` using the built-in `GITHUB_TOKEN` (no new secret to
  provision) with `permissions: packages: write` at the job level. Package visibility follows the repo
  (private) unless manually changed on the GHCR package settings — worth confirming once, not something
  this workflow controls.
- **Build:** `docker/build-push-action`, `platforms: linux/amd64` only (matches the investigation's
  explicit non-goal on multi-arch), with `cache-from`/`cache-to` GHA cache (`type=gha`) so a re-tag isn't
  a from-scratch rebuild.
- **Order/isolation:** the two image builds run as independent matrix jobs (or parallel steps) — a
  failure building `ravilo-web` must not leave `jellystructure` half-published, and vice versa. Prefer a
  matrix (`strategy.matrix.image: [jellystructure, ravilo-web]`) so both get identical login/tag/push
  logic instead of duplicated steps.

## 3. Non-goals

Everything below was raised in the investigation report but is explicitly **not** part of this spec —
either because it lives in a different repo, is a deployment-time decision rather than code, or is an
operational task with no code shape:

- **Caddy rate-limit plugin (report finding F4).** D1's mitigation needs `mholt/caddy-ratelimit` added to
  `~/caddy/Dockerfile` and that image rebuilt — a different repo, tracked as a prerequisite for actually
  *flipping the firewall open*, not for building/publishing these images.
- **`~/jellyfin/docker-compose.yml` changes (F5, F6)** — same-origin Caddy routing for Ravilo web, and
  the identity path-mount requirement for the existing library paths. These are deployment configuration
  in a different directory/repo, applied once these images exist and are pulled there.
- **Whether the admin surface should be public at all** vs. publishing only `/api/tv/**` — a deployment
  decision, not something this spec resolves.
- **Secret rotation** — outstanding since the 2026-08-02 audit, operational, not a code change.
- **Debug→release cutover on the live host (F8)** — happens naturally once the compose file in the other
  repo switches from the dev-run binary to the published image; nothing to spec here.
- **Multi-arch builds.** amd64 only, matching the only architecture this deployment runs on.
- **Dependency CVE scanning.** Out of scope, as it was for the original security review.

## 4. Open questions

1. ~~`caddy:2-alpine` vs `nginx:alpine` for the ravilo-web runtime image~~ — **resolved by explicit
   correction**: neither. Both images are plain Kotlin services (FR-167-5's `:web-static-server`); no
   reverse-proxy technology is baked into either image, so operators front them with whatever they
   already run.
2. **Should `publish.yml` also attach the git tag's own commit SHA as a third image tag** (alongside
   `<version>` and `latest`), for exact provenance? Low cost, no real downside. Recommendation: yes, add
   it, but it's not load-bearing for anything in this spec.
3. **Source-map stripping mechanism (FR-167-5)** — needs confirming against the actual Kotlin/Wasm
   Gradle plugin version during implementation; the spec names the intent (`ravilo.js.map` shouldn't
   ship) but not a verified flag.

## 5. Verification

- `docker build` context size check: confirm the `.dockerignore` (FR-167-1) actually shrinks the sent
  context (`docker build` prints "Sending build context to Docker daemon  X MB" — compare before/after).
- `docker run --rm --entrypoint sh jellystructure -c 'command -v fpcalc'` succeeds (FR-167-2).
- `docker inspect` shows a working `Healthcheck` and it reports `healthy` after the container's
  `start-period` elapses (FR-167-2).
- Load the admin frontend through a real browser with devtools open, confirm **zero** CSP violations in
  the console, and confirm Space Grotesk/Sora/JetBrains Mono are the fonts actually rendering (not a
  system-font fallback) — this is the same check the 2026-08-02 deployment guide's checklist called for
  and that never actually happened (FR-167-3).
- Open a Ravilo title with a trailer through a CSP-enforcing browser, confirm the YouTube/Vimeo iframe
  loads instead of being blocked (FR-167-4).
- `docker build -f ravilo-web/Dockerfile .` succeeds standalone; `docker run` it and load the page with no
  other container present, confirming the bundle really is self-contained end to end, not just
  self-contained in its file listing (FR-167-5).
- Compile checks: `compileKotlinLinuxX64`, `:ravilo-web:compileKotlinWasmJs`, full existing
  `docker-compose.test.yml` Playwright suite still green (nothing here should change backend/frontend
  behavior other than the CSP header and the two Dockerfiles).
- Push a real `v1.0` tag against a throwaway branch/fork first if possible, or dry-run the workflow
  (`workflow_dispatch`) before trusting the real tag push, to confirm both images actually land in GHCR
  with the right two tags each and the package is private.

## 6. Real-world outcome (2026-08-17)

Pushed to `main` and tagged `v1.0` — both images built and landed in GHCR with all three tags
(`1.0`/`latest`/commit-SHA) each, confirmed via the workflow's own run logs (`gh` didn't have
package-read scope locally, so this was verified from the `docker/build-push-action` push-manifest
lines directly rather than a separate registry query).

**`ci.yml` broke on the same two commits** — traced to two issues, neither in scope of this spec's FRs
but found and fixed while getting CI green again:

1. **The Playwright golden snapshots (`shell-dark-chromium-linux.png`/`shell-light-chromium-linux.png`)
   went stale.** Self-hosting the fonts (FR-167-3) shifted text metrics just enough (~4% of pixels) to
   exceed `shell-layout.spec.ts`'s `maxDiffPixelRatio: 0.02` — confirmed by viewing the actual diff image
   (overlapping ghost text at every label, the classic signature of a font-metrics change, not a layout
   bug). Regenerated both snapshots (`playwright test --update-snapshots`) against the corrected
   rendering.
2. **`shell-layout.spec.ts` logs in fresh in all 3 of its tests**, unlike `scan-fixture.spec.ts` and
   `bazarr-dashboard.spec.ts`, which already share one `beforeAll` login specifically because — per their
   own comments — the app's login-rate-limiter (5/60s) is shared across the *whole* Playwright run, not
   per file. A full-suite run makes 7 login POSTs (auth ×2 + bazarr-dashboard ×1 + scan-fixture ×1 +
   shell-layout ×3) well inside one rate-limit window, tripping it — confirmed live via the
   `error-context.md` page snapshot literally showing "Too many login attempts — try again in a minute"
   on the login form. This bug predates this phase (the file was added 2026-08-13, the shared-login
   pattern already existed elsewhere by then) and was only ever exposed because (1)'s extra retries
   pushed the cumulative count over the edge in the specific CI runs this phase's commits triggered.
   Fixed `shell-layout.spec.ts` to match the established `test.describe.serial` + `beforeAll` pattern —
   one login, theme/viewport switches via `localStorage` + `page.reload()` instead of a fresh login per
   test.

Both fixes verified against a real local Docker Compose test stack (not just re-reading CI logs) —
matching [[feedback-test-docker-images-as-nonroot]]'s spirit of checking real behavior, not configuration.
One dead end worth recording: an initial local repro attempt gave misleading results because (a) running
Playwright from the host instead of the `playwright` service in `docker-compose.test.yml` breaks Docker
DNS resolution (`jellyfin-mock` doesn't resolve outside the `testnet` network), and (b) repeated local
runs against the same bind-mounted `config-test/`/fixture directories accumulate real state (the test
suite's own file mutations, e.g. `scan-fixture.spec.ts` actually calls `mkvpropedit` on the fixture MKV)
that a real CI run never has, since CI always starts from a clean checkout. Both are testing-environment
artifacts, not real bugs — resolved by running the actual `playwright` compose service and rebuilding
fixtures fresh between attempts.

## 7. Live bug (2026-08-17): Ravilo web crashed on every load, both dev and production

Reported live: opening `ravilo-web` (dev server, port 8082) threw
`org_jetbrains_skiko_node_RenderNodeContextKt_RenderNodeContext_1nMake is not a function` on every load.
Confirmed the same crash in the production build (`:ravilo-web:wasmJsBrowserDistribution`, minified to
`Jk is not a function`) — not dev-only.

**Root cause, traced with file:line precision, not guessed:**

1. `ravilo-web/webpack.config.d/skiko.js` (dated before the Compose Multiplatform 1.8.1→1.9.3 bump,
   commit `fda96349`, 2026-06-30) force-redirected `skiko.mjs` imports to a separate npm package copy —
   stale since CMP 1.7+, where skiko's web runtime ships bundled directly inside the compiled app JS, no
   redirect needed. Removed — legitimate cleanup, but (confirmed by testing) not sufficient alone; the
   redirect's own target didn't even resolve to an existing path, so it was already inert.
2. **The actual bug**: Kotlin/Wasm's *generated* bootstrap entry (`<module>.mjs`, not our code — verified
   directly in `ravilo-web/build/compileSync/wasmJs/main/productionExecutable/optimized/
   jellystructure-ravilo-web.mjs`) does `await WebAssembly.instantiateStreaming(...)` for the app's own
   wasm, then calls `exports._start()` immediately — with **no wait at all** for skiko's separately-loaded
   wasm to finish instantiating. Confirmed with Node's own `WebAssembly.Module.exports()` that the called
   function genuinely exists in skiko.wasm's export table (1010 exports total) — this isn't a version
   mismatch, it's a pure race. Since skiko.wasm (~8MB) is larger than the app's own wasm (~6MB) here, the
   app routinely finishes loading first and calls into skiko's still-unset lazy-binding export stubs
   (skiko.mjs's own pattern: `export let X = (...a) => (X = loadedWasm._[X])(...a)`, which throws exactly
   this "is not a function" the instant `loadedWasm._` isn't populated yet). Verified via network-request
   tracing that this ordering (app wasm's response arriving before skiko wasm's) is exactly what happens.

skiko.mjs already exports a promise for exactly this purpose — `export const awaitSkiko =
loadSkikoWASM().then(...)` — literally named for it. The generated bootstrap just never awaits it.

**Fix**: a new webpack loader (`ravilo-web/webpack.config.d/await-skiko.js` +
`await-skiko-loader.cjs`) patches the generated bootstrap file at build time: imports `awaitSkiko` from
`./skiko.mjs` and awaits it immediately before `exports._start()`. Content-matched (looks for the literal
`exports._start();` call), not filename-matched, since the generated module name is derived from Gradle
project coordinates and isn't guaranteed stable. A source-level fix isn't possible since this file is
regenerated fresh every build; a post-bundle patch was considered and rejected — tree-shaking could have
already dropped `awaitSkiko`'s implementation if nothing in the original unmodified source graph
references it, so the transform has to happen before webpack's own analysis sees the final module graph,
not after.

**Verification, not just "seems fixed":**
- 3 consecutive fresh page loads in headless Chromium: zero `pageerror` events (was: fired every time).
- Temporary console.log instrumentation (removed before commit) confirmed the actual sequence:
  `awaitSkiko` resolves, `_start()` is called, `_start()` returns — full successful bootstrap, not just
  "no crash because nothing ran."
- Headless Chromium's own screenshot/evaluate calls hung independently of the fix (low, declining CPU
  during the hang — ruled out an infinite loop) — traced to a headless-Chromium WebGL/compositor
  limitation in this specific local test environment, not the app. Confirmed by re-running under `xvfb`
  (real display, headed Chromium): canvas correctly resized to the real viewport (1280×720, not the
  browser's 300×150 default), zero errors, and a real screenshot showing the actual rendered sign-in
  screen — proof this is a genuine, working fix, not just an absence-of-error illusion.
- New regression test `tests/e2e/ravilo-web.spec.ts` added, wired into `docker-compose.test.yml` (new
  `ravilo-web` service running the real `ravilo-web/Dockerfile` image) and therefore `ci.yml`. Verified
  green against the real compose stack end to end, alongside the rest of the suite (10 passed, 2
  pre-existing skips) — not run in isolation.
- **2026-08-18 postscript: adding the `ravilo-web` build to `ci.yml` then broke CI itself, for reasons
  unrelated to the crash fix above** — three real root causes found and fixed in sequence, documented
  here because each one was genuinely non-obvious and the debugging path is worth keeping:
  1. **`BUILDX_CACHE_FROM`/`BUILDX_CACHE_TO` env vars in `ci.yml` were dead on arrival.** `docker compose
     build`/`up --build` never reads those as a recognized convention — confirmed via
     `grep -n "BUILDX_CACHE\|cache_from\|cache_to" docker-compose.test.yml` returning zero matches.
     Docker layer caching had been a complete no-op since it was introduced; every run did a full cold
     rebuild. Fixed with the Compose Specification's real `build.cache_from`/`build.cache_to` keys,
     verified locally against a `docker-container` buildx builder (the default `docker` driver doesn't
     support `type=local` cache export — confirmed via `docker buildx ls`). Note on a wrong intermediate
     conclusion: a local re-build test (reusing the same builder container across both attempts) showed
     the `RUN --mount=type=cache` gradle/konan/npm steps always re-executing despite `cache_from`
     pointing at a full prior export, which looked like proof BuildKit can never cache those steps — but
     the real CI evidence (below) contradicts that: a same-commit rerun against a genuine actions/cache
     upload from the prior green run showed **every** layer, including the two gradlew `RUN` steps, hit
     as `CACHED`, taking the whole "Run test stack" step from 34m13s down to 3m40s. The local test wasn't
     representative — likely because reusing one builder container across both local attempts let the
     mount's own *ephemeral* content stay warm regardless of `cache_from`, muddying what was actually
     being measured. Bottom line: the fix is more effective than the local test suggested — a real cache
     hit (unchanged source between runs) skips the dominant cost entirely, not just the cheap layers.
  2. **Two consecutive real CI runs then died with the same signature**: `conclusion=failure`, but the
     active "Run test stack" step never recorded a completion timestamp, and every later `if: always()`
     step — including "Move Docker cache", which is supposed to run no matter what — never even started.
     That pattern means the *runner process itself* died mid-step, not a script exiting nonzero; per-job
     log fetches (`gh api .../jobs/{id}/logs`) came back `BlobNotFound` both times, consistent with a
     runner that never got to flush/upload logs. First hypothesis: `docker compose up --build` builds
     `app` and `ravilo-web` **concurrently** by default, each spinning up its own Gradle daemon at
     `org.gradle.jvmargs=-Xmx4g` — two at once already want 8GB of heap alone, and this repo (private) is
     capped at the 2-core/8GB runner tier (public repos get 4-core/16GB). Fixed by serializing the two
     `docker compose build` calls ahead of `up`. This alone was **not sufficient** — the very next run
     died with the identical signature, faster (~26min vs ~58min), which is actually evidence *against*
     pure memory pressure (serializing should reduce peak memory, not shrink time-to-crash). Second,
     complementary hypothesis: disk. Added a "Free disk space" step (removes `ubuntu-latest`'s
     preinstalled Android SDK/.NET/GHC, none of which this project uses) and dropped `cache_to`'s
     `mode=max` (which caches every intermediate layer, including the entire discarded multi-stage
     builder stage — ~669MB/455MB locally) down to default mode, plus a `docker buildx prune -f` between
     the two builds to reclaim the first build's intermediate layers before the second starts (touches
     only BuildKit's build cache, never the tagged image the later `up` step needs).
  3. **Correction to a working assumption made during this debugging**: GitHub's own runner-spec docs
     state private-repo `ubuntu-latest` gets "14GB SSD" storage; the actual observed root filesystem in a
     live run was **72GB** (`df -h /` showed 58G used / 72G total, 81%, before cleanup; 34G used / 72G
     after — 1.787GB reclaimed by the image prune alone). So disk was tighter than ideal but not
     catastrophically constrained the way the 14GB figure implied — meaning the exact decisive factor
     among (memory serialization / disk cleanup / cache-mode change / plain non-determinism in shared
     runner infrastructure) isn't pinned down with certainty. What's certain: the run immediately after
     all three fixes landed together went fully green (`32088719529`, all 16 steps `success`, 35m37s,
     real Playwright report artifact uploaded, traces-on-failure step correctly `skipped`) — confirmed via
     a same-commit rerun rather than trusted as a one-off. **The rerun (attempt 2) went further and
     confirmed the cache fix works better than believed above**: it hit a genuine cache import from
     attempt 1's upload, took "Run test stack" from 34m13s down to 3m40s, and the log showed `CACHED` on
     every layer including the two gradlew `RUN --mount=type=cache` steps — not just the cheap ones. Real
     Playwright output confirmed genuine test execution both times, not a short-circuit: 12 tests found,
     10 passed / 2 pre-existing skips, including `ravilo-web.spec.ts` (the regression guard for §7's
     crash fix) passing in both runs. **CI is genuinely green as of this sync, two consecutive runs, real
     tests actually executing.**
- **The test does not check DOM text or poll the canvas's bounding box — both were tried and rejected.**
  A `getByText("Sign in")` assertion failed with "element(s) not found" even while a screenshot taken at
  that exact moment showed the text plainly on screen: this is a Compose Canvas (WebGL/Skia) app, not
  DOM-based UI, so rendered text is pixels, not accessible DOM content — wrong locator strategy for this
  rendering technology, not a bug. A follow-up version polled `#ComposeTarget`'s bounding box (waiting
  for it to resize off its 300×150 default) instead — this one made it past local verification (10
  passed, including headed-Chromium screenshot proof) but **hung the real GitHub Actions job for over an
  hour** with no error and no timeout ever firing; cancelled manually. Headless Chromium's WebGL/
  compositor behavior on an actual CI runner (no GPU) is evidently unreliable in ways this project's own
  local Docker testing didn't surface, and in a way that didn't respect Playwright's own `expect.poll`
  timeout — a bounded *failure* would have been fine, an unbounded *hang* is not. Final test: a single
  `page.goto()` with an explicit timeout, then a fixed `page.waitForTimeout()` (no browser round-trip,
  can't hang), then a plain check of `pageerror` events collected passively during that wait — the only
  browser-protocol call in the whole test is the `goto()`, which has its own timeout; everything after it
  is local. `test.setTimeout(30_000)` as a hard backstop regardless. This deliberately gives up verifying
  that Compose actually renders (confirmed separately, once, via the local headed-Chromium screenshot
  above) in exchange for a test that can fail but never hang the pipeline — the right tradeoff for CI.

## 7. Amendment (2026-08-27) — publish on every push to main, not release-only

FR-167-6/7 originally gated `publish.yml` behind a real GitHub Release, deliberately, so `latest` only
moved on a conscious action. **User-directed reversal**: `publish.yml` now also runs on every push to
`main`, pushing `latest` + a commit-SHA tag for both images with no version tag (there is no release
version to attach on a plain push). Release publishing is unchanged and additive — a release still adds
the versioned `MAJOR.MINOR` tag alongside `latest` + SHA. `workflow_dispatch` (re-publish an existing
version) is unchanged.

Net effect: `latest` in GHCR now tracks `main` continuously, the same as `ci.yml`'s own build (which
still only builds+tests, never pushes). Known cost, flagged but not blocking: this doubles the
Docker-build load on every `main` push on the same 2-core/8GB private-repo runner that needed disk-
exhaustion and build-serialization fixes in §6 above — not yet re-verified under this new load. If it
resurfaces runner instability, the fix is sharing/consolidating the two workflows' Buildx cache scopes
or dropping `ci.yml`'s redundant image build in favor of this workflow's.
