# Phase R82 — Store static playback data at scan time (runtime, episode Jellyfin id, season names)

> Persist the small set of **static, per-file facts** that the TV detail path currently re-fetches
> from Jellyfin on every open — item & episode runtime, the per-episode Jellyfin id, and season
> display names. Once stored, the detail endpoints no longer need Jellyfin for anything except
> per-user state (R83 then makes them catalog-only).

## Problem
Opening a movie or series in Ravilo makes a live Jellyfin call (`getItemDetail` /
`getSeriesEpisodes`) that returns a mix of **static** data (runtime, the per-episode playable id,
season names, audio/sub stream list) and **per-user** data (resume position, watched flag). The
static half never changes between scans, yet it is re-fetched on every detail open because it is not
stored locally. This forces a Jellyfin round-trip on the detail critical path and is the only reason
series detail calls Jellyfin at all (it exists purely to map `(season, episode) → Jellyfin id`).

## Architectural constraint (driving decision)
**Ravilo is served from jellystructure's own data; Jellyfin manages streaming, nothing else.**
(See [[ravilo-off-jellyfin-data]].) Static catalog facts belong in the local `MediaStore`, captured
at scan time, not fetched live. This phase stores them; **R83** consumes them to drop the live calls.

## Current state (as-is)
- **No runtime field anywhere.** `src/commonMain/.../model/Media.kt` `MediaItem` and `Episode` have
  no `runtime`/duration. `DetailService` reads `runTimeTicks` live (`getItemDetail` for movies,
  `getSeriesEpisodes` for episodes).
- **`Episode` has no Jellyfin id.** `Episode` (`Media.kt`) stores `filename/path/season/episode/
  title/overview/stillPath/tmdbEpisodeId/tracks/…` but **not** the Jellyfin item id. Series detail
  (`tv/DetailService.kt` `getSeriesDetail`) calls `getSeriesEpisodes` *only* to obtain `jfEp.id` (the
  playable id used by `playback/start` and the still image), falling back to `ep.path` when absent.
- **Season names come from Jellyfin** (`jfEp`/season payload `seasonName`); no local `Season` model,
  fallback "Season N".
- **Audio/sub languages are already local for series, still Jellyfin for movies.** `MediaItem.tracks`
  (ffprobe: kind AUDIO/SUBTITLE + `language`) already carry this; R75/R78 populate **series** detail
  flags from scanned tracks, but **movie** `audioLanguages`/`subtitleLanguages` still come from
  Jellyfin `MediaStreams` in `DetailService` (the only reason is R75/R78 stream ordering — the data
  exists locally).
- **Movie Jellyfin id is already stored** (`MediaItem.jellyfinId`), so movie playback already has its id.
- The scanner (`media/Scanner.kt`) already fetches TMDB + Jellyfin item data per item and is the
  natural place to capture these facts; episodes are enumerated there (Phase 49 full-episode coverage).

## Requirements

### A. Model — add the static fields
1. `MediaItem.runtime: Int?` (minutes; nullable). 
2. `Episode.runtime: Int?` (minutes; nullable).
3. `Episode.jellyfinId: String?` — the Jellyfin item id for that episode (the playable id).
4. Season display names — store `MediaItem.seasonNames: Map<Int, String>` (season number → name), or
   equivalent; absence falls back to "Season N" as today. (Minor; do not invent a full `Season` model.)
   All new fields default to null/empty so existing serialized rows deserialize unchanged.

### B. Scanner — populate them
5. Populate `MediaItem.runtime` from the item's runtime (Jellyfin `RunTimeTicks` already fetched during
   scan, or TMDB runtime) at scan + re-pull time.
6. Populate each `Episode.jellyfinId` and `Episode.runtime` during episode enumeration (the scan
   already lists episodes; capture the Jellyfin episode id + runtime then).
7. Populate `seasonNames` from the data already pulled for the series (TMDB seasons / Jellyfin season
   payload).
8. These write on **full scan**, **re-pull from Jellyfin**, and **re-pull from TMDB** consistently
   (mirror how existing fields are set in each path).

### C. Make movie audio/sub languages local (parity with series)
9. Populate movie `audioLanguages`/`subtitleLanguages` in `DetailService` from `MediaItem.tracks`
   (kind AUDIO/SUBTITLE `language`), the same source series already uses — so neither movie nor series
   detail needs Jellyfin `MediaStreams` for the language strips. Preserve R75/R78 ordering/dedupe/cap
   behaviour using the local track order.

## Invariants
- New fields are **additive and nullable/empty-defaulted** — no DB migration breakage; pre-R82 rows
  read fine and simply show the live-fetch fallback until re-scanned.
- Renders **server-pushed state only**; these are scanned facts, never client-derived.
- This phase **does not yet change the detail endpoints' Jellyfin calls** — it only adds and populates
  storage. R83 flips the detail path to read local. (Keeping them separate means R82 is safe to ship
  alone and verify by inspecting stored data.)
- Streaming is untouched — `playback/start` keeps using the Jellyfin id (now sourced locally for episodes).

## Out of scope
- Removing the `getItemDetail`/`getSeriesEpisodes` calls — that is **R83** (this phase just makes the
  data available so R83 can).
- A backfill/auto-migration for items scanned before R82 — they show the live fallback until the next
  scan/re-pull (no migration; matches R81's stance).
- Rich media-source info (channels, bitrate, container, per-stream `DisplayTitle`) — the live
  `PlaybackInfo` negotiation (R56) still owns the direct-play/transcode decision at playback start.
- Per-user data (resume/watched) — stays on Jellyfin (R83/R84 read it separately).

## Source references
- `src/commonMain/.../model/Media.kt` (`MediaItem`, `Episode` — new fields)
- `src/linuxX64Main/.../media/Scanner.kt` (full scan + re-pull population paths; episode enumeration)
- `src/linuxX64Main/.../tv/DetailService.kt` (`getMovieDetail`/`getSeriesDetail` — movie audio/sub from
  local tracks; episode id/runtime/season-name now from local)
- `src/linuxX64Main/.../auth/JellyfinClient.kt` (`getItemDetail`, `getSeriesEpisodes` — the calls R83
  will drop for catalog)
- Research report: `specs/research-reports/ravilo-jellyfin-decoupling-investigation.md` §8 (Workstream 1)
- Related: **R75/R78** (detail audio/sub flags — series already local), **R81** (cast already local),
  **R56** (PlaybackInfo negotiation stays), **Phase 49** (episode coverage in the scan).
