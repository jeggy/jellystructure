# Phase R261 — Fullscreen only while something plays

> The phone app is meant to be an ordinary app — status bar, gesture bar, page colour — everywhere
> except the media player, which goes immersive for as long as it is on screen (R244). Two things are
> wrong with that today, both measured on the Pixel 9 (2026-09-18, Play build 1.26). **The bars never
> come back:** after the first playback the whole app stays immersive until the process dies —
> Discover, Home, the detail page, all without a status bar. **And the player is not actually
> fullscreen:** in landscape the picture starts 153 px in from the cutout edge, behind a strip painted
> in the page colour. Owner direction: fullscreen only when the app is actually playing media, and
> casting to a TV does not count as playing.

## Status

`✓ Built` — written 2026-09-18 from an owner report ("The top notification bar and bottom native
android buttons are gone (just like when in full-screen mode). I only want this fullscreen when the
app is actually playing some media") and a same-day trace + device measurement. **Dev-reviewed 2026-09-18 against `main`
`05195d1f`** (see §Dev review at the bottom: `minSdk 21` — the window calls are inert only on Android
15+ and stay, plus a cutout mode below it; FR-R261-5 is met by visibility-independent insets, not by
ordering; the padding moves inside `AnimatedContent`). **Built 2026-09-18** per the dev review's
corrected FR-R261-1/3/5 (see §Dev review); compiles clean on commonMain/Android/wasmJs, including
`ravilo-android`. **Not yet device-tested** — acceptance 1–7 need a real Pixel 9 (incl. an Android ≤14
device/emulator for the dev review's added cutout-mode step) and the Stue TV, neither run this
session. Client-only (`ravilo-ui` androidMain seam + one root-layout rule in commonMain). No backend,
DTO, string or design change. The TV is unaffected by design (see FR-R261-1).

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — Ravilo taken
through **R259**, admin through **234**. Ravilo-only, no admin pair. Siblings: **R260** (the player's
Back on the phone) and **R262** (Discover as one frame).

## Current state (traced against `main` and measured on the Pixel 9, 2026-09-18)

### 1. The bars are hidden by the player and never shown again

`seams/PlayerImmersiveEffect.kt` (Android actual) is the only code in the app that hides the system
bars. On entry it hides them; on dispose it restores them **only if** it saw them visible on entry:

```kotlin
val barsWereVisible = ViewCompat.getRootWindowInsets(decorView)
    ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
…
onDispose { if (barsWereVisible) { …show(systemBars()) } }
```

The comment calls this "captured, not assumed" — written so the TV (whose Activity hides the bars for
its whole life) is not un-hidden when the player closes. **On a phone the capture is always `false`.**
`Type.systemBars()` is `statusBars | navigationBars | captionBar`, and `WindowInsets.isVisible(mask)`
returns true only when *every* type in the mask is visible. A phone has no caption bar. In
`InsetsState.calculateInsets` (AOSP `frameworks/base`, read 2026-09-18) the visibility map starts
all-`false` and is set only for inset sources that exist — a type with no source stays `false`. So
`isVisible(systemBars())` is `false` on every phone, `barsWereVisible` is `false`, and the dispose
branch never runs.

Reproduced end to end on the Pixel 9: cold start → `dumpsys window` shows no non-default visibility
request (bars visible) → play *Numpties* → `Requested non-default-visibility types: statusBars
navigationBars captionBar` → leave with the in-app back arrow → the detail page, **same request still
in force**. The owner's screenshot of Discover with no status bar is this state, hours later. Every
screen after the first playback is immersive: the profile menu, Settings, the cast remote, Discover.
A side effect visible in the same test: the detail page's content shifted 69 px down between the two
visits, because the inset padding changed underneath it.

### 2. The player pads itself away from the display cutout

`RaviloApp.kt`'s root `Box` applies `.windowInsetsPadding(WindowInsets.safeDrawing)` to **every**
destination, the player included. `safeDrawing` includes the display cutout, and the cutout does not
go away when the bars hide. On the Pixel 9 Pro the cutout inset is 153 px (`mDisplayCutout`
`insets=Rect(153, …)`). Measured in the landscape screenshot: the first non-page-colour column is
**x = 153 of 2142**; the strip 0–152 is the page colour (`#0A0C13`, R259's window background), not
black; the picture is scaled into the remaining 1989 px and is therefore both narrower and off-centre
by 76 px. In portrait the same 153 px band sits above the video. The window itself is already
`layoutInDisplayCutoutMode=always` (edge-to-edge is enforced at `targetSdk 36`), so the platform is
offering the whole panel — the app's own padding declines it.

FR-R244-13 asked for the *chrome* to respect safe areas, and the build did that (every handset layer
is `windowInsetsPadding(safeDrawing)`). Nothing asked for the *picture* to respect them; it does so
only because the root pads everything.

### 3. Two things the phone player does not do that "playing" implies

- **Keep the screen on.** `FLAG_KEEP_SCREEN_ON` is set only in the TV Activity. Nothing on the phone
  holds the screen — no `keepScreenOn` on the surface, no `setWakeMode` on the ExoPlayer. The Pixel's
  30-minute timeout hides this; a phone with a 30-second timeout sleeps mid-film.
- **`setDecorFitsSystemWindows` is inert.** Both the entry (`false`) and the restore (`true`) are
  no-ops under edge-to-edge enforcement (`pfl=EDGE_TO_EDGE_ENFORCED` in `dumpsys`), which is why the
  Compose inset padding is the only thing that actually positions content — and why fixing the bars
  alone would not fix the strip.

### What "playing" is, and what it is not

The owner's line: casting does not count. `Dest.CastRemote` is a remote control for a picture on
another screen; `Dest.Player` and `Dest.LiveTv` are the app rendering video. Only the latter two are
"playing" for this phase. Every other destination — including the remote, the profile picker, Login,
Settings — is an ordinary screen.

## Requirements

**FR-R261-1 · The baseline is declared, not observed.** The immersive effect restores the **platform's
baseline** on dispose, and the baseline is a fact of the platform, not a reading taken at entry: a TV
(`isTvPlatform`) is immersive for the Activity's whole life and the effect changes nothing on dispose;
a phone's baseline is bars visible, gesture bar visible, page-colour ground, and the effect shows the
bars on dispose unconditionally. `isVisible(systemBars())` is not consulted anywhere for this
decision — the caption-bar fact above is recorded in the seam's comment so it is not re-introduced.

**FR-R261-2 · Immersive exactly while playing.** The system bars are hidden if and only if
`Dest.Player` or `Dest.LiveTv` is the visible destination. Entering either hides them; leaving by any
route — pop, Back (R260), cast hand-off to `Dest.CastRemote`, auto-advance ending a series, an
error sheet's *Back*, process death and relaunch — shows them again before the next screen is
interactive. `Dest.CastRemote` is not playing: casting from inside the player (R245's hand-off) ends
immersive mode the moment the remote replaces the player.

**FR-R261-3 · The picture owns the whole panel.** The root's safe-area padding does not apply to the
two playing destinations. Their video surface spans the full window including the display cutout, the
ground behind the picture is **black** everywhere (never the page colour — the R259 window colour is
right for every other screen and wrong here), and the picture is centred on the full display, so a
cutout falls inside the letterbox or, on a picture wide enough to reach it, inside the picture's own
edge. The chrome keeps FR-R244-13: every control, the seek bar, the sheets and the lock glyph stay
inset from the cutout and the gesture bar. Live TV's player follows the same rule.

**FR-R261-4 · Playing keeps the screen on.** While `Dest.Player` or `Dest.LiveTv` is on screen the
phone holds the screen on (`FLAG_KEEP_SCREEN_ON` scoped to the destination, or the equivalent on the
surface); it releases the moment the destination leaves, including on the cast hand-off. Paused is
still "on screen" — a viewer who paused to answer a message should not return to a dark phone. The TV
Activity's global flag is unchanged.

**FR-R261-5 · No layout shift on the way out.** Because the bars are shown before the previous screen
becomes interactive, that screen composes once with its final insets: the 69 px jump measured on the
detail page must not occur. A screen that was on the stack under the player renders in the same place
it did before playback.

**FR-R261-6 · The TV is unchanged.** `MainActivity` (TV) still hides the bars in `onCreate`; the effect's
entry on TV re-requests the same state and its dispose does nothing; no TV pixel moves. R256's rule
(a TV is never a handset) is the gate — `isTvPlatform`, never dp.

## Acceptance

Pixel 9 (Android 17, `targetSdk 36`, gesture navigation), Play build:

1. Cold start → status bar and gesture bar visible on the profile picker, Home, a detail page.
2. Play → `dumpsys window` reports `statusBars navigationBars` requested hidden; rotate to landscape:
   the first non-black column of the video surface is **x = 0**, no page-colour strip, the picture
   centred within 0–2142 (the letterbox, if any, symmetric to ±2 px).
3. In-app back arrow → detail page with bars visible; `dumpsys` shows no non-default visibility
   request; the page's first text baseline is at the same y as in step 1.
4. Play → cast to the Stue TV from inside the player → the remote appears with bars visible.
5. Play, set the phone's screen timeout to 15 s, do not touch it for 60 s → still lit; leave the
   player → the phone sleeps on its normal timeout.
6. Live TV player: 2 and 3 hold.
7. Stue TV: no visible change on any screen, before, during or after playback.

## Non-goals

- Picture-in-picture, background audio, or any playback surface that is not the full window.
- Changing which swipe reveals the bars in immersive mode (R260 open question 1).
- iOS safe areas (the same rule will apply; no target exists).
- Web: the browser owns fullscreen; the wasm actual stays an empty body.

## Open questions

1. **Cutout inside the picture, or picture inside the cutout?** FR-R261-3 chooses "the panel is the
   canvas" (the platform's `always` mode, what YouTube and Netflix do). The alternative — inset the
   *video* by the cutout on its long edges only, keeping the picture whole at the cost of the strip —
   is one line the other way. Lean: the panel; a 2.39:1 film on a 20:9 phone never reaches the cutout
   anyway, and a 16:9 one loses a sliver behind the camera rather than 7 % of its width.
2. Should the *paused* state release the screen after some minutes (FR-R261-4)? Lean: no — the
   platform's own timeout is the user's setting and "on screen" is the honest condition.

## Dev notes

- FR-R261-1 is two lines in `PlayerImmersiveEffect.kt`: drop `barsWereVisible`, branch on
  `isTvPlatform`. Keep `setDecorFitsSystemWindows` calls out — they are inert at this target and their
  presence suggests they do something.
- FR-R261-3 is a root-layout decision in `RaviloApp.kt`: the `safeDrawing` padding modifier is applied
  per destination (not for `Dest.Player` / `Dest.LiveTv`), and the player's root draws
  `Modifier.fillMaxSize().background(Color.Black)` beneath the surface. The chrome already pads itself.
- FR-R261-4: a small `KeepScreenOnEffect` seam (expect/actual; wasm and TV actuals empty) composed by
  the two playing screens, adding/clearing `FLAG_KEEP_SCREEN_ON` in a `DisposableEffect`. Same footing
  as `PlayerImmersiveEffect`, which it could also simply live inside.
- Test first on the Pixel with `adb shell dumpsys window windows | grep "Requested non-default"` — an
  empty result is "bars visible". The screenshot method used here (first non-page-colour column) is a
  fine acceptance probe for FR-R261-3.

## Dev review (2026-09-18, against `main` `05195d1f`)

`PlayerImmersiveEffect.kt` is as quoted, `RaviloApp.kt:696` pads every destination with
`WindowInsets.safeDrawing`, `FLAG_KEEP_SCREEN_ON` exists only at `android/MainActivity.kt:29`, and the
phone Activity deliberately uses default window fitting. Both defects are real. Three corrections.

1. **"Inert" is true of the Pixel, not of the app.** `minSdk = 21`. Edge-to-edge is enforced only on a
   device running Android 15+; on Android 14 and below `targetSdk 36` changes nothing, and there
   `setDecorFitsSystemWindows(false)` is precisely what lets the player draw behind the hidden bars. The
   dev note "keep these calls out" would break the player on every older phone. **Kept**, entry and
   restore, with the restore now unconditional off-TV (FR-R261-1).
2. **FR-R261-3 needs one more window attribute below Android 15.** Nothing in the app or its theme sets
   a cutout mode, so on Android 9–14 the system itself letterboxes a landscape window out of the cutout —
   black rather than page-coloured, but the picture is still off-centre. The effect sets
   `layoutInDisplayCutoutMode = SHORT_EDGES` (API 28+) on entry and restores the previous value on
   dispose. **Acceptance gains** an Android ≤ 14 phone or emulator for step 2.
3. **FR-R261-5 cannot be met by ordering alone.** `controller.show()` is asynchronous: the insets arrive
   a frame or more after the detail page has composed, so "shown before the screen is interactive" still
   composes once with zero insets and then shifts — the measured 69 px. The robust rule: every
   non-playing destination pads by insets that **ignore visibility**
   (`WindowInsets.systemBarsIgnoringVisibility` ∪ `displayCutout` ∪ `ime`), so its layout does not depend
   on whether the bars have come back yet. The jump disappears on the way in as well as on the way out.
4. **Where the padding lives.** It cannot stay on the root `Box`: during the 220 ms slide both the
   player and the detail page are children of the same `AnimatedContent`. The padding moves **inside the
   `AnimatedContent` content lambda**, chosen per `dest` (none for `Dest.Player` / `Dest.LiveTv`), so the
   outgoing screen keeps its own insets while it slides. The root's other children — the profile menu
   overlay and the F5 FPS overlay — take the same padding explicitly.
5. **One seam with R263.** R263 FR-R263-6 needs safe-area values on wasm, where Compose's
   `WindowInsets.safeDrawing` has no source. Both phases want the same thing: a single
   `rememberSafeAreaPadding()` expect/actual (Android: item 3's union; wasm: `env(safe-area-inset-*)`)
   replacing the 12 `WindowInsets.safeDrawing` call sites in `RaviloApp.kt`, `PlayerScreen.kt`,
   `PlayerHandsetChrome.kt`, `CastRemoteScreen.kt` and `components/Cast.kt`. Whichever phase is built
   first introduces it.
6. **Keep-screen-on needs no new seam:** `LocalView.current.keepScreenOn = true` in the effect's Android
   actual, cleared on dispose. Note `LiveTvPlayerScreen.kt:108` composes the effect only when `handset`;
   `PlayerScreen.kt:1235` always — the TV branch must therefore leave `keepScreenOn` and the bars alone
   (`isTvPlatform`), which FR-R261-6 already requires.
7. **Open question 1:** the panel is the canvas. **Open question 2:** no timeout.

Build with or after R260; they share an acceptance session on the Pixel.
