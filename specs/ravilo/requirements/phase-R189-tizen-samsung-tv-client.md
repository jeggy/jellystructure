# Phase R189 — Ravilo: Samsung Tizen TV client, milestone 1 (login + browse + play) (FR-RV-TIZEN1)

> The operator wants Ravilo on Samsung TVs too — specifically **2016–2018 Tizen models** (Tizen
> 2.4/3.0/4.0), not the newest Tizen generation. Preference stated up front: **share as much Kotlin
> code as possible** with the existing clients, but a fully separate implementation is an accepted
> fallback if sharing isn't feasible. Preceded by a research pass (this session, no spec) that
> determined the concrete architecture below.

**Status:** Milestone 1 implemented (build-verified only — see "Verification" for what wasn't testable
in this environment).

## Why not reuse `ravilo-web`
`ravilo-web` targets Kotlin/Wasm (`wasmJs`), which requires WasmGC — Chrome ~119/Safari 18, roughly
2023+. 2016–2018 Tizen TVs run Chromium 47 (2016, Tizen 3.0's engine vintage) through Chromium 56 (2018,
Tizen 4.0) or older WebKit (2016, Tizen 2.4) — **below even plain (non-GC) WebAssembly**, which first
shipped in Chrome 57 (March 2017). The existing web build cannot run on any TV in the target range,
confirmed against Chromium's own WASM-support history and Tizen's documented per-year OS/engine mapping.

