# Phase R31 — `:ravilo-player` engine fork (FR-RV31)

**Status:** ✓ Done — codec support implemented via the **decoder-dependency approach** (2026-06-21).
On-device exotic-codec playback is not yet verified. The literal source-vendor fork described below
was judged redundant and **not** done.

## Implemented (2026-06-21)
- New Android-only **`:ravilo-player`** module — the GPL-containment boundary; only `:ravilo-android`
  links it — bundling `org.jellyfin.media3:media3-ffmpeg-decoder` (GPL-3.0, Maven Central) + a
  `DefaultRenderersFactory(EXTENSION_RENDERER_MODE_PREFER)` (`RaviloRenderers`).
- App **Media3 bumped 1.7.1 → 1.8.0** (no 1.7.x build of the decoder exists).
- Wired via `RaviloPlayerEngine.renderersFactoryProvider` (set in `MainActivity`) so `:ravilo-ui` and
  `:ravilo-web` stay GPL-clean; `RaviloPlayerAndroid` falls back to default renderers when unset.
- `NOTICE` records the GPL component + containment. Builds + packages `libffmpegJNI.so` for all 4 ABIs
  (APK 18 → 21 MB). **Remaining:** verify a real DTS/TrueHD/AC3 file plays (needs a deploy + test file).

The scoping plan below records the original intent; the source-vendor fork remains the path only if
jellyfin's *format-selection* logic is later needed beyond the decoders themselves.

## Problem
R14 ships the Android player on **direct ExoPlayer/Media3**, which covers all common formats. The one
gap is **exotic-codec passthrough/decode** — DTS, TrueHD, AC3/E-AC3 — which jellyfin-androidtv handles
via `org.jellyfin.media3:media3-ffmpeg-decoder` plus its tuned device-profile / direct-play-vs-transcode
logic. The intent ([[ravilo-player-fork-decision]], confirmed 2026-06-21) is that Ravilo **inherits
jellyfin-androidtv's playback behavior automatically** — never a user-facing "choose a player" control;
Ravilo adds only chrome/UX on top.

## Current state (as-is)
- `RaviloPlayer` is an `expect`/`actual` seam (`ravilo-ui/.../seams/RaviloPlayer.kt`); the comment
  already notes "full forked jellyfin-androidtv engine in R14 final."
- The Android `actual` (`RaviloPlayerAndroid`) is **direct ExoPlayer/Media3**.
- The seam is **byte-stream only** — start/stop/progress already live in `PlayerStore`/`TvApiClient`,
  so the control plane needs no rework.
- `:ravilo-player` module does **not exist**.

## Goal / non-goals
- **Goal:** automatic format/codec handling matching jellyfin-androidtv, incl. FFmpeg software decoders
  for DTS/TrueHD/AC3/E-AC3; same Ravilo Compose chrome on top.
- **Non-goals:** forking jellyfin-androidtv's UI / nav / settings; any "pick a player" control; any
  change to the Web `actual`.

## Approach
1. **`:ravilo-player`** — a new **Android-only** module = the **GPL-containment boundary**. Only
   `:ravilo-android` links it; `:ravilo-ui` and `:ravilo-web` never do (keeps `:shared`/common + the web
   bundle GPL-clean).
2. **Vendor only `playback/*`** from jellyfin-androidtv: Media3 player wiring, device-profile /
   capability builder, codec selection, `media3-ffmpeg-decoder`, subtitle/trickplay. **Strip** its
   Jellyfin control-plane calls.
3. **Seam unchanged:** reimplement only `RaviloPlayerAndroid` on the vendored engine behind the existing
   `expect class RaviloPlayer`. No interface change; Web `actual` untouched.
4. **Resolution through `/api/tv/**`:** client capabilities → `POST /api/tv/playback/start` → `StreamTicket`
   (jellystructure builds the Jellyfin device profile, resolves direct-play vs HLS); progress/stop via the
   existing TV routes. jellystructure stays the only control API.
5. **Licensing:** repo is already GPL-3.0; preserve upstream notices, add `:ravilo-player/NOTICE`, confirm
   jellyfin-androidtv's GPL-2.0-only-vs-or-later at vendoring time (FFmpeg decoder is GPL-3.0).

## Migration steps
1. Scaffold `:ravilo-player` + `NOTICE` + Gradle GPL containment (only `:ravilo-android` depends on it).
2. Vendor `playback/*`; remove control-plane calls.
3. Reimplement `RaviloPlayerAndroid` on the vendored engine.
4. Wire capability negotiation → `POST /api/tv/playback/start`.
5. Verify DTS / TrueHD / AC3 + subtitles + trickplay on the stue TV.
6. Drop or keep direct-ExoPlayer as a fallback path.

## Risks / open questions
- jellyfin-androidtv GPL-2.0 **only**-vs-**or-later** wording (affects the GPL-3.0 repo combination).
- Pinning an upstream revision + ongoing rebase/maintenance burden of a vendored fork.
- `media3-ffmpeg-decoder` AAR build complexity + APK-size increase.
- WASM subtitle-switching gap (R14) is separate and unaffected.

## Trigger / recommendation
Build **only when** a real library file fails to play on the TV (the exotic-codec gap). Until then,
direct ExoPlayer/Media3 already satisfies the "automatic, no chooser" intent for common formats.

## Out of scope
- The native-focus work (done in [R30](phase-R30-native-focus-traversal.md)); UI/nav/settings stay
  Ravilo's own Compose.
