# R214 — Ravilo's own image cache must not outlive a corrected poster/backdrop

> Reported live: a movie ("Hjem") had been auto-matched to the wrong TMDB title (a Spider-Man film) and
> downloaded that title's poster/backdrop. The match was corrected in jellystructure (Find/fix match +
> re-pull), and **Phase 176** confirmed the on-disk `poster.jpg`/`fanart.jpg` were correctly replaced —
> `hjem-2026`'s `poster.jpg.src` now records the right TMDB `file_path`, and `RaviloArtworkService`'s own
> resized-image cache had already regenerated against the new file (its `.ct` sidecar recorded the new
> byte size and a fresh timestamp). But **Ravilo (TV/phone) kept showing the Spider-Man poster** for this
> title well after the backend was confirmed correct.

**Status:** Implemented 2026-08-27 (backend + `ravilo-ui` client). Not yet live-tested against the
reported item (needs a backend restart — owner's call, per standing instruction).

## Root cause

Backend-side, Phase 176 already closed the "the file disagrees with the match" gap. The bug that's left
is entirely about **how long a client is allowed to go on believing a URL's bytes never change**:

`RaviloImageUrl` (R133) builds every poster/backdrop/logo/season-poster URL as `/api/tv/image/{MediaItem.id}/{type}`
— **keyed only by the item's id, not by which TMDB match or which file produced the bytes**. That was a
deliberate simplification at the time (R133's own doc comment: *"Image URLs are keyed by `MediaItem.id`
(full decouple)"*), built on top of an assumption Phase 118 had already encoded in the HTTP layer:

> `CacheHeaders.kt`'s `respondCachedBytes()` doc comment: *"Artwork is immutable at a given URL in this
> app (a changed poster gets a new path/version, not an in-place overwrite), so a long max-age is safe."*

That assumption was true when Phase 118 was written, and it is **exactly the assumption Phase 176 was
built to break** — deliberately self-healing a stale poster/backdrop *in place, at the same URL*, with no
new path. Nobody revisited the two caches built on the old assumption:

1. **The HTTP layer.** `respondCachedBytes()` sends `Cache-Control: max-age=86400` (24h) with
   `must-revalidate = false` and a content-hash `ETag`. A client that already has a cached response for
   `/api/tv/image/hjem-2026/poster` will not even issue a conditional request for up to 24h — it has no
   reason to, the response told it not to. Confirmed against the live item: the fix landed 2026-08-26
   14:12; this bug was still reproducing 2026-08-27 10:48, inside that same 24h window.
2. **Coil's in-memory cache** (`ravilo-ui`'s `RemoteImage`/`ImageLoader.kt`). Coil keys its memory cache
   by request (URL + params) and, on a memory-cache hit, **never touches the network at all** — that's
   deliberate (R87's doc comment: *"Coil skips the crossfade for memory-cache hits, so prefetched/
   revisited images appear instantly"*). A tile already resident in a long-lived TV app process's memory
   cache would keep showing the old bitmap **indefinitely**, even after the HTTP layer's 24h window
   passed, until the process restarted or LRU pressure evicted that entry.

Both layers use the URL itself as the cache key. Since Phase 176 made the same URL legitimately point at
different bytes over time, both caches needed the URL to change when the bytes do — and nothing made
that happen.

## Requirements

### FR-210-1 — A version stamp in every server-built poster/backdrop/logo/season-poster URL

`RaviloImageUrl.poster/backdrop/heroBackdrop/logo/seasonPoster` each gain an optional `v: Long?` param,
appended as `?v=<value>` when present. The value is the **current on-disk file's byte size** —
`ArtworkDownloader.assetVersion(item, asset)` / `seasonPosterVersion(item, season)` — the same signal
`RaviloArtworkService`'s own resize cache already trusts (its doc comment: *"any artwork rewrite...
changes the compressed size, so a cache entry auto-invalidates with no explicit hooks to miss"*).
Read fresh from disk at the moment a feed/detail/browse/upcoming response is built, not a counter some
writer has to remember to bump — Phase 176 already showed that "remember to invalidate" is exactly the
kind of gap that lets a stale image survive a fix.

Every server-side DTO builder that constructs one of these URLs passes the version:
`DetailService.toMediaCard()` (+ its `Season.posterUrl`, movie/series `logoUrl`), `BrowseService.toMediaCard()`,
`HomeFeedService.toMediaCard()` + all three hero builders (`buildHeroes`/curated hero paths), and
`UpcomingService`'s two `posterUrl` sites for an already-held match. This required adding an
`ArtworkDownloader` dependency to `BrowseService`, `HomeFeedService`, and `UpcomingService`, which didn't
previously need one.

### FR-210-2 — The client must not re-corrupt a versioned URL

`ravilo-ui`'s `sizedProxyUrl()` (R96) appends its own `?w=<width>` device-size param to a proxy URL. It
previously always used a bare `?`, which would have built an invalid `...?v=123?w=200` for any URL that
already carried FR-210-1's version param. Fixed to join with `&` when the URL already has a `?`.

### Deliberately unchanged

- **`Cache-Control: max-age=86400` stays as-is.** The version param is the fix, not the max-age — a
  versioned URL genuinely is immutable (new bytes get a new `?v=`), so a long client-side cache for it is
  correct and desirable, not the bug.
- **Episode stills (`RaviloImageUrl.still`) and avatars (`RaviloImageUrl.avatar`) are out of scope.**
  Stills share the same theoretical exposure (R131's screengrab→TMDB auto-upgrade replaces a still's
  bytes at the same URL) but are lower-stakes — a still is a supplementary per-episode thumbnail, not the
  primary poster/backdrop identity a wrong-match bug like this one is about — and adding per-episode
  versioning means resolving it against `episodeStillPath`/`readStillSrc` territory that's already more
  involved (Phase 149's multi-episode-file disambiguation). Left for a follow-up if it's ever reported
  live, same as Phase 176 deferred stills/season-posters for its own reason. Avatars are already excluded
  by design — R133's doc comment already calls out avatars as "the one exception," cached durably with no
  staleness check at all, because a Jellyfin user's profile photo essentially never changes mid-session.

## Source references

- `tv/RaviloImageUrl.kt` — the URL builder; new `v` params.
- `media/ArtworkDownloader.kt` — new `assetVersion()`/`seasonPosterVersion()` (paired with the existing
  `assetPath()`/`seasonPosterPath()` this reuses).
- `tv/DetailService.kt`, `tv/BrowseService.kt`, `tv/HomeFeedService.kt`, `tv/UpcomingService.kt` — the DTO
  builders that now pass a version; `Main.kt` — wiring `artworkDownloader` into the three services that
  didn't already have it.
- `ravilo-ui/.../seams/ImageLoader.kt` — `sizedProxyUrl()`'s `&`-vs-`?` fix.
- Related: **Phase 118** (`CacheHeaders.kt` — the caching layer whose "immutable URL" assumption this
  phase completes rather than reopens), **R133** (image URLs served from jellystructure's own on-disk
  artwork, keyed by `MediaItem.id`), **Phase 176** (the on-disk self-heal this phase's client-visibility
  fix rides on top of — 176 fixed the file; this fixes the URL that points at it), **R96** (the `?w=`
  device-size param `sizedProxyUrl` was already appending).

## Verification

- `compileKotlinLinuxX64`, `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid` all
  clean.
- Confirmed against the live `hjem-2026` item pre-fix: backend-side artwork and its resize cache were
  already correct (`poster.jpg.src` matches `item.posterPath`; `hjem-2026-poster.ct` recorded the current
  file's byte size, regenerated after the file fix) — isolating the bug to the client-visible URL/caching
  layer this phase addresses, not a backend staleness gap.
- Not yet live-tested end-to-end on a TV/phone (needs a backend restart to pick up the compiled change —
  owner's call, per standing instruction not to restart without asking; also per standing instruction, no
  deploy to a TV without asking).
