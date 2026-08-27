# Phase R212 — Show the last-known Home feed instantly on cold start (client-side, bounded-size cache)

> Found during the 2026-08-26/27 Ravilo TV startup-performance investigation. Today, every cold start
> is a bare network round trip: `HomeState` begins at `Loading` (`HomeStore.kt:31`), and the viewer
> watches a shimmer skeleton (`HomeLoadingShell`) until `getHome()` resolves — there is no local
> cache of *any* kind (no SQLDelight DB, no DataStore, not even a flat file) anywhere in
> `ravilo-android`/`ravilo-ui`/`shared`. Separately, `RaviloApp` renders Home with default display
> settings (`Skin.AURORA`, `tileScale=1f`, `gridColumns=6`) until the independent `getConfig()` call
> resolves and flips them (`RaviloApp.kt:256,261-271`) — a second, related "wrong-then-right" flash on
> every cold start. This phase caches both, in one small on-device file per user, to remove both
> flashes for a returning viewer.
>
> Raised explicitly during scoping: **on-device storage on a TV is often scarce.** This spec commits
> to a concrete, checkable size bound (see "Storage guardrails") rather than an open-ended cache.

**Status:** Planned.

## Goal
A viewer who has used this device before sees their **last-known Home feed and display settings
immediately** on app launch — no shimmer, no default-skin flash — while a fresh `getHome()`/
`getConfig()` still run in the background exactly as today and silently replace it the moment they
resolve. A first-ever login (nothing cached yet) is unaffected — same shimmer-then-content flow as
today.

## Current state
- `HomeStore` (`HomeStore.kt`): `_state` starts at `HomeState.Loading`; `load()` retries `getHome()`
  with exponential backoff (10 attempts, 1s→256s) before giving up to `HomeState.Error`.
- `HomeStore.refresh(silent = true)` (`HomeStore.kt:136-148`) already implements the exact swap
  behavior this phase needs for the *live* case: replace the shown feed with a fresh one, no Loading
  flash, only once something is already `Loaded`. Explicitly documented as a no-op while not yet
  `Loaded` (`:138`) — this phase's job is to make that starting condition true from a persisted
  snapshot instead of only from a completed network fetch.
- `RaviloApp.refreshConfig()` (`RaviloApp.kt:261-271`) is the equivalent for skin/tileScale/
  gridColumns/lang, fired once via `LaunchedEffect(Unit)` on the `Dest.Home` fast path
  (`RaviloApp.kt:376-378`).
- No local persistence of either exists today beyond `SharedPreferences`/`localStorage` for
  `base_url`, sessions, and device identity (all tiny scalars, not the feed itself).

## Design

### FR-RV-R212-1 — A small `expect`/`actual` snapshot store, keyed per user
```kotlin
@Serializable
data class HomeSnapshot(
    val feed: HomeFeed,
    val uiLanguage: String,
    val skin: Skin,          // or however RaviloTheme's Skin type is represented
    val tileScale: Float,
    val gridColumns: Int,
    val portraitGridColumns: Int,
    val savedAtEpochMs: Long,
)

expect object HomeSnapshotCache {
    fun load(userId: String): HomeSnapshot?
    fun save(userId: String, snapshot: HomeSnapshot)
    fun clear(userId: String)   // called on sign-out / session removal
}
```
- **Android actual**: one JSON file per user under `context.filesDir` (e.g.
  `home_snapshot_<userId>.json`), written via a full-overwrite (not append) — matches the
  `FileIo`-only-outside-`dev.jellystructure.io`-rule pattern already established for the *server*
  ([[reference-ktor-native-fd-setsize]] is a Kotlin/Native backend rule and doesn't apply to the JVM
  Android client, but the same "always fully close, no leaked handles" discipline applies) using
  ordinary `java.io.File` write-then-rename for atomicity (never a partially-written file left behind
  by a crash mid-write).
- **Web (`wasmJs`) actual**: `localStorage.setItem("ravilo_home_snapshot_<userId>", json)` — browser
  storage isn't the scarce resource here, but the same key-per-user shape is used for consistency.
- **Not implemented for Tizen** in this phase (matches the precedent set by R190's Tizen omission) —
  `ravilo-tizen` doesn't share `HomeStore`/`RaviloApp` today; revisit if/when it does.

### FR-RV-R212-2 — `HomeStore` seeds from the cache instead of always starting at `Loading`
`HomeStore`'s constructor gains the active `userId` and an optional seeded `HomeSnapshot`. On
construction:
- If a snapshot exists **and** is younger than the staleness cutoff (see FR-RV-R212-4), `_state`
  starts at `HomeState.Loaded(snapshot.feed)` instead of `Loading` — the shimmer never shows for a
  returning viewer.
- `load()` still runs immediately in the background exactly as it does today, **but when a snapshot
  was used to seed the state, it runs in `refresh(silent = true)`'s style** (swap on success, never
  disturb the shown feed on failure) **for the first attempt only** — not the full visible
  Loading→retry→Error sequence, since the viewer already has something reasonable on screen. If no
  snapshot was available (first-ever login, or the cutoff was exceeded), behavior is **completely
  unchanged**: the existing `Loading` → 10-attempt backoff → `Error` sequence runs exactly as today.
- Every successful `getHome()` (seeded or not) writes through: `HomeSnapshotCache.save(userId,
  HomeSnapshot(feed = freshFeed, ...current config fields..., savedAtEpochMs = now))`.

