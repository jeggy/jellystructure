# Phase 268 — Every title knows its size on disk, shows it, and can be sorted by it

> Owner, 2026-09-26: *"Let's add a new filesize ordering support, both in Ravilo and in Jellystructure library
> and as a sort order within ravilo content rows configuration within jellystructure. We also would like to
> show the filesize on the media details page in jellystructure. And for series the filesize should be based
> on the whole series instead of on a single episode level."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Backend (the model, the scanner, a backfill, the sorts) and the admin frontend. The Ravilo
half is **R317**, which reads what this phase stores. **Numbering:** verified against `STATUS.md` the
same day — admin taken through **267**.

## What is there today

- **No size is stored anywhere a sort can reach.** `MediaItem`, `Episode` and `Track` carry no file size.
  The file checks (254/255/261) record `size` in `file_integrity` / `file_track_coverage`, but only for
  files a check has already reached (8,957 paths), and only as a change detector.
- **The sorts that exist:**
  - the admin Library page: *recently added · title A–Z · year*, `Library.kt` `#lib-sort` → `MediaStore`'s
    page sort;
  - content rows: Phase 225's shared resolver `RowOrder`, keys `added · title · year`, used both by the
    server (`HomeFeedService.orderRow`) and by the row editor's preview (`WorkbenchOrder.kt`);
  - Ravilo's browse page: R317.
- **Measured on production, 2026-09-26:** `stat` of every file the library knows (9,417 files, movies
  plus every episode file) took **0.6 s**, with no path unreachable. The library is **16.1 TB**. The
  largest series is 808 GB, the smallest title 23 MB.

## Requirements

**FR-268-1 — A file's size is part of what a scan records.** The scanner records `sizeBytes` (from `stat`,
never from reading the file) on every `Episode` and on every movie and music video, whenever it records
the file. It is refreshed whenever the file is (a re-scan, a 254 *Replace from clean copy*, a 263 repair),
so a size never describes a file that is no longer there. It is the **local** path's size
(`library.localPath`, not the Jellyfin-side path).

**FR-268-2 — A title's size is one rule, in one place.** `MediaItem.sizeBytes()` (a derived function, not
a stored field that could drift):

- **a movie or music video:** the size of its file;
- **a series:** the sum over its episodes' **distinct file paths**. A 3-in-1 file (Phase 149) is counted
  once, not three times. A duplicate copy of an episode in a second file (the `duplicate_episode` triage
  case) **is** counted, because it takes up disk;
- **unknown** (`null`) while any of the title's files has no size yet. It is never a partial sum shown as
  if it were the whole.

Every surface below (the admin page, both sorts, R317) calls this one function.

**FR-268-3 — Existing titles get their sizes without waiting months for a re-scan.** The freshness
cooldown means an untouched title may not be re-scanned for a long time. So a background pass at boot
(`GateClass.BACKGROUND`, stat only) fills `sizeBytes` for every file that lacks it. On production that is
the 9,417 files above, about a second. It runs again after any scan that adds files without sizes, and
never on a request path.

**FR-268-4 — The admin shows it.** On the movie detail page the size of the file; on the series detail
page the **whole series** as one figure, in the same header area as the other facts. On the series page's
*Seasons & episodes* tab, each season's header also shows that season's size (by the same distinct-path
rule), so the series total is explainable season by season. Units are decimal with one decimal place
(*23.4 GB*, *812 MB*), from one formatter shared by every admin surface. Unknown sizes read *—*, never *0*.

**FR-268-5 — The admin Library sorts by it.** `#lib-sort` gains **size, largest first** and **size,
smallest first**. `MediaStore`'s page sort handles both with `sizeBytes()`, titles of unknown size last in
either direction, ties by title. The sort survives a reload in the URL like the others (`sort=size` /
`sort=size_asc`).

**FR-268-6 — A content row can be ordered by size.** Phase 225's `RowOrder.KEYS` gains `size`, taking the
title's `sizeBytes()` through the same resolver. The server builds the row with it and the row editor
previews it through the very same function, so the two cannot disagree (FR-225-2). Direction in words:
*largest first* / *smallest first* (FR-225-9: never asc/desc). The config validator's message lists the
new key. The editor's *Order* section gains *Size* beside *Date added · Title · Release year*. Hand-picks
first, then the rest by size, works like every other key. `Row.sort_by = "size"` reaches the client for
R253's *See all*, where R317 maps it.

