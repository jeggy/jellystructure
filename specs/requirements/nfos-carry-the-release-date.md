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

The new tag changes the content hash of every movie and series with a known `releaseDate`, so the `write_nfo` pass that
next reaches each of those rewrites it once, plus every episode NFO of the series whose bytes change; `sync_jellyfin` then
refreshes them. A foreign (hand-edited) NFO is still skipped unless overwrite is on. Nothing writes again until a field
changes.

The scheduled pass reaches only the items its freshness filter makes due, so left to the schedule an existing title gets
the date when it next falls due (`pull_tmdb`'s `refresh_older`). A forced full pass (`POST /api/pipeline/run?full=true`)
gets the whole library at once. Nothing forces that pass automatically; prod had one on 2026-11-24, during QA (DEB-5442).

## Amendment 2026-11-24 — write_nfo settles (DEB-5458)

QA on DEB-5442 ran three forced full passes back to back on prod. They wrote about 8.9k NFOs, then 170, then 60, and never
reached 0. Two causes were found, both in episode NFOs, which carry no hash:

- FR-3 rewrites every episode NFO of a series on any move of the series hash, not only one caused by FR-2. That hash
  covers the whole `tvshow.nfo` (plot, cast, genres, `<mpaa>`), so a single changed field on a series rewrote all of
  its episode files.
- The phase 153 repair rewrites the files of every episode with no `jellyfinId` or no Jellyfin `IndexNumber` on every
  pass, whether or not they changed, and bumps `nfoWrittenAt`. So `sync_jellyfin` sends a full Jellyfin refresh for
  that series every hour, for as long as Jellyfin never resolves the episode. On 2026-11-24 that was 8 files a pass on
  one series.

A third cause was observed but not explained. The `tvshow.nfo` hash of some series moved between passes in which no step
wrote their fields: one series on every pass, and nine others between passes 2 and 3. FR-3 then rewrote every episode of
each of them, which was 41 files a pass for the every-pass series. FR-5 covers it.

The fix keys every episode write on its content. FR-3 is not limited to a release-date move, which would need a one-time
marker and would miss a real change such as a cast edit, which also reaches the episode NFO through the inherited cast.

- **FR-4.** Every episode NFO that `PipelineStepOps.writeNfo` writes, whether through FR-3, the phase 153 repair, or
  `includeEpisodes`, is first compared with the file at its path. A file is written only when none exists or its bytes
  differ from what would be written; a multi-episode file is compared as the one combined NFO. FR-3 itself stands: a
  rewritten `tvshow.nfo` still re-checks every episode NFO of its series, and writes only the ones that differ.
  The manual "Write NFO" routes in `MediaRoutes` keep writing unconditionally.
- **FR-5.** A series' `tvshow.nfo` is rewritten only when a stored field it is built from has changed since the last
  write. Two consecutive `write_nfo` passes with nothing between them that writes the item give the same `contentHash`,
  and the hash that `writeNfo` persists is still the stored `nfoHash` at the next pass. No ingest, scan or other step writes
  back an older `nfoHash` over a newer one. This was already promised ("Nothing writes again until a field changes");
  FR-5 names it so that a fix can cite it. The cause is not yet known. Start from the two on-disk versions of one
  affected series' `tvshow.nfo`, and from the `realtime_ingest` that `media_history` shows for the every-pass series at
  every `:30`.
  What the build does for FR-5 (DEB-5458): a series whose stored hash differs from the would-be hash, but whose file on disk
  already holds the would-be bytes, records the hash and writes nothing; and cast members that tie on `order` (TMDB hands out
  duplicates, 93 of 272 prod series have one) are written in `tmdbId` order, in the series and in the episode NFOs, so the
  bytes do not depend on the order of the stored list. The prod forensics on 2026-11-24 found every `tvshow.nfo` equal to
  what its stored fields build, so the movers (4 of 272 across the QA passes, 3 of them with tied cast) are not proven to
  be the tie order. QA's second forced pass decides it.
- **FR-6.** `writeNfo` bumps `nfoWrittenAt` for an episode write only when FR-4 wrote at least one file. A repair whose
  files are already on disk and current sends no Jellyfin refresh. That episode stays on Triage
  (`unresolved_jellyfin_id`, or the missing `IndexNumber`) until a scan resolves it or its NFO content changes. This
  amends phase 153 FR-SCAN2-3; see the dated note in `phase-153-scheduled-episode-nfo-repair.md`.

**Acceptance.** Two consecutive forced full passes (`write_nfo` and `sync_jellyfin` only, every other step skipped), with
no field written between them: the second pass writes 0 NFO files and sends 0 Jellyfin refreshes. That includes a
series that has an unresolved episode. A unit test covers each of these: a series whose hash moves but whose episode
NFOs are unchanged writes no episode file; a cast change on the series rewrites the episode files that change; a repair
episode whose NFO is already current writes nothing and leaves `nfoWrittenAt` alone; a missing repair NFO is written
and bumps it.
