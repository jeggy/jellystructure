# Ravilo ⇄ Jellyfin decoupling — investigation report

**Date:** 2026-06-26
**Question:** How much does the Ravilo TV app depend on Jellyfin — directly, and
indirectly through jellystructure — and how much of Jellyfin can we remove so the
app loads faster, while keeping **actual media streaming** on Jellyfin?

**Scope:** read-only investigation. This is a two-pass document: Part I is the as-is
audit; Part II is the decision-driven plan with deep-dive findings on each workstream.

**Specced as (2026-06-26):** this report's three workstreams are now phase specs —
**R82** (WS1: store static data at scan time), **R83** (WS3 backend: catalog-only detail +
home + bulk `/api/tv/playstate`), **R84** (WS3 app: two-phase load), **R85** (WS2: artwork
served from jellystructure). Dependency chain R82 → R83 → R84; **R85 independent**. Tracked
in the repo-root `STATUS.md`; the open decisions (§13) ride on R85.

**Decisions taken (2026-06-26):**
- **Tier 1 (store static data at scan time):** yes.
- **Tier 2 (artwork):** the app fetches **all artwork from jellystructure** (Jellyfin =
  streaming only). **Not** TMDB CDN. Cache it (TTL discussed below).
- **Tier 3 (per-user state):** **Jellyfin stays the sole owner**; jellystructure only
  **reads** it. We split **catalog render** (instant, from jellystructure) from
  **playstate hydration** (a separate request; indicators arrive a moment later).
- **Movie/series detail:** stop calling `getItemDetail`/`getSeriesEpisodes` for catalog
  data — catalog comes from jellystructure; per-user state moves to the playstate request.
- **Home Continue row:** **keep it inline in `/api/tv/home` (do NOT decouple).** Refined
  2026-06-26: decoupling the Continue row would make it pop in above the catalog after first
  paint and reflow the whole screen — the flicker we are refusing. It is home's only per-user
  element, and home is already one R76-parallelized round-trip, so there is no upside to
  decoupling it. Two-phase hydration is therefore a **detail-screen-only** technique (overlays
  on already-rendered elements, never new/resized rows). See §10.5 and the R83/R84 specs.
- **Security/token exposure:** deferred (a separate token rewrite is planned later).

---

# PART I — As-is audit

## 1. Executive summary (as-is)

Ravilo runs on a **two-host model** that is mostly invisible in the app's own code:

- **Control + catalog traffic** → the jellystructure backend (`/api/tv/**`). One
  configured base URL; the app holds only a jellystructure device token.
- **All library artwork** (posters, backdrops, logos, episode stills, avatars) →
  **fetched directly from Jellyfin** by the app's image loader (Coil), using a Jellyfin
  `api_key` baked into URLs the backend hands back.
- **Video + sidecar subtitles** → **streamed directly from Jellyfin** by ExoPlayer via a
  `StreamTicket` (Jellyfin URL + token). This is the intended, irreducible dependency.
- **Discover/Top-10 artwork** → **directly from the TMDB CDN** (already Jellyfin-free).

**Key findings**

1. **The catalog is already local.** Titles, years, overviews, genres, tags, cast/crew,
   series/episode structure, and search all render from jellystructure's local SQLite with
   **zero Jellyfin catalog calls**.

2. **The home screen still hits Jellyfin twice over** — once server-side (the *Continue
   Watching* row fetches resume + next-up live, and that gates the whole `/api/tv/home`
   response) and then **N times directly** from the device, one Jellyfin image GET per
   visible poster/backdrop/logo. The image fan-out is the biggest perceived-load cost.

3. **Remaining indirect Jellyfin calls split into two buckets:** *per-user state*
   (resume/watched/favorites/continue/next-up — Jellyfin genuinely owns this) and *static
   per-file data that simply isn't stored yet* (item & episode runtime, per-episode
   Jellyfin id, season names) which forces a live call on every detail open.

4. **No response caching** anywhere except a 5-minute token-validity cache. Movie→play
   fetches the same `getItemDetail` twice.