**FR-268-7 — The Ravilo payload carries the size where R317 needs it.** `BrowseCard` gains an additive
`size_bytes` (browse-only, like `imdb_rating` and `sort_name`, not on every `MediaCard`), from
`sizeBytes()`, absent when unknown.

**FR-268-8 — Tests.** `sizeBytes()`: a movie; a series with a 3-in-1 file (counted once); a duplicate
episode file (counted); one file without a size (whole title unknown). The Library sort in both
directions with unknowns last. `RowOrder` with `size` in both directions, and the validator accepting it.

## Non-goals

- Showing a size to viewers in Ravilo. Only the sort is asked for there (R317).
- Sidecar files (subtitles, NFOs, artwork) and a title's extras. The size is the media files'.
- Disk-usage reporting per library or per drive. It is a natural next step on top of FR-268-2, but it
  was not asked for.

## Acceptance

1. After the backfill, every title on production has a size. A series page's total equals the sum of its
   season headers, and Johnny Bravo's counts each 3-in-1 file once.
2. The admin Library sorted *size, largest first* opens on the 808 GB series. *Smallest first* opens on
   the 23 MB music video.
3. A content row set to *Size · largest first* shows the largest matching titles on the TV, and the row
   editor's preview shows the same order.
4. A title whose file is replaced shows its new size after the replacement, without a manual scan.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The design holds. One correction (item 3); the rest places the work.

1. **Stat without opening.** `SystemFileSystem.metadataOrNull(Path(p))?.size` is already the idiom
   (`RaviloArtworkService.kt:241`, `ClearlogoInk.kt:39`). It opens no descriptor, so Phase 134's FD
   hygiene does not apply. Episodes are built at `Scanner.kt` `:697-722` (`scanSeries`) and `:1060-1091`
   (`syncSeriesEpisodes`); films and music videos at `:400`, `:495`, `:796` and `:863`. Each takes a size
   there. `Episode` and `MediaItem` (`model/Media.kt`) each gain `fileSizeBytes: Long? = null` (the file's
   own size; on a `MediaItem`, the film's or music video's file). It is additive to the stored JSON blob,
   and named apart from the title's derived `sizeBytes()` so the two cannot be confused. `sizeBytes()`
   lives beside `recencyKey()` (`Media.kt:337`) in `commonMain`, so the backend, the admin (`wasmJs`) and
   `RowOrder` (`shared`) all call the same code.
2. **Paths are already local.** Stored `Episode.path` and `MediaItem.path` are the local mount paths
   (production: `/mnt/series/...`, `/mnt/media/...`), so no Jellyfin-path translation is needed.
3. **The backfill must be one write, not 528.** Every item write bumps `libraryVersion`, and a write that
   changes content bumps `feedVersion` (`MediaStore.kt:127-143`, Phase 204), which discards the assembled
   Home feed for every viewer. 528 single writes at boot would do that 528 times in a second. Add one
   `MediaStore.setFileSizes(sizes: Map<path, Long>)`: it patches every affected item in **one**
   `db.transaction { }` and bumps each version **once**. The scanner needs nothing special, because it
   records sizes inside writes it already makes. The 254 replace and 263 repair jobs update the file's
   size in the write they already make after the swap.
4. **The sort sites.** The admin Library is `MediaStore`'s page sort (`MediaStore.kt:526-531`) and its
   `#lib-sort` options (`Library.kt:214-218`). The URL keeps `sort=` (`Library.kt:129`, `:151`).
   Content rows: `RowOrder` gains a `size: (T) -> Long?` selector and `"size"` in `KEYS`
   (`RowOrder.kt:17`); `problem()`'s message and `directionWords()` learn it. Its three callers pass
   `{ it.sizeBytes() }`: `HomeFeedService.kt:701-704`, `WorkbenchOrder.kt:60-62`, and the validator's
   route. The editor's preview gets items from `MediaApi.list` (`Workbench.kt:729-738`), and those
   `MediaItem`s carry the sizes (item 1), so the preview needs no new request.
5. **`BrowseCard.size_bytes`** is filled where `BrowseService` builds `BrowseCard`s, from `sizeBytes()`,
   and is absent when unknown.

**Net effect.** Two model fields and one function, a stat at four scanner sites, one batched store
method plus the boot pass, two sort sites, one `RowOrder` selector, one DTO field, the admin's size
lines. No migration: the size rides the item's JSON.
