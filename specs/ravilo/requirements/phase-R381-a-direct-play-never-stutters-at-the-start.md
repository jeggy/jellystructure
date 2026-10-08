# Phase R381 — A direct play never stutters at the start, and a stall is counted once

> Owner, 2026-10-08 (the streaming re-review): only stalls of ≥ 2 s, or two within a minute, lower what a device is
> trusted with (309, decision 2), **plus a small phase of its own to find and remove the sub-0.5 s stall at the start
> of direct plays** (170 of Stue TV's 265 direct plays reported one).

## Status

`⚠ Partial` — **R381a (counting) and R381b (prepare + direct-play prefetch) built 2026-10-08** on branch
`worktree-agent-aceb0612dd5f38a6b` (not merged, not deployed, no app release); the counting half verified live on Stue TV
with a debug build (see *Build notes*). R381c (FR-R381-5, fixing what real start stalls remain) waits for a week of
corrected numbers by design. Written 2026-10-08 (dev-authored) from a read-only investigation; **dev-reviewed 2026-10-08**
(section below). Android (TV + phone) player and the backend's readers of `playback_qoe` (308, 309).

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

## Dev review (2026-10-08, against `main` `fdd48f10`)

Read against `RaviloPlayerAndroid.kt` (the `qoe*` fields, `qoeListener`, `load()`, `releaseEngine()`,
`restartPreferringExtensions()`, `qoeSnapshot()`), `PlayerStore.kt` (`startSession`, `postQoeNow`), `PlayerScreen.kt`
(`advanceNext`, the next-up trigger, `resolvedNextEpisodeId`), `PlaybackQoe.sq`, `PlaybackService.recordQoe`,
`VideoLadder.measuredThroughput`, `TvRoutes.kt` (the admin's `recentQuality`), the receiver's QoE in `Receiver.kt`, the
desktop players (`RaviloPlayerDesktop.kt`, `MacPlayer.kt`), and Media3 1.8.0 (`libs.versions.toml`). The diagnosis
holds; the preload needs one backend change the spec does not name yet. Twelve items, two for the owner.

1. **The diagnosis holds, and it is wider than rebuffers.** `load()` resets only R218's per-item fields
   (`_hasRenderedFirstFrame`, `_isBuffering`, `_playbackFailed`, `_isSeeking`) and 308's variant; every `qoe*` counter
   and `qoeFirstFrameRendered` survive. `PlaybackQoe.sq` writes `INSERT OR REPLACE` per (device, item), so each item's
   row holds the running totals. The same carry-over hits `dropped_frames`, `subtitle_load_errors`, the recovery
   counters and `bandwidth_estimate_bps` (the shared meter's last value). **FR-R381-1 must cover every counter in
   `PlayerQoeSnapshot`, not only rebuffers.**
2. **Two more paths count a start as a stall:** `releaseEngine()` (R292's return from the background) and
   `restartPreferringExtensions()` (R379) rebuild the engine but leave `qoeFirstFrameRendered` true, so the rebuilt
   engine's first `STATE_BUFFERING` is counted. FR-R381-2 lists both; the build must reset the flag on every engine
   build, not only in `load()`.
3. **What an "item" is.** A restream (R195/R291 subtitle or audio switch, R284) calls `load()` again for the **same**
   item. Counts must be per Jellyfin item id and survive a restream; the first-frame flag is per load. Without this
   rule FR-R381-1's "reset in `load()`" would wipe a real stall the moment the viewer switches subtitles.
4. **Build it as a pure state machine** in `commonMain` (`QoeCounter`: events in — load, first frame, buffering, ready,
   seek, track switch, variant change, engine rebuild, decoder restart — counts and stall events out), fed by the
   Android listener, the desktop players and later the web. FR-R381-6's test 1 then runs in `commonTest` with no
   Media3 at all.
5. **The wire is additive** (never delete a DTO field): `PlaybackQoeReport` gains `per_item` (bool), `stalls` (≤ 20
   events: seconds after first frame, position, buffered ms, phase, delivery, variant) and the optional session
   totals. The backend stores `per_item` and the events (one migration, numbered at build time with 309's and 310's).
   Rows without `per_item` are the legacy rows of FR-R381-4.
6. **Who reads the stall counts today:** not 308. `measuredThroughput` reads only `bandwidth_estimate_bps` and
   `direct_play`. The harm starts with 309's record (`stalled_bps`, FR-309-8). So **R381's counting half must ship
   before 309's record**, and the admin's device line (`TvRoutes.kt` `recentQuality`) must stop showing legacy counts.
7. **The other players.** The receiver resets its counters per load (`Receiver.kt`), fine. The desktop players report
   mpv's/AVPlayer's `stalls` per player instance; whether that instance survives an episode change has to be checked,
   then fed through the same `QoeCounter`.
8. **The preload cannot use today's start call.** The next episode starts through `onNavigateToEpisode` → a new
   `startSession` → `POST` start, which (a) reports `/Sessions/Playing` to Jellyfin, (b) moves the R368 session to the
   next item, (c) starts the tracker and its heartbeat, (d) for a transcode starts the Jellyfin job and the composed
   master. Doing that at the credits would make Jellyfin switch *now playing* while the current episode still plays
   (Jellyfin then stops the current one with the session's position, another source of 312-style writes), move
   Continue Watching early, and leave a started next episode behind when the viewer stops at the credits. **Add
   FR-R381-7: a `prepare` call** that returns the next item's ticket with none of those side effects (same ACL and
   kids gating as a start, valid ≤ 5 min, nothing reported to Jellyfin), and the real start's side effects run only
   when the item actually begins (a `begin` with the prepared ticket, or the first progress report).
9. **Media3's mechanism.** The player is built around one `MediaItem` per `load()` (per-item subtitles, R291's master
   per ticket, R218 state, the screen per episode), so turning it into a playlist (`addMediaItem` +
   `setPreloadConfiguration`) would touch all of that. Lean: **prefetch the next file's first ~15 s into Media3's
   cache** (`CacheDataSource` over the prepared direct-play URL) at the credits, and start the next item from that
   cache on advance; Media3's `DefaultPreloadManager` (unstable API in 1.8) is the later upgrade if a prefetch is not
   enough. The cache is dropped on `releaseEngine()`, on Back and after 5 min unused.
10. **Transcoded next episodes.** For a direct play the prefetch is cheap. For a transcode, "preload" means starting
    the encode early: that is 309's early encode / 313's job, adopted on advance. Until one of those exists, a
    transcoded next episode gets no preload (see the owner question). R291's audio renditions are never warmed for a
    preload (R291 decision 2).
11. **Edge rules for the build:** the trigger is the next-up card's own trigger (trusted credits marker or the
    heuristic); the next id is `resolvedNextEpisodeId` (so R343's shuffle and R375's next follow automatically); a
    multi-episode file (149) whose next part is in the same file needs no preload; a seek back out of the credits, Back,
    a stop or a background release discards the preload; a viewer who stops at the credits leaves nothing started.
12. **Tests are feasible, with one dependency.** `media3-test-utils` and `media3-test-utils-robolectric` (1.8.0) are
    not in the build yet; Robolectric 4.14.1 is. Add them for FR-R381-6's test 4. Add backend tests that `prepare`
    sends nothing to a fake Jellyfin and that `begin` sends exactly the start report.

**Build order:** R381a — the `QoeCounter`, per-item counts, the wire fields, the backend's legacy handling and the admin
line (backend half reaches every app at once; the counting half needs the app release). R381b — `prepare`/`begin` and
the direct-play prefetch. R381c — FR-R381-5's fixes after a week of corrected numbers.

**For the owner:**
- **Q1 — preload when "play next automatically" is off?** (a) Yes, prefetch anyway and discard if not used (lean: the
  next-up card still offers *Next*, and a prefetch of a direct play costs a few MB); (b) only when autoplay is on.
- **Q2 — a transcoded next episode, before 309's early encode or 313 exists?** (a) No preload for it until then (lean);
  (b) start Jellyfin's transcode at the credits (hides the 8–38 s cold start, costs a GPU encode for every binge
  episode, and is wasted when the viewer stops).

## Decided by the owner (2026-10-08, after the dev review)

1. **Preload the next episode whether or not autoplay is on** (Q1): a few MB, thrown away if unused.
2. **No preload for a next episode that needs a transcode until 309's early encode or 313's encoder exists** (Q2);
   only direct-play episodes preload until then.

## Build notes (2026-10-08)

**R381a — a stall is counted once, per item (FR-R381-1…-4, -6).**
- `QoeCounter` (`ravilo-ui` commonMain `seams/QoeCounter.kt`): a pure state machine (events in: item, load, engine
  rebuild, first frame, seek, track switch, variant switch, recovery, buffering, ready, interrupted; counts out). An
  item is a Jellyfin item (`beginItem(key)`: a new key resets the item's counts, the same key — a restream — keeps them);
  a load's first wait is its `start`; a wait after the load's first frame is a stall unless a seek, track switch, variant
  switch (≤ 3 s before), engine rebuild (R292's return, R379's restart) or recovery explains it — each counted by name in
  `waits`. A pause while waiting drops the wait. Each stall: ms after the item's first frame, position, buffered ahead,
  duration, phase (`start` < 10 s, else `mid`), variant; newest 20 kept. Session totals kept apart.
- Android (`RaviloPlayerAndroid.kt`): the listener feeds the counter (under one lock); **every** per-item counter
  (dropped frames, bandwidth estimate, subtitle errors, recoveries, returns, restores, variant switches) resets on a new
  item (`beginQoeItem`, called by `PlayerScreen` before every `load`, and by the Live TV player per channel). The video
  decoder's name is the engine's and is kept (a reused engine has no new init event — found live). Track switches,
  engine rebuilds and R379's restart tell the counter.
- The report (`PlaybackQoeReport`, additive): `per_item`, `stalls`, `waits`, `session_rebuffer_count/_ms`; `PlayerStore`
  also posts R292's recovery/return counters, which it carried but had never posted.
- The backend: migration **72** (`per_item`, `stalls_json`, `waits_json`, `session_rebuffer_count`, `_ms`; existing rows
  get `per_item = 0`, nothing deleted). `QoeSummary` reports a legacy row's rebuffers as 0 (kept as
  `legacy_rebuffer_count` for the record), so the admin's device line and Activity's quality card stop showing them,
  with no frontend change. `measuredThroughput` never read rebuffers; 309's record must read `per_item` rows only.
- The desktop players and the web report `per_item = false` (not fed through the counter yet; the web has no counters).

**R381b — prepare, then prefetch (FR-R381-7, owner decisions).**
- `POST /api/tv/playback/prepare` → `PreparedStream` (`PlaybackService.preparePlayback`): the same gate and negotiation
  as a start (requireVisible, PlaybackInfo with the device's capabilities and 308's budget) and **none** of its side
  effects — no `/Sessions/Playing`, no tracker, no session, no plan, no encode, no composed master; valid 5 min. A
  transcode answers `direct_play = false`, no URL (owner: no transcode preload until 309/313). The real start is the
  ordinary `startPlayback` when the episode begins (the dev review's "begin").
- Client: at the credits marker (or 60 s before the end without a trusted one), autoplay on or off (owner), the player
  screen asks `PlayerStore.prepareNext(next)`; a direct play from 0:00 is prefetched by `NextPrefetch` (Android): its
  first 16 MB and last 4 MB (where a Matroska file keeps its Cues) into a 64 MB cache of its own. `load()` reads that
  item from the cache when its URL is the prepared one (read-only cache source, `FLAG_IGNORE_CACHE_ON_ERROR`); any other
  stream drops the prefetch; a seek back out of the credits, a background release, and 5 minutes unused drop it too.
  A preload that fails or is late changes nothing. An older server without the route answers 404 → no preload.
- Desktop and web: `NextPrefetch` is a no-op.

**Tests.** `QoeCounterTest` (10, commonTest: one item; a three-item binge; a seek; a mid-play stall with phase and
position; a start-phase stall; rebuild/recovery/track/variant named, none a stall; item 2's stall absent from item 3;
a restream keeps the item's stall; a pause drops the wait; 20 kept). `PlaybackQoeStorePerItemTest` (2: a legacy row's
rebuffers unread and unbadged; a per-item row's stalls/waits/session totals). `PlaybackPrepareIntegrationTest` (2, a
fake Jellyfin over loopback: `prepare` sends no `/Sessions/Playing`, no release, no user data and starts no tracker, the
start then reports exactly once; a transcode-only item is not prepared). `MusicEditionsStoreTest`'s rewind drops 72's
columns. Full runs: backend `:linuxX64Test` 1 219 tests (the one failure, that rewind, fixed and re-run green);
`:ravilo-ui:testDebugUnitTest` 693 green; `:ravilo-android:assembleDebug`, desktop and wasm compile.
Not built: FR-R381-6 test 4 (a Media3 `TestPlayerRunHelper` integration test; `media3-test-utils` not added — the pure
counter covers the event sequences).

**Live (Stue TV, debug build `dev.jellystructure.ravilo.debug` against the dev backend, 2026-10-08 13:17–13:19).** A
never-watched series: S01E01 played ~33 s, then the remote's Next key → S01E02 on the same engine (the binge case) for
~45 s, then Back. Both direct play. `playback_qoe`: S01E02 **0 rebuffers** (the old counting reported the next item's
start as one in 91 of Stue TV's 100 new events), S01E01 0. The prefetch path was not exercised live: the deployed
backend has no `prepare` route yet (the app then gets 404 and preloads nothing, as designed). Found and fixed: the
decoder name was reset per item (above). Cleanup: both episodes marked unplayed through `/api/tv/mark` (Jellyfin's
`MarkUnplayed` clears play count, position and last-played date — verified in Jellyfin's database; the series left
Continue Watching again); the TV was put back to its home screen and asleep. An early install of a build made while two
Gradle runs overlapped crashed at launch (`ClassNotFoundException`); a clean rebuild fixed it — nothing wrong in the code.

**Open:** R381c after a week of per-item numbers (needs the app release); the desktop players through `QoeCounter`;
309's record must read `per_item` rows only; migration 72's number is to be checked against whatever lands on `main`
first (309a0/310 may take numbers).
