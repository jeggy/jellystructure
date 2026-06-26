# Phase R85 — Serve all artwork from jellystructure (image cache), not direct Jellyfin

> Make jellystructure the single image source for the Ravilo app: posters, backdrops, logos, episode
> stills, and avatars come from a jellystructure endpoint backed by a bounded, cached on-demand proxy —
> not loaded directly from Jellyfin (and not from the TMDB CDN). Jellyfin stays streaming-only.

## Problem
Every artwork tile the app shows is currently a **direct Jellyfin GET**: the catalog DTOs carry absolute
Jellyfin image URLs (`/Items/{id}/Images/…?api_key=…`, built by `tv/JellyfinImageUrl.kt`) and Coil loads
them straight from Jellyfin. The home screen fans out one such Jellyfin request per visible tile — the
dominant perceived-load cost and a coupling to Jellyfin's responsiveness (R63 right-sized those URLs but
they still hit Jellyfin). It also leaks a Jellyfin token to the device (deferred security concern). We
want the app to fetch artwork only from jellystructure, cached.

## Architectural constraint (driving decision)
**The app talks only to jellystructure except for the actual stream** ([[ravilo-off-jellyfin-data]]).
jellystructure serves artwork from a local cache, fetching from upstream **on miss** through a single
shared, **Semaphore-bounded** HttpClient — this is mandatory: the Native CIO server crashes fatally at
FD ≥ 1024, and an image proxy is exactly the fan-out that caused the Phase-78 crash
([[reference-ktor-native-fd-setsize]]). Cache hits open no outbound FD, so a high hit-rate keeps it safe.

## Current state (as-is)
- `tv/JellyfinImageUrl.kt` builds absolute Jellyfin URLs for poster/backdrop/logo/still/avatar; these
  are embedded in DTOs by `HomeFeedService`/`DetailService`/`BrowseService` and loaded verbatim by Coil
  (`ravilo-ui/.../seams/ImageLoader.kt` → `AsyncImage`). R63 added size caps + configured Coil.
- **Proven on-demand-proxy-with-cache precedent already in production:** `media/LogoDownloader.kt`
  (Phase 31) downloads to `<dataDir>/artwork/{studios,networks,people}/`, **already FD-bounded**
  (`Semaphore(8)`, `withPermit` around download+temp-file), served by `GET /api/people/{tmdbId}/image`
  (serve-from-cache → else bounded download → serve). Generalizing this is the core of the phase.
- **Disk-serve precedent:** `tv/ChannelLogoStore.kt` serves bytes from `<dataDir>/channel-logos` via a
  **public** route `GET /api/tv/channel-logos/{name}` (`respondBytes`, traversal guard) — Coil loads it
  with no auth header.
- `media/ArtworkDownloader.kt` (Phase 47) downloads TMDB art into the media dir but is admin-triggered,
  **not** in the scan flow, and **unbounded** — do not reuse on the TV path as-is.
- **No TTL/eviction exists anywhere** — all current caches are permanent (overwritten on re-fetch).
- **Logos are not in the model** (`Media.kt` has poster/backdrop + studio/network logos, no item
  clearlogo) — `JellyfinImageUrl.logo` builds it live from `/Items/{id}/Images/Logo`.
- The app already resolves relative server paths for channel logos
  (`HomeScreen.kt`: `if (it.startsWith("/")) "$baseUrl$it"`, base from `LocalServerBaseUrl`). The Discover
  tab builds **absolute** `https://image.tmdb.org/...` URLs itself — these must keep passing through.

## Requirements

### A. jellystructure image endpoint (bounded on-demand proxy + cache)
1. Add `GET /api/tv/image/{itemId}/{type}` (`type` ∈ poster/backdrop/logo/still; plus an avatar variant),
   **public** like `/api/tv/channel-logos` (Coil sends no auth header). Serve-from-cache → else fetch
   upstream → cache to disk (atomic temp+rename) → `respondBytes`. Cache key `{itemId}-{type}` (the
   `MediaCard.id` is the Jellyfin id; episode stills keyed by the per-episode Jellyfin id from R82).
