# Phase 84 — Debounce the Library search (URL + results) (FR-LS1)

## Problem
The Library search box writes the query into the URL (so it's linkable/refresh-stable) and refetches
results — but it does so on **every keystroke**, so typing even slightly fast is janky. We want: type
freely; the URL ends at the **latest** value typed; and **live results still update while typing** (not
only after stopping).

## Findings (what's actually janky — and what isn't)
The search handler (`Library.kt:253-257`) fires `reload()` → `loadMore(scope, reset = true)` on **every**
`input` event, with **no debounce**. Per call, `loadMore` (`:799-853`):
- writes the URL (`:804 updateLibraryUrl()`), and
- **wipes the grid to "Loading…"** (`:805-807`), then
- does a fresh `MediaApi.list(...)` HTTP fetch (`:813-818`) and **innerHTML-rebuilds up to 60 poster
  cards** (`:834`).

Not the user's hypotheses:
- **Not history spam** — `updateLibraryUrl()` already uses `historyReplaceState` (`:100`,
  `JsInterop.kt:6`), so no extra history entries.
- **Not a full page re-render / focus loss** — `replaceState` doesn't fire `hashchange`, and the route
  handler only listens on `hashchange` (`Router.kt:42-46`), so `renderLibrary` isn't re-run and the
  `<input>` is never recreated (cursor/focus preserved).

The real causes:
- **(a)** A network fetch + full grid teardown (the "Loading…" flash + 60-card rebuild) on **every**
  keystroke — typing "batman" = 6 fetches and 6 grid flashes.
- **(b)** The `libLoading` re-entry guard (`:800 if (libLoading) return`) sits *before* the URL write and
  fetch, so a keystroke arriving during an in-flight fetch is **dropped with no trailing re-run** — under
  fast typing the displayed results *and the URL* can end up stale vs the latest typed value.

**Reference pattern already in the codebase:** the ⌘K command palette debounces with cancel-previous +
`delay(200)` + relaunch (`Shell.kt:203,269-271,302-303,345-348`) — only the last keystroke's fetch
survives.

## Goal
Smooth typing with live results and a URL that always lands on the final value.

## Requirements
1. **Debounce the search handler** (`Library.kt:253-257`), mirroring the ⌘K palette: add a module-level
   `private var libSearchJob: Job? = null`; on each `input`, set `libSearch` to the latest value
   immediately (so the input stays instant), then cancel the prior job and relaunch with a trailing
   `delay(~250ms)` before calling `loadMore(scope, reset = true)`.
   ```kotlin
   document.getElementById("lib-search")?.addEventListener("input") {
       val v = (document.getElementById("lib-search") as? HTMLInputElement)?.value?.trim()
       libSearch = if (v.isNullOrBlank()) null else v   // latest value captured now
       libSearchJob?.cancel()
       libSearchJob = scope.launch { delay(250); loadMore(scope, reset = true) }
   }
   ```
   Cancel-previous guarantees only the final keystroke's `loadMore` runs (URL ends at the latest value via
   the existing `replaceState`), and the trailing delay gives **live** results shortly after each pause —
   not only on a deliberate stop. This also sidesteps the dropped-keystroke staleness of guard (b) since
   debounced calls no longer overlap.
2. **(Optional polish) Drop the "Loading…" flash on a search refresh.** In `loadMore`, when it's a search
   refresh, skip the `grid.innerHTML = "Loading…"` wipe (`:806`) and replace the grid contents only once
   the page arrives — removing the remaining flicker.

## Scope
- `src/wasmJsMain/.../ui/Library.kt` — the search `input` handler (`:253-257`), a module-level
  `libSearchJob`, and optionally the `loadMore` loading-flash skip. (`Job`/`delay` imports already partly
  present.)

## Non-goals
- No router change (`replaceState` is already correct).
- Other filters (studio/network/genre/tags) which apply on explicit selection, not per-keystroke, don't
  need debouncing — though they could reuse the same `loadMore` reset.

## Acceptance
- Type a multi-character search quickly: the input never stutters, results refresh ~250ms after you pause
  typing (live, while still typing), and the URL ends exactly at the final typed value (refresh reproduces
  the same search). No "Loading…" flash on each keystroke if the optional polish is applied.
