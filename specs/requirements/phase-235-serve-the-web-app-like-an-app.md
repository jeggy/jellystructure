# Phase 235 — Serve the web app like an app

> Ravilo web is about to become the phone client for households with iPhones (research report
> `ravilo-web-pwa-player-cast-2026-09-18.md`, owner direction 2026-09-18: *"an iPhone installs the PWA and
> streams to the Samsung TV — our most important use case"*). An installed web app is judged by how it
> loads from its icon, and today the static server behind `ravilo.jebster.net` ships **15.3 MB of
> WebAssembly uncompressed**, revalidates every hour, sends **no Content-Security-Policy**, answers
> `HEAD` with 404, and answers `GET /manifest.webmanifest` with `index.html`. None of the client work in
> R263–R265 can be judged honestly until this is fixed, and every fix here is a header.

## Status

`Planned` — written 2026-09-18 from live measurements of `ravilo.jebster.net` and a read of
`web-static-server/.../Main.kt` and `server/Server.kt`. Not dev-reviewed, not built. Backend / static
server only; no client, DTO, string or design change. Siblings: **R263** (the app installs), **R264**
(honest browser capabilities), **R265** (AirPlay).

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — admin taken through
**234**, Ravilo through **R262**. The report's prospective "235" is this phase.

## Current state (measured 2026-09-18 against `ravilo.jebster.net`, traced against `main`)

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

**FR-235-1 · Hashed assets are immutable, `index.html` is not.** A file whose name carries a content hash
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

**FR-235-6 · One security posture.** `web-static-server` sends the same `X-Content-Type-Options`,
`Referrer-Policy`, `Strict-Transport-Security` and `Content-Security-Policy` as `Server.kt` sends for
`/tv/**`, with two additions both paths get: `manifest-src 'self'` and `worker-src 'self'` (the service
worker), and `media-src` keeps `https:` (Jellyfin streams are cross-origin). `frame-ancestors 'none'` and
`X-Frame-Options: DENY` stay. No CDN host is added — R264 self-hosts hls.js and JASSUB; until it lands, the
web player's CDN loads fail under this CSP exactly as they already fail on `/tv/**`, which is the point.

**FR-235-7 · Nothing else changes.** `DEFAULT_SERVER_URL` / `RAVILO_VERSION` injection, the ETag
scheme, the path-traversal guard and the `webstatic` user are untouched. `index.html` with neither env set
stays byte-identical to the bundle's own.

## Acceptance

- `curl -sI -H 'Accept-Encoding: br, gzip' https://ravilo.jebster.net/<hash>.wasm` shows
  `content-encoding`, `cache-control: public, max-age=31536000, immutable`, `vary: accept-encoding`, and
  `content-length` under 3.5 MB for the Skiko module.
- `curl -sI https://ravilo.jebster.net/` → 200; `curl -sI .../manifest.webmanifest` → 200
  `application/manifest+json`; `curl -s -o /dev/null -w '%{http_code}' .../nope.png` → 404;
  `curl -s -o /dev/null -w '%{http_code}' .../some/route` → 200 `text/html`.
- `curl -sI https://ravilo.jebster.net/ | grep -i content-security-policy` is non-empty and equal to the
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
2. **Should `/tv/**` on the backend stay at all** once `ravilo.jebster.net` is the app's origin (R263
   needs a stable origin for the service worker and manifest `id`)? Lean: keep it for single-container
   self-hosters, documented as "manifest `start_url`/`scope` become `/tv/`".

## Dev notes

- The receiver's CSP (`CAST_RECEIVER_CSP`, `Server.kt:775`) is separate and unaffected.
- `HEAD` in Ktor CIO: `head("{...}")` sharing the handler, or `AutoHeadResponse`.
- The e2e image is `mcr.microsoft.com/playwright:v1.61.0-noble` locally (WebKit runs there; the host lacks
  `libgtk-4`, `libevent`, `libwoff2dec`) — the header spec needs only `request`, not a browser.
