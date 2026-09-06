# Phase 195 — The failed-ingest retry set re-fires itself into saturation and can never drain

> Live report, 2026-09-06: *"I just triggered a normal scan in jellystructure but it did not find the
> new episode of Fjollerne."* And: *"whenever a new episode is available, we should try to make it
> available in jellystructure as soon as possible as well."*

## Status
✓ Built 2026-09-06 (FR-195-1 … FR-195-6). Not dev-reviewed, not live-verified — the running backend
still has the old behaviour, so the 117-entry production backlog is untouched and Fjollerne S11E08 is still
missing until this is deployed. Root-caused against production logs and the production database.
Companion phase: **196** (the freshness clock that keeps the periodic scan away from the same items).
Neither alone is sufficient — see *Why both phases are needed*.

**Implementation notes:**
- FR-195-2 landed first because it is the actual cause: a new `fileProbeGate` semaphore
  (`scan_workers × 2`, clamped 2–8) wraps every `FfprobeRunner.probe`/`diagnose`/`chapters` call in
  both `scanSeries` and `syncSeriesEpisodes`. Deliberately **smaller** than ProcessGate's 12 background
  permits rather than equal: what matters is that scan-originated waiters inside ProcessGate stay under
  its permit count, so its 30 s deadline is only ever reached by genuinely slow work. Waiting on our own
  gate is unbounded and free; waiting in ProcessGate costs a timeout, a failed ingest and a dirty-set
  entry. Kept separate from Phase 183's `episodeFanoutGate` rather than widening that one — the block it
  guards already acquires `episodeFanoutGate` internally for TMDB, and a coroutine holding one permit of
  a non-reentrant semaphore while waiting for a second is a deadlock under contention, not a bound.
- FR-195-3: `ingestOnce` returns a three-state `IngestOutcome` (OK / BUSY / FAILED) instead of a
  `Boolean`. `BUSY` — a `ProcessGate`/`OutboundHttp` `GateTimeoutException` — neither consumes one of the
  two attempts nor marks the item dirty. This is the same distinction Phase 194 draws in `isTokenValid`,
  in a different file: an unavailable dependency and a bad input are not the same fact.
- FR-195-4: `dirty_item` gained `attempt_count` + `next_attempt_at` (`41.sqm`). `markDirty` was a bare
  `INSERT OR IGNORE`, which made a repeat failure a **silent no-op** — the counter could never grow and
  no backoff could ever exist. It is now UPDATE-then-INSERT-OR-IGNORE in one transaction (the dialect is
  `sqlite_3_18`, which has no UPSERT), with the doubling computed in SQL from the row's own
  `attempt_count` so two concurrent failures for one id can't race a read-then-write. 30 min base,
  clamped at 24 h; `created_at` never moves, so oldest-first still means longest-outstanding.
- FR-195-1/5: the drain now calls `dirtyItemStore.due(now, scan_workers × 2)` and runs *after* the
  sweep's own enqueues, so a genuinely new episode never queues behind the backlog. The log line reports
  attempted / due / total (`Retrying 8 of 117 due previously-failed ingest(s) (117 outstanding in
  total)`) per Phase 183's no-silent-caps rule.
- FR-195-6: `IngestStatus` gained `oldest_retry_at` and `due_retry_count` alongside the existing count.
  **Not built:** the `app/activity.html` surface — the API carries the data, the page was left alone.
- New `DirtyItemBackoffTest` (7 cases against a real database, since both properties live in SQL):
  the increment, the doubling, the clamp, the `created_at` stability, the bounded oldest-first `due`,
  backoff exclusion, and clear-resets-the-backoff. `compileKotlinLinuxX64` clean,
  `verifyCommonMainJellystructureDbMigration` green, `linuxX64Test` 221/221.

## What happened

`Fjollerne.S11E08.DANiSH.1080p.WEB.h264-STROMPEBUKSER [UNRAR].mkv` landed on disk at **06:14 local**,
2026-09-06. Jellyfin ingested it correctly — `GET /Shows/{id}/Episodes?season=11` returns 8 items with
`IndexNumber = 8`. On disk: **102** video files under `/mnt/series/jellyfin/Fjollerne`. In jellystructure:
**101** episodes, S11 stopping at E07.