5. **Streaming is already correctly direct app→Jellyfin** — jellystructure never proxies
   media bytes. Keep that boundary.

## 2. Architecture today

```
                         ┌─────────────────────────────────────────────┐
                         │              Ravilo TV app                    │
                         │  TvApiClient (1 base URL) · Coil · ExoPlayer  │
                         └───────┬───────────────┬───────────────┬──────┘
            control/catalog      │       artwork  │       stream  │  (+Discover art)
            (Bearer device tok)  │  (Jellyfin tok)│ (Jellyfin tok)│
                                 ▼               ▼               ▼            ▼
                    ┌────────────────────┐  ┌─────────┐    ┌─────────┐  ┌─────────┐
                    │   jellystructure   │  │ Jellyfin│    │ Jellyfin│  │  TMDB   │
                    │   /api/tv/**       │  │ /Items/ │    │ /Videos/│  │   CDN   │
                    │   SQLite catalog   │  │ Images  │    │ stream  │  │ (images)│
                    └─────────┬──────────┘  └─────────┘    └─────────┘  └─────────┘
       live, per-request:     │
       tvToken/isTokenValid   ▼
       resume/next-up      ┌─────────┐
       getItemDetail       │ Jellyfin│
       getSeriesEpisodes   │  (REST) │
       favorites, mark,    └─────────┘
       PlaybackInfo
```

**App side:** `shared/.../tv/TvApiClient.kt` is the only API client. The Jellyfin host is
never configured — discovered at runtime from `StreamTicket.jellyfin_base_url` and the
absolute image URLs in feed responses. Image loading is Coil (`RaviloAppContext.kt`).

**Backend side:** TV routes in `server/routes/TvRoutes.kt` delegate to `tv/`
(`HomeFeedService`, `DetailService`, `BrowseService`, `PlaybackService`). The single
Jellyfin client is `auth/JellyfinClient.kt` (Ktor Curl, one shared instance, no body
caching). Image URLs are built — not proxied — by `tv/JellyfinImageUrl.kt`.

## 3. Direct Jellyfin dependencies (device → Jellyfin)

| What | Where (app) | Jellyfin endpoint | Trigger | Home critical path? |
|------|-------------|-------------------|---------|---------------------|
| **All library artwork** | Coil via `JellyfinImageUrl` URLs | `/Items/{id}/Images/Primary\|Backdrop\|Logo`, `/Users/{id}/Images/Primary` | home render, row scroll, detail, avatars | **YES — one GET per visible image** |
| **Video stream** | `PlayerScreen.kt` → ExoPlayer | `/Videos/{id}/stream` or Jellyfin `TranscodingUrl`/`master.m3u8` | playback start | no |
| **External subtitle VTT** | ExoPlayer sideload | `/Videos/{id}/.../Subtitles/{idx}/0/Stream.vtt` | playback start | no |

Everything else the device sends goes to jellystructure — including playback progress,
stop, and mark-watched (the backend relays those to Jellyfin).

## 4. Indirect Jellyfin dependencies (device → jellystructure → Jellyfin)

| Ravilo route | Live Jellyfin call(s) | Why (data not local) | Bucket |
|--------------|----------------------|----------------------|--------|
| **every TV request** | `isTokenValid` via `tvToken()` (cached 5 min) | validate paired token vs. server-token fallback | token |
| `GET /tv/home` | `getResumeItems` + `getNextUp` | Continue row = per-user playstate | **per-user** |
| `GET /tv/movie/{id}` | `getItemDetail` | resume %, runtime, audio/sub list | per-user + **static** |
| `GET /tv/series/{id}` | `getSeriesEpisodes` | per-ep watched/resume, stills, runtime, season names, per-ep Jellyfin id | per-user + **static** |
| `GET /tv/browse` (My List) | `getFavoriteItemIds` | favorites = per-user | **per-user** |
| `GET /tv/search` | `tvToken` only | — | token |
| `POST /tv/playback/start` | `getItemDetail` + `startPlaybackSession` + `getPlaybackInfo` | resume, Now-Playing, transcode negotiation | streaming |
| `POST /tv/playback/progress\|stop` | `reportPlaybackProgress` / `stopPlaybackSession` | write playstate | streaming |
| `POST /tv/mark` | `markPlayed` / `markUnplayed` | write watched | **per-user** |
| `POST /tv/pair/approve` (creds) | `authenticateByName` | verify login at pairing | one-time |

