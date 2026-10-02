# Phase 302 — Subtitle problems are grouped by cause, each with its fix

> Written 2026-10-02 from an owner question about the Dashboard's two subtitle rows: *"they should be Critical, as
> they are fully incorrect … the numbers are huge. Let's understand the different scenarios … group them by
> different reasons … a simple fix button that explains how it'll be fixed."*
>
> Owner decisions the same day:
> 1. jellystructure may change a subtitle file itself, **but only when someone presses a button**. Bazarr is
>    always preferred. Where Bazarr can't fix something, either a *Fix now* button that explains itself, or say
>    plainly that it is wrong and suggest what to do.
> 2. Verdicts measured against the speech track may act like any other (*"whatever solves this is allowed"*).

## Status

`✓ Built` 2026-10-02, **not deployed** (see Build notes). Written 2026-10-02 (dev-authored), against `main` `f98f6c52`. Number verified free (admin specs top at
301). Not dev-reviewed. Builds on **301** (the measurement is right first).

**Amends** phase 273: FR-273-21 (the Dashboard card), FR-273-16's rule that a verdict from the speech track only
ever waits (dev review item 3), and FR-273-20's admin view (one list per cause). Phase 285's subtitle rows.

## Today

The two rows count verdicts, not causes, and say things that aren't true:

- **Severity.** Both are `warning`. On *Only report* (production) every one of these subtitles is still offered to
  viewers.
- **"Bazarr's sync could not line them up."** Nothing has been asked of Bazarr on *Only report*; the action log is
  empty.
- **Open → goes nowhere useful.** `/subtitles?fit=out` opens the Bazarr overview, which ignores `fit` and lists no
  verdict.
- **One row mixes four causes.** *Out of step* holds subtitles under 2 s off (readable), subtitles at the wrong
  speed, subtitles shifted by a constant, and subtitles timed for another cut. Each has a different fix.

The causes, on production before 301's re-measure (301 removes the false ones and the numbers will move):

| Cause | Verdict | Count | What Bazarr can do |
|---|---|---|---|
| Slightly early or late, under 2 s anywhere | `off`, worst < 2 s | 375 | Sync |
| Wrong speed (timed for 25 fps, or the 24 / 23.976 pair) | `off`, speed ≠ 1, worst ≥ 2 s | 431 | Sync with *fix framerate* |
| Shifted by the same amount, 2 s or more | `off`, speed 1, worst ≥ 2 s | 223 | Sync with a large enough max offset |
| Fits only part of the video (another cut) | `off_mid_file` | 21 | Sync applies one offset only: replace |
| Made for a longer video (a double episode, a disc, a longer cut) | `longer_video` | 143 after 301 | Replace |
| Made for another video | `not_this_video` | 30 | Replace |
| Filed under the wrong episode | `other_episode` | 12 | Move to that episode, then replace |

**Moving a longer subtitle into place was tried and dropped.** We searched each `longer_video` for a window that
fits the video (30 s steps, the usual ±120 s and five speeds) against its speech track. A part was found for 20 of
148. About half of those were the ad-line false alarms 301 removes. The double-episode cartoons scored z 4–5,
below the bar. Cutting a file on that evidence would break "rather nothing than wrong", so these are replaced
through Bazarr, and the row says when the video, not the subtitle, is the likely problem.

## Requirements

**FR-302-1 — One row per cause.** Every sidecar verdict belongs to at most one group:

| Group | Verdict | Label | Fix |
|---|---|---|---|
| `slight` | `off`, worst < 2 s | Subtitles slightly early or late | Sync (Bazarr) |
| `speed` | `off`, speed ≠ 1, worst ≥ 2 s | Subtitles at the wrong speed | Sync (Bazarr), fixing the speed |
| `shift` | `off`, speed 1, worst ≥ 2 s | Subtitles early or late by 2 s or more | Sync (Bazarr) |
| `cut` | `off_mid_file` | Subtitles that fit only part of the video | Replace (Bazarr) |
| `longer` | `longer_video` | Subtitles made for a longer video | Replace (Bazarr) |
| `wrong` | `not_this_video` | Subtitles made for another video | Replace (Bazarr) |
| `episode` | `other_episode` | Subtitles filed under the wrong episode | Move, then replace (Bazarr) |

