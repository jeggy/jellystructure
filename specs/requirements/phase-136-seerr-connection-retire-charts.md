# Phase 136 — Jellystructure: Jellyseerr/Overseerr connection; retire third-party chart Discover

> Renumbered **134 → 136** after syncing with the repo (repo owns 132–135).

> Add a **Jellyseerr / Overseerr** connection under **Settings ▸ Download tools**, and **remove the entire
> third-party chart Discover/Top-10 subsystem** (Netflix·Tudum / JustWatch / movieofthenight — RapidAPI) plus
> the **Streaming Availability API key**. Seerr becomes the sole browse/request source for Ravilo's Request tab
> ([R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)); per-user rows are configured in
> [Phase 137](phase-137-ravilo-config-request-builder.md).

**Status:** Planned — **design built** (`design/app/settings.html`), backend/frontend integration unbuilt.

## Problem
Discover/Top-10 was fed by ingesting third-party charts from several vendors — Netflix (Tudum), JustWatch, and
**movieofthenight.com** via a **RapidAPI "Streaming Availability" key**. We are standardising on **Seerr** for
all discover + request. The vendor stack (providers, country charts, weekly refresh, the RapidAPI key gating)
is now dead weight and confusing next to the Seerr flow.

## Current state (as-is) — design built in `design/app/settings.html`
- **Added:** a **Jellyseerr / Overseerr** card in the **Download tools** tab (`#sect-seerr`): enable toggle,
  **Seerr URL**, masked **API key**, connection/status chip + **Test connection**; copy points users to
  **Ravilo config → Request** for per-user visibility. `config.toml` preview gains `[seerr] enabled/url/api_key`.
- **Removed:** the **"Discover / Top 10 sources"** card (`#sect-discover`: `PROVIDERS`/`GROUPS` provider chips,
  `#reg-grid` country charts, refresh cadence) **and** its driving IIFE; the **Streaming Availability API key**
  field (`#sa-key`) in **Connections**; the `sect-discover` entry in the tab map. No RapidAPI / JustWatch /
  movieofthenight anywhere.

## Requirements
### A. Seerr connection (Download tools)
1. A **Jellyseerr / Overseerr** card under **Settings ▸ Download tools**: enable toggle, **URL**, **API key**
   (masked, `##KEEP##`-style on save like the *arr keys), **Test connection** with a live status chip. The
   card is the **connection only** — it does not choose what shows on the TV.
2. `config.toml` `[seerr]` block: `enabled`, `url`, `api_key`. Requests/approvals are owned by Seerr; approved
   titles are fulfilled by the existing Radarr/Sonarr integration.

### B. Retire the chart subsystem
3. **Remove** the Discover/Top-10 chart-sources UI, its provider/region/refresh config, and the
   **Streaming Availability (RapidAPI) key**. Remove the corresponding config keys and ingestion. No
   third-party chart provider (Netflix·Tudum, JustWatch, movieofthenight) remains — **supersedes/retires
   Phases 57 and 107**.

## Invariants
- Seerr card is **connection-only**; per-user Request visibility & rows live in Ravilo config (Phase 137).
- API key masked at rest; never logged.
- No RapidAPI / streaming-availability dependency remains in config, UI, or backend.

## Out of scope
- Per-user **Request rows** builder → **[Phase 137](phase-137-ravilo-config-request-builder.md)**.
- The Ravilo **Request tab** UI → **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)**.
- Migrating existing Discover config — the feature is removed, not migrated.

## Source references
- Design: `design/app/settings.html` (`#sect-seerr` card; removal of `#sect-discover`, `#sa-key`, the provider
  IIFE, and the tab-map entry; `[seerr]` in the config preview).
- Related: **Phase 57 / 107** (chart ingestion, now retired), **Phase 54/56** (Radarr/Sonarr — fulfilment),
  **[Phase 137](phase-137-ravilo-config-request-builder.md)** (per-user rows),
  **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)** (TV Request tab).

---

## Dev-review addenda (2026-07-04 — backend design, verified against code)

> Added after a code investigation (three parallel agents mapped the connection pattern, the chart
> subsystem, and the acquisition engine). The design mock is accepted as-is; this section makes the
> backend concrete. Seerr API facts checked against **docs.seerr.dev** + `seerr-team/seerr` source
> (`server/constants/media.ts`).

### D0. Seerr API surface (Jellyseerr / Overseerr — the contract we integrate against)
Base `{{seerr.url}}/api/v1`, auth header **`X-Api-Key: <key>`** (same header name as *arr, different value).
- **Health / test:** `GET /status` (no auth — returns `{version, commitTag, updateAvailable}`) for reachability;
  `GET /auth/me` (auth) or `GET /settings/about` (auth) to prove the key. Test uses both (reachable + key-valid).
- **Discover** (Phase 137 catalogue / R171 feeds): `GET /discover/movies`, `/discover/movies/genre/{genreId}`,
  `/discover/movies/language/{iso}`, `/discover/movies/studio/{studioId}`, `/discover/movies/upcoming`;
  `GET /discover/tv`, `/discover/tv/genre/{genreId}`, `/discover/tv/language/{iso}`,
  `/discover/tv/network/{networkId}`, `/discover/tv/upcoming`; `GET /discover/trending`. Common query params:
  `page`, `language`. Genre catalogue for the picker: `GET /genres/movie`, `GET /genres/tv`.
