# Phase 133 — Keep a manually-chosen poster/backdrop across TMDB sync (FR-ART1)

> When an operator picks or uploads a different poster (or backdrop) in the admin, it must **survive the
> next TMDB sync / re-pull / scheduled scan**. Today the manual choice reverts, because every metadata
> pull unconditionally resets `MediaItem.posterPath` to TMDB's default and nothing marks the poster as
> user-chosen. Track the manual choice and preserve it — the same way genres (`mergeUserGenres`) and JS
> tags (`preserveJsTags`) already survive a re-pull.

**Status:** Planned — root-caused (see Root cause below).

## Problem (as reported)
"Whenever I choose another poster artwork within jellystructure it gets overwritten upon the next TMDB
sync." Confirmed and root-caused.

## Root cause — two sources of truth that disagree
- **On-disk `poster.jpg`** — what **Ravilo TV** renders (via `GET /tv/image/{id}/poster`). The pipeline's
  `download_artwork` step is **skip-if-present** (`ArtworkDownloader.fetch`, `:70`), so a scan never
  clobbers it — the operator's file stays on disk.
- **`MediaItem.posterPath`** (a TMDB `file_path`) — what **every admin surface** renders, over the TMDB
  CDN (`MediaDetail.kt:214`, `Library.kt:601`, `Workbench.kt:438`, `RaviloConfig.kt:1281`). This field is
  **unconditionally reset** to TMDB's default on every metadata pull — `Scanner.kt:711`/`:773`
  (`rescanMetadata` / `pull_tmdb`), `:471` (`syncMovie`), `:181` (`scanMovie`), `:405` (`scanSeries`) —
  with **no** preservation, unlike the sibling fields right beside it (genres/tags/imdb/network all
  merge-preserve).
- **No state marks a poster as user-chosen** (contrast the `clearlogo` and episode-still `.src` sidecars,
  which record `"manual"` and are never auto-upgraded), so the scanner has nothing to branch on and
  `MediaStore.addOrUpdate` — which already preserves JS tags, `titlesByLang`, and timestamps — has
  nothing to preserve on. Result: the admin shows the poster "revert" on the next sync while Ravilo keeps
  showing the on-disk file (the two surfaces disagree).
- **Latent second bug:** the **upload** path (`MediaRoutes.kt:467-522`) writes `poster.jpg` but sets
  neither `posterPath` nor a marker, so an uploaded poster is **invisible in the admin from the moment
  it's uploaded** (Ravilo shows it; the admin, rendering from `posterPath`, never does).

## Requirements

### FR-ART1-1 — Mark a poster/backdrop as manually chosen
Record when the operator sets artwork by hand. Additive, defaulted, no DB migration (the Phase-108
JSON-blob pattern): either a `lockedArtwork: Set<String>` on `MediaItem` (values `"poster"` /
`"backdrop"`), or a per-asset `.src = "manual"` sidecar next to the file (reusing
`ArtworkDownloader.writeAssetSrc`/`readAssetSrc`, which already back `clearlogo`). Set it in **both**
manual routes: the TMDB-candidate pick (`MediaRoutes.kt:561-592`) and the multipart upload (`:467-522`).
A sidecar survives a DB reset (marker lives with the media); a model field is simpler to read at
merge-time — pick one and be consistent.

### FR-ART1-2 — Preserve it across every metadata pull
At the one choke point `MediaStore.addOrUpdate` (`MediaStore.kt:415-446`, alongside `preserveJsTags`),
when the prior item has the poster locked, keep the old `posterPath` + the lock instead of the incoming
TMDB default — so scan, sync, and re-pull are all covered by a single guard rather than patching each
`Scanner` assignment. Apply the identical guard to `backdropPath` (reset at
`Scanner.kt:712`/`:774`/`:472`/`:182`/`:406`).

### FR-ART1-3 — Make an uploaded poster visible in the admin
Fix the latent upload bug so a hand-uploaded poster actually shows in the admin. **Preferred:** point the
admin poster render sites at the on-disk file (an admin image route reusing `RaviloArtworkService`/
`assetPath`), so `posterPath` resets become cosmetically irrelevant and both the pick **and** the upload
cases stay consistent between admin and Ravilo (this also resolves the dual-source-of-truth at its root).
**Minimum acceptable:** give the upload a sentinel/served path the admin can render, so an uploaded poster
is not silently invisible.

### FR-ART1-4 — Respect the lock on re-download too
Keep `download_artwork` skip-if-present (it already preserves the on-disk file), and ensure a manual
**re-pick/upload** still overwrites on purpose (an explicit operator action re-sets the lock and file),
while an automatic sync never does.

## Invariants
- **A manual poster/backdrop survives every automatic path** (scheduled scan, `pull_tmdb`, `sync_*`,
  re-pull-from-TMDB); only an explicit operator re-pick/upload changes it.
- **Additive, nullable, no DB migration** if a model field is chosen (JSON-blob pattern); a sidecar marker
  is the alternative and survives a DB reset.
- **Preserve at one choke point** (`MediaStore.addOrUpdate`), mirroring `preserveJsTags` /
  `mergeUserGenres` — don't scatter the guard across every `Scanner` assignment.
- **Admin and Ravilo agree** on which poster is shown.

## Out of scope
- A general per-field "lock all artwork" UI — this is poster + backdrop only (clearlogo and stills already
  carry manual markers).
- Re-plumbing the whole artwork pipeline, or migrating existing items (an operator re-picks once and it
  sticks thereafter).
- Writing the manual poster back to Jellyfin.

## Source references
- Storage / skip-if-present: `media/ArtworkDownloader.kt` (`:40-46` `posterArtworkExists`, `:70`
  skip-if-present in `fetch`, `:191-201`/`:236-244`/`:265-270` the `.src` sidecar patterns to reuse,
  `:217-230` dirs/filenames).
- Overwrite sites: `media/Scanner.kt` (`:711`,`:773`,`:471`,`:181`,`:405` posterPath;
  `:712`,`:774`,`:472`,`:182`,`:406` backdropPath); `media/MediaStore.kt` (`:415-446` `addOrUpdate` merge;
  `:250` the "care about disk, not posterPath" comment — the intent this aligns the display code to).
- Manual routes: `server/routes/MediaRoutes.kt` (`:467-522` upload, `:561-592` TMDB-candidate pick — a
  `.src` marker is written only for `clearlogo` today).
- Admin render (from posterPath/CDN): `MediaDetail.kt:214`, `Library.kt:601`, `Workbench.kt:438`,
  `RaviloConfig.kt:1281`. Ravilo render (from disk): `tv/RaviloArtworkService.kt:135`, `TvRoutes.kt:558`.
- Related: **Phase 22** (Jellyfin field-lock — the "user override wins" precedent), **R131** (still `.src`
  "manual" sidecar — the reusable marker), **Phase 94** (`mergeUserGenres` — the preserve-on-re-pull
  pattern), **Phase 47/48/71/81** (artwork manager / lightbox this touches).