## Architecture decision
Researched precedent: **[jellyfin/jellyfin-tizen](https://github.com/jellyfin/jellyfin-tizen)** — a
thin Tizen `.wgt` (widget) wrapper around a plain JS web app, using Samsung's native **AVPlay** API for
video (required: the HTML5 `<video>` tag on old Tizen has no real HLS/DASH support), sideloaded via the
TV's Developer Mode (no App Store submission needed for a private app).

**Code-sharing verdict** (the specific ask): UI cannot be shared with the Compose clients — Compose
Foundation/Skia has no path to pre-WasmGC engines, and the only DOM-based Compose option (Compose HTML)
is a from-scratch screen-by-screen rewrite anyway, so it saves nothing over hand-writing the UI directly.
**The data layer can be shared and is worth it**: `shared`'s `TvApiClient` + DTOs are plain Kotlin
(Ktor client-core/websockets + kotlinx.serialization + coroutines, engine supplied by the caller) with
no Compose dependency — all of that toolchain already supports the Kotlin/JS **IR** backend (distinct
from `wasmJs`), which compiles to plain, old-engine-compatible JS. So:

- **`shared` gains a `js(IR)` target** (FR-RV-TIZEN1-1) — the Tizen app consumes the *exact same*
  `TvApiClient`/DTOs as every other Ravilo client, eliminating `/api/tv/**` contract drift by
  construction rather than by hand-kept-in-sync duplicate models.
- **The Tizen app's UI is hand-written Kotlin/JS** (direct DOM manipulation, no framework) — not
  TypeScript/vanilla JS, and not Compose HTML. This keeps the *entire* app in Kotlin (maximizing the
  "share as much as possible" ask at the language level) while staying runtime-compatible with old
  engines, without taking on Compose HTML's from-scratch-UI cost for zero additional benefit.
- **AVPlay bindings are hand-written Kotlin/JS `external` declarations** (`external object tizen`,
  `external interface AVPlayObject`, etc.) — the standard Kotlin/JS interop pattern for a global JS API
  the Tizen WebAPI injects at runtime.

## Requirements (Milestone 1)

### FR-RV-TIZEN1-1 — `shared` module: add a `js(IR)` target
`shared/build.gradle.kts` gains `js(IR) { browser() }` alongside the existing `androidTarget`/
`linuxX64`/`wasmJs` targets. No new dependencies at the `shared` level — `ktor-client-core`,
`ktor-client-websockets`, `kotlinx-serialization-json`, `kotlinx-coroutines-core` all already support
Kotlin/JS IR. `TvApiClient` takes a caller-supplied `HttpClient`, so `shared` itself doesn't need
`ktor-client-js` — only the consuming `ravilo-tizen` module does (constructing `HttpClient(Js)`, the
same engine `ravilo-ui`'s `wasmJsMain` already uses).

### FR-RV-TIZEN1-2 — New `ravilo-tizen` module: login
A real `POST /api/tv/login` flow via the shared `TvApiClient.login(...)`, storing the returned device
token (browser `localStorage`, same shape as the other clients' token stores) and re-using it on reload.
D-pad-navigable username/password form (two DOM `<input>`s + a submit control), driven by
`tizen.tvinputdevice`-registered remote keys (arrow keys move focus between the two fields and the
submit button; Enter submits/activates; Back exits the form).

### FR-RV-TIZEN1-3 — New `ravilo-tizen` module: a browsable Home screen
Calls the existing `/api/tv/home` feed (via the shared client) and renders it as a D-pad-navigable grid
of poster tiles, grouped by row — the same data every other Ravilo client already renders, no new
backend endpoint. Selecting a tile navigates to a minimal detail view (title/synopsis/Play button).

### FR-RV-TIZEN1-4 — New `ravilo-tizen` module: playback via AVPlay
Selecting Play calls `/api/tv/playback/start` (existing endpoint, same as every other client) and feeds
the returned stream URL into AVPlay (`prepareAsync`/`play`), with the standard AVPlay lifecycle
(`onbufferingstart`/`onbufferingcomplete`, `onstreamcompleted`) wired to basic play/pause/stop remote-key
handling. Progress reporting reuses the existing `/api/tv/playback/progress` contract via the shared
client, same cadence as the other clients.

### FR-RV-TIZEN1-5 — Tizen packaging scaffold
`config.xml` (Tizen widget manifest: app id, name, icon, `tizen:profile name="tv"`, required privileges
`http://tizen.org/privilege/internet`, `http://developer.samsung.com/privilege/avplay`,
`http://tizen.org/privilege/tv.inputdevice`) plus an `index.html` entry point loading the Kotlin/JS
IR-compiled bundle. Scaffolded per Samsung's documented Tizen Studio project layout so it's ready for
`tizen build-web` / `tizen package -t wgt` once that tooling is available (see Verification).

## Verification
`:shared:compileKotlinJs` and `:ravilo-tizen:compileKotlinJs` both pass. `:ravilo-tizen:jsBrowserDevelopmentWebpack`
also succeeds end to end (webpack bundles `ravilo-tizen.js`, ~7.9 MiB unminified/dev-mode — a production
build via `jsBrowserProductionWebpack` would be materially smaller and is what an actual `.wgt` should
ship; dev-mode size isn't a real signal either way). The `RAVILO_BASE_URL` DefinePlugin substitution
(`webpack.config.d/tizen-config.js`) was confirmed present in the built bundle.

**Tizen Studio (the `tizen` CLI, TV Extension SDK, certificate signing) is not installed here** —
packaging the compiled bundle into a signed `.wgt`, sideloading via Developer Mode, and any on-real-TV
verification (AVPlay playback, remote-key behavior, D-pad focus, the old-engine JS compatibility
question flagged in the research report, and whether the ~7-8 MiB bundle parses/loads acceptably on
2016-era hardware) has **not** been done and needs the operator's own Tizen Studio setup + a real
2016–2018 TV, per this repo's live-device-testing convention. Packaging steps once Tizen Studio is
available: `./gradlew :ravilo-tizen:jsBrowserProductionWebpack` (or `-PraviloTizenBaseUrl=http://<lan-host>:9505`
to point at a real backend instead of the dev default), copy the resulting bundle + `index.html` +
`style.css` + this module's `config.xml`/`icon.png` into one staging directory, then `tizen build-web`
+ `tizen package -t wgt` from that directory.

## Out of scope (future phases)
- Discover/Seerr request tab, Live TV, kids-mode UI treatment, multi-profile switching UI, Continue
  Watching row polish, cast & crew, search — Milestone 1 is login + browse + play only, to get a real
  build in front of actual old-Tizen hardware before investing further.
- A vanilla-JS/TS fallback (if the Kotlin/JS IR toolchain proves incompatible with 2016 Tizen 2.4's
  WebKit specifically once tested on real hardware) — noted as the documented fallback in the research
  report, not attempted here since the Kotlin/JS path hasn't yet been proven to fail.
- `fetch` polyfill for 2016 Tizen 2.4 WebKit (flagged as a likely-needed gap in the research) — add once
  real-hardware testing on a 2016 set confirms it's actually missing, rather than guessing.

## Source references
- Research: Tizen year→engine mapping, AVPlay requirement, jellyfin-tizen precedent, code-sharing
  analysis — see this session's research (no separate doc; summarized above and in requirements).
- `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/TvApiClient.kt`.
- `ravilo-tizen/` (new module).