**Already 100% Jellyfin-free:** `/tv/facets`, `/tv/config`, `/tv/discover` (+item/request →
TMDB/Radarr/Sonarr), all `/tv/admin/*`, `/tv/settings`, `/tv/sessions`, pairing
start/poll, `/tv/events` (WS), channel-logo serving.

## 5. What jellystructure already stores locally

`model/Media.kt` — one `MediaItem` JSON blob per item in SQLite (SQLDelight), full library,
scanner-refreshed.

**Stored & sufficient:** title, originalTitle, year, kind, **`jellyfinId` (movie)**, tmdb/
imdb/tvdb ids, overview, genres, tags, studio/network (+ids + logo paths), **TMDB
`posterPath`/`backdropPath`** (stored but currently unused by the app), director, full
`cast[]`/`crew[]` (TMDB, incl. episode guests/crew), series `episodes[]` (season/episode,
title, overview, **TMDB `stillPath`**, `tmdbEpisodeId`), per-track `{streamIndex, codec,
language, title, default, forced}` (ffprobe), `titlesByLang`, language resolution, locks.

**NOT stored (forces live Jellyfin):** runtime/duration (no field at all); per-episode
`jellyfinId` (Episode has none — series detail makes a live call just to map (season,ep)→id);
season display names; rich media-source info (channels/bitrate/container/`DisplayTitle`);
**all per-user state**; chapters/markers.

## 6. Where the latency is

Cold home render today: `GET /tv/config` + `GET /tv/home` (catalog part local & fast), but
`/tv/home` **blocks** on `getResumeItems`+`getNextUp` (Continue row) before responding; then
the device fans out **one direct Jellyfin image GET per visible tile** (dominant cost,
gated by Jellyfin responsiveness and the FD/connection limits in project memory). No
caching anywhere; movie→play double-fetches `getItemDetail`.

---

# PART II — Decision-driven plan & deep dives

## 7. Target architecture

```
                         ┌─────────────────────────────────────────────┐
                         │              Ravilo TV app                    │
                         │   TvApiClient · Coil · ExoPlayer              │
                         └───────┬───────────────────────────┬──────────┘
        catalog + artwork +      │                  stream    │   (+Discover art → TMDB)
        playstate (all to JS)    │              (direct→JF)   │
                                 ▼                            ▼
                    ┌──────────────────────────┐        ┌─────────┐
                    │      jellystructure       │        │ Jellyfin│
                    │  /api/tv/** (catalog,     │        │ /Videos/│
                    │   image cache, playstate) │        │ stream  │
                    │  SQLite + artwork cache    │        └─────────┘
                    └─────────┬─────────────────┘
       reads only (no write   │  bulk user-data (resume/watched), continue/next-up,
       of playstate ownership)▼  PlaybackInfo, image fetch-on-miss — all bounded
                          ┌─────────┐
                          │ Jellyfin│
                          │  (REST) │
                          └─────────┘
```

The app talks to **only jellystructure** except for the actual stream. jellystructure:
serves catalog from local SQLite (no Jellyfin), serves artwork from a local cache
(fetch-on-miss, bounded), and reads per-user state from Jellyfin via a **single bulk
call per screen** in a **separate request** from the catalog. Jellyfin remains the system
of record for playstate (we only read) and the streaming origin.

## 8. Workstream 1 — store static data at scan time (Tier 1)

**Goal:** stop the two detail Jellyfin calls from being needed for *anything static*, so
they collapse to pure per-user reads (handled by Workstream 3).

**Add to the model (`model/Media.kt`):**
- `MediaItem.runtime` (minutes/ticks) — today `runTimeTicks` is re-fetched on every movie
  detail open (`DetailService.kt:43,48`); no local field exists.
