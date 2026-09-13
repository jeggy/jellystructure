# Phase 201 — mkvpropedit moves the track list to the end of the file, and Ravilo can never start it

> Live report, 2026-09-08: *"it seems like special seasons dont work in ravilo? when playing snurre
> snup byggevenner season 0 episodes it just loads for ever"*
>
> It is not season 0. It is **164 files** across 7 titles, including 35 of the 39 Season 1 episodes of
> that same series. The viewer landed on a working Season 1 episode first and a broken Specials one
> second, and the coincidence named the wrong axis.

## Status
✓ Built 2026-09-10 (FR-201-1/2/3/4/6/8). Root cause corrected 2026-09-10 after a byte-exact
reproduction — see *Correction* below. Audit-authored, not dev-reviewed, not deployed. Backend/media-file
only — **no Ravilo counterpart and no client change**: the client is behaving correctly given a file whose
track list it cannot reach. See STATUS.md for the build summary.

**FR-201-5's repair route ran for real for the first time 2026-09-13**, on a different series
(Hoppy Hare Builders, not the Hoppe Hares Byggebande example above) — see the 2026-09-13 "later"
amendment at the bottom for what that click found and fixed (a real concurrency bug, no data lost) and
FR-201-12's amended, job-queue-backed behavior. The original 164-file count from this spec's opening
example is still not run against production as of writing.

**Amended 2026-09-12 — FR-201-6 shipped half of itself.** The sweep and repair *routes*
(`GET /media/health/mkv-layout`, `POST /media/health/mkv-layout/repair`) exist and work — confirmed
live by re-running the sweep by hand, which found **165** files (the original 164 plus one new
`Ashworth` episode broken by a track edit made after the 2026-09-08 count, before FR-201-2/3 shipped
their guard). But FR-201-6's own text promised the sweep would be "surfaced on the Activity page's
existing health reporting," and it never was — there is no reference to `mkv-layout` anywhere in the
frontend. An operator has no way to discover a single one of these 165 files is broken except calling
the route by hand. FR-201-9 through FR-201-13 below close that gap: a real triage surface grouped by
title, plus a fix action on the title's own detail page, so this doesn't stay a curl-only feature.
Not yet built.

### Correction (2026-09-10)
The first draft of this spec blamed `-cues_to_front 1` in `TrackCommandBuilder.ffmpegReorder:102`.
**That was wrong and `-cues_to_front` must stay.** Running the exact production remux under the
container's own ffmpeg 5.1 produces a *correct* file — `Tracks` at offset 293, `Cues` at 2832, first
`Cluster` at 5674, matching the broken file's layout in every respect except the one that matters.
The eviction happens in the `mkvpropedit` call that runs **after** it. Evidence below.

## The finding