Detection was never the problem. Phase 181's set-difference sweep found it on the very next cycle:

```
[INFO] Library sweep: 8757 Jellyfin items scanned, 4 missing, 22 stale
[INFO] Realtime ingest: 8b202d3c7dc4d006ff33834abfc56e34      ← Fjollerne
```

and then, every single time, for six consecutive attempts across four hours:

```
[WARN] Realtime ingest error for jellyfinId=8b202d3c…: ProcessGate saturated (background, 30000ms)
[WARN] Realtime ingest failed twice for jellyfinId=8b202d3c… — recorded for retry on the next scan cycle
```

The ` [UNRAR]` suffix in the filename is **not** the cause and should not be treated as one:
`findEpisodeFiles` (`Scanner.kt:1366`) filters on extension and a leading dot only, and a `[UNRAR]`
file for *Muldvarpen* had an episode NFO written successfully the previous night
(`2026-09-05T23:28:53Z`). The file never got as far as being parsed.

## Root cause

Three multiplicative facts.

**1. The retry set is fired as one unbounded burst.** `PipelineEngine.kt:249-255`:

```kotlin
val outstanding = dirtyItemStore.all()
if (outstanding.isNotEmpty()) {
    Logger.info("Retrying ${outstanding.size} previously-failed ingest(s)", "ingest")
    outstanding.forEach { realtimeIngest.enqueue(it) }
}
```

Every entry is enqueued at once, with no pacing and no cap. Production has **117** of them.

**2. Each ingest is itself an unbounded per-file fan-out.** `enqueue` launches into `queueScope`
(`RealtimeIngestService.kt:59-61`), whose `limitedParallelism(2)` bounds *CPU threads*, not suspended
coroutines — 117 ingests all become live concurrently. Each then reaches `scanSeries`, which dispatches
one coroutine per episode file (`Scanner.kt:583-590`):

```kotlin
val episodes = coroutineScope {
    filesToProbe.map { file ->
        async {
            val tracks = FfprobeRunner.probe(file)
```

Its own comment argues this is safe because "real concurrency stays bounded by the existing
ProcessGate". That is true of *concurrency* and false of *queueing*: Fjollerne alone dispatches 102
ffprobe waiters. This is the same unbounded ffprobe fan-out recorded as an open follow-up after
Phase 183 — 183 bounded the TMDB fetch inside this very loop (`episodeFanoutGate`) and left the
ffprobe call beside it ungoverned.

**3. The gate they all queue on is small and deadlined.** `ProcessGate.kt:42-47` gives background work
`MAX_CONCURRENT (16) − INTERACTIVE_RESERVE (4)` = **12 permits**, with a **30 s** acquire timeout.

So: 117 series × their whole episode lists, thousands of waiters, 12 permits, 30 seconds. Essentially
every waiter times out. `ingestOnce` catches the `GateTimeoutException`
(`RealtimeIngestService.kt:119-123`), returns false, waits 60 s, fails identically, and then
re-registers the id (`:111`).

### The loop closes

The failure mode is not that some items fail — it is that **failure is the mechanism that schedules
the next failure**, at the same width, forever.

Production evidence, 2026-09-06:

```
dirty_item rows:            117   (reason: 'ingest failed twice', 100 %)
oldest entry:               2026-09-05 20:11:33   — never drained since
"Retrying N previously-failed": N = 120 → 119 (×8) → 118 (×2) → 117 (×2)
ProcessGate background saturations:  1 243 on 2026-09-05,  1 994 on 2026-09-06
```

Twelve hours of continuous retrying moved the backlog by **three items**. The set is not converging;
it is being re-created every 30 minutes by its own retry.

A larger library makes it strictly worse: the more episodes a series has, the more waiters it adds, and
the more likely every *other* series in the burst is to time out. Fjollerne — at 102 files, one of the
largest — is both a major cause of the saturation and one of its victims.

### It also defeats the manual scan

