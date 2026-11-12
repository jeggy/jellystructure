# Artwork is on the screen as soon as the app is

Task: DEB-4390

## Goal

The owner, 2026-11-12: *"Very often (atleast on stue tv) when opening ravilo it works fine, but all
artwork is missing and takes long before it's showing anywhere. I'm able to open a collection and click
play on something before even something renders any posters. The streaming works perfectly fine …
Both backend should be fast in responding and the ravilo tv app should already have some of this
cached (if its not taking up too much space)."*

When the TV app opens, the artwork the viewer saw last time is drawn with the first frame. Artwork it
has never seen arrives in about a second. An image that fails to load is tried again rather than left
as an empty tile until the viewer leaves the screen.

## What was measured (2026-11-12)

- **The backend is not the slow part.** About three minutes after a prod restart (index cold), twelve
  random posters from `/api/tv/image/{id}/poster` each took 21–31 ms to the first byte. Forty
  `backdrop?w=640` requests, 20 at a time and including resizes that ran live through ffmpeg, finished
  with a p50 of 0.49 s and a max of 0.88 s. The backend logged no `RaviloArtwork: gate busy` line.
  `RaviloArtworkService`'s own cache holds 19 072 files (1.2 GB of a 2 GB cap).
- **The client's image path has three gaps** that can each turn a short network hiccup at launch into
  a screen of empty tiles. The usual case is a TV waking from standby before its Wi-Fi is back, which
  R210's timeout comment already names.
  1. `RemoteImage` (`ravilo-ui/.../seams/ImageLoader.kt`) builds its request once per URL
     (`remember(resolved)`) and uses the placeholder for `error` as well. A load that fails shows as
     "still loading" and is **never retried** until the tile leaves the composition. Every tile on
     Home, and on a collection grid opened during the same dead window, stays empty. Meanwhile
     HomeStore retries its REST calls and R212 draws the cached feed, so the rest of the app looks
     fine. Media3 opens its own connections, so playback works too. That is the owner's report.
  2. Images and REST share one bare `OkHttpClient()` (`RaviloAppContext.okHttp`, R316). Its default
     dispatcher allows 5 requests per host at once, and image calls have no call timeout. Socket
     reads time out after 10 s. A launch that starts the visible tiles, 4-tile row prefetches, two
     hero slides and their logos, and the Home REST calls behind those 5 slots queues every new
     image behind the slowest request. A socket left dead by standby holds its slot for the whole
     10 s.
  3. After a long standby, the first requests reuse pooled connections that the network has
     already dropped.
- **The disk cache already exists**: 150 MB in `cacheDir/ravilo_image_cache` (R63). At ~45 KB per
  320 px poster it holds several thousand. When posters are missing it is because the request never
  reached it or failed, not because it is too small. `cacheDir` is space the OS may reclaim when
  storage runs low. That keeps Ravilo from ever filling a TV, which is what the owner's "if it's not
  taking up too much space" asks for, so it stays where it is.

Not proven on the device (this seat has no device reach): which of 1–3 fires on the Stue TV, and
whether Android has trimmed that TV's cache. FR-6 measures both before and after.

## Requirements

### FR-1 — A failed artwork load is tried again

When a `RemoteImage` load fails while its tile is on screen, it is retried after 1, 2, 4, 8, 16 and
then every 30 s for as long as the tile stays composed. It is also retried at once when the device's
network becomes available and when the app returns to the foreground. A retry that succeeds
crossfades in exactly like a first load. The tile looks the same while it waits: no broken-image icon
or error text, because a viewer never needs to know. A load cancelled because the tile left the
screen is not a failure and is not retried. This applies to every client that uses `RemoteImage`,
not only Android TV.

### FR-2 — Artwork has its own lane

