# Phase 272 — AI work is a queue you can watch, with its last five conversations kept

## Status

`✓ Built` 2026-09-27, the same afternoon it was written (commits `9b9298d9`, `3f337153`). `Planned` when written
2026-09-27 from the owner's two asks after the first real AI run on production. Builds on **270** (the AI tab,
the batch runner, the per-job monthly limit), **269** (the Recommended lists the re-rank orders, *Rebuild now*)
and **213/260/261** (Activity ▸ *Jobs & workers*). Not dev-reviewed.

### Build (2026-09-27)

- **Migration 57**: `ai_queue` (unique on *(job, subject)*, `INSERT OR REPLACE` so newest wins and moves to the
  back), `ai_transcript`, and `ai_batch` + `ended_at`/`outcome`/`cost_micro_usd`/`counts`/`cancelling`. Dry-run
  against a copy of production's database: applies cleanly; the one batch read before this phase gets empty
  columns and shows as *read before conversations were kept* with a neutral mark, not a failure.
- **`AiJobs`**: `enqueueRerank`/`enqueueThemes` → `sendQueued` (one batch per job out, oldest first, the limit
  taken as a prefix of per-request worst cases — FR-272-4's sentence kept in memory per job and shown by both
  the card and the AI tab), `pollPending` stores Anthropic's `request_counts` and sends the job's queue right
  after a read, a conflated `Channel` wakes the minute loop at once on a queue or a cancel, a failed send backs
  off five minutes. The theme queue skips titles already waiting or in the batch out, so no title is paid for
  twice. `afterBuild(inputs, reason, by)` queues then sends inline, as 270's did.