`in_sync` and `cant_tell` belong to none. A group with no sidecars has no row (FR-285-5).

**FR-302-2 — Severity is what a viewer meets today.** A group is `critical` while at least one of its subtitles is
offered to viewers: always on *Only report*, and on the other modes when FR-273-17 does not hide it. When every one
is hidden, the group is `warning`: a viewer no longer sees it, but it still needs a fix. `slight` is always `info`.

**FR-302-3 — Each row says what is wrong and how it will be fixed.** The sentence is the cause in plain words,
then the fix, then the check after it:

- `slight`: *Under 2 s anywhere in the file; readable. Bazarr's sync lines them up, and each is checked again after.*
- `speed`: *Timed for video at another frame rate (25 instead of 23.976, or 24 instead of 23.976), so they drift
  further off through the file: up to a minute by the end of an episode at 25. Bazarr's sync fixes the speed; each is
  checked again after, and one that still doesn't fit is replaced.*
- `shift`: *The whole file is early or late by the same amount. Bazarr's sync moves it into place; each is checked
  again after, and one that still doesn't fit is replaced.*
- `cut`: *Timed for another cut: they drift apart partway through, which a sync can't fix. Bazarr blacklists each one
  and searches again.*
- `longer`: *They run on past the end: a double episode, a whole disc, or a longer cut. Bazarr blacklists each one and
  searches again. When the video itself is much shorter than the episode (see the list), the video is what needs
  replacing.*
- `wrong`: *They match nothing in this video. Bazarr blacklists each one and searches again.*
- `episode`: *Each matches another episode of the same series. Bazarr gives it to that episode, then searches again
  for this one.*

Every replacing row adds: *A language with nothing right stays empty: rather nothing than wrong. Downloads count
against the daily budget (N); the rest continue the next day.*

**FR-302-4 — The button fixes the whole group, by hand.** Pressing it (after a confirmation naming the count and the
fix) works through the group's subtitles one at a time, through Bazarr, whatever the mode: pressing it is the
admin's OK. For each subtitle:

- **Sync groups:** sync (273's FR-273-12: reference, max offset, fix framerate). When this file has already been
  synced and the check after it still finds it off, replace instead. When the check after a sync hasn't run yet,
  skip it for now.
- **Replace groups:** 273's replace (Bazarr's blacklist when the source is known, else a delete through Bazarr),
  with its limits: three rounds a day per language, inside the daily download budget. Past the budget, it waits for
  the next press.
- **`episode`:** move (FR-273-11), then replace.

