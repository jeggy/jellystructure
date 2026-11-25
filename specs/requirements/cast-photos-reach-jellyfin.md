# Cast photos reach Jellyfin
Task: DEB-5534

> Owner, 2026-11-25 (DEB-5531): picked *"Cast photos: write a URL Jellyfin can fetch"* from the log review.

## Scope

Backend only: `NfoWriter`, and its callers that pass a server URL (`PipelineStepOps.writeNfo`, `DriftEvaluator`,
`NfoWriter.writeTracked`/`contentHash`). No route, DTO, schema, client or admin change. jellystructure's own person
photos (`GET /api/people/{tmdbId}/image`, the cast grid, Ravilo) are untouched.

**What this supersedes.** Phase 75 (`phase-75-cast-crew.md`, pruned in `95d7e04af`; read it with
`git show 95d7e04af^:specs/requirements/phase-75-cast-crew.md`) promised:

> *"The NFO `<thumb>` points at that route (stable, reachable by Jellyfin even if TMDB is rate-limited)."*
> `<thumb>https://<jellystructure>/api/people/<id>/image</thumb>`

That promise was never kept. Films, series and music videos write `<thumb>{api_keys.jellyfin_url}/api/people/{id}/image</thumb>`.
That is Jellyfin's own host, and Jellyfin answers 404. Episode guest stars write a bare relative
`<thumb>/api/people/{id}/image</thumb>`. Our own host would answer 401, because the route sits behind the admin's
auth. Opening it without auth would let anyone on the internet ask *"is this actor in this library?"*, which tells a
stranger what the household owns. So the thumb now names TMDB's image CDN, which is where our cache gets the photo from
anyway. Jellyfin downloads each photo once into its own People store, so it does not depend on TMDB after the first fetch.

Measured on prod (DEB-5531): on 2026-11-24 Jellyfin logged 127,681 *"Primary image … not found"* and 73,433
*"Cannot fetch image"* warnings, which is 79.7 of the day's 82 MB of log. One person was retried 4,512 times. Library
scans during the floods took up to 19 min 51 s against about 1 min normally. No cast photo has ever reached Jellyfin.

## Requirements

- **FR-1.** Every `<actor>` that jellystructure writes, in `movie.nfo`, `tvshow.nfo`, a music video's NFO and every
  episode NFO (each block of a multi-episode file too), carries
  `<thumb>https://image.tmdb.org/t/p/h632{profilePath}</thumb>` when the person has a non-blank TMDB `profilePath`.
  `{profilePath}` is the stored value as TMDB gives it, starting with `/`.
- **FR-2.** A person with no `profilePath` gets no `<thumb>` at all. Today a film's actor gets a thumb whenever its
  `tmdbId` is non-zero, which points at a photo that does not exist. An actor with a `tmdbId` of 0 is written the same
  as today, minus the thumb.
- **FR-3.** No NFO contains `/api/people/`, and no NFO's bytes depend on `api_keys.jellyfin_url` or any other
  configured address. The `serverUrl` parameter that existed only for the thumb goes away.
- **FR-4.** The change reaches an existing library in the same way as the release date did
  (`nfos-carry-the-release-date.md`, "One-time cost"). Every film, series and music video with cast now has a
  different content hash, so the `write_nfo` pass that next reaches it rewrites it once. That pass also rewrites the
  series' episode NFOs whose bytes change (that spec's FR-3/FR-4), and `sync_jellyfin` refreshes the item.
  - A foreign (hand-edited) NFO is still skipped unless overwrite is on.
  - Nothing forces a full pass automatically. Left to the schedule, a title gets its photos when it next falls due.
  - After a title's one rewrite, nothing writes it again until a field changes. Two consecutive passes still write 0
    files (that spec's Acceptance).

## Non-goals

- Making `/api/people/{tmdbId}/image` reachable without auth. It stays behind the admin's auth.
- Writing photos into Jellyfin's People folders ourselves.
- Crew (`<director>`, `<writer>`) photos. Jellyfin reads no thumb for them.

## Acceptance

1. **Unit test** (`NfoWriterTest`, `linuxX64Test`). For each of the four NFO kinds, an actor with
   `profilePath = "/abc.jpg"` writes `<thumb>https://image.tmdb.org/t/p/h632/abc.jpg</thumb>`. An actor with no
   `profilePath` writes no `<thumb>`. No output contains `/api/people/`. `contentHash` is the same whatever server
   URL is configured.
2. **Prod, after the deploy, with QA's claim.** Run a single-title *Write NFO + Sync* on one film and one series with
   cast. Jellyfin's log then shows no *"Cannot fetch image"* or *"Primary image … not found"* for those titles' people,
   and Jellyfin's `/Persons` or `/Items/{personId}/Images` answers a Primary image for at least one of their actors. A
   person Jellyfin already holds with a bad stored path (*"not found at "/api/people/…""*) picks up the new thumb on
   that refresh. If it does not, write that finding on the QA row, not a workaround.
3. **Prod, 24 h after the deploy, read-only.** The day's Jellyfin log has at most a few hundred people-image warnings
   instead of more than 100,000, and the scheduled scans are back near their normal length.
