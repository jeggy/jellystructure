# Phase 196 — `last_checked` records that the row was written, so any unrelated write hides an item from the scanner

> Live report, 2026-09-06: *"I just triggered a normal scan in jellystructure but it did not find the
> new episode of Fjollerne."*

## Status
✓ Built 2026-09-06. Not dev-reviewed, not live-verified (no deploy — the running backend still has the
old behaviour). Root-caused against the production database. Companion phase: **195** (the ingest retry
set that cannot drain). Neither alone is sufficient — see *Why both phases are needed*.

**Implementation notes:**
- FR-196-1/2: `upsertItemDbOnly` takes an explicit `examined: Boolean`. When false it reads the stored
  value back via a new `getLastExamined` query and hands it to `upsert` — `INSERT OR REPLACE` rewrites
  every column, so carrying it forward is the only way to leave it alone (the shape Phase 163 already
  uses for `has_segments` in the same function). `updateOne` gained `examined: Boolean = false`, so all
  68 existing call sites are correct by omission; the failure mode of a wrong `false` is one redundant
  scan, of a wrong `true` a title that silently stops being scanned. Only two paths pass `true`:
  `addOrUpdate` (the scan write path) and `POST /api/media/{id}/sync` — and the latter only on the
  branches that actually re-probe files (`syncMovie`/`syncSeriesEpisodes`), not `rescanMetadata`, which
  re-fetches TMDB without reading a single file.
- FR-196-3: renamed to `last_examined_at` in the column, `lastExaminedMap`, `lastExaminedAt()` and
  `isDueForRecheck`'s parameter. **Deviation:** done as a table rebuild in `40.sqm`, not
  `ALTER TABLE … RENAME COLUMN` — this project's SQLDelight dialect is `sqlite_3_18`, which predates
  both `RENAME COLUMN` (3.25) and `UPSERT`.
- FR-196-4: folded into `40.sqm`'s `INSERT…SELECT` as `MIN(last_checked, scanned_at * 1000)`.
  **Dry-run against a copy of the production database:** 516 rows in, 516 out, `SUM(length(json))`
  byte-identical at 31 461 342, all four `media_*` indexes recreated, 0 rows left ahead of their own
  `scannedAt`, and Fjollerne's value lands on 2026-09-05 01:49 → immediately due. No forced repair rescan.
- FR-196-5: **deviation** — this `Logger` has no DEBUG level, so rather than adding one, the freshness
  filter emits a single compact INFO line naming the five longest-unexamined skips and their age
  (`fjollerne-2005 31h (airing)`). Same diagnostic value; no new log level. The item-detail surface is
  **not built** — the API change is there but the wasmJs detail page was left alone.
- FR-196-6: an `AIRING_FLOOR_MS` of 24 h in `isDueForRecheck`, applied as `minOf(configuredTier, floor)`
  so a *faster* configured cadence still wins, and as the whole predicate when the tier is `never`.
- 4 new `FreshnessFilterTest` cases (7 → 11) covering the floor, its non-application to non-airing
  titles, `never`, and the faster-cadence-still-wins property. `compileKotlinLinuxX64` clean,
  `verifyCommonMainJellystructureDbMigration` green, `linuxX64Test` 221/221.

## The finding

Fjollerne's row, production, 2026-09-06 08:40 local:

```
scanned_at    2026-09-05 01:49:11     ← when its files were last actually examined
last_checked  2026-09-06 08:37:05     ← what the freshness filter reads
```

Two timestamps, side by side, 31 hours apart, and the scanner believes the wrong one.

`scan_files` is configured `refresh_this_year = "daily"`, and Fjollerne correctly resolves to that tier —
Phase 181's `isActivelyAiring` works exactly as designed (`sonarrNextAiringDate = 2026-09-13`,
`sonarrStatus = continuing`). But `last_checked` is bumped roughly every 30 minutes, so
`now - last_checked` is never ≥ 24 h, so Fjollerne is **never due**:

```
Jellyfin returned 519 items (0 skipped for resume, 15 in the effective worklist)
Scan: 504 item(s) not due for a recheck yet — skipped by the freshness filter
```

Fjollerne is one of the 504. A new episode had been on disk for two hours.

## Root cause

`MediaStore.upsertItemDbOnly` (`MediaStore.kt:830-855`) stamps the clock on **every write to the row**,
regardless of what caused it:

```kotlin
private fun upsertItemDbOnly(item: MediaItem) {
    libraryVersionAtomic.incrementAndGet()
    val now = nowMs()
    lastCheckedLock.withLock { lastCheckedMap[item.id] = now }
    …
    db.mediaQueries.upsert(
        …
        scanned_at = item.scannedAt,   // ← the item's own value, carried through honestly
        last_checked = now,            // ← wall clock, unconditionally
    )
}
```

Note the asymmetry in that single call: `scanned_at` is taken from the item (so it means what it says),
while `last_checked` is `now()` (so it means "this function ran"). Every writer in the codebase reaches
this function — `write_nfo` stamping `nfoWrittenAt`, `sync_jellyfin` stamping `jfSyncedAt`, artwork
fetch, Sonarr enrichment, segment writes, a manual metadata edit — and every one of them silently
resets the recheck clock for an item whose *files* nobody looked at.

