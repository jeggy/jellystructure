# Phase R184 — Ravilo: auto-advance can start the next episode minutes into playback (bug fix, FR-RV-POS1)

> A viewer reported that when Ravilo auto-plays the next episode, it sometimes opens several minutes
> in instead of at 0:00. Root-caused by tracing every place playback start position is set; fix is
> spec'd here before implementation, per this repo's "spec before fix" convention. Distinct from
> **554c7b4** (duplicate-episode-file id collisions causing the auto-advance loop/no-op) — that fix
> does not touch position state at all. Interacts with three other undocumented, already-shipped
> changes to the same player-session lifecycle: **0fadfc0** ("end the playback session when the app
> is backgrounded", which introduced the exploitable code path below), **b877813** (per-(device,item)
> `PlaybackTracker` on the watchdog) and **590b9ff** (Continue Watching cache invalidation on stop) —
> none of them own a spec either; this phase is the first to document this corner of the player.

**Status:** Implemented, not yet on-device verified (see Dev-review addendum).

## Bug report
"Sometimes the auto-play is auto-advancing in Ravilo. So when next episode starts it starts already
some minutes in the episode, instead of the start." (2026-07-30, Android TV.)

## Investigation
All auto-advance paths funnel through `advanceNext()` (`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt:369-385`), which calls `onNavigateToEpisode(nextId)`.
`RaviloApp.kt:822-877` reuses the **same** `PlayerScreen` composable across every episode of a binge
(only `dest.itemId` changes) but creates a **new** `PlayerStore` keyed on `remember(dest.itemId)`
(`RaviloApp.kt:830`).

The start position itself is resolved correctly per item: `PlaybackService.startPlayback`
(`src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt:184-247`) does a **live**,
per-`jellyfinId` fetch of `itemDetail?.userData?.playbackPositionTicks` (line 199) — no caching, no
cross-item bug there, and the client never sends an explicit start position
(`PlayerStore.startSession`, `PlayerStore.kt:52-113`).

The bug is upstream of that fetch — in how the *previous* episode's tail-end position gets written
into the *new* episode's own Jellyfin resume field before it's ever read back:

- `positionMs`/`durationMs` are declared once for the whole `PlayerScreen`, not per episode
  (`PlayerScreen.kt:234-236`), updated by a 500 ms poll loop (`PlayerScreen.kt:595-596`).
- `LaunchedEffect(itemId)` (`PlayerScreen.kt:555-564`), which fires on every episode transition,
  resets `nextUpDismissed`/`nextUpVisible`/`countdown` but **not** `positionMs`/`durationMs`. For the
  window between `itemId` flipping to the next episode and the new ticket's `player.load()` landing,
  these still hold the **outgoing** episode's near-the-end values.
- `PlayerLifecycleEffect` (`PlayerScreen.kt:812-816`, wired up by 0fadfc0) calls
  `store.stopSession(positionMs, durationMs)` on `Lifecycle.Event.ON_STOP`
  (`ravilo-ui/src/androidMain/.../PlayerLifecycleEffect.kt:11-58`). `store` is already the **new**
  episode's `PlayerStore` right after `advanceNext()`'s `replaceTop` swap, but `positionMs` can still
  be the **outgoing** episode's value if an Android lifecycle blip (screensaver, HDMI-CEC standby, a
  system overlay, a memory-pressure pause — all plausible on TV hardware) lands in that window.
- `PlayerStore.stopSession` (`PlayerStore.kt:130-145`) posts `apiClient.stopPlayback(itemId, positionMs)`
  with the **correct** (new) `itemId` but the **stale** `positionMs`. `PlaybackService.stopPlayback`
  (`PlaybackService.kt:265-274`) forwards it verbatim to Jellyfin
  (`JellyfinClient.kt:378-393`, `stopPlaybackSession(..., positionMs * TICKS_PER_MS, ...)`) — no sanity
  check anywhere in the pipeline.
- The very next ticket fetch for that episode (the `onForeground = { armSession(currentItemId) }`
  re-arm on `ON_START`, `PlayerScreen.kt:816`, or the original in-flight `startSession` call) reads
  that corrupted value straight back via `PlaybackService.kt:199` and hands it to ExoPlayer as
  `startPositionMs`.

This explains the "sometimes": it only reproduces when a lifecycle blip coincides with the few hundred
milliseconds of the auto-advance transition, which is intermittent by nature.

## Root cause
`positionMs`/`durationMs` are screen-scoped state reused across episodes, but `PlayerLifecycleEffect`'s
`onBackground` treats them as if they always describe the store's current item. `advanceNext()` swaps
the store without ever invalidating that state, so a lifecycle stop firing mid-transition writes one
episode's playback tail onto a different episode's resume position with no guard at any layer.

## Requirements

### FR-RV-POS1-1 — Reset episode-scoped position state on advance
`positionMs` and `durationMs` (`PlayerScreen.kt:234-236`) must not survive an `itemId` change. Reset
both to `0L` inside the existing `LaunchedEffect(itemId)` block (`PlayerScreen.kt:555-564`), alongside
the `nextUpDismissed`/`nextUpVisible`/`countdown` resets already there, so no stale value is even
observable during the transition window.

