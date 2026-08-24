# Phase 174 — Clear a wrong TMDB match (and remove an on-disk asset)

> Reported live: the music video **"Tina Dico - Drifting"** was auto-matched to an unrelated **1923
> film** of the same one-word title. Nothing in the admin could undo it: `PATCH .../tmdb-id` clears the
> *id* and nothing else, so the wrong film's overview, genres, cast, studio, certifications, trailer and
> its **downloaded poster/backdrop** stayed on the item permanently — and the next scheduled
> `pull_tmdb` would re-run the same search and re-apply the same wrong match anyway.

**Status:** Implemented 2026-08-24 (backend + admin UI). Not yet live-tested (needs a backend restart).

## Root cause

Two independent gaps, both pre-existing:

1. **No removal path for a match.** Every TMDB-derived field is *write-only* from the admin's point of
   view: `Scanner.scanMovie`/`scanMusicVideo`/`rescanMetadata` set them, and the only editing surface
   (`PATCH /{id}/tmdb-id`, `PATCH /{id}/metadata`) can reach `tmdbId` plus a handful of text fields.
   `posterPath`/`backdropPath` and the **files on disk** had no removal path at all — `ArtworkDownloader`
   could `saveAsset` and overwrite, never delete (`deleteIfExists` existed but was private, used only by
   the misplaced-artwork cleanup).
2. **Clearing the id doesn't stop the automation.** `rescanMetadata` (and the from-scratch scan paths)
   all read `item.tmdbId ?: tmdb.searchXxx(...)` — a null id is precisely the trigger for a fresh
   search. Clearing the id therefore *guarantees* the wrong match comes back on the next
   `pull_tmdb`/scan, since the same title+year produces the same top hit. There was no way to express
   "an operator has looked at this and it genuinely has no correct TMDB entry".

## Requirements

### FR-174-1 — `POST /api/media/{id}/tmdb-match/clear`

Fully undoes a match: nulls/empties every **TMDB-owned** field, deletes the downloaded poster/backdrop
(file + `.manual` + `.src` sidecars), releases those assets from `lockedArtwork`, and sets
`tmdbMatchLocked = true`. The exact field set is defined **once**, in `clearTmdbMatch()`
(`TmdbMatchLock.kt`), so the route and the scan-merge guard (FR-174-2) can never drift apart:

`tmdbId · posterPath · backdropPath · overview · genres · tmdbGenres · studio · studioTmdbId ·
studioLogoPath · secondaryStudios · originalTitle · originalLanguage · cast · crew · imdbId · runtime ·
certifications · trailer · imdbRating`

**Deliberately not cleared:**

- **`title`** — not TMDB-exclusive (the scanner's own fallback is the parsed filename), and it already
  has a manual-edit field the operator may have used since. Clearing it would throw away a fix.
- **`year`** — same reason, and stronger: `scanMusicVideo`/`scanMovie` fall back to Jellyfin's
  `ProductionYear` (`details?.releaseDate ?: jItem.year`), so the stored year is frequently *not* from
  TMDB. Nulling it would destroy Jellyfin-derived data with no recovery short of a full rescan.
- **`tags`** — Jellyfin-sourced, never TMDB (a TMDB re-pull merges keywords in, but the field's origin
  is Jellyfin — see `mergeRepullTags`).
- **`titlesByLang`** — mixes TMDB translations with manual `metadata_edit` titles and is union-merged on
  every store write; separating the two is out of scope here.

The action is recorded as `tmdb_match_clear` with a revertable snapshot (FR-174-5).

### FR-174-2 — `tmdbMatchLocked` actually blocks re-matching, and survives a rescan

The flag is worthless unless every path that could re-apply a match honours it, **and** it survives the
paths that rebuild a `MediaItem` from scratch:

- **`Scanner.rescanMetadata`** returns `null` immediately for a locked item — no TMDB traffic at all.
  This covers `pull_tmdb` (`PipelineStepOps.pullTmdb`), `POST /{id}/repull`, and `POST /{id}/sync` for
  every kind. Each caller already treats `null` as "nothing pulled" and writes nothing.
- **`MediaStore.addOrUpdate` / `updateOne`** apply `preserveTmdbMatchLock(fresh, old)` — the same shape
  as Phase 151's `preserveLockedArtwork`. A full library scan (`scanItem` → `scanMovie`/
  `scanMusicVideo` → `addOrUpdate`) and the sync/re-pull-from-Jellyfin paths build a fresh `MediaItem`
  with `tmdbMatchLocked` at its `false` default and, having no store access, will have re-run the
  search; the guard both carries the flag forward and re-applies `clearTmdbMatch()` to the result, so a
  locked item can never silently regain a match. `title`/`year` are additionally carried forward from
  the stored item **when the incoming item actually carries a match** (`tmdbId != null`) — there, unlike
  FR-174-1, the guard *knows* those values came from a re-search it is discarding. An incoming item with
  no id is an ordinary store-derived write (a metadata edit, a track change), so it keeps its own
  title/year and only the flag is carried; otherwise the guard would silently revert an operator's own
  edit on every locked item. Same bug class Phase 153 fixed for `nfoWrittenAt`/`nfoHash`/`jfSyncedAt`.

