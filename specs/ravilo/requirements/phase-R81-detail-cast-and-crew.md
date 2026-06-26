# Phase R81 — Detail screen: populate Cast & Crew (from jellystructure data)

> The Movie/Series/Discover detail screens already have a fully-built "Cast & Crew" row, but it never
> appears because the backend returns an empty cast list. Wire it from **jellystructure's own scanned
> cast/crew** (sourced from TMDB at scan time) — no Jellyfin request.

## Problem
Every detail screen renders a "Cast & Crew" row guarded by `if (detail.cast.isNotEmpty())`, backed by
the `CastCircle` component (photo or initials · name · role) and the `detail.cast` i18n label
(EN "Cast & Crew", DA "Medvirkende", FO "Leikfólk"). But the row is **never shown**: the backend
`DetailService` hard-codes `cast = emptyList()` for both movies and series. The data already exists in
jellystructure — the scanner stores `MediaItem.cast` and `MediaItem.crew` from TMDB (Phase 76/80) —
it was simply never read by the TV detail path.

## Architectural constraint (driving decision)
**Ravilo must request from Jellyfin as little as possible — ideally not at all. Jellyfin manages
streaming; the UI is served from jellystructure's own data.** Cast therefore comes from the scanned
`MediaItem`, not from Jellyfin's `People` API. This is also strictly better here: `DetailService`
already holds the `MediaItem` in memory (`all.firstOrNull { it.jellyfinId == … }`), so populating cast
costs **zero extra round-trips** — Jellyfin or otherwise. Person images come from the TMDB CDN
(`image.tmdb.org`), not Jellyfin.

## Current state (as-is)
- **UI is complete.** `ravilo-ui/.../components/CastCircle.kt` renders a 72.dp circular avatar
  (`RemoteImage` from `person.imageUrl`, else first initial), name, and role; D-pad focusable. Rendered
  in a `focusRestorer()` `LazyRow` keyed by `person.id` on `MovieDetailScreen.kt` (≈ 204–220, header
  `str("detail.cast")`), `SeriesDetailScreen.kt` (≈ 317), and `DiscoverDetailScreen.kt` (≈ 168–170 via
  `CastSection`).
- **TV DTO is ready.** `shared/.../tv/Models.kt`:
  `data class Person(val id, val name, val role: String?, @SerialName("image_url") val imageUrl: String?)`;
  `MovieDetail.cast` and `SeriesDetail.cast` both `List<Person>`.
- **i18n is ready.** `"detail.cast" → "Cast & Crew" / "Medvirkende" / "Leikfólk"`.
- **jellystructure already stores the data.** `src/commonMain/.../model/Media.kt`:
  `MediaItem.cast: List<Person>` and `MediaItem.crew: List<Person>` (model `Person` =
  `tmdbId, name, profilePath, character, role, job, department, order, type, …`). The scanner
  (`media/Scanner.kt`) populates them from TMDB `getMovieFullCredits` / aggregate credits — cast sorted
  by billing `order`, crew with `job`/`department`/`type` (Director/Writer/…).
- **Backend was the only gap.** `src/linuxX64Main/.../tv/DetailService.kt`: `cast = emptyList()` at the
  movie and series constructors.
- **TMDB image precedent exists.** `server/routes/TvRoutes.kt` already builds the TV `Person` from TMDB
  credits as `profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }` (the Discover detail path).

## Requirements

### A. Map jellystructure cast/crew → the TV Person DTO
1. Add a `castFrom(item: MediaItem): List<Person>` helper in `DetailService` that maps
   `item.cast + item.crew` to the shared TV `Person`:
   - skip blank-named entries; **dedupe by `tmdbId`** (a person credited as both actor and crew keeps
     their cast entry — cast is concatenated first) so the `LazyRow` `key = person.id` never collides;
   - `id` = `tmdbId.toString()`;
   - `role` = `character` (actors) ?: `job` ?: `role` ?: `department` ?: `type` (so crew rows still
     read meaningfully, e.g. "Director");
   - `imageUrl` = `"https://image.tmdb.org/t/p/w185" + profilePath` when present, else `null` (the
     `CastCircle` initials fallback covers it);
   - preserve order (cast in TMDB billing order first, then crew) and **cap** at ≈ 20.

### B. Wire into both detail paths
2. Movie: `cast = castFrom(item)`.
3. Series: `cast = castFrom(item)`.
   Both reuse the already-loaded `item` — **no Jellyfin call, no new DB query, no TMDB call at request
   time** (the data was fetched and stored at scan time).

## Invariants
- **Jellyfin is not consulted for cast.** No `People` field is requested; `getItemDetail`'s `Fields`
  query is unchanged. This phase must not add any Jellyfin round-trip.
- Renders **server-pushed state only** — cast is jellystructure-owned (scanned from TMDB), never
  client-guessed.
- Person images stream from the TMDB CDN (same pattern as the Discover detail and admin Library),
  independent of Jellyfin.
- `LazyRow` keys must be unique → the dedupe-by-`tmdbId` rule is load-bearing, not cosmetic.

## Out of scope
- A dedicated person/filmography screen on selecting a cast circle (display-only, as today).
- Splitting into separate "Cast" and "Crew" rows — one mixed row, matching the design.
- Re-scanning to backfill cast for items scanned before Phase 76/80 (those simply show no row until a
  re-pull) — no migration here.
- Per-episode guest stars on series — series-level `item.cast`/`item.crew` only.
- Re-sourcing runtime / audio-languages away from Jellyfin (R75 still reads Jellyfin `MediaStreams`);
  the broader "Ravilo off Jellyfin" migration is tracked separately.

## Source references
- `src/linuxX64Main/.../tv/DetailService.kt` (`castFrom(item)`; movie ≈49 / series ≈124 call sites),
  `src/commonMain/.../model/Media.kt` (`MediaItem.cast`/`.crew`, model `Person`),
  `src/linuxX64Main/.../media/Scanner.kt` (TMDB credit population ≈ 843–867),
  `src/linuxX64Main/.../server/routes/TvRoutes.kt` (TMDB→TV `Person` image precedent ≈ 381),
  `shared/.../tv/Models.kt` (`Person`, `MovieDetail.cast`, `SeriesDetail.cast`),
  `ravilo-ui/.../components/CastCircle.kt`, `MovieDetailScreen.kt` + `SeriesDetailScreen.kt` +
  `DiscoverDetailScreen.kt`, `ravilo-ui/.../i18n/Strings.kt` (`detail.cast`).
- Design: `design/ravilo/Ravilo TV.html`, `design/ravilo/ravilo-app.js` (`castCircle`, `castFor`),
  `design/ravilo/ravilo.css` (`.cast`, `.cast-av`, `.cast-n`, `.cast-r`).
- Related: **Phase 76/80** (scanner cast/crew from TMDB — the data source), **R76** (minimize Jellyfin
  round-trips — same spirit), **R63** (image byte-capping), **R75/R78** (other detail enrichments).
