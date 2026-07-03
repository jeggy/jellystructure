# Phase R171 — Ravilo TV: Seerr-powered Request tab (browse · request · search)

> Renumbered **R167 → R171** after syncing with the repo.

> Inside **Discover** (see [R170](phase-R170-profile-hub-discover-merge.md)), the **Request** tab is a full
> **Jellyseerr / Overseerr** integration: rows of Seerr **discover feeds** (configured per user by
> [Phase 137](../../requirements/phase-137-ravilo-config-request-builder.md)), a **Seerr-scoped search**, and
> a request action that hands off to Seerr → Radarr/Sonarr. Replaces the retired Top 10 charts.

**Status:** Planned — **design built**, Compose app integration unbuilt.

## Problem
Requesting missing titles from the sofa used to ride on third-party **Top 10 charts** (Netflix/JustWatch/etc.)
gated by Radarr. That vendor stack is being retired ([Phase 136](../../requirements/phase-136-seerr-connection-retire-charts.md));
**Seerr** becomes the single browse-and-request source. The TV needs to render Seerr's discover feeds and
place requests, without any of the chart framing (ranks, "weeks on chart", trend arrows, "why it's trending").

## Architectural constraint
Ravilo renders **server-pushed state**. The Request rows, their order/visibility, and each item's availability
+ request status all come from the server (Jellystructure proxying Seerr). The client formats feeds and issues
a request intent; it does not talk to Seerr directly, and does not compute availability.