Each subtitle is acted on at most once per file content (FR-273-16). After each change, Jellyfin is told and the
video is checked again (as 273 does). Work stops between subtitles while a TV plays (FR-273-12's deferral).

While a group is being worked through, its row says so (*Fixing now: 23 of 176*), and the button is not offered
again until the run ends.

**FR-302-5 — jellystructure itself writes no subtitle file here.** Every group has a Bazarr fix, so no group needs
jellystructure to edit a file. If one ever does, it follows owner decision 1: by hand only, with a sentence saying
exactly what will be written.

**FR-302-6 — Speech-track verdicts act like any other.** On *Fix it*, a verdict from the speech track is acted on
alone like one from a subtitle reference (owner decision 2; amends dev review item 3). The check after every change
is the safety net. A *doubt* (`cant_tell`, weak against speech) still only waits for an OK.

**FR-302-7 — Open → lists the group.** The Subtitles page takes `?fit=<group>`. Above the Bazarr overview it shows
every group with its count, and for the chosen one a list:
- the title (a link to its page) and the episode (`S01E05`);
- the file's name;
- the verdict in words (FR-273-20);
- what it was measured against (*the subtitle inside the file*, *the speech*, *another subtitle beside it*);
- whether viewers are offered it;
- for `longer`, the video's length beside TMDB's runtime, so a short video stands out (*2:45 · TMDB 12 min*).

The page fixes the group with the same button as the Dashboard row.

## API (admin only, additive)

| Route | |
|---|---|
| `GET /api/subtitles/fit?group=` | `{groups: [{id, label, count, severity, running?}], rows: [...]}`, at most 500 rows |
| `POST /api/subtitles/fit/{group}/fix` | starts the run; `202 {queued}`, `409` while one runs, `503` without Bazarr |

## Not in scope

- Moving part of a longer subtitle into place (see *Today*).
- A per-subtitle fix button (the title page already has *Needs your OK* for single proposals).
- Searching beyond ±120 s.

## Acceptance

1. On *Only report*, a group with one subtitle off by 10 s is `critical`. On *Fix it*, the same subtitle is hidden
   and the group is `warning`. `slight` is `info` on either.
2. Every verdict in production lands in exactly one group or none, and the group counts add up to the flagged total.
3. Pressing *Sync them in Bazarr* on `speed` on *Only report*: each subtitle gets one Bazarr sync with *fix
   framerate*, Jellyfin is told, and the video is checked again. A second press while it runs answers 409.
4. A subtitle synced whose check after it is still off is replaced on the next press, never synced twice.
5. Replace past the daily budget stops downloading and leaves the rest for the next press.
6. `/subtitles?fit=longer` lists those subtitles with the video's length and TMDB's runtime.

## Verification plan

Unit tests for the grouping, severity and the per-subtitle fix choice, with Bazarr faked (`BazarrOps`). Compile the
admin. On a copy of the production database, count the groups. `scripts/check-phases.sh`,
`scripts/check-mobile-css.sh`.

## Build notes (2026-10-02)

- **Groups and severity:** `subtitles/SubtitleFitGroups.kt` (`FitGroup.of`, `severity`, the sentences). `slight` is
  decided first (worst < 2 s), so a 1.001 speed over a short file is `slight`, not `speed`.
- **Dashboard:** `DashboardRoutes` builds one row per group from `fitGroups(...)` (shared with the route): `fix =
  "here"`, `action_id = "subs_fix:<group>"`, `href = /subtitles?fit=<group>`. While a run is going the row says
  *Fixing now: n of N* and offers *Open* only. The admin confirms with `confirmSubtitleFix` (count, sentence, one at a
  time, waits while a TV plays).
- **The fix:** `BazarrSteering.fixGroup` / `fixRow`. One `Mutex` now covers `act` and `fixRow`, so the loop and a
  press never act on the same video at once. A press counts only actions done, failed or approved
  (`doneOnContent`), so a proposal waiting on *Ask me first*, or one dismissed earlier, does not hold it back;
  after it acts, waiting proposals for that subtitle are answered (`answerWaitingFor`).
- **FR-302-6:** `act` and `preview` no longer hold back speech-track verdicts; `ask` in `fix_would` now counts doubts
  only.
- **Subtitles page:** `?fit=` shows *Do the subtitles fit their video?* above the Bazarr overview: the causes as chips,
  the chosen one's sentence, its button and up to 500 subtitles (title link, `S01E05`, language, words, file, what it
  was measured against, offered or hidden; for `longer`, the video's length and TMDB's runtime). Existing classes
  only, no new CSS.
- **Mockup:** `design/app/dashboard-data.js` shows the new rows.
- **Production, counted on a copy:** before 301's migration, `speed` 431, `shift` 223, `slight` 375, `longer` 167,
  `wrong` 30, `cut` 21, `episode` 12 (1 259 = every flagged verdict, each in one group). After it, before the
  re-check: `speed` 322, `slight` 282, `shift` 127, `episode` 9; the rest wait to be measured again. (`speed` is
  larger than the 176 first quoted to the owner: the 24 / 23.976 pair counts too.)
- Tests: three new in `BazarrSteeringTest` (one group per verdict and the severity rule; a press on *Only report*
  syncs, and a second group blacklists; a press goes past a waiting proposal, waits for the check after a sync, then
  replaces and never syncs twice). Subtitle + Bazarr suites 30/30. Backend and admin compile.
- **Owed:** after a deploy the owner approves, and once 301's re-check has run, the Dashboard on production, and one
  press of a small group (`episode`) watched end to end in Bazarr.
