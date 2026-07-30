# Phase 151 — Manually-selected images are never overwritten automatically (FR-ART2)

> Phase 133 made a manually-picked **poster/backdrop** survive a TMDB sync by locking it at
> `MediaStore.addOrUpdate`. It reverted anyway, because the two routes an operator actually clicks —
> **Sync** and **Re-pull from Jellyfin** — persist their freshly-scanned item through
> `MediaStore.updateOne`, which never had the guard. Close that hole, and extend the guarantee to
> **every** image an operator can choose: clearlogo, season posters and episode stills.

**Status:** ✓ Done.

## Problem (as reported)
"Selecting a poster image for an entry works, but when a new scan or re-pull from TMDB happens it gets
overwritten again." Reproduced by inspection and root-caused below.

## Root cause — the Phase-133 guard sat on the wrong choke point
- `MediaStore.addOrUpdate` applied `preserveLockedArtwork`; `MediaStore.updateOne` did not.
- `POST /api/media/{id}/sync` (`MediaRoutes.kt`), `POST /api/media/{id}/repull-jellyfin` and
  `pushToJellyfin` each build a **fresh** item via `Scanner.rescanMetadata` / `syncMovie` /
  `syncSeriesEpisodes` / `rescanFromJellyfin` — all of which reset `posterPath`/`backdropPath` to
  TMDB's current default — and then store it with `updateOne`. The lock was therefore bypassed on
  exactly the operator-facing paths, while the scheduled scan (which uses `addOrUpdate`) was fine.
- Secondary gaps found while tracing every automatic writer:
  - `addOrUpdate` keyed the lock off `existing`, so a **slug rename** (id change; the Phase-122 twin
    path, where the predecessor is `stale`, not `existing`) silently lost the lock.
  - `clearlogo`, **season posters** and **episode stills** had no lock at all. On-disk they were only
    protected incidentally, by `ArtworkDownloader.fetch` being skip-if-present.
  - The picker's **Generate from frame** wrote `.src = "screengrab"`, which R131 defines as the
    lowest priority — so the next "Download artwork" run replaced the operator's chosen frame with
    TMDB's still.
  - `fetch`'s legacy misplaced-artwork cleanup deletes `<parent-of-series-dir>/poster.jpg`, i.e.
    usually a **library-root** image, on every TV fetch — including one placed there deliberately.

## Requirements

### FR-ART2-1 — Apply the lock at every store write choke point
`preserveLockedArtwork` moves out of `MediaStore` into `media/ArtworkLock.kt` (with `ArtworkAsset`
naming the keys) and is applied by **both** `addOrUpdate` and `updateOne`. `updateOne` takes
`respectArtworkLock: Boolean = true`; only the explicit artwork routes (the operator action that
*sets* the lock) pass `false`, so a deliberate re-pick still wins. `addOrUpdate` keys the guard off
`old` (`stale ?: existing`) so the lock survives a slug rename.

### FR-ART2-2 — One marker that covers every asset, including those with no model field
An `<image>.manual` sidecar written next to any image an operator picks, uploads or generates
(reusing the R131 `.src` sidecar precedent, so it survives a DB reset and travels with the media).
Covers `poster.jpg`, `fanart.jpg`, `clearlogo.png`, `seasonNN-poster.jpg` and `*-thumb.jpg`.
`MediaItem.lockedArtwork` remains what protects the **metadata**; the marker protects the **file**.

### FR-ART2-3 — Every automatic writer honours it
- `fetchEpisodeStill` no longer upgrades a `screengrab` still that is marked manual.
- `screengrabEpisodeStill` (the picker's explicit "Generate from frame") marks the file manual while
  keeping the honest `screengrab` source label.
- `isArtworkIncomplete` stops counting a locked screen-grab as incomplete — otherwise its series
  would be re-processed on every "missing" run forever now that the upgrade is refused.
- `fetch`'s misplaced-artwork cleanup skips a marked file.
- `fetch`'s automatic season-poster gap-fill passes `manual = false`; every other `saveAsset` /
  `saveSeasonPoster` / `saveEpisodeStill` caller is an explicit pick and marks the result.

### FR-ART2-4 — Make the lock observable
`ArtworkStatus` gains `posterManual`/`fanartManual`/`logoManual`, `EpisodeStillStatus` gains
`manual`, and the seasons DTO gains `posterManual` — all additive and defaulted (the admin client
runs `ignoreUnknownKeys = true`).

## Invariants
- **A manually selected image survives every automatic path** — scheduled scan, `pull_tmdb`,
  `sync_*`, `fetch_artwork`, per-item Sync / Re-pull from Jellyfin, realtime ingest, `pushToJellyfin`.
  Only an explicit operator re-pick/upload/regenerate replaces it.
- **`download_artwork` stays skip-if-present** — the marker records *why* a file must stay; it never
  becomes the only thing standing between an operator's image and an overwrite.
- **Additive and defaulted throughout** — no DB migration, no backfill; a marker's absence simply
  means "not operator-chosen".

## Out of scope
- A "lock/unlock artwork" admin UI (re-picking a TMDB candidate already re-points the lock).
- Retro-marking artwork chosen before this phase (an operator re-picks once and it sticks).
- Writing the manual image back to Jellyfin.

## Source references
- Guard: `media/ArtworkLock.kt` (new), `media/MediaStore.kt` (`addOrUpdate`, `updateOne`).
- Markers + automatic writers: `media/ArtworkDownloader.kt` (`isManual`/`markManual`, `fetch`,
  `fetchEpisodeStill`, `screengrabEpisodeStill`, `isArtworkIncomplete`, `saveAsset`,
  `saveSeasonPoster`, `saveEpisodeStill`).
- Manual routes: `server/routes/MediaRoutes.kt` (`/artwork/upload`, `/artwork/candidates/save`,
  `/seasons/{season}/poster/save`, `/episodes/{f}/still/save`, `/episodes/{f}/still/screengrab`).
- Bypassed paths fixed: `MediaRoutes.kt` `POST /{id}/sync`, `POST /{id}/repull-jellyfin`,
  `pushToJellyfin`.
- Tests: `src/linuxX64Test/.../media/ArtworkLockTest.kt`.
- Related: **Phase 133** (the original lock), **R131** (the `.src` "manual" precedent), **Phase 94**
  (`mergeUserGenres` — the preserve-on-re-pull pattern), **Phase 22** (Jellyfin field-lock).
