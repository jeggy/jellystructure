# Phase 260 — Empty a job queue without stopping what is running

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockup in `design/app/activity.html` → *Jobs &
workers* (`?view=jobs`, drawn the same day; owner answered the design questions before this was written).
Not dev-reviewed. Not built. Spec first. Builds on **164** (lanes, dedupe keys, per-job cancel) and **213**
(three queues — `media`, `segments`, `subtitles` — over one `behavior.job_workers` pool).

> *"Let's add an empty queue to Jobs & workers. It should both support empty all queues, or empty only
> segment detection queue etc. When emptying it should not empty the currently running items."*

## Today

A queued job can be cancelled one at a time (164: *cancel … reused unchanged*). A backlog of 41 subtitle
pre-warms or a whole library's segment detection is 41 clicks, or a restart that does not clear it either.

## Functional requirements

**FR-260-1 — one route, one or more queues.**
`POST /api/jobs/empty` with `{ "queues": ["segments"] }` (any non-empty subset of the three names;
unknown names → 400). Admin only. Response: `{ "removed": { "segments": 6 }, "kept_running": [<job snapshot>…] }`.

**FR-260-2 — only waiting jobs, in one statement.**
`UPDATE media_job SET state = 'cancelled', finished_at = :now, cancelled_reason = 'emptied' WHERE lane IN (…)
AND state = 'queued'` in one transaction. A worker claiming a job at the same instant claims with its own
conditional `queued → running` update, so each job is either claimed or cancelled, never both. Running
jobs are not read, touched or signalled. Cancelled rows free their dedupe key (164's partial unique index
covers `queued`/`running` only), so the same work can be queued again later.

**FR-260-3 — "running" means the whole job.** Owner decision: a `segments_season` job that is on episode 4
of 22 **finishes the season**. Emptying never shortens a job; there is no per-step cancellation here.

**FR-260-4 — the queue shows two ways in, one confirmation.**
The Queues card gets *Empty queues…* in its header and an *Empty* button on each lane with something
waiting (hidden at 0). Both open the same inline panel: one row per queue — checkbox, *N waiting*, and
**what keeps running** (*keeps running: Detecting intro & credits · Fjollerne S11E04*, or *nothing
running*). From a lane's button only that queue is listed. The confirm button counts what it will do
(*Remove 6 waiting jobs*; *Nothing picked*, disabled, at zero); the other button is *Keep them*.
A queue with nothing waiting is listed, dimmed, unchecked.

**FR-260-5 — no undo.** Owner decision. The confirmation is the safeguard; a waiting job has touched no
file, so nothing on disk needs reverting.

**FR-260-6 — it is written down.** One *Recent* entry per emptied queue:
*Emptied the segments queue · 6 waiting intro & credits detections removed* / *by {admin} · 14:02 · kept
the running job — {title}*, badge *emptied*. Individual cancelled rows are **not** listed one by one in
Recent. The lane shows *Emptied 14:02 · 6 removed · the running job finishes* until its next job. A toast
confirms: *N waiting jobs removed · running jobs untouched*.

**FR-260-7 — counts agree everywhere.** The pool chip (*N queued*), each lane's count and the tab badge
(queued + running) are one number source and update from the existing job WS event.

## Open questions

1. **Does the next pipeline scan put them straight back?** Segment detection and subtitle pre-warm are
   enqueued by the pipeline for items that still need them. If the next scan re-adds all 41, emptying is a
   pause, not a clear, and the panel should say so (*they return on the next scan*) — or emptying should
   also skip those items until something changes. Lean: say so, change nothing else.
2. Should `cancelled_reason` be a new column, or is the Recent entry enough of a record?

## Acceptance

1. Emptying `segments` with one running and six queued leaves the running job running to completion and
   six rows `cancelled`; `media` and `subtitles` are untouched.
2. Emptying all three in one request is one transaction.
3. A job claimed during the request is either running or cancelled, never both (test with a forced race).
4. The mockup's counts (50 queued · 52 on the tab) come from one source.
