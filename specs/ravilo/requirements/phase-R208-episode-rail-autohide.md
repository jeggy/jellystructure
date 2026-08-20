# Phase R208 — Ravilo TV: episode rail auto-hides after 30s inactivity (FR-RV-EPRAIL-1/2)

> **Renumbered from R196 2026-08-21**: the design side authored this spec as R196 without knowing
> the dev tracker had already spent that number on `phase-R196-remembered-track-regression.md`
> (✓ Done, landed 2026-08-14). STATUS.md's Ravilo table was dense/sequential through R207 at the
> time this was pulled in, so R208 is the real next-free number. The code (commit `3f9ce337`) and
> this file are updated to match; no functional change.

> The in-player episode rail (opened via the "Episodes" chip while watching a series episode, shipped
> in R14) has no inactivity timeout of its own — `openEpRail()`/`epRailOpen` are explicitly excluded
> from the ambient chrome auto-hide guard (`CHROME_HIDE_MS`), and nothing else ever closes it. Reported
> live: once opened it stays on screen indefinitely unless the viewer explicitly backs out or picks an
> episode. This phase gives it its own inactivity timeout.

**Status:** Implemented.

## Requirements

### FR-RV-EPRAIL-1 — 30s inactivity auto-hide
While the episode rail is open, an inactivity timer of **30 seconds** auto-closes it via the existing
close path (`closeEpRail()` in the JS mockup; `epRailOpen = false` in `PlayerScreen.kt`) — not a new
dismiss path. Any input while the rail is open (nav left/right between episode cards, or any other key)
resets the timer back to 30s; it never fires while the viewer is actively browsing. This mirrors a gap
that exists elsewhere in the player too — the Live TV Now/Next guide overlay has the identical
"guarded out of the ambient hide timer, nothing else ever closes it" pattern — but that overlay is
explicitly out of scope for this phase.

### FR-RV-EPRAIL-2 — auto-hide reuses the existing slide-down close, no new animation
Closing the rail — by timeout, by explicit back, or by selecting an episode — must go through the one
close path each implementation already has:
- JS mockup: removing the `.player.eprail` class already drives `.pl-eprail`'s existing
  `transform: translateY(112%); opacity: 0` transition (.34s) — the timeout must call `closeEpRail()`,
  never toggle visibility any other way.
- Compose: `epRailOpen = false` already drives `AnimatedVisibility`'s existing
  `exit = slideOutVertically { it } + fadeOut(tween(250))` — the timeout must just flip that same state,
  not introduce a second exit transition.

No new CSS or animation code is needed in either implementation — both already slide the rail down on
close for every existing dismiss path. This phase only has to fire that same path from an idle timer.

## Invariants
- **30 seconds of inactivity, not 30 seconds of wall-clock time since open** — any rail-scoped input
  resets the countdown.
- **Same close path for every trigger.** Timeout, Back/Escape, and episode-select all end up calling
  the one existing close function/state flip — no parallel "hide instantly" branch added for the
  timeout case.
- **Chrome's own auto-hide timer stays untouched and still excludes the rail** — this phase adds a
  rail-scoped timer; it does not fold the rail into `CHROME_HIDE_MS`.

## Out of scope
- The Live TV Now/Next guide overlay has the same never-times-out gap (`guideOpen`, JS `armBarTimer`
  guard excludes `overlay === 'nownext'`) — not touched here; a candidate for a later phase if wanted.
- Changing the open animation, layout, thumbnail content, or focus behavior of the rail — untouched.
- The 30s value is a starting point, not empirically tuned against real viewing sessions — revisit if
  live use shows it's too eager or too lax.

## Source references
- `design/ravilo/ravilo-player.js` — `EPRAIL_HIDE_MS = 30000` constant; new `armEpRailTimer()`
  (mirrors `scheduleHide()`'s style), called from `openEpRail()` and `epNav()`; `closeEpRail()` and
  `load()` both `clearTimeout(epRailTimer)`; `exit()` teardown clears it too.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` —
  `EPRAIL_HIDE_MS = 30_000L` constant beside `CHROME_HIDE_MS`/`CURSOR_HIDE_MS` (~line 114-116); new
  `LaunchedEffect(epRailOpen, chromeRevision)` alongside the existing chrome-hide effect (~line
  816-825) — reuses `chromeRevision` as the activity signal since `wake()` already bumps it from every
  D-pad handler, including rail nav, so no new input plumbing was needed.

## Dev-review addendum (2026-08-20 — implementation notes)
1. Verified via `:ravilo-ui:compileKotlinWasmJs` and `:ravilo-ui:compileDebugKotlinAndroid` — both
   pass. Not on-device/live-scan verified this session (deploy is user-initiated, per standing
   preference).
2. No new timer/activity plumbing was needed on the Compose side beyond the one `LaunchedEffect` —
   `chromeRevision` (bumped by `wake()`, called first in every D-pad handler) already doubles as the
   rail's own activity signal.
