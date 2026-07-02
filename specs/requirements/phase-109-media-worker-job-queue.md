# Phase 109 — Serialized media-worker job queue for heavy edits (ffmpeg remux) + Activity ▸ Jobs page

## Problem
A heavy audio **re-order** is an `ffmpeg -c copy` **remux** — a full stream copy that rewrites the whole
file (for a 4K movie, tens of GB). Today that work runs **inline on the request that handles Apply**, so
the API stops responding (504s) and concurrent remuxes can take the box down.

### Verified execution model (why it breaks)
- `POST /api/media/{id}/tracks/reorder` runs `FfmpegRunner.reorderTracks` **inside the route handler**
  (`TrackRoutes.kt:370-418`); the per-episode editor does the same. `FfmpegRunner.runCommand`
  (`FfmpegRunner.kt:84-95`) is `popen` + a blocking `fgets` loop until ffmpeg exits — so a remux
  **occupies a server worker thread for its full duration** (minutes for 4K). A couple of these plus
  normal traffic starves the Ktor CIO native worker pool → every request stalls → the dev proxy (and any
  reverse proxy) reports **504**; heartbeats and WS pings stall too.
- The **bulk** wizard (Phase 96) already runs off-request (`appScope.launch`, `TrackRoutes.kt:490`) and
  streams `JobEvent`s — but it is **fire-and-forget concurrency**: two bulk runs (or a bulk + several
  singles) happily remux in parallel; there is **no queue, no persistence, no priority, no disk-space
  check**, and each parallel remux doubles disk I/O + temp-file footprint (a second full-size copy per
  job — two 4K remuxes can exhaust the disk, which is a plausible path to the observed end-of-run crash).
- What already exists and is kept: temp-file + `mv` on success (`TrackCommandBuilder:62,81,97`), temp
  cleanup on failure (`FfmpegRunner.runRemux`), ownership/mode preservation
  (`withOwnershipPreservation`), the seeding guard on write paths, and the `JobEvent` WS protocol
  (`jobs/JobEvent.kt`: `started`/`progress`/`file_done`/`finished` + `WsBroadcaster`).

## Goal
Move heavy media edits off the request path onto a **single, serialized media worker**: **Apply enqueues
a job and returns immediately**; jobs run **one at a time** (FIFO); and a dedicated **Jobs** view in
Activity shows the worker, the running job (live ffmpeg progress), the queue, and recent results.

## Requirements

### A. Enqueue, don't block
1. Clicking **Apply** on an operation that needs a remux (audio/subtitle **re-order**, track **remove**;
   the Bulk Audio Reorder wizard; any per-episode re-order) **creates a job and returns immediately**
   (`202` + job id) — never runs ffmpeg on the request thread. The bulk wizard becomes a **planner**
   that enqueues its per-file work as one job with N files (its existing plan/opt-in semantics
   unchanged).
2. Light edits stay inline: flag/default/language changes are `mkvpropedit` in place (~40 ms) — **not**
   queued.
3. The seeding guard runs **twice**: at enqueue (fail fast with the current 409 UX) and again when the
   job starts (state may have changed while queued).

### B. Single serialized worker
1. A **media worker with concurrency 1** drains a FIFO queue: exactly **one remux at a time**, no matter
   how many Applies or admins. Separate from the **scan** workers (which stay parallel). The worker runs
   ffmpeg on a **dedicated dispatcher/thread** so the blocking `popen` read never touches the server's
   request threads (this alone removes the 504s).
2. Run ffmpeg at **low priority**: prefix the command with `nice -n 19 ionice -c3` (shell prefix — the
   runner already builds shell commands) so a big remux doesn't starve the API or playback.
3. **Preflight disk space:** refuse to start (job → `failed`, reason `disk_space`) unless free space on
   the target filesystem ≥ source size + 10 % (`statvfs`); the temp copy is a second full-size file.
4. **Live progress:** run ffmpeg with `-progress pipe:1 -nostats` and parse `out_time_ms`/`speed`
   incrementally from the existing `fgets` loop (today the loop only buffers output); combined with the
   source duration (ffprobe already known) this yields a real per-file % + ETA. Emit as `JobEvent`
   `progress` (extend the payload with `pct`, `speed`, `eta_seconds`).
5. **Persistence & restart:** a new `media_job` SQLite table (id, type, target item/episode, params,
   enqueuer, state, timestamps, error). Jobs survive restart: on boot, `running` → re-queued (the temp
   file is removed; the original is untouched by design), `queued` stays queued. States:
   `queued → running → done | failed | cancelled`.
6. **Write safely** (kept from today): remux to `.jstmp_*`, atomic `mv` over the original only on
   ffmpeg success, ownership/mode restored, temp removed on failure. After success: re-probe, re-derive
   `resolvedLanguage`, history entry, Jellyfin refresh — same post-steps the inline path does today.

### C. Activity ▸ Jobs page
A **"Jobs & workers"** view (segmented switch next to the existing scan console/log filter bar,
`Activity.kt:124-136`):
- **Media worker** status line (busy/idle · concurrency 1 · counts: running / queued / done today).
- **Running now** — the current job with a live progress bar + ffmpeg line (%, speed, ETA), who queued
  it, and **Cancel** (kills the ffmpeg process group, removes the temp file, job → `cancelled`).
- **Queue** — waiting jobs in FIFO order (position, target, size, enqueuer, time) each **cancellable**.
- **Recent** — completed/failed (duration; failed shows the reason + **Retry** and is flagged for
  attention).
Everything streams over the existing job WebSocket (no polling); the detail-page toast links here
("Apply queued — view in Activity ▸ Jobs").

### D. Failure handling
A failed remux (bad source, disk full, ffmpeg non-zero exit) **fails just that job**, leaves the
original file untouched, logs an Activity warning, flags the item for attention, and offers **Retry** —
it never takes the worker or server down. A worker-loop exception is caught, the job is failed, and the
worker continues with the next job.

## Scope
- Backend: `MediaJobQueue` (persistent FIFO, `media_job` table + `.sqm` migration) + single-worker
  executor on a dedicated dispatcher wrapping `FfmpegRunner`; `-progress` parsing; nice/ionice prefix;
  disk preflight; re-order/remove endpoints return a job id; bulk-reorder becomes an enqueue; `JobEvent`
  extensions.
- `design/app/activity.html` — the **Jobs & workers** view. **Built.**
- `design/app/media.html` — re-order note/toast say **Apply queues a job**. **Built.**
- FE: `Activity.kt` Jobs view; `MediaDetail.kt`/`BulkReorderWizard.kt` switch to job-id responses (the
  wizard's existing `JobEvent` progress UI is reused nearly as-is).

## Non-goals
- No multi-worker parallelism for remuxes (serialization is the point — protects the box). Concurrency
  could become a bounded config later, defaulting to 1.
- No change to the scan pipeline's own parallel workers.
- No priority/reordering within the queue beyond FIFO + cancel.
- No queueing of `mkvpropedit` micro-edits.

## Acceptance
- Applying a re-order on a 4K movie returns in <1 s with a job id; the API (Library, detail, Ravilo
  feeds) stays fully responsive while the remux runs; the file flips atomically at the end.
- Firing five Applies (or two admins + the bulk wizard) results in five queued jobs executing strictly
  one-at-a-time; queue order and live % are visible in Activity ▸ Jobs; cancel works on queued and
  running jobs.
- Killing the server mid-remux leaves the original playable; on restart the job re-runs from the queue.
- A remux with insufficient disk space fails fast with a clear reason and no temp residue.