- **Search** (R171): `GET /search?query=&page=&language=` → mixed movie/tv/person results.
- **Request** (R171 — the fulfilment pivot): `POST /request` body `{ mediaType: "movie"|"tv", mediaId: <tmdbId>,
  seasons?: "all"|number[], is4k?: bool }`.
- **Status enums (locked, from `server/constants/media.ts`):**
  `MediaStatus { UNKNOWN=1, PENDING=2, PROCESSING=3, PARTIALLY_AVAILABLE=4, AVAILABLE=5, BLOCKLISTED=6, DELETED=7 }`;
  `MediaRequestStatus { PENDING=1, APPROVED=2, DECLINED=3, FAILED=4, COMPLETED=5 }`; `MediaType { MOVIE="movie", TV="tv" }`.
  Each discover/search result carries an optional `mediaInfo { status, status4k, downloadStatus[], requests[] }`;
  `downloadStatus[]` items carry `{ size, sizeLeft, status, title, estimatedCompletionTime }` (from the *arr queue,
  proxied by Seerr) — the source for a live **%**.

### A. Config model (`[seerr]`) — mirror `ArrConfig` exactly
- **Backend:** add `data class SeerrConfig(enabled: Boolean = false, url: String = "", @SerialName("api_key") apiKey: String = "")`
  to `config/AppConfig.kt` (mirror `ArrConfig` `:89-95`) and a `val seerr: SeerrConfig? = null` field on
  `AppConfig` (`:6-25`). ktoml's `ignoreUnknownNames = true` (`ConfigStore.kt:19`) means removing `[discover]`
  from existing on-disk configs is safe (same mechanism that let Phase 132 drop `streaming_availability_key`).
- **Frontend mirror:** add the matching `SeerrConfig` + `seerr` field to `api/ConfigApi.kt` `AppConfig` (`:63-77`).
- **Masked key (`##KEEP##`):** frontend emits the sentinel on blank save (mirror `Settings.kt:1157`
  `apiKey = getInputValue("seerr-key").ifBlank { "##KEEP##" }`); backend restores the stored key on receipt
  (mirror `ConfigRoutes.kt:79` — add `if (received.seerr?.apiKey == "##KEEP##") …`) and preserve-on-null-merge
  like `acquisition`/`discover` (`ConfigRoutes.kt:85-88`). Reuse the masked-key UI helpers `arrBoxHtml` /
  `setArrKeyBadge` / `setArrKeyBadgeResult` (`Settings.kt:441-472, 1333, 1339`).
- **`config.toml`:** new `[seerr]` block `enabled` / `url` / `api_key` (matches the mock's config preview,
  `design/app/settings.html:488-490`).

### B. `SeerrClient` + Test connection
- **New client** `src/linuxX64Main/.../seerr/SeerrClient.kt` (structure mirrors `arr/ArrClient.kt`): `base(url) =
  url.trimEnd('/') + "/api/v1"`, `X-Api-Key` header, **under the app-wide `OutboundHttp` permit + `HttpTimeout`**
  (Phase 90/129/134 — do not add an unbounded Curl client). Introduced here; its `discover()`/`search()`/
  `createRequest()`/`media()` methods are consumed by 137/R171.
