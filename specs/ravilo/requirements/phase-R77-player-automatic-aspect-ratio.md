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

1. Add a `MutableStateFlow<VideoSize>` field to the Android actual class, initialised to
   `VideoSize.UNKNOWN`:

   ```kotlin
   val videoSize = MutableStateFlow(VideoSize.UNKNOWN)
   ```

2. In the existing `Player.Listener` registration, add:

   ```kotlin
   override fun onVideoSizeChanged(size: VideoSize) {
       videoSize.value = size
   }
```

   This is called by ExoPlayer/Media3 each time the decoded video geometry is known or changes
   (e.g. ABR format switch).

3. **DAR calculation** (done in the composable, not here — just store the raw `VideoSize`):

   ```
   DAR = width * pixelWidthHeightRatio / height
   ```

   `pixelWidthHeightRatio` (sample aspect ratio, SAR) accounts for anamorphic encoding (e.g. DVD
   720×480 @ SAR 32:27 = effective 853×480 ≈ 16:9). A `pixelWidthHeightRatio` of 1.0f means square
   pixels (all modern streaming content).

### B. Android — constrain the video surface to DAR

`ravilo-ui/src/androidMain/kotlin/.../seams/PlayerVideoSurface.kt`

4. Collect `player.videoSize` as Compose state:

   ```kotlin
   val videoSize by player.videoSize.collectAsState()
   ```

5. Compute the display aspect ratio and the effective rotation:

   ```kotlin
   val dar: Float = run {
       val w = videoSize.width
       val h = videoSize.height
       if (w <= 0 || h <= 0) return@run 0f
       val sar = videoSize.pixelWidthHeightRatio
       val rot = videoSize.unappliedRotationDegrees
       // Swap width/height for 90° or 270° rotations (portrait video, rare on TV)
       if (rot % 180 != 0) h * sar / w else w * sar / h
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

7. **`SubtitleView` must follow the video frame, not the full screen.** It is already a child of the
   `FrameLayout` inside the `AndroidView`, so it inherits the constrained dimensions automatically —
   no change needed.

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
| 90° / 270° rotated video (phone camera, rare on TV) | width and height swapped in DAR calc |
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
| `ravilo-ui/src/androidMain/.../seams/RaviloPlayerAndroid.kt` | Add `videoSize: MutableStateFlow<VideoSize>` field; add `onVideoSizeChanged` override in listener |
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
