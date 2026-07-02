# Phase 116 — Determinate scan progress: a real bar with a real total (FR-SP3)

## Problem
During a scan, the Activity page's overall progress bar stays **empty** and the label only counts up
("N items scanned") with no denominator; the ambient scan dock behaves the same. The admin can't tell if
a scan is 10 % or 90 % done.

### Why (verified in code) — the total is thrown away
- The WS protocol already supports totals: `JobEvent.Started(jobId, total)` and
  `FileProgress(current, total)` (`jobs/JobEvent.kt`), and the FE **already renders them**: Activity's
  `#ov-bar` moves on `progress` events (`Activity.kt:353-372`), the Shell dock on the same
  (`Shell.kt:580-587`), both seeded by `Started.total`.
- But `runScan` broadcasts **`Started(jobId, -1)`** (`MediaRoutes.kt:1734`) — one line **before** it
  fetches the full Jellyfin item list (`:1736`, size even logged at `:1742`) — and then emits **no
  `FileProgress` at all** during the scan (only `ItemScanned` per item and `Finished`). So the bar has
  neither a total nor progress ticks. No guessing is needed — the scanner *knows* the exact worklist.

## Requirements

### A. Emit the real total
1. `runScan` broadcasts `Started` (or a follow-up `progress 0/N` immediately after the fetch) with the
   **effective worklist size**: the Jellyfin item count **after** applying the resume `skipIds` and the
   Phase 91 freshness filter (the producer filters at `MediaRoutes.kt:1760` — compute the filtered
   worklist size up front rather than filtering lazily). Per-library scans (`?library=`) do the same for
   their subset.
2. While the Jellyfin fetch is still running (before the total is known), the FE keeps today's
   indeterminate "starting…" state.

### B. Emit per-item progress
1. Each completed item emits `FileProgress(jobId, itemTitle, current, total)` alongside the existing
   `ItemScanned` — `current` = processed count including failures/skips-with-error, monotonic.
2. Items that appear in Jellyfin *mid-scan* don't grow the total (fixed at start); the bar clamps at
   99 % until `Finished` snaps it to done. Cancel keeps the current partial state (existing cancelled
   banner unchanged).

### C. Surfaces
1. **Activity overall bar**: with `Started.total` + `FileProgress` flowing, `#ov-bar` + `#ov-label`
   ("N of M · ~T remaining") work with today's rendering code plus a small label tweak — add a simple
   remaining-time estimate from the rolling items/second rate (label only, no bar jumps).
2. **Ambient dock** (`Shell.kt`) shows "N / M" + the filling bar (its `dockTotal` already comes from
   `Started.total`).
3. **Dashboard scan banner** (`Dashboard.kt:242`) says "Scanning — N of M items…".
4. Realtime-ingest runs (Phase 114) and single-item re-pulls emit `total=1` — tiny but consistent.

## Scope
- Backend: `MediaRoutes.runScan` (worklist precompute + `Started(total)` + per-item `FileProgress`).
- FE: label copy in `Activity.kt` / `Shell.kt` / `Dashboard.kt` (bar plumbing already exists).

## Non-goals
- No per-stage weighting (ffprobe vs TMDB vs artwork inside one item) — item granularity is enough.
- No pipeline-step-level composite progress (the bar tracks the scan step; other steps keep log lines).
- No persistence of progress across restarts (a restarted scan restarts its accounting via resume).

## Acceptance
- Starting a full scan shows "0 of M" within a couple of seconds and a bar that fills monotonically to
  100 % at completion; a freshness-filtered nightly run shows the *filtered* (small) total, not 327.
- Per-library scan and re-pull show correct small totals; the dock and Dashboard banner agree with
  Activity.
- A cancelled scan freezes at its true position with the existing cancelled messaging.
