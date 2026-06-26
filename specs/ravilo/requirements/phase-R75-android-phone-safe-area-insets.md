# Phase R75 — Android phone: safe-area insets for system bars (FR-RV-SA1)

## Problem
On the Pixel 9 Pro (and any Android phone running the `:ravilo-phone` build) the app content
draws under the status bar — the AppBar, hero text, and top nav items are partially hidden behind
the camera cutout, system clock, and notification icons. The bottom content can also bleed under
the gesture navigation bar.

## Root cause
`MainActivity` (`:ravilo-phone`) correctly calls `WindowCompat.setDecorFitsSystemWindows(window, false)`
to opt into edge-to-edge drawing, but no inset padding was applied to the content at any level.
Without consuming the `WindowInsets.safeDrawing` the system provides, every composable starts at
pixel 0,0 — behind the status bar.

The TV build (`ravilo-android`) is unaffected: leanback immersive mode hides all system bars so
insets are zero.

## Goal
All interactive content (AppBar, nav items, hero metadata, buttons) sits within the OS-declared
safe drawing area on the phone. Backgrounds and the app surface still fill the full screen.

## Fix
Apply `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` to the outermost `Box` in
`RaviloApp.kt`. `WindowInsets.safeDrawing` (Compose Foundation) covers:
- **Top**: status bar height + display cutout (camera hole)
- **Bottom**: gesture navigation bar or three-button nav bar
- **Sides**: camera cutout if in landscape

On the TV/Wasm targets `WindowInsets.safeDrawing` is zero, so the modifier is a no-op and the
shared `commonMain` code needs no platform split.

## Requirements
1. `RaviloApp.kt` outer `Box` carries `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`.
2. All screens and the AppBar receive correct top/bottom clearance automatically — no per-screen
   change required.
3. TV and Wasm builds unaffected (insets resolve to zero on those targets).

## Scope
- `ravilo-ui/src/commonMain/.../ui/RaviloApp.kt` — add `windowInsetsPadding(WindowInsets.safeDrawing)`
  to the back-intercept `Box`.

## Notes
This is a functional-first fix: content clears the status bar area. The hero backdrop does not
bleed under the status bar (edge-to-edge hero treatment is follow-up work). The phone build is
deployed as a release APK (`ravilo-phone-1.0-release.apk`).