- `Episode.jellyfinId` — **the playable id.** Today `Episode` has none; `getSeriesEpisodes`
  is called purely to map `(season,episode)→jfEp.id` for playback + stills
  (`DetailService.kt:84,86`), falling back to `ep.path`. This is the single biggest reason
  series detail touches Jellyfin.
- `Episode.runtime` — re-fetched per series detail open (`DetailService.kt:83,89`).
- Season display name (minor) — currently `jfFirstSeason.seasonName`
  (`DetailService.kt:79-80`); falls back to "Season N" if absent.

**Already local — can stop pulling from Jellyfin today, no new field:**
- Audio/subtitle language strips. `MovieDetail.audioLanguages/subtitleLanguages`
  (`DetailService.kt:52-53` via `streamsOf` on Jellyfin `mediaStreams`) are already present
  in `MediaItem.tracks` (kind AUDIO/SUBTITLE + `language`). The only reason they pull from
  Jellyfin is R75/R78 stream ordering — the data exists locally. Series already uses local
  tracks for this (`firstEpTracks`, `DetailService.kt:117-125`).
- Episode stills can use the stored TMDB `Episode.stillPath` instead of the Jellyfin still
  (which needs the jellyfin id); see Workstream 2 for the serving path.

**Result (confirmed by investigation):** after Workstream 1, `getItemDetail` provides only
`userData`, and `getSeriesEpisodes` provides only per-episode `userData` (+ the series
progress derived from it). Both become pure per-user-state fetches. Requires a model
change + a re-scan to populate the new fields.

## 9. Workstream 2 — serve all artwork from jellystructure (Tier 2, revised)

**Goal:** the app loads every poster/backdrop/logo/still/avatar from jellystructure (not
direct Jellyfin, not TMDB CDN), backed by a local cache.