`last_checked` is documented as "Phase 91: per-item last_checked timestamps" (`MediaStore.kt:123`) with
no statement of what "checked" means. `isDueForRecheck` (`FreshnessFilter.kt:55-66`) consumes it as
"when did we last examine this title", which is the only reading that makes the freshness filter
correct, and is not what the field holds.

### It is bulk writes that do the damage

Grouping the production library by `last_checked` to the second:

```
2026-09-06 02:10:17   259 items      ← one second
2026-09-06 02:10:18    51 items
2026-09-06 08:37:05    63 items
2026-09-06 08:37:04    27 items
```

**310 items had their freshness clock reset within two seconds** at 02:10 local — the `write_nfo` pass
(`Wrote NFO: /mnt/series/jellyfin/Fjollerne/tvshow.nfo`, `2026-09-06T00:10:14Z`). Not one of those 310
titles had a file re-read. All 310 became invisible to the next day's scans.

Across the library: **89 of 516 items** carry a `last_checked` more than an hour ahead of their
`scanned_at`. The number is understated, because `scanned_at` is also bumped by any successful scan —
the two only diverge visibly for items the scanner has *stopped* reaching, which is exactly the
population this bug creates.

### The circular part

`sonarrEnrich.enrichAll()` runs immediately after every library scan (`PipelineEngine.kt:266`). For
each TV show whose Sonarr data changed it calls `mediaStore.updateOne(updated)`
(`SonarrEnrichService.kt:51-53`) — and for an actively-airing show, `sonarrNextAiringDate` /
`sonarrNextAiringEpisode` change constantly, so the write happens nearly every cycle.

So:

1. Sonarr enrichment writes `sonarrNextAiringDate` — the field that makes `isActivelyAiring` true.
2. That write stamps `last_checked = now`.
3. The freshness filter reads `isActivelyAiring = true`, picks the `daily` tier — the *right* tier —
   and then compares against a clock the enrichment just reset.
4. The item is not due. It is skipped.

**The mechanism that supplies the "this show is airing, check it often" signal is the same mechanism
that hides the show from the scanner.** Phase 181 correctly identified that premiere-year bucketing
starved airing shows and fixed the tier selection; the fix cannot take effect because the threshold it
is compared against is reset by a side effect of gathering its own input.

The more actively a show is airing, the more often Sonarr's data about it changes, the more often its
clock is reset — the bug is strongest exactly where Phase 181 aimed it.

## Why both phases are needed

Phase 195 and this one are independent, and fixing either alone leaves the reported symptom:

| Path to the new episode | Blocked by |
|---|---|
| Jellyfin webhook → realtime ingest | **195** — gate saturation from the 117-item retry burst |
| Phase 181 sweep → realtime ingest | **195** — same burst, same failure |
| Periodic / manual library scan | **196** — item is never "due" |

The sweep detected Fjollerne's missing episode on the first cycle after it landed. The ingest path it feeds
was saturated (195). The scan that should have been the backstop skipped the item (196). Both doors
were shut by different bugs, which is why six scan cycles across four hours produced nothing.

## Problem, stated plainly

A field whose name asserts one thing and whose value records another, consumed by a filter that decides
whether work happens. Every unrelated write to a row makes the system more confident it has recently
examined a title it has not examined at all.

## Goal

The freshness filter's decision is based on when a title's files were last examined. No write that does
not examine files can defer a scan.

## Requirements

### FR-196-1 — Stop stamping the clock on every write

`upsertItemDbOnly` no longer sets `last_checked = now()`. The column carries forward its stored value
unless the caller explicitly supplies a new one — the same treatment `scanned_at` already gets two
lines above it, and the same "INSERT OR REPLACE touches every column, so carry the current value
forward" discipline Phase 163 already applied to `has_segments` in this exact function.

### FR-196-2 — Only an examination sets it

The clock is set by the paths that actually read the title's files on disk — `Scanner.scanItem` /
`scanSeries` / `scanMovie` / `scanMusicVideo` / `syncSeriesEpisodes` — at the point they complete. A
scan that *fails* must not stamp it, or a permanently-failing item becomes permanently fresh.

Audit every current writer for the same omission-in-reverse that Phase 193 audited for `jfSyncedAt`:
each site either examined the files and stamps, or did not and says so in a comment. No site may stamp
without examining.

### FR-196-3 — Make the name mean the thing

