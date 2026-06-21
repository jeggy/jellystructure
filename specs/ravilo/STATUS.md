# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-21_

## Current state

**R01–R31 done** (R31's on-device exotic-codec playback not yet verified). The app runs end-to-end on
the stue TV (Sony BRAVIA XR-65X93K, `192.0.2.11`). Installed via `nc` + `adb_shell` from the HA
container.

### What's working
- Full browsing: Home (hero + channel rail + content rows), Browse grids (Movies / Series / My List),
  Search with on-screen keyboard, Movie detail, Series detail (seasons + episodes).
- Multi-user profiles ("Who's watching?"), device pairing, localized UI (en / da / fo).
- **Player (R14):** direct-play + HLS via ExoPlayer/Media3; player chrome with seek bar, skip
  ±10 s/30 s, Audio & Subtitles picker, next-up countdown card, in-player episode rail for series.
  Video renders via `TextureView` (`PlayerVideoSurface` expect/actual seam) so the Compose chrome
  overlays correctly.
- Config editor at `/ravilo`: drag-reorder, show/hide, hero height, tile shape, live preview.

### Recent fixes (2026-06-21 session — R31, FFmpeg exotic-codec decoder)
- Implemented R31 via the **decoder-dependency approach** (not a source vendor): new Android-only
  **`:ravilo-player`** GPL-containment module bundling jellyfin's `media3-ffmpeg-decoder` + a
  `DefaultRenderersFactory(PREFER)`, wired through `RaviloPlayerEngine` so `:ravilo-ui` / `:ravilo-web`
  stay GPL-clean. App Media3 bumped 1.7.1 → 1.8.0 (no 1.7.x decoder build exists). Builds + packages
  `libffmpegJNI.so` for all 4 ABIs (APK 18 → 21 MB); on-device DTS/TrueHD/AC3 verification pending.

### Recent fixes (2026-06-21 session — R30, native focus traversal)
- Replaced the hand-rolled focus engine (`FocusGrid` / `FocusRow` + per-item `requestFocus`) with
  **native Compose focus traversal**: lazy items no longer consume direction keys, so the framework
  moves focus, composes the off-screen item in the search direction, and scrolls it into view. Added
  `Modifier.focusRestorer()` per `LazyRow` / grid. Fixes the held-key **lag / stuck focus** on Home
  rows, the channel rail, and the Browse/Search grids. **`FocusEngine.kt` deleted** — so the R29
  empty-row `FocusGrid` guard and the 2026-06-20 `StaticContentRow` `animateScrollBy` note (both
  below) are now moot; that code is gone. Manual key handling is kept only for content actions (hero
  paging, the Search keyboard↔grid edges); the Player rail's virtual-focus model and the non-lazy
  keyboard / profile / settings screens are unchanged. See
  [R30](requirements/phase-R30-native-focus-traversal.md).

### Recent fixes (2026-06-21 session — R29, contract review fixes)
- **`playback/stop` always 400'd**: client encoded `PlaybackProgressRequest` vs the route's
  `PlaybackStopRequest` — Jellyfin never saw the stop, exact resume position lost. Now sends the right
  type.
- **On-device viewer settings never saved**: `putSettings` PUT a whole `RaviloConfig` (with
  `default_skin`) to a route expecting `ViewerSettingsRequest` (`skin`). Replaced with
  `putViewerSettings(...)`; `ViewerSettingsRequest` promoted to `:shared`.
- **Server JSON hardened** with `ignoreUnknownKeys = true` (matching the rest of the repo).
- **Viewer skin override**: new `viewerSkinOverride` + `effectiveSkin()` so a viewer's pick no longer
  overwrites the operator `defaultSkin`.
- Removed the synthesized/ignored `session_id`; guarded `FocusGrid` against empty rows; widened
  `autoAdvanceSeconds` clamp to `[0,120]`; reconciled the R26 spec and `:ravilo-player` deferral docs.

### Recent fixes (2026-06-20 session)
- **Player UI scale**: all dp/sp values reduced ~30 % from initial implementation (was designed at
  CSS-px scale; Android TV renders at xhdpi density, making everything too large).
- **Home screen horizontal scroll**: `StaticContentRow` `LazyRow` switched from
  `animateScrollToItem(focusedIndex)` (which jumped every tile to position 0) to
  `animateScrollBy(minimalOffset)` — now scrolls only enough to reveal the newly-focused tile's edge.

## Foundational decisions locked (constitution)

- **Control plane = jellystructure only; data plane = Jellyfin directly.** The client "logs into"
  only jellystructure (pairing); video/images stream from Jellyfin via brokered, scoped tokens.
- **Compose Multiplatform, two targets:** Android TV + Web/WASM **canvas** (browser video).
  Maximise sharing — screens/theme/components/focus/state live once in `:ravilo-ui`. The
  jellystructure "no Compose for Web" rule is scoped to the **admin** frontend; the two WASM bundles
  (admin DOM, Ravilo canvas) coexist.
- **Android player: direct ExoPlayer/Media3 for now.** The originally planned fork of
  `jellyfin-androidtv` `playback/*` into `:ravilo-player` (for DTS/TrueHD/AC3/E-AC3 via
  `media3-ffmpeg-decoder`) is **deferred**. The current `RaviloPlayerAndroid` uses ExoPlayer
  directly and covers all common formats. The fork remains the path if exotic codec support is
  needed later.
- **DTOs defined once** in `:shared`; reused by backend + admin frontend + both Ravilo clients.
- **Config is server-owned, per Jellyfin user, synced** across all a user's devices.
- **The TV renders server-composed layout & server-pushed state**; **Ravilo never mutates the
  library** (only playback progress, played/unplayed, per-user settings). **Non-admin** Jellyfin
  users are allowed.

## Design reference

- Visual target: `design/ravilo/` (`Ravilo TV.html`, `ravilo.css`, `ravilo-player.css`).
- Per-user config surface: `design/ravilo/ravilo-app.js` (drives R16/R26-R28).

## Open threads

- **`:ravilo-player` FFmpeg decoder (R31 ✓ done; on-device test pending):** the GPL-contained
  `:ravilo-player` module bundles jellyfin's `media3-ffmpeg-decoder` and the player prefers the FFmpeg
  renderers, so DTS/TrueHD/AC3 decode automatically — never a user-facing "choose a player" control
  (confirmed intent: Ravilo inherits jellyfin's automatic handling, adds only chrome/UX). **Remaining:**
  verify a real exotic-codec file plays on the TV. Vendoring jellyfin-androidtv's `playback/*` source
  stays the path only if its *format-selection* logic is later needed beyond the decoders.
- **WASM subtitle switching (R14 gap):** `selectSubtitleTrack` on `RaviloPlayerWasm` is a no-op
  because `TextTrackList.item()` is not bridged in Kotlin/WASM DOM bindings. Fix path: `@JsFun`
  interop bridge or wait for upstream bindings update.
- **Soveværelse TV (`192.0.2.12`):** same ADB key authorized. Deploy separately when needed —
  not targeted during development sessions.
