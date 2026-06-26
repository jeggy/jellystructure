# Phase R83 — Catalog-only **detail**, with a separate bulk playstate endpoint

> Make the **detail** endpoints (`/api/tv/movie|series`) render from local data with **zero Jellyfin
> round-trips**, and move their per-user state (resume/watched) onto a **separate** bulk request — one
> Jellyfin call per screen — so detail never waits on Jellyfin. Jellyfin stays the sole **owner** of
> per-user state; jellystructure only **reads** it.
>
> **`GET /api/tv/home` is deliberately NOT decoupled.** The Continue-Watching row is the *only*
> per-user element on home, it sits at row 0, and pulling it out of the home response would make it
> **pop in above the catalog after first paint and shove every row down** — the exact flicker/
> auto-appearing-row behaviour we are refusing. Home keeps its Continue row inline (one R76-parallelized
> round-trip; the catalog itself is already local), so home renders in its final layout, no reflow. The
> performance wins come from **detail** (this phase) and **artwork** (R85), not from touching home.

## Problem
Today `GET /api/tv/movie/{id}` and `/api/tv/series/{id}` each make a live Jellyfin call
(`getItemDetail` / `getSeriesEpisodes`) before responding, and `GET /api/tv/home` is **gated** on a
live Continue-Watching fetch (`getResumeItems` + `getNextUp` run inside `buildRows` → `buildContinueRow`
after the top-level `await`, so the whole feed can't return until that round-trip completes — even
though heroes/channels/newly-added/genre rows are pure-local CPU work). R76 already parallelized
`allItems ‖ tvToken` and `getResumeItems ‖ getNextUp` and added the 5-min token cache, but the
catalog response is still coupled to Jellyfin's responsiveness.

After **R82** stores runtime, per-episode Jellyfin id, and season names locally (and movie audio/sub
languages come from local tracks), the two detail Jellyfin calls provide **only per-user state** — so
they can be dropped from the catalog path entirely and that state served separately.

## Architectural constraint (driving decision)
**Jellyfin remains the system of record for per-user state — we never write ownership locally, only
read it.** (See [[ravilo-off-jellyfin-data]], [[fe-reflects-be-no-derived-state]].) Per-user reads are
consolidated into a single bulk call per screen and decoupled from catalog render, so catalog is
instant and playstate hydrates a moment later. Every fan-out / outbound fetch stays **Semaphore-bounded**
per the FD-ceiling constraint ([[reference-ktor-native-fd-setsize]]).

## Current state (as-is)
- `tv/DetailService.kt`: `getMovieDetail` calls `getItemDetail` (`userData` + `runTimeTicks` +
  `mediaStreams`); `getSeriesDetail` calls `getSeriesEpisodes` (per-episode `userData` + `runTimeTicks`
  + `seasonName` + `jfEp.id` for the playable id/still). After R82 the non-userData parts are local.
- `tv/HomeFeedService.kt` `getHomeFeed`: `async { allItems() }` ‖ `async { tvToken() }`, then builds
  heroes/channels/rows; `buildContinueRow` (inside `buildRows`) runs `getResumeItems ‖ getNextUp` and
  the feed is returned only after it completes. **Only the Continue row carries per-user indicators**;
  newly-added/genre/custom tiles have no progress/watched today.
- `auth/JellyfinClient.kt`: `getResumeItems`/`getFavoriteItemIds` already use
  `/Users/{userId}/Items?…&Fields=UserData`; `getItem` uses the `/Items?Ids={id}&Fields=…` list shape
  (with a note that `/Items/{id}` 400s under a server token but `Ids=` is accepted). A **bulk** user-data
  call is the union of these proven patterns.
- 5-min token-validity cache lives in `PlaybackService.tvToken()` (R76).

## Requirements

### A. Detail endpoints become Jellyfin-free (home is left as-is)
1. `GET /api/tv/movie/{id}` and `GET /api/tv/series/{id}`: serve **entirely from `MediaStore`** — card,
   synopsis, runtime (R82), cast (local, R81), related, audio/sub languages (local tracks); for series
   the full season/episode structure with per-episode `jellyfinId` (R82), runtime, title, still, season
   names. **Remove the `getItemDetail`/`getSeriesEpisodes` calls.** No `tvToken`, no Jellyfin RTT.
