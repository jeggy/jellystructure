# Phase 168 — Music-video library support (filename-only, no TMDB)

> Reported 2026-08-17: *"I just added a new library to my jellystructure backend, which is music video
> type. It doesn't seem like they are getting ingested."* Traced (see
> `specs/research-reports/music-video-support-investigation-2026-08-17.md`) to a complete absence of
> music-video support: three independent layers exclude the Jellyfin item type `MusicVideo` before it
> ever reaches jellystructure's own storage. Follow-up decision from the user, scoping this phase tightly:
> *"Let's not add support for music yet. But only Music Videos… filename only… \[TMDB\] should be
> excluded… neither movies or series \[but\] no new tab added… \[pull_tmdb\] fully skip… \[segment
> detection\] no."*

**Status:** Planned — dev-authored, not yet built.

## 1. What's there now

Full blast-radius trace in the companion investigation report. Summary of what changes here:

- `MediaKind` (`commonMain/model/Media.kt:6`) has exactly two cases, `MOVIE`/`TV_SHOW`. A third case is
  the right lever — it forces every exhaustive `when (kind)` site to be handled at compile time (a real
  safety net for a change this cross-cutting), and several *binary* `if (kind == TV_SHOW) … else …` sites
  across `DetailService`/`BrowseService`/`HomeFeedService` currently fold any non-TV_SHOW item straight
  into `MediaKind.MOVIE` in Ravilo's wire model — silently wrong once a genuinely-different third kind
  exists, so those need deliberate new branches too, not just the compiler-forced ones.
- TMDB is a total non-concern for most of the codebase already: `TriageDetection.kt` never reads
  `tmdbId`; `ArtworkDownloader` already no-ops gracefully on a missing TMDB-sourced `posterPath`;
  `NfoWriter` already writes `<tmdbid>` conditionally. The only two places that actually need gating are
  the `notifyOnNoMatch` scan-completion webhook counter (`Main.kt:475-479`,
  `MediaRoutes.kt:2214-2217` — both `items.count { it.tmdbId == null }`, unscoped) and the scheduled
  `pull_tmdb` "missing" step's working-set filter (`Main.kt:531-533`, `it.tmdbId == null` — would retry a
  music video forever, for nothing).
- `BrowseService.search()` (`tv/BrowseService.kt:175`) and `facets(kind: String?)` (`:210`, `else -> null`
  = no kind filter) are already kind-agnostic/safe — once a music video is a stored `MediaItem`, it
  surfaces via search and any untyped content row/channel with zero changes needed there.
- `Main.kt:642-658`'s `detect_segments` bulk-enqueue step has its own exhaustive
  `when (item.kind) { MOVIE -> …; TV_SHOW -> … }` (`needsDetection`, `:655-658`) — a second compile-forcing
  site the investigation didn't catch, found while scoping this spec.

## 2. Functional requirements

### FR-168-1 — `MediaKind.MUSIC_VIDEO`

