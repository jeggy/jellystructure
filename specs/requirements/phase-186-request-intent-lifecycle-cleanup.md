# Phase 186 — a request must be able to end: lifecycle reconciliation for Discover requests

> Requested 2026-09-04: *"Please remove Lokkeduerne and Digger from this list"* (the Ravilo Discover ▸
> Request ▸ **"In progress"** rail on ravilo.example.net) → after a manual three-system removal → *"Let's
> write a spec for this, so it won't happen again. We will need some type of cleanup job within
> jellystructure that checks for stale stuff in this area."*

**Status:** ✓ Built 2026-09-05, same session as this status update — spec'd 2026-09-04, built the next
day after probing Seerr's live API to answer open question 2 first (same discipline this file's own
FR-186-1 asks of it). Compiles clean (`compileKotlinLinuxX64`); full suite green (185/185,
`linuxX64Test`); `verifyCommonMainJellystructureDbMigration` passes. Not dev-reviewed, not live-verified
against a real dead/declined request (every Seerr probe below was run against nonexistent ids or an
already-cleaned-up title specifically to avoid touching this house's real requests). The reported
instance was cleaned up by hand the same day it was reported (see §1); this phase is the systemic fix so
it never has to be done by hand again.

### Open questions, resolved 2026-09-05

Probed live against `stream.example.net` (Seerr 3.4.1) before writing any sweep code, same as Phase
163's/187's standing discipline:

1. **Cadence** — its own independent hourly timer (`AcquisitionConfig.lifecycleSweepHours`, default 1,
   coerced 1–24), not folded into `poll()` (which runs every 3–300s watching the *arr download queue —
   a different timescale and a different question) or a pipeline run (this reconciles against Seerr/*arr
   ground truth and has no reason to entangle with Phase 182's scan Gate classes at all).
2. **Seerr "declined" detection — the API not only exposes it, it's better than assumed.**
   `GET /movie|tv/{tmdbId}}`'s own embedded `mediaInfo` carries a **`requests[]`** array (each with its
   own `id`/`status`) — verified live against a real title with an active request. So classifying a
   single `request_intent` row never needs a separate paginated `GET /request?filter=...` call; the same
   per-tmdbId lookup this codebase already had (`SeerrClient.movieDetails`/`tvDetails`) answers rules 1
   and 2 in one round trip. Two corrections the probe also turned up, both fixed in `SeerrClient.kt`:
   Seerr's media `status` **7** = DELETED, not the previously-documented 6 (no live example of 6 was
   ever seen); and request `status` has a **5** = COMPLETED value the old docstring didn't list (140 of
   219 real requests carried it).
3. **"Viewer hasn't looked at it since"** — dropped, as this file's own fallback suggested: no per-viewer
   last-seen signal exists for the Request tab. A stale FAILED row retires on age alone (rule 4).
   Separately, the literal "non-retryable" half of rule 4 turned out to be unreachable: every FAILED
   `AcquisitionRecord` in this codebase is created with `retryable = true` (`AcquisitionService.fail()`
   and `reconcileMovie`'s FAILED branch both hardcode it; nothing ever sets it false) — so rule 4 is
   implemented as "FAILED past the retention window", full stop, and this deviation from the literal
   wording is called out in `RequestLifecycleService.classify()`'s own comment rather than left silent.
4. **Retention window** — kept the proposed defaults: 14 days (`AcquisitionConfig.requestRetentionDays`),
   3 consecutive sweeps (`AcquisitionConfig.deadSweepThreshold`). No real-world data yet to sanity-check
   against; both are config, adjustable without a redeploy.
5. **Soft-delete vs hard-delete** — soft (`retired_at`/`retired_reason`, migration `39.sqm`) for the
   sweep's own dead-row retirement (FR-186-4's audit trail), matching the file's stated preference.
   `save()` (a fresh request) always clears both, plus `dead_streak` — answers this question's second
   half: yes, a retired row is un-retired the moment it's requested again. **FR-186-6's explicit remove
   action hard-deletes** — by the time that cascade has run and been logged, there's nothing left to
   explain.
6. **Multi-user rows** — accepted as-is, no change: `request_intent`'s `(media_kind, tmdb_id)` PK means
   FR-186-6's remove is global for that title, matching how the table already behaves everywhere else.
7. **The `acquisition` ↔ `request_intent` link** — confirmed: every write site in both
   `AcquisitionService.request()`/`trackSeerrRequest()` uses `itemKey = "tmdb:$tmdbId"` unconditionally,
   never a library `item_key`, before a match exists. No exception found.

### What's built

- `RequestIntent.sq` / migration `39.sqm` — `dead_streak`/`retired_at`/`retired_reason` columns;
  `forUser`/`all` filter `retired_at IS NULL`; new `bumpDeadStreak`/`resetDeadStreak`/`retire`/`deleteOne`.
- `SeerrClient.kt` — `SeerrMediaInfo` gains `id`/`requests[]` (see OQ2); `SeerrRequestResult` gains
  `type`; new `declineRequest`/`deleteRequest`/`deleteMedia`, each verified live against a nonexistent id
  (Seerr's own `{"message":"Request not found."}` shape, distinct from a route-miss 404 — confirms the
  method+path are real without touching a real request).
- `ArrClient.kt` — `movieExists`/`seriesExists` (rule 3's ground truth); `deleteMovie`/`deleteSeries`
  gain `deleteFiles`/`addImportExclusion` params (default `false`, matching prior hardcoded behaviour).
- `AcquisitionService.kt` — `cancel()`'s *arr teardown extracted into public `teardownArr()`, reused by
  the new cascade so a title with no `acquisition` row (Lokkeduerne's shape) still gets the *arr half
  attempted.
- `RequestLifecycleService.kt` (new) — `classify()` (FR-186-2), `sweep()` (FR-186-1 + FR-186-4),
  `sweepOrphanAcquisitions()` (FR-186-5), `removeRequest()` (FR-186-6). Wired + started in `Main.kt`.
- `SeerrDiscoverService.getMyRequests` — FR-186-7's defensive filter: drops a row immediately on
  `NOT_REQUESTED` (Seerr-derived **or** the real linked `acquisition` record), never on `FAILED`.
- `AcquisitionRoutes.kt` / `Server.kt` — `POST /acquisition/request/remove` (FR-186-6), `503` until the
  service is wired, same nullable-optional pattern as every other feature route in this file.

A genuine logic bug was caught by the compiler's own dead-code warning while building this, not by
review: an early draft of `classify()` conflated "the Seerr call itself failed" with "the call succeeded
and Seerr says no record exists" into one `?:` chain, which would have made every network hiccup read as
a dead request. Fixed before this was ever run.

**Prospective number:** admin **186** (next unassigned admin number as of 2026-09-04, verified against
`ls specs/requirements/` + `STATUS.md`; highest existing is 185). No Ravilo (R) number — the missing
mechanism is entirely server-side; the client already renders whatever the server returns.

---

## 1. The reported case

The Request tab's **"In progress"** rail showed two titles that should not have been there:

| Title | TMDB | Where it actually existed | Real state |
|---|---|---|---|
| **Digger** (2026) | 1248832 | `request_intent` row · Seerr media 486 + request 209 (APPROVED/PROCESSING) · Radarr movie 680 (monitored, `announced`, **no file**, nothing in any queue) | A legitimately-pending request for a film **not yet released** (2026-09-30). It had simply been "in progress" for ~2 months and the owner no longer wanted it. |
| **Lokkeduerne** (2018) | 1711816 | `request_intent` row **only** — nothing in Seerr, nothing in Radarr | An **orphan**. The Ravilo request was persisted (or survived) but never propagated to Seerr/*arr, or its Seerr/*arr side was removed at some point. It had been dead on the rail indefinitely. |

Manual removal on 2026-09-04 required **three separate systems**, none of which jellystructure exposes an
action for:

1. **Radarr** (`~/arr`, `:7003`): `DELETE /api/v3/movie/680?deleteFiles=false&addImportExclusion=false`
2. **Seerr** (`:7006`): `DELETE /api/v1/request/209` then `DELETE /api/v1/media/486`
3. **jellystructure**: `DELETE FROM request_intent WHERE tmdb_id IN (1248832, 1711816)` straight against
   the live prod SQLite DB — there is no query, endpoint, job, or admin control that does this.

No files existed on disk for either title, so nothing was deleted there.

---

## 2. What's there now — why a request can never leave the rail

All findings verified against source (`SeerrDiscoverService.kt`, `RequestIntentStore.kt`,
`RequestIntent.sq`, `Acquisition.sq`, `AcquisitionService.kt`, `TvRoutes.kt`, `AcquisitionRoutes.kt`) and
against the running production stack on 2026-09-04.

### 2.1 `request_intent` has exactly one exit condition, and it is not enough

The "In progress" rail is `SeerrDiscoverService.getMyRequests(userId)` (route
`GET /tv/discover/requests/mine`, `TvRoutes.kt:541`). It reads every row from
`requestIntentStore.forUser(userId)` and re-derives each entry's status live. The **only** filter is:

```kotlin
if (acq.status == AcquisitionStatus.AVAILABLE) return@mapNotNull null
```

So a row disappears from the rail **only** when the title becomes available in the library **and** is
matched to it — which, per Phase 181's findings, is itself matched purely on `tmdbId`. Every other
outcome keeps the row forever:

- **Declined in Seerr** — Seerr's flat `MediaInfo.status` "carries no distinct 'declined' signal" (the
  code says so at `SeerrDiscoverService.kt:316`); a declined request reads back as `NOT_REQUESTED`, and
  `getMyRequests` does not drop `NOT_REQUESTED`. The row stays, now rendering a nonsensical state.
- **Seerr media deleted** (an admin tidying Seerr, or the manual cleanup in §1) — same: reads back as
  `NOT_REQUESTED`/absent, row stays.
- **Request permanently FAILED** — e.g. `RequestLanguageService.profileFor` returned null and the strict
  request was rejected (`AcquisitionStatus.FAILED` at `SeerrDiscoverService.kt:250`). If the viewer never
  hits Retry, the row sits on the rail as a failure indefinitely.
- **Acquired under a different id / no id** — Phase 181 established that ~half the library carries junk
  metadata and tmdb matching is imperfect. A requested title that lands in the library without a
  resolvable `tmdbId` never satisfies the `AVAILABLE` check and never leaves.
- **`*arr` entity removed by hand** — Seerr keeps its `media` + `media_request` rows pointing at a
  now-missing Radarr/Sonarr id; jellystructure keeps its `request_intent` row. This is the Lokkeduerne
  shape.

There is no `deleteOne` in `RequestIntent.sq` (only `upsert`, `getOne`, `forUser`) and nothing anywhere
calls a delete — `RequestIntentStore` has no removal method at all.

### 2.2 The `acquisition` table goes stale the same way — with one partial exception

`AcquisitionService.trackSeerrRequest` also writes an `acquisition` row for the same title. The
reconciler (`startReconciler` → `poll()`, `AcquisitionService.kt:187`) already runs continuously, but:

- `poll()` only looks at `store.active()` = status `NOT IN ('AVAILABLE','FAILED','NOT_REQUESTED')`. A
  declined/deleted Seerr request that the reconciler flips to `FAILED` then **stops being polled** and
  lingers as a `FAILED` row forever.
- `poll()` reconciles a movie against the Radarr **queue** and library only. If the Radarr movie is
  deleted out from under it (`arr_id` now 404s), `reconcileMovie` has no "the entity is gone" branch —
  it just keeps reporting the last-known state.
- The one real teardown that exists — `AcquisitionService.cancel(itemKey)` (`POST /acquisition/cancel`,
  admin acquisition surface only) — deletes the `*arr` entity + its queue items and drops the
  `acquisition` row, but **does not** touch `request_intent` and **does not** decline the originating
  Seerr request. Run against a Seerr-originated title it produces exactly the Digger end-state: Radarr
  clean, Seerr and jellystructure both still holding the request.

### 2.3 Age alone is not a signal

Digger is a real, still-wanted-by-the-system request for a film that will not exist for months. Pruning
by "row older than N days" would wrongly retire it (and every pre-order / upcoming-season request). The
job must reconcile against **ground truth** — Seerr and `*arr` — not against a clock.

---

## 3. What this phase adds

### FR-186-1 — a request-lifecycle reconciliation pass

Extend the existing acquisition reconciler (not a new loop) with a **request-intent sweep** that runs on
the same cadence as `poll()` (or once per `RunTarget.Library` pipeline run — dev's call, see open
questions). For every `request_intent` row it resolves the title's true current state from the systems
that own it and classifies each row as **live**, **fulfilled**, **dead**, or **indeterminate**.

### FR-186-2 — the definition of "dead"

A row is **dead** (eligible for retirement) when **any** of these holds, confirmed on **≥ N consecutive
sweeps** (N configurable, default 3 — never on a single observation, so a Seerr/TMDB hiccup can't
retire a real request):

1. Seerr reports the title as **declined** (`media_request` status = declined) or **no request exists**
   for a tmdbId that jellystructure has a `request_intent` row for — i.e. the Seerr side was removed.
2. Seerr `media` row is `DELETED` (status 6) or absent, **and** the title is not present in the library.
3. The `*arr` entity referenced by the linked `acquisition` row (`arr_id`) returns 404, **and** Seerr
   holds no active request for it.
4. The linked `acquisition` row is `FAILED` and non-retryable, **and** older than the retention window,
   **and** the viewer has not opened the Request tab for it since (best-effort — see open questions).

A row is **fulfilled** (also retired, silently) when the title is now in the library under **any**
match signal — tmdbId **or**, when the row's tmdbId doesn't resolve, an exact
`normalize(title) + year` match against `MediaStore` (the Phase 181 lesson: don't trust the id alone).

### FR-186-3 — never retire a legitimately-pending request

A row is **live** — untouched, regardless of age — when there is a Seerr request in
`PENDING`/`APPROVED`/`PROCESSING` **or** a monitored `*arr` entity for it, even with no file and no
queue activity (the Digger case: unreleased, monitored, correct to keep). "In progress" for a
not-yet-released title is a valid resting state.

### FR-186-4 — retirement is audited, not silent (for the non-fulfilled cases)

When the sweep retires a **dead** row (not a plain `AVAILABLE`/fulfilled one), it writes one line to the
requesting title's History / the global Activity log: what was retired, which classification rule fired,
and what ground-truth state was observed (`"Request retired — declined in Seerr 4 sweeps ago; no library
match"`). A fulfilled row retiring is expected and stays quiet, matching today's behaviour.

### FR-186-5 — the `acquisition` table is swept too

The same pass prunes orphaned `acquisition` rows: `FAILED`/`NOT_REQUESTED` rows past the retention
window whose `*arr` entity is gone and whose Seerr request is gone; and it re-widens `poll()` so a row
that flipped to `FAILED` is re-examined once more before it's considered settled (catches "declined,
then re-approved" without needing a page reload).

### FR-186-6 — an explicit "remove this request" action (so §1 is never manual again)

A single admin-triggerable operation — endpoint `POST /acquisition/request/remove` (body:
`{ tmdbId, mediaKind, deleteFiles? = false, addExclusion? = false }`) — that does the **full cascade**
the manual cleanup did:

1. Decline + delete the Seerr `media_request` and `media` rows (Seerr API), when Seerr is the origin.
2. Remove/unmonitor the `*arr` entity and clear its queue items (reuse `AcquisitionService.cancel`'s
   existing `*arr` teardown — it already does this correctly).
3. Delete the `request_intent` row(s) and the `acquisition` row for the title.
4. Write the History/Activity line.

`deleteFiles` defaults **false** — this is "stop wanting it", never "delete my library". This
generalises the existing `/acquisition/cancel` (which today skips steps 1 and 3).

### FR-186-7 — `getMyRequests` filters defensively, before the sweep runs

Independent of the sweep, `getMyRequests` drops a row from the rail immediately when the live
re-derivation already proves it dead this fetch: Seerr media absent/`DELETED` **and** no library match,
or a linked `acquisition` row that is `NOT_REQUESTED`. This is belt-and-suspenders — the rail should
never show a title Seerr has no record of, even in the window before the next sweep. (It must **not**
hide a `FAILED` row — that one the viewer needs to see and Retry.)

### FR-186-8 — schema

`RequestIntent.sq` gains `deleteOne(media_kind, tmdb_id)` and `forUserWithAge` (or a `last_seen_ok`
column) to support the "N consecutive sweeps" counter. Decision for dev review: a hard `DELETE` vs. a
`retired_at`/`retired_reason` soft-delete kept for audit. Soft-delete is preferred (the row is cheap,
and "why did my request vanish?" is answerable), with `forUser` filtering `retired_at IS NULL`. Needs a
matching numbered `.sqm` migration (per the SQLDelight-migration rule — a new column on an existing
table never reaches a non-fresh DB without one).

---

## 4. Non-goals

- **No new Ravilo UI.** The rail already renders server state; this phase only changes what the server
  puts on it. No "your request was removed" toast, no retired-requests screen in Ravilo.
- **No automatic re-requesting** of anything the sweep retires.
- **Never deletes library files.** Every path here defaults to keeping media that has already landed.
- **Not a Seerr reconciler in general.** jellystructure only reconciles titles it has a
  `request_intent`/`acquisition` row for — it does not police Seerr's own catalogue.
- **No change to the request-language intent model** (Phase 139) beyond adding a delete path.
- **No age-based pruning.** Age is never sufficient on its own (§2.3).

---

## 5. Open questions for dev review

1. **Cadence**: fold the sweep into `AcquisitionService.poll()` (every 3–300 s, but it early-returns
   when `active()` is empty — the sweep would need to run even then), or into the `RunTarget.Library`
   pipeline run (once per scan, alongside Phase 181's `sweepJellyfinLibrary()`), or its own slow timer
   (e.g. hourly)? The Library-run hook is the closest structural match to Phase 181.
2. **Seerr "declined" detection**: `SeerrClient` today has no method that reads `media_request` status
   directly — confirm the public Seerr API exposes request status per media (`GET /api/v1/media/{id}`
   or `GET /api/v1/request?filter=...`) before relying on rule 1/FR-186-2. Same discipline as Phase
   163's 405 and Phase 180's stop-call check: verify live first.
3. **"Viewer hasn't looked at it since"** (FR-186-2 rule 4) — is there any per-viewer last-seen signal
   for the Request tab, or should that clause be dropped and FAILED rows retired purely on age + dead
   ground truth?
4. **Retention window N (days)** for FAILED/dead rows, and **sweep count N** before retirement —
   propose defaults (14 days / 3 sweeps) but these want a real-world sanity check.
5. **Soft-delete vs hard-delete** (FR-186-8) — and if soft, is a retired row ever un-retired (title
   re-requested later), i.e. does `save()` need to clear `retired_at`?
6. **Multi-user rows**: `request_intent` PK is `(media_kind, tmdb_id)` — one row, last requester wins
   (`requested_by` is overwritten). FR-186-6's remove is therefore global for that title, not
   per-viewer. Confirm that's acceptable (it matches how the table already behaves).
7. **The `acquisition` ↔ `request_intent` link** is only `tmdb_id` (+ `item_key = "tmdb:<id>"`).
   Good enough for the join, but confirm no Seerr-origin path writes an `acquisition` row with a
   library `item_key` instead before a tmdb match exists.
