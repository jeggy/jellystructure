# Phase 109 — Serialized media-worker job queue for heavy edits (ffmpeg remux) + Activity ▸ Jobs page

## Problem
A heavy audio **re-order** is an `ffmpeg -c copy` **remux** — a full stream copy that rewrites the whole
file (for a 4K movie, tens of GB). Today that work runs **inline on the request/thread that handles
Apply**, so:
- the API stops responding while it runs → clients get **504 Gateway Timeout**;
- firing several re-orders (or two admins re-ordering at once) piles concurrent remuxes onto the same
  CPU/disk → the box saturates and, after minutes, the server **crashes**.

Re-ordering audio on a 4K movie must "just work" without taking the server down.

## Goal
Move heavy media edits off the request path onto a **single, serialized media worker**: **Apply enqueues a
job and returns immediately**; jobs run **one at a time** (FIFO); and a dedicated **Jobs** view in Activity
shows the worker, the running job (live ffmpeg progress), the queue, and recent results.

## Requirements

### A. Enqueue, don't block
1. Clicking **Apply** on an operation that needs a remux (audio/subtitle **re-order**; the Bulk Audio
   Reorder wizard; any per-episode re-order) **creates a job and returns immediately** (202-style) with the
   job id — never runs ffmpeg on the request thread.
2. Light edits stay inline: flag/default/language changes are `mkvpropedit` in place (~40ms) — **not**
   queued.

### B. Single serialized worker
1. A **media worker with concurrency 1** drains a FIFO queue: exactly **one remux at a time**, regardless
   of how many Applies or how many admins enqueue. This is separate from the **scan** workers (which stay
   parallel).
2. Run ffmpeg at **low I/O/CPU priority** (ionice/nice) so a big remux doesn't starve the API even while
   running.
3. Jobs survive brief load; a crash/restart **re-queues** unfinished jobs (persist the queue).
4. Each job: type, target (title/episode + resolution/size), enqueuer (Jellyfin user), state
   (`queued → running → done | failed | cancelled`), progress, timestamps, and the ffmpeg command.
5. **Write safely:** remux to a temp file, then atomic rename over the original (never leave a
   half-written file); optional qBittorrent cross-seed guard already applies.

### C. Activity ▸ Jobs page
A **"Jobs & workers"** view (segmented switch next to the existing "Scan console"):
- **Media worker** status line (busy/idle · concurrency 1 · counts: running / queued / done today).
- **Running now** — the current job with a live progress bar + ffmpeg line (frame/fps/time/speed/ETA),
  who queued it, and **Cancel**.
- **Queue** — waiting jobs in FIFO order (position, target, size, enqueuer, time) each **cancellable**.
- **Recent** — completed/failed (with duration; failed shows the reason + **Retry** and is flagged for
  attention).
Everything streams over the existing job WebSocket (no polling).

### D. Failure handling
A failed remux (bad source, disk full, ffmpeg non-zero exit) **fails just that job**, leaves the original
file untouched, logs an Activity warning, flags the item for attention, and offers **Retry** — it never
takes the worker or server down.

## Scope
- Backend: a `MediaJobQueue` (persistent FIFO) + single-worker executor wrapping the existing
  `FfmpegRunner`; the re-order endpoints return a job id instead of running inline; job WS events.
- `design/app/activity.html` — the **Jobs & workers** view (worker status, running/queue/recent, cancel).
  **Built.**
- `design/app/media.html` — the tracks re-order note + toast now say **Apply queues a job** (Activity ▸
  Jobs). **Built.** (Bulk Audio Reorder wizard enqueues per-title jobs the same way.)

## Non-goals
- No multi-worker parallelism for remuxes (serialization is the point — protects the box). Concurrency
  could become a bounded config later, defaulting to 1.
- No change to the scan pipeline's own parallel workers.
- No priority/reordering within the queue beyond FIFO + cancel (a later nicety).
