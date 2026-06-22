# Phase R14 — Player: `RaviloPlayer` expect/actual (FR-RV14)

**Status:** Planned · _play the bytes, from Jellyfin, report progress to jellystructure._

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
- **GPL-contained decoder:** the Android engine is direct Media3/ExoPlayer; Jellyfin's prebuilt
  `media3-ffmpeg-decoder` lives in the Android-only `:ravilo-player` module — this makes the **Android
  app GPL** but does not infect web/backend. (A source-vendor fork of `jellyfin-androidtv` was skipped
  as redundant — see R31.)
- **The player never calls Jellyfin's control plane:** its stream-resolution + progress-report seams are
  rewired to `/api/tv/**`. The only Jellyfin traffic from the device is the **byte stream** (data plane).
- One shared player **chrome**; the engine is the only platform-specific part (`actual`).
- Progress/played writes go through jellystructure → Jellyfin, never client→Jellyfin.

## Out of scope
- Transcoding/quality selection beyond direct-play vs HLS (Jellyfin defaults initially).
- Offline/downloads; PiP.
- Adopting the rest of `jellyfin-androidtv` (UI, navigation, settings) — **only** the `playback/*`
  engine is forked; everything else is Ravilo's own Compose code.
