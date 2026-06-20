# Ravilo — Status

Living record of where **Ravilo** work stands. The
[`requirements/README.md`](requirements/README.md) is the single source of truth for which Ravilo
phases exist and their done/planned status; this file tracks current focus and context.

Ravilo is a sibling product **inside the jellystructure repo** — an Android TV **and** browser (WASM,
canvas) streaming front-end built from one **Compose Multiplatform** codebase, talking only to the
jellystructure backend.

_Last updated: 2026-06-20_

## Current state

**R01–R28 all done.** The app runs end-to-end on the stue TV (Sony BRAVIA XR-65X93K,
`192.0.2.11`). Installed via `nc` + `adb_shell` from the HA container.

### What's working
- Full browsing: Home (hero + channel rail + content rows), Browse grids (Movies / Series / My List),
  Search with on-screen keyboard, Movie detail, Series detail (seasons + episodes).
- Multi-user profiles ("Who's watching?"), device pairing, localized UI (en / da / fo).
- **Player (R14):** direct-play + HLS via ExoPlayer/Media3; player chrome with seek bar, skip
  ±10 s/30 s, Audio & Subtitles picker, next-up countdown card, in-player episode rail for series.
  Video renders via `TextureView` (`PlayerVideoSurface` expect/actual seam) so the Compose chrome
  overlays correctly.
- Config editor at `/ravilo`: drag-reorder, show/hide, hero height, tile shape, live preview.

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

- **`:ravilo-player` engine fork (deferred from R14):** vendor `jellyfin-androidtv` `playback/*`
  when DTS/TrueHD/AC3 passthrough is needed. Confirm upstream GPL-2.0-only-vs-or-later terms;
  preserve copyright notices in a `:ravilo-player/NOTICE` file.
- **WASM subtitle switching (R14 gap):** `selectSubtitleTrack` on `RaviloPlayerWasm` is a no-op
  because `TextTrackList.item()` is not bridged in Kotlin/WASM DOM bindings. Fix path: `@JsFun`
  interop bridge or wait for upstream bindings update.
- **Soveværelse TV (`192.0.2.12`):** same ADB key authorized. Deploy separately when needed —
  not targeted during development sessions.