`MkvpropeditRunner.setDefault` — via `TrackCommandBuilder.mkvDefault` — edits the MKV **in place**. It
is fast and lossless, which is exactly why it was chosen over an ffmpeg remux for MKV flag and
language edits. But setting `flag-default=0` on tracks that carry no `FlagDefault` element (the
element's default value *is* 1, so writing 0 must add it), or adding `language-ietf` alongside
`language`, makes the `Tracks` element **grow**. When the grown element no longer fits its original
slot and there is no adjacent `Void` to absorb it, mkvpropedit overwrites the old slot with a `Void`
of the identical size and **appends `Tracks` to the very end of the file**, reachable only through
`SeekHead`.

`mkvpropedit` is called from four places, all of which can do this:

| site | call |
|---|---|
| `TrackRoutes.kt:587` | bulk reorder → `setDefault` **after** `FfmpegRunner.reorderTracks` |
| `TrackRoutes.kt:626` | bulk reorder, `toFlagFix` loop → standalone `setDefault` |
| `TrackRoutes.kt:254` / `:364` | single-title set-language / set-default |

### Reproduced byte-for-byte, 2026-09-10

Starting from a clean remux of `Hoppy.Hare.Builders.S00E02`, run inside the production container:

| step | offset 52 | 121/124 | 213 | 293 | 937 | 2832 | 5674 | EOF |
|---|---|---|---|---|---|---|---|---|
| after `ffmpeg … -cues_to_front 1` | SeekHead 64 | Void 83 | Info 75 | **Tracks 638** | Tags 1889 | Cues 2836 | Cluster | — |
| after `mkvpropedit --set language…` | SeekHead 67 | Void 80 | Info 75 | **Void 635** | Tags 1889 | Cues 2836 | Cluster | **Tracks @92539415** |
| **the live broken file** | SeekHead 67 | Void 80 | Info 75 | **Void 635** | Tags 1889 | Cues 2836 | Cluster | **Tracks @92539415** |

The last two rows are identical. Its `SeekHead` says so outright:
`Info → 213 · Tags → 937 · Cues → 2832 · Tracks → 92539415`.

The `writing_application` on every broken file is `Lavf59.27.100` — ffmpeg 5.1, the version inside our
own container, not the host's 7.1. So our remux wrote the file and our mkvpropedit call then moved its
track list to the end.

### Why only Ravilo breaks

Everything that opens the file **as a local seekable file** follows `SeekHead` and is fine — `ffprobe`
reports all 7 streams, our scanner records them, Jellyfin's `MediaSources` are complete and correct,
and Jellyfin will happily direct-play it.

ExoPlayer's `MatroskaExtractor` reads the HTTP body **linearly**. It reaches the first Cluster having
never seen a `Tracks` element, so it never builds a single renderer format. It does not error — there
is nothing to error about — it simply never leaves the buffering state.

The telemetry matches that precisely:

| | working S01E01 | broken S00E02 |
|---|---|---|
| `POST /api/tv/playback/start` | 200, `direct_play=true` | 200, `direct_play=true` |
| Jellyfin `GET /Videos/…/stream` | 200, full body | 200, full body |
| `playback_qoe.video_decoder` | `c2.exynos.hevc.decoder` | **empty** |
| `playback_qoe.rebuffer_count` | 1 | **0** |
| `playback_qoe.dropped_frames` | 1 | **0** |

Zero rebuffers, zero dropped frames and no decoder name is not a slow start — it is a player that
never had a track to decode. The client closes the stream ~30 ms after the response headers (Caddy:
`aborting with incomplete response … broken pipe`, 0.02–0.03 s) and never reopens it, because there is
nothing left to ask for. `/api/tv/playback/start` returns in 0.18 s with a correct ticket throughout.

## Blast radius (measured 2026-09-08, full sweep of 7 885 `.mkv` files)

**164 files** have `Tracks` after the first `Cluster`. Every one is unplayable in Ravilo on every
platform, and plays normally in Jellyfin's own clients.

| title | files |
|---|---|
| Hoppy Hare Builders (Hoppe Hares Byggebande) | 85 of 93 — S01 35/39, S02 36/40, Specials **14/14** |
| Ben the Bricklayer | 52 |
| Ashworth | 8 of 8 |
| The Tidier (2021) | 6 |
| Things We Hide | 6 |
| Chore Captain | 5 |
| movies — *Adventures of the Road Runner (1962)*, *The Engagement (2026)* | 2 |

`media_history` ties the two largest runs to this path: `bulk_reorder_tracks` on
`bugs-bunny-builders-2022` (93 episodes, 2026-09-07 14:34) and `ashworth-2020` (8 episodes,
2026-09-07 10:34). Ashworth is 8-for-8.

**Whether a file breaks is data-dependent** — it turns on whether the grown `Tracks` still fits. That
is why 8 Bugs Bunny files survived and 85 did not, and why this cannot be caught by testing one file.

## Requirements

**FR-201-1 — no write path may leave a file whose `Tracks` element follows the first `Cluster`.**
This is the invariant. Everything below is a means to it.

**FR-201-2 — check the layout after every mkvpropedit edit.** A top-level EBML walk of the edited
file, confirming `Tracks` precedes the first `Cluster`. It reads a few hundred bytes and needs no
subprocess. It belongs in `MkvpropeditRunner`, once — not at each of the four call sites. **It must
not use ffprobe**, which seeks and therefore always passes.

**FR-201-3 — a file that fails the check is repaired in place, immediately, before the operation
reports success.** `ffmpeg -i <file> -map 0 -c copy -cues_to_front 1 <tmp> && mv` restores `Tracks` to
the front *and* keeps Cues at the front. Measured: **0.59 s for a 92 MB file**, with every stream,
language tag and default/forced disposition byte-identical before and after. Same `tmpPath` +
ownership-preservation wrapper `FfmpegRunner.runRemux` already uses. The repair is not optional or
deferred — an unrepaired file is a title that silently cannot be played.

**FR-201-4 — keep `-cues_to_front 1`.** It is innocent here and it is load-bearing: without it ffmpeg
writes Cues at the end and ExoPlayer spends ~7.6 s reading most of the file before playback starts
(the 2026-08-16 report). Note that **`mkvmerge` is not a substitute** — it preserves `Tracks` at the
front but writes Cues at EOF (verified 2026-09-10), which trades this bug for that one.

**FR-201-5 — repair the 164 existing files** with the same one-line remux as FR-201-3. Lossless: no
re-encode, no metadata loss, no track-order change. ~0.6 s each plus I/O. This is an operator action
on the media library, not something a scan may do on its own initiative. The file list is reproducible
from a sweep (FR-201-6) rather than stored.

**FR-201-6 — a detector the operator can run and read.** The same EBML walk over a library,
classifying each `.mkv` as `ok` / `tracks-after-clusters`, surfaced on the Activity page's existing
health reporting. Cheap — the full 7 885-file sweep ran in well under a minute. It must not
auto-repair.

**FR-201-7 — Ravilo says nothing new.** A file this broken should be repaired, not explained. No new
copy, no new error state, no "this file can't be played" screen — R237's classification covers real
start failures and this is not one (the start *succeeds*). A client-side guard, if ever wanted,
belongs in its own phase with its own thinking about what the viewer is meant to do about it.

**FR-201-8 — in the bulk-reorder path, make the ffmpeg remux the last writer.** `TrackRoutes.kt:587`
runs `setDefault` *after* `FfmpegRunner.reorderTracks`; swapping them lets the remux lay `Tracks` out
correctly for free, so the common path never reaches FR-201-3's repair at all. This is a complement to
FR-201-2/3 and **never a replacement** — it does nothing for the `toFlagFix` loop at `:626` or the
single-title edits at `:254`/`:364`, which have no remux to be last.

### Triage surface (added 2026-09-12 — closes the gap in FR-201-6)

`MkvLayoutAudit.sweep` reports a flat list of broken file paths. That is enough for a curl call and
nothing else: no screen in the admin turns a path back into "which movie or which series and episode is
this." The following make the existing sweep/repair routes into an actual triage feature, the same way
Phase 95's `missingFromSource` makes an item surface in Triage rather than just sit in a log.

**FR-201-9 — the sweep groups by title, not by raw path.** `MkvLayoutAudit.SweepResult` gains a
per-item breakdown: for each `MediaItem` that has at least one `TRACKS_AFTER_CLUSTER` file, its `id`,
`title`, `kind` (movie/series), and the affected path(s) — for a series, keyed further by which
episode(s) (season/episode number, falling back to filename if either is null, same as the Seasons &
episodes tab already does for an unparsed file). The flat `tracksAfterClusters: List<String>` stays for
`FR-201-5`'s existing repair contract; the grouping is additive, not a breaking change to the response
shape.

**FR-201-10 — a health card on `app/activity.html`.** "MKV track layout" (or folded into an existing
health section if one is added around the same time — this is not precious about its own card), showing
a total broken count and a scrollable list of affected titles, each row: poster thumb, title, kind, and
a count ("14 episodes" / "1 file"). Clicking a row navigates to that title's `media.html` or
`series.html`. Sourced from `GET /media/health/mkv-layout`; the sweep is cheap (full library under a
minute per the FR-201-6 measurement) but is **not** run on every Activity page load — run it on demand
(a "Check now" affordance, or once per admin session, mirroring how the Phase 93 divergence check
already behaves on this page) rather than a page-load side effect no one asked for. Empty list ⇒ the
card doesn't render at all — this is a defect surface, not a permanent fixture.

**FR-201-11 — a fix banner on the title's own detail page.** `media.html` (movie) and `series.html`
(series) each check, on load, whether the open title appears in the last sweep's broken-title list (via
the item id from FR-201-9 — no separate per-title endpoint needed, the admin frontend already holds the
sweep result from FR-201-10, or refetches it if the operator navigated here directly). If it does: a
banner in the same family as the existing drift/Jellyfin-lock banners (Phase 71/74 pattern), reading
something like *"3 episodes in this series can't play in Ravilo (track list unreachable)"* / *"This
file can't play in Ravilo (track list unreachable)"* for a movie, with a **Fix now** button.

