# Phase R260 — The player's Back is the phone's Back

> On the phone, the system back gesture inside the media player ends the app. The player has a
> deliberate two-step Back (R112: chrome showing → hide it; nothing showing → leave), but it only hears
> it as a **key event** — the thing a TV remote sends. Android's back gesture and back button never
> arrive as a key event; they go to the Activity's back dispatcher, where the root handler has been
> switched off for exactly this screen (so as not to race the player's own Back). Nothing is left to
> answer, and the platform's default answer is to finish the Activity. Reproduced on the Pixel 9
> 2026-09-18 on the Play build (1.26): two edge swipes in *Nimrods* → launcher; on relaunch the
> viewer is at *Who's watching?* with the film gone.

## Status

`Planned` — written 2026-09-18 from an owner report ("using the native android back button it just
closes the app, instead of actually going back"), traced against `main` and reproduced on the Pixel 9
the same day. **Dev-reviewed 2026-09-18 against `main` `05195d1f`** (see §Dev review at the bottom: a
hardware Back can reach both entrances, so the platform entrance is off on TVs and de-duplicated per
press elsewhere — FR-R260-1 amended; acceptance 6 moves to the bedroom TV). Not built. Client-only (`ravilo-ui` commonMain + the Android actual
that already exists). No backend, DTO, string or design change.

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken
through **R259**, admin through **234**. Ravilo-only, no admin pair. Siblings written the same day:
**R261** (fullscreen only while playing) and **R262** (Discover as one frame).

## Current state (traced against `main`, 2026-09-18)

Three pieces, each correct on its own, that together leave the phone's Back unanswered in the player:

1. **The player's Back lives in a key handler.** `PlayerScreen.kt` puts the whole decision tree in the
   root `dpadFocusable(onBack = { … })` block (~line 1421): picker open → picker back; episode rail →
   close; next-up card → stay; scrubbing → cancel; chrome visible → `hideChrome()`; else → `onBack()`
   (pop, which also ends the session per phase 180). `dpadFocusable` fires it from `Key.Back` /
   `Key.Escape` (`focus/FocusModifiers.kt:150`). `LiveTvPlayerScreen.kt:180` has the same shape.
2. **The root deliberately steps aside.** `RaviloApp.kt` computes
   `ownsItsOwnBack = dest is Dest.Player || dest is Dest.LiveTv` and passes
   `PlatformBackHandler(enabled = !ownsItsOwnBack && …)`. The comment records why: with both enabled,
   one physical Back on the TV exited the player instead of hiding the chrome first (found live on the
   bedroom TV). The root's `onKeyEvent` block steps aside for the same reason.
3. **The phone's Back is not a key event.** `RaviloRoot.kt`'s own doc comment on `PlatformBackHandler`
   says so: under gesture navigation (the Pixel default) a back swipe is handled by the
   `OnBackPressedDispatcher` and "never reaches Compose as a KeyEvent at all". The Android actual is
   `BackHandler(enabled, onBack)`. While the player is on top, **no enabled `BackHandler` exists**.

What the platform does when no callback is registered was measured rather than assumed. The comment
on `rememberExitAction` expects `moveTaskToBack` for a launcher-affinity root activity. On the Pixel 9
(Android 17, `targetSdk 36`, back invoked through `OnBackInvokedDispatcher`) the Activity was
**finished**: after the gesture, `dumpsys activity activities` lists no `ActivityRecord` for
`.phone.MainActivity` while the process (pid 9042) and the Recents entry survive; relaunch runs
`onCreate` again and lands on the profile picker. From the viewer's chair that is "the app closed".

Two consequences beyond the annoyance:

- **The session is stopped by the lifecycle, not by the player.** `PlayerLifecycleEffect`'s `ON_STOP`
  path reports the stop and detaches the surface, so Jellyfin is told; but the pop that would have
  restored the previous screen, the store's `onDispose`, and R248's *home_changed* round trip all run
  in a dying Activity. The viewer relaunches to a cold profile picker and Continue Watching that may
  or may not have caught the position.
- **The lock screen's contract is unverifiable.** FR-R244-8 says the system back is *not blocked* while
  locked and the build note says "the root's Back still leaves because the chrome is hidden". Today the
  system back while locked finishes the Activity, which is neither.

**Note on the test method.** In immersive mode the *first* edge swipe reveals the transient bars
(`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`); the second is the back gesture. Both R260 and R261 touch this
sequence — R261 is why the bars are hidden at all.

**Web is not affected.** The browser's Back pops through `installHashListener` regardless of chrome
state; `PlatformBackHandler`'s wasm actual is an empty body. That behaviour stands (see Non-goals).

## Requirements

**FR-R260-1 · One Back decision, two entrances.** The player's Back decision tree (picker → rail →
next-up → scrub → chrome → leave, and Live TV's equivalent) is hoisted out of the `dpadFocusable`
lambda into one function per screen, and **both** entrances call it: the existing key-event path
(TV remote, web keyboard) and the platform back path. The platform entrance is
`PlatformBackHandler(enabled = true) { playerBack() }` composed **inside** `PlayerScreen` /
`LiveTvPlayerScreen` — the screen that owns Back registers for it, rather than the root guessing.

**FR-R260-2 · The root keeps stepping aside.** `ownsItsOwnBack` and the root's key-event exclusion stay
exactly as they are. The bedroom-TV race they fixed must not come back: there is still exactly one
handler per Back press, it is now the player's on every path, and the root's is enabled on none.
Regression check: with the TV player's chrome visible, one remote Back hides the chrome and nothing
else happens.

**FR-R260-3 · Locked means locked, Back means leave.** While FR-R244-8's lock overlay is up, a system
back runs the same decision tree: the chrome is hidden under lock, so the result is *leave* — the
session ends through the normal pop (phase 180), never through Activity death. The overlay itself
still takes every touch; this requirement only makes its documented Back behaviour true.

**FR-R260-4 · Leaving is a pop, never a finish.** After any Back that leaves the player, the previous
screen is on screen with its state (the detail page that launched playback, with focus/scroll where
R139/R137 put it), the player's store has been closed by its `onDispose`, and the Activity is the same
instance. Finishing the Activity from inside the player is a defect, not a fallback.

**FR-R260-5 · Predictive back is declined here.** With a `BackHandler` enabled the system's predictive
back-to-home animation does not run for the player. That is the intended trade: a half-swiped
preview of the launcher behind a playing film is worse than none. No `enableOnBackInvokedCallback`
change, no per-screen opt-in elsewhere.

## Acceptance

Pixel 9, Play build, gesture navigation, an episode playing:

1. Chrome visible → one back gesture → chrome hides, playback continues, Activity unchanged.
2. Chrome hidden → one back gesture → the detail page, same Activity instance (`dumpsys activity
   activities` still lists `.phone.MainActivity` RESUMED), Jellyfin's session stopped once.
3. Picker sheet open → back gesture closes the sheet; season sheet, next-up card and a live scrub each
   take one gesture to dismiss before any gesture leaves.
4. Locked → back gesture leaves (FR-R260-3).
5. Three-button navigation (the Back button) behaves identically to the gesture in 1–4.
6. Stue TV, remote Back with chrome visible → hides chrome only (FR-R260-2 regression).
7. Live TV player on the phone: 1–2 hold.

## Non-goals

- Web: the browser's Back leaving the player in one step is the browser's contract and stays.
- iOS (R244 FR-R244-13's "the edge swipe leaves"): out of scope until an iOS target exists; the hoisted
  function is where it will plug in.
- Changing R112's two-step semantics, `CHROME_HIDE_MS`, or R251's keypress rules.

## Open questions

1. Should the *first* edge swipe in immersive mode be Back rather than "reveal the bars"? That is a
   window-behaviour choice (`BEHAVIOR_DEFAULT` vs `…TRANSIENT_BARS_BY_SWIPE`) that belongs with R261;
   YouTube and Netflix keep the two-swipe model. Lean: keep it.

## Dev notes

- The hoist is mechanical: `val playerBack: () -> Unit = remember(...) { { when { … } } }` (or a plain
  local fun capturing the same state) referenced from `dpadFocusable(onBack = playerBack)` and from
  `PlatformBackHandler(enabled = true, onBack = playerBack)`. Both screens are already commonMain and
  `PlatformBackHandler` is already an `expect` there, so no new seam.
- `HandsetLockOverlay` needs nothing: it never consumed Back, it only appeared to.
- The comment on `rememberExitAction` about `moveTaskToBack` describes an older Android; worth a
  one-line correction while in the file (observed: finish, on API 36 with the OnBackInvoked path).

## Dev review (2026-09-18, against `main` `05195d1f`)

The three pieces in *Current state* are exactly as described: the decision tree at
`PlayerScreen.kt:1421–1432` (picker → rail → next-up → scrub → chrome → leave) and
`LiveTvPlayerScreen.kt:180–186` (guide → number entry → leave — Live TV has no chrome-hide stage, which
"its equivalent" should be read as); `ownsItsOwnBack` at `RaviloApp.kt:679` gating
`PlatformBackHandler` at `:689` and the root `onKeyEvent` at `:701`; the Android actual is a bare
`BackHandler(enabled, onBack)`. No manifest sets `enableOnBackInvokedCallback`; `targetSdk = 36`.
The fix is right. One requirement would re-create the bug it cites.

1. **FR-R260-2's "exactly one handler per Back press" is asserted, not true.** The comment at
   `RaviloApp.kt:670–678` records, from the bedroom TV, that a single physical Back reached **both** a
   dispatcher-level `BackHandler` and a Compose key handler — that is the whole reason the root steps
   aside. FR-R260-1 registers a dispatcher-level handler *and* keeps the key handler, both calling
   `playerBack()`. On any device where one hardware key reaches both paths, one press runs the tree
   twice: chrome visible → hidden, then hidden → leave. From the sofa that is the original bedroom-TV
   defect, now inside the player. A phone with a Bluetooth keyboard or a gamepad's B button is the same
   case. **FR-R260-1 amended:**
   - the platform entrance is composed with `enabled = !isTvPlatform` — the TV stays byte-for-byte on
     today's key-only path, which is what FR-R260-2 meant to promise;
   - off the TV, the two entrances are de-duplicated per physical press: the key path sets a flag on
     Back's `KeyDown` and clears it on `KeyUp`; the dispatcher entrance returns without acting while the
     flag is set. A gesture or a nav-bar Back never sets it.
   - **Acceptance 6 moves to the bedroom TV** (the device the race was found on; the Stue TV never showed
     it) and gains: a hardware keyboard's Escape on the Pixel hides the chrome and does not also leave.
2. **Compose this outside `PlayerScreen`'s body.** `PlayerScreen` has already hit ART's register-count
   verifier once in a release build (R258's first fix attempt). The handler and its flag go in a small
   private `@Composable` of their own, called from both screens; phase 231's ART verification of the
   release APK is the gate.
3. **Two stale comments to correct in the same commit:** `PlayerScreen.kt:1939` ("the root's onBack sees
   no chrome and exits" — after this phase it is the player's own handler) and the `moveTaskToBack`
   description at `RaviloApp.kt:665` / `RaviloRoot.kt:88`, which the spec's own measurement contradicts
   (observed: the Activity is finished).
4. **Open question 1:** keep the two-swipe model; nothing here depends on it.

No other phase is needed first. R261 touches the same seam file but not the same lines.
