# Phase 235 — Serve the web app like an app

> Ravilo web is about to become the phone client for households with iPhones (research report
> `ravilo-web-pwa-player-cast-2026-09-18.md`, owner direction 2026-09-18: *"an iPhone installs the PWA and
> streams to the Samsung TV — our most important use case"*). An installed web app is judged by how it
> loads from its icon, and today the static server behind `ravilo.example.net` ships **15.3 MB of
> WebAssembly uncompressed**, revalidates every hour, sends **no Content-Security-Policy**, answers
> `HEAD` with 404, and answers `GET /manifest.webmanifest` with `index.html`. None of the client work in
> R263–R265 can be judged honestly until this is fixed, and every fix here is a header.

## Status

`✓ Built` — written 2026-09-18 from live measurements of `ravilo.example.net` and a read of
`web-static-server/.../Main.kt` and `server/Server.kt`. **Dev-reviewed 2026-09-18 against `main`
`05195d1f`** (see §Dev review at the bottom: only the `.wasm` files are hashed, so FR-235-1 narrows; the
CSP would block the page's own inline runtime-config script, so FR-235-8 removes inline script; hls.js and
JASSUB self-hosting moves here from R265 as FR-235-9 so production playback never breaks). **Built
2026-09-18** in the dev review's own build order (FR-235-8/FR-235-9 first, then the headers).
**Container-tested the same day**: built `ravilo-web/Dockerfile`'s image for real (with `brotli` now
installed in its builder stage) and ran it standalone — confirmed live with curl: br/gzip negotiation on
the hashed `.wasm` (1.4 MB served over the wire for the 6.9 MB module, matching the spec's own reference
numbers), `Cache-Control: public, max-age=31536000, immutable` only on the two hash-named files,
`no-cache` elsewhere, `Vary: Accept-Encoding`, the full CSP with `manifest-src`/`worker-src`, `HEAD`
matching `GET`, `nope.png` → 404, an extension-less path → the SPA shell, and `/runtime-config.js`
correctly empty/populated depending on `RAVILO_VERSION`/`DEFAULT_SERVER_URL` — with `index.html` itself
now carrying no injected script at all. `tests/e2e/ravilo-web-headers.spec.ts` added asserting the same
against both serving paths (needed wiring `RAVILO_WEB_DIR` into the test stack's backend service, unset
in every real deployment, so `/tv/**` is reachable there at all); the full docker-compose e2e run was
in progress against the real stack as this note was written — see the commit history for its outcome if
this line hasn't been updated since. Backend / static
server only; no client, DTO, string or design change. Siblings: **R263** (the app installs), **R264**
(honest browser capabilities), **R265** (AirPlay).

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — admin taken through
**234**, Ravilo through **R262**. The report's prospective "235" is this phase.

## Current state (measured 2026-09-18 against `ravilo.example.net`, traced against `main`)

| Fact | Where | Consequence |
|---|---|---|
| No `Content-Encoding` on `.wasm` or `.js`: 6 908 230 + 8 405 319 + 607 726 bytes on the wire (gzip −9: 1.9 MB + 3.2 MB + ~0.2 MB) | `web-static-server` never compresses; the prod compose has no Caddy `encode` label | ~16 MB per cold load; on a phone's first launch that is the whole first impression |
| `Cache-Control: max-age=3600, must-revalidate` on content-hashed assets | `Main.kt` `serveBytes` | hourly revalidation of files whose name *is* their content hash |
| No `Content-Security-Policy`, no `X-Content-Type-Options`, no `Referrer-Policy` | `Main.kt` sets none; `Server.kt:318–336` sets all of them for `/tv/` | the two ways of serving the same bundle have different security postures, and the backend's `script-src 'self'` would block the CDN-loaded hls.js the player uses today |
| `HEAD /` → 404 | only `get("{...}")` is routed | uptime probes and some install checkers fail |
| `GET /manifest.webmanifest`, `/sw.js`, `/icon-512.png` → **200 `text/html`** (`index.html`) | the SPA fallback answers *every* unmatched path | a browser asked for a manifest is handed a page; a service worker registration would fail with a MIME error; a missing icon is "found" |
| `contentTypeFor` has no `webmanifest`; `.wasm` is right, `.woff2` is right | `Main.kt` | a manifest served as `application/octet-stream` is ignored by Chrome |
| `index.html` served `no-cache` with the runtime-config script injected | `injectRuntimeConfig` (R225 / phase 224) | correct; keep |

