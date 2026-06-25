# Phase R63 — Ravilo TV performance: right-sized images, tuned image cache, viewport prefetch (FR-RV-PF1)

## Problem
The Ravilo TV app doesn't feel snappy. Posters/backdrops pop in blank on scroll, the hero re-decodes a
heavy image every few seconds, and image bytes/decode cost are far higher than they need to be. An
investigation of the image pipeline + list virtualization found the bottleneck is **not** focus/scroll
(already draw-phase optimized in R42/R43/R47) but the **image loading pipeline**, which is essentially
untuned.

## Findings (from a code audit — versions: compose-multiplatform 1.8.1, coil 3.2.0)

1. **Backend serves full-resolution source images.** Every image URL is built with **no sizing query
   params** — e.g. `tv/HomeFeedService.kt:296-297` `…/Images/Primary?api_key=…`,
   `:85-86` hero `Backdrop/0` + `Logo`, plus `BrowseService.kt:137-138`, `DetailService.kt:70,131-132`,
   `TvRoutes.kt:138,222` (avatars). A 155×232 dp poster downloads a full ~1000×1500 JPEG; a landscape
   resume tile (256×144 dp) downloads a full-res backdrop. Coil downsamples *after* decode, so RAM is
   bounded — but the **network transfer + Jellyfin server-side encode are full-res**, the expensive part
   on a TV's Wi-Fi. **The sizing pattern already exists** — `PlaybackService.kt:193` appends
   `&MaxWidth=1920&MaxHeight=1080` for video; it's just never applied to images.
2. **The image loader is unconfigured.** `seams/ImageLoader.kt:9-24` `RemoteImage` is a bare
   `AsyncImage(model=url)` — no `placeholder`, `crossfade`, `error`, or `.size()`. The only
   `SingletonImageLoader.setSafe` is Android-only and just registers the SVG decoder
   (`androidMain/RaviloAppContext.kt:13-15`) — **no `memoryCache`, no `diskCache`, no crossfade**, so
   both targets run Coil defaults. **wasmJs has no `SingletonImageLoader` at all** → browser HTTP cache
   only, no Coil disk cache.
3. **No beyond-viewport prefetch.** Lists are correctly lazy with stable `key`s (`ContentRow.kt:121`,
   `HomeScreen.kt:215`, `BrowseScreen.kt:274`). But Compose's default prefetch is ~1 item ahead and
   `LazyLayoutCacheWindow` (true cache-window prefetch) needs foundation 1.9.x — the project is on
   **compose-multiplatform 1.8.1**. Off-screen rows aren't composed, so their images don't start
   loading until scrolled near → blank pop-in (made worse by the missing placeholder + crossfade).
4. **The hero is the single heaviest surface.** `HeroCarousel.kt:118-129`
   `AnimatedContent(targetState = active.backdropUrl)` cross-dissolves a **full-res** backdrop on every
   7s auto-advance and every Left/Right page — full-res decode + layered transition.
5. **Already good (do not touch):** `Tile.kt` runs scale/shadow/ring in `graphicsLayer`+`drawWithCache`
   (no per-frame recompose); stores are retained across nav (R40, no Loading flash); network is on
   `Dispatchers.Default`, nothing blocks the main thread; `derivedStateOf` for scrolled state is
   correctly `remember`ed.

## Goal
Cut image bytes/decode dramatically and hide remaining latency, with the smallest, lowest-risk set of
changes — no Compose-version upgrade required for the core wins.

## Requirements

### A. Right-size image URLs at the backend (highest impact)
Append Jellyfin sizing params per image *kind* where the URLs are built, sized to the actual UI target
(reuse the `PlaybackService.kt:193` pattern). Suggested caps (tune to `RaviloDimens`):
- **Poster** (Primary): `&fillHeight=480&fillWidth=320&quality=90`
- **Tile/landscape backdrop** (resume/landscape): `&fillWidth=640&quality=90`
- **Hero backdrop**: `&fillWidth=1920&quality=90` · **Hero/title logo**: `&fillHeight=300`
- **Channel logo**: `&fillHeight=300` · **Skyborn**: `&fillHeight=160`
Touch: `tv/HomeFeedService.kt` (hero `:85-86`, channel rail, rows `:296-297`), `BrowseService.kt:137-138`,
`DetailService.kt:70,131-132`, `TvRoutes.kt:138,222`. Centralize the suffix builder so the size policy
lives in one place (e.g. a `JellyfinImageUrl` helper) rather than scattered string literals.

### B. Configure the Coil ImageLoader on every target
Set an explicit `memoryCache { maxSizePercent(…) }` + `diskCache { directory + maxSizeBytes(…) }` +
`crossfade(true)` in `androidMain/RaviloAppContext.kt` (alongside the SVG decoder), and **add a
`SingletonImageLoader.setSafe` for wasmJs/web** (currently absent) with a disk/memory cache. Keep the
SVG decoder registration.

### C. Placeholder + crossfade in `RemoteImage`
Add a `placeholder`/`error` + short `crossfade` to `seams/ImageLoader.kt`. Use the existing per-item
**hash-gradient fallback** (already in `Tile.kt`) as the placeholder colour so a tile shows its brand
gradient immediately and the poster crossfades in — no blank flash.

### D. Hero backdrop
With (A) the hero backdrop is already much lighter; additionally reconsider the
`AnimatedContent` cross-dissolve in `HeroCarousel.kt:118-129` (a cheaper fade or pre-warming the next
backdrop) so each 7s advance isn't a full-res decode+layer.

### E. (Minor / consistency) Move remaining animated reads to the draw phase
`AppBar.kt:134-147` (nav-item `animateColorAsState` read in `Text`) and `HeroCarousel.kt:246` (per-dot
`animateDpAsState` read at composition) recompose during transitions — move to `graphicsLayer`/
`drawBehind` like `Tile.kt`. Low impact; do only if cheap.

## Non-goals / deferred
- **Beyond-viewport `LazyLayoutCacheWindow` prefetch** — needs a Compose-Multiplatform **1.9** upgrade;
  track as its own phase. (A)+(B)+(C) mask most pop-in without it.
- No change to the focus/scroll model (R42/R43/R47 stand).
- Data plane stays Jellyfin-direct (images still stream from Jellyfin; we only add size params).

## Acceptance
- Image URLs carry size params (verify in a `/api/tv/home` response).
- Scrolling a cold home feed shows gradient placeholders that crossfade to posters (no blank flash);
  measured image bytes per screen drop sharply vs. full-res.
- Hero auto-advance no longer hitches on a full-res decode.
