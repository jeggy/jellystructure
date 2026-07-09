# Phase R176 — Home-feed playstate: cache, concurrent fetch, cross-device live sync

## Goal
Every `/api/tv/home` request paid a live Jellyfin round trip for watched/in-progress state, and — on a
structural-feed cache miss — paid a **second**, **sequential** live round trip for the Continue Watching
row first. Measured live (profile-switch test, Pixel 9 + bedroom TV): a consistent 4.5–5.8s per load,
not decreasing on repeat visits to an already-loaded profile. Fix: (A) a short-TTL cache for playstate
so a repeat load within the window costs zero Jellyfin calls, (B) run the structural-feed build and the
playstate fetch concurrently instead of sequentially when a live fetch **is** needed, and (C) once a
live fetch completes, push the fresh state to every other device signed in as that user over
`/api/tv/events`, so an already-open Home/Browse/Search screen elsewhere patches its tiles instantly
instead of independently paying its own round trip on its own next load.

## Current state (verified in code, pre-fix)
- `HomeFeedService.getHomeFeed()`: on a structural-cache hit, called `hydrateWatched(device, cached.feed)`
  — one live Jellyfin call (`fetchPlaystate` → `getUserDataBulk`), bounded by `WATCHED_TIMEOUT_MS` (2.5s),
  **every single call, hit or miss** (the structural cache's key — libVer/cfgHash/allowedHash — deliberately
  excludes playstate, per its own comment, so a fresh structural cache never skips this).
- On a structural-cache **miss**: `buildHomeFeed()` first (which includes `buildContinueRow`'s own live
  `getResumeItems`/`getNextUp` calls, already run concurrently with each other, bounded by
  `CONTINUE_TIMEOUT_MS` = 6s), **then** `hydrateWatched()` afterward — two live-Jellyfin phases end to end,
  strictly sequential, worst case ≈8.5s.
- `hydrateWatched`'s candidate id list came from the **already-built feed's own rows** (excluding
  Continue), which is why it had to run after `buildHomeFeed` — a real data dependency, not an
  arbitrary ordering choice.
- `fetchPlaystate` (`PlaystateHydrator.kt`) chunks `ids` at 100/request behind a `Semaphore(4)`, but
  fetched chunks one at a time in a plain `for` loop — the semaphore's 4-way capacity went unused by its
  own primary caller; N chunks cost N sequential Jellyfin round trips.
- No playstate cache existed at all — every call re-fetched live, and no other connected device for the
  same user ever benefited from a fetch made on its behalf.
- `WatchedBus` (`ravilo-ui/.../screens/WatchedBus.kt`) already exists and is already collected by the
  retained Home/Browse/Search stores (R147) — but only ever published from a **local, same-device**
  action (marking something watched on the detail screen). Nothing server-pushed fed it.

## Requirements

### A. Playstate cache (`HomeFeedService.kt`)
1. New `playstateCache: HashMap<String, PlaystateEntry>` (`PlaystateEntry(data: Map<String, CardPlayState>,
   builtAt: Long)`), keyed by `jellyfinUserId` — separate from the existing structural `feedCache`.
2. `PLAYSTATE_TTL_MS = 20_000L` — deliberately shorter than the structural feed's `FEED_TTL_MS` (5 min):
   watched/in-progress state changes far more often than rows/heroes/channels.
3. Cache scope widened from "this feed's rows" to **this user's entire visible catalog**
   (`mediaStore.liveItems(device)`, the same already-cached list `buildHomeFeed` itself reads) — this
   removes the data dependency on the built feed entirely (a superset is always sufficient to hydrate
   any subset of rows), which is what unlocks requirement B, and makes the cache reusable by
   `getChannelFeed` too instead of being Home-specific.

### B. Concurrency
1. `getHomeFeed()`: `feedDeferred` (structural feed, from cache or `buildHomeFeed`) and
   `playstateDeferred` (from cache or a live fetch) are both `async` and awaited together — a cache hit
   on either side resolves immediately; a live fetch on one side runs while the other is in flight
   instead of after it.
