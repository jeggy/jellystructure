# Phase 203 — a cold health cache must not hold the Dashboard hostage, and "not yet known" is not "zero"

> Live report, 2026-09-13, minutes after the v1.13 deploy: *"Why does it take long before the dashboard
> works after a restart?"* — with a screenshot of **Items needing attention** showing a bare `—` and the
> whole attention breakdown reading *Loading…*.
>
> Not the MKV row. **The whole breakdown.** One health check nobody asked to wait for was holding up
> every other issue type on the page.

## Status
✓ Built 2026-09-13. Audit-authored, not dev-reviewed, not deployed. Backend-only — no Ravilo
counterpart, no client change, and (FR-203-2 aside) no new admin UI. `compileKotlinLinuxX64` and
`compileTestKotlinLinuxX64` clean; not live-verified (the running backend still has the old
lazy-refresh-on-access behaviour until redeployed).

**Build notes:** `MkvHealthCache.brokenPathsOrNull()` replaces the old auto-refreshing `brokenPaths()` —
returns `null` on a cold cache, never triggers a sweep, never blocks (FR-203-1/2). `start()` (called once
from `Main.kt`, reusing `scanDispatcher`/`scanWorkers`) warms at boot and re-warms every 15 minutes for
the process's life (FR-203-3); `refresh()` gets a `Mutex.tryLock()` so a concurrent trigger — the boot
loop, the post-`scan_files` hook, or both landing together — skips rather than queues or overlaps
(FR-203-4). The sweep itself moved into `MkvLayoutAudit.brokenParallel`, fanning file opens across a
`Semaphore`-bounded set of coroutines on the scan dispatcher instead of one path at a time on the
calling thread (FR-203-5). `sweptAt()` is now surfaced on `/api/health` as `mkv_health_swept_at`, the
same place Phase 182's gate stats and Phase 183's TMDB pacing already report (FR-203-6).

**Open question 2 resolved during build, not left open:** `MediaStore.kt:417`'s Library `?filter=`
path was already named in FR-203-4's own text as a site that "must not start its own sweep" and must
"take the current value and move on" — which already answered the question against the "leaning:
Library waits" text above it. Kept consistent with FR-203-4 rather than the older leaning: a cold
filter now returns no matches instead of blocking ~88s on an operator's own click.

Direct consequence of Phase 201's 2026-09-13 amendment: this phase exists because widening that
phase's detector silently invalidated an assumption its *caller* was built on, and nobody re-examined
the caller. See *The finding*.

## The finding

`TriageRoutes.kt:147` computes the Dashboard's attention breakdown like this:

```kotlin
val mkvBroken = MkvHealthCache.brokenPaths(all)   // ← blocks the whole response when cold
val mkvLayoutInstances = all.sumOf { TriageDetection.mkvLayoutBrokenCount(it, mkvBroken) }
```

`MkvHealthCache.brokenPaths` refreshes in-line when its cache is empty or older than 15 minutes. The
cache is process-lifetime, so **every restart starts it cold**, and the refresh is a full-library sweep
that opens every `.mkv` file in the library. Until it returns, `/triage/count` returns nothing — so
every *other* issue type on that page (untagged tracks, wrong default audio, duplicate episodes,
unresolved Jellyfin ids, segments…) waits behind a check that has nothing to do with any of them.

### Phase 201's amendment made this ~10× worse, and that is the real lesson

The original detector (`scanMkvLayout`, Phase 201 FR-201-2) read each file's **top-level headers only**
and stopped at the first `Cluster` — a few hundred bytes per file. That is precisely why FR-201-6 could
call the sweep "cheap" and why the 2026-09-13 amendment felt safe putting it behind a lazy, 15-minute,
on-access refresh.

The 2026-09-13 *later* amendment (FR-201-14/14a, shipped v1.13) made the walk **descend into the first
`Cluster`** — necessary and correct, since that is where the corruption actually lives. But the first
Cluster of a production file is ~400 KB, not a few hundred bytes. Measured on the live library:

| | per file | 8 015 files | wall clock |
|---|---|---|---|
| before (top-level only) | a few hundred bytes | ~KB-scale | "well under a minute" (FR-201-6) |
| after (first-Cluster descent) | **~400 KB** | **~3 GB of reads** | **~88 s** |

**The caller was never revisited.** A cost assumption stated in one requirement was invalidated by
another requirement three amendments later, and the only thing that surfaced it was a household member
noticing the Dashboard was slow. That is the finding worth keeping — not the 88 seconds.

### Why "just don't block" is the wrong fix on its own

The obvious patch — return whatever is cached and refresh in the background — makes a cold cache report
**zero broken files**. On this exact surface, that renders as a clean, confident "nothing needs your
attention."

That is the same false negative that wasted an entire release earlier the same day: v1.12's detector
looked in the wrong place, found nothing, and reported `OK` — and "the detector found nothing" was read
as "nothing is wrong." A cold cache reporting `0` is that failure with a different cause. Speed must
not be bought with a confident wrong answer.

## Requirements

