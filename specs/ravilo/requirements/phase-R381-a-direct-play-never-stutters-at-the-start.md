# Phase R381 — A direct play never stutters at the start, and a stall is counted once

> Owner, 2026-10-08 (the streaming re-review): only stalls of ≥ 2 s, or two within a minute, lower what a device is
> trusted with (309, decision 2), **plus a small phase of its own to find and remove the sub-0.5 s stall at the start
> of direct plays** (170 of Stue TV's 265 direct plays reported one).

## Status

`Planned` — written 2026-10-08 (dev-authored) from a read-only investigation (`playback_qoe` and the Android player's
code). No test plays were needed; no device was touched. Not dev-reviewed, not built. Android (TV + phone) player and
the backend's readers of `playback_qoe` (308, 309).

## What happens

**Measured** (`playback_qoe`, last 30 days, direct plays):

| Device | Direct plays | Reported a stall | Rows only repeating the previous row's totals | Distinct new stall events | ≥ 2 s | Median new stall |
|---|---|---|---|---|---|---|
| Stue TV (BRAVIA VH21) | 506 | 323 (64 %) | 215 | 105 | 0 | 460 ms |
| Kids' TV (BRAVIA VH2) | 216 | 158 (73 %) | 116 | 40 | 1 | 482 ms |
| Pixel 9 Pro | 36 | 6 | 2 | 4 | 0 | 611 ms |
| Web | 16 | 0 | — | — | — | (not measured on the web, R216) |

- **Most "stalls" are one number copied forward.** On Stue TV on 2026-10-06, thirteen kids' episodes in a row over
  3.5 h each reported `rebuffer_count 1, rebuffer_ms 144`. The second episode had the one event, and every later
  episode's report carried it. Cause, in `RaviloPlayerAndroid.kt`: the `qoe*` counters "deliberately persist across a
  binge's episode-to-episode player reuse (never reset)", and `qoeSnapshot()` sends those running totals as the item's
  own report. So each item's row holds the whole session's stalls so far.
- **Most of the real events are the next item's own start, counted as a stall.** `qoeFirstFrameRendered` is set once
  and never reset per item. When the reused engine loads the next item, its first `STATE_BUFFERING` (the normal cold
  start of a new file) comes after "the first frame" (the previous item's) and is not a seek, so it is counted. **91 of
  Stue TV's 100 new events and 34 of the kids' TV's 38 are exactly that**: the count goes from 0 to 1 on the second item
  after a fresh player. The viewer sees the normal start-up, not a stutter in the picture.
- **What is left is small:** about 9 events on Stue TV and 4 on the kids' TV in 30 days (≈ 2 % of direct plays), all
  under 2 s. These may be real (suspects below), but nothing measured says so yet.

**Suspected, not measured** (the remaining ≈ 2 %): `DefaultLoadControl`'s stock `bufferForPlaybackMs` (2.5 s) starting
playback on a thin buffer from the NAS; a subtitle side-load attaching after the first frame; R379's decoder restart
(counted as a stall today); R220's surface recovery seek; a 308 variant switch on a transcode.

**Why it matters beyond the report:** 308's `measuredThroughput` and 309's record read `playback_qoe`. 309's
`stalled_bps` and FR-309-8 would treat these copied and miscounted events as real, pushing most of Stue TV's plays
from direct play to transcodes, the opposite of the goal.

## Requirements

### FR-R381-1 — A report counts its own item only

Every QoE report carries the counts **of that item's play only** (reset in `load()`, alongside R218's per-item fields).
If session totals are still wanted (R216's "did this happen at all this session"), they are separate fields
(`session_rebuffer_count`, `session_rebuffer_ms`), never the item's own.

### FR-R381-2 — A stall is a stall in the picture, after this item's first frame

A stall counts only after **this item's** first rendered frame, and never inside:
- the item's own start (load → first frame),
- a seek, resume or skip (Skip Intro/Credits),
- an audio or subtitle track switch (R195/R291 restreams),
- R379's decoder restart, or R220's surface recovery,
- a 308 variant switch window, or a return from the background (R292).

Each of those is counted under its own name, so nothing is hidden, but it is not a stall.

### FR-R381-3 — A counted stall says where and why

Each counted stall is reported with: seconds since this item's first frame, the position in the file, how much was
buffered just before, direct play or transcode, the variant (308), and the phase (`start` within 10 s of the first frame,
`mid` after). At most 20 per report, newest kept. The admin's *Playing now* and device page (304, 185) show stalls from
these fields, not from the old totals.

### FR-R381-4 — The backend never trusts the old numbers

308's and 309's readers of `playback_qoe` use FR-R381-1's per-item counts. For rows from apps without this phase (no
`per_item` marker in the report), they ignore `rebuffer_count`/`rebuffer_ms` entirely: they cannot be told apart from
copied totals. A one-off clean-up marks existing rows as legacy (no data is deleted).

### FR-R381-5 — Remove the real start stalls that remain

After a week of corrected numbers on the household's TVs, any phase that still shows real stalls in the first 10 s of
a direct play is fixed at its cause:
- a thin first buffer → raise `bufferForPlaybackMs` for direct play from a local file server (measured, at most ~1 s
  more start time);
- a subtitle side-load attaching after the first frame → load it before `prepare()` or off the playback path;
- any other cause FR-R381-3 names.

Each change is its own small commit, with the before and after numbers recorded in this spec's build notes.

### FR-R381-6 — Tests are part of the phase

1. The counter as a pure state machine (extracted from the `AnalyticsListener` so it can be unit-tested), driven by
   event sequences:
   - one item, no stall → 0;
   - a binge of three items on one engine → each report 0, and the second item's start is not a stall;
   - a seek → not a stall;
   - a real mid-play stall → 1, with its phase and position;
   - an R379 restart, an R220 recovery, a track switch and a variant switch → each named, none a stall.
2. A report after a stall in item 2 of a binge: item 3's report shows 0, not 1.
3. The backend: legacy rows are ignored by `measuredThroughput` and 309's record; per-item rows are used.
4. A Media3 integration test (`TestPlayerRunHelper` with a fake clock) of load → first frame → load the next item →
   first frame: no stall counted.

## Acceptance

1. Over one week on Stue TV and the kids' TV, **≤ 5 % of direct plays report any stall**, and no report repeats the
   previous item's numbers.
2. A binge of ten episodes with no visible stutter reports 0 stalls on all ten.
3. Every remaining reported stall carries its phase and position, and the cause is recorded in the build notes.

## Non-goals

- Making the start of a direct play itself faster (that is 309's early start and R266).
- Stall counting on the web (R216 has no browser counters; R376 may add them).

## Open questions (dev)

1. Preloading the next episode during a binge (adding it to Media3's playlist so its start is buffered before the
   credits) would remove the wait between episodes. In scope here, or its own phase? Lean: its own phase, after this
   one has corrected the numbers.
2. Whether the desktop (mpv) and Cast receiver counters have the same carry-over (the receiver resets per load in
   `Receiver.kt`; mpv's `stalls` is per player instance). Check and align with FR-R381-1.

## Decided by the owner (2026-10-08)

1. **Preload the next episode inside this phase** (owner, over the lean of its own phase): during a binge the next
   episode is added to the player's queue before the current one ends (at the credits marker, or 60 s before the end),
   so it starts with no wait. It follows R375's next episode, never preloads after a film, stops preloading if the
   viewer seeks back out of the credits, and counts nothing as a stall (FR-R381-1's rules apply per item). A direct play
   preloads its file; a transcode asks for the next episode's encode at the same moment (309's early encode / 313's job).
