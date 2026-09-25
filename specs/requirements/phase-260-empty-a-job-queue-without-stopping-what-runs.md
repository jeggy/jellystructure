# Phase 260 — Empty a job queue without stopping what is running

## Status

`Planned` — written 2026-09-25 from the owner's ask and the mockup in `design/app/activity.html` → *Jobs &
workers* (`?view=jobs`, drawn the same day; owner answered the design questions before this was written).
**Dev-reviewed 2026-09-25 against `main` `e7991df3`** (see §Dev review at the bottom: the claim statement is not
conditional on `queued` today, so FR-260-2's race guarantee needs one `WHERE`; the Jobs view polls, it
does not receive a WS event; the Recent entry is best a synthetic `media_job` row; open question 1 is
yes). Not built. Spec first. Builds on **164** (lanes, dedupe keys, per-job cancel) and **213**
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
   **Closed — dev review item 4: yes, they come back.** The pipeline's detect step enqueues per item that
   still needs detection (`PipelineEngine.kt:496`; subtitles at `:373`), and a cancelled row frees its
   dedupe key. Emptying is a pause until the next scan; the panel says so.
2. Should `cancelled_reason` be a new column, or is the Recent entry enough of a record?
   **Closed — dev review item 3:** neither; `media_job` already has `error` (`MediaJob.sq:71`) — write
   `emptied` there, and let the Recent entry be a synthetic `queue_emptied` row in the same table.

## Acceptance

1. Emptying `segments` with one running and six queued leaves the running job running to completion and
   six rows `cancelled`; `media` and `subtitles` are untouched.
2. Emptying all three in one request is one transaction.
3. A job claimed during the request is either running or cancelled, never both (test with a forced race).
4. The mockup's counts (50 queued · 52 on the tab) come from one source.

## Dev review (2026-09-25, against `main` `e7991df3`)

The pieces this builds on are where the spec says: `media_job` with `state`, `lane` and `dedupe_key`
(`MediaJob.sq:1-24`), the partial unique index over `queued`/`running` only (`:32-34`), per-job `cancel`
(`MediaJobQueue.kt:234-262`, a `cancelRunning` flag for a running job and a row update for a queued one),
`laneSummaries()` as the one count source (`:300-310`), and the routes in `JobsRoutes.kt` (`:47` is the
cancel). Five items.

1. **The claim is not conditional today, so FR-260-2's guarantee is false until it is.** `claimNext()`
   picks a row from `listQueuedByLane` and then runs `UPDATE media_job SET state = 'running', started_at =
   :started_at WHERE id = :id` (`MediaJob.sq:65`) — no `AND state = 'queued'`. A row emptied between the
   read and that update is flipped back to `running` and runs anyway. Add the condition and check
   `changes()`; a zero means "lost the race, pick the next". Acceptance 3's forced race is exactly this
   line and would fail against the code as it stands.
2. **The queue view polls; there is no job WS event.** The Jobs & workers segment polls `GET /api/jobs`
   while visible (`Activity.kt:572`, Phase 109); the only push is `job_queues` inside `GET /api/health`
   (`MediaJobQueue.kt:312`, `Server.kt:445`). FR-260-7's "update from the existing job WS event" becomes
   "the route's response carries the new counts and the view re-polls at once" — one source, as intended,
   without inventing an event.
3. **The Recent entry: a synthetic row in `media_job`, and `error` carries the reason.** The Recent panel
   is `listRecent` over `media_job` (`MediaJob.sq:61`), so cancelled rows *would* each appear in it unless
   filtered — FR-260-6 says they must not. Write `state = 'cancelled', error = 'emptied'` on the rows
   (`error` already exists, `:71`; no `cancelled_reason` column, no migration), filter
   `error = 'emptied'` out of the Recent list, and insert one `type = 'queue_emptied'` row per emptied
   queue (`state = 'done'`, lane set, label as FR-260-6's sentence, `enqueued_by` = the admin) — it rides
   the existing list, poll and count paths with nothing new.
4. **Open question 1 is yes, and the lean is right.** `PipelineEngine.kt:496` enqueues detection per item
   that still needs it on every pipeline run, and the freed dedupe key lets it through. The panel's
   *they return on the next scan* line is the whole answer; skipping items would be a second kind of
   state for the pipeline to honour, which 164's dedupe was built to avoid.
5. **"In one statement" needs the lane list built by the query layer.** SQLDelight has no `IN (:list)`
   binding; either three statements inside one `queries.transaction { }` (one per selected lane, which
   is still one transaction) or a `WHERE lane IN (?, ?, ?)` with the unselected slots bound to a
   never-matching value. Either satisfies acceptance 2; say which.

**Small corrections.** "164: *cancel … reused unchanged*" — the running branch sets a flag the worker
polls (`cancelRunning`), it does not kill anything, which is exactly why FR-260-3 costs nothing. The lane
names are the three strings at `MediaJobQueue.kt:309`; `unknown names → 400` should validate against that
list, not a copy.

**Net effect.** One `WHERE` on the claim, one route beside `JobsRoutes.kt:47`, one transaction, one
synthetic row type, one filter on Recent, the panel in `Activity.kt`'s jobs view. No migration.
