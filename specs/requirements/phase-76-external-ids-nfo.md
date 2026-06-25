# Phase 76 — External provider IDs (IMDb / TheTVDB) from TMDB → NFO (FR-XI1)

**Status:** ◻ Planned · _design_

> Builds on the write-through model of
> **[Phase 74](archive/phase-74-metadata-write-through.md)** (DB on edit · Save→NFO · Sync→Jellyfin)
> and the TMDB-ingest pattern of **[Phase 75](archive/phase-75-cast-crew.md)**. Reuses the existing
> TMDB `external_ids` plumbing already present for Sonarr
> (`TmdbClient.getTvTvdbId` / `TmdbExternalIds`).

## Problem

Jellyfin renders an item's external links (the **IMDb ↗**, **TheTVDB ↗** buttons on a movie/series
page) **only when the item carries the matching provider id**. Jellystructure currently writes just
the TMDB id into the NFO:

```xml
<tmdbid>1234</tmdbid>
<uniqueid type="tmdb" default="true">1234</uniqueid>
```

So after a Jellystructure sync, Jellyfin knows the TMDB id but **not** the IMDb id — the user cannot
open IMDb directly from the item in Jellyfin. TMDB already knows the IMDb id (and, for series,
TheTVDB id); we simply never fetch or persist it.

## Goal

Fetch **all useful external ids** from TMDB, store them on the item, and write them into the NFO as
additional `<uniqueid>` entries so that **on Save → NFO (and Sync → Jellyfin)** Jellyfin gains the
IMDb / TheTVDB links — letting the user open IMDb directly from the item in Jellyfin.

## TMDB ingest

- TMDB endpoints:
  - **Movie** — `GET /movie/{id}/external_ids` → `imdb_id` (`tt…`), plus `wikidata_id`,
    `facebook_id`, `instagram_id`, `twitter_id`.
  - **TV** — `GET /tv/{id}/external_ids` → `imdb_id`, `tvdb_id`, `tvrage_id`, `wikidata_id`, socials.
- Extend the existing `TmdbExternalIds` DTO (currently `tvdb_id` only) with `imdb_id` (String) and
  add one `getExternalIds(tmdbId, isMovie): TmdbExternalIds?` call. Fold the existing `getTvTvdbId`
  (Phase 56, Sonarr bridge) onto this single fetch so there is **one** external-ids call path.
- Fetched at **scan** and on **"Re-pull from TMDB"** — same trigger points where details/credits are
  already pulled (Scanner `buildMovieItem` / TV paths). A null/blank result leaves the ids unset; no
  error, mirroring how missing details are handled.
- **Scope of "all":** persist + write the ids Jellyfin actually consumes — **IMDb** and (TV)
  **TheTVDB**. The social/wikidata ids are out of scope (Jellyfin has no provider link for them);
  capture them only if it's free, but do **not** write them to the NFO.

## Data model

- `MediaItem` gains `imdbId: String? = null` and `tvdbId: Int? = null` (TV only; null for movies).
- `Episode` optionally gains `imdbId`/`tvdbId` — see **Episodes** below (phase-optional).
- Both default-null and additive, so existing persisted rows deserialize unchanged.

## NFO sync

`NfoWriter` emits the new ids alongside the existing TMDB block. TMDB stays `default="true"`
(Jellystructure remains the TMDB-keyed source of truth); IMDb/TVDB are added as non-default
`<uniqueid>` so Jellyfin builds the external links without re-keying the item.

**Movie (`movie.nfo`):**
```xml
<tmdbid>1234</tmdbid>
<uniqueid type="tmdb" default="true">1234</uniqueid>
<uniqueid type="imdb">tt1234567</uniqueid>   <!-- only when known -->
<imdbid>tt1234567</imdbid>                     <!-- legacy element some scrapers prefer -->
```

**Series (`tvshow.nfo`):** as above plus
```xml
<uniqueid type="tvdb">456789</uniqueid>        <!-- only when known -->
```

- Each `<uniqueid>`/`<imdbid>` line is emitted **only when the id is non-null/non-blank** — never
  write an empty provider id.
- Phase 22 invariant holds: **no `<lockdata>`**. Ids are written unlocked; Jellyfin merges them on
  re-read.

## How Jellyfin surfaces it (verification)

After **Save → NFO** then **Sync Jellyfin** (or Jellyfin's next library scan), the item's
`<uniqueid type="imdb">` is read by Jellyfin's IMDb/TMDB/TVDB metadata plugins, which render the
**IMDb ↗** (and **TheTVDB ↗** for series) external links on the item page. This is the same
mechanism Jellyfin uses for its own scrapers — Jellystructure only needs to write a reachable id.

## Detail-page UI (optional, low-cost)

- The Phase 10 detail **pagebar** external links (Jellyfin ↗ / TMDB ↗) gain an **IMDb ↗** link when
  `imdbId` is known (`https://www.imdb.com/title/<imdbId>/`), and **TheTVDB ↗** for series with a
  `tvdbId`. Purely a convenience mirror of what Jellyfin will show; no editing.
- IMDb/TVDB ids are **read-only / derived from the TMDB id** (unlike the editable TMDB id of
  Phase 24). Changing the TMDB match (Phase 32) re-fetches them on the next pull.

## Episodes (phase-optional)

TMDB also exposes `/tv/{id}/season/{s}/episode/{e}/external_ids` (`imdb_id`, `tvdb_id`). Writing
`<uniqueid type="imdb">` into each `episodedetails.nfo` would give per-episode IMDb links in
Jellyfin. **Deferred unless cheap:** it adds one TMDB call per episode (rate-limit cost on large
series). If included, gate it behind the existing episode-cap (Phase 49) and reuse the same
`getExternalIds` shape against the episode endpoint.

## Scope / invariants

- Trigger model follows Phase 74: ids land in the **DB** at scan/re-pull; **Save → NFO** writes
  them; **Sync Jellyfin** asks Jellyfin to re-read. No new save action.
- One external-ids fetch path shared with the Sonarr `tvdbId` bridge (Phase 56) — no parallel call.
- TMDB remains the `default="true"` uniqueid; IMDb/TVDB are additive and never re-key the item.
- Additive, null-default model fields → no migration; old NFOs without the ids are unaffected.

## Mockup

No new screen. The optional pagebar link reuses the Phase 10 external-link styling on
`design/app/media.html` / `design/app/series.html` (add an **IMDb ↗** chip beside Jellyfin ↗ /
TMDB ↗).
