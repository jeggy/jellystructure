# Phase 64 — Jellystructure startup: connection test retry (FR-CS1)

**Status:** Planned

## Goal

When the Jellystructure admin app loads in the browser and the Jellyfin server happens to be momentarily unreachable for the first `testConnections()` call, the sidebar connection dot turns red and stays red until the user manually reloads the page. There is no automatic retry — the test runs exactly once at shell initialisation. The user sees "Jellyfin offline" even though Jellyfin comes back within seconds.

## Current behaviour

`Shell.kt` `init()` calls `testConnections()` once (lines ~167–177). Result is painted into `#status-jf`. No scheduled re-check is set up. If the check fails transiently, the dot stays red permanently for that page session.

## Target behaviour

- If `conn.jellyfin == false`, schedule a retry after 5 s (via `window.setTimeout`) up to a maximum of 3 retries.
- On success, update the indicator to green and stop retrying.
- After 3 failed retries, leave the indicator red (genuine offline).
- No change to the initial fast check — it still runs immediately on shell init.

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/Shell.kt` | Wrap `testConnections()` call in a retry loop using `window.setTimeout` via `js()` or a recursive coroutine with `delay` |

## Non-goals

- No new UI chrome — reuse the existing `#status-jf` indicator.
- No persistent polling (stop after first success or 3 failures).
- No changes to the WASM startup / loading screen.