### FR-RV-R212-3 — `RaviloApp` seeds skin/tileScale/gridColumns/lang the same way
Rather than a second independent cache, `RaviloApp` reads the **same** `HomeSnapshot` (it already
carries the display fields) before `getConfig()` resolves, so the default-Aurora-then-flip flash
(investigation finding #6) disappears for the same returning-viewer case this phase already targets.
`refreshConfig()`'s success path also writes through to the snapshot's display fields (via
`HomeSnapshotCache`, keeping the two in one file rather than introducing a second one).

### FR-RV-R212-4 — Staleness cutoff
A snapshot older than **7 days** (`savedAtEpochMs`) is treated as absent — `HomeStore`/`RaviloApp`
fall back to today's behavior (`Loading` shimmer, default display settings) rather than risking a
viewer seeing a materially out-of-date catalog after a long absence. This number is a starting point,
not load-bearing — easy to tune later without any structural change.

## Storage guardrails (the scarce-TV-storage concern)
- **One file per user, always overwritten in place — never a growing history.** A device with 3
  cached profiles has exactly 3 snapshot files, regardless of how many times each has refreshed.
- **Images are never part of this cache.** `HomeFeed`'s cards carry poster/backdrop *URLs* only —
  the pixels themselves stay exclusively in Coil's existing, already-budgeted 150MB disk cache
  (`RaviloAppContext.kt:25-29`), untouched by this phase.
- **Expected size is small.** A `HomeFeed` is heroes + a handful of rows of `MediaCard`s (ids,
  titles, poster/backdrop URLs, progress, badges) — on the order of a few hundred cards even for a
  large personalized Home, each serializing to a few hundred bytes of JSON. A realistic upper bound
  is well under **500 KB**; a pathological Home (dozens of oversized rows) is bounded by whatever
  `HomeFeedService` already caps server-side, since the client only ever caches exactly what the
  server sent.
- **Explicit hard cap as a defensive backstop**: `save()` skips writing (logs and leaves the previous
  snapshot in place) if the serialized JSON exceeds **2 MB** — a size that should never occur in
  practice given the above, but costs nothing to guard.
- **Cleaned up on sign-out**: `HomeSnapshotCache.clear(userId)` is called wherever a session is
  removed (`MultiTokenStore.remove()`'s call sites) so a signed-out profile doesn't leave its
  snapshot behind indefinitely.

## Invariants
- **Never treated as more current than a fresh response** — a snapshot is exactly what a prior
  successful `getHome()`/`getConfig()` returned, verbatim, never merged or locally modified; the
  instant a real fetch succeeds, its result wins, matching [[fe-reflects-be-no-derived-state]] and
  the constitution's "the TV renders server-pushed state; it holds no derived catalog state." This
  phase does not compute or infer anything — it only delays repainting an exact prior server payload.
- **A stale-but-shown snapshot never masks a real failure silently forever.** See FR-RV-R212-5 below.

### FR-RV-R212-5 — Staleness indicator on persistent background-refresh failure
If the seeded-snapshot background refresh (FR-RV-R212-2) fails repeatedly with no successful
`getHome()` for longer than one normal retry cycle would have taken, a small, non-blocking indicator
(e.g. a subtitle under the AppBar clock, or a corner badge — exact placement is an implementation/
design-review decision, not fixed here) communicates "showing saved content, couldn't refresh" rather
than leaving the viewer to discover staleness only when something they try to play fails. Clears the
instant a refresh succeeds. This is connectivity/freshness *metadata* the client legitimately owns
(not catalog data), so it doesn't conflict with the no-derived-state invariant above.

## Non-goals
- **Not a general offline mode.** Playback, search, and every other screen still require a live
  connection exactly as today — this phase only smooths the Home screen's first paint.
- **Not caching anything beyond Home + display settings** — Discover/Upcoming availability,
  Live TV channels, and detail pages are unaffected and keep their current Loading-on-every-visit
  behavior.
- **Not a cross-device sync mechanism** — purely a local, per-device, per-user snapshot.

## Acceptance
- Force-quit and relaunch with a previously-loaded profile: Home appears instantly with the
  last-known feed and correct skin/density, no shimmer, no default-then-flip. *(On-device
  verification is user-initiated per [[feedback-no-tv-deploy]]; this phase's own checks are
  compile + a unit test around `HomeSnapshotCache`'s save/load/staleness-cutoff logic.)*
- First-ever login on a fresh install: unchanged shimmer-then-content behavior (no snapshot exists).
- Airplane-mode relaunch with a fresh (<7 day) snapshot: Home shows the stale feed immediately; the
  staleness indicator (FR-RV-R212-5) appears once the background refresh has clearly failed rather
  than the screen looking falsely "fully synced" forever.
- Snapshot file size stays within the documented bound on a real library-sized Home feed (spot-check
  during implementation, not an automated size regression test).

## Source references
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeStore.kt` — `HomeState`,
  `load()`, `refresh(silent)` (the swap pattern this phase reuses for the seeded case).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/RaviloApp.kt:256,261-271,376-378` —
  `refreshConfig()` and its Home-fast-path trigger.
- `ravilo-ui/src/androidMain/kotlin/dev/jellystructure/ravilo/ui/RaviloAppContext.kt:25-29` — Coil's
  existing 150MB image disk cache, explicitly untouched by this phase.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:274` — `HomeFeed`, already
  `@Serializable`.
- Investigation: [[project-ravilo-tv-startup-investigation-2026-08]].
