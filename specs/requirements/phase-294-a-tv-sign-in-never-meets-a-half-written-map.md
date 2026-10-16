# Phase 294 — A TV sign-in never meets a half-written map

> Found live on production, 2026-10-02 (backend v1.48-33-g1ae6c1c4).

## Scope

Written 2026-10-02 (dev-authored), against `main` `145fc0f9`. Not dev-reviewed.

**Amends** phase 224 (`DeviceIdentityRegistry`, FR-224-4) and the R86-B token cache in `RaviloDeviceService`: both
described "plain map, no lock" as a discipline. It isn't one on Kotlin/Native.

## What happens

1. A TV signs in. The backend logs `TV login succeeded for user '…' on device …` and at once
   `[ERROR] Unhandled route exception on /api/tv/login: This cannot happen with fixed magic multiplier and grow-only
   hash array. Have object hashCodes changed?`. The TV shows *Couldn't reach the server — try again*.
2. The message is Kotlin/Native's `HashMap` finding its own table inconsistent while inserting: either a key's hash
   changed after it went in, or two threads changed the map at the same time. The keys here are `String`s, so it is
   the second.
3. After *login succeeded* the route calls `RaviloDeviceService.loginDevice`, which writes two process-wide maps:
   - `DeviceIdentityRegistry.byToken` (a plain `HashMap`): `remember()` is called by **every** authenticated TV
     request (`validateDeviceToken`), on whatever Ktor/Dispatchers thread serves it, and by `allDevices()`. A login
     inserts a new key (a new Jellyfin token), which can grow the table while another thread inserts.
   - `RaviloDeviceService.tokenCache` (a plain `HashMap` of entries with a mutable field): read, inserted and removed
     by every authenticated request, removed by a login.
4. The row was already written, so the viewer is in fact signed in on the server, but the TV never got its token. A
   corrupted map can also keep failing, or loop, for later requests.
5. The same unlocked pattern is in the other request-path caches: `SessionService.lastUsedWritten` and
   `ApiKeyStore.cache` (every admin request), `HomeFeedService`'s four caches and `BrowseService.facetsCache` (every
   Home and browse request, the first thing a TV does after signing in), and the Dashboard's Lidarr cache.

## Requirements

**FR-294-1 — The sign-in path's maps are thread-safe.** `DeviceIdentityRegistry` and `RaviloDeviceService`'s token
cache use a small locked map (`ops/LockedMap`, built on the existing `SpinLock`). Entries are immutable; the
once-a-minute *last seen* write is claimed in one atomic step, so two requests can't both write it. No database call
runs inside the lock.

**FR-294-2 — The siblings get the same.** `SessionService.lastUsedWritten`, `ApiKeyStore.cache`,
`HomeFeedService`'s feed, Continue Watching, channel-rail and channel-content caches, `BrowseService.facetsCache` and
the Dashboard's Lidarr cache become `LockedMap`s too. What each caches, and for how long, is unchanged.

**FR-294-3 — `DeviceIdentityRegistry.remember` writes only on a change.** Every request remembers its device again;
the map is written only when the identity is new or different.

**FR-294-4 — A regression test.** Eight threads hammer `LockedMap`, `DeviceIdentityRegistry` and
`RaviloDeviceService` (sign-ins while others validate tokens) at once. The final contents are exact and nothing throws.

**FR-294-5 — No wire change.**

## Out of scope

- The other shared maps in `tv/` that already take a `Mutex` or `SpinLock` (the playback tracker, the event bus,
  the session bridge, recommendations, renditions, …). Checked: each takes its lock.