Coil's network fetcher on Android uses its own OkHttp client, derived from `RaviloAppContext.okHttp`
with `newBuilder()` so that it shares the connection pool but gets its own `Dispatcher`.
`maxRequestsPerHost` is 8 and `callTimeout` is 15 s. REST calls keep their own client and dispatcher.
A REST call can no longer hold a slot an image is waiting for, and an image can no longer hold one a
REST call is waiting for. R316's FR-R316-1 ("Android fetches over OkHttp") still holds.

### FR-3 — Waking from standby starts on fresh connections

When the Android app returns to the foreground after 60 s or more in the background, it evicts the
shared pool's idle connections (`connectionPool.evictAll()`) before Home refreshes. The first image
and REST requests then open new sockets instead of waiting out dead ones.

### FR-4 — The first screen comes from the cache, without the network

1. Coil's disk cache stays at 150 MB in `cacheDir`. It is not moved to storage the OS cannot reclaim,
   and it is not made larger.
2. A disk-cache hit draws without a network round trip. The image loader is configured so this is
   stated, not assumed: no revalidation of a cached image whose URL is unchanged. R214's `?v=` already
   changes the URL when the bytes change.
3. When Home seeds from the R212 snapshot, the hero's first backdrop and logo and the posters of the
   first two rows are enqueued at once. They are requested at the same sizes the tiles will ask for,
   so that a viewer who opens the app and scrolls straight down sees posters, not placeholders. This
   is the cold-start half of DEB-4290.

### FR-5 — The server makes every size the TV asks for

Restores **FR-220-1** ("the pipeline produces what the TV will ask for"). The landscape tiles
(Continue Watching and other 16:9 rows) request `backdrop?w=640`. `RaviloArtworkService.presize`
makes backdrop 1920 but not 640, so each such tile's first request resizes live. `presize` also
writes `backdrop` at 640, under the same keys the request path computes, for both ids a card can
carry.

### FR-6 — Measured on the Stue TV, before and after

A QA run on the Stue TV, with the release build, records the four cases below. The Before half runs
on today's build, before FR-1–FR-4 land. For each case it records:
- the app's cache size from `dumpsys diskstats` before opening;
- the time from first frame to the first poster drawn, and to all posters of the first Home screen
  drawn;
- whether any tile on the first screen is still empty after 10 s.

The four cases:
- **(a)** opening after the TV has been in standby for 10 minutes or more;
- **(b)** opening after a force-stop, cache warm;
- **(c)** opening with the app's cache cleared;
- **(d)** turning Wi-Fi off for 15 s right after launch and back on.

**Pass:**
- (a) and (b): the first screen's posters are all drawn within 1 s of the first frame.
- (c): they are all drawn within 3 s.
- (d): every tile fills within 5 s of the network coming back.
- In no case does a tile stay empty while its screen is open.

## Non-goals

- A bigger cache, or one the OS cannot reclaim. The owner's space condition is met by leaving the
  cache where it is.
- Any change to the wire, to the image URLs, or to `Cache-Control` (R214's "deliberately unchanged"
  stands).
- The fast-scroll stutter itself (DEB-4290). FR-4.3 removes its cold-start half; the decode cost is
  that task's.

## Source references

- `ravilo-ui/src/commonMain/.../seams/ImageLoader.kt` — `RemoteImage`, `sizedProxyUrl`, the R88
  prefetch helpers.
- `ravilo-ui/src/androidMain/.../RaviloAppContext.kt` — the shared `okHttp` and the Coil
  `ImageLoader` (memory 20 %, disk 150 MB).
- `ravilo-ui/src/androidMain/.../RaviloRootActuals.kt` — the REST client on the same OkHttp, and
  R210's timeout comment.
- `src/linuxX64Main/.../tv/RaviloArtworkService.kt` — `presize`, `resizeServe`, the default widths.
- Related: R63 (image cache), R88 (prefetch), R212 (Home snapshot), R214 (`?v=`), R316 (OkHttp),
  phase 220 (presize, the gate), DEB-4290.