### 9.1 There is already a working precedent
- **`media/LogoDownloader.kt` (Phase 31)** is on-demand-proxy-with-disk-cache, *already in
  production*: `GET /api/people/{tmdbId}/image` (`MediaRoutes.kt:1405-1428`) serves from
  cache, else downloads (from TMDB) and serves. Crucially it is **already FD-bounded** —
  `private val downloadGate = Semaphore(8)` (`LogoDownloader.kt:32`), every download under
  `downloadGate.withPermit { ... }` (`:122`), with a comment citing the Phase-78 crash this
  prevents ("a flood of cold `/api/people/{id}/image` requests opened unbounded sockets and
  crashed the process"). Cache dir `<dataDir>/artwork/{studios,networks,people}/`.
- **`tv/ChannelLogoStore.kt`** is disk-serve precedent: files under `<dataDir>/channel-logos`,
  public route `GET /api/tv/channel-logos/{name}` → `respondBytes` (`TvRoutes.kt:503-506`),
  path-traversal guard, atomic tmp+rename writes, MIME by extension.
- **`media/ArtworkDownloader.kt` (Phase 47)** downloads poster/fanart/clearlogo/still **from
  TMDB into the media-library dir** (for Jellyfin to read) — but it is **admin-triggered
  only, NOT wired into the scan flow** (`Scanner.kt` never calls it) and is **unbounded**
  (its own `HttpClient(Curl)`, no semaphore) — safe today only because it's off the TV path.

**Serving primitive:** the codebase uses read-whole-file-into-memory + `respondBytes`
everywhere (no `respondFile`/`staticFiles`/streaming). Fine for posters.

**No TTL/eviction exists anywhere** — all three caches are permanent (overwritten on
re-fetch). The "short TTL" the user asked for is net-new logic.

### 9.2 FD-ceiling assessment (the governing constraint)
The Kotlin/Native CIO server uses `select()`; any FD ≥ 1024 (`FD_SETSIZE`) crashes the
**whole process** (project memory + `LogoDownloader.kt:27-32`). An on-demand image route is
exactly a fan-out endpoint: a cold home screen = dozens of concurrent Coil GETs, each a
cache miss opening an **outbound** Jellyfin socket **+** a temp-file FD, stacked on top of
inbound FDs and the **four** existing `HttpClient(Curl)` pools (`JellyfinClient`,
`LogoDownloader`, `ArtworkDownloader`, `TmdbClient`). This is the Phase-78 crash pattern.

**Mandatory bounding for any proxy:** a single shared `Semaphore` around the outbound fetch
(mirror `LogoDownloader`'s `Semaphore(8)`, maybe ~16), ideally a **single shared bounded
HttpClient** rather than a new Curl pool, and the temp-file write inside the permit. **Cache
hits open no outbound FD** — so a high hit-rate is what keeps this safe, which argues for a
*long* TTL with invalidation, not a genuinely short one.

### 9.3 Option (a) on-demand proxy + TTL  vs  (b) pre-download at scan time
- **(a) On-demand proxy + cache** `GET /api/tv/image/{itemId}/{type}`: lowest new surface
  (generalize the proven people-image route). Cache key `{jellyfinItemId}-{type}` (the
  `MediaCard.id` is already the jellyfin id); episode stills keyed by per-episode id.
  TTL = file-mtime staleness check; double-bandwidth only on cold miss (Coil also client-
  caches); mandatory shared semaphore. **Risk:** a short TTL forces frequent cold misses →
  frequent bounded outbound fetches.
- **(b) Pre-download at scan time, serve static:** reuses `ArtworkDownloader` mechanics but
  it's **not in the scan flow today** and pulls from **TMDB, not Jellyfin** — real new scan
  work. Larger disk footprint (episode stills dominate). **Zero outbound FD on the TV path**
  (pure disk read), so safest at runtime; refreshes only on re-scan/re-pull.

**Recommendation:** a **hybrid** — use (a)'s on-demand mechanism (the proven people-image
pattern, generalized) but with a **generous TTL + explicit invalidation** on
re-pull/artwork-change rather than a genuinely short TTL. This gives the user's requested
"cache" behavior, keeps outbound fan-out bounded *and rare*, and avoids front-loading scan/
disk work. Pure (a)-with-short-TTL is the riskiest for the FD ceiling; pure (b) is safest at
runtime but heavier upfront. **Upstream source** for posters/backdrops can be Jellyfin (single
upstream, already size-capped via `fillWidth/fillHeight`, R63) or TMDB (already used by
`ArtworkDownloader`); pulling from **Jellyfin** keeps one upstream and avoids TMDB rate limits.

### 9.4 Logos (the coverage gap)
There is **no item clearlogo field** in `Media.kt` (only studio/network logo paths). Today
the TV builds it live: `JellyfinImageUrl.logo` → `/Items/{id}/Images/Logo`
(`JellyfinImageUrl.kt:15-16`), used only for heroes. Three sources for a JS-served scheme:
1. **Proxy Jellyfin's `Images/Logo`** — simplest, identical coverage to today (works only
   where Jellyfin actually has a logo).
2. **TMDB images API** — `TmdbClient.getMovieImages/getTvImages` already exist and return a
   language-tagged `logos[]` (`TmdbImagesResponse`); needs a new model field + a scan/
   artwork-time fetch (and the `xx` no-language bucket the artwork manager already handles).
3. **Local `clearlogo.png`** via `ArtworkDownloader.saveAsset("clearlogo", …)` — exists but
   only through the manual Phase-47 manager; no auto-fetch.

Cleanest for "single source": proxy Jellyfin's logo (accept today's coverage) for v1, or
fetch+store a TMDB logo the same way option (b) handles other art.

### 9.5 URL scheme
DTO image fields become **relative** and are resolved app-side by prepending the base URL —
the pattern already exists for channel logos (`HomeScreen.kt:205-206`:
`if (it.startsWith("/")) "$baseUrl$it" else it`, base from `LocalServerBaseUrl`):
- `posterUrl  = "/api/tv/image/{jellyfinItemId}/poster"`
- `backdropUrl = "/api/tv/image/{jellyfinItemId}/backdrop"`
- `logoUrl     = "/api/tv/image/{jellyfinItemId}/logo"`
- episode `stillUrl = "/api/tv/image/{jellyfinEpisodeId}/still"`
- `avatarUrl   = "/api/tv/image/user/{userId}/avatar"`