### FR-RV-POS1-2 — A lifecycle stop may only report a position that is known-fresh for the current item
Track which `itemId` the current `positionMs` value was actually observed for (set at the same site as
the poll-loop update, `PlayerScreen.kt:595-596`, and at `player.load()`). `PlayerLifecycleEffect`'s
`onBackground` (`PlayerScreen.kt:812-816`) must not pass `positionMs` to `store.stopSession(...)` unless
that tracked item matches the store's own current item — if it doesn't (i.e. no fresh position has been
observed yet for this episode), pass `0` instead of the stale value. This is the belt-and-suspenders
layer: FR-RV-POS1-1 closes the window in the common case, this closes it even if a lifecycle event
lands before the reset/first poll tick has run.

### FR-RV-POS1-3 — Regression coverage
A test (or, if `PlayerScreen`'s Compose state isn't practically unit-testable, a documented manual
repro step) that simulates an `ON_STOP` landing immediately after `advanceNext()` swaps to a new
episode and asserts the resulting `stopSession` call carries position `0` (or the new episode's own
freshly-observed position), never the outgoing episode's.

## Invariants
- **A new episode's own resume position is only ever written by that episode's own playback.** No
  code path may report a position sourced from a different `itemId`, whether via poll state, lifecycle
  stop, or any future consumer of `positionMs`/`durationMs`.
- **Auto-advance always starts at 0:00 unless the viewer has genuinely already watched into the next
  episode** (e.g. re-entering a binge Jellyfin already has partial progress for) — this phase must not
  change that legitimate resume case.

## Out of scope
- The synthetic-id bug found during investigation: `Scanner.kt:327` keys Jellyfin episode lookup only
  by `IndexNumber`, not `IndexNumberEnd`, so parts 2/3 of a multi-episode file (`partIndex`/`partCount`,
  `Scanner.kt:358-408`) get no `jellyfinId` and a synthetic fallback id (`DetailService.kt:103`) that
  playstate-overlay lookups filter out (`DetailService.kt:171`, ids starting with `/`) — so
  `SeriesDetailScreen.kt:174-177`'s `groupEntryPoint` can pick an unplayable synthetic id as "next
  episode." This produces a broken/404 stream, not a wrong start offset, so it doesn't match this bug
  report; it needs its own spec if/when it's reported.
- Retroactively spec'ing 0fadfc0 / b877813 / 590b9ff in full — this phase only documents the slice of
  their behavior that's load-bearing for this fix (the `onBackground`/`onForeground` contract). A
  follow-up phase could give the background/foreground session lifecycle its own proper spec.

## Dev-review addendum (2026-07-30 — implementation notes)

1. **FR-RV-POS1-1 and FR-RV-POS1-2 turned out to be one mechanism, not two.** The itemId-reset alone
   (`positionMs = 0L` in `LaunchedEffect(itemId)`) only holds until the *next* poll tick, ~500 ms later —
   and that tick still unconditionally overwrote `positionMs`/`durationMs` from `player.positionMs`, which
   still reflects the outgoing stream until `player.load()` swaps it in. So the reset alone doesn't close
   the window; the poll loop had to stop being unconditional. Implemented as: the poll loop's
   `positionMs`/`durationMs` assignment is now gated on the same `playerLoadedForCurrentItem` boolean the
   next-up/end-of-stream checks already used (`PlayerScreen.kt`), and a new `positionKnownForItemId` state
   var is set alongside it — so the values simply never advance past the itemId-reset zero until the
   player is actually loaded for the current item. `PlayerLifecycleEffect`'s `onBackground` reads that
   flag and substitutes `0L` if it doesn't match `currentItemId`, which in practice is now a pure
   defense-in-depth backstop (the gated poll loop already prevents `positionMs` from ever holding stale
   data), covering the theoretical case of an `ON_STOP` landing before Compose has dispatched the
   `LaunchedEffect(itemId)` reset at all.
2. **FR-RV-POS1-3 (regression coverage) — no automated test.** `ravilo-ui` has no test source set at all
   (`ravilo-ui/build.gradle.kts` declares only `commonMain`/`androidMain`/`wasmJsMain`, no `commonTest`,
   no Compose UI testing dependency) — adding that infrastructure for one test was judged disproportionate
   to this bug fix's scope. Falling back to the spec's documented allowance: manual repro is to auto-advance
   near the end of an episode while forcing an `ON_STOP` (Home button / recents) at the moment the credits
   card's countdown fires, then resume and confirm the new episode starts at 0:00, not near its own end.
   User-initiated on-device testing, per project convention — not run as part of this change.
3. Verified via compile check only, across every target that consumes `PlayerScreen.kt`:
   `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-web:compileKotlinWasmJs`,
   `:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin` all pass.

## Dev-review addendum (2026-08-02 — third root cause found, still reproducing)

The viewer reported the bug is still occurring (2026-08-02), specifically on a kids show they rewatch
very often. Re-investigated per this repo's "spec before fix" convention before touching code.

