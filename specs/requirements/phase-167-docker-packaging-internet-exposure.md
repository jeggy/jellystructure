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

### FR-167-5 — New `ravilo-web` Dockerfile

New `ravilo-web/Dockerfile`, static-file image:

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
# same node/libatomic1 setup as the main Dockerfile's builder stage, minus the linuxX64-only deps
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
COPY design ./design
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle --mount=type=cache,target=/root/.konan \
    --mount=type=cache,target=/root/.npm \
    ./gradlew :ravilo-web:wasmJsBrowserDistribution --no-daemon

FROM caddy:2-alpine
COPY --from=builder /app/ravilo-web/build/dist/wasmJs/productionExecutable/ /srv/
COPY ravilo-web/Caddyfile /etc/caddy/Caddyfile
EXPOSE 80
```

`ravilo-web/Caddyfile`:

```
:80 {
    root * /srv
    encode gzip zstd
    @hashed path_regexp \.(wasm|js)$
    header @hashed Cache-Control "public, max-age=31536000, immutable"
    header /index.html Cache-Control "no-cache"
    header Content-Type "application/wasm" {
        match path *.wasm
    }
    file_server
    try_files {path} /index.html
}
```

This is intentionally **standalone-servable** (matches how the investigation verified the bundle has no
external refs), independent of whether it ends up deployed standalone or the existing `RAVILO_WEB_DIR`
in-backend path is used instead — that's a deployment-time choice, not something this spec decides (see
§3).

Drop `ravilo.js.map` (1.7 MB source map) from what ships — add `-Pkotlin.wasm.sourceMapsEnabled=false` (or
equivalent Gradle property; confirm the exact flag against the Kotlin/Wasm plugin version pinned in
`gradle/libs.versions.toml` during implementation) to the production build, or `RUN rm` it in the builder
stage as a fallback if no build-time flag exists.

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

- **Trigger:** `push: tags: ['v*']`, filtered in-job to the `v[0-9]+.[0-9]+` shape (FR-167-6) so a
  malformed tag fails loudly instead of silently no-op'ing or silently publishing something wrong.
  Also `workflow_dispatch` with a required `version` input (same shape, validated the same way), for
  re-publishing an existing tag's images without cutting a new tag.
- **Does not trigger on every push to `main`.** `ci.yml`'s existing build-and-test-only Docker build
  (via `docker-compose.test.yml`) already validates every commit builds; this workflow is publish-only
  and deliberately gated behind a deliberate version tag, per the user's "not always just pushing at
  latest" instruction.
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

1. **`caddy:2-alpine` vs `nginx:alpine` for the ravilo-web runtime image.** Caddy was picked in FR-167-5
   for automatic gzip/zstd and a much shorter Caddyfile than an equivalent nginx config, and because the
   deployment host already runs Caddy (one less server technology in play). nginx is the more
   conventional choice for "serve a static SPA" and has a smaller base image. Recommendation: Caddy, for
   the config-brevity and homogeneity reasons above; author's call if that's wrong.
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