2. **Bound the outbound fetch with a single shared `Semaphore`** (mirror `LogoDownloader`), ideally a
   single shared bounded `HttpClient` (not a new Curl pool); the temp-file write sits inside the permit.
   This is load-bearing, not optional ([[reference-ktor-native-fd-setsize]]).
3. **Caching policy (open decision — see report §13):** the user asked for a short TTL, but a genuinely
   short TTL forces frequent cold misses = frequent bounded outbound fetches near the FD ceiling.
   Recommended: a **generous TTL + explicit invalidation** on re-pull / artwork change (file-mtime
   staleness check; invalidate on the existing artwork-write paths). Confirm the freshness-vs-safety
   trade-off before implementing; Coil also disk-caches client-side.
4. **Upstream source (open decision):** pull from **Jellyfin** (single upstream, already size-capped per
   R63) or from **TMDB** (stored `posterPath`/`backdropPath`/`stillPath`, independent of Jellyfin uptime).
   Default to Jellyfin for a single upstream + existing sizing; note the trade-off.

### B. Logos
5. Resolve the logo gap (open decision): proxy Jellyfin's `Images/Logo` (v1, identical coverage to today)
   **or** fetch+store a TMDB logo (`TmdbClient.getMovieImages/getTvImages` already return a language-tagged
   `logos[]`; would add a model field + scan/artwork-time fetch, reusing the `xx` no-language bucket).

### C. DTO + app switch
6. Change the image fields in the TV DTOs to **relative** paths (`/api/tv/image/…`) in the `toMediaCard`
   helpers + hero builders (replacing the `JellyfinImageUrl.*` calls).
7. App: **centralize** the relative→absolute resolution inside `RemoteImage` (`ImageLoader.kt`) by reading
   `LocalServerBaseUrl.current` and prepending when `startsWith("/")` — so every call site is covered at
   once and `HomeScreen` can drop its local resolution. **Keep the `startsWith("/")` guard** so the
   Discover tab's absolute TMDB URLs pass through untouched.

## Invariants
- The outbound image fetch is **always Semaphore-bounded** — no unbounded fan-out on the TV image path
  ([[reference-ktor-native-fd-setsize]]).
- Cache hits make **no outbound call**; the steady-state TV path is disk-read + `respondBytes`.
- Absolute URLs (TMDB Discover art) are **never** prepended — the `startsWith("/")` guard is load-bearing.
- The app loads no artwork directly from Jellyfin after this phase (streaming is unaffected).
- Renders server-pushed state only; image URLs come from the server DTO.

## Out of scope
- Pre-downloading all artwork at scan time as the primary mechanism (option (b) in the report) — heavier
  upfront; the on-demand-with-cache hybrid is preferred. May be revisited if the FD/TTL trade-off demands it.
- The Jellyfin-token exposure rewrite (deferred — separate token effort).
- Cast/person images (already TMDB-CDN, unchanged) and channel-button logos (already jellystructure-served).
- Streaming/subtitle URLs (stay direct→Jellyfin).

## Source references
- `src/linuxX64Main/.../tv/JellyfinImageUrl.kt` (URLs to replace), `tv/ChannelLogoStore.kt` (disk-serve
  precedent), `media/LogoDownloader.kt` (bounded on-demand-proxy precedent + `GET /api/people/{id}/image`),
  `media/ArtworkDownloader.kt` (Phase 47, do-not-reuse-unbounded note), `server/routes/TvRoutes.kt`
  (add `/api/tv/image/…`; channel-logos public-route pattern), `tmdb/TmdbClient.kt`
  (`getMovieImages/getTvImages` for logos)
- `ravilo-ui/.../seams/ImageLoader.kt` (`RemoteImage` — centralize prepend), `screens/HomeScreen.kt`
  (existing channel-logo resolution), all tile/hero/detail/avatar call sites
- Research report: `specs/research-reports/ravilo-jellyfin-decoupling-investigation.md` §9 (Workstream 2) + §13 (open decisions)
- **Independent of R82–R84** (can ship in parallel). Builds on **R63** (image sizing/Coil config),
  **Phase 31/78** (cache pattern + FD bounding), **R36/R39** (channel-logo serving).