- **Verdicts** (`AiRequests.judgeRerank`/`judgeThemes`) return the reason; `validateRerank`/`validateThemes` are
  now `judge…().value`, so what is accepted is unchanged (270's validator tests pass untouched). An `errored`
  result carries its API error type and message.
- **Transcripts** are written at send (system prompt and user message pulled out of the exact request) and
  filled at read (answer as returned, verdict, the readable picks with titles, tokens, cost); `pruneHistory`
  keeps five ended batches with their items and transcripts.
- **269**: `RerankInput.label` = the viewer's Jellyfin username (queue label only; a test asserts it is absent
  from the request). `rebuildViewer(device, by)` always queues when `by` is set (the admin route passes the
  session's username); `buildAll(reason)`; the pipeline's `runPipeline(byHand = triggerKind == "manual")` and
  `RecommendationsStep.notDue(last, now, every, byHand)`. `GET /api/pipeline/plan` rows gained `rebuildEvery`.
- **Routes**: `GET /api/ai/jobs`, `GET /api/ai/batches/{id}`, `DELETE /api/ai/queue/{id}`, `POST
  /api/ai/batches/{id}/cancel` (a bodyless POST — the transport now omits the content type and body when empty);
  the recommendations view gained `aiPending`. `JobView`/`HistoryDto` also carry the model's display name, so
  the page never maps ids to names itself.
- **Admin**: the *AI · sent to Anthropic* card; the conversations panel is filled only on a click and never
  redrawn by the poll, so a request left open (native `<details>`) stays open; the two poll-drawn parts redraw
  only when their data (or, for the job lines, the minute) changes. `formatClock`/`prettyJson` in `JsInterop`.
- **Found on the way, fixed here:** `#/activity?view=jobs` never opened the tab — only the design mockup read it.
  And the admin runs every page in one app-wide `MainScope` that is never cancelled, so Activity's pollers
  stopped only on flags (the health poll's was never cleared): every visit left one more loop polling after you
  left. Each poll now stops once its own render is gone (`activityGen` + the view still being in the DOM). The
  tab badge is refreshed every 10 s from the Scan console too (FR-272-11 needs it; it was only ever updated while
  *Jobs & workers* was open).
- **Tests**: `AiJobsTest` 17 → 27 — a request made while a batch is out waits and goes in the same poll as the
  read (acceptance 1), newest wins per subject, the limit's prefix and raising it (3), five batches kept (4), the
  verdicts incl. an errored line (5), cancel with a partly cancelled batch (6), the request byte-identical to
  270's and free of the label (8), off ⇒ nothing queued; plus the cadence rule (2). Full suite 654 + 63 shared.
- **Verified in a browser** against the e2e stack's backend on this build with `/api/ai/*` answered in the page
  (no Anthropic): card, badge (lanes + AI), *Remove*, *Cancel at Anthropic* → *cancelling…*, the history row, a
  conversation that stays open across polls with the answer pretty-printed, a batch still out showing *waiting for
  the answer*, `?view=jobs` by fresh load, hash change and link, and the AI tab showing **13:29** for a batch
  sent at 11:29:56 UTC in a Copenhagen browser (acceptance 7).

> *"The jellystructure looked like it got finished right away, if this is a long running process. Maybe we
> should make it into a queue and have it in the activity jobs page?"*
>
> *"Yes, and maybe keep history of last 5 ai sessions, so we can see the ai chats?"*

## Today

What happened on production on 2026-09-27, the first time AI ran:

1. The owner started a run by hand from the pre-run dialog with only `build_recommendations` ticked. The step
   answered `not due — built 14 h ago, rebuilt weekly` and the run ended: a run started by hand obeys the
   weekly cadence exactly like the scheduled one, so it did nothing and said so only in the log.
2. The owner then pressed *Rebuild now* on their own Recommended list (Users & devices). The page redrew at
   once as if finished. One re-rank request went to Anthropic as a batch at 13:29:56 and was read back at
   13:33:49 — about four minutes, most of it the runner's five-minute poll. Nothing in the admin showed that
   anything was still out except the AI tab's *Last run* line, which read *"sent 11:29"*: the server formats
   the time itself, in the container's zone (UTC).
3. The answer was used (50 picks, 46 with the model's reason, 4 topped up from 269's order;
   8 977 input / 1 027 output tokens, $0.0071 on Claude Haiku 4.5). Neither what was asked nor what came back
   can be seen anywhere: the request is built, sent and forgotten, and the answer is applied and dropped.

Found while reading the runner for this spec — a real defect, not only a missing view:

4. **A request made while a batch is out is dropped without a word.** `AiJobs.submitRerank` returns `false`
   when a re-rank batch is already pending. So a second viewer's *Rebuild now*, or 269's weekly build itself,
   during those minutes sends nothing and records nothing; and a viewer whose first build was dropped this way
   is stamped as *asked* and not asked again for a day (`AI_RETRY_SEC`). `submitThemes` has the same early
   return.

## What changes

### §A — A queue, so nothing is dropped

- **FR-272-1 — One persisted queue of AI requests.** A new `ai_queue` table, one row per *(job, subject)*: the
  subject is a viewer and scope for a re-rank, a title for themes. A row carries what the request needs to be
  built when it is sent (for a re-rank, 269's shortlist and the watched ids as they were at the build), a
  **label** for the admin (the viewer's name, or the title), **why** it was queued (*weekly build*,
  *Rebuild now*, *first build*, *untagged title*), who queued it (the admin's name for *Rebuild now*, else
  *jellystructure*) and when. It survives a restart.
- **FR-272-2 — Newest wins per subject.** Queuing a subject that is already waiting replaces that row (its
  payload, reason and time): the shortlist 269 built last is the one worth paying for. A subject that is in a
  batch still out may be queued again; it goes in the next batch, and because batches of one job are sent one
  at a time (FR-272-3) its answer is applied after the older one, never before.
- **FR-272-3 — At most one batch per job out; the queue goes the moment it can.** When a job has no batch out,
  everything waiting for it is sent as one batch, oldest first — immediately on queuing (no poll wait), and
  again the moment a batch of that job is read back. The early returns that dropped requests (Today, 4) are
  gone.
- **FR-272-4 — The monthly limit takes what fits.** 270's rule stands — nothing is sent that could pass the
  job's limit — but it is applied to the queue as a prefix: requests are taken oldest first while the batch's
  worst case still fits under *limit − spent*; the rest **stay queued** and the job's line says so
  (*"4 waiting — this month's limit: $0.12 left, the next request could cost up to $0.03"*). Raising the limit,
  or the month turning, sends them. Nothing is thrown away because of the limit.
- **FR-272-5 — A read is noticed within a minute.** While a batch is out it is polled every **60 s** (was five
  minutes); with nothing out the runner only looks at its own queue.
- **FR-272-6 — *Rebuild now* always asks.** The admin's *Rebuild now* queues a re-rank for that viewer whenever
  the re-rank job is on, even if the viewer already has an AI order — it is an explicit request, and it costs
  cents under a limit the admin set. The finish-triggered rebuild (269's stale path) keeps 270's FR-270-7 rule:
  only a viewer's first build is re-ranked, at most once a day.
- **FR-272-7 — A run started by hand builds.** A pipeline run started by hand (the pre-run dialog, *Run
  pipeline*) with `build_recommendations` ticked rebuilds every viewer's list regardless of the cadence;
  scheduled and startup runs keep the cadence (269, FR-269-8). The step's row in the pre-run dialog says
  *"rebuilds now — the schedule rebuilds {weekly}"*. The weekly build queues a re-rank for every viewer it
  built (FR-272-1), so this is also how the admin asks AI to re-rank the whole household.

### §B — Seeing it: Activity ▸ Jobs & workers gets an **AI** card

- **FR-272-8 — The card.** Below *Queues*, a card titled **AI · sent to Anthropic**, shown whenever AI is
  switched on or has any history (hidden entirely otherwise). One line per job — *Re-rank Recommended* and
  *Theme tags* — in the lanes' idiom: a dot (idle · *waiting on Anthropic*), the model, and chips *N waiting* ·
  *1 batch out* · *$0.01 of $5.00 this month*. An AI batch uses **no job worker** and says so (*"runs at
  Anthropic — no worker here is busy"*), so the pool line above it is not misread.
- **FR-272-9 — Waiting requests.** Under its job line, each waiting request: the label, why, by whom, queued at,
  and **Remove**. More than five collapse to the first five and *and N more*.
- **FR-272-10 — The batch out.** Sent at, how long ago, how many requests, and Anthropic's own counts as of the
  last poll (*processing · succeeded · errored · canceled · expired*), plus **Cancel at Anthropic**: the batch
  is cancelled there, answers already produced are still read, applied and paid for, and the cancelled ones
  keep the standard list. The line after a cancel reads *"cancelling — Anthropic finishes what it started"*
  until the batch ends.
- **FR-272-11 — The tab badge counts AI too.** *Jobs & workers*' badge adds waiting AI requests and batches
  out, so a request in flight is visible from the Scan console view.
- **FR-272-12 — *Rebuild now* says it isn't finished.** The Recommended panel's header gains, while that
  viewer has a re-rank waiting or out: *"AI re-rank waiting for Anthropic since 13:29 — Activity ▸ Jobs &
  workers"* (a link to `#/activity?view=jobs`). When the job is off it says nothing about AI (269's panel as
  today). The recommendations view carries this as an added, nullable field; nothing is removed.

### §C — The last five conversations

- **FR-272-13 — Kept per request.** For the **five most recent batches** (both jobs together; a sixth read
  deletes the oldest batch's record and its transcripts), each request keeps: its label, the model and effort,
  **the exact system prompt and user message sent**, the answer **exactly as returned** (text, stop reason,
  result type), the verdict (FR-272-14), its tokens and cost, and a readable form of what was applied — for a
  re-rank the picks numbered with their titles and reasons, for themes the tags. A request still out shows
  its prompt and *waiting for the answer*.
- **FR-272-14 — A verdict in words.** Each answer is marked *used* or *kept the standard list*, with why:
  *not valid JSON* · *more than 50 picks* · *an id not on the shortlist (t123)* · *a title picked twice* ·
  *stopped at the output limit* · *refused* · *expired* · *cancelled* · *errored*. A used re-rank also says how
  many picks were topped up from 269's order (*"used · 46 picks, 4 topped up"*). The validators return this
  reason instead of a bare `null`; what they accept does not change.
- **FR-272-15 — Where it is read.** The AI card's **History** lists the five batches newest first (when, job,
  model, requests, outcome, cost, how long it took); a batch opens to its requests (label · verdict · tokens ·
  cost); a request opens to three blocks — **System**, **Sent**, **Answer** (JSON pretty-printed) — and the
  readable picks. Transcripts are fetched only when a batch is opened.
- **FR-272-16 — What this stores, and where it stays.** The transcripts hold what already left the house
  (FR-270-8: titles, years, genres, keywords, synopses, what a viewer watched) and the admin-only label — a
  viewer's name is stored beside the request, **never inside it**; the request sent to Anthropic is byte for
  byte what 270 sends today. The store is admin-only and bounded to five batches.

### §D — Times in the admin's own zone

- **FR-272-17 — The server sends instants; the page formats them.** Every AI time the admin shows (the AI
  tab's *Last run*, the card, the history, *Rebuild now*'s line) is formatted in the browser from epoch
  seconds. The stored run lines no longer contain a clock time (*"sent · 1 viewer · waiting for Anthropic"*,
  *"ran · 1 viewer · $0.01"*); `lastRunAt` already carries the instant. The monthly limit's month stays the
  server's calendar month (FR-270-6), stated as such on the card.

## API (all admin, all additive)

- `GET /api/ai/jobs` → per job: `job`, `label`, `on`, `model`, `spentMicroUsd`, `limitMicroUsd`, `waiting`
  (rows: `id`, `label`, `reason`, `by`, `queuedAt`), `heldBack` (the FR-272-4 sentence or null), `out` (null or
  `id`, `sentAt`, `requests`, `counts`, `cancelling`); plus `history` (five batches: `id`, `job`, `model`,
  `sentAt`, `endedAt`, `requests`, `outcome`, `costMicroUsd`). Reads only the database — never Anthropic — so
  the Activity view can poll it with the lanes.
- `GET /api/ai/batches/{id}` → that batch's transcripts (FR-272-13).
- `DELETE /api/ai/queue/{id}` → removes one waiting request (404 once it has been sent).
- `POST /api/ai/batches/{id}/cancel` → Anthropic's `POST /v1/messages/batches/{id}/cancel`; 409 if the batch
  has already ended.
- `GET /api/tv/admin/users/{userId}/recommendations` (and its rebuild) gain `aiPending`: null, or
  `{state: "waiting"|"out", since}`.

## Storage

Migration 57: `ai_queue` (FR-272-1, unique on *(job, subject)*); `ai_batch` gains `ended_at`, `outcome`,
`cost_micro_usd`, `counts` (Anthropic's last `request_counts`, JSON) and `cancelling`; `ai_transcript` keyed
*(batch_id, custom_id)* (FR-272-13). Existing `ai_batch` rows keep working: a `submitted` row from before the
migration is polled and read as today and simply has no transcript.

## Acceptance

1. With a re-rank batch out, *Rebuild now* on a second viewer shows the request **waiting** on the card; when
   the first batch is read, it is sent within the same poll, and both viewers end with an AI order.
2. A manual run with only `build_recommendations` ticked, a day after the last build, rebuilds and queues one
   re-rank per viewer; the scheduled run the same hour still says *not due*.
3. With $0.02 left of the limit and three requests waiting whose worst case is $0.03 each, nothing is sent and
   the card says why; raising the limit sends them without another click.
4. After a batch is read, its history entry opens to each request's System / Sent / Answer, the verdict and the
   readable picks; after six batches only the newest five are kept.
5. An answer naming an id not on the shortlist is *kept the standard list — an id not on the shortlist (tNN)*,
   and the viewer's list is 269's.
6. *Cancel at Anthropic* on a batch out ends it; answers already produced are applied and counted in the month's
   spend.
7. The AI tab, the card and *Rebuild now* all show 13:29 for a batch sent at 13:29 in the browser's zone, on a
   server whose container runs in UTC.
8. Nothing sent to Anthropic changes: a request built for the same inputs is byte-identical to 270's.

## Non-goals

- No live streaming of a batch's progress beyond Anthropic's own counts; no per-request cancel inside a batch.
- No transcripts for batches read before this phase; no export.
- No change to what either job sends, how answers are judged, or how 269 builds and uses an order.
- The AI card does not appear in the Scan console's step list — a batch is not a pipeline step.