2. The per-user fields (`playback`, series `progress`, episode `playback`) are no longer populated by
   these endpoints — they move to the playstate endpoint (B). Return them as null/empty placeholders in
   the catalog DTOs (the app overlays them in R84). Keep the DTO contract explicit so R84 knows which
   fields hydrate late.
3. **`GET /api/tv/home` is unchanged** — it keeps building the Continue row inline
   (`getResumeItems ‖ getNextUp`, R76-parallelized) so the row is present in its final position at first
   paint. Do **not** make home catalog-only and do **not** move Continue to a separate request: that
   would make the row pop in after paint and reflow the screen (the flicker we are refusing). Home's only
   per-user element is this row, so there is nothing else to hydrate late there.

### B. New bulk playstate endpoint (for the detail screens)
4. `GET /api/tv/playstate?ids=a,b,c,…` → `{ id → { resumeMs, played, playedPct } }`, backed by **one**
   bulk `GET /Users/{userId}/Items?Ids=…&Fields=UserData` per call (deserialized into the existing
   `JellyfinItem`/`JellyfinUserData`). Uses `tvToken` (the only Jellyfin touch here; behind the 5-min
   cache). This single round-trip replaces N× `getItemDetail` and serves the detail screens' overlays
   (movie Play/Resume label, series episode resume bars + the series `progress` summary).
5. **Chunk ids** to stay under URL-length caps (a long series can be 100+ episode ids; near common
   proxy/Kestrel limits): split into batches (e.g. ≤100/request) and fan them out **bounded by a
   Semaphore** (mirror `buildContinueRow`/`LogoDownloader`), or accept ids via POST body. Document the cap.
6. Series episode playstate: `playstate` for a series must cover its episodes (so the episode rail and
   the series `progress` — watchedCount/resumeEpisodeId/resumeLabel — can be derived). Either accept the
   episode ids in the same call or expose the series' episode userData in the response shape; keep it one
   bulk round-trip.

## Invariants
- **Detail** endpoints (`movie`/`series`) and `search`/`facets` make **zero Jellyfin calls** — verify no
  `getItemDetail`/`getSeriesEpisodes`/`tvToken` on those paths.
- `GET /api/tv/home` is **behaviourally unchanged** — Continue stays inline (its one R76-parallelized
  round-trip); no new "home returns instantly then a row appears" path is introduced. No row may
  appear/reorder on home after first paint as a result of this phase.
- Per-user state is **read-only** from Jellyfin; nothing here writes or mirrors ownership locally.
- All Jellyfin fan-out stays Semaphore-bounded ([[reference-ktor-native-fd-setsize]]).
- Streaming, `playback/*`, `mark`, favorites/My-List browse are unchanged (they legitimately use Jellyfin).
- FE renders server-pushed state only — the split is a transport change, not derived client state.

## Out of scope
- The app-side two-phase consumption for detail (instant catalog → late playstate overlay, series resume
  pointer, season-reset fix) — that is **R84**.
- **Decoupling the home Continue row** — explicitly rejected (would flicker); home is left as-is.
- Artwork serving from jellystructure — **R85**.
- Owning/caching per-user state in jellystructure (resume/watched) — explicitly **not** doing this;
  Jellyfin stays the owner.
- Changing the favorites/My-List or `mark`/playback Jellyfin calls.

## Source references
- `src/linuxX64Main/.../tv/DetailService.kt`, `tv/HomeFeedService.kt` (`getHomeFeed`, `buildContinueRow`
  — left as-is), `tv/PlaybackService.kt` (`tvToken`, 5-min cache), `auth/JellyfinClient.kt` (bulk
  `…?Ids=…&Fields=UserData`)
- `src/linuxX64Main/.../server/routes/TvRoutes.kt` (detail routes; add `/api/tv/playstate`),
  `shared/.../tv/TvApiClient.kt` + `Models.kt` (DTO contract for R84)
- Research report: `specs/research-reports/ravilo-jellyfin-decoupling-investigation.md` §10 (Workstream 3 backend)
- Depends on: **R82** (static fields). Builds on: **R76** (token cache, parallel fetches), **R81/R75/R78**
  (already-local cast/languages).
