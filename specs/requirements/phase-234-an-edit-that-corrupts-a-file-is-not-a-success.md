# Phase 234 — An edit that corrupts a file is not a success, and one file is written by one process

> From the operator's bug report of 2026-09-17 (*Hoppe Hares Byggebande* S01E05: `DirectPlayError`
> on the Jellyfin Android TV app, a toast and stutter mid-episode) and the same evening's sweep
> ([report](../research-reports/mkv-payload-corruption-beyond-first-cluster-2026-09-17.md)): 66 files
> in three titles are corrupt mid-file, all written during the 2026-09-13 concurrent-repair race.

## Status

`Planned` 2026-09-17 — not dev-reviewed. Backend-only (`MkvpropeditRunner.kt`, `FfmpegRunner.kt`,
`MkvLayoutAudit.kt`, new `MediaFileLock.kt`). No wire, schema or string change.

**Numbering:** verified against `STATUS.md` 2026-09-17 — admin taken through **233**.

## What is wrong

1. **The post-edit check finds the corruption and reports success.** `verifyAndRepairLayout` runs
   after every `mkvpropedit` edit and repairs only `TRACKS_AFTER_CLUSTER`
   (`if (layout != MkvLayout.TRACKS_AFTER_CLUSTER) return true`). `scanMkvLayout` has returned
   `ELEMENT_SIZE_OVERFLOW` since v1.13 — added for this very incident — and `MkvLayoutAudit.REPAIRABLE`
   already treats both alike. The edit path is the one consumer that does not.
2. **Nothing stops two processes writing one file.** The 2026-09-13 amendment to 201 moved *Fix now*
   onto the media job queue and relied on "the media lane's single-worker FIFO guarantees no two
   remuxes ever run at once". That stopped being a guarantee twice over: **213** put the lanes on a
   shared pool of 1–3 workers, and the edit path's own repair (`verifyAndRepairLayout` →
   `repairTracksLayout`) never went through the queue at all. Every remux of a file writes the same
   fixed `.jstmp_<name>` (109's cancel and disk preflight depend on that name), and `mkvpropedit`
   edits in place — so a track edit during a queued repair of the same file is the 2026-09-13 race
   again, by a different door.
3. **A repair of this kind loses data and says nothing.** A stream-copy remux makes the container
   valid by discarding what it cannot parse. Measured: the operator's remux of S01E05 has **246 fewer
   video packets** (≈10 s), 329 fewer audio packets and 2 fewer subtitle cues than its clean seeding
   copy; duration unchanged, because the gaps are inside the file.

## Requirements

**FR-234-1 — Both broken layouts fail the post-condition.** `verifyAndRepairLayout` repairs whatever
`MkvLayoutAudit` would: the decision is one shared predicate (`MkvLayout.needsRepair`), so the edit
path, the sweep and *Fix now* cannot drift apart again. Each case logs its own sentence — a file with
an overflowing element did not have its "Tracks evicted".

**FR-234-2 — One file, one writer.** `MediaFileLock.withLock(path)`: a keyed, in-process mutex held
around every remux (`runRemux`, `runRemuxTracked`) and every `mkvpropedit` invocation. Different files
never wait on each other. The temp filename stays fixed — uniqueness is not needed once same-file
writers are serialised, and 109's cancel (`pkill -f` on that name) and disk preflight keep working.
The post-edit repair takes the lock on its own rather than inside the edit's, so the lock is never
re-entered.

**FR-234-3 — A lossy repair says so.** Repairing `ELEMENT_SIZE_OVERFLOW` logs, at warn level, that
the remux discards the data it cannot read and that the file should be replaced from its source. No
UI: the Triage surface is unchanged. (Whether Triage should carry the sentence is open question 1.)

## Non-goals

- **Detecting mid-file corruption.** The shipped walk stops at the first cluster by design; the 66
  files are invisible to it and stay so. A title-scoped deep check is the research report's option 3.
- Repairing the 66 files — an operator action on production media (copy from the seeding copies).
- Moving the edit path's repair onto the job queue.

## Acceptance

1. `linuxX64Test`: `needsRepair` is true for exactly the two broken layouts; two `withLock` calls on
   one path never overlap; two on different paths do.
2. A track edit on a file with a queued repair waits for it (observed in the log order).

## Open questions

1. Should a lossy repair be visible in Triage or the title's History, not only the log?
2. `classify` reads a file another writer may be replacing. The lock covers writers only; a reader
   racing an `mv` sees either the old or the new inode, both whole — believed safe, not tested.