- **Test route:** `POST /api/config/test-seerr` in `ConfigRoutes.kt` (mirror `testArr` `:110-119`; register in
  `Server.kt:310`), body `TestSeerrRequest(url, apiKey)`, response `SeerrTestResult(ok, detail, version)` →
  `SeerrClient.status()` (GET `/status` for version + an auth'd `GET /auth/me` for the key). Frontend
  `ConfigApi.testSeerr` + `#seerr-test-btn` handler (mirror `Settings.kt:1304-1320`). The card lives under
  `data-tab="downloads"` beside `#sect-arr`/`#sect-crossseed`/`#sect-ingest` (mock `#sect-seerr` already present).

### C. Removal surface — the chart / Discover / Top-10 subsystem (delete, don't migrate)
RapidAPI / movieofthenight / streaming-availability is **already gone** (Phase 132 — only dead comments remain at
`Main.kt:184-186`, `ConfigStore.kt:16-17`, `ChartProvider.kt:43`). What Phase 136 removes:
- **`chart/` package (all 5 files):** `ChartProvider.kt`, `ChartIngestService.kt`, `ChartStore.kt`,
  `NetflixTudumProvider.kt`, `JustWatchProvider.kt`.
- **Shared:** `shared/.../tv/Chart.kt` (whole file — `ChartListSpec`/`ChartEntry`/`Trend`/coverage). ⚠ **Keep
  `shared/.../tv/Discover.kt`'s `AcquisitionChangedEnvelope`** (`:47`) — it's shared with the acquisition engine
  (§E); repurpose the rest of `Discover.kt` for R171 rather than deleting the file wholesale.
- **Routes:** `server/routes/ChartRoutes.kt` (whole — `/discover/lists`, `/discover/list/{id}`,
  `/discover/refresh`, `/discover/coverage`); in `TvRoutes.kt` the `/tv/discover*` handlers (`:385-485`) +
  helpers `acquisitionFor`/`servable`/`TvDiscoverRequest` are **repurposed** by R171, not deleted.
- **Wiring / scheduled job:** `Main.kt:183-199` (chart store/registry/ingest construction) + the **scheduled
  refresh loop `:284-295`** (24 h chart pull); `Server.kt:134-136` params + `:323-326` registration.
- **Config keys:** `AppConfig.DiscoverFeedConfig` (`:65-71`) + the `discover` field (`:17`); `config.toml
  [discover]` (`:116-120`); frontend `ConfigApi.DiscoverFeedConfig` (`:86-92`) + field (`:73`); the
  preserve-merge `ConfigRoutes.kt:88`. (Per-user `RaviloConfig.discover` / `DiscoverConfig` is **repurposed** by
  Phase 137, not deleted.)
- **DB:** `db/Chart.sq` tables `chart_entry` / `chart_history` / `chart_override` — add a **DROP-TABLE migration**
  (don't leave orphan tables).
- **Admin frontend `Settings.kt`:** Discover nav `:62`, card `#sect-discover` `:226-227`, `DISCOVER_PROVIDER_IDS`
  / `DISCOVER_REGIONS` `:546-547`, state `:552-555`, load `:602-611`, `readForm` `:1166-1171`,
  `renderDiscoverRegions` `:1601`, tab-registry `"sect-discover"→"discover"` `:497` + `SETTINGS_TABS` `:503`.
  (Leave `sect-arr`/`sect-crossseed` `:498-499`.)

### D. The request/fulfilment pivot — DECISION (dev-team confirm)
The mock is explicit: *"Seerr owns request & approval rules and hands approved titles to Radarr / Sonarr."* So the
**recommended architecture is Seerr-proxy** (see [R171](../ravilo/requirements/phase-R171-seerr-request-tab.md) §addenda
for the status mapping): TV requests go `Ravilo → jellystructure → Seerr POST /request`, and request status is read
from **Seerr `mediaInfo`**, not from a jellystructure-owned *arr poll. The existing **`AcquisitionStatus` /
`AcquisitionRecord` / WS `acquisition_changed` / TV status rendering are reused but re-sourced** from Seerr;
the direct-to-*arr request path (`AcquisitionService.request()` → `ArrClient.addMovie/addSeries`, reconciler
polling `getQueue`) is **retired for TV requests**.
- **Radarr / Sonarr stay connected in jellystructure** — still used by **R160 Upcoming** (reads the *arr calendar
  directly) and available for root-folder import; they are simply no longer the request *entry point* for the TV.
- **Alternative (not recommended):** keep the direct-*arr `AcquisitionService` and use Seerr for browse/search
  only. Rejected because it bypasses Seerr's approval/permission/quality-profile model — the whole reason to adopt
  Seerr — and duplicates request logic. Flagged here for the dev team to veto if they want a lighter first cut.

### E. Acquisition engine (context — mostly reused)
`arr/AcquisitionService.kt` + `AcquisitionStore.kt` + `db/Acquisition.sq` + `shared/.../tv/Acquisition.kt`
(`AcquisitionStatus{NOT_REQUESTED,REQUESTED,QUEUED,DOWNLOADING,IMPORTING,AVAILABLE,FAILED}`, `AcquisitionRecord`)
are **coupled to charts at only three seams** — `TvRoutes.acquisitionFor()` `:99-107`, `servable()` `:95-96`,
`post("/tv/discover/request")` `:462-485`. Under §D those seams are re-pointed at Seerr; the DTO + WS + persistence
+ TV rendering are otherwise unchanged. R171 owns the Seerr→`AcquisitionStatus` mapping + the new reconciler.

### Source references (backend anchors)
- Config: `config/AppConfig.kt:6-25/89-95/65-71`, `config/ConfigStore.kt:12-51`, `server/routes/ConfigRoutes.kt:48-129`,
  `arr/ArrClient.kt:61-86` (ping/rootFolders pattern), frontend `api/ConfigApi.kt:63-77/196-226`,
  `ui/Settings.kt:441-472/1150-1171/1304-1320`, live `config/config.toml`.
- Charts (remove): `chart/*` (5 files), `shared/.../tv/Chart.kt`, `server/routes/ChartRoutes.kt`,
  `Main.kt:183-199/284-295`, `Server.kt:134-136/323-326`, `db/Chart.sq`, `ui/Settings.kt` (§C).
- Acquisition (reuse/re-source): `arr/AcquisitionService.kt`, `arr/AcquisitionStore.kt`,
  `shared/.../tv/Acquisition.kt`, `db/Acquisition.sq`, `server/routes/TvRoutes.kt:95-107/462-485`.
- Seerr API: **docs.seerr.dev/api/** (discover pages), `seerr-team/seerr` `server/constants/media.ts` (enums),
  `seerr-api.yml` (OpenAPI, ground truth).