## Current state (as-is) — design built in `design/ravilo/`
- **`ravilo-app.js`**
  - `renderDiscover()` (Request tab): a `dischead` (title + `request_sub`), the Coming Soon / Request segment,
    a **Search Seerr** pill (`data-seerrsearch`), then one `discoverRow(list)` per configured feed.
  - `rankTile(it, list)` is now a **plain poster tile**: poster + `statusMark(it)` badge + title + **metadata**
    (`year · genre · rating+`). **No** rank numeral, "weeks", trend arrow, or "new this week". Row headers show
    the feed title only (scope/`note` chips removed).
  - **Request status** vocabulary retained: `not_requested` → **Request**, `requested`, `queued` (#pos),
    `downloading` (%), `importing`, `available` (✓ In Library), `failed`; live ticker + `requestFetch`.
  - **Seerr search**: the pill opens `renderSearch({ seerr: true })` — the on-screen-keyboard search screen
    scoped to the **Seerr catalogue** (`seerrCatalog()` over the discover feeds, not the library `catalog()`).
    `renderSearchResults` builds request tiles; a result opens the **request detail**. Back returns to Request.
  - **Request detail** (`renderDiscoverDetail`): the chart-only **"Why it's trending"** block (rank / on-chart /
    trend / views stats) is **removed** — detail is hero + synopsis + request actions only.
- **`ravilo-data.js`** — the discover feeds relabeled to Seerr-style names (Trending Movies/Series, Popular
  Movies, International Films, All-Time Popular); stands in for the server-proxied Seerr feeds.
- **`ravilo-i18n.js`** — `search_seerr`.

## Requirements
### A. Feed rows
1. The Request tab renders one **row per configured Seerr discover feed** (order + visibility from Phase 137).
   Each row is a horizontal rail of **plain poster tiles**: poster, title, `year · genre · rating`, and a
   **request-status badge**. No ranks/weeks/trend/chart wording anywhere.

### B. Request action + status
2. Each tile/detail exposes the request lifecycle from server state: **Request** (not in library) → requested →
   queued → downloading (%) → importing → **available** (✓ In Library), plus **failed** (retry). Requesting
   is subject to the per-user permission (Phase 137); approvals happen in **Seerr**; fulfilment via Radarr/Sonarr.

### C. Seerr-scoped search
3. A **Search Seerr** affordance on the Request tab opens the keyboard search **scoped to the Seerr catalogue
   only** (never the local library). Results are request tiles that open the request detail. Back returns to
   the Request tab.

### D. Request detail
4. The request detail shows hero art, title, meta (cert/year/genre), synopsis and **request actions** only —
   **no** rank / trend / "why it's trending" / chart-source framing.

## Invariants
- **No chart framing** — ranks, weeks-on-chart, trend arrows, "new this week", "why it's trending" are gone.
- Search on the Request tab **only** searches Seerr (library search stays on the AppBar search icon).
- Render-only + request-intent-only; availability, status and feed contents are server-pushed.
- Requesting obeys the per-user **allow-request** permission; admins may always request.

## Out of scope
- The admin **connection** (Settings) and per-user **row config** → Phases **136** / **137**.
- People/actor search results, Seerr issue reporting, request management/approval UI (lives in Seerr).
- Coming Soon tab contents (R160/R170).

## Source references
- Design: `design/ravilo/ravilo-app.js` (`renderDiscover`, `rankTile`, `discoverRow`, `seerrCatalog`,
  `searchFilter`, `renderSearchResults`, `renderSearch` seerr mode, `renderDiscoverDetail` w/o `ddt-why`,
  `requestFetch`/status machinery); `design/ravilo/ravilo-data.js` (relabeled `discoverLists`);
  `design/ravilo/ravilo.css` (`.rtile` plain tile, `.dsearch`).
- Related: **136** (Seerr connection), **137** (per-user Request rows), **R170** (Discover shell),
  **R48–R50** (retired chart request flow this replaces).

---

## Dev-review addenda (2026-07-04 — backend + app design, verified against code)

> Good news for scope: the **request lifecycle already exists end-to-end** and is reused — R171 is mostly
> *re-sourcing the feeds from Seerr and stripping chart framing*, not building a request system. Depends on
> [Phase 136](../../requirements/phase-136-seerr-connection-retire-charts.md) (`SeerrClient`, §D pivot) and
> [Phase 137](../../requirements/phase-137-ravilo-config-request-builder.md) (per-user feeds).

### A. Feeds — repurpose the `/tv/discover` route family, Seerr-backed
The existing shape is reused with chart fields dropped:
- **Backend:** replace `ChartIngestService` with a **`SeerrDiscoverService`** that, per configured `SeerrFeed`
  (Phase 137), calls `SeerrClient.discover(endpoint, param, page)` and maps each result to a tile. Repurpose
  `GET /api/tv/discover` (`TvRoutes.kt:385`) to build rows from the user's `RaviloConfig.request.feeds` instead of
  `discover.lists`; keep the `DiscoverResponse{available, canRequest, rows}` / `DiscoverRow` envelope (drop
  `source`/`region`). Replace the chart-shaped `ChartEntry` (rank/weeksOnChart/trend/views) with a plain
  **`RequestEntry{ tmdbId, mediaKind, title, year, genre, rating, posterPath, backdropPath, overview,
  acquisition: AcquisitionRecord }`** (or reuse `MediaCard` `Models.kt:117` + a status field). Posters are TMDB
  **relative paths**; the client builds the absolute CDN URL itself (`tmdbImg`, §D) — **no image proxy** (same as
  the retired Discover; this shared helper must survive Phase 136's removal — see R167, which also depends on it).
- **Route naming:** the `/tv/discover*` family may be renamed `/tv/request*` to match R170's "Request" label
  (cosmetic; update `TvApiClient` in lockstep). Either is fine; keep one.

### B. Request action + the Seerr → `AcquisitionStatus` mapping (the crux)
Reuse `AcquisitionStatus{NOT_REQUESTED,REQUESTED,QUEUED,DOWNLOADING,IMPORTING,AVAILABLE,FAILED}` /
`AcquisitionRecord` / WS `acquisition_changed` / TV `StatusPill`+`discoverStatusLabel` **unchanged**, but source
the state from **Seerr `mediaInfo`** instead of the *arr queue (Phase 136 §D pivot):
- **Request:** repurpose `POST /api/tv/discover/request` (`TvRoutes.kt:462`) → `SeerrClient.createRequest(mediaType,
  mediaId = tmdbId, seasons = "all")` (Phase 136 §D0). Returns the seeded `AcquisitionRecord` (REQUESTED).
- **Status source:** a **new reconciler** (replacing `AcquisitionService`'s `getQueue` poll `:145-177`) polls Seerr
  — `GET /api/v1/request?take=&skip=&filter=` and/or `GET /api/v1/media` — and maps to `AcquisitionStatus`:

  | Seerr signal | → `AcquisitionStatus` |
  |---|---|
  | no `mediaInfo` / `MediaStatus.UNKNOWN(1)` | `NOT_REQUESTED` |
  | request `PENDING(1)` approval **or** `MediaStatus.PENDING(2)` | `REQUESTED` |
  | `MediaStatus.PROCESSING(3)`, `downloadStatus[]` empty/not started | `QUEUED` (+ `queuePosition` if known) |
  | `MediaStatus.PROCESSING(3)`, `downloadStatus[]` active | `DOWNLOADING`, `progress = round(100·(1 − sizeLeft/size))`, `eta` from `estimatedCompletionTime` |
  | `MediaStatus.PROCESSING(3)`, download done, not yet available | `IMPORTING` |
  | `MediaStatus.PARTIALLY_AVAILABLE(4)` / `AVAILABLE(5)` | `AVAILABLE` (series: roll up per-season `mediaInfo`) |
  | request `DECLINED(3)` / `FAILED(4)` | `FAILED` (`retryable = true`) |

  `AVAILABLE` also short-circuits when the title is already in the local library (existing `acquisitionFor`
  in-library check, `TvRoutes.kt:99-107`). Emit deltas via `eventBus.notifyAcquisitionChanged(...)` exactly as today
  → the TV's `LocalLiveAcquisition` ticker updates live (no client change to the status machinery).
- **Permission:** requesting obeys the per-user `canRequest` (Phase 137) — non-permitted users browse + see status
  but the action is hidden; admins always may. Approval itself happens in Seerr.

### C. Seerr-scoped search
- **Backend:** add `GET /api/tv/search/seerr?q=` → `SeerrClient.search(query)` (`GET /api/v1/search?query=`),
  returning request tiles (movie/tv only; drop `person` results) with the §B status. Keep the existing
  library `GET /api/tv/search` (`TvRoutes` / `TvApiClient.search :80`) untouched — it stays on the AppBar search icon.
- **App:** `TvApiClient` gains `searchSeerr(query)`; `SearchScreen.kt:120` takes a `seerr: Boolean` mode
  (the mock's `renderSearch({seerr:true})`) — same native-IME `BasicTextField` + grid, different endpoint +
  request-tile rendering; Back returns to the Request segment.

### D. TV client + screens (rebuild `DiscoverScreen`, drop chart chrome)
- **`TvApiClient`** (`shared/.../tv/TvApiClient.kt`): reuse/repoint `getDiscover()` `:233` (→ request feeds),
  `getDiscoverItem()` `:239` (→ request detail), `requestDiscover()` `:253` (→ Seerr request); add `searchSeerr()`.
- **`DiscoverScreen.kt`**: `RankTile` (`:228`, giant outlined rank numeral + poster-over-edge) → a **plain `Tile`**
  (poster + `StatusPill` + title + `year·genre·rating`). Remove `chartSubline` (`:378`), the rank numeral draw
  (`:259-277`), and the `#rank in <REGION>` kicker. Row headers show the feed title only.
- **`DiscoverDetailScreen.kt`**: remove `WhyTrending` (`:210`) + the rank/on-chart/trend/views block and the region
  kicker (`:110-118`) — detail = backdrop hero + synopsis + `PrimaryAction` (`:177`, the existing status-driven
  Request/Watch/Retry button, unchanged) + optional cast. Posters/backdrops via `tmdbImg`/`tmdbPoster`
  (`DiscoverScreen.kt:371-376`) + `ImageLoader` absolute-URL passthrough (`:46-51`) — the **shared CDN helper that
  must survive Phase 136** (R167 depends on it too).
- Status rendering (`StatusPill :356`, `discoverStatusLabel :388`, `discoverStatusColor :402`, `LocalLiveAcquisition`)
  is reused verbatim.

### E. Invariants (backend)
- **Client never calls Seerr directly** — jellystructure proxies every Seerr call (constitution: render
  server-pushed state). `SeerrClient` runs under the `OutboundHttp` permit + `HttpTimeout` (Phase 129/134).
- **No chart data model leaks** — `ChartEntry`/`ChartListSpec` are deleted (Phase 136 §C); R171 introduces
  `RequestEntry` (or reuses `MediaCard`), no rank/weeks/trend fields anywhere.

### Source references (backend + app anchors)
- Backend: `server/routes/TvRoutes.kt:99-107/385-485` (repurpose), new `seerr/SeerrDiscoverService.kt` +
  `SeerrClient` (Phase 136 §B), `arr/AcquisitionService.kt:145-177` (reconciler replaced), `tv/TvEventBus.kt:96`
  (WS emit, reused), `shared/.../tv/Acquisition.kt` (reused), `shared/.../tv/Discover.kt` (repurpose; keep
  `AcquisitionChangedEnvelope`).
- App: `shared/.../tv/TvApiClient.kt:80/233/239/253`; `screens/DiscoverScreen.kt:228/259-277/356-402`,
  `screens/DiscoverDetailScreen.kt:110-118/177/210`, `screens/SearchScreen.kt:120`, `ImageLoader.kt:46-51`.
- Seerr API + enums: [Phase 136](../../requirements/phase-136-seerr-connection-retire-charts.md) §D0.
