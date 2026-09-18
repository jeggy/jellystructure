# Phase R178 — Player: D-pad Select must never re-trigger a hidden control (FR-RV-SEL1)

> Reported live: pick a subtitle track from "Audio & Subs", then just watch for a while. The chrome
> auto-hides after its usual timeout, as designed — but D-pad focus silently stays parked on the
> now-invisible "Audio & Subs" button. Press **Select/OK** on the remote expecting play/pause (nothing
> is visibly focused, so that's the only sane reading of the button) and it instead **reopens the
> Audio & Subs picker**. Confusing and, per the report, "bad and annoying UX." This is general — it
> reproduces with *any* control (skip buttons, seek bar, etc.), subtitles is just how it was found.

**Status:** Planned.

## Problem

`PlayerScreen.kt` hand-rolls its own D-pad focus model instead of using real Compose focus nodes:

- The root `Box` (`:448-590`) is the **only** real `dpadFocusable` in the whole screen
  (`focusRequester = playerFR`, granted once via `LaunchedEffect(Unit) { playerFR.requestFocus() }`,
  `:449`). Every on-screen control (`PlayPauseButton`, `SkipButton`, `TrackButton`, the seek bar, …) is
  deliberately **not** `dpadFocusable`/`.focusable()` — comment at `:1072-1075` states this outright:
  Compose focus stays on the root; "which button looks focused" is tracked separately.
- That separate tracking is a plain `var focus by remember { mutableStateOf(PlFocus.PLAY) }` (`:172`),
  a hand-rolled enum reassigned by `onControlHover`/`onControlClick` (`:689`, `:704`) as the user
  D-pad-navigates between visible buttons (e.g. `:896-900`'s `TrackButton` sets `focus = PlFocus.TRACKS`
  on select).
- The root's `onKeyEvent` routes `Key.Enter`/`NumPadEnter`/`DirectionCenter` to `onSelect` (confirmed in
  `dpadFocusable`, `FocusModifiers.kt:93`), whose `when` branches **on that stale `focus` var**
  (`:523-543`) — `PlFocus.PLAY → togglePlay()` (`:532`) vs. `PlFocus.TRACKS → `reopen the picker
  (`:535-539`), etc.
- **Nothing ever resets `focus` back to a sane default.** The auto-hide timer (`:368-372`,
  `LaunchedEffect(chromeRevision) { delay(CHROME_HIDE_MS); if (!pickerOpen && !nextUpVisible &&
  !epRailOpen) chromeVisible = false }`) only flips `chromeVisible`; it never touches `focus`. Neither
  does the tap-to-toggle-chrome path (`:587-589`, `onTap = { if (chromeVisible) chromeVisible = false
  else wake() }`) or `wake()` itself (`:229`, `chromeVisible = true; chromeRevision++` — no focus
  touched either). So `focus` just keeps whatever value it last had, forever, regardless of whether the
  control it names is even visible.
- **Dedicated hardware Play/Pause already dodges this** (R44): media-transport keys are matched and
  dispatched to `onMediaKey` unconditionally, *before* the generic key `when` (`FocusModifiers.kt:74-87`
  — `MediaKey.PLAY_PAUSE → togglePlay()`, `:561`), independent of `focus`/`chromeVisible`. Only the
  generic D-pad Select/Enter path is affected, because it alone dispatches through the stale-`focus`
  `when`. This existing split is the right prior art: Select-with-hidden-chrome should behave like a
  media key, not like "whatever was last clicked."

## Requirements

### FR-RV-SEL1-1 — Reset `focus` to `PlFocus.PLAY` whenever chrome hides
Every site that transitions `chromeVisible` from `true` to `false` must also reset `focus =
PlFocus.PLAY` in the same place, atomically — there must not be a frame where chrome is hidden but
`focus` still names a hidden control. This covers, at minimum, both current transition sites:
- The auto-hide `LaunchedEffect` (`:368-372`).
- The tap-to-toggle-chrome branch of root `onTap` (`:588`, the `if (chromeVisible) chromeVisible =
  false` arm) — handset/web only today (`:587`), but the reset must apply there too since the same
  stale-focus hazard exists on those platforms.

