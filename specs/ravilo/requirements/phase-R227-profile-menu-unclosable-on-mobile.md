# Phase R227 — Profile menu couldn't be closed on mobile

> Live bug report: on the Android phone build, opening the avatar dropdown ("Switch profile" /
> My List / Settings / Add user / Sign out / Unpair) left the user stuck — no way to close it and
> get back to Home.

## Status
Implemented (2026-09-03).

### Implementation notes (2026-09-03)
- **Root cause confirmed in code, not guessed:** `profileMenuOpen` (`RaviloApp.kt:373`) is local
  `remember`ed overlay state — it is not pushed onto the `stack` nav model at all. Two independent
  back paths exist above it and neither knew about it:
  1. `ProfileMenu`'s own `dpadFocusable(onBack = onClose)` (`ProfileMenu.kt:109`) only fires on a
     real Compose `KeyEvent` with `Key.Back`/`Key.Escape`. A TV remote's physical Back key genuinely
     dispatches that event, so the menu closed fine on TV.
  2. Android's system back gesture/button does **not** reach Compose as a `KeyEvent` under gesture
     navigation — it's intercepted by `OnBackPressedDispatcher` first, exactly the gap
     `RaviloApp.kt`'s own comment above `PlatformBackHandler` already documents for screens without
     an on-screen back affordance. That dispatcher-level handler (`RaviloApp.kt:572`, pre-fix) was
     gated purely on `stack.size > 1 || atHomeRoot` — `profileMenuOpen` played no part, so pressing
     system Back with the menu open either popped the screen underneath it or (at Home root)
     called `exitApp()`, while the menu itself stayed rendered on top, untouched.
  - **Fix:** `PlatformBackHandler`'s `enabled` condition and handler body at `RaviloApp.kt:572-576`
    now check `profileMenuOpen` first — `if (profileMenuOpen) profileMenuOpen = false else if
    (stack.size > 1) pop() else exitApp()`. One property, same file, no new state.
  - Tap-outside-to-close was **not** added — `ProfileMenu.kt`'s existing doc comment already records
    that click-away isn't modeled anywhere else in the app (no pointer click-away convention to
    match), and the reported bug was specifically "can't close it," not "expected a tap-outside."
    Not revisited here; still a candidate follow-up if raised again.
  - The raw `onKeyEvent` Back-key block further down (`RaviloApp.kt:~584`, keyboard/physical Back on
    web/desktop) was left unchanged — it only ever runs for real `KeyEvent`s, and on those platforms
    `ProfileMenu`'s own `dpadFocusable` is the topmost focusable and already consumes the event
    first (Compose focus dispatch bubbles from the focused node up), matching the existing
    TV-remote-works-fine behavior. No parity gap there.

## Problem
`ProfileMenu` (R170) is rendered as a same-tier overlay alongside the debug/message overlays, gated
by `profileMenuOpen`, entirely outside the `stack`-based nav model the rest of the app's Back
handling is built around (`RaviloApp.kt:1155-1174`). Every other overlay/screen either owns a stack
entry (so `pop()` naturally closes it) or has its own back affordance drawn on screen (Player/LiveTv's
`ownsItsOwnBack`). `ProfileMenu` had neither — it relied solely on a keyboard-shaped Back key event
that mobile touch input never produces.

## Goal
Opening the profile menu on a touch-only device (phone, tablet, or any Android build without a
physical/IR remote) and pressing the system Back button/gesture closes the menu and returns to
whatever screen was underneath — matching what already happened on TV.

## Requirements

### FR-R227-1 — System back closes an open profile menu first
While `profileMenuOpen` is true, the platform back action (gesture, button, or `KeyEvent`) must
close the menu and consume the action — it must never fall through to popping the underlying screen
or exiting the app in the same press.

### FR-R227-2 — No regression to existing Back behavior
With the menu closed, platform back behaves exactly as before: `pop()` when `stack.size > 1`,
`exitApp()` at Home root, no handling at all while `ownsItsOwnBack` (Player/LiveTv) is on screen.

## Invariants (must not change)
- `ProfileMenu`'s own `dpadFocusable(onBack = onClose)` stays as-is — it's still the path that
  closes the menu on TV and via any real `KeyEvent` (keyboard, remote), and continues to win the
  race against the dispatcher-level handler on those platforms since it's the topmost focusable.
- No tap-outside/click-away affordance added — not required to fix the reported bug, and would be a
  separate product decision (see implementation notes).
- No change to the `stack`/`Dest` nav model — `profileMenuOpen` remains local overlay state, just
  now consulted by the one handler that needed to know about it.

## Non-goals
- Tap-outside-to-close.
- Moving `ProfileMenu` into the `stack` nav model (would change its transition/back semantics more
  broadly than this fix requires).
- Any change to `ProfileMenu`'s TV/remote behavior, which already worked correctly.

## Acceptance
- Phone: open the avatar menu from Home, press system Back (gesture or button) — menu closes,
  Home stays put, app does not exit.
- Phone: open the avatar menu from a pushed screen (e.g. Browse), press system Back — menu closes,
  that screen (not Home) remains visible; a second Back press pops as normal.
- TV: unchanged — physical remote Back still closes the menu exactly as before.
- Web/desktop keyboard Back/Escape: unchanged — still closes the menu via `ProfileMenu`'s own
  handler.

## Source references
- Bug: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/ProfileMenu.kt:104-109`
  (doc comment already flagged the missing click-away/back-affordance risk under §R170).
- Fix: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/RaviloApp.kt:571-583`
  (`ownsItsOwnBack`, `PlatformBackHandler` call), `:373` (`profileMenuOpen` declaration),
  `:1155-1174` (`ProfileMenu` overlay call site).
- Prior art for the same platform-back gap: `RaviloApp.kt:550-559`'s own doc comment on
  `PlatformBackHandler` bridging Android's system gesture back into `pop()`.

## Relationships
- Fixes a latent gap in **R170** (profile-hub/Discover merge, which introduced `ProfileMenu`).
- Same failure shape as the gesture-back bridge **R170**'s host phase already had to solve once for
  the nav stack — this phase closes the same gap for the one piece of overlay state that stack-level
  fix didn't cover.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the
  row from the design side.**
