# Phase R169 — Ravilo web player: keep the video visible while the controls are shown (FR-RV-WP2)

> On the web player, showing the transport overlay (play/pause + other buttons) turns the **entire video
> area black**; the moment the overlay auto-hides, the picture returns perfectly. The controls should
> **overlay the video** (video stays visible, dimmed only where the scrims actually are — exactly like the
> Android/TV player), not black out the whole frame.

**Status:** ✓ Done — implemented via the **FR-R169-3 fallback**, not FR-R169-2. Follows **R157** (made
the web video visible at all) and **R158** (player focus chrome). Web (`wasmJs`) target only;
**Android/TV is unaffected** and stays pixel-identical (`PlayerChromeBridge` is a no-op there).

## Implementation note (2026-07-04) — why the fallback, not the transparent-canvas fix
Investigated FR-R169-2 first: confirmed `ComposeViewportConfiguration.isWindowTransparent` genuinely
exists in this exact CMP 1.9.3 wasmJs `.klib` (new evidence the original spec didn't have — it only
hedged "may... allow"), so canvas transparency is technically *possible*. But `ComposeViewport` takes a
**container id** and creates its **own** canvas inside it — it does not adopt an existing `<canvas>`
element — whereas `index.html`'s hand-authored `#ComposeTarget` **is itself** the canvas (`<canvas
id="ComposeTarget">`), and the manual keyboard/gamepad-replay dispatch
(`document.getElementById('ComposeTarget').dispatchEvent(...)`) targets that exact element directly.
Migrating would mean turning `#ComposeTarget` into a container `<div>`, with no way to verify — without a
live browser — that the input dispatch still reaches whatever canvas `ComposeViewport` creates inside it.
A mistake there breaks **D-pad/gamepad navigation app-wide**, not just the player screen. Given no way to
interactively verify a browser-only regression of that severity in this environment, implemented the
explicitly-sanctioned **DOM-overlay fallback (FR-R169-3)** instead — bounded to the player screen only,
additive (Android untouched), and the existing z-index-swap mechanism is **kept exactly as-is**, just
re-triggered by a narrower condition (`pickerOpen || nextUpVisible || epRailOpen` instead of
`chromeVisible`) so the video only ever demotes behind the canvas for the picker/next-up/episode-rail
overlays — which Compose still draws unchanged. The basic transport (play/pause, skip ±, seek, time) is a
small DOM/CSS bar (`PlayerChromeBridge` + its wasmJs actual), reusing a native `<input type="range">` for
scrubbing rather than hand-rolled drag math. **FR-R169-2 remains a valid future upgrade** — the
`isWindowTransparent` finding is a real lead for whoever picks it up, now backed by evidence instead of
speculation — but requires interactive browser verification of input dispatch before landing.

## Problem
Web only: with the chrome **shown**, the whole picture is black behind the controls; with the chrome
**hidden**, the video plays perfectly. There's also an **initial-open black flash** — the player opens
with chrome visible (and thus black) until the ~3.6 s auto-hide fires.

## Root cause (verified in code — the task's own R157 premise is now outdated)
R157 originally hoped to make the Compose canvas transparent (`opaque = false`) so a `<video>` behind it
would show through. **That was abandoned**: this CMP version's `CanvasBasedWindow` exposes no canvas-alpha
parameter (`ravilo-web/.../Main.kt:14-17`; `PlayerBackdrop.kt:5-9`). The current design instead **z-order
swaps the DOM `<video>` around an *opaque* canvas**, driven straight by chrome visibility:

- The canvas is **opaque**, `z-index: 1` (`ravilo-web/src/wasmJsMain/resources/index.html:18-26`), and
  the web player backdrop is `Color.Black` (`ravilo-ui/.../seams/PlayerBackdrop.kt:10`) — so the canvas
  paints solid black everywhere Compose isn't drawing something else.
- The `<video>` starts **behind** the canvas at `z-index: 0`, `pointer-events: none`
  (`ravilo-ui/src/wasmJsMain/.../seams/RaviloPlayerWasm.kt:28`).
- **`setChromeVisible` swaps the video's z-index by chrome state**
  (`RaviloPlayerWasm.kt:33-35`): `zIndex = if (visible) "0" else "2"` — chrome visible → video **behind**
  the opaque canvas (occluded → **whole frame black**); chrome hidden → video **above** the canvas
  (visible).
- Wired to the chrome-visibility state at `ravilo-ui/.../screens/PlayerScreen.kt:370`
  (`LaunchedEffect(chromeVisible) { player.setChromeVisible(chromeVisible) }`); `chromeVisible` starts
  `true` (`:160`), which is why the open flashes black.

**The intent was:** when chrome shows, sink the video so Compose's chrome paints over it. **The flaw:**
the canvas is opaque across the *whole* frame, but the chrome is only **gradient bands** at top
(`:745-756`, 230 dp) and bottom (280 dp) plus a **partial dim** (`:547-555`, α 0.34–0.50). The large
middle region — where the picture should show through, merely dimmed — instead gets the full-bleed opaque
black canvas. So "show controls" = "black out everything," not "dim behind the controls."

The fundamental tension of the z-index-swap-against-an-opaque-canvas design is binary:
- video **above** canvas → video visible, but Compose chrome (painted on the canvas *below*) is hidden
  behind the video;
- video **below** canvas → chrome visible, but the opaque canvas blacks out the whole video.

