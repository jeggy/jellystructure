# Phase R251 — Player chrome that doesn't eat the keypress

> Stue TV, 2026-09-16, twice in one session: the intent was **→ → OK** to reach *Audio & Subs*; the
> result was **>> Next**, skipping S08E04 → E07, then E07 → E09, losing the viewer's place each time.
> Nothing was mis-pressed. The same three keys do different things depending on whether the chrome
> had auto-hidden a moment earlier, because hiding the chrome silently moves the focus.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F8 plus the rail-title item in
F14). Not dev-reviewed, not built. Client-only, TV chrome (R244 owns the phone player).

**Numbering:** verified against `STATUS.md` on 2026-09-16 — Ravilo taken through R245; R246–R250 by
sibling specs the same day.

## What the code does (traced against `main`, 2026-09-16)

`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`:

- `CHROME_HIDE_MS = 3_600` (`:121`); `hideChrome()` (`:460`) sets `chromeVisible = false` **and
  `focus = PlFocus.PLAY`** — R178's rule, so a later Select cannot re-fire whatever was focused when the
  chrome went away.
- `onSelect` (`:1241-1257`, R178 FR-RV-SEL1-2) captures `wasHidden` before `wake()` and, when the
  chrome was hidden, does only `togglePlay()` — a Select never acts on an invisible control.
- `onLeft` / `onRight` (`:1189-1204`) do **not** have that guard: `wake()` first, then move focus in the
  transport order — from `PLAY`, because `hideChrome()` put it there. So:
  - chrome up, focus on `TRACKS`: `→` = `NEXT_EP`, `→` = stays, `OK` = next episode;
  - chrome hidden 3.6 s later: `→` = reveal + `PLAY → SKIP_FWD`, `→` = `TRACKS`, `OK` = picker.
  The viewer cannot see which of the two they are in — the chrome hides on a timer they are not
  watching.
- The picker's close path calls `wake()` (`:659`), so after picking a subtitle the transport is up
  with focus on `TRACKS` and the 3.6 s clock running; a viewer who reads the subtitle line for four
  seconds before reaching for *Next* is in the second case.
- Episode-rail and transport titles use `maxLines = 1` with the default `TextOverflow.Clip`
  (`:1787`, `:2849`, `:2918`, `:3100`) — "7. The Gang Gets Ready for" is cut mid-word, no ellipsis.
- Subtitles: `PlayerVideoSurface.kt:139-146` gives the `SubtitleView` a fixed 28 dp bottom inset
  relative to the screen (R77's letterbox fix). It does not change when the chrome is up, so a cue
  renders inside the transport band, between the seek bar and the buttons.
- **Back ×3 from the picker is R112 by design** — "if the controls are showing, Back just hides
  them; only Back with nothing on screen leaves the player" (`:1287-1298`). Picker → close (chrome
  wakes) → hide → leave. Recorded so it is not re-reported; not changed here.

## Requirements

**FR-R251-1 — A key that finds the chrome hidden reveals it and does nothing else.** `onLeft`,
`onRight`, `onUp` and `onDown` get R178's `wasHidden` guard: when the chrome was hidden, the press
only wakes it (focus lands on `PLAY`, unchanged), and the *next* press acts. `onSelect` already
behaves this way; this makes the five keys consistent. Media keys (`onMediaKey`) are unaffected —
they act regardless of chrome by design (R44).

**FR-R251-2 — The same sequence always means the same thing.** Acceptance: from any prior focus, with
the chrome hidden, **→ → OK** opens *Audio & Subs* and **→ → → OK** advances the episode; with the
chrome visible and focus on `PLAY`, the same. A viewer who waits and one who doesn't reach the same
control.

**FR-R251-3 — Never a hard clip.** Every single-line title in the player — the transport title, the
episode-rail cards, the next-up card — uses `TextOverflow.Ellipsis`.

**FR-R251-4 — Subtitles rise above the chrome.** While `chromeVisible`, the subtitle inset is the
transport band's measured height plus the 28 dp floor, animated with the chrome's own show/hide; when
hidden, back to 28 dp. R77's screen-relative measurement is kept — the inset is added to it, not
replaced. The episode rail (R208) and the picker cover the video and need no lift.

**FR-R251-5 — The hide timer is not the fix.** `CHROME_HIDE_MS` stays 3.6 s; R112's Back semantics
stay. This phase changes what a key does *after* a hide, not when hides happen.

**FR-R251-6 — On-device.** The two skipped transitions re-run on the stue TV with the release build:
S08E04 → *Audio & Subs* after a 5 s pause; then `>> Next` reached deliberately.

## Non-goals

- The phone player's chrome (R244).
- Picker layout (R180/R195/R238).
- Changing which controls exist in the transport or their order (R178 §transportOrder).

## Verification

1. A commonTest over the D-pad handler's pure decision (extract `wasHidden`-gated dispatch into a
   testable function, as R178 did for Select) covering all five keys × hidden/visible.
2. FR-R251-6 on the device.
3. Screenshot with chrome up and a two-line cue: the cue's bottom edge is above the transport band.

## Open questions

- Whether `Up` while hidden should reveal *and* move to the seek bar (the only key whose "move" is
  arguably the intent). Recommendation: no — one rule for all five keys is the point.