Set in the `toMediaCard` helpers (`DetailService.kt:147-160`, `BrowseService.kt:137-150`,
`HomeFeedService.kt:310-319`) + hero builders, replacing `JellyfinImageUrl.*`. **Auth caveat:**
today's Jellyfin image URLs embed `?api_key=`; the new route must be **public** like
`/api/tv/channel-logos` (Coil sends no auth headers — `ImageLoader.kt` passes a bare URL),
so public-serve is the path of least resistance.

## 10. Workstream 3 — split catalog render from playstate hydration (Tier 3)

**Goal:** render the catalog instantly from jellystructure; hydrate per-user indicators
(resume bars, watched ticks, Resume labels, Continue/Next-Up) via a **separate** request,
with Jellyfin remaining the sole owner (read-only).

### 10.1 Static-vs-per-user split (backend)
After Workstream 1, the only Jellyfin-sourced data left on detail is per-user:

| Detail call | Static (→ local, post-WS1) | Per-user (→ playstate request) |
|---|---|---|
| `getItemDetail` (movie) | runtime, audio/sub languages, card/synopsis/cast/related (already local) | `userData`: resume ticks, played, played% |
| `getSeriesEpisodes` | per-ep jellyfinId, runtime, title, still, season name | per-ep `userData` → series `progress` (watchedCount, resumeEpisodeId, resumeLabel) |

### 10.2 Home parallelism finding
`getHomeFeed` (`HomeFeedService.kt:32-47`) parallelizes only the two top-level reads
(`allItems`, `tvToken`). The **Continue fetch is NOT parallel with the response** — it runs
*inside* `buildRows` → `buildContinueRow` (`:265-301`), reached only after the `await`, and
the `/api/tv/home` response can't return until it completes. `getResumeItems`/`getNextUp` are
parallel *with each other* (`:275-278`) but the whole feed is gated on that round-trip
(CONTINUE is the default first row). **Only the Continue row carries per-user indicators
today**; NEWLY_ADDED/GENRE/CUSTOM tiles have no progress/watched (`:195-236`) — so adding
overlays elsewhere is net-new and fits a separate hydration request cleanly.

### 10.3 Bulk user-data is feasible (proven patterns)
`getItem` already uses `/Items?Ids={id}&Fields=...` (with a note that `/Items/{id}` 400s with
a server token but the `Ids=` filter is accepted — `JellyfinClient.kt:110-122`).
`getResumeItems`/`getFavoriteItemIds` already use `/Users/{userId}/Items?...&Fields=UserData`.
A bulk call is the union: **`GET /Users/{userId}/Items?Ids=a,b,c&Fields=UserData`** →
`{Items:[{Id, UserData{PlaybackPositionTicks, Played, PlayedPercentage}}]}`, deserializable
into existing models. One round-trip replaces N× `getItemDetail`. **URL-length caveat:** a
full home screen (~150-200 ids ≈ 5-7 KB) approaches common proxy/Kestrel URL caps (~8-16 KB)
→ chunk (e.g. 100/request, bounded fan-out like `buildContinueRow`) or POST. Keep any fan-out
Semaphore-bounded (FD ceiling).

### 10.4 Proposed backend shape (design only)
- **Detail endpoints become catalog-only** — `GET /api/tv/movie/{id}` and
  `GET /api/tv/series/{id}` return purely from `MediaStore` (card, synopsis, runtime,
  cast, related, audio/sub languages; for series the full season/episode structure with
  per-episode `jellyfinId`, runtime, title, still, season names). **Zero Jellyfin calls —
  not even `tvToken`.** `playback`/`progress` fields drop out (or return null placeholders).
- **New playstate endpoint** — `GET /api/tv/playstate?ids=a,b,c` →
  `{id → {resumeMs, played, playedPct}}` via **one** bulk `…?Ids=…&Fields=UserData` call
  (chunked). The single place per-user state is read; both detail and home call it after
  rendering catalog. Needs `tvToken` (the only place the 5-min cache is consulted here).
