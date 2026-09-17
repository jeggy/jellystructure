# Phase 131 — IMDb ratings: ingest from imdbapi.dev, store & sync, show on the detail page

> For titles that carry an **IMDb id**, fetch the **aggregate rating + vote count** from
> **[imdbapi.dev](https://imdbapi.dev)**, **store it on the item**, and refresh it on a **periodic sync** —
> never query the API ad-hoc at render time. Show it on the **admin detail page** and expose it on the
> **Ravilo TV detail DTO** so the companion app can display it
> ([R164](../ravilo/requirements/phase-R164-imdb-rating.md)). Titles with no IMDb id (or not yet synced)
> simply have no rating and every surface hides the affordance.

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — **design built** (admin `#imdb-card` + pagebar pill), backend + sync + DTO unbuilt.

## Problem
Jellystructure stores no external **quality** rating. TMDB has a vote average, but the number viewers know
is **IMDb**'s. imdbapi.dev exposes it keyed by IMDb id as `{ aggregateRating, voteCount }`, but nothing
fetches, stores, or displays it — and it must **not** be called per-request (rate limits, latency, and the
Ravilo detail must stay catalog-only / zero-external-call at read time).

## Goal
A small, **stored** IMDb rating per title, kept fresh by a **scheduled sync** (like the scan pipeline),
surfaced read-only on the admin detail page and on the TV detail DTO. One rating per title.

## Requirements

### FR-131-1 — Source the IMDb id
1. ~~Store `MediaItem.imdbId`~~ **Already exists** (dev review 2026-07-03): `MediaItem.imdbId`
   (`model/Media.kt`) has been populated from TMDB `external_ids` at every scan/sync/re-pull site for
   many phases and already drives the admin pagebar's IMDb external link. This requirement reduces to:
   **reuse it**. No IMDb id ⇒ no rating fetch, no rating.

### FR-131-2 — Fetch + store from imdbapi.dev
2. For each title with an `imdbId`, call **imdbapi.dev** and store the response on the item:
   `MediaItem.imdbRating = { aggregateRating, voteCount, syncedAt }` (additive/nullable, JSON-blob pattern —
   no migration). `aggregateRating` is the 0–10 IMDb average; `voteCount` is the raw count. A failed/absent
   lookup leaves the previous value intact (never blanks a good rating on a transient error) and records the
   attempt.

### FR-131-3 — Periodic sync (not ad-hoc)
3. Refresh ratings on a **schedule**, not per request — a new **"Sync IMDb ratings"** pipeline block /
   scheduled job (sits alongside the Phase 91 scan-pipeline blocks; cadence configurable in Settings, e.g.
   weekly). It walks titles with an `imdbId`, throttled/batched to respect imdbapi.dev, and updates
   `imdbRating.syncedAt`. A manual **Re-sync** on the detail page refreshes one title on demand.

### FR-131-4 — Show it on the admin detail page
4. Surface the rating on **Movie** (`media.html`) and **Series** (`series.html`) detail:
   - a compact **pagebar pill** — `IMDb ★ <rating> · <votes>` (linking to `imdb.com/title/<id>`), beside the
     TMDB-matched / certification badges;
   - an Overview **IMDb rating** card — the score `<rating>/10`, the vote count, the **IMDb id** (link),
     **synced <ago>**, and a **Re-sync from imdbapi.dev** action. Both hidden / empty-state when the title
     has no `imdbId` or no rating yet.

### FR-131-5 — Expose it on the Ravilo TV DTO
5. Add `imdbRating` (`{ aggregateRating, voteCount }`, null when absent) to `MovieDetail` / `SeriesDetail`
   **only**, populated by `DetailService` from the stored `MediaItem.imdbRating` — **catalog-only, zero
   external calls at read time** (the value is already on the item). R164 renders it. *(Dev review
   2026-07-03: the original "add to `MediaCard` where useful" is dropped — R164 is detail-hero-only, and
   `MediaCard` rides every home/browse/channel feed row, so a speculative field there is pure payload
   bloat; add it in the future phase that actually puts a rating badge on tiles.)*

## Invariants
- **Stored + scheduled, never ad-hoc** — the API is called by the sync job (and manual Re-sync), not on
  detail/feed reads; the TV DTO is populated from stored data only.
- **Keyed by IMDb id** — no id ⇒ no rating; every surface is conditional on the rating existing.
- **Additive/nullable fields, no migration** (same JSON-blob pattern as Phase 108 / 130).
- **Transient failures preserve the last good value**; `syncedAt` records freshness.
- One **aggregate** rating per title (the show rating for a series), 0–10 scale + raw vote count.

## Out of scope
- **Per-episode** IMDb ratings (series shows the show-level rating only).
- Rotten Tomatoes / Metacritic / TMDB-vote display — **IMDb only** this phase.
- Writing the rating into the **NFO** or back to Jellyfin — it's a jellystructure/Ravilo display value.
- A Library **sort/filter by IMDb rating** (possible follow-up; not this phase).
- Bundling IMDb's logo artwork — use a plain **"IMDb"** text mark + star (data attribution, not their logo).

## Source references
- Design: `design/app/media.html` + `design/app/series.html` — the pagebar `.imdb-pill` and the Overview
  `#imdb-card` (score · votes · id link · synced · Re-sync) + `.imdb-*` CSS.
- Backend: `MediaItem.imdbId` + `MediaItem.imdbRating` (additive/nullable); an **imdbapi.dev** client
  (`GET` rating by id → `{ aggregateRating, voteCount }`); a scheduled **IMDb-sync** job / Phase-91 pipeline
  block + Settings cadence; `DetailService` DTO population; per-title manual Re-sync route.
- Related: **[R164](../ravilo/requirements/phase-R164-imdb-rating.md)** (Ravilo detail rating display — the
  primary consumer), **Phase 106 / R153** (ingest→DTO→render pattern), **Phase 91** (scan/sync pipeline this
  hooks a block into), **Phase 108** (additive JSON-blob fields, no migration).

## Dev-review addenda (2026-07-03 — backend decisions the design mock couldn't know)

1. **`imdbId` already exists** — see FR-131-1; only `MediaItem.imdbRating` is new.
2. **Concrete API:** `GET https://api.imdbapi.dev/titles/{imdbId}` → read `rating.aggregateRating` +
   `rating.voteCount` (a batch endpoint, `/titles:batchGet`, exists — verify its per-request id cap at
   implementation time and prefer it for the bulk sync). Free, no API key.
3. **New outbound client = Phase 129 mandate:** the imdbapi.dev client MUST use the shared
   `OutboundHttp.client` and wrap every call in `OutboundHttp.withPermit` — no new `HttpClient(Curl)`
   (the FD-budget rule from Phase 118/129). Throttle the bulk sync modestly (e.g. small batches + delay)
   on top of the permit gate.
4. **Scheduling anchor:** the periodic sync is a new **`PipelineStep` kind** in `cfg.scan.pipeline`
   (Phase 91) executed by `executePipeline` in `Main.kt`'s scheduler loop — NOT a second scheduler.
   Admins who don't add the step still get the manual per-title Re-sync. Default cadence when the step is
   enabled: whatever the pipeline schedule fires (the spec's "weekly" is a Settings suggestion, not a new
   config axis).
5. **Explicit routes:** `POST /api/media/{id}/imdb-rating/sync` (manual per-title Re-sync; responds with
   the stored rating-or-null) — records to `MediaHistory`.
6. **Series id note:** for a series, `imdbId` is the **show** id (that is what the scanner stores from
   TMDB TV `external_ids`) — consistent with the show-level-rating invariant.