The backend's own `/tv/**` route (`Server.kt:652`, `serveFrontendFile`) has the same SPA fallback and the
same hourly cache but does carry the CSP. Both paths must end up identical in behaviour.

## Requirements

**FR-235-1 · Hashed assets are immutable, `index.html` is not.** *(Narrowed in dev review, item 1: only
the two `.wasm` files are hashed; `ravilo.js` and `composeResources/**` are `no-cache`.)* A file whose name carries a content hash
(`[0-9a-f]{20}\.wasm`, `ravilo.js`'s chunks, `composeResources/**`) is served with
`Cache-Control: public, max-age=31536000, immutable`. `index.html`, `manifest.webmanifest` and `sw.js` are
served `Cache-Control: no-cache` (ETag revalidation stays). Both `web-static-server` and the backend's
`/tv/**` route.

**FR-235-2 · Compressed on the wire.** `.wasm`, `.js`, `.css`, `.json`, `.webmanifest`, `.svg` and `.html`
are served gzip- (and, where the client offers it, brotli-) encoded when `Accept-Encoding` allows, with
`Vary: Accept-Encoding`. Preferred: **precompress at build time** — `wasmJsBrowserDistribution` output
gains `.gz`/`.br` siblings and the server picks the sibling — so no request pays for compression at
runtime on a Kotlin/Native process with no zlib binding. Falling back to a Caddy `encode zstd gzip` label
in `docker-compose.yml` is acceptable for this house but does not count as done: every self-hoster would
have to know. `ETag` must differ per encoding (append `-gz`/`-br`).

**FR-235-3 · The SPA fallback applies only to routes.** A request whose last path segment contains a `.`
(an asset-shaped path) that does not exist answers **404**, never `index.html`. Extension-less paths keep
the fallback (Ravilo routes on the URL hash, so this is only deep-link hygiene).

**FR-235-4 · Right types.** `webmanifest` → `application/manifest+json`; `.map` → `application/json`;
`.txt` → `text/plain`; `.gz`/`.br` siblings carry the *underlying* type plus `Content-Encoding`. `sw.js` is
`application/javascript` at the site root so its scope is `/` (or `/tv/` on the backend path) without a
`Service-Worker-Allowed` header.

**FR-235-5 · `HEAD` works.** Every `GET` route answers `HEAD` with the same headers and no body.

**FR-235-6 · One security posture.** *(Dev review items 2–3: ships together with FR-235-8 and FR-235-9,
never before them.)* `web-static-server` sends the same `X-Content-Type-Options`,
`Referrer-Policy`, `Strict-Transport-Security` and `Content-Security-Policy` as `Server.kt` sends for
`/tv/**`, with two additions both paths get: `manifest-src 'self'` and `worker-src 'self'` (the service
worker), and `media-src` keeps `https:` (Jellyfin streams are cross-origin). `frame-ancestors 'none'` and
`X-Frame-Options: DENY` stay. No CDN host is added — R264 self-hosts hls.js and JASSUB; until it lands, the
web player's CDN loads fail under this CSP exactly as they already fail on `/tv/**`, which is the point.

**FR-235-7 · Nothing else changes.** `DEFAULT_SERVER_URL` / `RAVILO_VERSION` injection, the ETag
scheme, the path-traversal guard and the `webstatic` user are untouched. `index.html` with neither env set
stays byte-identical to the bundle's own.

## Acceptance

- `curl -sI -H 'Accept-Encoding: br, gzip' https://ravilo.example.net/<hash>.wasm` shows
  `content-encoding`, `cache-control: public, max-age=31536000, immutable`, `vary: accept-encoding`, and
  `content-length` under 3.5 MB for the Skiko module.
- `curl -sI https://ravilo.example.net/` → 200; `curl -sI .../manifest.webmanifest` → 200
  `application/manifest+json`; `curl -s -o /dev/null -w '%{http_code}' .../nope.png` → 404;
  `curl -s -o /dev/null -w '%{http_code}' .../some/route` → 200 `text/html`.
- `curl -sI https://ravilo.example.net/ | grep -i content-security-policy` is non-empty and equal to the
  backend's `/tv/` policy plus `manifest-src`/`worker-src`.
- The e2e suite gains `ravilo-web-headers.spec.ts` asserting the five lines above against the test stack's
  `ravilo-web` container **and** the backend's `/tv/` route.
- `scripts/check-phases.sh` green.

## Non-goals

- No service worker, manifest or icon — R263 ships those files; this phase makes them servable.
- No HTTP/2 push, no CDN, no reverse proxy baked in (FR-167-5's "people put it behind Caddy" stands).
- No change to the admin frontend's serving beyond what `serveFrontendFile` shares with `/tv/`.

## Open questions

1. **Precompress vs runtime.** Gradle can emit `.gz`/`.br` siblings with a small task (or the webpack
   `compression-webpack-plugin` already in the toolchain's reach); a Kotlin/Native runtime gzip would need
   a zlib cinterop. Lean: precompress; the runtime path is out of scope.
2. **Should `/tv/**` on the backend stay at all** once `ravilo.example.net` is the app's origin (R263
   needs a stable origin for the service worker and manifest `id`)? Lean: keep it for single-container
   self-hosters, documented as "manifest `start_url`/`scope` become `/tv/`".

## Dev notes

- The receiver's CSP (`CAST_RECEIVER_CSP`, `Server.kt:775`) is separate and unaffected.
- `HEAD` in Ktor CIO: `head("{...}")` sharing the handler, or `AutoHeadResponse`.
- The e2e image is `mcr.microsoft.com/playwright:v1.61.0-noble` locally (WebKit runs there; the host lacks
  `libgtk-4`, `libevent`, `libwoff2dec`) — the header spec needs only `request`, not a browser.

## Dev review (2026-09-18, against `main` `05195d1f`)

Every row of the *Current state* table was re-checked against `web-static-server/…/Main.kt`, `Server.kt`
and a real `wasmJsBrowserDistribution` output. The measurements stand. Three premises do not, and two of
them would have taken the production web client down on the day this shipped.

1. **Only the two `.wasm` files are content-hashed.** The distribution is `<hash>.wasm` ×2, **`ravilo.js`
   (fixed name, 607 KB)**, `index.html` and 45 files under `composeResources/**` with **stable names**
   (`font/sora.ttf`, `drawable/flag_fo.png`, …). FR-235-1 as written marks `ravilo.js`'s "chunks" and
   `composeResources/**` immutable — a changed string table, flag or font would then never reach a
   browser that had seen the old one, and `ravilo.js` would pin a whole build for a year.
   **FR-235-1 corrected:** `immutable` only for a file whose *name* carries a ≥ 16-hex-digit segment
   (today: the two `.wasm`). `ravilo.js`, `composeResources/**`, `index.html`, `manifest.webmanifest`,
   `sw.js`, `boot.js` and `runtime-config.js` are `no-cache` with the existing ETag (a 304 per launch is
   cheap; R263's service worker removes even that). The comment above `injectRuntimeConfig` claiming every
   other asset is "content-addressed" is wrong and goes. Hashing `ravilo.js` via webpack
   `output.filename` is a possible follow-on, not this phase.
2. **FR-235-6's CSP blocks the page's own inline scripts — including the one that tells the app where
   the backend is.** `index.html` carries an inline block (lines 42–105: the fullscreen button and the
   gamepad poller) and `injectRuntimeConfig` adds a second inline `<script>` assigning
   `window.__RAVILO_DEFAULT_SERVER__` / `__RAVILO_VERSION__`. `script-src 'self' 'wasm-unsafe-eval'
   'unsafe-eval'` has no `'unsafe-inline'`, so both are refused. On the static origin that is the
   2026-09-17 login-404 again: with no default server the app calls its own origin for the API. (It is
   already the case on the backend's `/tv/` path today — invisible there only because same-origin happens
   to be the right default; the gamepad poller and the fullscreen button are dead on `/tv/`.)
   **New FR-235-8 · No inline script.** The inline block moves to `boot.js`; the runtime config is served
   as **`GET /runtime-config.js`** (generated per request from the two env vars, `application/javascript`,
   `no-cache`; an empty file when neither is set; the backend's `/tv/` path serves it too) and referenced
   by a static `<script src>` ahead of `ravilo.js`. `injectRuntimeConfig` and its string-match on
   `<script src="ravilo.js">` are deleted, so **`index.html` is byte-identical to the bundle's own in
   every deployment** (FR-235-7 amended accordingly). R263's WasmGC probe and service-worker registration
   live in `boot.js` for the same reason — R263 must not add an inline script. `'unsafe-inline'` and
   nonces are rejected: a nonce makes `index.html` uncacheable by R263's worker.
3. **The CSP also blocks the player's CDN loads, and that is not acceptable as "the point".**
   `RaviloPlayerWasm.kt:199` loads hls.js and `:221–222` loads JASSUB's worker and wasm from
   `cdn.jsdelivr.net`. Under FR-235-6 every transcoded (HLS) play in Chrome/Firefox and every ASS subtitle
   breaks on the production web origin until R265 ships — a large phase that depends on 236 and a TV app.
   Production has live users. **New FR-235-9 · hls.js and JASSUB are self-hosted here**, moved out of
   R265 FR-R265-8: npm dependencies of `ravilo-web`, copied into the distribution, loaded from `'self'`.
   R265 keeps the capability probing only. The CSP and the self-hosting ship in the same release, in that
   order of dependency.
4. **Brotli needs a tool the builder does not have.** A Gradle task can gzip with the JDK alone;
   brotli needs the `brotli` binary in both Dockerfiles' builder stage (or a JVM library on the build
   classpath). Decision: `.gz` siblings always; `.br` siblings when the binary is present, which the two
   Dockerfiles guarantee. The serving rule is unchanged: prefer `br`, then `gzip`, else identity.
   `serveBytes` reads the whole file per request — unchanged, and cheaper with a 3 MB sibling than an 8 MB
   original. The 304 path must carry `Cache-Control` and `Vary` too (today it returns before setting them).
5. **Sibling references are the research report's prospective numbers, not the specs that were
   written.** "R264 (honest browser capabilities)", "R265 (AirPlay)" and "R264 self-hosts hls.js" do not
   exist: the real **R264** is the receiver-only TV app and the real **R265** is *play on a TV*, which
   contains both the capability probing and AirPlay. Read every such reference in this file as R265 (and,
   for the self-hosting, as FR-235-9 above).
6. **`manifest-src` / `worker-src`** already fall back to `default-src 'self'`; adding them is explicit,
   not functional. Keep them — a later tightening of `default-src` should not silently break the install.
7. **Open question 1:** precompress (item 4). **Open question 2:** keep `/tv/` — it is the
   single-container self-hoster's only way to serve Ravilo web, and R263 already specifies the
   `/tv/`-scoped manifest.
8. The two serving paths stay two functions in two modules (`web-static-server` has no dependency on the
   backend); parity is enforced by the e2e spec running the same assertions against both, as the
   acceptance already says. `AutoHeadResponse` is available to both.

**Build order:** FR-235-8 and FR-235-9 first (they are safe on their own), then the headers. Nothing here
waits on any other phase; **R263 waits on this one**.