- **Home flow** — catalog rows render instantly from local store. **Continue / Next-Up stay
  on Jellyfin** (they're Jellyfin-*computed* lists, not just per-item userdata, so they can't
  be served locally) but should be **hoisted to parallel** or moved to their own request so
  the catalog isn't gated on them. Progress/watched overlays on other rows hydrate via the
  playstate endpoint using on-screen ids.

### 10.5 App-side: indicators, store shape, and what may hydrate late
**Per-user UI indicators (16, inventoried):** tile progress bar (`Tile.kt:174-189`), tile
watched ✓ (`:192-203`), home/channel/browse/search tile bar+✓+subtitle
(`HomeScreen.kt:233-237`, `ChannelScreen.kt:207-211`, `BrowseScreen.kt:303-304`,
`SearchScreen.kt:274-275`), related-tile ✓ (`MovieDetailScreen.kt:281`,
`SeriesDetailScreen.kt:385`), **Continue Watching row** (`HomeScreen.kt:230`), movie
Play/Resume label (`MovieDetailScreen.kt:228-231`), series Play/Resume label + count + kicker
+ **initial season selection** (`SeriesDetailScreen.kt:286-295,243-250,262-265,166-172`),
episode-card bar/✓/dim/"UP NEXT" (`EpisodeCard.kt`), player next-up rail
(`PlayerScreen.kt:1381-1395`). *(The `Tile` "NEW" badge exists but is never wired. Hero
badge and Discover progress are not per-user.)* **No watched/unwatched filtering or sorting
exists app-side** — order/membership come from the server.

**Store shape:** all four stores are a single immutable `Loaded(...)`. **HomeStore and
MovieDetailStore already have a silent re-emit path** (`refresh(silent=true)` /
`refreshSilent`, the R33 live-config mechanism) that swaps in new data with no `Loading`
flash and preserves scroll/focus — a phase-2 merge can reuse it almost verbatim. **Hazard:
`SeriesDetailScreen` keys season state on `remember(detail)`** (`:166,172`) — a phase-2
re-emit of a new `detail` object **resets the season picker**. Fix by keying season state on
a stable `itemId`, or by using a parallel overlay map for series episode playstate.

**Key fact that makes late hydration safe for movies:** the movie **resume seek position is
resolved server-side** — `onPlay` passes only `card.id`; `PlaybackService` reads
`StreamTicket.startPositionMs` from Jellyfin (`PlaybackService.kt:54-56`). So `detail.playback`
drives **only the label**; pressing "Play" before it flips to "Resume" still resumes
correctly. The movie Play/Resume label is purely cosmetic.

**Block-vs-hydrate-late recommendation (block on almost nothing):**

| Indicator | Recommendation |
|---|---|
| Tile progress bars, watched ticks (all screens) | **Hydrate late**, fade-in — overlays inside fixed-size tiles, no reflow |
| Tile subtitle (`nextUpLabel`) | Hydrate late, but **reserve one line** so a late subtitle doesn't nudge layout |
| **Continue Watching row** | **Ship row *membership + art* in phase 1**; hydrate only the bars late. A whole row appearing/reordering reflows the list & moves focus. (Membership is catalog-shaped/cheap; only "how far watched" is true playstate.) |
| Movie Play/Resume label | **Hydrate late — safe** (cosmetic; seek is server-side) |
| **Series Play label + resume target + initial season** | **Phase 1** (ship the tiny `resumeEpisodeId`+season pointer) or disable Play until it lands — unlike movies, `onPlay` builds context from `resumeEpisodeId`/`selectedSeasonIdx`, so early Play would start the wrong episode |
| Series "X of Y watched" + kicker | Hydrate late (text in fixed lower-third) |
| Episode-rail bars/✓/dim/"UP NEXT" | Hydrate late (needs `resumeEpisodeId` from the phase-1 pointer) |

