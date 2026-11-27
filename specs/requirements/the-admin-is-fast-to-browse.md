# The admin is fast to browse

Task: DEB-5847

> Owner, 2026-11-27 (DEB-5847): *"Lets make Jellystructure faster. Currently the dashboard is loading too slow. When
> opening the songs library page it's slow and when opening an artist it's slow and opening the jellyfin collections
> page it's slow etc. Lets make all of this faster, so it's nice to browse around on the different pages on
> Jellystructure."*

## Scope

Backend (`MusicBrowse`, `MusicRoutes`, `MetadataRoutes`, the music image routes, one Ktor intercept in `Server.kt`) and
the admin's music grids (`MusicLibrary.kt`, `MusicArtist.kt`) and Metadata wall (`Metadata.kt`). No schema change, no
new dependency, no design round: every page looks exactly as it does today. The wire gains one optional query parameter
on image routes (`w`) and changes nothing else.

**The Dashboard is not specified here.** Its slowness, and the sidebar badge that makes every other page pay for it, are
[phase 285](phase-285-the-dashboard-is-one-overview-of-what-could-be-fixed.md)'s amendment of 2026-10-25
(FR-285-15 … FR-285-20, DEB-2497), written and sliced as DEB-2498 and DEB-2499 and never built. This ask is what
schedules them. This file adds only what that amendment does not cover.

Supersedes one sentence: phase 278's note of 2026-10-14 that *"the Artists view already mostly renders wordmarks rather
than images"* and that Songs *"is the only admin list that is both uncapped and large"*. Both were true on that day and
neither is now.

## Measured on prod, 2026-11-27

Signed in as the local admin, read-only, headless Chromium on the LAN against `jelly.jebster.net`, no scan running. Each
number repeated on at least two visits; none improved on the second, so none of it is a cold cache.

| What | Time / weight |
|---|---|
| `GET /api/dashboard` | **6.3 s** server time, every time; asked for **twice** on a cold load (page + sidebar), 13.6 s until both land |
| `GET /api/dashboard` on a cold load of *any other* page | 6.3 s, for the sidebar's *to triage* count |
| `GET /api/music/browse?view=artists` | **3.6–3.9 s** (423 artists) |
| `GET /api/music/artist/{id}/page` | **4.0 s**, for an artist with 2 albums and 23 songs |
| `GET /api/music/browse?view=songs` / `view=albums` | 0.4 s / 0.1 s |
| Library → Music → Artists, images | **339 requests, 142–149 MB**: each picture is the original 1000 × 1000 JPEG (~330–440 KB) drawn in a small circle; slowest 5.2 s |
| Same, second visit a minute later | 102 MB again — the response says `Cache-Control: private, max-age=60` though the URL is versioned (`?v=`) |
| Metadata → Studios | 179 requests, **16 MB**: logos up to 1.9 MB each, drawn at 46 px high |
| `GET /api/jellyfin-collections` | 26 ms; the page paints in ~0.4 s. What reads as slow there is the sidebar's 6.3 s dashboard call and, after the Artists view, its pictures still downloading |
| Films Library, Album page, Songs, Activity, Suggestions, Live TV, Users | 5–560 ms per request — not the problem |

**Why the two music endpoints are slow** (code read, `main` `6e50f9e84`). `MusicBrowse.artistRow` computes each artist's
song count with `songsBy(artistId)` (`MusicBrowse.kt:330`), a filter over **every live track** — about eleven thousand —
once per artist: roughly 4.7 million credit checks per request, all on one Kotlin/Native thread. Its album count
(`:321`) is the same shape over every album. The artist page then calls the whole Artists browse
(`MusicRoutes.kt:422`) to read **one** artist's `songs` from it, and also runs the whole-library Albums browse (`:408`)
to keep this artist's rows. No SQL, no Jellyfin and no file I/O are involved; it is CPU spent on a loop that a single
pass can replace.

## Requirements

- **FR-1 — An artist's counts cost one pass, not one pass per artist.** The music snapshot carries, built once per
  snapshot alongside `albumsByArtist`, each artist's folded song count and own-album count, computed in a single pass
  over the tracks and albums. `artistRow` reads them. The numbers are **identical** to today's `songsBy` and `own` for
  every artist — same folding (305 dev review 9: a song on an album, a single and a best-of counts once), same
  `missingSince` handling. Target: `view=artists` answers in **under 200 ms** server time on the household library.

- **FR-2 — The artist page builds only its own artist.** `GET /api/music/artist/{id}/page` reads the artist's song count
  from FR-1's index and builds album rows only for `albumsByArtist[id]`, never `MusicBrowse.browse(...)` over the whole
  library. The response is byte-for-byte what it is today for the same snapshot. Target: **under 150 ms** server time.

