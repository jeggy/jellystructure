# Phase 171 — Music videos: real per-file artwork paths + TMDB matching like any other content

> Reported live, the same session Phase 168 shipped: "how come jellyfin has some images for the new
> music media, but they are not shown [in jellystructure]?" and separately: "seems like tmdb never
> works for musicvideos? Example `a73985678a02866af471dc597ca42eaa` is Muse Haarp, but this was a live
> dvd music concert and it does exist in tmdb at 25352. So we should be able to fetch it's metadata +
> artwork from there like any other content. It's only that music videos that have tmdb issues, should
> not actually be flagged, as it's expected. But it does not mean that it won't sometimes actually have
> a tmdb match." Two independent bugs, both found investigating the same symptom (a real music-video
> item with no artwork/metadata showing anywhere).

**Status:** Implemented 2026-08-21 (same day as Phase 168, live-tested against the real "Musik"
library — 22 real items, `jellyfin_id=8a05b025...`).

## §1 — Real per-file artwork paths (bug fix)

**Problem.** `ArtworkDownloader`'s `mediaDir()` treated `MUSIC_VIDEO` as "movie-shaped" — a single
fixed `poster.jpg`/`fanart.jpg`/`clearlogo.png` inside `item.path.substringBeforeLast('/')`. That's
correct for a movie (one-per-folder by convention) but wrong for a music video: **confirmed against
the real library**, an artist's folder holds multiple music videos side by side (e.g. `Eivør/` has 4).
Real, pre-existing poster files were already sitting on disk under the per-basename convention this
house's library already used — `<video-basename>-poster.jpg` (e.g. `Eivør - Live in Bucharest
(2026-08-04)-poster.jpg`) — the same shape `episodeStillPath`/`NfoWriter.nfoPath` already use
elsewhere in this codebase. `posterArtworkExists()`/`check()`/`assetPath()` (and therefore
`RaviloArtworkService`'s actual image-serving route, and the admin's "missing artwork" filter) never
looked at that filename, so real Jellyfin-visible artwork was invisible everywhere jellystructure
serves images from.

**Fix.** New `assetFilePath(item, filename)` (`ArtworkDownloader.kt`, top-level so both the module's
free function `posterArtworkExists` and its class methods share it): `MOVIE`/`TV_SHOW` keep the
existing fixed-filename-in-own-directory behavior; `MUSIC_VIDEO` returns
`"${item.path.substringBeforeLast('.')}-$filename"`. Routed through everywhere a poster/fanart/logo
path was previously built inline: `posterArtworkExists()`, `check()`, `fetch()`, `assetPath()`
(→ `RaviloArtworkService` + the admin's manual asset-pick endpoint), and `MediaRoutes.kt`'s direct
upload handler (`POST /api/media/{id}/artwork/upload`, which had its own separate ad-hoc dir
computation — now calls the same shared helper instead of duplicating the logic a second time).

## §2 — TMDB matching, like any other content (design correction)

**Problem.** Phase 168 (FR-168-3) decided "no TMDB search, no TMDB match attempt, ever, for a
MUSIC_VIDEO item." Wrong premise: a **concert film / live DVD** release routinely has a real TMDB
movie entry — confirmed live: "Muse Haarp Tour" (filename) has no TMDB match by that title, but the
same file genuinely is TMDB movie **25352**, "Muse: HAARP - Live from Wembley Stadium" (2008). The
user had already set `tmdbId=25352` by hand via the existing, kind-agnostic
`PATCH /api/media/{id}/tmdb-id` route — and nothing happened, because every MUSIC_VIDEO code path
(`scanMusicVideo`, `rescanMetadata`, the per-item "Sync" button) unconditionally ignored `tmdbId`.

**Fix.** Music videos are now searched and fetched **exactly like a movie**, with one narrow
exception (`director` always stays the filename-parsed artist — see below):

- `scanMusicVideo` (`Scanner.kt`): mirrors `scanMovie`'s TMDB half — `jItem.providerIds?.tmdb ?:
  tmdb.searchMovie(query, year)`, then (if matched) `getMovieDetailsLocalized` +
  title/overview/poster/backdrop/genres/studio/cast/crew/certifications/trailer/imdbId, exactly as a
  movie gets. Search query is `"$artist $title"` when an artist was parsed, else just `title` — a
  concert film's real TMDB title routinely includes the artist name (confirmed: TMDB has no results at
  all for the bare filename-derived "Muse Haarp Tour", but the artist-qualified real title matches).
  Filename-only data is still the floor — a miss leaves title/artist exactly as Phase 168 always
  produced them, no error, no different code path.
- `rescanMetadata`'s `MUSIC_VIDEO` branch (was a hardcoded no-op): now mirrors the `MOVIE` branch —
  respects an existing (including manually-set) `tmdbId`, searches if absent, populates the same field
  set on a match. Unlike `MOVIE`, a miss is **not** a failure (`item` unchanged, not `return null`) —
  a miss is the expected, common case here, not an error condition.
- The per-item "Sync" button's `MUSIC_VIDEO` branch (`MediaRoutes.kt`, was also a no-op `item`):
  now calls `scanner.rescanMetadata(item)` — the same metadata-only rescan `TV_SHOW`'s "series" scope
  already uses (no per-file re-probe path exists or is needed here — filename parsing doesn't need a
  fresh ffprobe).
- `pull_tmdb`'s working-set filter (`Main.kt`): the "fully skip MUSIC_VIDEO, always" exclusion is
  gone — a music video is now eligible the same as any other kind, `scope=missing` retries it as long
  as `tmdbId == null`, same as a movie.
- `NfoWriter.buildMusicVideoXml`: gains the full `buildMovieXml` field set (`<tmdbid>`/`<uniqueid>`,
  `<plot>`, `<mpaa>`, `<genre>`, `<tag>`, `<studio>`, `<director>`/`<writer>` for the *film's* crew,
  `<actor>`) — written only when a match exists. `<title>`/`<artist>` are unconditional and always
  filename-derived, never replaced.

**Explicitly unchanged (still correct, per the user's own framing):**
- `notifyOnNoMatch`'s webhook counter still excludes `MUSIC_VIDEO` entirely — a miss is common and
  expected, and should never be flagged, same as Phase 168 decided. Only the search *attempt* changed,
  not whether a miss is treated as noteworthy.
- `detect_segments` still never enqueues a music video (FR-168-6, untouched — a TMDB match doesn't
  change that; segment detection is a separate concern from metadata matching).
- No new UI surface (FR-168-7, untouched) — the existing generic detail page already renders whatever
  fields are populated; a matched music video just has more of them filled in now.

**`director` deliberately never comes from TMDB.** TMDB's own director/crew credit for a concert film
(who directed the *filming*) is a different concept from "who performs" (the artist/band) — folding a
match's real crew into `crew` (so it appears on the Cast & Crew tab, "like any other content") while
leaving `director` — and therefore the admin's "Artist" label (FR-168-1) — exactly as the filename
parse set it, regardless of match state.

## Verification

- `compileKotlinLinuxX64` clean.
- `linuxX64Test` — 135/135 (was 131; new `AssetFilePathTest` for §1's path helper, plus a new
  matched-TMDB case in `NfoWriterMusicVideoTest` for §2's NFO shape). Verified directly via the
  compiled native test binary (`build/bin/linuxX64/debugTest/test.kexe`) — this sandbox's Gradle
  test-report collector is independently flaky (`Multiple entries with same key` / `Buffer
  underflow`), unrelated to these changes; the binary itself is authoritative.
- Live-verified against the real library: confirmed the exact reported item
  (`jellyfinId=a73985678a02866af471dc597ca42eaa`, "Muse Haarp Tour") is TMDB movie 25352 via a direct
  TMDB API call, and that pre-existing on-disk posters follow the `<basename>-poster.jpg` convention
  §1 now reads. End-to-end confirmation (an actual scan/re-pull picking up the match, and Ravilo
  rendering the artwork) is a live-scan/live-render check left for the next scan run or an explicit
  "Sync"/"Re-pull TMDB" click — not repeated here since the code paths themselves are shared with
  movies' already-proven behavior.
