# R58 — Ravilo TV: fix startup error (1-3 seconds) (FR-RS1)

**Status:** Planned

## Goal

On app launch, a red/error state is shown for 1–3 seconds before the home screen appears. On the very first-ever cold launch (no previously persisted active user), the error is permanent.

## Root cause

`HomeStore.init {}` fires `load()` immediately. On Android the network stack is not yet warm after a cold launch, so the first `GET /api/tv/home` fails with a connection error. `HomeState.Error` is set and the `HomeErrorState` composable is shown.

The self-healing path: the `LaunchedEffect(activeUserId)` in `RaviloApp.kt` connects a WebSocket to `/api/tv/events`. On a successful connection it emits `liveConfig`, which `HomeScreen.kt` collects to call `store.refresh(silent = true)`. `refresh(silent)` retries the API call and emits `HomeState.Loaded` directly (skipping `HomeState.Loading`). The WebSocket takes 1–3 seconds to establish — hence the delay.

**First-ever launch bug**: `activeUserId` is initialized at the top of `RaviloApp` composable using `MultiTokenStore.getActive()?.userId`, *before* the `initialDest remember {}` block that calls `MultiTokenStore.setActive()`. On a first-ever install, no active user is persisted yet, so `activeUserId` is `null`. The `LaunchedEffect(activeUserId)` returns immediately — the self-healing WebSocket never connects, and the error state is permanent.

## Target behaviour

1. `HomeStore.load()` retries on failure with an exponential back-off (e.g. 1 s, 2 s, 4 s, max 3 retries) before setting `HomeState.Error`. This eliminates the transient cold-start failure.
2. During retries, keep showing `HomeState.Loading` (or show nothing / a subtle spinner) rather than `HomeState.Error`.
3. Remove `HomeState.Error` being set on the initial load attempt; only set it after all retries are exhausted.
4. **First-launch fix**: Ensure `activeUserId` is derived after `MultiTokenStore.setActive()` has been called (or derive it reactively from a `StateFlow` so it updates when the active user is set during `initialDest` resolution).

## Files

| File | Change |
|------|--------|
| `ravilo-ui/.../stores/HomeStore.kt` | Add retry loop (with `delay`) in `load()` before emitting `Error` |
| `ravilo-ui/.../RaviloApp.kt` | Fix `activeUserId` initialisation order so first-launch WebSocket fires |

## Non-goals

- No change to the `HomeErrorState` UI — it stays for genuine unreachable scenarios.
- No change to `refresh(silent = true)` — it already works correctly.