- **FR-3 — A grid picture is sent at the size it is drawn.** The music image routes (`/api/music/image/artist/{id}/{kind}`,
  `/api/music/image/album/{id}`) and the studio/network wall's artwork route accept an optional `w` (pixel width). With
  `w`, the server returns that width, resized once and held on disk, with the same mechanism and the same ProcessGate
  fallback as Ravilo's artwork service (`RaviloArtworkService.resizeServe`: one resize in flight per key, the cache
  entry fresh only while the source file's size matches, the original served under a saturated gate when it is small).
  Without `w`, the route serves the original exactly as today, so the album page's lightbox and every existing caller are
  unchanged. The admin asks for **2× the drawn size**: the Artists circles and the Albums grid covers at their CSS width
  × 2, the Studios wall at 2 × its 46 px height. Target: the Artists view's pictures total **under 10 MB**.

- **FR-4 — A picture the browser already has is not fetched again.** Every image response from the routes in FR-3 carries
  a content ETag and answers `304` to a matching `If-None-Match` (the existing `respondCachedBytes`, phase 118). A URL
  that carries a version (`?v=`) is cacheable for a day like every other artwork route; `private, max-age=60` goes. The
  cache stays truthful: after a picture is replaced in an artist's or album's Artwork tab, the grid shows the new picture
  on the next visit without a hard reload — if the existing `v` does not change when the bytes do, the version is taken
  from the file (size and mtime), as `RaviloImageUrl`'s `v` already is (R214).

- **FR-5 — The Artists grid loads the pictures it shows.** The Artists view's picture is an `<img loading="lazy"
  decoding="async" alt="">` filling the `.mu-circ` box, exactly as FR-278-16's correction did for album covers (inline
  positioning, `object-fit:cover`, **no CSS file change**; the wordmark branch and the match chip unchanged). Arriving on
  Artists issues picture requests for the circles in or near the viewport, not 339; leaving the page leaves nothing
  queued behind the next one.

- **FR-6 — A slow request names itself.** Any `/api/*` request whose handler takes longer than **1 s** writes one log line
  at INFO: method, the route *template* (`/api/music/artist/{id}/page`, never the concrete id or query string), status,
  and milliseconds. The set of slow routes is then read from the log rather than found by a household member, the same
  reasoning as FR-285-19 and FR-203-6. Image and streaming routes are excluded (their duration is the client's download
  speed); `/ws` is excluded. No new metric, no wire change.

## Out of scope

- The Dashboard and the sidebar count — phase 285 FR-15 … FR-20 (DEB-2498, DEB-2499).
- Paging the Artists view. 423 rows render in well under a second once FR-1 and FR-5 land; FR-278-14's pager stays on
  Songs only.
- The next tier, each under 1.5 s and each to be confirmed by FR-6's log before anyone touches it: a film's Bazarr panel
  (~0.7 s, an outbound call), Settings' ingest status (~1.4 s), an album's Files tab (~0.4 s), and the music snapshot
  discarding its lazy indexes on every music write (`MusicStore.kt:127`).
- Moving work off Kotlin/Native, a database change or a host change. Nothing measured points at any of them, and
  the 2026-09-13 read-latency investigation behind
  [phase 204](phase-204-a-library-write-must-not-cost-a-reader.md) ruled the database out with numbers.

## Acceptance

On the household server, after a deploy, signed in as an admin on the LAN:

1. **(FR-1)** Ten consecutive `GET /api/music/browse?view=artists&page=1&page_size=100` each answer in under 200 ms. For
   every artist, `albums` and `songs` equal the values the pre-change build returned for the same library (diff the two
   JSON bodies).
2. **(FR-2)** Ten consecutive `GET /api/music/artist/{id}/page` for three artists (one with many albums, one credited
   only, one with none on disk) each answer in under 150 ms, and each body equals the pre-change body.
3. **(FR-3, FR-5)** Opening Library → Music → Artists in a fresh browser profile transfers under 10 MB of pictures, and
   the pictures look as sharp as before on a 2× display. Metadata → Studios transfers under 3 MB.
4. **(FR-4)** Reopening Artists within the hour transfers no picture bytes (every picture is a cache hit or a `304`).
   Replacing one artist's picture, then reopening Artists, shows the new picture.
5. **(FR-6)** `curl` of a deliberately slow route (a test-only handler, or the unchanged ingest-status) prints one log line
   with the route template and the milliseconds; a fast route prints none.
6. With phase 285's FR-15 … FR-20 also deployed: Dashboard, Library → Music (each view), an artist, an album, Jellyfin
   collections, the films Library and Metadata each paint their content within **1 s** of opening on the LAN, from a cold
   load and from in-app navigation.

## Build order

Two lanes that touch different files, so they run side by side.

**Engineer:**
1. Phase 285's **DEB-2498**, then **DEB-2499** — the Dashboard. First, because it is the owner's first complaint and its
   sidebar count puts 6.3 s on the cold load of every other page.
2. **FR-3 + FR-4 + FR-5** — the resize parameter, the cache headers and the lazy circles, together: FR-5 without FR-3
   still sends 149 MB once the user scrolls, and FR-3 without FR-4 sends it again a minute later.

**Junior:**
1. **FR-1 + FR-2** — backend only, about 60 lines, proved by a `linuxX64Test` that builds a synthetic snapshot and asserts
   FR-1's counts equal `songsBy`/`own` for every artist and FR-2's DTO equals the old path's.
2. **FR-6** — one intercept and its test, after 1.

**QA:** the acceptance above, once both lanes are on prod.