The user's manual trigger at 06:36 local behaved exactly like the scheduled ones: sweep → enqueue
Fjollerne → `ProcessGate saturated` at 06:37:01 → retry → saturated again at 06:38:31 → re-marked dirty.
Pressing **Scan** cannot fix an item in this state, which is precisely the experience reported.

## Problem, stated plainly

A retry policy that reproduces the conditions of the original failure is not a retry policy. The system
correctly detects a new episode, correctly decides to ingest it, and then destroys its own ability to
do so by asking for everything at once — and it does this on a schedule.

## Goal

A new episode reaches jellystructure within minutes of Jellyfin seeing it, and a backlog of failed
ingests shrinks monotonically instead of re-arming itself.

## Requirements

### FR-195-1 — The retry set drains at a bounded rate

`PipelineEngine.kt:249-255` stops enqueuing the whole set. It processes the outstanding ids through a
bounded worker pool (reuse `runPipelineStepPool`'s existing shape and `pipelineStepConcurrency`, which
already derives width from `scan_workers`), oldest `created_at` first so no entry can be starved by
newer arrivals.

The log line must say what was actually attempted this cycle, not the backlog size:
`Retrying {n} of {total} previously-failed ingest(s)`. A silent cap reads as "we tried everything" when
it did not — the same no-silent-caps discipline Phase 183 applied to its own run summary.

### FR-195-2 — Bound the per-episode ffprobe fan-out

`scanSeries`'s `filesToProbe.map { async { … } }` (`Scanner.kt:583`) acquires a semaphore per file
before dispatching, exactly as Phase 183 already does for the TMDB call inside the same loop
(`episodeFanoutGate`). Width derives from `scan_workers`, as 183's does.

This is the change that stops one large series from filling the queue on its own. `syncSeriesEpisodes`
(`Scanner.kt:917`) has the identical ungoverned dispatch and gets the identical treatment — it is the
`POST /api/media/{id}/sync` path already flagged as self-saturating the *interactive* reserve after
Phase 183, and the two are one bug in two places.

### FR-195-3 — A gate timeout is a deferral, not a failure

`ingestOnce` currently flattens every cause into `false` (`RealtimeIngestService.kt:119-123`) — a
`GateTimeoutException` (server busy, try later) is recorded identically to "Jellyfin has no such item"
(will never succeed).

Distinguish them. A gate timeout must not consume one of the two attempts and must not immediately
re-mark the item dirty; it re-queues with backoff, because the only thing wrong was timing. Only a
genuine ingest failure exhausts the retries and lands in `dirty_item`.

This is the same lesson as Phase 194's `isTokenValid`, in a different file: an unavailable dependency
and a bad input are not the same fact, and collapsing them into one boolean loses the distinction the
retry policy needs.

### FR-195-4 — Backoff per item, so a permanent failure stops costing a burst

An id that has failed N consecutive cycles is retried on a widening interval (e.g. 1, 2, 4, 8 cycles,
capped at a day) rather than every cycle forever. `dirty_item` gains `attempt_count` and
`next_attempt_at`.

Today a genuinely un-ingestable item is retried every 30 minutes indefinitely, and its share of the
burst is paid by every healthy item behind it.

### FR-195-5 — A new episode does not wait for the backlog

An ingest triggered by a live event — the Jellyfin webhook, or the sweep finding an id
jellystructure has never held — must not queue behind a 117-item retry burst. Give the retry drain
strictly lower priority than a fresh trigger, so latency for the case the user actually asked about
("as soon as possible") does not degrade with backlog size.

The simplest sound form: run the FR-195-1 drain only after the cycle's fresh ingests have been
enqueued, and never let it occupy more than a fraction of the background gate.

### FR-195-6 — The backlog is visible before someone notices a missing episode

