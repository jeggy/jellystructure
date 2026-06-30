# Phase 92 — "Missing artwork" means no poster on disk (filter + triage) (FR-AM3)

> Builds on **Phase 31** / the Phase-3 artwork downloader
> (fetch/cache/write `poster.jpg`·`fanart.jpg`·`clearlogo.png` next to the media), **Phase 47**
> (per-item Artwork tab), and **Phase 27** (the
> floating Triage dock). This phase fixes *what "missing artwork" means* across the Library filter and
> triage — it does not change how artwork is fetched, cached or written.

---

## Problem

The Library's **Missing artwork** quick filter tested `MediaItem.posterPath.isNullOrBlank()` — the **TMDB
poster path** (metadata). On a TMDB-matched library that field is populated for almost every title
(measured: 311/323), so the filter really meant "no TMDB match," not "no artwork." It also collided with
a short-lived **Missing TMDB** filter that tested `tmdbId == null` — two filters, both metadata, neither
about the actual image. Operators care about **what is on disk**: does this title have a real poster
image that Jellyfin will show? A TMDB-matched title whose poster never downloaded should be flagged; a
title with a **manually-added** poster (no TMDB) should *not* be.

## Requirements

1. **One artwork signal, on disk (FR-AM3).** "Has poster artwork" = a **`poster.jpg` file exists in the
   title's media directory** — the same path the artwork downloader writes/reads (movie → the movie
   file's folder; series → the series folder). Exposed as one reusable predicate
   `posterArtworkExists(item)`; the Library filter and triage both use it, so they can never disagree.
2. **"Missing artwork" filter = `!posterArtworkExists`.** The Library quick filter (`filter=missing_artwork`)
   returns titles with no `poster.jpg` on disk. Counts manually-added artwork as present; flags
   TMDB-matched titles whose poster failed/never downloaded; independent of `posterPath`/`tmdbId`.
3. **Missing artwork is a triage issue.** A title with no poster on disk is **"needs attention"**: it
   appears in the floating Triage dock and the Dashboard attention list, is added to the triage **count**
   (`TriageCount.missingArtwork`, folded into `total`), carries a `TriageItem.missingArtwork` flag, and
   the dock's one-line subline reads **"missing poster artwork."** It surfaces even when the title has no
   other (track/language/overview) issue.
4. **No metadata artwork concept.** There is **no** separate "Missing TMDB" filter or triage issue —
   "missing TMDB match" is not a tracked artwork state. (Removes the transient `no_tmdb` filter.)

## Data / transport

- **No new persisted field.** `missingArtwork` is **derived live** from a per-item filesystem check
  (`poster.jpg` in the media dir), not stored on `MediaItem`. The Library `filter=missing_artwork` /
  `attention` paths and `GET /api/triage` (+ `/api/triage/count`, cached by `libraryVersion`) compute it
  on demand. (~one `stat` per item; the media paths are already host-accessible — same paths the artwork
  manager uses.)
- DTOs: `TriageItem.missingArtwork: Boolean`, `TriageCount.missingArtwork: Int` (frontend mirror in
  `MediaApi.kt`). `Condition`/workbench facets unchanged — this is a quick filter + triage flag, not a facet.

## Scope / invariants

- **Poster only.** `fanart.jpg` / `clearlogo.png` presence is out of this signal — the operator asked for
  *poster* artwork. The artwork downloader still manages all three.
- `posterArtworkExists` mirrors the downloader's `mediaDir` exactly (movie = `path.substringBeforeLast('/')`,
  series = `path`) so the filter, triage, and the artwork writer agree on the file location.
- One evaluator-of-record (`posterArtworkExists`) shared by the filter, the `attention` filter, the triage
  count, and `toTriageItem()` — they can never report different sets.

## Out of scope

- Triage/filters for fanart, clearlogo, banner, or episode stills.
- Persisting artwork-presence on `MediaItem` (would avoid the per-request stat but needs scan/artwork-
  pipeline ordering); revisit only if the live stat shows up in profiling.
- Any "no TMDB match" surfacing (filter, triage, or facet).

## Files / code

- `media/ArtworkDownloader.kt` — `posterArtworkExists(item)` (top-level; reuses the `mediaDir` + `poster.jpg`
  check).
- `media/MediaStore.kt` — `filter == "missing_artwork"` → `!posterArtworkExists(it)`; the `attention`
  filter ORs in `!posterArtworkExists(item)`; `no_tmdb` removed.
- `server/routes/TriageRoutes.kt` — `TriageItem`/`TriageCount` `missingArtwork`; `/count` + `toTriageItem()`
  (movie + TV) compute it via `posterArtworkExists`.
- `ui/Library.kt` — Missing-artwork quick filter retained; Missing-TMDB button/wiring removed.
- `api/MediaApi.kt` (`TriageItem.missingArtwork`) + `ui/Shell.kt` (dock subline "missing poster artwork").
