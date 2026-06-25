# Phase 31 — Studio & Network Logo Artwork (FR-SNA1)


## Problem
Phase 19 §5 specified TMDB **logo artwork** for studios and networks (fetch, cache, serve, plus a
batch "fetch all missing"), and the Metadata page already renders the UI for it — logo tiles, a
"no logo" fallback, and **Fetch (missing) logos** buttons. But the endpoints were never built:
`MetadataRoutes.kt` only returns the raw `logoPath` string from the stored item; there is no route
that downloads, caches, or serves a logo, and the "Fetch logos" buttons call nothing. This phase
finishes that feature.

## Current state (as-is)
- **Model** already carries the inputs (Phase 19 P2): `MediaItem.studioTmdbId` / `studioLogoPath`
  and `networkTmdbId` / `networkLogoPath`, populated by the scanner from TMDB
  `production_companies` / `networks`.
- **`GET /api/metadata/studios|networks`** return `MetadataEntry(name, count, tmdbId?, logoPath?)`
  where `logoPath` is the **TMDB path** (e.g. `/abc.png`), not a Jellystructure-served URL.
- **No** `POST`/`GET …/artwork` routes exist; **no** logo cache directory; the batch action is absent.
- `ArtworkDownloader.kt` already downloads poster/fanart/logo/stills for media items — the same
  streaming-download + atomic-write pattern should be reused here.
- TMDB has **no `/search/network`** endpoint (see `_investigation-findings.md` P2): a network logo is
  only obtainable from the `networkLogoPath` captured at scan time. Studios *can* be resolved by name
  via `/search/company` if `studioLogoPath` is missing.

## Requirements

### Backend — fetch, cache, serve
1. Logo cache directory under the data dir, e.g. `<data>/artwork/studios/<tmdbId-or-slug>.png` and
   `<data>/artwork/networks/<tmdbId-or-slug>.png`. Logos are transparent PNGs; base
   `https://image.tmdb.org/t/p/original`.
2. `POST /api/metadata/studios/{name}/artwork` — resolve the logo (from the captured `studioLogoPath`,
   else `/search/company` by name for studios) and download it to the cache. Returns `{ ok, cached }`
   or 404 when no logo is resolvable.
3. `POST /api/metadata/networks/{name}/artwork` — same, but networks can **only** use the captured
   `networkLogoPath` (no name search). When absent, return a clear "no logo available" result rather
   than an error.
4. `GET /api/metadata/studios/{name}/artwork` and `GET …/networks/{name}/artwork` — serve the cached
   PNG; **404** when not yet cached so the UI can fall back to the name tile.
5. `POST /api/metadata/studios/artwork/batch` and `…/networks/artwork/batch` — "fetch all missing"
   (mirror `POST /api/media/batch/artwork`): iterate distinct studios/networks, fetch any uncached
   logo, run async without blocking, and report a summary (`{ fetched, skipped, failed }`).
6. Extend `MetadataEntry` (or add a field) so `GET /metadata/studios|networks` indicates whether a
   **served** logo exists (`hasLogo: Boolean`) so the page doesn't probe every tile blindly.

### Frontend — wire the Metadata page
7. Studio/Network tiles request the served logo (`GET …/{name}/artwork`); on 404 show the existing
   **name-text fallback** tile (and, for networks with no captured path, a muted "no logo available").
8. The **"Fetch (missing) logos"** button calls the matching batch endpoint, shows progress, and
   re-renders tiles as logos land. Per-tile re-fetch on click is optional.
9. No new page or nav — this only activates controls already present on `/metadata` (Phase 19).

## Invariants (must hold)
- **Least-destructive / reuse:** use the existing `ArtworkDownloader` streaming download + atomic
  `.tmp`→rename write; do not pull whole images into memory.
- **Frontend renders server state only:** tiles reflect what the serve endpoint returns (200 vs 404),
  not a guess.
- **Fail soft:** a missing or unresolvable logo is a normal state (name fallback), never an error
  banner — especially for networks, where most logos may be unavailable.

## Out of scope
- Manual logo **upload** for a studio/network (could be a later enhancement).
- Backfilling `studioTmdbId`/`networkLogoPath` for items scanned before Phase 19 P2 — they fill in on
  next scan/sync; this phase doesn't force a re-scan.
- Genre/tag artwork (genres are text chips; tags use colored dots — both intentionally logo-less).
