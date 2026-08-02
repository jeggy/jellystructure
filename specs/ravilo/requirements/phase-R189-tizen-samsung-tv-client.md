# Phase R189 — Ravilo: Samsung Tizen TV client (FR-RV-TIZEN1)

> The operator wants Ravilo on Samsung TVs too — specifically **2016–2018 Tizen models** (Tizen
> 2.4/3.0/4.0), not the newest Tizen generation. Preference stated up front: **share as much Kotlin
> code as possible** with the existing clients, but a fully separate implementation is an accepted
> fallback if sharing isn't feasible. Preceded by a research pass (this session, no spec) that
> determined the concrete architecture below.

**Status:** Milestone 1 (login/browse/play) and Milestone 2 (everything else except Discover, per the
operator's explicit "build everything except the Discover tab" instruction) both implemented
(build-verified only — see "Verification" for what wasn't testable in this environment).

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

## Requirements (Milestone 2 — "everything except Discover")

The operator's instruction was explicit: build everything except the Discover tab (Upcoming calendar +
Seerr Request — both live under that one nav tab, confirmed by the app's own "Discover: Request" /
"Discover: Coming Soon" sub-page switcher) and whatever lives inside it. Everything below reuses the
same shared `TvApiClient` calls every other Ravilo client makes — no new backend endpoints.

### FR-RV-TIZEN1-6 — Multi-profile session store
`MultiTokenStore`/`LocalSession` (`TokenStore.kt`) replace the milestone-1 single-token store — same
field shape/semantics as `ravilo-ui`'s own (hand-written, per-platform, not `shared`) `MultiTokenStore`,
same hand-rolled localStorage-JSON encoding. Startup gate mirrors `ravilo-ui` exactly: 0 sessions ->
`LoginScreen`, 1 -> auto-activate straight to `HomeScreen`, 2+ -> `ProfilePickerScreen`.

### FR-RV-TIZEN1-7 — Nav bar + profile menu
`NavBar.kt` — the persistent Home/Movies/Series/Search/avatar bar shared by every top-level screen, with
an explicit two-region (nav vs content) D-pad focus handoff contract (see its doc comment — deliberately
avoids the exact "focus dead end" class of bug the real Ravilo LoginScreen shipped this same session).
`ProfileMenuScreen` (Switch profile / My List / Settings / Sign out / Unpair this TV) and
`ProfilePickerScreen` ("Who's watching?") round out the profile system.

### FR-RV-TIZEN1-8 — Home: hero, channel rail, Live TV "On now", rows
`HomeScreen.kt` rewritten: an auto-advancing hero banner (`feed.heroes`), the channel rail
(`feed.channels`, opening `ChannelScreen`), a Live TV "On now" row when `feed.liveTvHome.showOnNowRow`
is set (a live `getLiveTvChannels()` fetch, not embedded in `HomeFeed` itself), and every content row
with watched/progress/next-up badges. One flat list of D-pad "sections" (on-now -> channels -> rows) so
Up/Down/Left/Right navigation is uniform regardless of which sections are actually present.

### FR-RV-TIZEN1-9 — Movies/Series browse + channel pages
`BrowseScreen.kt` (`GET /api/tv/browse` + `GET /api/tv/facets` for a genre sidebar) covers the Movies/
Series nav tabs; `ChannelScreen.kt` (`GET /api/tv/channel/{id}`) covers a channel button's own page,
same row-of-tiles shape as Home minus the hero/rail.

### FR-RV-TIZEN1-10 — Search
`SearchScreen.kt` — a hand-rolled on-screen D-pad keyboard (old Tizen has no reliable native IME
concept for a self-hosted app; the same reasoning that makes every other screen in this client own its
own input) driving `GET /api/tv/search` on a debounce, with a live results grid.

### FR-RV-TIZEN1-11 — Full movie/series detail
`DetailScreen.kt` rewritten to dispatch on `card.kind` to `GET /api/tv/movie/{id}` or
`GET /api/tv/series/{id}`: backdrop/clearlogo, synopsis, an actions row (Play/Resume, Trailer button
when present, Mark watched, My List toggle), a cast rail, a related row, and — series only — a season
tab row + episode list (resume/watched state per episode). "Play" resolves to the series' own
server-computed resume episode (`SeriesProgress.resumeEpisodeId`) the same way every other client's
Play/Resume button does.

### FR-RV-TIZEN1-12 — Player: tracks, Skip Intro/Credits, resume, series autoplay
`PlayerScreen.kt` rewritten around a `PlaybackContext` (which item is actually playing, its
`TvSegmentMarkers`, and the next episode for autoplay) instead of a bare `MediaCard`: an auto-hiding
control bar (Play/Pause, ±10s, Tracks, Next episode), an audio/subtitle track picker overlay driven by
`StreamTicket.audio`/`.subtitles` and AVPlay's `setSelectTrack`, a Skip Intro / Skip Credits pill
(R182 parity, driven by the item's own segment markers), and — for a series episode with a known next
episode — an auto-offered "Next: <title>" card near the end that either the viewer confirms or the
stream's own completion triggers automatically. Auto-advance uses the new `App.replaceTop` (swaps the
current screen without growing the back stack) so Back exits a binge in one press, not once per episode.

### FR-RV-TIZEN1-13 — Live TV: on-now row, guide, zapping, live player
`LiveTvGuideScreen.kt` (shown channels + embedded current/next program, no separate guide fetch needed —
same as every other Ravilo client) and `LiveTvPlayerScreen.kt`, which follows Live TV's distinct
open-close lifecycle (R177: `tuneLiveTv` -> AVPlay -> a 30s heartbeat -> explicit `stopLiveTv` on exit or
before re-tuning) rather than VOD's resume-position contract. Channel Up/Down re-tunes within the same
screen instance (classic remote zapping) instead of pushing a new screen per channel change.

### FR-RV-TIZEN1-14 — My List + viewer Settings
`WatchlistStore.kt` — client-local (no watchlist/favorite endpoint exists anywhere in jellystructure's
backend, confirmed by grep; see its doc comment), scoped per profile so switching profiles doesn't leak
one person's list into another's. `MyListScreen.kt` renders it. `SettingsScreen.kt` covers the
on-device viewer-tweakable subset of `RaviloConfig` (skin, tile shape, autoplay, progress bars) via
`getConfig`/`putViewerSettings`, write-through on every change (no separate Save step).

## Verification (updated for Milestone 2)
`:shared:compileKotlinJs`, `:ravilo-tizen:compileKotlinJs`, and both
`:ravilo-tizen:jsBrowserDevelopmentWebpack` / `:ravilo-tizen:jsBrowserProductionWebpack` all pass with
the full Milestone 2 surface in place — the production bundle is **915 KiB minified** (down from
Milestone 1's ~7.9 MiB *unminified dev* build; not a like-for-like comparison, but a materially more
TV-hardware-reasonable number for what actually ships). The rest of the repo
(`compileKotlinLinuxX64`, `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-web:compileKotlinWasmJs`) still
compiles clean — `shared`'s new `js(IR)` target and this module are additive, nothing else was touched
by Milestone 2.

Same hardware-verification gap as Milestone 1: **no Tizen Studio in this environment**, so nothing here
has been packaged, sideloaded, or run on a real 2016–2018 TV. Milestone 2 adds real risk surface beyond
Milestone 1's — AVPlay's `setSelectTrack`/`getTotalTrackInfo` index-alignment assumption (documented in
`TizenPlatform.kt`), the Live TV tune/heartbeat/stop lifecycle, and the hand-rolled D-pad focus regions
across ~13 screens — none of which can be confirmed correct without a real device and remote.

## Out of scope (future phases)
- The Discover tab (Upcoming calendar + Seerr Request) — explicitly excluded by the operator's own
  instruction, not a technical limitation.
- Kids-mode UI treatment (age-gating badges, restricted browsing chrome) — the backend already gates
  what a kids device's `visibleTo` calls return (R188, this same session); this client renders whatever
  it's sent, same as every screen already does, but has no kids-specific *presentation* differences yet.
- Cast/crew person detail pages (tapping a cast member currently does nothing — noted directly in
  `DetailScreen.kt`).
- Trailer playback (the action row's "Trailer" button is inert — AVPlay targets Jellyfin-served streams,
  not an arbitrary external YouTube/Vimeo URL; would need a second playback path).
- Continue-Watching-specific "See all" page, browse-page facet parity beyond genre (audio language,
  quality, channel — `BrowseCard`'s extra fields aren't consumed here yet), portrait/mobile layout.
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
