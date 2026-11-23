# NFOs jellystructure writes carry the release date

Task: DEB-5440

> Owner, 2026-11-24 (DEB-5322): *"can you make sure to add the release date as well to the nfo files"*.

## Scope

Backend only (`NfoWriter`, `PipelineStepOps.writeNfo`). No route, DTO, schema or client change: `Episode.airDate` and
`MediaItem.releaseDate` already exist (R148, R325) and are filled from TMDB on every scan.

Every library NFO is written by jellystructure from TMDB, and none of them carried a date, so Jellyfin showed none even
where TMDB has one. The staged NFOs outside the library already carry `<aired>`/`<premiered>`.

## Requirements

- **FR-1.** An episode NFO (single and each block of a multi-episode file) contains `<aired>yyyy-MM-dd</aired>` and
  `<premiered>yyyy-MM-dd</premiered>`, the same value, whenever `Episode.airDate` is a `yyyy-MM-dd` date. Otherwise both tags
  are omitted, never written empty.
- **FR-2.** `tvshow.nfo` contains `<premiered>` and `movie.nfo` contains `<premiered>` and `<releasedate>`, from
  `MediaItem.releaseDate`, when it is a `yyyy-MM-dd` date; otherwise omitted. `<year>` stays. Music videos are unchanged.
- **FR-3.** The scheduled `write_nfo` pass, which wrote a series' episode NFOs only for a single-item run or the
  numbering repair of phase 153, also rewrites a series' episode NFOs when it has just rewritten that series' own `tvshow.nfo`.
  Episode NFOs carry no hash, so this is how an existing library receives FR-1 once, after the series hash moves with FR-2.
  A series whose NFO is unchanged writes no episode NFO, so there is no steady-state write.

## One-time cost

The new tag changes the content hash of every movie and series with a known `releaseDate`, so the first `write_nfo` pass
rewrites each of those once, plus every episode NFO of each such series; `sync_jellyfin` then refreshes them. A foreign
(hand-edited) NFO is still skipped unless overwrite is on. Nothing writes again until a field changes.
