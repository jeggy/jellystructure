# Phase 192 — a logo is never fetched, can be saved corrupt, and is shown cropped to nothing

> Live report: *"`…/media/cf9ca84ee58176b63c69b834e56f349f?tab=artwork` has no logo and when trying to
> see what I can choose from, I just get a bunch of empty images. So something is not really working
> here."*

## Status
Planned (spec'd 2026-09-06). Not dev-reviewed. `cf9ca84ee58176b63c69b834e56f349f` is Breaking Bad's
Jellyfin id (`breaking-bad`, TMDB 1396). Three defects confirmed against the live library and the
TMDB API; the "empty tiles" symptom itself was **not reproduced in a browser** — see Open questions.

## What was found

### A. Nothing ever fetches a clearlogo

`ArtworkDownloader.fetch()` (`ArtworkDownloader.kt:127-205`) is the one function every automatic path
goes through — the `fetch_artwork` pipeline step, `POST /{id}/artwork/fetch`, `pushToJellyfin`,
`batch/artwork`. It downloads:

- poster (`item.posterPath`) ✓
- backdrop (`item.backdropPath`) ✓
- every missing episode still (R125) ✓
- every missing season poster, picking best-by-language-then-vote (R126) ✓
- **clearlogo — read only.** `val logoExists = SystemFileSystem.exists(Path(logo))` at `:133`, carried
  straight into the returned `ArtworkStatus` at `:198`. There is no download branch for it anywhere.

`isArtworkIncomplete()` (`:206-`) likewise never asks about the logo, so the pipeline's `scope =
"missing"` can never select an item *because* its logo is missing.

The result on disk, counted 2026-09-06 across `/mnt/series/jellyfin` (184 series) and
`/mnt/media/jellyfin/movies` (309 movies):

```
clearlogo.png present:  86 of 493 titles  (17.4 %)
poster/fanart/season posters checked: 1 469 files, 0 corrupt
```

**82.6 % of the library has no logo**, and the only way to get one has ever been for a human to open
the Artwork tab and pick one by hand. "Has no logo" is not a bug on that item — it is the default
state of five titles in six.

### B. `download()` writes whatever came back, including error pages

```kotlin
val bytes = http.get(url).readRawBytes()
if (bytes.isEmpty()) return@withPermit false
FileIo.writeBytes(Path(tmp), bytes)
platform.posix.rename(tmp, destPath)
```
— `ArtworkDownloader.kt:249-261`

No status-code check. No content-type check. No magic-byte check. A non-empty body is treated as an
image.

Two of the 86 logos on disk are not images at all:

```
/mnt/media/jellyfin/movies/Three Robbers and a Lion (2022)/clearlogo.png
  → 936 bytes, starts "<!DOCTYPE HTML …<TITLE>ERROR: The request could not be satisfied</TITLE>
    <H1>504 Gateway Timeout ERROR</H1>"
  → clearlogo.png.src = /7ssLrNNhRLr0okansSuyDuyD0yL.png   (a perfectly valid TMDB path)

/mnt/series/jellyfin/Breaking Bad/clearlogo.png
  → SVG source, saved 2026-09-05 21:27
  → clearlogo.png.src = /ojzKpMUAcA91P6wF0TfCyAvvYLw.svg
```

The first is a CDN 504 captured as artwork in July and never noticed since. Worse than the bad file:
`writeAssetSrc` stamped a `.src` sidecar recording that this *is* the current image for that TMDB
path, and `saveAsset` wrote a `.manual` marker. Every downstream check — "is it on disk", "is it
stale", "is it manual" — now says the logo is present, current and operator-chosen. **Nothing in the
system can ever discover it is a 504 page**, and nothing will ever replace it.

Posters and backdrops escaped only by luck: Phase 176's `isStaleAutoAsset` re-downloads them when
`.src` disagrees with the item's current TMDB path, which gives a corrupt one a second chance. The
clearlogo has no such path, because of (A).

### C. TMDB serves SVG logos, and they are saved as `.png`

TMDB's `logos` array mixes PNG and SVG (`/…/images`, confirmed live: Breaking Bad 39 logos of which 3
`.svg`; Game of Thrones 71 of which 4). `assetFileName("clearlogo") = "clearlogo.png"`
(`ArtworkDownloader.kt:365`) is fixed, and `saveAsset` downloads the source to it verbatim
(`:408-413`). An operator picking an SVG candidate gets SVG bytes in a file called `.png`.

This happens to survive today: the production container's ffmpeg is built with `librsvg`
(verified: `docker exec jellystructure ffmpeg -decoders | grep svg`), so `RaviloArtworkService`'s
`resizeImage` rasterises it and Ravilo gets a logo. But **every other consumer reads the file
directly** — Jellyfin's local-artwork reader, Kodi, and anything that trusts the extension — and the
guarantee rests on an ffmpeg build flag nothing in this repo pins or asserts. It is a latent break, not
a live one, and it is trivially avoidable.

### D. The picker renders a logo as if it were a backdrop

```kotlin
ArtTarget("clearlogo", "Clearlogo", "16 / 9")
```
— `MediaDetail.kt:3033`
```css
.art-card { background:#0006; }
.art-card img { width:100%; height:100%; object-fit:cover; }
```
— `MediaDetail.kt:3564-3565`

Logos are wordmarks: the sample above runs from 1.4:1 to 10.7:1, clustering around 5:1. `object-fit:
cover` in a 16:9 box crops a 5:1 logo to its **middle third** at 3× magnification, and a 10:1 logo to
its middle sixth.

Simulated faithfully (same box, same crop maths, same `#0006` card over the dark surface) against
Game of Thrones' real TMDB logos at `w342`, the resolution the picker requests:

```
cover  (today):  "DF TH"  ·  "RA DOS T"  ·  "o в Ce"  ·  "тяж K и"   ← fragments
contain (fixed): the whole wordmark, readable, in every language
```

There is also no backing for transparency: a dark-ink logo (measured ink luminance in the sample
ranged 94–255) on a 40 %-black card over a dark page is close to invisible, and in the admin's Light
theme the same card is a mid-grey that swallows a white one.

## Goal

A logo is fetched automatically like every other asset, a saved logo is a real PNG of the thing the
operator picked, and the picker shows each candidate as a legible wordmark.

## Requirements

### FR-192-1 — Never write a non-image to an artwork path

`downloadUnchecked` must reject the response unless **all** of:

- the HTTP status is 2xx,
- the `Content-Type` is an `image/*`,
- the first bytes match a known image signature (`\x89PNG`, `\xFF\xD8\xFF`, `RIFF…WEBP`, `GIF8`, or —
  see FR-192-3 — an SVG root when SVG is being handled deliberately).

A rejected download returns `false` and **writes nothing** — no file, no `.tmp` left behind, no `.src`
sidecar, no `.manual` marker. It logs the URL, the status and the sniffed type.

This applies to posters, backdrops, stills, season posters and logos alike. It is the single change
that would have prevented the 504 page from ever reaching disk.

### FR-192-2 — Detect and repair the ones already on disk

A one-time sweep (a maintenance action, not a scan step) walks every known artwork path, checks the
magic bytes, and for each file that is not an image: deletes it along with its `.src` and `.manual`
sidecars, and records a `artwork_corrupt_removed` entry on that item's History. The next
`fetch_artwork` then re-downloads it normally.

The `.manual` marker must be deleted too — otherwise the repair is undone by the very lock that was
protecting the corrupt file.

Known affected today: 2 files (listed above). The sweep must still run over everything, because
episode stills were not audited for this spec.

### FR-192-3 — A saved logo is a real PNG

`saveAsset` must guarantee the on-disk format matches the filename. For an SVG candidate that means
rasterising to PNG before the file lands — `FfmpegRunner.resizeImage` already does exactly this and is
already used on this file by `RaviloArtworkService`, so the capability exists; it just needs to run at
save time instead of at serve time.

Rasterise at a fixed height (300 px matches what `RaviloArtworkService` serves) with transparency
preserved. If rasterisation fails, the save fails loudly — it must not fall back to writing the SVG.

The alternative (accept `clearlogo.svg` as a second valid filename) is **rejected**: Jellyfin's local
artwork naming, Kodi's, and `RaviloArtworkService.sourceFile` all key off the fixed name, and adding a
second one multiplies the "which file is current" question by two for no gain.

### FR-192-4 — `fetch()` and `isArtworkIncomplete()` cover the clearlogo

The logo joins the assets `fetch()` fills in, using the same shape R126 already uses for season posters
(which likewise have no stored path on the item): pull `getMovieImages`/`getTvImages`, filter to the
item's `resolvedLanguage`, fall back to textless, then to any, and take the highest-voted — and
**skip any SVG candidate when a PNG of comparable quality exists**, so FR-192-3's rasterise path is
the exception rather than the norm.

`isArtworkIncomplete()` returns true when the logo is missing, so `scope = "missing"` selects the item.

Downloading a logo writes its `.src` (as poster/backdrop already do), which brings the logo under
Phase 176's stale-asset check for free.

**Never overwrite a `.manual` logo** — the Phase 151 guarantee applies here identically.

### FR-192-5 — The picker shows a logo as a logo

For the `clearlogo` target only:

- `object-fit: contain`, not `cover` — the whole wordmark, always.
- a wider card aspect than 16:9 (logos average ~4:1); the exact figure is a design call, but a
  cropped logo is never acceptable.
- a transparency backing that works in both themes and against both light and dark ink — a neutral
  mid-tone panel (or the conventional checkerboard) behind the image, so a black logo and a white logo
  are both visible on the same card.
- the lightbox (`#art-lightbox`) gets the same treatment; zooming a logo currently inherits the same
  `cover`.

Poster and backdrop targets are unchanged — `cover` is right for photographic art.

### FR-192-6 — Say when there is nothing to show

The existing explainer already handles `all.isEmpty()`. Add the case this phase creates: when the
picker's candidate list is non-empty but the item has **no logo on disk**, the explainer must not read
like an error. *"No logo saved yet — pick one, or let the next artwork run fetch it."*

## Non-goals

- No new artwork asset types.
- No change to poster/backdrop/still/season-poster *selection* logic (FR-192-1/2 change validation
  only, which applies to all of them).
- No fanart.tv / other-provider logo source. TMDB is the one source, as it is for everything else.
- No Ravilo change: `RaviloArtworkService` already serves `/api/tv/image/{id}/logo` correctly and will
  simply start finding files where it previously found none.
- No automatic mass re-download of the ~407 titles with no logo. FR-192-4 makes the *next* artwork run
  fill them in on the pipeline's own cadence; forcing 407 TMDB image lookups at once is exactly the
  fan-out Phase 183 exists to prevent.

## Acceptance

1. Point `saveAsset` at a URL that 504s: nothing is written to disk, the route returns the existing
   `502 download failed`, and no `.src`/`.manual` sidecar appears.
2. Run FR-192-2's sweep: the Breaking Bad and *Three Robbers and a Lion* files are removed, both items
   get a History entry, and a subsequent artwork fetch downloads real logos.
3. Save Breaking Bad's SVG candidate: `clearlogo.png` starts with `\x89PNG`, opens in an image viewer,
   and has transparency.
4. Delete a movie's `clearlogo.png` and run the `fetch_artwork` step with `scope = "missing"`: the item
   is selected and a logo appears.
5. A logo with a `.manual` marker is not replaced by that run.
6. Open the Artwork tab → Clearlogo on Breaking Bad: every tile shows a complete, readable wordmark;
   a dark-ink candidate is visible; the SVG candidates render.
7. Open the same tab in Light theme: still legible.

## Source references

- `src/linuxX64Main/kotlin/dev/jellystructure/media/ArtworkDownloader.kt` — `fetch()` `:127-205`
  (logo read-only at `:133`/`:198`), `isArtworkIncomplete()` `:206-`, `download`/`downloadUnchecked`
  `:237-261`, `saveAsset` `:408-413`, `assetFileName` `:365`, `writeAssetSrc`/`markManual`,
  `isStaleAutoAsset` (the poster/backdrop escape hatch the logo lacks).
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt:727-760` (candidates),
  `:762-807` (candidates/save), `:160-170` (`mapCandidates`).
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/RaviloArtworkService.kt:205-241` — the serve-time
  resize that currently rescues the SVG, and the `image/png` content type it asserts.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` — `artTargets` `:3030-3034`,
  `renderArtGallery` `:3184-3300`, injected CSS `:3535-3580`.
- `src/linuxX64Main/kotlin/dev/jellystructure/tmdb/TmdbClient.kt:322-336`, `:1025-1040` — the images
  API and its DTO.

## Open questions

1. **The reported "bunch of empty images" was not reproduced in a browser.** Three confirmed defects
   each independently produce blank or meaningless logo tiles — cover-cropping to a gap between
   letters, dark ink on a dark card, and (for the local tile) a file that is not the format it claims.
   Which one the reporter actually saw is unknown, and the fix above addresses all three rather than
   guessing. A single screenshot of that tab, or one console check of whether the `<img>` elements
   loaded, would settle it and is worth capturing before the fix so it can be verified against the same
   view. No headless browser is installed on this host, which is why it wasn't done here.
2. What aspect ratio should the logo card use? Measured TMDB logos cluster at ~4:1 with a long tail to
   10:1. A fixed 4:1 card with `contain` letterboxes the extremes but never crops; a per-candidate
   intrinsic ratio makes the grid ragged. Recommendation: fixed, `contain`, and let the grid stay even.
3. Should the automatic pick (FR-192-4) prefer a textless logo or one in `resolvedLanguage`? R126's
   season-poster rule prefers the resolved language; for a wordmark the same rule is probably right
   (a Danish show should get its Danish logo), but it is a real choice and should be stated rather
   than inherited.
