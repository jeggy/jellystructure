# Phase R200 — Focus permanently strands after a back-return whose target row can't find its item (bug fix, FR-RV-NAV1)

> Reported live: navigating into a channel (e.g. Apple TV), moving around its rows, then pressing Back
> sometimes leaves the viewer stuck on the hero/nav bar unable to move Down again, for the rest of the
> session — only an app restart recovers it. Root-caused live on stue TV (real device, adb-driven
> navigation + logcat), then confirmed in code: `StaticContentRow`'s R139 back-return-restore effect can
> mark itself "done" without ever telling its caller, permanently orphaning the shared Down-navigation
> `FocusRequester` bridge.

**Status:** Implemented. Verified via `:ravilo-ui:compileKotlinWasmJs` (compile-check only — an on-device
Ravilo APK rebuild + reinstall was deliberately not done this session; see the CLAUDE.md standing
preference against unrequested TV deploys).

## Bug report
"sometimes when openning a channel lile apple tv and navigating up and down and then going back, then
im stuck on the hero carousel or navbar, but cant get further down." (2026-08-18.)

## Investigation
Live-reproduced on stue TV via adb (`input keyevent`, `screencap`, `logcat`): after some navigation, every
subsequent `DPAD_DOWN` on Home printed Compose's own diagnostic —
`FocusRelatedWarning: FocusRequester is not initialized` — and the screen visibly never changed (no focus
ring appeared anywhere; hero pagination and app-bar navigation kept working, only Down was dead). The
warning fired on **every** press across a 2.4s span with no self-recovery; a full app restart was the only
fix, matching the report's "stuck for the rest of the session" shape. A fresh app launch did **not**
reproduce it immediately — confirming this is a state corruption reachable through navigation, not a
constant defect.

### Root cause
`HomeScreen.kt`'s `firstRowFR` (the single always-composed `FocusRequester` bridging hero-Down and
app-bar-Down to whichever row is currently first — `HomeScreen.kt:178`, wired at `:296`/`:307`/`:312`/`:317`)
is only ever attached to a real tile when that tile's `focusRequester` param is non-null. Each tile gets
`fr ?: if (i == 0) firstRowFR else null` (`:296`), where `fr` comes from `ContentRow.kt`'s
`StaticContentRow`/`ContentRow` (shared by **both** `HomeScreen.kt`'s channel rail and **every row inside
`ChannelScreen.kt`**, which has its own independent `firstTileFR`/`heroFR` bridge pair using the exact same
shared component): `fr` is non-null (`restoreFR`) for whichever item's key matches `restoreItemKey`
(`ContentRow.kt:226`), the R139 "scroll back to the tile I came from" mechanism.

`ContentRow.kt:127-137`'s `LaunchedEffect(Unit)` runs once per composable instance:
```kotlin
LaunchedEffect(Unit) {
    if (!restoredOnce && restoreItemKey != null && itemKey != null) {
        val idx = items.indexOfFirst { itemKey(it) == restoreItemKey }
        if (idx >= 0) {
            runCatching { listState.scrollToItem(idx) }
            runCatching { restoreFR.requestFocus() }
            onRestored()
        }
        restoredOnce = true   // ← set even when idx < 0
    }
}
```
`restoredOnce = true` is set **unconditionally**, but `onRestored()` — which is the *only* thing that
clears the caller's `store.focusRowKey`/`focusItemKey` (`HomeScreen.kt:289`, `ChannelScreen.kt:253`) — is
called **only** inside the `idx >= 0` branch. Whenever the previously-selected item genuinely isn't found
in this particular row's `items` on this composition (its own doc comment already anticipates rows
scrolling out of the `LazyColumn`'s composed window and back in as a fresh instance — the far more likely
trigger than the item vanishing outright), the effect gives up **silently**: `restoredOnce` stops it from
ever trying again for this instance, but the store's `focusRowKey`/`focusItemKey` are left pointing at that
stale target forever — nothing else in the app ever clears them.