A locked item still costs one wasted TMDB search per full scan — `Scanner` holds no `MediaStore`
reference and threading one through is a larger refactor than this fix warrants. The guard makes the
*result* correct; only the request is wasted.

### FR-174-3 — Only an explicit re-match unlocks

`PATCH /{id}/tmdb-id` with a **non-null** id clears `tmdbMatchLocked` (and passes
`respectTmdbMatchLock = false` so the guard doesn't immediately strip the id back out). Setting the id
to null leaves the flag as it stands — clearing an id is not by itself a statement that no correct match
exists. Same "operator decision beats automation, and only an operator decision reverses it" precedent
as `lockedArtwork` (Phase 151) and the per-marker segment locks (Phase 163).

### FR-174-4 — `POST /api/media/{id}/artwork/{asset}/clear`

Removes one on-disk asset (`poster` · `backdrop` · `clearlogo`): the image, its `.manual` marker and its
`.src` provenance sidecar. Unknown asset names are a `400`, matching `/artwork/upload`'s validation
rather than silently no-op'ing with a `200` and a history entry.

The asset is also **released from `lockedArtwork`**. Without this, `preserveLockedArtwork` keeps
restoring the stored (now `null`) `posterPath`/`backdropPath` on every scan-derived write, so
`fetch_artwork` can re-download the file but the path can never come back — the operator clears a bad
poster and the slot stays empty forever, while the UI keeps showing a lock badge for an image that no
longer exists.

Both clear paths trigger a Jellyfin item refresh, exactly as `/artwork/upload` does — otherwise Jellyfin
goes on serving its cached copy of a deleted image.

### FR-174-5 — Revert

`tmdb_match_clear` is revertable. The snapshot is the item **minus `episodes`** (the bulk of a series'
JSON, and no TMDB-owned field lives there), and the revert restores **only** the FR-174-1 field set onto
the current item — never a wholesale item restore, which would also resurrect unrelated fields edited
since the clear. Reverting also clears `tmdbMatchLocked`, so the item is genuinely back where it was.

The deleted image files are **not** restored: `posterPath`/`backdropPath` are TMDB `file_path`s, so the
next `fetch_artwork` re-downloads them from the restored paths.

`artwork_clear` is **not** revertable — the deleted bytes are gone and, unlike a match, there is no
provenance record to re-derive them from. It is recorded for the audit trail only.

The admin History tab gains labels for both new actions (previously it rendered the raw snake_case
action name for anything unmapped).

### FR-174-6 — Admin UI

- **Artwork tab** (`MediaDetail.kt`'s `renderArtGallery`): item-level asset targets (`t.kind == "asset"`)
  that are currently on disk get a **Clear** button (`.btn.bad`, next to Upload/Paste URL) alongside the
  existing "Currently in use" tile. Confirms, calls `MediaApi.clearArtworkAsset`, then refreshes the rail
  and gallery from the response's fresh `ArtworkStatus`. Season posters and episode stills are unchanged
  — not part of the reported gap.
- **Pagebar Identity card**: a **Clear TMDB match** button (`.btn.bad`) next to "Find / fix match…",
  shown whenever there's something to undo (`tmdbId != null`, or the item is already locked from a prior
  clear). Confirms (mentioning the History-tab revert), calls `MediaApi.clearTmdbMatch`, then re-renders
  the detail view from the returned item. While locked, a 🔒 note explains that scans won't re-search
  until an explicit re-match.

## Explicitly out of scope

- No bulk/library-wide "clear all matches" action.
- Season posters and episode stills keep their existing behaviour — `clearAsset` is item-level only.

## Verification

- `compileKotlinLinuxX64` + `compileKotlinWasmJs` clean.
- `linuxX64Test` 143/143, incl. new `TmdbMatchLockTest` (8 cases): `clearTmdbMatch` leaves
  `title`/`year`/`tags` alone; the guard restores a locked item's cleared state over a freshly-matched
  scan result, keeps an operator's own edit on an id-less (ordinary) write, and passes an unlocked item
  through untouched.
- `scripts/check-mobile-css.sh` — OK (no new CSS rule needed; both new buttons reuse the existing
  `.btn.bad` class).
- Not yet live-tested against the reported "Tina Dico - Drifting" item, and neither UI affordance has
  been clicked in a real browser yet (needs a backend restart, which is the owner's call).
