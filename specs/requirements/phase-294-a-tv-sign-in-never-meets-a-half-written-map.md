# Phase 294 — A TV sign-in never meets a half-written map

> Found live on production, 2026-10-02 (backend v1.48-33-g1ae6c1c4).

## Status

`✓ Built` 2026-10-02 (see Build notes). Written 2026-10-02 (dev-authored), against `main` `145fc0f9`. Number verified free (admin specs top at
293). Not dev-reviewed.

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
