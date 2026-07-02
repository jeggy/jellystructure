# Phase R157 — Ravilo web: a working media player (visible video, clickable controls) (FR-RV-WP1)

## Problem
The web player is effectively broken: **no video is visible (audio only)** and **none of the on-screen
buttons react to mouse clicks** — only keyboard/D-pad navigation works.

## Root cause (verified in code — both are structural, not bugs in the video pipeline)
1. **The video is painted over.** The wasm player creates a real `<video>`
   (`RaviloPlayerWasm.kt:17-23`: `position:fixed; …; object-fit:contain; z-index:0`, appended to
   `document.body`), but the Compose canvas sits **above** it
   (`ravilo-web/src/wasmJsMain/resources/index.html:18-26`: `canvas#ComposeTarget { z-index: 1 }`), and
   `PlayerScreen`'s root paints an opaque fill (`Modifier.fillMaxSize().background(Color.Black)`,
   `PlayerScreen.kt:360`). The wasm `PlayerVideoSurface` actual is an **empty Box**
   (`seams/PlayerVideoSurface.kt:8-10`) — nothing ever makes the canvas transparent over the video. So
   the element plays (audio audible) behind an opaque black canvas. (R77's `object-fit:contain` is
   present and fine.)
2. **The transport controls have no pointer targets.** The player's `SkipButton` / `PlayPauseButton` /
   `TrackButton` / `BackButton` are bespoke Boxes with **no click/tap handler** (`PlayerScreen.kt:722-733,
   849-946`); the only tap handler is on the **root** Box (`:361-476`), whose select action fires
   whatever control currently holds **D-pad focus** — so a mouse click anywhere activates the focused
   control, never the button under the cursor. (Everywhere else the app uses
   `dpadFocusable(onSelect)`, which **does** handle taps — `FocusModifiers.kt:82-91`; the player is the
   one screen that bypasses it.)

## Requirements

### FR-R157-1 — Video is visible (transparent player scene)
1. While the player is open on the web target, the Compose scene must be **transparent where the video
   shows through**: enable canvas alpha for the wasm target and make the player screen's opaque fills
   platform-conditional (an expect/actual `playerBackdropColor` — `Color.Black` on Android where the
   surface is in-scene, `Color.Transparent` on wasm where the video sits behind the canvas), including
   any app-root background above it in the composition chain while `Dest.Player` is on top.
2. Player chrome (scrims, controls, next-up card, subtitles) keeps rendering normally in Compose — over
   the transparent areas, i.e. visually over the video.
3. **Fallback (only if canvas alpha proves infeasible in skiko):** promote the `<video>` above the
   canvas with `pointer-events:none` while chrome is hidden and drop it behind when chrome is summoned —
   explicitly second-choice (video vanishes while chrome is up) and to be replaced when alpha works.
4. Element sizing stays viewport-fixed with `object-fit:contain` (already correct); aspect handling
   unchanged.

### FR-R157-2 — Controls are real pointer targets
1. Every transport control (play/pause, both skips, audio/subs, next, back) adopts
   `dpadFocusable(onSelect = …)` so taps/clicks activate **that** control (the same modifier already
   provides tap handling app-wide). D-pad behaviour is unchanged.
2. **Pointer hover moves focus** to the hovered control (so the visual focus state follows the mouse and
   Enter/click agree on the target).
3. The **seek bar becomes click-to-seek** (and drag-to-scrub) on pointer targets.
4. The root-level tap changes meaning on web: a click on empty space **toggles the chrome**
   (show/hide), the web convention — it no longer activates the focused control. (Android/TV semantics
   untouched.)

### FR-R157-3 — Pointer chrome conventions
1. Mouse movement wakes the chrome (same path as D-pad activity); the chrome auto-hide timer applies.
2. The cursor auto-hides after ~2 s of inactivity during playback and reappears on move.

## Scope
- `ravilo-ui` wasm actuals (`RaviloPlayerWasm.kt`, `PlayerVideoSurface.kt` wasm, backdrop expect/actual),
  `PlayerScreen.kt` (controls → `dpadFocusable`, hover-focus, seek-bar hit target, root-tap semantics
  per target), `ravilo-web` `index.html`/bootstrap (canvas alpha).
- No backend change (`StreamTicket`/HLS negotiation untouched).

## Non-goals
- No codec/HLS work — playback itself already runs (audio proves it).
- No touch-gesture system (mobile web); pointer basics only.
- No change to the Android/TV player beyond the shared `dpadFocusable` adoption on controls (which is a
  no-op for D-pad usage) — the focused-state styling itself is **R158**.

## Acceptance
- Playing any title on the web shows the picture, correctly letterboxed, with chrome overlaid on top of
  it.
- Every control activates on mouse click exactly like on Enter; hovering highlights the hovered control;
  clicking the seek bar jumps to that position.
- Clicking empty space toggles chrome; the cursor disappears during hands-off playback.
- TV/Android behaviour is unchanged (verified by the existing D-pad flows).