Do not rely on remembering to add the reset at each call site piecemeal if a cleaner single choke
point is available (e.g. a small `hideChrome()` helper that sets both fields together, replacing the
two direct `chromeVisible = false` assignments) — implementer's choice, but the **invariant** (below)
must hold regardless of how many places can hide chrome, including any added later.

### FR-RV-SEL1-2 — Belt-and-suspenders: guard `onSelect` on `chromeVisible` directly
Independent of FR-RV-SEL1-1 (defense in depth, not a substitute for it — see Invariants), the root's
`onSelect` handler must check `chromeVisible` **before** branching on `focus`: if chrome is not
currently visible, Select must call `togglePlay()` (and reveal chrome per FR-RV-SEL1-3), full stop —
never evaluate the `focus`-based `when` at all. This way even a future bug that reintroduces a stale
`focus` value can't resurrect this class of issue.

### FR-RV-SEL1-3 — Select-while-hidden also reveals chrome
When Select fires the "chrome is hidden → toggle play/pause" path (FR-RV-SEL1-2), it must also call
`wake()` (or equivalent) so the chrome reappears and the user gets visual confirmation of the new
play/pause state — mirroring the feedback a tap or a dedicated Play/Pause remote button already gives
elsewhere in this screen. Don't leave the user pressing OK into a still-black screen wondering if
anything happened.

### FR-RV-SEL1-4 — General, not subtitle-specific
The fix must not be scoped to `PlFocus.TRACKS` alone. Any `PlFocus` value (skip-back, skip-forward,
trailer, next-episode, whatever exists now or is added later) must be equally covered by the same
reset/guard — the bug is "stale focus survives chrome hiding," not "the subtitle button in particular
is wrong."

## Invariants

- **FR-RV-SEL1-1 and FR-RV-SEL1-2 are both required, not either/or.** 1 fixes the state so the chrome
  that reappears next also *looks* right (highlighted button matches what Select would do); 2 makes the
  behavior correct even if 1 is ever incomplete (a new hide-chrome call site is added without the
  reset). Shipping only one of them is not a pass.
- **Dedicated hardware Play/Pause behavior (R44) is unchanged** — it already bypasses `focus` entirely
  via `onMediaKey` and must keep doing so.
- **No change to normal, chrome-visible D-pad navigation.** Left/right/up/down between visible buttons,
  and Select acting on whichever one is actually highlighted while chrome is up, must behave exactly as
  today. This phase only fixes what happens once chrome has hidden.
- **No change to the auto-hide timing itself** (`CHROME_HIDE_MS`, the `pickerOpen`/`nextUpVisible`/
  `epRailOpen` guards that delay auto-hide) — only what state is left behind when it fires.

## Out of scope

- Any change to `dpadFocusable`/`FocusModifiers.kt`'s media-key dispatch (already correct, see Problem).
- Redesigning the hand-rolled `PlFocus` model into real per-button Compose focus nodes — a legitimate
  bigger refactor, but not required to fix this specific bug, and out of scope here.
- The Live TV player (`LiveTvPlayerScreen.kt`, R177) — separate screen, not covered by this phase unless
  it's confirmed to share the same hand-rolled-focus pattern and bug (check when implementing; if so,
  file it as its own follow-up rather than silently expanding this phase's scope).

## Source references
- Code: `ravilo-ui/.../screens/PlayerScreen.kt` — `:166-167` (`chromeVisible`/`chromeRevision` state),
  `:172` (`focus: PlFocus` state), `:229` (`wake()`), `:368-372` (auto-hide `LaunchedEffect`),
  `:448-590` (root `Box`/`dpadFocusable`/`onSelect`/`onTap`), `:523-543` (`onSelect`'s `focus` `when`),
  `:561` (`onMediaKey`'s `PLAY_PAUSE → togglePlay()`), `:689`/`:704` (`onControlClick`/`onControlHover`),
  `:896-900` (`TrackButton`), `:1072-1075` (comment confirming the hand-rolled-focus design).
- Code: `ravilo-ui/.../focus/FocusModifiers.kt:70-97` (`dpadFocusable` — media-key vs. Enter/Select
  dispatch split, confirms `onMediaKey` already bypasses `focus`).
- Related: **R44** (physical remote/keyboard transport keys — the existing, correctly-immune
  `onMediaKey` path this phase's fix should behave consistently with).
