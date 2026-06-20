# Phase R14 — Player: `RaviloPlayer` expect/actual (FR-RV14)

**Status:** ✓ Done (2026-06-20)

## Problem
Ravilo must play media **directly from Jellyfin** (data plane) on both targets, with one shared player
UI/chrome, resume, and progress reporting — while the byte stream never transits jellystructure.

## Current state (as-is)
- R08 provides `playback/start` (→ `StreamTicket`), `playback/progress`, `playback/stop`, `mark`.
  `:ravilo-ui` declares `expect RaviloPlayer` (R02). No real player.
- The **Android engine is forked from `jellyfin-androidtv`** (constitution decision): the best-tested
  playback stack in the ecosystem. The fork is **GPL** and is contained in a new **`:ravilo-player`**
  module. Web stays on the browser media stack.

## Requirements

### Engine (`actual`s)
1. **Android `actual` = the player engine forked from `jellyfin-androidtv`**, vendored into the
   Android-only **`:ravilo-player`** module (the GPL containment boundary):
   - Fork `playback/core` (player + play-queue + media-session abstraction) and `playback/media3`
     (Media3/ExoPlayer backend) **including `org.jellyfin.media3:media3-ffmpeg-decoder`** so DTS/
     TrueHD/AC3/E-AC3 and other audio stock ExoPlayer can't decode still play.
   - Keep their subtitle rendering (ASS/SSA/PGS), trickplay, and audio-passthrough handling.
   - **Re-point the control-plane seams:** upstream `playback/jellyfin` resolves streams and reports
     progress to Jellyfin **directly** — replace those with the `StreamTicket` (in) and
     `/api/tv/playback/*` (out). The fork decodes/renders; jellystructure stays the only control API.
   - Expose it behind the shared `expect RaviloPlayer` so `:ravilo-ui` chrome is engine-agnostic.
2. **Web `actual` = browser-native video**: a DOM `<video>` element bridged to Compose (positioned
   with the skiko canvas), with **`hls.js`** for HLS and **JASSUB/libass** for ASS/SSA subtitles. We
   **do not fork `jellyfin-web`** (GPL TS/JS; the browser decodes regardless — no decoder advantage) —
   its `htmlVideoPlayer` is a reference only. The Android `playback/*` fork is never linked by
   `:ravilo-web`.
3. Both consume the `StreamTicket` (direct-play container or HLS URL + Jellyfin base + scoped token +
   resume position) from `POST /api/tv/playback/start`. `:ravilo-player` sends its **client
   capabilities** with the start call so the server resolves direct-play vs HLS (R08). The player
   streams **directly from Jellyfin** (jellystructure not in the byte path) and starts at the ticket's
   resume position.

### Chrome (shared Compose)
4. A focus-aware player overlay: play/pause, seek bar with scrubbing, skip ±10s, position/duration,
   title (and S·E for episodes), and — for series — a **"Next episode"** affordance near the end.
5. D-pad / key / pointer controls; auto-hiding chrome; **Back** exits playback to the detail screen.

### Reporting & binge
6. Send `playback/progress` heartbeats (~10s, on pause/seek); `playback/stop` on exit with the final
   position. On finishing an episode, offer/auto-advance to the **next-up** episode (server pointer),
   and let the server mark played + advance next-up.
7. After exit, the detail/home state reflects the new watched/resume position on next load (optionally
   optimistic in the just-watched item).

