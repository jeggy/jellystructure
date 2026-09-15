# Phase 214 — stopping background work must actually stop it, and say what it cannot stop

**Status:** Planned
**Authored:** 2026-09-15 (design-authored with the owner, not dev-reviewed)
**Depends on:** Phase 182 (`cancelRun` actually cancels), Phase 164 (per-lane job cancel),
Phase 178 (defer while playing)
**Sibling:** Phase 213 — 213 bounds how much un-recallable work can exist; this phase is how an
operator stops it and what they are told.

---

## 1. Why

During the 2026-09-15 playback incident the owner tried to stop a runaway `prewarm_subtitles` pass and
reported: *"there is no UI to cancel it"*. They then **restarted the whole jellystructure backend**,
and the viewer's playback was still broken afterwards.

Both halves of that are worth fixing, and neither is the bug they look like.

### §1.1 The control exists — it is just called something else

`Activity.kt:132` renders:

```html
<button id="act-cancel-btn" class="btn sm ghost" style="display:none">Pause</button>
```

It calls `MediaApi.cancelScan()` → `POST /api/scan/cancel` → `scanTracker.cancelRun(appScope)`, which
since Phase 182 (FR-182-5) genuinely cancels the run's `Job` and force-frees its slots. Dashboard has a
second entry point at `Dashboard.kt:359`.

So the function is there, and it works. The problems are presentational and they defeated a competent
operator under pressure:

- **The label says "Pause".** The action is a terminal cancel. An operator hunting for a way to *stop*
  a runaway pass does not read "Pause" as the stop button — it promises something reversible and
  partial — and an operator who *does* press it gets a harder outcome than the word implied. The label
  is wrong in both directions at once.
- **It is `ghost`-styled and hidden** (`display:none`) until scan status reports running
  (`Activity.kt:344`), tucked in the pagebar after the WebSocket badge.
- **It is all-or-nothing.** The incident was one step of a nine-step pipeline. The only remedy offered
  is killing the entire run, including the seven steps that had already completed usefully.

### §1.2 Neither cancel nor a restart stops work already handed to Jellyfin

This is the substantive gap, and it is why the restart appeared not to work.

`prewarm_subtitles`' cost is an ffmpeg running **inside the Jellyfin container** (Phase 213 §2.1).
jellystructure can stop *issuing* requests. It cannot recall one already made — Jellyfin's extraction
is filling its own subtitle cache and does not care that the HTTP client disconnected, and there is no
Jellyfin API to cancel it.

Measured on the night:

| Time (UTC) | Event |
|---|---|
| 18:31:41 | operator restarts the jellystructure container |
| ~18:36 | Jellyfin's subtitle-extraction ffmpegs finally exit |

**~5 minutes of continued disk saturation with jellystructure not even running.** From the viewer's
seat the restart did nothing, which is exactly what was reported. Nothing in the product said this
would happen, and the most drastic action available — a full restart — silently failed to deliver what
it obviously implied.

## 2. Requirements

### FR-214-1 — call it what it does

`#act-cancel-btn`'s label becomes **"Stop scan"**, and it is no longer `ghost`-styled. Same for the
Dashboard control.

If a genuine pause (resumable, keeps the working set) is ever wanted, it is a separate action with its
own verb — `POST /scan/resume` already exists, so this is a real possibility, not a hypothetical. What
must not continue is one control whose word and behaviour disagree.

### FR-214-2 — stop one step, not only the whole run

The Activity run view gains a per-step stop on the **currently executing** step, alongside the
whole-run stop.

The incident's shape is the argument: eight of nine steps were fine, one was saturating a disk, and the
only offered remedy was to kill everything. With Phase 213 the subtitle work becomes a lane with its
own jobs, so "stop the subtitle pre-warm and let the rest of the run finish" becomes expressible — and
per-lane stop already exists for segments (`Activity.kt:649`).

### FR-214-3 — say what stopping cannot reach

A stop that touches work already delegated to Jellyfin must say so, at the moment it is pressed, in the
operator's own terms. Not a tooltip, not a doc note — the confirmation itself:

> Stopped. jellystructure won't start any more subtitle extractions. Jellyfin is still finishing
> **N** it already started; those can take a few minutes and there's no way to stop them.

The count is known — it is the number of in-flight `warmSubtitleExtraction` calls. If it is zero the
sentence does not appear at all.

Phase 164's existing precedent is exactly this discipline: the segments lane's cancel is labelled
**"Stop after this episode"** rather than "Cancel", because *"segments-lane cancel is cooperative (no
temp file to kill); say so rather than implying an instant stop the media lane's own Cancel genuinely
provides"* (`Activity.kt:647-649`). This phase applies the same rule one layer out, to work running in
a different process entirely.

### FR-214-4 — the same warning on restart is out of scope, and here is why

A restart is not a jellystructure affordance — it is `docker restart` on a terminal. There is nowhere
honest to put a warning, and inventing an in-app "Restart backend" button purely to host one would add
a dangerous control to solve a documentation problem.

Instead: FR-214-3's sentence is what teaches the operator the property. Once they know Jellyfin-side
work outlives a stop, it follows that it outlives a restart. Recorded here as a deliberate non-goal so
it is not re-proposed as an oversight.

### FR-214-5 — the stop control must be reachable when it matters

`#act-cancel-btn` appears only while scan status reports running. A run that has moved into a state the
status does not report as running, or a page loaded after that moment, leaves no way to stop anything.

The stop control's visibility keys on **whether any run or lane has work in flight**, not on the scan
status string. This is the same class of bug as the 2026-09-05 fix already recorded in
`awaitPlaybackClear` — a live-only WS event left a reconnecting client with no way to discover state
that was still true.

## 3. What this does not do

- **It does not add a Jellyfin-side kill.** No such API exists. Phase 213 is the real mitigation: at
  concurrency 1, the un-recallable tail is one extraction instead of ten.
- **It does not add a restart button.** See FR-214-4.
- **It does not change cancel semantics.** Phase 182's `cancelRun` is correct; only its presentation
  and granularity change.

## 4. Open questions

1. **Should "Stop scan" ask for confirmation?** It is terminal and may discard a long run's remaining
   steps. But a confirmation dialog on the button an operator reaches for during a live incident is its
   own cost. Leaning no; not decided.
2. **Is a real pause worth building?** `POST /scan/resume` exists and `startResume` takes `skipIds`, so
   the machinery is most of the way there. It would make FR-214-1's rename less of a consolation.
3. **Per-step stop and resumability.** FR-214-2 stops the executing step — does the run then continue
   to the next step, or end? Continuing is more useful and more surprising. Undecided.
4. **Should the in-flight count in FR-214-3 be live?** A static number at press time goes stale within
   seconds. A live countdown is more honest and more code.

## 5. Verification

- Reproduce the incident with Phase 213 in place: stop the subtitle lane mid-run, confirm the run's
  remaining steps continue and no new extraction is issued.
- Confirm FR-214-3's sentence appears with a correct non-zero count, and is absent at zero.
- Load Activity fresh while work is in flight and confirm the stop control is present (FR-214-5).