**FR-203-1 — the Triage summary must never block on a health sweep.** `/triage/count` and `/triage`
respond from whatever is already known. No handler on the Dashboard's critical path may trigger a
synchronous full-library file walk. This is the invariant; everything below serves it.

**FR-203-2 — "not yet known" is a third state, and it is not zero.** When a health check has no result
yet, its `TriageTypeCount` row is **omitted from the response entirely** rather than sent with a count
of 0. An issue type that is absent from the breakdown is already how every other never-computed type
behaves (`segments_lowconf` when `detect_segments` is disabled, per Phase 150), so this needs no new
client state, no spinner, and no new copy — the row simply appears once there is something true to say.
A `0` on this page must always mean "checked, and clean."

**FR-203-3 — warm the cache in the background at startup.** A refresh kicks off on boot, off the
request path, so by the time anyone opens the Dashboard the answer normally already exists. Failure is
logged and never fatal (same posture as `sonarrEnrich` and the Phase 201 scan hook). This is what makes
FR-203-2's omitted-row window short in practice rather than merely honest.

**FR-203-4 — one refresh at a time, and callers never queue behind it.** Concurrent callers observing a
cold or stale cache must not each start their own sweep (the Dashboard, the Library `?filter=` path at
`MediaStore.kt:417`, and the scan hook can all fire within seconds of each other). A refresh already in
flight is joined by *nobody*: callers take the current value and move on. Same reasoning as Phase 201's
own concurrent-repair incident — the fix there was a single-worker queue, and the fix here is that a
sweep has exactly one owner.

**FR-203-5 — parallelize the sweep.** It is ~8 000 independent file opens, I/O-bound, and currently
serial. It runs on the background lane and must respect the existing `ProcessGate`/scan-worker
partitioning (Phase 182) so a health sweep can never starve interactive `/api/tv/**` traffic — the
whole point of that partitioning. Target is "finishes well inside the 15-minute refresh interval
without being noticeable," not a specific number.

**FR-203-6 — report when the answer is from.** `MkvHealthCache.sweptAt()` already exists and is
currently surfaced nowhere. The sweep's completion time and duration should be visible (Activity page
health reporting, or the Triage response itself) so a slow or failing sweep is diagnosable without
reading container logs — which is how this was found, and should not be how the next one is found.

**FR-203-7 — the posture generalizes.** Any future health check that walks the library on the
Dashboard's path adopts this same shape: cached, background-warmed, single-owner, omitted-when-unknown.
This phase is not about MKV files; `MkvHealthCache` is simply the first cache to have outgrown a lazy
on-access refresh. A second one added later must not re-learn this from a household member.

## Out of scope

- **Making the detector itself cheaper.** Bounding the Cluster walk to the first N children or N KB
  would cut the I/O substantially (the observed corruption sat at child index 4–11, well inside any
  sane bound) — but it trades a complete structural check for a signature match, and Phase 201 has
  already spent one release on a detector that looked in too small a window. If the sweep is still too
  expensive after FR-203-3/4/5, that trade can be made deliberately, in its own phase, with the
  FR-201-14a validation re-run against it. Not smuggled in as a performance fix.
- **A loading state for the attention breakdown.** FR-203-2's omitted row is deliberately the *absence*
  of a row, not a new "checking…" affordance. A spinner per issue type is UI this page has never had,
  and the background warm should make the window rare enough that it isn't worth inventing.
- **Persisting sweep results across restarts.** Phase 201 FR-201-13 is explicit that no "is this file
  broken" state is written anywhere and that the live sweep is the only source of truth. A disk-backed
  cache would make cold starts instant and would also be exactly the persisted-defect-state that
  requirement forbids. Revisit only by amending FR-201-13 on purpose.
- **The 15-minute refresh interval itself.** It is unexamined here and probably fine once a refresh is
  neither blocking nor serial.
- Any change to what the detector classifies as broken. This phase moves *when and how* the answer is
  computed; v1.13's answers stand unchanged.

## Open questions

1. **Where should the sweep's "warm at startup" live** — `Main.kt` alongside the other boot-time
   services, or inside `MkvHealthCache` itself as a self-warming object? The latter keeps the concern
   in one file but gives a plain `object` a coroutine lifecycle it currently doesn't have, which is the
   sort of thing that is hard to reason about at shutdown.
2. **Does the Library `?filter=mkv_track_layout` path want the same treatment?** It calls
   `brokenPaths` too (`MediaStore.kt:417`), but there the operator has *explicitly asked* for exactly
   this data, so blocking is arguably honest — an empty result there would be a lie in a way that an
   omitted Dashboard row is not. Leaning toward: Dashboard omits, Library waits. Not decided.
3. **Should a failed sweep be distinguishable from an unfinished one?** Both currently yield "no row."
   An operator whose sweep throws every time would see a permanently absent issue type and no
   indication anything is wrong — FR-203-6's reporting may be sufficient, or may need an explicit
   error state.
4. **Is 15 minutes still right** once a sweep is backgrounded and parallel, given a scan already forces
   a refresh on completion (Phase 201 FR-201-16)? The two mechanisms may now overlap enough that the
   interval is doing little.