**FR-RV-POS1's fix (client-side race) still holds and is not the cause here** — traced
`PlayerScreen.kt`'s current state and confirmed the poll-loop gating (`playerLoadedForCurrentItem`,
`positionKnownForItemId`) and the `LaunchedEffect(itemId)` reset are both intact and correctly close the
window described above.

**554c7b4's fix (duplicate-episode id collisions causing the advance loop) is also not the cause** — that
fix stops a *retry loop*, it doesn't touch resume-position state.

**New root cause, upstream of both: `Scanner.kt`'s `(season, episode) → JellyfinEpisodeItem` lookup is
non-deterministic when Jellyfin holds two items for the same slot** (duplicate physical files, each
imported by Jellyfin separately — exactly the shape of library a frequently-rewatched, heavily-handled
kids show tends to accumulate). Both `scanSeries` (`Scanner.kt:328`, pre-fix) and `syncSeriesEpisodes`
(`Scanner.kt:662-663`, pre-fix) built this map with `jfEpsMeta.associateBy { (season, episode) }`.
`associateBy` keeps whichever duplicate is **last** in the list, and that list comes from
`GET /Shows/{id}/Episodes` with no `SortBy` parameter (`JellyfinClient.kt:444-456`) — Jellyfin's response
order for that endpoint is not documented as stable, so the "winning" duplicate can differ between scans
of the same show.

This matters because jellystructure's own `Episode.jellyfinId` is reassigned from this map on every scan
(`Scanner.kt:390`, `:718`). When the winner flips, an episode that previously pointed at Jellyfin item A
starts pointing at item B — a different physical file with its **own** `PlaybackPositionTicks` in
Jellyfin's database, entirely unrelated to what the viewer actually watched most recently. Since
`PlaybackService.startPlayback` fetches that position live per `jellyfinId`
(`PlaybackService.kt:230-233`) and nothing anywhere migrates position/watched state when a duplicate's
"primary" changes (checked `DuplicateEpisodes.kt`'s `withUniqueIds()` and both `PlaybackService.kt` and
`MediaStore.kt` — no such migration exists), the next auto-advance (or any playback of that episode) can
resume from whatever item B was last stopped at — "a few minutes in," matching the report exactly. A show
watched on a loop maximizes both preconditions: more scans (more chances for the API order to shift) and
more distinct stop-position history spread across whichever duplicate happened to be resolved at the time.

Note `DuplicateEpisodes.kt`'s own `PRIMARY_ORDER` comparator (which picks which *local file* is primary
once duplicates are recognized as such) is itself deterministic — sorted on local `path`, no mtime/order
dependence. The bug is entirely in the earlier, un-deduplicated `associateBy` join against Jellyfin's own
item list, before `DuplicateEpisodes` ever runs.

### FR-RV-POS1-4 — Season/episode → Jellyfin item lookup must be deterministic across scans
When Jellyfin holds more than one item for the same `(season, episode)`, the same one must win on every
scan, independent of API response order. Implemented as `Scanner.kt`'s new `bySeasonEpDeterministic()`:
groups by `(season, episode)` (order-independent) and picks `minByOrNull { it.path ?: it.id }` — the
same tiebreak field (`path`) `DuplicateEpisodes.PRIMARY_ORDER` already uses for local files, so the two
selections agree in spirit even though they operate on different data. Applied at both existing call
sites (`scanSeries`, `syncSeriesEpisodes`), replacing the bare `associateBy`.

**Out of scope for this addendum:** retroactively repairing an already-flipped episode's stale
`PlaybackPositionTicks` in Jellyfin for shows scanned before this fix — the next full watch/mark-watched
of the affected episode will naturally overwrite it. No migration was written to hunt down and fix
already-corrupted per-item ticks, since there's no reliable way to know which of the duplicate items'
positions is the "true" one after the fact. If this proves to matter in practice (report persists after
the next few rescans), a follow-up could have the admin's duplicate-episode triage flow offer a manual
"reset position" action.

**Verification:** `compileKotlinLinuxX64` passes. Not re-verified on-device (same constraint as the
original phase — per-project convention, on-device testing is user-initiated, not run as part of this
change).

## Source references
- Bug: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
  (`positionMs`/`durationMs` state, `LaunchedEffect(itemId)`, `PlayerLifecycleEffect` wiring).
- Lifecycle actual: `ravilo-ui/src/androidMain/.../PlayerLifecycleEffect.kt`.
- Store: `ravilo-ui/src/commonMain/.../PlayerStore.kt` (`startSession`, `stopSession`).
- Server: `src/linuxX64Main/kotlin/dev/jellystructure/tv/PlaybackService.kt` (`startPlayback`,
  `stopPlayback`), `auth/JellyfinClient.kt` (`getItemDetail`, `stopPlaybackSession`).
- Related: **554c7b4** (duplicate-episode id collisions — different bug, same `advanceNext()` area),
  **0fadfc0** / **b877813** / **590b9ff** (undocumented prior changes to this session lifecycle).
