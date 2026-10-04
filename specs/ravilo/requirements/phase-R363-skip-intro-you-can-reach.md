# Phase R363 — Skip Intro you can reach with the remote

> Owner, 2026-10-04: *"Let's test skipping intro with the popup provided when watching a series. I usually feel it
> close to impossible to click this button. Especially when I open the media controls and try to focus on this
> button."*

## Status

`✓ Built` 2026-10-04 (build notes at the end). Was `Planned` — written 2026-10-04 (dev-authored) from a test on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`), a series episode whose intro runs 0:05–0:34, Skip Intro mode **Prompt**, countdown 6 s.
Dev-reviewed 2026-10-04 against `main` `5210045a` (see the end; still `Planned`). Client only (`PlayerScreen.kt`). Owner decisions 2026-10-04: the pill's **visibility stays as
today** (its countdown, and whenever the controls are up); the countdown ring is drawn **only in Auto mode**.

**Amends R182 FR-RV-SKIP1-1** (focus, reachability, the ring) and records that its *"stays visible until the intro
ends"* after the controls are opened is not what the owner wants: the pill shows while the controls are up and hides
with them. **Amends R350 FR-R350-7** for the pill only (FR-R363-4).

## What was seen

1. **Pill, then nothing.** The pill appears at 0:05 with a ring and focus; ~6 s later it disappears with the
   controls. The intro has ~20 s left.
2. **Opening the controls to skip pauses instead.** Up during the intro shows the controls and the pill, but the
   ring of focus is on **Play** (`LaunchedEffect(skipIntroPillVisible)` gives the pill focus only when its visibility
   flips, and the auto-hide had already moved focus to Play). The viewer sees the pill and presses OK: **the video
   pauses.**
3. **No arrow reaches the pill.** With the controls up and focus anywhere in the transport row:
   - Up from **Audio & Subs** or **Next** — the controls directly under the pill — goes to the **seek bar**;
   - Up from the seek bar does nothing;
   - Left along the row reaches −10 s, then the seek bar, and Left on the seek bar **scrubs** the video.
   The code puts `SKIP_INTRO` first in `transportOrder` (before `SEEK_BAR`), and the seek bar consumes Left, so the
   pill is in the order but unreachable. Down from the pill goes to Play, so once the viewer leaves it there is no
   way back.
4. **It steals focus mid-action.** Paused at 0:41 with focus on −10 s, OK twice: the first press re-entered the intro,
   the pill re-armed and **took focus**; a second OK (meant as another −10 s) would have skipped forward past the
   intro — the opposite of what was asked.
5. **The ring promises something that never happens.** In Prompt mode the ring counts down and then the pill just
   hides; nothing is skipped.

## Requirements

### FR-R363-1 — Reachable from the controls under it
While the pill is visible and the controls are up:
- **Up** from the **seek bar**, from **Audio & Subs** or from **Next** focuses the pill (it sits above them);
- **Down** from the pill goes back to the control it was reached from (Play if it took focus itself);
- **Left/Right** never reach it through the seek bar: `SKIP_INTRO` leaves `transportOrder`.
Up from −10 s, Play and +30 s stays the seek bar.

### FR-R363-2 — Focus when it appears, and only then
The pill takes focus when it **appears on its own** (the countdown at the intro's start, controls hidden). When it
becomes visible because the viewer is already using the controls (woke them, rewound or skipped into the intro),
focus **stays** where it is.

### FR-R363-3 — Opening the controls during the intro offers the skip first
Inside the intro, a key that wakes hidden controls (R350 FR-7: Up/Left/Right act on their first press; OK reveals)
puts focus on the **pill** if the pill had focus when the controls last hid, or if the viewer has not used any other
control since the intro began. So *Up, OK* skips the intro, from hidden controls.

### FR-R363-4 — OK on a hidden screen during the intro does not pause
While playback is inside the intro and the pill is armed (mode Prompt or Auto, not dismissed by leaving the window),
OK with the controls hidden **reveals the controls with the pill focused** instead of pausing; a second OK skips.
Outside the intro, OK on a hidden screen is unchanged (R178/R350: play/pause). Play/Pause on the remote always
toggles.

### FR-R363-5 — The ring only where it means something
**Auto** mode: the ring counts down to the automatic skip (unchanged). **Prompt** mode: no ring — the pill reads
*Skip Intro* with its *OK* key cap only; it still tucks away after the countdown time (visibility unchanged).

### FR-R363-6 — The credits card is checked for the same faults
The credits / next-up card (R182 FR-RV-SKIP1-2) is tested against FR-R363-1…4's scenarios; any of the same faults
found there is fixed the same way, in this phase.

## Acceptance (TV, D-pad only, an episode with an intro)

1. Let the pill time out, press **Up**: controls up, pill focused, OK skips to the intro's end.
2. Let it time out, press **OK**: controls up, pill focused, video still playing; OK again skips.
3. Controls up, focus on **Next** → Up: pill focused. From **Audio & Subs** → Up: pill. From the seek bar → Up: pill.
4. Pill focused → Down → Up: pill again.
5. Paused after the intro, focus on −10 s, OK until inside the intro: focus stays on −10 s.
6. Prompt mode draws no ring; Auto mode draws it and skips at zero.
7. Outside the intro, OK on a hidden screen still pauses.
8. The decisions behind 1–7 are unit-tested and the walks are done on the TV (no Robolectric `PlayerScreen`
   harness); the release player passes `check-player-dex.sh`. See *Tests*.
9. Skip or scrub into the credits: the card starts on *Watch credits*; reached by playing, it starts on *Play next*.

## Tests

`PlayerScreen` sits at ART's 256-register limit in the release build (review item 9), so every decision is a top-level
pure function in its own file, new state lives in `PlayerBookkeeping`, and the tests target the functions. No
Robolectric `PlayerScreen` test (it would need a fake `RaviloPlayer`; review item 12).

**Decisions (`commonTest`, `…/screens/`).** `PlayerDpadRevealTest` gains the hidden-OK case; the rest go in a new
`SkipIntroFocusTest`. Names follow review item 9; rename with the code.
- FR-1, acceptance 3–4: `skipIntroUpTarget(focus, pillVisible)`: `SEEK_BAR`, `TRACKS`, `NEXT_EP` with the pill
  visible → `SKIP_INTRO`; `SKIP_BACK`, `PLAY`, `SKIP_FWD` → `SEEK_BAR`; pill hidden → today's target. Down from the pill
  → the remembered return target, `PLAY` when the pill took focus itself. Left/Right on `SKIP_INTRO` → no move
  (review item 3), and `transportOrder` never contains `SKIP_INTRO`.
- FR-2, acceptance 5: `skipIntroGrabsFocus(armingEdge, chromeVisible)` is `true` only for `(true, false)`; a rewind
  into the intro (always with `wake()`, so chrome visible) → `false`.
- FR-3, acceptance 1: `skipIntroWakeFocus(key, introArmed, pillHadFocusWhenHidden, touchedOther)`: Up or OK, armed,
  and (the flag set or nothing else touched) → `SKIP_INTRO`; Up with another control touched and no flag → the
  remembered control; Left/Right → the remembered control (they scrub); Down → reveal only.
- FR-4, acceptance 2 and 7 (owner decision 1): `hiddenSelect(introArmed, …)`: armed → `REVEAL_TO_PILL`; outside the
  intro, mode `OFF`, picker, next-up card or episode rail open → as today (`PLAY_PAUSE` / `NONE`). The existing "OK
  with the chrome hidden never fires the remembered control" still holds with `introArmed = false`.
- FR-5, acceptance 6: `skipIntroShowsRing(mode)`: `AUTO` → `true`, `PROMPT` → `false`.
- FR-6, acceptance 9 (owner decision 2): `nextUpStartsOn(broughtByViewer)`: a skip or scrub commit into the credits
  → *Watch credits*; normal playback → *Play next*.

**Robolectric, outside the player body.** `SkipIntroPillTest` (new, `…/components/`, `w960dp-h540dp`) renders only
`SkipIntroPill` (`private` today; make it `internal`, which costs `PlayerScreen` no registers): `showRing = false` → no node tagged `SKIP_INTRO_RING_TAG` (a new tag on `CountdownRing`) and the
*OK* key cap present; `showRing = true` → the ring node exists.

**Checklist.** `./gradlew :ravilo-android:assembleRelease`, then `scripts/check-player-dex.sh`: `PlayerScreenKt`'s
widest method stays ≤ 250 registers. No new `var … by remember` or `LaunchedEffect` in `PlayerScreen`'s body (the
pill's focus-grab `LaunchedEffect` is removed, not moved).

**TV only (manual, D-pad on the TV, an episode with a known intro, the release build).** Prompt mode, then Auto:
acceptance 1–7 in order, each from a fresh start of the episode. Play/Pause on the remote during the intro toggles.
+30 s into the credits → the card on *Watch credits*, OK does not start the next episode; let another episode play
into its credits → *Play next*.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `PlayerScreen.kt`: `PlFocus`, `dpadRevealsOnly`, `transportOrder`, the poll loop's intro latch, the
pill's visibility and focus effect, the countdown effect, the root key handler, `SkipIntroPill` and the credits card.
Also `PlayerDpadRevealTest`. The design holds. One correction to "What was seen" 2, two implementation traps, and the
release-build register limit to respect. Twelve items; two for the owner.

1. **Confirmed.**
   - The pill is visible while `insideIntroWindow && mode != OFF && (skipIntroCountingDown || chromeVisible)`
     (`:1290-1291`). After the countdown it rides the chrome's 3.6 s auto-hide (`CHROME_HIDE_MS`, `:145`).
   - Up from anything but the seek bar goes to the seek bar, and Up on the seek bar does nothing (`:1587-1588`).
   - `SKIP_INTRO` is first in `transportOrder` (`:248-254`). The seek bar consumes Left/Right as a scrub before the
     order is consulted (`:1547-1550`, `:1567-1570`). So the pill is in the order but unreachable.
   - Down from the pill goes to Play (`:1608`).
   - Seen 4: −10 s back into the window re-arms the countdown, because leaving the window reset both latches
     (`:1197-1205`). Then the visibility effect takes focus (`:1295-1298`).
   - Seen 5: `SkipIntroPill` always draws `CountdownRing` (`:3526`), whatever the mode.

2. **Correction to seen 2's mechanism.** Hiding the chrome no longer moves focus (R350 FR-7, `hideChrome()` at
   `:611`). What moves focus to Play is the pill's own effect when it *disappears* (`:1297`). On Up from hidden
   controls, the code at HEAD does the opposite of what was seen:
   - Up acts on the first press, so focus goes to the seek bar;
   - `wake()` makes the pill visible again;
   - the visibility effect then moves focus to the pill.
   **OK** from hidden controls does what was seen: `focus = PLAY` plus `togglePlay()` (`:1628-1633`). That is
   FR-R363-4's bug. The two presses were probably mixed up in the notes. It does not change the fix: FR-R363-2/3
   replace the visibility effect, so either path ends where the spec says.

3. **Trap 1: Left/Right on the pill.** With `SKIP_INTRO` out of `transportOrder`, `order.indexOf(SKIP_INTRO)` is
   `-1`. Right's `idx < order.lastIndex` then sends focus to `order[0]`, the seek bar (`:1572-1574`). FR-R363-1 must
   say, and the code must do: **Left and Right on the pill do nothing.** Handle `focus == SKIP_INTRO` before the order
   lookup.

4. **Trap 2: never leave `focus = SKIP_INTRO` while the pill is hidden.** `dpadRevealsOnly` treats
   `focus == SKIP_INTRO` as "not hidden" (`:215`). So an OK would run `skipIntro()` on a pill nobody can see. FR-R363-3's
   "the pill had focus when the controls last hid" has to be its own flag, in `PlayerBookkeeping` (item 9). Focus
   itself still leaves the pill when the pill goes, as today.

5. **FR-R363-1.** In `onUp`: if the pill is visible and `focus` is `SEEK_BAR`, `TRACKS` or `NEXT_EP`, remember
   `focus` as the pill's return target and move to `SKIP_INTRO`. In `onDown` from the pill, go to that return target
   (Play when the pill took focus itself). The pill sits bottom-right (`:1997`), above Audio & Subs and Next, so this
   matches what the viewer sees.

6. **FR-R363-2.** Delete `LaunchedEffect(skipIntroPillVisible)`'s grab (`:1295-1296`). Keep its release half: when
   the pill goes while focused, focus goes to the return target, else Play. Grab focus only on the arming edge in the
   poll loop (`:1197-1200`, where `skipIntroCountingDown` becomes `true`), and only when `chromeVisible` is false at
   that moment. A rewind into the intro always comes with `wake()` (`skip()`, `commitScrub()`), so it never grabs.
   That is seen 4's fix.

7. **FR-R363-3: Up and OK only.** The spec's parenthesis could be read as "Left/Right too". Keep Left and Right on
   R350's act-on-first-press: from a remembered seek bar they scrub. Sending them to the pill would turn a scrub from
   hidden controls into a jump to the pill. So:
   - Up or OK that wakes hidden controls inside the armed window puts focus on the pill, when the flag from item 4 is
     set or the viewer has touched no other control since the window began;
   - Down keeps its reveal-only rule.

8. **FR-R363-4: a third outcome.** Today `dpadRevealsOnly` returns a `Boolean` and the caller turns `true` into
   play/pause. Give the hidden-OK case its own pure decision, for example
   `hiddenSelect(introArmed, …): HiddenSelect { PLAY_PAUSE, REVEAL_TO_PILL, NONE }`. `introArmed` is
   `insideIntroWindow && mode != OFF && !pickerOpen && !nextUpVisible && !epRailOpen`. A paused player never hides
   its chrome on a TV (R350, `:1223-1225`), so the case does not arise while paused. Media Play/Pause stays
   `togglePlay()` (`onMediaKey`, unchanged). This amends R178 FR-RV-SEL1-2 and R350 FR-7 for the intro window. Name
   both in *Amends*.

9. **The release build's register limit.** `PlayerScreen`'s body sits at ART's 256-register limit in the R8 build
   (R258; `PlayerScreen.kt:434-437` says *add new state to `PlayerBookkeeping`, never another `var … by remember`*).
   An earlier one-line `LaunchedEffect` in this body crashed the release player on open. So:
   - new state (the pill's return target, the "had focus when hidden" flag, "touched another control") goes into
     `PlayerBookkeeping`;
   - the decisions go into top-level pure functions in their own file, for example
     `skipIntroUpTarget(focus, pillVisible)`, `skipIntroWakeFocus(…)`, `skipIntroGrabsFocus(armingEdge, chromeVisible)`
     and `hiddenSelect(…)`;
   - run `scripts/check-player-dex.sh` on a release APK before shipping.

10. **FR-R363-5.** Pass `showRing = mode == AUTO` to `SkipIntroPill` and skip the `CountdownRing` when it is false.
    Nothing else changes, and visibility still uses the countdown.

11. **FR-R363-6, the credits card, checked.** The card is modal. While `nextUpVisible`, every key branch goes to it,
    and `dpadRevealsOnly` counts it as not hidden. So it cannot be unreachable, and OK cannot pause behind it.
    **Seen 4's twin does exist.** +30 s (or a scrub commit) into the credits window shows the card at once with
    **Play next** focused (`:1155-1158`). The next OK, meant as another +30 s, starts the next episode. See owner
    question B.

12. **Tests.**
    - `commonTest` (`PlayerDpadRevealTest` already covers `dpadRevealsOnly`) gains every decision function in item
      9. That covers acceptance 1–5 and 7 as decisions, and 6's ring flag.
    - There is no Robolectric `PlayerScreen` test, and one needs a fake `RaviloPlayer`. Acceptance 8's key walks are
      not worth that harness for this phase.
    - Device: the walks in acceptance 1–7 on an episode with a known intro, in Prompt and in Auto.

**For the owner.**
- **A.** *During an intro, with the controls hidden, OK shows the controls with Skip Intro selected instead of
  pausing (a second OK skips). To pause during an intro you'd press Play/Pause, or OK twice with Play selected. OK?*
  Lean: yes. It is the press people use to skip.
- **B.** *When you skip or scrub into the end credits yourself, the credits card appears with "Play next" selected,
  so your next OK (meant as another skip) starts the next episode. Should the card then start on "Watch credits"
  instead?* Lean: yes, when you brought it up yourself. It still starts on "Play next" when it appears on its own.


## Owner decisions (2026-10-04, after the dev review)

1. **During an intro with the controls hidden, OK shows the controls with Skip Intro selected**; a second OK skips. To
   pause during an intro: Play/Pause, or OK with Play selected.
2. **The credits card starts on *Watch credits* when the viewer brought it up by skipping or scrubbing** into the
   credits; reached by normal playback it starts on *Play next* as today.

## Build notes (2026-10-04)

Built with both owner decisions. Client only. Every decision is a top-level function in the new
`screens/SkipIntroFocus.kt`; `PlayerScreen` only calls them, and its new state lives in `PlayerBookkeeping`
(`skipIntroInside`, `skipIntroArmingEdge`, `skipIntroReturn`, `skipIntroHadFocus`, `skipIntroTouchedOther`,
`viewerSeek`). No new `var … by remember` and no new `LaunchedEffect` in the body: the pill's old focus effect was
re-bodied (its grab removed, its release kept).
- **FR-1:** `skipIntroUpTarget(focus, pillVisible)` — Up from the seek bar, Audio & Subs or Next reaches the visible
  pill (remembering where from); from −10 s / Play / +30 s it is the seek bar as before. Down from the pill goes to
  `skipIntroDownTarget(reachedFrom)` (Play when the pill took focus itself). `SKIP_INTRO` left `transportOrder`
  (now `transportOrder(hasNextEp)`), and Left/Right on the pill do nothing (review item 3).
- **FR-2:** the pill takes focus only at the countdown's arming edge with the controls hidden
  (`skipIntroGrabsFocus`); the poll loop marks the edge, the pill's focus effect consumes it. A rewind or scrub into
  the intro wakes the controls and never grabs (seen 4). Focus never stays on a hidden pill (review item 4): when
  the pill goes while focused, focus returns to where it came from and `skipIntroHadFocus` remembers it.
- **FR-3:** `skipIntroWakeFocus` — Up waking hidden controls inside the armed window (`skipIntroArmed`: inside the
  intro, Prompt/Auto, no picker/next-up/rail) goes to the pill when it had focus when the controls hid or nothing
  else was touched since the intro began. Left/Right keep R350's first-press rule, Down only reveals (review item 7).
- **FR-4 (owner decision 1):** `hiddenSelect(wasHidden, introArmed)` → `REVEAL_TO_PILL` inside the armed intro (a
  second OK skips), `PLAY_PAUSE` outside it, `NONE` when the controls are up. Media Play/Pause always toggles.
- **FR-5:** `SkipIntroPill` is `internal` with `showRing`; the player passes `skipIntroShowsRing(mode)` (Auto only).
  The ring carries `SKIP_INTRO_RING_TAG`.
- **FR-6 (owner decision 2):** `skip()` and `commitScrub()` set `viewerSeek`; the credits card's early trigger starts
  on `nextUpStartsOn(viewerSeek)` (*Watch credits* when brought up by a skip/scrub, *Play next* from playback); the
  flag lives for one poll tick. The natural-end trigger keeps *Play next*. The card itself is modal, so FR-1/3/4's
  faults cannot occur there (review item 11).
- Tests (green): `SkipIntroFocusTest` (commonTest, 9: each decision above incl. `transportOrder` never holding the
  pill), `PlayerDpadRevealTest` gains the hidden-OK cases (reveal to the pill when armed; play/pause outside; the
  remembered control never fires from hidden), `SkipIntroPillTest` (Robolectric: no ring + the *OK* key cap in
  Prompt, the ring in Auto).
- **Register check:** `:ravilo-android:assembleRelease` then `scripts/check-player-dex.sh`: `PlayerScreenKt`'s widest
  method uses **234** registers (limit 250) — unchanged from the build just before.
- Device only (release build, an episode with a known intro, Prompt then Auto): acceptance 1–7 in order;
  Play/Pause during the intro toggles; +30 s into the credits → the card on *Watch credits*, OK does not start the
  next episode; playing into the credits → *Play next*.
