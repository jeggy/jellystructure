# Phase 158 — IMDb ratings: in-house imdb.com crawler (replace the dead imdbapi.dev source)

> The third-party API **imdbapi.dev** that Phase 131 fetches IMDb aggregate ratings + vote counts from
> **has shut down** — the domain no longer resolves (DNS `ENOTFOUND`), so every `sync_imdb_ratings` run
> and manual Re-sync is currently a silent no-op. Rather than chase another third-party API, this phase
> replaces the fetch with a **self-hosted crawler that reads the rating straight off `imdb.com`** — the
> title page's embedded JSON-LD block — from inside the jellystructure backend. **Nothing else about
> the subsystem changes**: the `ImdbClient.getRating(imdbId): ImdbFetchedRating?` contract, the stored
> `MediaItem.imdbRating` model, the scheduled `sync_imdb_ratings` pipeline step, the manual per-title
> Re-sync route, and the Ravilo detail DTO all stay exactly as Phase 131 built them — only *how the
> value is obtained* changes.

## Status
Planned — investigation complete (web-verified + codebase-mapped). Modifies **Phase 131**'s FR-131-2
data source only; supersedes the `imdbapi.dev` API choice. Follows the touch-point-map + config-safety
discipline of **Phase 132** (RapidAPI removal), though this is a much smaller blast radius — the whole
IMDb subsystem *surface* is retained, only the fetch internals are rewritten.

## Problem — verified
- **imdbapi.dev is gone.** `https://imdbapi.dev` and `https://api.imdbapi.dev/titles/tt0111161` both
  fail DNS resolution outright (not a 5xx, the host simply no longer exists). The current
  `ImdbClient(baseUrl = "https://api.imdbapi.dev")` (`imdb/ImdbClient.kt:29`) therefore fails every
  call, returns null, and — correctly, per its best-effort contract — leaves stored ratings intact. So
  there is **no data corruption, just permanently stale ratings** and a dead pipeline step. Nothing new
  gets rated, nothing already rated changes.
- **The rest of the subsystem is healthy and worth keeping.** The stored-and-scheduled design (never
  fetched at read time), the small-pool throttle, the manual Re-sync, and the catalog-only DTO
  population are all fine — they just have no working source behind them.

## Goal
A drop-in replacement source: `ImdbClient.getRating(imdbId)` fetches `imdb.com`'s title page, extracts
the aggregate rating + vote count from the page's machine-readable JSON-LD, and returns the same
`ImdbFetchedRating?` (null on any failure). One rating per title, still stored, still refreshed only by
the scheduled sync + manual Re-sync — never at detail/feed-read time. Every downstream surface (admin
pill/card, Ravilo DTO) keeps working unchanged the moment the source is live again.

## Current state (verified against live code — the surface a crawler slots into)
- **`ImdbClient`** (`src/linuxX64Main/kotlin/dev/jellystructure/imdb/ImdbClient.kt`, 45 lines): one class,
  one method `suspend fun getRating(imdbId: String): ImdbFetchedRating?`, riding the shared
  `OutboundHttp.client` + `OutboundHttp.withPermit` (the Phase 118/129 FD-budget mandate — no new
  `HttpClient`). Best-effort, **null on any failure/missing rating** so callers leave the stored value
  intact on a transient error. **This exact surface is preserved; only the body changes.**