There is no partial state — which is exactly the reported all-or-nothing symptom. (The same limitation is
the documented web trade-off in **R163**'s trailer chrome.)

## Requirements

### FR-R169-1 — Video stays visible under the controls
1. On web, while the chrome is **shown**, the video must remain **visible** behind the controls, dimmed
   only by the actual scrims/gradients (top/bottom bands + the partial dim) — i.e. the same result the
   Android/TV player already produces, where controls overlay a still-visible picture. Showing controls
   must **never** black out the middle of the frame.

### FR-R169-2 — Primary approach: genuine compositing via a transparent canvas (Android parity)
2. Achieve real video-under-Compose compositing on web by making the surface the video sits behind
   **transparent** where Compose isn't painting opaque pixels — the clean fix that reproduces Android's
   behaviour and **keeps the existing Compose chrome** (no chrome rewrite):
   - Re-evaluate canvas/scene transparency in the **current** stack (CMP **1.9.3**, Kotlin 2.3.21). R157's
     blocker was specific to the deprecated `CanvasBasedWindow` API; **`ComposeViewport`** (its 1.9
     replacement — already flagged as a TODO at `Main.kt:7-10`) and/or a skiko transparent-framebuffer
     path may now allow a transparent canvas that `CanvasBasedWindow` did not.
   - With a transparent canvas: `<video>` sits behind (z-index 0), the canvas is transparent (z-index 1),
     and Compose paints **only** the player's semi-transparent scrims + controls — which composite over
     the live video exactly as on Android. `setChromeVisible`'s z-index swap and the opaque `Color.Black`
     web backdrop are then **removed** (backdrop → transparent; video stays behind at all times).
   - The migration must preserve the existing web mechanics that depend on the `#ComposeTarget` canvas:
     the manual key/gamepad dispatch in `index.html` and the `pointer-events: none` video (clicks pass
     through to Compose). `Main.kt:7-10` calls this out as the rework required to move off
     `CanvasBasedWindow`.

### FR-R169-3 — Fallback if a transparent canvas remains infeasible
3. If canvas transparency still can't be achieved in this stack, keep the `<video>` **always promoted
   above** the canvas and render the player **chrome as a DOM/HTML overlay above the video** (the R163
   trailer-chrome precedent — a fixed DOM layer over the video), rather than sinking the whole frame
   behind an opaque canvas. This preserves a visible picture with legible controls at the cost of not
   using Compose to paint the chrome. It is explicitly **second choice** (loses Compose-drawn chrome /
   focus styling) and should be replaced by FR-R169-2 if/when transparency works.

### FR-R169-4 — No black flash on open
4. Opening the player must show the **picture immediately**, not a black frame until the first auto-hide.
   (Falls out naturally from FR-R169-2: with the video always behind a transparent canvas, `chromeVisible
   = true` at open no longer blacks anything out. Under the FR-R169-3 fallback, start with the video
   visible.)

### FR-R169-5 — Android/TV untouched
5. No behavioural or visual change to the Android/TV player. There the video is **in-scene** (TextureView
   inside `PlayerVideoSurface`), `setChromeVisible` is a **no-op** (`RaviloPlayerAndroid.kt`), and
   `playerBackdropColor = Color.Black` shows only as letterbox bars. All of that stays exactly as-is.

## Invariants
- **Web-only change.** The commonMain player logic (chrome timers, focus, controls) and the entire
  Android path stay behaviourally identical; only the web layering/compositing changes.
- **Compose-drawn chrome is preferred** (FR-R169-2) — the DOM-overlay fallback (FR-R169-3) is a documented
  second choice, not the target.
- **Clicks still reach Compose** — the video keeps `pointer-events: none` (or the DOM-overlay handles its
  own input), so the R157 pointer-controls work is not regressed.
- **Playback pipeline untouched** — HLS/hls.js, `<track>`/JASSUB subtitles, Media Session, audio/subtitle
  pickers all stay as-is; this is purely a display-layering fix.

## Out of scope
- Codec / HLS / subtitle-rendering work (playback itself is fine — the video shows when chrome is hidden).
- The **R163 trailer** overlay (separate surface; it already documents the same web z-order trade-off and
  is not changed here, though a successful FR-R169-2 transparent-canvas result could later benefit it).
- Any Android/TV player change.
- New pointer/touch gestures beyond keeping R157's existing behaviour working.

## Source references / anchors
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt` — video style
  `:28` (z-index 0, `pointer-events:none`), `setChromeVisible` z-index swap `:33-35`.
- `ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/PlayerBackdrop.kt:10` — web backdrop
  `Color.Black` (to become transparent under FR-R169-2).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` — `chromeVisible`
  `:160`, `wake()` `:223`, auto-hide `:362-366` (`CHROME_HIDE_MS = 3600`), `setChromeVisible` wiring
  `:370`, root fill `:418`, dim scrim `:547-555` (α 0.34–0.50), chrome gradient bands `:745-756`.
- `ravilo-web/src/wasmJsMain/resources/index.html:18-26` — canvas `z-index:1`, opaque; `Main.kt:7-18`
  (`CanvasBasedWindow`, no opaque param, `ComposeViewport` migration TODO).
- `ravilo-ui/src/androidMain/.../seams/PlayerVideoSurface.kt` (in-scene TextureView),
  `RaviloPlayerAndroid.kt` (`setChromeVisible` no-op), `androidMain/.../PlayerBackdrop.kt` (Black) — the
  untouched Android path.
- Related: **R157** (web video visibility + pointer controls — this completes its chrome-overlay gap),
  **R158** (player focus chrome), **R163** (documents the same web z-order trade-off; DOM-overlay
  precedent for the FR-R169-3 fallback).