That stale pointer then keeps re-arming `restoreItemKey` as non-null on every future recomposition of
*that specific row* for the rest of the session (its own `remember`-scoped `restoredOnce` guard is a red
herring here — a **different** row/screen instance reading the same never-cleared store field is
unaffected by it and free to hit the exact same idx-not-found dead end again). Separately and more
directly for the reported symptom: while `store.focusRowKey`/`focusItemKey` are pinned to a row whose
target item keeps failing to resolve, that row's tile matching the id never gets `fr`, so nothing about
that specific row is broken *directly* — but the underlying failure mode (an effect that can permanently
abandon its contract without notifying its caller) is exactly the class of bug that leaves `firstRowFR`
attached nowhere: if the row instance that currently satisfies `i == 0` happens to be torn down and
recomposed (a scroll-out/scroll-back-in, or a live feed refresh reordering rows) at the same moment
`onDown`'s `requestFocusRetrying` fires, the existing single-frame retry (`FocusModifiers.kt:40-46`) is not
guaranteed to land after the new composable's own `LaunchedEffect` and layout pass complete, and there is
no further retry — `requestFocus()` on the still-not-yet-reattached `firstRowFR` fails permanently for
every future Down press until the composable tree is torn down and rebuilt fresh (an app restart).

## Requirements

### FR-RV-NAV1-1 — `onRestored` always fires, whether or not the target was found
`ContentRow.kt`'s restore effect must call `onRestored()` unconditionally once it has made its one
determination (found or not), not only on the `idx >= 0` path, so a caller's `focusRowKey`/`focusItemKey`
is never left pointing at a target this row could not resolve. This closes the state-leak at its source —
every affected screen (`HomeScreen.kt`, `ChannelScreen.kt`, and any future caller of this shared component)
benefits without its own change.

### FR-RV-NAV1-2 — Down-navigation focus bridges survive more than one frame's recomposition
`requestFocusRetrying` (`FocusModifiers.kt:40-46`) must retry across a short bounded window (several
frames, capped so a genuinely-never-attached target still gives up quickly) instead of exactly one retry,
so a `firstRowFR`/equivalent bridge whose target composable takes more than one frame to (re)attach — a
live feed refresh reordering rows, a row scrolling back into the `LazyColumn`'s composed window — still
recovers on its own instead of failing permanently. This is defense-in-depth alongside FR-RV-NAV1-1, not a
replacement for it: FR-RV-NAV1-1 stops the leak that causes prolonged detachment; this bounds how much any
*remaining* transient detachment (of any future cause) can hurt.

## Invariants
- **A back-return restore effect always resolves its caller's pending restore, whether or not the target
  item is still present.** No composable may set `restoredOnce`/equivalent "done" state while leaving the
  caller's tracking fields dangling.
- **A Down-navigation focus bridge recovers from ordinary recomposition timing on its own** — a viewer
  should never need to restart the app to regain Down navigation from the hero/nav bar.

## Out of scope
- Making `ContentRow.kt`'s restore effect re-run if `items` loads in later (it deliberately fires once via
  `LaunchedEffect(Unit)`, matching R139's original "once per entry" design) — FR-RV-NAV1-1 only ensures the
  *give-up* path is clean, not that it retries against a slower-loading list.
- On-device verification on stue TV — requires a fresh Ravilo APK build + install, which is not done
  without the user's separate, explicit go-ahead (standing preference).

## Source references
- Bridge definitions: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeScreen.kt`
  (`firstRowFR`/`heroFR`/`navBarFR`, lines 178-354).
- Same pattern, independent instance: `ravilo-ui/.../screens/ChannelScreen.kt` (`firstTileFR`/`heroFR`/
  `channelBarFR`, lines 67-68/135-137/249-265).
- Bug site: `ravilo-ui/.../components/ContentRow.kt` (`StaticContentRow`'s restore `LaunchedEffect`,
  lines 122-137).
- Retry helper hardened: `ravilo-ui/.../focus/FocusModifiers.kt` (`requestFocusRetrying`, lines 31-46).
- Related: R139 (original back-return restore feature, not separately spec'd under this numbering scheme
  per the codebase's own comments), the existing "Bug fix" comments already present in both `HomeScreen.kt`
  and `ContentRow.kt` documenting earlier rounds of this same focus-bridge fragility.