2. `getChannelFeed()`: same pattern — `playstateDeferred` starts alongside the existing `allDeferred`/
   `tokenDeferred`, not after row-building finishes.
3. `PlaystateHydrator.fetchPlaystate()`: chunks are now `async`-fanned-out and `awaitAll()`-ed (still each
   individually gated by the existing `Semaphore(4)`) instead of fetched one at a time in a `for` loop —
   this benefits every existing caller (feed/browse/search/related), not just Home.

### C. Cross-device live sync
1. New shared envelope `PlaystateChangedEnvelope(type: String, patch: Map<String, CardPlayState>)`
   (`shared/.../tv/Models.kt`), pushed as `{"type":"playstate_changed","patch":{...}}` — the same
   payload-bearing-envelope pattern as `AcquisitionChangedEnvelope`.
2. `TvEventBus.notifyPlaystateChanged(userId, patchJson)` — per-user (like `notifyConfigChanged`), fans
   out to every device currently connected as that user (not just the one whose request triggered the
   fetch).
3. `HomeFeedService` takes `TvEventBus` as a new constructor dependency; whenever a **live** fetch
   actually happens (never on a cache hit — there's nothing new to tell anyone), the result is broadcast
   after being cached.
4. `TvApiClient.connectEvents()` gains an `onPlaystateChanged: suspend (Map<String, CardPlayState>) -> Unit`
   callback and a `"playstate_changed"` branch alongside the existing `"acquisition_changed"` one.
5. `RaviloApp.kt` wires `onPlaystateChanged = { patch -> WatchedBus.publish(patch) }` — reuses R147's
   existing patch-in-place path verbatim; no new client-side state machine.

## Scope
- Backend: `HomeFeedService.kt` (cache + concurrency rewrite, `applyPlaystate`/`fetchAllPlaystate`/
  `playstateFor` replacing `hydrateWatched`), `PlaystateHydrator.kt` (concurrent chunk fan-out),
  `TvEventBus.kt` (`notifyPlaystateChanged`), `Main.kt` (constructor wiring).
- Shared: `Models.kt` (`PlaystateChangedEnvelope`), `TvApiClient.kt` (`onPlaystateChanged` + branch).
- App: `RaviloApp.kt` (wires the new callback to the existing `WatchedBus`).

## Non-goals
- No change to the structural feed cache's own TTL/invalidation (`FEED_TTL_MS`, `libVer`/`cfgHash`/
  `allowedHash`) — untouched.
- No new client-visible loading state or UI — this is purely a latency/freshness fix; the response shape
  (`HomeFeed`) is unchanged.
- No dedicated test suite added — `HomeFeedService`/`PlaystateHydrator` had none before this phase either
  (heavy external dependencies — `JellyfinClient`, `ConfigStore`, `MediaStore`, `TvEventBus` — make them
  costly to unit-test in isolation); `applyPlaystate`'s patch logic is a verbatim carry-over from the old
  `hydrateWatched`, not new behavior.
- Browse/Search/Channel stores' own collection of `WatchedBus.patches` is pre-existing R147 wiring, not
  touched here — this phase only adds a second **source** feeding the same bus.

## Acceptance
- Repeat profile switches within ~20s of each other cost zero Jellyfin calls for playstate (served from
  `playstateCache`) and zero for structure within ~5 min (unchanged `feedCache` behavior).
- A genuinely cold load (both caches empty/expired) no longer pays the Continue-row fetch and the
  playstate fetch back to back — they overlap.
- Marking something watched on device A, or device A's own `/api/tv/home` triggering a live playstate
  fetch, patches device B's already-open Home/Browse/Search screen (same Jellyfin user) within one WS
  round trip — no re-fetch, no flicker, matching R147's existing same-device patch behavior exactly.
