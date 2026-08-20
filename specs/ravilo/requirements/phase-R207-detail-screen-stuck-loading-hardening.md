# Phase R207 — Series detail can get stuck on "Loading…" indefinitely (hardening; root cause not confirmed)

> Found live during the 2026-08-18/20 screenshot session (`presentation/observed-issues-2026-08-18.md`,
> item 1): opening a series left the detail screen on `Loading…` and it never resolved, reproduced
> twice at the same title, recovered only by leaving to Home and back. Backend was healthy at that
> moment (`/api/health` 2.5 ms, load 2.72), so this reads as client-side. **This spec is deliberately
> honest that static code review could not reproduce a code path that gets permanently stuck** — the
> fetch/timeout/error machinery is already reasonably well-guarded. What it *did* find is one real,
> separate correctness bug in the same code (a stale-job race) and a real UX gap (no retry). Both are
> fixed here as defense-in-depth, matching the original note's own recommendation: "worth adding
> regardless of root cause: a timeout → retry/error state, so 'Loading…' forever is not a reachable
> end state."

**Status:** Implemented (hardening only — see "What this does not claim to fix" below).

## Investigation

### What's already in place (ruled out)
- Every REST call (Android) goes through an `HttpClient` with `install(HttpTimeout) { connectTimeoutMillis
  = 5_000L; requestTimeoutMillis = 10_000L; socketTimeoutMillis = 10_000L }`
  (`RaviloRootActuals.kt`, added 2026-07-09, commit `056035eb`, for this exact class of symptom
  — its own comment: *"a genuinely unreachable/slow server... fell through to the OS's raw TCP
  connect timeout, often 60-120+s... this could compound into minutes of an apparently 'stuck
  forever' shimmer skeleton with no feedback"*). So an unbounded network hang should already surface
  as an `Error` within ~10s, not stay `Loading` forever.
- `SeriesDetailStore.load()`/`MovieDetailStore.load()` wrap the whole fetch in `runCatching` and set
  `Error(message)` on any failure, including a timeout exception.
- `RaviloApp.kt`'s navigation uses `AnimatedContent(contentKey = { it::class })`, so pushing a new
  `Dest.Home` (different class) between two `Dest.SeriesDetail` attempts forces a genuine remount —
  `SeriesDetailScreen`'s `LaunchedEffect(itemId) { store.load(itemId) }` does fire again on the second
  attempt, and `store.load()`'s own logic (`currentId == id && state is Loaded`) correctly falls
  through to a fresh fetch when the cached state is still `Loading`, not `Loaded` — it doesn't skip
  the retry just because `currentId` is unchanged.

None of this rules out a *slow* (not infinite) backend response for one specific title, but a slow
response would still cross the 10s client ceiling and become an `Error`, not an indefinite `Loading`.

### What was found instead
A real race in `MovieDetailStore.load()`/`SeriesDetailStore.load()`: calling `load(id)` again while a
previous `loadJob` is still in flight does `loadJob?.cancel()` then relaunches — but the *cancelled*
coroutine's `runCatching { apiClient.getXxx(id) }` catches its own `CancellationException` (an
ordinary `Throwable` to `runCatching`) and continues past the catch to write `_state.value =
Error(...)`. If that write lands **after** the newer job's write (a real possibility — the two run on
different coroutines with no ordering guarantee between them), the cancelled job's stale `Error`
silently overwrites the newer job's correct `Loaded`/`Loading` state. This doesn't match the exact
reported symptom (it produces a flip to `Error`, not a permanent `Loading`), but it's a genuine
correctness bug in the same method, worth fixing regardless.

Separately: the `Error` state has no retry affordance today — just static text (`Text(s.message, ...)`)
in both `SeriesDetailScreen.kt` and `MovieDetailScreen.kt`. `HomeScreen.kt` already solved this exact
problem for its own error state (`HomeErrorState`, with a focusable Retry button) — the detail screens
never got the equivalent.

### What this does not claim to fix
If the real trigger is something outside static analysis's reach (a specific title's backend response
genuinely hanging past 10s without the client timeout firing, a platform-specific engine quirk, etc.),
this spec does not claim to have found or fixed it. What it guarantees instead: **even if it recurs,
it's no longer a dead end** — the screen offers a Retry action, and the identified race can no longer
silently corrupt a good load with a stale one.

## Requirements

### FR-RV-R207-1 — Stale job writes can't clobber a newer job's state
Guard `_state.value` writes inside the launched coroutine with a generation counter: increment a
`loadGen` on every `load()` call, capture it locally, and skip the write if `loadGen` has moved on by
the time the coroutine resumes. Applies to both `MovieDetailStore.load()` and
`SeriesDetailStore.load()` (same duplicated pattern in both).

### FR-RV-R207-2 — Error state offers Retry
Add a `DetailErrorState` composable (message + a focusable Retry button, styled like `HomeErrorState`
minus its Sign-out action, which is specific to Home's auth-invalidation case) and use it in place of
the bare `Text` in both `SeriesDetailScreen.kt`'s and `MovieDetailScreen.kt`'s `Error` branches. Retry
re-invokes `store.load(itemId)`.

## Invariants
- A stale/cancelled load can never overwrite a newer load's result, in either detail store.
- No detail screen's `Error` state is a dead end — every one offers a way back to `Loading` without
  leaving the screen.

## Out of scope
- Backend-side investigation of why the original fetch might have hung for this specific series —
  needs on-device reproduction with server-side request logging for that title, which requires TV
  access; not available this session. If it recurs, capture the series id and check the backend log
  for that request's timing before assuming it's client-side again.
- Applying the same generation-guard pattern to other stores (`HomeStore`, `BrowseStore`,
  `SeededBrowseStore`, etc.) — not reported as broken there, and `HomeStore`'s retry-with-backoff
  already works differently. Revisit only if a similar report surfaces for one of them.

## Source references
- Guarded stores: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/DetailStore.kt`
  (`MovieDetailStore.load`, `SeriesDetailStore.load`).
- Error UI: `.../screens/SeriesDetailScreen.kt`, `.../screens/MovieDetailScreen.kt` (`Error` branches).
- Retry pattern reused from: `.../screens/HomeScreen.kt` (`HomeErrorState`).
- Timeout config (unchanged, already correct): `.../androidMain/.../RaviloRootActuals.kt`,
  `.../wasmJsMain/.../RaviloRootActuals.kt`.
