# Phase R77 — Player: automatic aspect-ratio correction (letterbox / pillarbox)

> The video player must always preserve the source's native display aspect ratio. When the screen
> and the video do not share the same ratio, fill the gap with black bars — never stretch, never crop.
> No user picker is required or wanted: there is exactly one correct behaviour.

## Problem

`PlayerVideoSurface` unconditionally stretches video frames to `fillMaxSize()` regardless of source
geometry. Every title that is not exactly 16:9 arrives distorted:

| Content type | DAR | Symptom |
|---|---|---|
| 2.39:1 scope cinema (most modern films) | wide | people squished vertically |
| 1.85:1 flat cinema (many US films) | slightly wide | subtle vertical squeeze |
| 4:3 SD (older TV shows, classic films) | 4:3 | people squished horizontally |
| Anamorphic (DVDs, some Blu-rays) | encoded at 720×480 @ SAR 32:27 = 16:9 | correct DAR only if SAR is applied |

This is immediately visible and breaks immersion. The Jellyfin official TV client offers
Auto / Zoom / Stretch but "Auto" (which is our target behaviour) is not the default, causing the
same bug there until the user changes it. We want Auto **always**, with no picker.

## Root cause

### Android (primary: TV + phone)

`ravilo-ui/src/androidMain/kotlin/.../seams/PlayerVideoSurface.kt`:

```kotlin
val frame = FrameLayout(ctx)
val texture = TextureView(ctx)
frame.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
// …
AndroidView(factory = { frame }, modifier = modifier)  // modifier = Modifier.fillMaxSize()
```

- `TextureView` is given `MATCH_PARENT` × `MATCH_PARENT` → stretches to the FrameLayout, which
  fills `fillMaxSize()`.
- `RaviloPlayerAndroid.kt` has a single `Player.Listener` override for subtitle cues only — **no
  `onVideoSizeChanged()` is registered**, so the app never learns the video's geometry.

### Web

`ravilo-ui/src/wasmJsMain/kotlin/.../seams/RaviloPlayerWasm.kt`:

```kotlin
v.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;background:#000;z-index:0"
```

- No `object-fit` property → browser default `fill` stretches the video frame to the element
  dimensions.

## Goal

Both targets display video at its **true display aspect ratio (DAR)**, letterboxed or pillarboxed
inside the black screen. No user action required; no picker is shown.

## Requirements

### A. Android — capture VideoSize from Media3

`ravilo-ui/src/androidMain/kotlin/.../seams/RaviloPlayerAndroid.kt`

New imports: `androidx.media3.common.VideoSize`, `kotlinx.coroutines.flow.MutableStateFlow`,
`kotlinx.coroutines.flow.StateFlow`.

1. Add a public `StateFlow<VideoSize>` field to the Android actual class, backed by a private
   `MutableStateFlow`, initialised to `VideoSize.UNKNOWN`:

   ```kotlin
   private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
   val videoSize: StateFlow<VideoSize> = _videoSize
   ```

2. Register a **dedicated** `Player.Listener` for video geometry — do **not** piggy-back on the
   subtitle-cue listener inside `setSubtitleView()` (that listener exists only to forward cues and is
   coupled to subtitle setup). Register it once when the player is built (in the `exo` lazy
   initialiser, after `builder.build()`) or at the top of `load()`:

   ```kotlin
   exo.addListener(object : Player.Listener {
       override fun onVideoSizeChanged(size: VideoSize) { _videoSize.value = size }
   })
   ```

   `onVideoSizeChanged` fires each time the decoded video geometry becomes known or changes
   (initial decode, seek to a different format, ABR quality switch).

3. **DAR calculation** (done in the composable, not here — just store the raw `VideoSize`):

   ```
   DAR = width * pixelWidthHeightRatio / height
   ```

   `pixelWidthHeightRatio` (sample aspect ratio, SAR) accounts for anamorphic encoding (e.g. DVD
   720×480 @ SAR 32:27 = effective 853×480 ≈ 16:9). A `pixelWidthHeightRatio` of 1.0f means square
   pixels (all modern streaming content). **Rotation is not handled here:** on API 21+ with a
   `TextureView` (our render path) ExoPlayer applies any rotation itself and reports
   `unappliedRotationDegrees = 0`, and a Jellyfin movie/series library has no portrait/rotated
   source — so `width`/`height` already reflect the on-screen orientation. No width/height swap.

### B. Android — constrain the video surface to DAR

`ravilo-ui/src/androidMain/kotlin/.../seams/PlayerVideoSurface.kt`

New imports: `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue`,
`androidx.compose.foundation.layout.aspectRatio` (via `Modifier.aspectRatio`),
`androidx.compose.foundation.layout.Box`, `androidx.compose.ui.Alignment`.

4. Collect `player.videoSize` as Compose state:

   ```kotlin
   val videoSize by player.videoSize.collectAsState()
   ```

5. Compute the display aspect ratio (square-pixel + anamorphic; no rotation branch — see A.3):

   ```kotlin
   val dar: Float = run {
       val w = videoSize.width
       val h = videoSize.height
       if (w <= 0 || h <= 0) return@run 0f
       w * videoSize.pixelWidthHeightRatio / h
   }
   ```

6. Wrap the `AndroidView` in a centering `Box`:

   ```kotlin
   Box(
       modifier = modifier,          // fillMaxSize() — fills the screen, black behind from PlayerScreen
       contentAlignment = Alignment.Center,
   ) {
       AndroidView(
           factory = { … },         // unchanged FrameLayout + TextureView + SubtitleView
           modifier = if (dar > 0f) Modifier.aspectRatio(dar) else Modifier.fillMaxSize(),
       )
   }
   ```

   - When `dar > 0`: Compose sizes the `AndroidView` to exactly the given ratio within the available
     space. The outer `Box` fills the screen; the unused area stays transparent, showing the black
     background of `PlayerScreen` → **letterbox / pillarbox bars**.
   - When `dar == 0` (`VideoSize.UNKNOWN` on startup, or a degenerate stream): fall back to
     `fillMaxSize()`, matching current behaviour until the real size is known.