- **Model** (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt:22,260`):
  `ImdbRating(aggregateRating: Double, voteCount: Long, syncedAt: Long)` + nullable `MediaItem.imdbRating`.
  **No model change.**
- **Scheduled step** `sync_imdb_ratings` (`Main.kt:697-712`): filters the working set to items with a
  non-blank `imdbId`, fans them into a worker pool capped at **1–2 concurrent**
  (`PipelineStepPool.kt:156-158`, deliberately small), each worker calls
  `PipelineStepOps.syncImdb(item, store, imdbClient)` then `delay(250)`. **This throttle model is right
  for a scraper and stays** (the existing "no verified batch endpoint → keep the pool small" reasoning
  transfers directly; a scraper wants *more* politeness, not less).
- **Manual Re-sync** (`server/routes/MediaRoutes.kt:1558-1573`): `POST /api/media/{id}/imdb-rating/sync`,
  records to `MediaHistory`. Unchanged — it calls the same `getRating` surface.
- **DTO population** (`tv/DetailService.kt:242-244`, mirrored `tv/BrowseService.kt:85`): reads the stored
  `imdbRating` straight off `MediaItem`, catalog-only, zero external calls at read time. Unchanged.

## Requirements

### A. Crawl the title page instead of a JSON API
#### FR-158-1 — Fetch `https://www.imdb.com/title/{imdbId}/` with browser headers
`getRating(imdbId)` fetches the IMDb title page over the shared `OutboundHttp.client`
(`OutboundHttp.withPermit`), reading the response as an **HTML text body** (`bodyAsText()`), not JSON
content-negotiation. **Browser-like request headers are mandatory** — a bare Ktor/Curl GET returns
**HTTP 403** (verified live); the shared client sets none today, so this call must add them per-request:
- `User-Agent`: a real desktop-browser string.
- `Accept-Language: en-US,en;q=0.9` — also pins rating field values to a stable English response.

### B. Extract the rating from JSON-LD (native-safe parsing)
#### FR-158-2 — Regex-isolate the JSON-LD block, then JSON-parse it
This is Kotlin/Native (linuxX64) — **there is no HTML DOM parser available** (jsoup and every JVM HTML
parser are JVM-only; nothing native-compatible exists on the classpath). Parsing is therefore:
1. **String/regex-extract** the JSON-LD script block(s) — `<script type="application/ld+json"> … </script>`
   — from the HTML text. Do **not** regex the rating numbers directly out of HTML.
2. **JSON-parse the extracted block's inner text** with `kotlinx.serialization.json.Json`
   (`ignoreUnknownKeys = true`, already the classpath norm) into a small `@Serializable` DTO — the same
   technique the old `ImdbClient` already used, just against the JSON-LD shape:
   ```
   aggregateRating.ratingValue   → the 0–10 average (Double)
   aggregateRating.ratingCount   → the vote count
   ```
3. A page can contain **multiple `ld+json` blocks** — select the one whose `@type` is `Movie`/`TVSeries`
   (or, pragmatically, the first block that actually contains `aggregateRating`), never blindly the
   first script tag.

**Primary source is JSON-LD** (a tiny, flat, redesign-stable block, path one level deep). The Next.js
`<script id="__NEXT_DATA__">` blob (`props.pageProps.aboveTheFoldData.ratingsSummary.aggregateRating` /
`.voteCount`) carries the same data but via a 5-levels-deep, more fragile path — keep it in mind as a
**fallback only** if JSON-LD extraction ever comes back empty on a page that clearly has a rating.

#### FR-158-3 — Parse `ratingCount` defensively (the single most likely parser bug)
IMDb's Schema.org JSON-LD *should* emit `ratingCount` as a **plain integer**, but this could not be
byte-confirmed during research (bare requests 403; only rendered views were reachable, which show the
abbreviated `3.2M` display form). The parser must therefore be **tolerant**: accept `ratingCount` as
either a bare number **or** a string, and normalize (strip commas) rather than hard-binding to `Long` —
so a `"3,012,345"` never silently becomes a fetch failure. **Verify the actual type against one live
server response at implementation time and pin the parser to reality.** `ratingValue` is a reliable
JSON number.

#### FR-158-4 — A missing rating block is "no rating", not an error
Titles with too few votes have **no `aggregateRating`** in the JSON-LD. That case (and any parse miss)
returns null — indistinguishable from any other "no rating", leaving the stored value intact. Same
null-on-failure contract Phase 131 established.

### C. Politeness, resilience, and the compliance posture
#### FR-158-5 — Treat anti-bot responses as transient; keep the throttle small
`imdb.com` sits behind Amazon anti-bot: bulk automated load draws **403 / 429 / 503 / CAPTCHA-HTML**,
especially from datacenter IPs. The crawler must:
- **Keep (or tighten) the existing throttle** — the `sync_imdb_ratings` step's 1–2-worker pool +
  `delay(250)` per title (`Main.kt:707`, `PipelineStepPool.kt:157`). Consider dropping to a single
  worker and adding **jittered backoff** on the bulk path; a big library refreshed in one burst is the
  risk scenario, and processing time is not a concern for this background job.
- **Classify a 403/429/503/CAPTCHA page as a transient failure** — return null, leave the stored rating
  intact, record the attempt. A blocked fetch must never blank a good rating (Phase 131 invariant).
- Detect a CAPTCHA/interstitial HTML body (no JSON-LD rating block present) and treat it as transient,
  not as "title has no rating."

#### FR-158-6 — Document the robots.txt / ToS posture openly
`imdb.com/robots.txt` `Disallow: /` under `User-agent: *` — generic (non-whitelisted) crawlers are
disallowed from all `/title/` paths, and automated scraping is contrary to IMDb's Conditions of Use.
This spec states that plainly as a **known posture the operator accepts for self-hosted, personal-scale,
low-volume use** — the mitigations in FR-158-5 (tiny concurrency, per-title delay, jitter, transient-
failure handling, no bulk bursting) are the courtesy that makes that posture defensible for a homelab,
not a claim that scraping is permitted. Data attribution stays a plain **"IMDb"** text mark + star
(Phase 131 already avoids bundling IMDb's logo).

### D. Configuration + removal hygiene (Phase 132 discipline)
#### FR-158-7 — Retire the dead `imdbapi.dev` base URL cleanly
The `ImdbClient` base URL flips from `https://api.imdbapi.dev` to the `imdb.com` title-page host. If any
config key referenced the old API host/base, follow Phase 132: ensure the tolerant TOML parser
(`config/ConfigStore.kt`, `TomlInputConfig(ignoreUnknownNames = true)`) is in place **before** removing
a key, so a stale `imdbapi`-shaped key in a live `config.toml` can never throw-and-reset the operator's
entire config to defaults. Keep code and design mockups in step (the admin "Re-sync from imdbapi.dev"
button label in `design/app/media.html` + `MediaDetail.kt:227,246` should read **"Re-sync from IMDb"**,
not name the dead service) so the next "updated designs" sync doesn't re-introduce the old wording.
Existing stored `imdbRating` values are **kept** (they simply resume refreshing) — the inverse of Phase
132's stale-row cleanup.

## Invariants (from Phase 131, preserved)
- **Stored + scheduled, never ad-hoc** — the crawler is called only by the sync step + manual Re-sync,
  never on detail/feed reads; the DTO is populated from stored data only.
- **Keyed by IMDb id** — no `imdbId` ⇒ no fetch, no rating.
- **Transient failures preserve the last good value**; `syncedAt` records freshness.
- **Rides the shared `OutboundHttp` client + `withPermit`** — no new `HttpClient` (FD-budget mandate).
- One aggregate rating per title (show-level for a series — `imdbId` is the show id).
- Additive/nullable model, no migration (nothing about the model changes at all here).

## Out of scope
- **No new third-party API** — the whole point is to stop depending on one.
- **No IMDb GraphQL** — the anonymous `api.graphql.imdb.com` endpoint uses **rotating persisted-query
  hashes** (breaks silently on IMDb's deploy schedule, needs live browser inspection to recover); the
  official IMDb GraphQL API is a paid enterprise product (~$150k/yr, no free tier). The title-page +
  JSON-LD scrape is the only realistic self-hosted route.
- **No headless browser / JS execution** — the JSON-LD is in the initial HTML; a static fetch + parse
  is enough, and a browser engine is not shippable in this native backend anyway.
- **No per-episode IMDb ratings, no RT/Metacritic/TMDB-vote display, no NFO/Jellyfin write-back, no
  Library sort/filter by rating** — all remain Phase 131's out-of-scope items.
- **No change to the sync cadence model** — it stays a `sync_imdb_ratings` pipeline step (Phase 131
  FR-131-3); this phase does not add a second scheduler.

## Acceptance
- With imdbapi.dev dead, `getRating("tt0111161")` returns the current IMDb rating + vote count for
  Shawshank (≈9.3 / millions of votes) by parsing the live title page's JSON-LD — where the old client
  returns null.
- A bare fetch without browser headers is observed to 403; the crawler's headered fetch succeeds.
- `ratingCount` parses correctly whether IMDb emits it as an integer or a comma-formatted string.
- A title with too few votes (no `aggregateRating` block) returns null and leaves any stored rating
  untouched; a 403/CAPTCHA response likewise returns null without blanking.
- The scheduled `sync_imdb_ratings` step and the per-title Re-sync route both resume working with **no
  code change to themselves** — only `ImdbClient`'s internals changed.
- The admin pill/card and Ravilo detail DTO show real ratings again, populated from stored data, with
  zero external calls at read time.
- No `config.toml` referencing an old `imdbapi` key resets to defaults on load.

## Source references
- Client to rewrite (surface preserved): `src/linuxX64Main/kotlin/dev/jellystructure/imdb/ImdbClient.kt`.
- Shared HTTP client + FD gate the crawler must use: `src/linuxX64Main/kotlin/dev/jellystructure/OutboundHttp.kt`
  (add per-request `User-Agent`/`Accept-Language`; read `bodyAsText()`).
- Untouched downstream: model `model/Media.kt:22,260`; step `Main.kt:697-712` +
  `media/PipelineStepPool.kt:156-158` + `media/PipelineStepOps.kt:47-52`; route
  `server/routes/MediaRoutes.kt:1558-1573`; DTO `tv/DetailService.kt:242-244`, `tv/BrowseService.kt:85`.
- Removal-discipline precedent (touch-point map + tolerant TOML parser + code/design-in-step):
  `specs/requirements/phase-132-remove-rapidapi.md`.
- Phase this modifies: `specs/requirements/phase-131-imdb-ratings-ingest.md` (FR-131-2 data source);
  its Ravilo consumer `specs/ravilo/requirements/phase-R164-imdb-rating.md` is unaffected.
- Parsing library (only native-safe option): `kotlinx.serialization.json` (already on the classpath;
  no HTML DOM parser exists for Kotlin/Native — regex-isolate the `ld+json` block, then JSON-parse it).

## Relationships
- **Fixes Phase 131** — same subsystem, working source. R164 (Ravilo rating display) needs no change.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the row
  from the design side.**
