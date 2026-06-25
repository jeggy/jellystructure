# Phase R55 — Subtitle rendering surface + embedded text subs (FR-RV-S1)


## Problem
Subtitles are selectable in the player's Audio/Subtitles picker (R41) and carry rich
labels (R46), but on Android TV **no subtitle ever appears on screen**, and for most
library items the subtitle list is empty or non-functional. R41/R46 wired *labelling* and
*selection* but never the two things that make a text subtitle visible: a **render surface
for cues** and a **reliable source for embedded (muxed-in) subtitles**.

## Current state (as-is)
- **Render surface — none for cues.** `ravilo-ui/src/androidMain/.../seams/PlayerVideoSurface.kt`
  creates a bare `TextureView` and calls `exo.setVideoTextureView(it)` (RaviloPlayerAndroid.kt:76).
  ExoPlayer renders **video** to the TextureView, but **subtitle cues** are emitted by the
  text renderer to a separate `TextOutput`/`SubtitleView` that is **wired to nothing**.
  There is no `SubtitleView` and no `Player.Listener.onCues` anywhere in `:ravilo-ui`.
  `media3-ui` (which contains `androidx.media3.ui.SubtitleView`) is declared in
  `gradle/libs.versions.toml:47` but is **not a dependency of `:ravilo-ui`** (only
  `:ravilo-android` uses it). ⇒ A correctly selected, decoded subtitle has nowhere to draw.
- **Backend offers external subs only.** `PlaybackService.buildSubtracks()`
  (`src/linuxX64Main/.../tv/PlaybackService.kt:109`) filters
  `it.type == "Subtitle" && it.isExternal && (isTextSubtitleStream || isTextSubCodec)` —
  so only **sidecar** `.srt`/`.ass` files become VTT sideloads. Subtitles **muxed into the
  container** (the common case) are excluded, with the code "trusting" ExoPlayer to extract
  them in-container — which is unconfigured and unreliable.
- **What already works and is reused:**
  - The Android `load()` already maps each `SubTrack` to a Media3
    `MediaItem.SubtitleConfiguration` (URL→MIME, language, label, FORCED/DEFAULT flags)
    (RaviloPlayerAndroid.kt:46–69) — sideloaded externals already become TEXT tracks.
  - `subtitleTracks` getter (RaviloPlayerAndroid.kt:160) and `selectSubtitleTrack(index)`
    (line 100) enumerate/override TEXT groups from `exo.currentTracks` — selection works
    once a track exists; index −1 disables (Off).
  - Shared DTOs already carry everything: `SubTrack`/`StreamTicket`
    (`shared/.../tv/Models.kt:63,89`); the picker (`PlayerScreen.kt` `choosePick()` line 210,
    "Off" = option 0 → index −1) and `PlayerStore` need no changes.
  - **Web already renders text subs natively** via `<track>` elements
    (`RaviloPlayerWasm.kt:37`) — this phase is **Android-only** for rendering.

## Requirements

### Render surface (Android) — Gap A
1. Add `implementation(libs.androidx.media3.ui)` to `ravilo-ui/build.gradle.kts`
   (androidMain, beside the existing media3 lines ~60–62). The version alias already exists.
2. Expose the player's text output to the surface. In `RaviloPlayerAndroid.kt`, add
   `fun setSubtitleView(view: SubtitleView)` that registers a `Player.Listener` forwarding
   `onCues(cueGroup: CueGroup)` → `view.setCues(cueGroup.cues)`. Apply TV-appropriate
   styling (`setFractionalTextSize`, a `CaptionStyleCompat` with a subtle shadow/outline so
   white text reads over bright frames).
3. In `PlayerVideoSurface.kt` (androidMain), replace the bare `TextureView` factory with a
   `FrameLayout` that contains the `TextureView` **and** a `SubtitleView` overlay
   (match-parent, above the video, below the Compose chrome), wiring both:
   `player.setVideoTextureView(texture)` + `player.setSubtitleView(subtitleView)`.
   Do **not** adopt Media3's full `PlayerView` — all transport chrome is custom Compose; we
   only need its cue rendering.

### Offer embedded text subs (backend) — Gap B
4. In `PlaybackService.buildSubtracks()`, **drop `&& it.isExternal`**. Keep the text-codec
   guard (`it.isTextSubtitleStream || isTextSubCodec(it.codec)`) so PGS/VobSub image subs —
   which can't be VTT-converted — are still excluded (they're R56). Jellyfin's existing
   `/Videos/{id}/{id}/Subtitles/{index}/0/Stream.vtt` extracts **embedded** text subs to VTT
   on demand, so the same sideload path now covers embedded SRT/ASS/SSA/MOV_TEXT.
5. No model/client changes: the new tracks flow through the existing
   `StreamTicket.subtitles` → `load()` → `SubtitleConfiguration` path unchanged.

### De-dupe (polish)
6. With direct-play MKV, ExoPlayer may *also* surface an embedded text sub that we now
   sideload, showing it twice. De-dupe in the `subtitleTracks` getter
   (RaviloPlayerAndroid.kt:160) by language/label, preferring the sideloaded (server-labelled)
   entry. Cosmetic; may follow the core fix.

## Invariants
- **Data plane = Jellyfin directly; control plane = jellystructure.** Subtitle VTT bytes
  stream from Jellyfin's `…/Subtitles/…/Stream.vtt` URL (scoped token), unchanged.
- **One shared chrome; the engine/surface is the only `actual`.** Web keeps its native
  `<track>` rendering; only the Android surface gains a `SubtitleView`.
- **Renders server-pushed state only** — the subtitle list is server-composed; the client
  doesn't synthesize tracks.

## Out of scope (→ R56)
- Image-based subtitles (PGS / VobSub / DVDSub) — they can't be served as VTT and need
  native in-container rendering or server burn-in.
- Jellyfin `PlaybackInfo` / DeviceProfile negotiation and any transcoding.
- Web dynamic subtitle **switching** (the `<track default>` initial selection stays; the
  TextTrackList bridge remains deferred per `RaviloPlayerWasm.selectSubtitleTrack`).

## Acceptance
- An MKV with an **embedded** SRT/ASS subtitle: the track appears in the picker and
  **renders on screen** when selected; "Off" hides it; reselect re-shows it; FORCED/DEFAULT
  behave. An item with an external `.srt` still works (regression). Web still renders text
  subs. Builds: `:ravilo-ui` android + the linuxX64 backend.