7. **`SubtitleView` follows the video frame, not the full screen.** It is already a child of the
   `FrameLayout` inside the `AndroidView`, so it inherits the constrained (DAR-sized) dimensions
   automatically — no change needed. **Behaviour change to note:** cues now render *within the video
   rectangle* rather than spanning the whole screen. For letterboxed (scope) content the bottom cue
   line moves up into the picture instead of sitting in the lower black bar. This is the standard
   player-of-record behaviour (Jellyfin/ExoPlayer `PlayerView` does the same) and is acceptable; if a
   future phase wants cues anchored to the screen bottom, the `SubtitleView` would have to be lifted
   out to the outer `Box` — explicitly out of scope here.

8. **Recomposition on format switch:** `videoSize` is a `StateFlow`, so `collectAsState()` triggers
   recomposition whenever `onVideoSizeChanged` fires mid-stream (e.g. ABR quality switch at a
   different resolution). The `aspectRatio` modifier updates in place without rebuilding the view.

### C. Web — one CSS property

`ravilo-ui/src/wasmJsMain/kotlin/.../seams/RaviloPlayerWasm.kt`

9. Add `object-fit:contain` to the video element's inline CSS:

   ```kotlin
   v.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;object-fit:contain;background:#000;z-index:0"
   ```

   `object-fit: contain` is the CSS equivalent of "fit inside the box, preserve ratio, black bars
   from `background:#000`". This is the entire web change.

## Edge cases

| Scenario | Behaviour |
|---|---|
| `VideoSize.UNKNOWN` (early frames, seek, initial load) | `dar = 0f` → `fillMaxSize()` (slight stretch until first frame decoded, typically < 1 frame) |
| 16:9 content on a 16:9 screen | `dar ≈ 1.777…` → fills exactly, no bars |
| Anamorphic (SAR ≠ 1.0) | `pixelWidthHeightRatio` applied → correct DAR without bars |
| 90° / 270° rotated video (phone camera, not present in a Jellyfin film/series library) | ExoPlayer applies rotation to the TextureView; `width`/`height` already reflect it → handled, no swap needed |
| ABR format switch mid-stream | `onVideoSizeChanged` re-fires → Compose recompose → bars adjust |
| `width = 0` or `height = 0` (malformed stream) | guard → `dar = 0f` → fallback |
| Portrait 9:16 video (very rare on TV) | pillarboxed — two vertical black bars |

## What does NOT change

- No user-facing aspect-ratio picker. No Zoom / Stretch / Fill modes.
- `PlayerScreen.kt` call site: `PlayerVideoSurface(player, Modifier.fillMaxSize())` unchanged.
- Transport chrome (seek bar, controls, episode picker) layout unchanged.
- Subtitle rendering: `SubtitleView` moves with the video frame automatically (it is inside the
  constrained `AndroidView`).
- `RaviloPlayer` expect class seam: `videoSize` is an Android-only field — `PlayerVideoSurface` is
  itself an `actual` fun in `androidMain`, so it can access Android-actual properties directly
  without requiring a seam change.
- FFmpeg / renderer configuration unchanged.

## Out of scope

- User-selectable ratio (Zoom / Stretch). One mode, always: Auto/Contain.
- Trickplay thumbnail aspect ratio.
- Cropping / zoom-to-fill (a separate future concern if requested).
- Reporting DAR to the detail screen.

## Implementation surface

| File | Change |
|---|---|
| `ravilo-ui/src/androidMain/.../seams/RaviloPlayerAndroid.kt` | Add public `videoSize: StateFlow<VideoSize>` (private `MutableStateFlow` backing); register a **dedicated** `Player.Listener` with `onVideoSizeChanged` (not on the subtitle-cue listener) |
| `ravilo-ui/src/androidMain/.../seams/PlayerVideoSurface.kt` | Collect `videoSize` state; wrap `AndroidView` in centering `Box`; conditional `Modifier.aspectRatio(dar)` |
| `ravilo-ui/src/wasmJsMain/.../seams/RaviloPlayerWasm.kt` | Add `object-fit:contain` to video element CSS |

No backend change. No shared-model change. No `PlayerScreen.kt` change.

## Design reference

Jellyfin official Android TV client (`org.jellyfin.androidtv`): `VideoPlayerActivity` →
`PlayerGlue` → `AspectRatioFrameLayout.setResizeMode(RESIZE_MODE_FIT)` — exactly this behaviour,
exposed to the user as "Auto". We hardwire it and skip the picker.

Media3 API: `Player.Listener.onVideoSizeChanged(videoSize: VideoSize)`,
`androidx.media3.common.VideoSize` (`width`, `height`, `pixelWidthHeightRatio`,
`unappliedRotationDegrees`). `pixelWidthHeightRatio` doc: "The width to height ratio of each pixel,
or 1.0 if unknown." `VideoSize.UNKNOWN` has `width = 0`, `height = 0`.

CSS: `object-fit: contain` — MDN: "The replaced content is scaled to maintain its aspect ratio
while fitting within the element's content box. The entire object is made to fill the box, while
preserving its aspect ratio, so the object will be letterboxed / pillarboxed if its aspect ratio
does not match the aspect ratio of the box."

**FR-RV-AR1**