Add a third case to **both** `MediaKind` enums that must stay in sync: `commonMain/model/Media.kt:6` (the
storage/backend model) and `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:8`
(Ravilo's wire DTO, today `{ MOVIE, SERIES }`).

Every exhaustive `when (kind)` this breaks compilation for gets a real branch, not a placeholder — the
compiler is the checklist:

- `MediaItem.recencyKey()` (`Media.kt:274-277`) — same recency semantics as `MOVIE` (single item, no
  season/episode complication).
- `NfoWriter.buildXml/write/nfoPath/exists/readRaw` (`nfo/NfoWriter.kt:17-20,44-51,65-72,84-91,96-103`) —
  see FR-168-3.
- `ArtworkDownloader.posterArtworkExists/mediaDir` (`media/ArtworkDownloader.kt:45-48,308-311`) — same
  movie-shaped single-file directory layout as `MOVIE`.
- `Scanner`'s re-pull TMDB dispatch (`media/Scanner.kt:904-905,960`) — no-op branch; a music video is
  never re-pulled from TMDB (FR-168-4).
- `Main.kt`'s `detect_segments` `needsDetection` (`:655-658`) — returns `false` unconditionally (FR-168-6).

Every silent binary `if/else` that today folds "not TV_SHOW" into "MOVIE" gets a real third branch instead
of being left to silently misclassify — `DetailService.toMediaCard()` (`tv/DetailService.kt:228-229`),
`BrowseService.kt:243-244`, `HomeFeedService.kt:560-561` all need `MediaKind.TV_SHOW -> SERIES;
MediaKind.MUSIC_VIDEO -> MUSIC_VIDEO; else -> MOVIE` instead of the current two-way check.

**Reuse `director: String?` for the artist name**, don't add a new column. `MediaDetail.kt:359-360`
already swaps the same underlying field's *label* between "Director" and "Network" depending on kind
(`director`/`network` are the same input, different meaning per kind) — a third label swap to "Artist"
(still writing to `director`) follows the existing pattern exactly and needs no schema change. Runtime,
genres, tags, cast/crew stay as-is (mostly unused/empty for a music video, which is fine — all nullable
today).

### FR-168-2 — Ingestion: Jellyfin query + scanner dispatch

`JellyfinClient.getItems()`/`getItemsByParent()` (`auth/JellyfinClient.kt:171/174` and `:182/185`) — add
`MusicVideo` to `IncludeItemTypes` and to the `.filter{}` right after it. **Only these two** (the scan
entry points) — `findRecentItemByPath`/`getResumeItems`/`getRecentlyPlayed`/`getFavoriteItemIds` (the
other four `IncludeItemTypes` sites) stay `Movie,Series`/`Movie,Episode` as-is; resume/favorites/recently-
played behavior for music videos is out of scope (§3) and Ravilo's own playstate tracking is
jellystructure-side, not gated by these Jellyfin-side queries anyway.

`Scanner.scanItem()` (`media/Scanner.kt:181-185`) — new `"MusicVideo" -> scanMusicVideo(...)` branch,
replacing what's currently the `else -> null` catch-all for this specific type. Structurally closer to
`scanMovie` than `scanSeries`: one file, no episodes, no season handling.

`classifySkip()` (`Scanner.kt:1091,1102-1103`) — its `"unsupported-type"` classification for non-Movie/
Series items must stop applying to `MusicVideo` now that it's a real, expected, non-skipped type.

### FR-168-3 — Filename-only metadata, no TMDB anywhere

No TMDB search, no TMDB match attempt, ever, for a `MUSIC_VIDEO` item — not "usually no match", genuinely
never attempted. `scanMusicVideo` derives `title` (and `director`-as-artist, per FR-168-1) purely from the
filename, matching Jellyfin's own recognized music-video naming convention (`Artist - Title.ext`) for
parity between what Jellyfin displays and what jellystructure stores: split on the first `" - "`, left
side → artist (`director` field), right side (minus extension) → title. No separator found → whole
filename (minus extension) becomes `title`, artist stays null. No album, no track number, no year unless
trivially present in the filename — nothing here parses folder structure or ID3-style tags; this is
intentionally the simplest possible metadata source, matching the user's explicit "filename only" answer
(open question 1 from the investigation, now resolved).

### FR-168-4 — NFO: real `musicvideo.nfo` shape, never a `<tmdbid>`

New `buildMusicVideoXml` in `NfoWriter.kt`, same-basename-as-video-file convention (matching
`buildMovieXml`'s file placement, not a separate fixed `musicvideo.nfo` filename). Kodi/Jellyfin's actual
schema: `<musicvideo><title>…</title><artist>…</artist></musicvideo>` — no `<album>`/`<track>` (not
derivable from filename-only metadata per FR-168-3), no `<tmdbid>`/`<uniqueid type="tmdb">` block at all
(unlike `buildMovieXml`'s conditional-on-`tmdbId != null` pattern — for a music video the condition is
always false by construction, so the block simply never exists in this NFO shape).

### FR-168-5 — TMDB-expectation gating, by kind, not by library config

No new `LibraryMapping` field — gating is on `item.kind == MediaKind.MUSIC_VIDEO`, which is simpler,
already-available, and matches exactly what was asked (music videos specifically, not a general
"libraries that don't expect TMDB" toggle).

- `notifyOnNoMatch` webhook counter (`Main.kt:475-479`, `MediaRoutes.kt:2214-2217`) — exclude
  `MUSIC_VIDEO` items from `unmatched` entirely: `items.count { it.tmdbId == null && it.kind !=
  MediaKind.MUSIC_VIDEO }`. Not counted separately, not surfaced at all — resolves investigation open
  question 2 exactly as answered.
- `pull_tmdb`'s "missing" scope filter (`Main.kt:531-533`) — **fully skip** `MUSIC_VIDEO` items from the
  working set regardless of scope (`"all"` or `"missing"`): they must never be selected for a TMDB search,
  ever. Resolves open question 4 ("yes. Fully skip").

### FR-168-6 — No segment detection, ever

`Main.kt:655-658`'s `needsDetection` gets a `MediaKind.MUSIC_VIDEO -> false` branch (forced by FR-168-1's
compile break anyway) — a music video is never enqueued for intro/credits detection. Resolves open
question 5 ("no").

Track editing itself (audio/subtitle language tagging, reordering — `TrackRoutes.kt`, the admin Tracks
tab) is already generic per-file track logic, not movie/series-specific (confirmed in the investigation);
it needs no new code to work correctly on a `MUSIC_VIDEO` item once one exists in the store, since it
operates on `item.tracks` directly regardless of kind.

### FR-168-7 — No dedicated UI surface

No new admin sidebar entry, no new Ravilo browse tab, no new dedicated filter chip. Music videos become
visible through what already works kind-agnostically once FR-168-1–168-2 land: `BrowseService.search()`,
any content row/channel configured without a `kind` restriction (`facets(kind: String?)`'s `else -> null`
path already means "all kinds"), and the admin Library grid's existing "all" bucket
(`Library.kt:262/708`'s `else -> "k-all"`/`"all"`). Per the user: *"as later on we will actually remove the
Movies / Series tabs and only have one with a bunch of filters and different kind of views"* — building a
dedicated Music Videos surface now would be thrown away by that later redesign; deliberately not doing it.

The admin **Movie detail page** does need to actually render for a `MUSIC_VIDEO` item when opened directly
(from Library's "all" grid, or search) — this is not a new tab, it's the existing detail page correctly
handling a third kind instead of assuming movie/series (FR-168-1's `MediaDetail.kt` sites: the
"Director"/"Network" label swap becomes a three-way swap including "Artist").

## 3. Non-goals

- General music-library support (audio tracks, albums, playlists) — this phase is music **videos** only,
  a video-with-one-audio-track media type, not an audio library type.
- MusicBrainz or any external metadata provider — filename-only, permanently for this phase (investigation
  open question 1, resolved: "not relevant").
- A dedicated Ravilo browse tab, admin sidebar entry, or "Music Videos" filter chip (FR-168-7).
- Resume/recently-played/favorites Jellyfin-query support for music videos (FR-168-2's explicit exclusion
  of the other four `IncludeItemTypes` sites).
- Segment detection / Skip Intro / Skip Credits for music videos (FR-168-6).
- A generic per-library "requires TMDB" config toggle for other collection types (e.g. home videos) — the
  investigation floated this; the user's answers scope this to music videos specifically, gated by kind,
  not a new config surface (FR-168-5).
- Album/track/year NFO fields — filename parsing doesn't attempt to derive these (FR-168-3/168-4).

## 4. Verification

- Unit test on the filename-parsing helper (FR-168-3): `"Artist - Title.mkv"` → artist=`"Artist"`,
  title=`"Title"`; `"JustATitle.mkv"` → artist=`null`, title=`"JustATitle"`; edge cases with multiple
  `" - "` occurrences (split on the *first* only).
- `compileKotlinLinuxX64` — confirms every exhaustive `when` site in FR-168-1 was actually handled (the
  compiler is the primary check here, not optional follow-up).
- `compileKotlinWasmJs` + `:ravilo-web:compileKotlinWasmJs` — confirms both `MediaKind` enums and every
  Ravilo-side consumer compile with the third case.
- Manual: run a scan against a real (or fixture) `musicvideos`-type library, confirm items land in
  `MediaStore` with `kind=MUSIC_VIDEO`, a filename-derived title/artist, an NFO written next to the file
  with no `<tmdbid>`, no artwork auto-download attempted (no crash, no TMDB call in the log), and the item
  visible via Library's "all" filter and via search, but absent from any Movies-only or Series-only view.
- Manual: trigger a full pipeline run with `notifyOnNoMatch` configured and a webhook target — confirm the
  music-video items never appear in the `unmatched` count even though they genuinely have no `tmdbId`.
- Manual: trigger `pull_tmdb` with `scope=missing` — confirm no TMDB search call is made for any
  music-video item (check the activity log / outbound call log for absence, not just absence of an error).
- Manual: confirm `detect_segments` never enqueues a music-video item (Activity ▸ Jobs & workers shows no
  segments-lane job for one).
- Manual: open a music-video item's detail page directly (via search or the Library "all" grid) in the
  admin frontend — confirm it renders (Artist label, no season/episode UI, no TMDB deep link) rather than
  erroring or rendering blank.