**FR-201-12 — Fix now calls `POST /media/health/mkv-layout/repair` scoped to this title only** — the
paths belonging to this item from FR-201-9's grouping, never the full library list. Write-through
(Phase 71 convention): on success the banner clears immediately without a page reload; the button shows
a busy state while the repair runs (FR-201-3 measured ~0.6 s per file, so a multi-episode series could
take a few seconds — the button must not look inert during that window). A partial failure (one file's
repair reports `false`) keeps the banner, states how many of N still fail, and Fix now retries only the
still-broken ones.

**FR-201-13 — no new state is invented for this.** The banner and the card both read directly off a
sweep result — nothing about "broken" is written to `media_history`, the item's `issueCount`, or any
other persisted field. Re-running the sweep is the only source of truth, matching FR-201-6's original
"never auto-repairs, this is a report" posture: a title that gets fixed by FR-201-12 simply stops
appearing the next time the sweep runs, the same way FR-201-3's own automatic repair leaves no residue
once a file is fixed.

## Out of scope

- Re-encoding anything.
- Replacing `mkvpropedit` with an ffmpeg remux for flag/language edits. In-place editing is the whole
  point of that path; a post-condition check plus a rare repair is far cheaper than remuxing every
  edit.
- Changing ExoPlayer/`MatroskaExtractor` behaviour or making a player seek to a trailing `Tracks`
  element. Reading the tail of an HTTP stream before the head is not something to build into a player.