117 items have been failing for 12+ hours with no surface other than a WARN line repeated 1 994 times.
`GET /api/ingest-status` (Phase 181 FR-181-4.2's `lastSuccessfulIngestAt` already lives there) reports
the outstanding count, the oldest entry's age, and the count of consecutive cycles in which the set
failed to shrink. Surface it on `app/activity.html` alongside the Phase 183 Outbound pacing card.

A backlog that only grows is the signal; the individual WARN lines are not.

## Non-goals

- No change to `MAX_CONCURRENT`, `INTERACTIVE_RESERVE`, or either acquire timeout. The gate is sized
  correctly; the bug is what is thrown at it.
- No change to Phase 181's sweep or its diff logic. It worked — it found the episode on the first
  cycle. This phase is entirely about what happens after `enqueue`.
- No change to the freshness filter or `last_checked` — that is **196**.
- No new ingest path for a standalone episode. A new episode is still processed as a re-scan of its
  series, per `computeLibraryDiff`'s existing contract.
- Nothing about the ` [UNRAR]` filename convention. It is not implicated.

## Acceptance

1. With 117 entries in `dirty_item`, run a scan cycle: the log reports a bounded attempt count, and the
   outstanding count **decreases**. Repeat cycles drive it to zero.
2. During that drain, `ProcessGate saturated (background, …)` does not appear.
3. Drop a new episode into a series while the backlog is non-empty: it is ingested in the same cycle,
   not after the backlog clears.
4. `POST /api/media/{id}/sync` on a 500-episode series does not exhaust the interactive reserve — the
   admin UI stays responsive throughout (the Phase 183 follow-up's own reproduction).
5. An id Jellyfin genuinely does not have is retried on a widening interval, not every 30 minutes, and
   its `attempt_count` is visible.
6. Kill Jellyfin mid-drain: affected items are deferred, not consumed — `attempt_count` does not
   increment for a gate timeout.
7. The ingest-status surface reports outstanding count and oldest-entry age, and a stuck backlog is
   visible without reading logs.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineEngine.kt:249-255` — the unbounded
  `outstanding.forEach { enqueue(it) }` burst.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/RealtimeIngestService.kt:59-61` — `queueScope` and
  its `limitedParallelism(2)` (threads, not coroutines); `:97-117` — `enqueue`, the two attempts, the
  60 s gap and `markDirty`; `:119-123` — `ingestOnce`, which flattens every cause to `false`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/Scanner.kt:583-590` — the per-episode `async`
  dispatch and the comment asserting the gate makes it safe; `:917` — `syncSeriesEpisodes`, same shape;
  `:1366-1386` — `findEpisodeFiles`, which does not exclude `[UNRAR]`.
- `src/linuxX64Main/kotlin/dev/jellystructure/ops/ProcessGate.kt:42-47` — 16 permits, 4 reserved,
  30 s background acquire timeout.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/DirtyItemStore.kt` — the store to extend for
  FR-195-4.
- `specs/requirements/phase-183-outbound-pacing.md` — `episodeFanoutGate`, the pattern FR-195-2 copies,
  and the recorded follow-up about the ungoverned ffprobe beside it.
- Production evidence 2026-09-06: `dirty_item` (117 rows, oldest 2026-09-05 20:11:33, all
  `ingest failed twice`); `docker logs jellystructure` — 1 994 background saturations, `Retrying`
  counts 120→117 over 12 hours.

## Open questions

1. **What put 117 items into the set on 2026-09-05 20:11 in the first place?** Every entry now reads
   `ingest failed twice` from gate saturation, which is self-sustaining — but something seeded it.
   Worth identifying before assuming the fix drains it, since a seeding event that recurs would refill
   the set at full width.
2. **Is `limitedParallelism(2)` doing anything useful?** It bounds CPU threads for work that is almost
   entirely suspended on I/O and gates. If FR-195-1 introduces a real bounded pool, this may be
   redundant or actively misleading and should be removed rather than left as a second, weaker limiter.
3. **Should a series ingest be incremental?** A new episode currently re-probes all 102 files. FR-195-2
   makes that survivable, but the honest fix for "as soon as possible" might be probing only files
   whose path is new or whose mtime/size changed. That is a larger change with a real risk of
   re-introducing the Phase 188 class of data loss, so it is deliberately not required here — but it is
   the difference between an ingest that takes seconds and one that takes minutes.
4. `dirty_item.reason` is free text and currently always the same string. If FR-195-3 distinguishes
   causes, the reason should become an enum so the ingest-status surface can say *why* a backlog is
   stuck, not just that it is.
