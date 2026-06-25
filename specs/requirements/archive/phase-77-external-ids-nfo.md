# Phase 77 — External IDs in NFO (IMDb / TVDB) (FR-XI1)

**Status:** ✓ Done

> Fetches IMDb and TVDB identifiers from TMDB and writes them to NFO files so Jellyfin can
> cross-link items to external databases without a separate scraper pass.

## Problem

Jellystructure was already fetching TMDB IDs and writing `<tmdbid>` to NFOs (Phase 24), but
Jellyfin also reads `<imdbid>` / `<uniqueid type="imdb">` and `<uniqueid type="tvdb">` to
populate its "External Links" panel and to de-duplicate library entries. Without these tags,
operators had to add them by hand after every NFO write.

## Fix

**TMDB client** (`TmdbClient.kt`) — added `getExternalIds(tmdbId, isMovie)` calling
`/movie/{id}/external_ids` or `/tv/{id}/external_ids`, returning `TmdbExternalIds(imdbId, tvdbId)`.

**Data model** (`MediaItem`) — added `imdbId: String?` and `tvdbId: Int?` fields.

**Scanner** — calls `getExternalIds` in both the movie and TV scan paths; stores the results on
the item.

**NFO writer** (`NfoWriter.kt`) — writes:
- `<imdbid>` + `<uniqueid type="imdb">` when `imdbId` is present (movies + series)
- `<uniqueid type="tvdb">` when `tvdbId` is present (series)

## Scope

Backend-only. No UI changes — the detail page already shows the TMDB ID; IMDb/TVDB are
surfaced only in the NFO file and via Jellyfin's own display.