- The Jellyfin side. Jellyfin is correct throughout.
- A dedicated Triage-dock entry (FR-201-9/10 use the Activity page, per FR-201-6's original placement;
  promoting it into the floating Triage dock is a separate call the operator hasn't asked for here).
- A per-episode fix button inside the Seasons & episodes tab. FR-201-11/12 fix a whole title in one
  action; a row-level "just this episode" button is easy to add later on top of the same repair route
  if the all-at-once button ever proves too coarse, but nothing today calls for it.
- Persisting any "was broken" history once a file is fixed. FR-201-13 is explicit that the sweep is the
  only source of truth.

## Open questions

1. **Ben the Bricklayer (52), The Tidier (6), Things We Hide (6), Chore Captain (5)** have no
   `reorder_tracks` / `bulk_reorder_tracks` row in `media_history` — but they may well have gone
   through the single-title set-language/set-default paths, which record different actions. Confirm
   before FR-201-2 is called complete; if some other writer can do this, the check is in the wrong
   place.
2. **Repair ordering.** Nothing stops the next flag edit from re-breaking a repaired file until
   FR-201-2/3 ship, so the repair should follow the code fix rather than precede it.
3. **(added 2026-09-12) How often does the Activity page's sweep refresh?** FR-201-10 deliberately
   doesn't run it on every page load; whether "once per admin session" is the right cadence, or whether
   it should also re-run automatically right after any bulk track-edit operation (reorder, bulk
   set-default/set-language), is unresolved. Leaning toward the latter since that's exactly the
   operation that causes the breakage — but it means threading a "sweep just this title" call into
   `TrackRoutes.kt`'s existing bulk-edit handlers, which FR-201-9's per-item grouping makes cheap enough
   to consider but doesn't itself decide.
4. **(added 2026-09-12) Does FR-201-9's per-episode keying need a stable episode id, or is
   season/episode-number-with-filename-fallback good enough?** The Seasons & episodes tab already lives
   with the fallback for display; whether Fix now's retry-only-the-failed-ones behavior (FR-201-12)
   needs something sturdier is worth a second look once this is actually being built, not guessed here.

### Amendment (2026-09-13) — the operator asked for this directly, and asked for the Dashboard, not Activity

Live report: *"Hoppe Hares Byggebande"* (this spec's own opening example) has no issue anywhere in
jellystructure and no category on the Dashboard — confirming the 2026-09-12 finding above: FR-201-9
through FR-201-13 were speced but never built. Verified again live-inspecting
`Hoppy.Hare.Builders.S01E14...mkv` with `mkvinfo`: no top-level `Tracks` element appears before the
file's single reported `Cluster` — the exact defect, still present, still unrepaired.

**Building FR-201-9/11/12/13 now, FR-201-10 superseded rather than built as originally written.** The
operator's own words: *"it a[s] an issue in jellystructure and then as a category on the dashboard, and
when opening an item with this type of issue, we should be prompted on the media details page... and an
explanation about what's wrong and what will fix it."* That is the **Triage framework** (Phase 117/146)
verbatim — every other issue type already gets exactly this shape (a `TriageTypeCount` row rendered as a
Dashboard "attention breakdown" cell, a `Library ?filter=` value, a click-through). Building a *second*,
parallel health card on `activity.html` for one issue type when the standing mechanism for "issue type
with a count and a fix" already exists on the Dashboard would be the inconsistency, not the fix. This
spec now treats **FR-201-10 as superseded**: `mkv_track_layout` becomes a normal `TriageTypeCount` /
`?filter=mkv_track_layout` entry instead of a bespoke Activity card. No Activity page change.

Answers open questions 3 and 4 as built:
- **Refresh cadence (Q3):** a process-lifetime cache (`MkvHealthCache`), lazily refreshed at most every
  15 minutes on `/triage/count`/`/triage` access — cheap enough per FR-201-6's own measurement not to
  need a dedicated trigger, and avoids a full-library file-header sweep on every Dashboard load. A
  successful repair (FR-201-12) removes just that path from the cache immediately rather than waiting
  out the interval — the same "no stale false-positive after a fix" property FR-201-13 asks for, without
  needing a full re-sweep. Hooking a sweep into bulk track-edit ops (the rest of Q3) stays unresolved —
  not built.
- **Per-episode keying (Q4):** the detail-page Fix banner (FR-201-11) doesn't reuse FR-201-9's
  library-wide grouping at all — it calls a new, cheap **per-item** endpoint
  (`GET /media/{id}/health/mkv-layout`, this item's own file(s) only, live, no cache) and matches the
  returned broken paths against the episodes/path the page already has loaded. FR-201-9's dedicated
  "SweepResult groups by title" data structure is therefore **not built** — the Dashboard/Library side
  only ever needs a flat broken-paths set (matched per item via the same `TriageDetection` predicate
  style every other issue type uses), and the detail page computes its own per-title grouping from a
  live per-item check. Simpler than FR-201-9 as originally scoped, and sidesteps the stable-episode-id
  question entirely since nothing persists a grouping keyed by episode.

### Amendment (2026-09-13, later) — Fix now's first real click found a concurrency bug; repair moved onto the media job queue

The Triage surface's first genuine production use: an operator clicked **Fix now** on a series with 85
broken episodes (Hoppy Hare Builders S2, not this spec's own Hoppe Hares Byggebande example). FR-201-12
as built ran every file's repair inline on the request thread with no progress feedback — for 85 files
that is minutes of a button that just looks disabled. Reading that as stuck, the operator reloaded and/or
re-clicked several times; each attempt was a fresh HTTP request that repaired the *same* files again, and
`docker logs` showed the identical episode paths being remuxed **16–26 times each, concurrently**, over a
~5 minute window. `FfmpegRunner`'s remux helper uses a fixed, non-unique temp filename
(`.jstmp_<basename>`) per path — shared by every track-edit function, not just this repair — so two
overlapping repairs of the *same* path race on that name. This time every losing attempt's `mv` failed
harmlessly onto a temp file the winner had already renamed away (confirmed on disk: no corruption, no
leftover temp files, and the MKV header layout is now correctly repaired on the sampled episodes), but
that was luck, not a guarantee — a truly simultaneous double-write to the same open temp path could have
corrupted whichever `mv` ran last.

**FR-201-12 is amended: the repair route no longer runs inline.** `POST /media/health/mkv-layout/repair`
now takes `{mediaId, paths}` and enqueues one `mkv_layout_repair` job on the existing Phase 109
`MediaJobQueue` (the media lane — the same single-worker FIFO that already serializes every
reorder/remove ffmpeg remux), returning `202 {jobId}` immediately instead of blocking for the whole
series. The job repairs its paths one at a time, reporting `filesDone`/`pct` after each so a long repair
shows real, visible progress on **Activity ▸ Jobs** — which is also the answer to "if it takes a while, can
we see the queue": yes, the same page every other bulk ffmpeg operation already uses. Critically, the
job queue's single-worker guarantee is what actually rules out the concurrency bug: a second "Fix now"
click (or a third, or a page reload) now just enqueues a second job behind the first — it can never run
alongside it, on this file or any other the media lane is touching. `MkvLayoutAudit.repair` itself keeps
no lock of its own; the queue is the only mutual-exclusion mechanism, matching how reorder/remove already
work. The detail-page banner (FR-201-11) no longer live-updates on completion or retries failed paths
itself — it shows "Repairing N files — queued. Progress in Activity ▸ Jobs." and leaves the button
disabled, the same convention `media.html`'s existing "Fix cover track" (Phase 144) action already uses
for its own queued remuxes; the banner clears on the next sweep/page load once the files are actually
fixed.

**FR-201-13's "no new state is invented" still holds, with one clarification.** That requirement is about
not persisting *"is this file broken"* anywhere but the live sweep — still true. What's new here is a
`media_history` row per repair job (`mkv_layout_repair`, `"fixed=N failed=M of T"`), the same *action log*
every other track-edit route already writes (`reorder_tracks`, `remove_track`, `bulk_reorder_tracks`,
`set_default`, …). This repair route was the one write path in the whole track editor that recorded
nothing at all about what it had just done — an operator had no way to see, on a title's own History tab,
that a repair had ever run. Logging the action taken is not the same thing as persisting broken-state,
and every sibling route already does it.

Fixed same day: `c10716ef` (an ad hoc in-flight-path guard, immediate stopgap) then `51b82387`
(supersedes it — the real fix, moving the repair onto `MediaJobQueue` as described above). Not
deployed as of writing.