## Invariants
- **Data plane = Jellyfin directly**; control/report = jellystructure (`/api/tv/**`). Bytes never
  transit jellystructure (unless the constitution's opt-in relay is enabled).
- **Engine forked, GPL-contained:** the Android engine is the forked `jellyfin-androidtv` playback
  stack in `:ravilo-player` — this makes the **Android app GPL** but does not infect web/backend.
  Track upstream `jellyfin-androidtv` playback changes when re-syncing the fork.
- **The fork never calls Jellyfin's control plane:** its stream-resolution + progress-report seams are
  rewired to `/api/tv/**`. The only Jellyfin traffic from the device is the **byte stream** (data plane).
- One shared player **chrome**; the engine is the only platform-specific part (`actual`).
- Progress/played writes go through jellystructure → Jellyfin, never client→Jellyfin.

## Out of scope
- Transcoding/quality selection beyond direct-play vs HLS (Jellyfin defaults initially).
- Offline/downloads; PiP.
- Adopting the rest of `jellyfin-androidtv` (UI, navigation, settings) — **only** the `playback/*`
  engine is forked; everything else is Ravilo's own Compose code.

## As built (2026-06-20)

The `:ravilo-player` fork was deferred. The Android `actual` uses **ExoPlayer/Media3 directly**
inside `:ravilo-ui` `androidMain`, sufficient for direct-play + HLS. The `media3-ffmpeg-decoder`
extension (for DTS/TrueHD/AC3) and the full `jellyfin-androidtv` playback engine fork remain
future work — tracked here when needed.

### Seams
- **`expect class RaviloPlayer`** (`commonMain/seams/`): `load(streamUrl, startPositionMs,
  subtitles)`, `play/pause/seekTo/release`, `selectAudioTrack(index)`,
  `selectSubtitleTrack(url?)`, read-only vals `positionMs / durationMs / bufferedMs /
  isPlaying / isEnded / audioTracks`.
- **`expect fun PlayerVideoSurface(player, modifier)`** (`commonMain/seams/`): Android actual
  creates a `TextureView` via `AndroidView` and calls `exo.setVideoTextureView()` so video
  renders into the Compose surface (no punch-through). WASM actual is a no-op `Box`; the
  `<video>` element is fixed-positioned behind the skiko canvas via CSS.
- **Android actual** (`androidMain`): `ExoPlayer.Builder` + `Media3`; subtitle tracks via
  `MediaItem.SubtitleConfiguration` (VTT/SRT/ASS MIME detection); audio selection via
  `TrackSelectionOverride`; buffered position from `exo.bufferedPosition`.
- **WASM actual** (`wasmJsMain`): DOM `<video>` with `<track>` children for subtitles.
  `bufferedMs` from `video.buffered.end(length-1)`. `selectSubtitleTrack` is a no-op stub
  (Kotlin/WASM DOM bindings lack `TextTrackList.item()`; the `<track default>` attribute
  handles initial selection).

### Chrome (`PlayerScreen.kt`, `commonMain`)
- Auto-hiding overlay (3.6 s idle timer via `chromeRevision` + `LaunchedEffect`).
- **Seek bar** (Canvas): background track + buffered fill + accent-gradient played fill +
  scrub ghost line + animated handle; left/right on the focused bar scrubs by ~1.2% of
  duration per press.
- **Transport controls**: skip −10 s / +30 s pills, play/pause circle (54 dp), Audio & Subs
  picker button, optional Next Episode button.
- **Track picker popup**: Audio / Subtitles tabs with radio-button option list; left/right
  on the D-pad switches tabs.
- **Next-up card** with 8 s countdown ring (Canvas arc); auto-advances on zero; "Watch
  credits" stays-through button.
- **Episode rail** (series only): slides up from the bottom, `LazyRow` of episode cards
  with progress bars and NOW PLAYING badge.
- **Pause flash**: brief centered icon flash on play/pause toggle.
- **`EpisodePlayContext`** + **`PlayerEpisodeEntry`** data classes thread the full episode
  list from `SeriesDetailScreen` through `Dest.Player` so the rail and next-ep navigation
  work without an extra API call.

### Navigation
`onNavigateToEpisode` in `RaviloApp` replaces the top of the back stack with a new
`Dest.Player` targeting the selected episode, preserving the episode list reference.
`SeriesDetailScreen.buildEpisodeContext()` constructs the context (kicker, next-ep pointer,
full episode list, current index) from the already-loaded `SeriesDetail` at both the
Play/Resume button and per-episode card tap sites.