Rename to `last_examined_at` (column, `lastCheckedMap`, `lastChecked()`, `isDueForRecheck`'s parameter)
in the same change, with a doc comment stating the invariant: *set only by a completed file
examination; never by a metadata write.*

This is not cosmetic. "Checked" is ambiguous enough that the current behaviour reads as defensible at
every individual call site, which is how it survived from Phase 91 to now. `scanned_at` cannot simply
be reused for this — it is the item's own scan timestamp carried in the JSON blob and consumed
elsewhere — but the two must stop disagreeing silently.

### FR-196-4 — Repair the existing values

A one-time backfill sets `last_checked = min(last_checked, scanned_at * 1000)` for every row, so items
currently hidden behind a falsely-fresh clock become due immediately.

Bounded and safe: it can only move the value backwards, i.e. only ever cause *more* scanning.

*Measured on a copy of the production database:* **all 516 rows** move, not the 89 quoted above — 89 is
how many were over an hour ahead of their own `scannedAt`, but essentially every row is ahead by *some*
amount, because the clock is reset by writes that happen after every scan. Fjollerne's lands exactly on its
`scannedAt` (2026-09-05 01:49) and is immediately due.

Do **not** trigger a repair rescan as part of this: the Phase 188 recovery is the standing reminder
that a broad forced rescan has its own blast radius. Let the normal cadence pick them up.

### FR-196-5 — A skipped item can be explained

`Scan: 504 item(s) not due for a recheck yet` is true and useless — it cannot distinguish "correctly
fresh" from "hidden by a bookkeeping bug". At DEBUG, log per skipped item its tier, its
`last_examined_at`, and the threshold it failed. On the item's own detail page, show when its files
were last examined, next to the existing NFO/sync timestamps.

Nobody could have found this from the outside: the only visible symptom was a missing episode, and the
only two numbers that would have shown it are both invisible in the UI.

### FR-196-6 — An airing show cannot be starved

Regardless of the clock, a title whose `sonarrNextAiringDate` is within the next 7 days is examined at
least once per configured `refresh_this_year` cadence. A belt-and-braces floor for the exact population
Phase 181 was written to protect, so a future variant of this bug cannot silently starve it again.

## Non-goals

- No change to the tier selection in `isDueForRecheck`, to `cadenceMs`, or to Phase 181's
  `isActivelyAiring` predicate. All three are correct.
- No change to the configured cadences in `config.toml`. `daily` for an airing show is the right
  setting and is not what failed.
- No change to `scanned_at`'s meaning or its consumers.
- No forced repair rescan (see FR-196-4).
- Nothing about ingest, gates, or the retry set — that is **195**.

## Acceptance

1. Write an NFO for a title without scanning it. Its `last_examined_at` does **not** move.
2. Run `write_nfo` across the library: no item's freshness clock changes. (Today: 310 in two seconds.)
3. Take an item last examined > 24 h ago in the `daily` tier. Run a scan: it is in the effective
   worklist, not the skipped 504.
4. Add an episode to an actively-airing series with `[ingest] realtime` disabled entirely. The next
   scheduled scan picks it up — proving the scan alone is a sufficient backstop.
5. After the FR-196-4 backfill, no row has `last_examined_at > scanned_at * 1000`.
6. A failed scan of an item does not stamp its clock; the item stays due.
7. The item detail page shows when the title's files were last examined.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:830-855` — `upsertItemDbOnly`, the
  unconditional `last_checked = now` beside the honest `scanned_at = item.scannedAt`; `:123-133` — the
  cache and its under-specified doc comment; `:239` — `lastChecked()`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/FreshnessFilter.kt:55-66` — `isDueForRecheck`;
  `:82-103` — `computeFreshnessFilter` and the `store.lastChecked(item.id)` read at `:95`.
- `src/linuxX64Main/kotlin/dev/jellystructure/media/PipelineEngine.kt:266` — `sonarrEnrich?.enrichAll()`
  immediately after every library scan.
- `src/linuxX64Main/kotlin/dev/jellystructure/arr/SonarrEnrichService.kt:44-54` — the per-item
  `updateOne` that closes the circle.
- `specs/requirements/phase-181-converge-on-jellyfin.md` — FR-181-2 and the original Fjollerne incident
  this phase's finding re-opens from the other end.
- Production evidence 2026-09-06: `fjollerne-2005` (`scanned_at` 2026-09-05 01:49:11 vs `last_checked`
  2026-09-06 08:37:05); 89/516 items ≥ 1 h apart; 310 items stamped within two seconds at 02:10 local;
  `Scan: 504 item(s) not due for a recheck yet` against a 519-item library.

## Open questions

1. **Is `last_checked` read anywhere else?** The freshness filter is the only consumer found, but if
   any UI or triage surface displays it as "last checked", that copy becomes wrong-in-the-other-
   direction once FR-196-2 lands — it will start showing genuinely old dates for items that are fine.
2. **Should a `write_nfo` failure make an item due sooner?** Arguably an item we could not write is one
   worth re-examining. Out of scope here, but the inverse question is worth recording.
3. **Does the same conflation exist in `dirty_item.created_at` or `scan_processed`?** Both are
   scheduling inputs written by paths that may not have done the work they represent. Worth a quick
   audit while this is fresh rather than discovering it the same way.
4. FR-196-6's 7-day window is a guess. Sonarr already knows the exact air date; the right window may be
   "next airing is within one cadence period", which is self-adjusting and has no magic number.