- What the TV shows when a sign-in really fails (R175's copy stands).

## Acceptance

1. `SharedMapsConcurrencyTest` passes in `linuxX64Test`.
2. After deploy, signing a TV in while another TV browses shows the profile, and the log has no
   `grow-only hash array` line.

## Dev notes

- `SpinLock` already guards `TmdbClient`'s and `RecommendationService`'s caches (phase 182). Critical sections are a
  map get, put or remove; a spin is cheaper than a park for those.

## Build notes (2026-10-02)

**Built.** `ops/LockedMap` (get/set/remove, `removeIf`, `getOrPut`, `update`, `snapshot`, all under one `SpinLock`;
lambdas run inside the lock and are pure). `DeviceIdentityRegistry` uses it and writes only on a new or changed
identity. `RaviloDeviceService.tokenCache` holds immutable `TokenEntry`s; the once-a-minute `updateLastSeen` is claimed
with one `update` and written after the lock; the app-info refresh replaces the entry the same way. Siblings
converted (FR-294-2): `SessionService.lastUsedWritten` (the debounce claimed the same way), `ApiKeyStore.cache`
(`removeIf` on revoke), `HomeFeedService`'s `feedCache`, `continueListCache` (`snapshot()` for the refresh ages),
`channelRailCache`, `channelContentCache` (`removeIf` on invalidate), `BrowseService.facetsCache`, and the
Dashboard's `lidarrCache`.

**Root cause, confirmed:** with the lock taken out of `LockedMap`, `SharedMapsConcurrencyTest` fails on the
`LockedMap` and `DeviceIdentityRegistry` cases with exactly the production message (*"This cannot happen with fixed
magic multiplier and grow-only hash array. Have object hashCodes changed?"*); with the lock it passes. Two threads
inserting into one Kotlin/Native `HashMap` is enough.

**Audited and left alone** (already behind a `Mutex` or `SpinLock`): `PlaybackTracker`, `PlaybackWriter`,
`TvEventBus`, `JellyfinSessionBridge`, `EventsSocketHealth`, `ScreenStatusTracker`, `SeriesReplay`,
`DevicePolicyReconciler`, `RecommendationService`, `LiveTvService`, `AudioRenditions(Jobs)`, `DeferredDisconnects`,
`RaviloArtworkService`, `LoginRateLimiter`. Function-local maps were not counted.

**Verified:** `linuxX64Test` green, including `SharedMapsConcurrencyTest` (8 threads: `LockedMap` 160 000 inserts
with removes; `DeviceIdentityRegistry` 40 000 devices remembered twice with forgets; `RaviloDeviceService` on a temp
database, 2 400 validations with sign-ins between them).

**Owed (deploy):** acceptance 2, a TV sign-in on production with no `grow-only hash array` line in the log.

## Amendment (2026-10-16) — the sweep missed `LogoDownloader`'s two sidecar caches (DEB-1498)

FR-294-2 lists the siblings that become `LockedMap`s, and the build notes above close the audit with *"Function-local
maps were not counted."* Two maps fell between those sentences: `LogoDownloader.inkCache` (phase 232) and
`LogoDownloader.reinkCache` (R308). They are fields on the one process-wide `LogoDownloader`, not function-local, and
they are **written from the request thread** — so they belong in FR-294-2's list and were left as plain `HashMap`s.

### What it cost

Production, 2026-10-16T11:53:42.536Z, 4.25 s into the DEB-1479 deploy's new container:

```
[ERROR] Unhandled route exception on /api/tv/facets/summary: null
```

The route is R310's (FR-R310-3) Discover-wall counts. The path from it to these maps:

- `BrowseService.facets` misses its per-user `facetsCache` — every entry is cold after a restart — and calls
  `buildFacets`, whose `toFacets` calls `logoInk(kind, name)` and `logoReink(kind, name)` **once per studio and once
  per network**. One cold build therefore inserts hundreds of keys into both maps, growing each table repeatedly.
- `/api/tv/facets` and `/api/tv/facets/summary` are the only routes that reach `toFacets`, which is why no other
  route logged anything.
- Concurrently, `Main.kt`'s boot launch of `logoDownloader.computeMissingInk()` (background gate) is calling
  `inkCache.remove` / `reinkCache.remove` over every logo on disk, and `fetchMissing` removes from both after a batch.

Several viewers' clients were polling the server as it was recreated, so several cold builds ran at once. That is
exactly the condition this phase proved corrupts a Kotlin/Native `HashMap` — *"Two threads inserting into one
Kotlin/Native `HashMap` is enough"* — and a read walking a half-grown table throws a `NullPointerException`, whose
`message` is `null`. Hence the bare `null` in the log line.

**Not a cold-start readiness problem, and not new.** The hazard has been there since phase 232 added `inkCache`; it
needs concurrent cold facets builds, which is why earlier deploys in this family recorded 0 `[ERROR]` lines at
startup and why this did not recur in the minutes after. Nothing about the route is unready at 4 s, so a readiness
contract (503 + `Retry-After`, or an empty-but-valid `wallSummary`) is **not** the fix: it is wire-visible, and it
would answer a corrupted cache with a soothing 503 instead of repairing it. A `runCatching` around the handler would
do the same, worse.

**FR-294-6 — `LogoDownloader`'s sidecar caches, and `FlacIndex`'s, are thread-safe.** `LogoDownloader.inkCache`,
`LogoDownloader.reinkCache` and `FlacIndex.known` are `LockedMap`s. What each caches, and for how long, is unchanged;
the sidecar and FLAC-header reads stay outside the lock, as FR-294-1 requires. No wire change (FR-294-5 stands).

**FR-294-7 — A regression test.** `SharedMapsConcurrencyTest` gains the facets case: eight threads call `logoInk` and
`logoReink` over the same 400-logo set, as concurrent cold facets builds do. Nothing throws and every sidecar still
reads back its own value.

### The rest of the sweep, asked properly this time

The audit's boundary — "a field on a long-lived object, mutated from a request thread" — was applied by hand twice and
missed `LogoDownloader` both times, so the six other unlocked map fields in the backend were each put to the same
question. Five are fine, and the reasons are worth recording so a third sweep does not re-ask:

| Field | Verdict |
| --- | --- |
| `FlacIndex.known` | **At risk — fixed here.** `FlacIndex` is an `object`, and `capabilitiesFor` → `hasSeekTable(path)` writes `known` straight from the music playback-negotiation route (`MusicTvRoutes.kt:85`). Two devices starting a song at once both insert, and the 4 000-entry `clear()` makes a collision worse than a lost entry. Now a `LockedMap`. |
| `MusicTvService.userCache` | Safe — already behind `userLock: Mutex` (`MusicTvService.kt:140`); every read, write and `forget` takes it. |
| `PlaybackTargets.byApp` | Safe — already behind `mutex: Mutex` (`PlaybackTargets.kt:42`). |
| `MusicBrowse`'s `albumCache` | Safe — a field on `Ctx`, which is built per request ("once per request", `MusicBrowse.kt:253`), so it is function-local in everything but spelling. |
| `MusicSongIndex.parent` | Safe — written only in `init`. The union-find's path compression is why `rootOf` is precomputed there, as its own comment says: *"the snapshot is read from many request threads, and path compression writes."* |
| `AiJobs.heldBack` / `retryAt` | Writes safe — every mutation is inside `lock.withLock`. **Two reads are not**: `heldBack[job]` at `AiJobs.kt:636` and `:721` are outside it, so a read can walk the table while a locked write grows it. Narrower than the above (one writer, at AI-job cadence) and left alone deliberately rather than overlooked. |
