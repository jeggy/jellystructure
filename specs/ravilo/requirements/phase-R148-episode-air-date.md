# Phase R148 — Series detail: show each episode's release (air) date in the episode picker

> The Series detail episode picker shows number · title · runtime · description, but never **when the
> episode first aired**. Surface the per-episode air date — sourced from the TMDB episode lookup the
> scanner **already performs** — as a line under each episode title. No request-time Jellyfin or TMDB call.

## Problem
On `SeriesDetailScreen`, the "Episodes" row renders an `EpisodeCard` per episode (still · number ·
runtime badge · title · 2-line description · watched toggle). It never shows the air date, so a viewer
browsing a season can't tell how old an episode is, or when a newly-added one dropped.

## Architectural constraint (driving decision)
**Ravilo requests from Jellyfin as little as possible — ideally not at all. Jellyfin manages streaming;
the UI is served from jellystructure's own scanned data.** The air date therefore comes from the scanned
episode `MediaItem`, not from Jellyfin's `PremiereDate`. `DetailService` already holds the season's
episodes in memory when it builds `SeriesDetail`, so reading the date costs **zero extra round-trips** at
request time.

## Current state (as-is) — corrected
- **Design is built.** `design/ravilo/ravilo-app.js`: `ep(n, title, dur, desc, pct, air)` carries an ISO
  `air` date; `episodeCard` renders `<div class="ep-date">` under `.ep-t` via `epAirLabel(iso)` (locale
  `month: short`, UTC-pinned so the day never drifts), shown only when `air` is set. `ravilo.css`
  `.ep-date` = JetBrains Mono · 13px · `--ink-dim` · 4px top margin.
- **The data is NOT captured yet.** Contrary to an earlier assumption, the scanner does **not** store the
  episode air date. The scanner *does* call `TmdbClient.getEpisodeDetails(...)` per episode (for title,
  overview, still, runtime), and TMDB's episode object includes `air_date` — but neither
  `TmdbEpisodeDetails` nor the `model.Episode` model carries it, so it is dropped on the floor.
- **Three layers need the field added:** the TMDB DTO, the scanned `Episode` model, and the TV `Episode`
  DTO — plus the scanner populating it and the screen rendering it.

## Requirements

### A. Capture the air date in the scan pipeline (jellystructure-owned, no new round-trip)
1. Add `@SerialName("air_date") val airDate: String? = null` to `TmdbEpisodeDetails` (the episode lookup
   the scanner already makes — no extra TMDB call).
2. Add `val airDate: String? = null` (ISO `yyyy-MM-dd`) to `model.Episode`.
3. In `Scanner`, at every episode-build site, set `airDate = epDetails?.airDate?.takeIf { it.isNotBlank() }`,
   preserving the existing value on incremental re-scans (mirror how `tmdbEpisodeId` / `runtime` are kept).

### B. Carry the air date on the TV Episode DTO + populate it
4. Add `@SerialName("air_date") val airDate: String? = null` to the shared TV `Episode` model. Nullable +
   defaulted so older clients and dateless episodes are unaffected.
5. In `DetailService.getSeriesDetail`, set the TV `Episode.airDate` from the scanned episode's `airDate`
   (date-only). **No Jellyfin call, no new DB query, no TMDB call at request time** — reuse the loaded item.

### C. Render it in the episode picker
6. In `EpisodeCard`, when `episode.airDate != null`, show a formatted date line under the title (locale
   month abbreviation · day · year). Parse the `yyyy-MM-dd` string directly into y/m/d and format from
   those parts — **UTC-pinned by construction** (no `Instant`/timezone, so the calendar day never shifts).
   When null, render nothing — no empty row, no placeholder. Matches the `.ep-date` mock treatment.

## Invariants
- **Jellyfin is not consulted for the air date.** `getItemDetail`'s `Fields` query is unchanged; this
  phase adds no Jellyfin round-trip.
- Renders **server-pushed state only** — the date is jellystructure-owned (scanned from TMDB), never
  client-derived or guessed.
- Date is stored/transported as a date-only ISO string; all display formatting is UTC-pinned so the
  calendar day never shifts by timezone.

## Out of scope
- Sorting/grouping episodes by air date, or an "aired vs unaired" filter (display only).
- A full air-time (clock time) or "next airing" countdown for ongoing series.
- **Backfilling dates for already-scanned items** — they carry no air date until **re-scanned** (the field
  is new; existing rows show no date line until then). No migration.
- Movie release date on the Movie detail screen — series episode picker only this phase.

## Source references
- Design: `design/ravilo/Ravilo TV.html`, `design/ravilo/ravilo-app.js` (`ep(...)`, `episodeCard`,
  `epAirLabel`), `design/ravilo/ravilo-data.js` (per-episode `air`), `design/ravilo/ravilo.css`
  (`.ep-date`, `.ep-d`, `.ep-t`).
- Backend: `src/linuxX64Main/.../tmdb/TmdbClient.kt` (`TmdbEpisodeDetails`),
  `src/commonMain/.../model/Media.kt` (`Episode`), `src/linuxX64Main/.../media/Scanner.kt` (episode
  build sites), `src/linuxX64Main/.../tv/DetailService.kt` (series episode mapping),
  `shared/.../tv/Models.kt` (TV `Episode`), `ravilo-ui/.../components/EpisodeCard.kt`.
- Related: **Phase 76/80/R81** (scanner + detail enrichment from TMDB — same data-source spirit),
  **R76** (minimize Jellyfin round-trips), **R75/R78** (audio/subtitle flags).
