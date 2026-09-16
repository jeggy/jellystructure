# Phase R223 — Detail screen: invisible season-picker focus, and rapid Up stranding the scroll below the hero

> Live UX pass on stue TV, 2026-09-02, requested after a standing report: *"I very often have trouble
> switching between seasons in the series details page within Ravilo. One example is Blå Blink."* and
> *"sometimes when opening the details page and navigating around and then going to the top again, it
> doesn't properly scroll, which means i cant see the series title or see the full image etc."* Both were
> reproduced live against the real deployment (device-testing grant covered this specific device/session)
> using Blå Blink (34/35 seasons in the library) and screenshots at every step; see the investigation
> below for exactly how.

**Status:** ✓ Built 2026-09-03. Not dev-reviewed, not re-verified on-device (the fix was implemented and
compiled clean across all 5 Ravilo targets, then deployed to both TVs at the user's request — but no
fresh live D-pad pass against the built APK was performed in this session to re-confirm the exact repro
steps now behave correctly).

## Bug 1 — a season pill loses its only strong focus indicator the instant it becomes selected

### Investigation
`SeasonPicker.kt:74-78`:
```kotlin
val isSelected = i == selectedIndex
var focused by remember { mutableStateOf(false) }
val scale        by animateFloatAsState(if (focused) RaviloMotion.PILL_FOCUS_SCALE else 1f, focusSpec, label = "pillScale$i")
val borderWidth  by animateDpAsState(if (focused && !isSelected) 2.dp else 0.dp, dpSpec, label = "pillBorder$i")
val glowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "pillShadow$i")
```
A focused-but-unselected pill gets a crisp 2dp `colors.focusRing` border (`.border(borderWidth, colors.focusRing, pillShape)`, line 111) on top of its dark `colors.surfaceVariant` fill — clearly legible from a couch. The moment a pill becomes selected (which, for the pill the viewer just pressed OK on, is also the moment it's still focused), `borderWidth` collapses to 0 — the `!isSelected` guard suppresses it deliberately. All that's left is `scale` (a ~1.03-1.08x grow) and `glowElevation` (a soft drop shadow), both still gated on `focused` alone with no `!isSelected` guard.

Live-verified this residual signal is real but weak: cropped the season-pill row from a screenshot taken the instant after selecting Season 35 (focused+selected) and from one taken after focus had moved away to the episode rail below (unfocused+selected) — `ImageChops.difference` found a nonzero diff confined to the pill's own bounding box, and the crops show the expected subtle size/glow difference, but it is genuinely hard to read at a glance, especially against a screenshot (worse from three-plus metres on a real living-room TV).

**Why a same-hue border wasn't the original fix and can't be the new one either** — `colors.focusRing` sits deliberately close to `colors.accent` in hue and lightness in every skin (`Colors.kt`):

| Skin | `accent` (pill fill when selected) | `focusRing` |
|---|---|---|
| Aurora | `#7B6EF0` | `#8E82FF` |
| Midnight | `#19D6C6` | `#28E6D6` |
| Noir | `#F5B542` | `#FFCF6B` |

A `focusRing`-colored border drawn over an `accent`-filled pill would itself be nearly invisible — almost certainly why the `!isSelected` guard exists at all, rather than being an oversight. The fix needs a genuinely contrasting color for exactly this one state, not just removing the guard.

**Real-world effect:** switching seasons on a long-running show (Blå Blink: 34-35, well past what fits one screen-width) leaves the viewer with no reliable "you are here" cue right when they most need it — immediately after the input that's supposed to confirm their choice. The natural next action (another Left/Right, or Down into episodes) has no visible starting point to reason from, which reads as the picker being unresponsive or unpredictable — the reported "trouble switching between seasons."

### Requirements

**FR-R223-1 — A focused season pill is always visibly focused, selected or not.**
- `borderWidth` is driven by `focused` alone (drop the `&& !isSelected` guard).
- Border color depends on selection state: `colors.focusRing` when unselected (unchanged), **`colors.onAccent`** when selected — the same token the pill's own label/badge text already switch to for legibility against the accent fill (`badgeText = if (isSelected) colors.onAccent else colors.text`, line 124), so this introduces no new color, just reuses the existing selected-state contrast pattern for the border too.
- `scale` and `glowElevation` are unchanged (they already work correctly for both states).

## Bug 2 — a fast burst of Up presses can strand the outer scroll position, hiding the title and top of the backdrop indefinitely

### Investigation
Reproduced on Blå Blink: from the true bottom of the page (the "More Like This" row), 15 rapid `DPAD_UP` events (no delay between them, simulating an impatient remote press rather than one press per frame) left the page scrolled to a **mid-hero offset** — synopsis and the Resume button visible, season picker visible below, but the title and the top third of the backdrop scrolled out above the fixed AppBar — with focus landed on the **Home** nav item. A further Up press did nothing at all; the state was genuinely stuck, not mid-animation (confirmed by waiting 1.5s and re-screenshotting). The only thing that recovered it was pressing **Down** from the nav bar — a backwards, non-obvious gesture for someone trying to reach the *top* of the page. The identical slow, one-press-at-a-time version of this same journey (four individual Up presses with settle time between each) worked correctly every time.

**Root cause: two different "go up" mechanisms coexist, and only one of them scrolls.**

`SeriesDetailScreen.kt:339-345` — `upToHero`, used by the season picker (line 619) and the lone-season episode rail (line 686):
```kotlin
val upToHero: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { ev ->
    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
        scope.launch { runCatching { listState.scrollToItem(0) } }
        requestFocusRetrying(scope, playFR)
        true
    } else false
}
```
This scrolls the outer `LazyColumn` back to item 0 **and** focuses Play, both as part of the same Up press.

But three other places move focus toward (or into) the nav bar from inside the hero, and **none of them touch `listState`** — they assume the list is already sitting at the top:

- `SeriesDetailScreen.kt:413` — the genre row's `onUp = { navBarFR.requestFocus() }`
- `SeriesDetailScreen.kt:441` — the synopsis's `onUp = { if (detail.genres.isNotEmpty()) genreFR.requestFocus() else navBarFR.requestFocus() }`
- `SeriesDetailScreen.kt:522` — the Play/My List/Trailer row's own `onKeyEvent` Up branch, same `else -> navBarFR.requestFocus()` fallback

(`MovieDetailScreen.kt` has the byte-for-byte identical structure at lines 256, 272 and 313 — and no `upToHero` equivalent at all, since movies have no season picker or episode rail to host one. The only place either detail screen unconditionally scrolls to item 0 is `AppBar`'s own `onDown` handler — `SeriesDetailScreen.kt:790` / `MovieDetailScreen.kt:443` — which is what actually fixed the stuck state in testing.)

The `Row` around Play/My List/Trailer also has an `onFocusChanged` (`SeriesDetailScreen.kt:501-511`) that nudges the list back toward offset 0 via `scrollBy` — but only *while already scrolled*, only by the **current** offset at the moment focus lands, and only as another separately-launched coroutine. It is a third, independent, similarly-racy scroll trigger, not a shared one.

With a normal, unhurried Up press, whichever mechanism fires has time to complete before the next key event arrives, so the list is always at 0 by the time focus reaches anywhere past the hero. Under a fast burst, an earlier `upToHero`'s `scope.launch { scrollToItem(0) }` can still be pending when a later Up event is processed by the Play-row's `onKeyEvent`, which sees focus already sitting on `playFR` (or beyond) and takes the "just move focus, assume we're already at the top" branch straight to `navBarFR` — landing focus on the always-visible AppBar overlay while the `LazyColumn` itself never finishes catching up. Because the AppBar sits outside the `LazyColumn` (last child of the outer `Box`), nothing about focusing it can ever bring it "into view" the way `LocalBringIntoViewSpec` does for items inside the list — so once focus is on the nav bar, there is no further automatic correction, and the list is stuck exactly where the race left it.

### Requirements

**FR-R223-2 — Every path that sends focus to the nav bar from within the hero also guarantees the list is at item 0, unconditionally, every time.**
Replace the three bare `navBarFR.requestFocus()` calls in each of `SeriesDetailScreen.kt` (lines 413, 441, 522) and `MovieDetailScreen.kt` (lines 256, 272, 313) with a shared helper — same shape as the existing `upToHero`:
```kotlin
val goToNavBar: () -> Unit = {
    scope.launch { runCatching { listState.scrollToItem(0) } }
    runCatching { navBarFR.requestFocus() }
}
```
Because this runs on **every** call that ends at the nav bar rather than depending on an earlier, different code path having already scrolled, the last Up event in any burst — however many preceded it, however fast — always issues its own `scrollToItem(0)`. There is nothing left to race: even if an earlier scroll is still in flight, the final one converges to the same end state (item 0, offset 0) instead of leaving the outcome dependent on event timing.

The existing `onFocusChanged`-driven `scrollBy` on the Play/My List/Trailer row (`SeriesDetailScreen.kt:501-511` / `MovieDetailScreen.kt:296-306`) is unchanged — it still gives the smooth reframe-on-focus behavior R72 was built for; FR-R223-2 only closes the gap for the paths that skip past it or race it.

## Invariants
- **A focused element is always visibly distinguishable from an unfocused one, on every skin, regardless of any other state (selected, watched, etc.) it also carries.** Bug 1 is a specific case of this; the fix must not special-case just the selected pill and leave some other future combination silently unreadable again.
- **No focus transition that leaves the scrollable list may assume a scroll performed by a different code path has already completed.** Every such transition issues its own, guaranteed scroll-to-target as an inseparable part of moving focus — never a bare `requestFocus()` on its own. This is the general form FR-R223-2 fixes for the nav-bar case specifically.

## Out of scope
- **A design pass on the season picker's focused+selected treatment** — FR-R223-1 specifies a concrete, minimal, already-precedented fix (reuse `onAccent`, the same token the pill's own text already uses for this exact contrast problem) rather than routing through a new `Directions.html` exploration. If a broader visual refresh of the picker is wanted later, that's a separate, design-led phase.
- **The `onFocusChanged`-driven `scrollBy` on the Play row** (`SeriesDetailScreen.kt:501-511`) — left as-is; it is not itself wrong, just insufficient on its own, and FR-R223-2 does not depend on it.
- **`MovieDetailScreen`'s cast/related rows and `SeriesDetailScreen`'s cast/related/multi-season episode rail** reaching the nav bar via **native spatial focus search** rather than an explicit `onUp` handler (e.g. Up from a `CastCircle` or a `Tile` with no bridging `onKeyEvent`) — not investigated in this pass. If native search can also land focus on the nav bar without scrolling, it would share the same failure mode; flagged here as a candidate follow-up, not confirmed.
- **Any equivalent issue on `ravilo-web` or `ravilo-tizen`** — this pass only tested the Android/TV target (Compose, shared by `:ravilo-android` and `:ravilo-phone`); the other targets use different UI toolkits entirely and weren't touched.

## Source references
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/SeasonPicker.kt` — `borderWidth`/`scale`/`glowElevation` (lines 74-78), the border modifier (line 111), the selected-state text/badge color precedent (`badgeText`, line 124).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/theme/Colors.kt` — `accent`/`onAccent`/`focusRing` per skin.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/SeriesDetailScreen.kt` — `upToHero` (339-345), genre row `onUp` (413), synopsis `onUp` (441), Play-row `onKeyEvent` Up branch (515-538, `navBarFR` at 522), Play-row `onFocusChanged` (501-511), `AppBar`'s `onDown` (790).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/MovieDetailScreen.kt` — identical structure: genre row `onUp` (256), synopsis `onUp` (272), Play-row `onKeyEvent` Up branch (308-317, `navBarFR` at 313), Play-row `onFocusChanged` (296-306), `AppBar`'s `onDown` (443).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/focus/FocusModifiers.kt` — `requestFocusRetrying`.

## Build note (2026-09-03)
Both FRs built exactly as specified, no deviations.

**FR-R223-1**: `SeasonPicker.kt`'s `borderWidth` now animates on `focused` alone; a new `borderColor` val
(`colors.onAccent` when `isSelected`, else the unchanged `colors.focusRing`) feeds the existing
`.border(...)` modifier. `scale`/`glowElevation` untouched.

**FR-R223-2**: added a `goToNavBar()` local function to both `SeriesDetailScreen.kt` (placed right after
`upToHero`, same shape) and `MovieDetailScreen.kt` (which has no `upToHero` to sit next to, so placed
right after the initial `playFR.requestFocus()` `LaunchedEffect`) — `scope.launch { runCatching {
listState.scrollToItem(0) } }` then `runCatching { navBarFR.requestFocus() }`. All three bare
`navBarFR.requestFocus()` call sites in each file (genre row `onUp`, synopsis `onUp`'s no-genres branch,
Play-row `onKeyEvent` Up's else-branch) now call `goToNavBar` (or `goToNavBar()` where the branch needed
to stay a statement rather than a function reference) instead.

Verified via `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`,
`:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin`
— all clean, no new warnings. `:ravilo-android:assembleRelease` (full minified release, exercises the same
build R8/baseline-profile path R213 verified) also succeeds.

**Deployed to both TVs same session** (user-requested): release APK (`ravilo-1.0-release.apk`) installed
via `adb install -r` and AOT-compiled (`cmd package compile -m speed -f dev.jellystructure.ravilo`) on
`BRAVIA 4K VH21` (stue TV, `192.0.2.22`) and `BRAVIA 4K VH2` (`192.0.2.23`, the household's second TV,
identified via the `ravilo_device` table's `display_name` + confirmed against each device's own
`ro.product.model`) — both installs and both compiles reported `Success`. Neither device was launched
into Ravilo afterward (confirmed via `dumpsys window` immediately after: stue TV sat at the Android TV
home launcher, the second TV was asleep with no foreground window) — the deploy is live on both, but not
yet re-verified with a fresh D-pad pass against the built APK in this session.