### 10.6 App-side image switch
Centralize the relative→absolute prepend **inside `RemoteImage`** (`seams/ImageLoader.kt`) by
reading `LocalServerBaseUrl.current`, keeping the `startsWith("/")` guard so absolute URLs
pass through untouched — **critical** because the Discover tab builds absolute
`https://image.tmdb.org/...` URLs itself (`DiscoverScreen.kt:366,370`,
`DiscoverDetailScreen.kt:97`) which must **not** be prepended. **No app code assumes a
Jellyfin URL or parses an `api_key`** (grep-confirmed — those strings live only in the
backend), so the switch is transparent app-side apart from this one resolution step. Coil
treats resolved-absolute URLs identically; SVG decoder already wired for channel logos,
raster posters use Coil defaults.

## 11. The irreducible Jellyfin core (after all workstreams)

1. **Streaming the media bytes** — direct app→Jellyfin (by design).
2. **A token** authorizing the stream (from config — no live call).
3. **`PlaybackInfo`** transcode negotiation for non-direct-play items; direct-play can skip it.
4. **Per-user state reads** — resume/watched/favorites/continue/next-up. Jellyfin stays the
   system of record; jellystructure reads it (one bulk call per screen) and never owns it.

Everything else — catalog, artwork, detail metadata, runtime, track lists, search — is
served by jellystructure with **no live Jellyfin call**.

## 12. Recommended sequencing

1. **Workstream 1** (store runtime, per-episode `jellyfinId`, season name; serve audio/sub
   languages + stills from local). Model change + re-scan. Unblocks WS3's catalog-only detail.
2. **Workstream 3 backend** (catalog-only detail endpoints + new `/api/tv/playstate` bulk
   endpoint; hoist Continue/Next-Up off the critical path). 0 Jellyfin calls for catalog.
3. **Workstream 3 app** (two-phase load: reuse silent-refresh for Home/Movie; fix the
   series `remember(detail)` season-reset; ship Continue membership + series resume pointer
   in phase 1; fade-in the rest).
4. **Workstream 2** (artwork via jellystructure: generalize the people-image on-demand
   pattern, bounded, generous TTL + invalidation; decide logo source; switch DTO fields to
   relative + centralize prepend in `RemoteImage`).
5. Leave streaming exactly as-is.

(WS2 and WS3 are independent and can proceed in parallel; WS1 must precede WS3.)

## 13. Open decisions

- **Image TTL policy:** the user asked for a "short TTL," but the FD ceiling favors a
  *generous* TTL + explicit invalidation. Confirm the desired freshness vs. safety trade-off.
- **Logo source:** proxy Jellyfin's logo (v1, today's coverage) vs. fetch+store TMDB logos
  (new field + scan work, better/independent coverage).
- **Image upstream:** pull cache from Jellyfin (single upstream) vs. TMDB (independent of
  Jellyfin uptime). Pulling from Jellyfin is simpler; TMDB further decouples.
- **playstate batching transport:** GET with chunked ids vs. POST with an id list.

---

## Appendix — key files

- App API client: `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/TvApiClient.kt`
- App DTOs: `shared/.../tv/Models.kt`
- App player: `ravilo-ui/.../screens/PlayerScreen.kt`, `.../seams/RaviloPlayerAndroid.kt`
- App image loading: `ravilo-ui/.../seams/ImageLoader.kt`, `.../components/Tile.kt`, `HeroCarousel.kt`, `EpisodeCard.kt`
- App stores: `ravilo-ui/.../screens/{HomeStore,DetailStore}.kt`, `BrowseScreen.kt`
- TV routes: `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt`
- TV services: `src/linuxX64Main/.../tv/{HomeFeedService,DetailService,BrowseService,PlaybackService}.kt`
- Image URL builder: `src/linuxX64Main/.../tv/JellyfinImageUrl.kt`
- Image cache precedent: `src/linuxX64Main/.../media/LogoDownloader.kt` (Phase 31), `tv/ChannelLogoStore.kt`, `media/ArtworkDownloader.kt` (Phase 47)
- Jellyfin client: `src/linuxX64Main/.../auth/JellyfinClient.kt`, models `auth/Models.kt`
- Data model: `src/commonMain/kotlin/dev/jellystructure/model/Media.kt`
- Store: `src/linuxX64Main/.../` MediaStore (SQLite/SQLDelight, `db/Media.sq`)
