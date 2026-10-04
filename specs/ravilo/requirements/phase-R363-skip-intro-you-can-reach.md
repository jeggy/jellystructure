# Phase R363 — Skip Intro you can reach with the remote

> Owner, 2026-10-04: *"Let's test skipping intro with the popup provided when watching a series. I usually feel it
> close to impossible to click this button. Especially when I open the media controls and try to focus on this
> button."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a test on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`), a series episode whose intro runs 0:05–0:34, Skip Intro mode **Prompt**, countdown 6 s.
Not dev-reviewed. Client only (`PlayerScreen.kt`). Owner decisions 2026-10-04: the pill's **visibility stays as
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
8. Robolectric key walks for 1–7 (`PlayerScreen` focus model; `dpadRevealsOnly` gains the intro case).
