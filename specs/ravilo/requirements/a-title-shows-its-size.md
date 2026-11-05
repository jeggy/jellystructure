# A title shows its size

Task: DEB-3711

> Owner, 2026-11-05 (DEB-3711), with a screenshot of a film's About section in Ravilo: *"Not with high priority, I
> would like to have Size here as well. Then show the same size that's being shown in jellystructure. Including for
> series."*

## Scope

The About section of Ravilo's film and series detail pages (R325), on every client that draws it. All of them draw it
through the one `AboutSection` in `ravilo-ui`. On the backend, one additive field goes on R325's `AboutFacts`. The
Chromecast receiver has no detail page.

This spec lifts the non-goal that both size phases carried for the detail page: R317 says *"Showing a title's size on a
tile or a detail page"* and Phase 268 says *"Showing a size to viewers in Ravilo"*. Both were written when the owner
had asked for the sort order only. The detail page now shows the size. Tiles still do not (see Non-goals).

Every viewer sees the row, not only an admin. The owner asked for it on the page every viewer sees.

There is no design round. The change is one more label and value in an existing grid, drawn by the existing
component, in the existing type.

## What is there today

- **The admin.** The admin's media page shows a size chip in its page bar (`#size-pill`, `MediaDetail.kt`), with the
  value `formatSize(item.sizeBytes())`.
  - `MediaItem.sizeBytes()` (Phase 268, FR-268-2) is the one rule for a title's size on disk:
    - a film or music video: its file;
    - a series: the sum over its episodes' **distinct** file paths, so a 3-in-1 file counts once;
    - `null` while any of those files has no size yet. A partial sum is never shown as the whole.
  - The size comes from the scanner's `stat` of the local file, stored in the item's JSON. `refreshFileSizes()`
    re-stats every path at boot and then hourly.
  - `formatSize` (FR-268-4) uses decimal units, with one decimal place below 100 of a unit and none at or above:
    *23.4 GB*, *812 MB*, *1.2 TB*.
- **Ravilo.**
  - `AboutFacts` carries `runtime_min`, `released`, `director`, `creator`, `studio_or_network`, `country`,
    `original_language`, `added`, `seasons` and `episodes`. It has no size.
  - `DetailService.aboutOf(item)` builds `AboutFacts` per request for `GET /api/tv/movie/{id}` and
    `GET /api/tv/series/{id}`.
  - `aboutRows()` draws a row only for a value the server sent. Nothing says *Unknown* (R325, FR-R325-1).
- **The wire's only size today** is `BrowseCard.size_bytes`, which feeds R317's browse sort and is never drawn.

## Requirements

**FR-1. The server states the size.** `AboutFacts` gains `@SerialName("size_bytes") val sizeBytes: Long? = null`.
`aboutOf` fills it from `item.sizeBytes()`, the same function the admin's size chip calls. The two figures are
therefore the same number by construction, for a film and for a series, and they move together when the hourly refresh
re-stats a file.
- The field is additive and defaults to `null`.
- An installed client that does not know the field ignores it.
- A client that gets no value from an older server draws no row (FR-3).
- No new route, no new `stat` and no schema change.

**FR-2. The client draws a *Size* row.** `aboutRows()` adds a row labelled `about.size` with the value
`formatSize(sizeBytes)`. It is the **last** row, after *In your library since*.
- In the two-column layout, a film then pairs *In your library since* with *Size* on the bottom line.

**FR-3. Unknown draws nothing.** When `sizeBytes` is `null` or not above zero, there is no row: never *—*, never *0*,
never *Unknown*. This follows R325's rule. A series that has an episode whose size is not yet known shows no size
until every file has one, which is what the admin's FR-268-2 rule says.

**FR-4. One formatter, so the strings match too.** `formatSize` moves from the backend module's
`dev.jellystructure.model` into `:shared`.
- The admin (`wasmJsMain`), the backend (`linuxMain`) and `ravilo-ui` all depend on `:shared`. Each of them calls that
  one function, and no copy is made.
- The admin's two call sites in `MediaDetail.kt` change their import.
- `FileSizeTest` changes its import too, and Phase 268's formatter cases stay as they are.
- The text is the admin's text exactly, in every language. The units are SI symbols and the decimal point is a point:
  *23.4 GB* in Danish and Faroese as well. The owner asked for "the same size that's being shown in jellystructure".
  If he later wants *23,4 GB* in Danish, that is a later change to one function.

**FR-5. A series shows its whole total.** The series' About section shows the size of the whole series, with the
label *Size*, the same as a film's. This is the admin chip's figure, and the owner's "including for series". A
season's or an episode's size is not drawn (see Non-goals).

**FR-6. The label in three languages.** `about.size`:
- en: *Size*
- da: *Størrelse*
- fo: *Stødd*

The da and fo entries are `{text, note}` drafts, in the same form as R325's other `about.*` keys.

## Non-goals

- A size on a poster tile, a browse card or a search result. R317's non-goal stands for tiles.
- A season's size on the season tabs, or an episode's size on its row. The admin's season headers have one, and the
  owner did not ask for it here.
- Any change to the admin, to how a size is measured, or to how often it is refreshed.
- A per-version size where a film has more than one file. `sizeBytes()` reads the film's file, exactly as the admin
  chip does.
- Localised number formatting (FR-4).

## Acceptance

1. **The server** (`linuxX64Test`). Each case checks `aboutOf` against `item.sizeBytes()` for the same fixture:
   - A film with `fileSizeBytes = 23_400_000_000` gives `size_bytes = 23400000000`.
   - A series of three episodes, two of them sharing one file and the third in its own file, gives the sum of the two
     distinct files.
   - A series with one episode whose size is unknown gives `null`.
2. **The wire.** An `AboutFacts` JSON without `size_bytes` decodes with `sizeBytes == null`.
3. **The formatter.** Phase 268's `formatSize` cases in `FileSizeTest` pass against the function in `:shared`. The admin still compiles and shows its chip
   unchanged.
4. **The client** (`ravilo-ui` desktop or common test). `aboutRows` with `sizeBytes = 23_400_000_000` ends in the row
   *Size* · *23.4 GB*. With `null` or `0` there is no *Size* row, and the other rows are unchanged.
5. **On a device** (QA). Take one film and one series, the series with more than one season. On the TV and on the phone,
   the About section shows *Size* with the same text as the admin's size chip for each title. With the Ravilo language
   set to Danish, the label reads *Størrelse* and the value is unchanged.
