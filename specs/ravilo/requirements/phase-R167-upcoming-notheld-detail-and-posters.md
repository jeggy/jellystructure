# Phase R167 — Upcoming: openable metadata detail + poster art for not-held items (FR-RV-UP2)

> Upcoming items we **don't hold yet** (a movie, or a series with **zero** episodes in our library) have
> **no poster** (a flat gradient placeholder) and open a **bare, near-empty** detail page — the viewer
> can't see what the thing even is. Make a not-held item open a proper **metadata-only detail page with
> real art**, exactly the way the **Top 10 / Discover** detail does today for a title we don't hold.

**Status:** ✓ Done — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) — extends **R160** (built) and explicitly **picks up R160's deferred artwork item**
(R160 dev-review addendum #3 deferred not-held-item artwork "to avoid a new external-image pipeline + FD
surface"). This phase satisfies it the **Discover way** — the TV loads the poster **directly from the
external CDN**, with **no jellystructure image proxy**, so R160's FD-surface concern does not apply.

## Problem
On the Upcoming calendar, held items look great (real poster from our on-disk artwork, and they open the
full series/movie detail). Not-held items are second-class:

- **No art.** `UpcomingCard` and `UpcomingDetailContent` both fall back to a title-seeded **gradient**
  whenever `posterUrl == null`, and `posterUrl` is *only ever set for held items*
  (`UpcomingService.kt:88`/`:118` key it off `matched`). So every movie/series we don't hold is a flat
  colour block with a title on it.
- **A bare detail page.** Opening a not-held item routes to `UpcomingDetailScreen`, which renders a
  gradient header, a kicker/relative-date chip, the title, a one-line meta row, whatever synopsis Sonarr/
  Radarr happened to supply, and a small schedule grid — **no backdrop, no poster, no genres list, no
  runtime, no cast**. Compared to the **Discover detail** (the page the user pointed at as the model),
  it's threadbare.
- **The user reports it "just fails".** The not-held path does not hard-crash in current code
  (`UpcomingDetailStore` re-fetches the feed and finds the item by id, rendering `UpcomingDetailContent`),
  but between the empty `Loading` state (renders nothing) and the art-less result, opening a not-held
  item reads as broken. (Note: the earlier raw-`{"error":"not found"}` failure was a *held*-item routing
  bug — the itemId used the internal slug instead of the Jellyfin id — already fixed this cycle. This
  phase must additionally make the *not-held* open path robust and never render a blank screen.)

## The model to replicate — how Discover shows a not-held title (verified in code)
This is the exact pattern the user asked for ("just like how the current Top 10 page does it"):

1. **Backend stores a TMDB relative path at ingest.** `ChartIngestService.kt:72-104` resolves a
   `tmdbId`, calls TMDB, and stores the **relative** `posterPath` / `backdropPath` / `overview` on the
   `ChartEntry` DTO (`shared/.../tv/Chart.kt:41-43`). No art bytes touch our server.
2. **The detail endpoint forwards them** and adds a live TMDB enrichment for genres / runtime / cast
   (`TvRoutes.kt:412-460`; `DiscoverDetail` = `entry` + `genres` + `runtime` + `isSeries` + `cast`).
3. **The client builds the absolute CDN URL itself.** `tmdbImg(path) = "https://image.tmdb.org/t/p/w780$path"`,
   `tmdbPoster = w500` (`DiscoverScreen.kt:371-376`); `RemoteImage` passes absolute `https://…` URLs
   through unchanged (`ImageLoader.kt:46-51`), so the TV fetches `image.tmdb.org` directly — **no
   `/api/tv/image` proxy, no on-disk cache, works for titles not in the library.**

**Net: the item does not need to be in our library to have art or a rich detail — the client loads the
external CDN, and the backend only forwards a path string + a small live-enrichment payload.**

## Art sources available (verified) — and why we don't need a proxy
- **Sonarr/Radarr calendar responses carry an `images` array** (`[{coverType:"poster"/"fanart",
  remoteUrl, url}]`) whose `remoteUrl` points at the upstream CDN (TheTVDB / TMDB / fanart.tv). We
  **currently drop it** — `ArrCalendarMovie` / `ArrCalendarSeriesRef` declare no `images` field
  (`ArrClient.kt:322-346`), and with `ignoreUnknownKeys = true` (`OutboundHttp.kt:49`) it's silently
  discarded. Capturing it gives a directly-loadable poster/backdrop URL for **both** series (Sonarr) and
  movies (Radarr), with **no tvdb→tmdb mapping** and **no proxy**.
- We also already have `mv.tmdbId` (Radarr) and `series.tvdbId` (Sonarr) server-side (used for catalogue
  matching, `UpcomingService.kt:59-64`), so a TMDB fetch (à la `ChartIngestService`) is a viable
  alternative for movies (series would need a tvdb→tmdb resolve).
- `UpcomingItem` carries **no** art field usable for not-held items today
  (`shared/.../tv/Upcoming.kt:23-47`) — `posterUrl` is the on-disk proxy path, only set when held.

## Requirements

### FR-R167-1 — Poster/backdrop art for not-held items (client loads external CDN, no proxy)
1. A not-held Upcoming item (card **and** detail) must show real **poster** (card) and **backdrop**
   (detail hero) art, loaded by the **client directly from the external CDN** — the Discover pattern —
   with **no jellystructure image proxy** and no on-disk caching (so R160's FD-surface concern is moot).
2. **Primary source: the *arr calendar `images[]` array.** Parse it into `ArrCalendarMovie` /
   `ArrCalendarSeriesRef` (add an `images` field), pick the `poster` and `fanart` `remoteUrl`, and carry
   them on `UpcomingItem` as new fields (e.g. `posterRemoteUrl` / `backdropRemoteUrl`, absolute URLs the
   client loads verbatim). This covers series and movies uniformly with no ID mapping. *(Alternative if a
   provider's `remoteUrl` proves unreliable: backend TMDB fetch by `tmdbId` for movies, mirroring
   `ChartIngestService`; series would need tvdb→tmdb resolution. Prefer `images[]`.)*
3. **Held items are unchanged** — they keep the on-disk `posterUrl` (R133), which is correct and already
   localized to our library artwork. `posterRemoteUrl` is a not-held-only fallback: the card/detail use
   `posterUrl` when present, else the remote URL, else the existing gradient (so a title with neither
   still degrades gracefully).

### FR-R167-2 — A metadata-rich, robust not-held detail page (Discover parity)
4. Opening a not-held Upcoming item must open a detail page with **Discover-detail parity**: a **backdrop**
   hero (from FR-R167-1), poster, title, **relative airs/aired chip** (already present), **synopsis**,
   **genres**, **runtime**, and — where obtainable — a **cast** row. The R160 **Schedule grid** (air/
   release date, time, release type, network) stays. Model the enrichment on `DiscoverDetail`: the
   backend does a live TMDB lookup (by `tmdbId`, resolving tvdb→tmdb for series) for genres/runtime/cast,
   forwarded in the detail payload — the same call `TvRoutes.kt:431-447` already makes for Discover.
5. **It must never render a blank screen.** The `Loading` state must show a shell/spinner (not nothing);
   a lookup miss must show the best-effort feed data we already have (title, date, whatever synopsis the
   *arr gave), never an error page or an empty frame.
6. **No action buttons** for not-held items (R160 invariant preserved) — My List is Jellyfin-owned and
   can't hold a title that doesn't exist yet; the page is informational. (A future "request via *arr"
   affordance is explicitly out of scope, consistent with R160.)

### FR-R167-3 — Routing stays R160-correct
7. Held items (series in catalogue → `itemId != null`) still open the **real** series/movie detail
   (`RaviloApp.kt:619-626`); only the not-held branch (`itemId == null` → `Dest.UpcomingDetail`) is
   enriched here. No change to when the real vs. lightweight detail is chosen.

## Invariants
- **No jellystructure image proxy for not-held art** — the TV loads the upstream CDN directly, exactly
  as Discover does; our server never fetches, caches, or serves these bytes (this is what makes it safe
  vs. R160's deferred concern).
- **Held items are untouched** — same on-disk `posterUrl` (R133) and same real detail page.
- **Server-pushed state only** — the client renders the URLs/metadata the feed/detail endpoint provides;
  it never queries Sonarr/Radarr/TMDB itself except loading an image URL the server handed it (the
  Discover precedent).
- **No source attribution** — the UI still never names Sonarr/Radarr/TMDB (R160).
- **Graceful degradation** — poster → gradient, detail enrichment → best-effort feed data; nothing about
  a not-held item ever produces a hard failure or blank screen.

## Out of scope
- Any **request / grab / retry** action on the not-held detail (acquisition stays Sonarr/Radarr's; R160
  fence holds).
- Proxying or **caching** external art on jellystructure (deliberately avoided — client-direct only).
- Localizing a not-held item's title/synopsis — we have no library data for it, so it uses the *arr /
  TMDB value; localized library titles for **held** items are **R166**.
- Reworking the **Discover** detail itself (this phase reuses its shape, doesn't change it).

## Source references / backend + client anchors
- Routing: `ravilo-ui/.../RaviloApp.kt:619-626` (Upcoming held vs not-held), `:585` (Discover always own
  detail).
- Client screens: `ravilo-ui/.../screens/UpcomingScreen.kt` (`UpcomingCard` poster/gradient `:441-446`),
  `UpcomingDetailScreen.kt` (`:105-110` art fallback, `:94-215` content), `DiscoverDetailScreen.kt`
  (`:97` backdrop from TMDB path — the model), `DiscoverScreen.kt:371-376` (`tmdbImg`/`tmdbPoster`),
  `ImageLoader.kt:46-51` (absolute URL passthrough).
- Backend: `src/linuxX64Main/.../tv/UpcomingService.kt` (`posterUrl` set only when held `:88`/`:118`),
  `arr/ArrClient.kt:307-346` (calendar DTOs — add `images`), `tv/TvRoutes.kt:412-460` (Discover detail
  builder to mirror), `media/ChartIngestService.kt:72-104` (TMDB-path-at-ingest precedent),
  `tv/RaviloImageUrl.kt:8-15` (on-disk-only proxy — deliberately *not* used here).
- DTOs: `shared/.../tv/Upcoming.kt:23-60` (add art URL fields), `shared/.../tv/Chart.kt:41-43` +
  `Discover.kt:33-43` (the parity target).
- Related: **R160** (calendar; addendum #3 is the deferred item this delivers), **R166** (localized held
  titles — sibling Upcoming fix), **R48–R50 / Discover** (the detail pattern), **R133** (on-disk artwork
  for held items).
