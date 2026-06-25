# Phase R67 — Stop focus jumping to "Home" + stop needless genre reloads (FR-RV-NF1)

## Problem
Two related annoyances when navigating the Ravilo TV top nav and genre filters:
1. Selecting **Movies** or **Series** (or any tab) starts loading and **D-pad focus jumps from the
   selected tab back to "Home"** in the navbar.
2. On Movies/Series, picking a **genre** **reloads the whole genre list** (clears then refetches it,
   even though the genres never change), and during that reload **focus jumps from the picked genre up
   to "Home"**.

## Findings (root cause)

### Bug 1 — focus lands on "Home" after a tab switch
`BrowseScreen.kt:122-123` force-requests the AppBar entry-point requester on mount:
```kotlin
val navBarFR = remember { FocusRequester() }
LaunchedEffect(Unit) { runCatching { navBarFR.requestFocus() } }
```
…but `AppBar.kt:95-96` hardwires that requester to **nav item 0 = "Home"**
(`allFRs = listOf(navFR) + otherFRs`, and item 0 is `nav.home`). `BrowseScreen` already computes the
correct `activeNav` (1 = Movies, 2 = Series, `:130-135`) but uses it **only for the highlight**, never
for focus. So every screen entry plants focus on Home regardless of the active tab.

### Bug 2 — genre pick reloads genres + drops focus to Home
Selecting a chip calls `filterByGenre → load()` (`BrowseScreen.kt:165,94`), and `load()` does a full
**clear + double refetch** (`:81-92`): it sets `_state = BrowseState.Loading` (which un-composes the
whole `Loaded` UI incl. `GenreChips`) and refetches **both** results **and** `getFacets()` — but
`getFacets` takes only `kind` (`TvApiClient.kt:121`), so the genre list is identical every time;
refetching it is pure waste. With the focused chip disposed by the `Loading` swap, Compose has no focus
target in the content area, so focus falls back to the first focusable in the tree — the always-composed
**Home** nav item. `GenreChips`' `focusRestorer()` (`:212`) can't help — it only restores on *re-entry*,
not after focus has already escaped.

> **Latent functional bug to fix alongside:** `browse()` has no `genre` parameter (`TvApiClient.kt:101`),
> so the selected genre is stored in `activeGenre` but **never sent to the server** — the reload
> accomplishes nothing except destroying focus. The genre filter currently doesn't actually filter.

Stores are **kept** across navigation (R40 `keptStore`, `RaviloApp.kt:309`), and re-nav to the same kind
skips reload (`:112`) — so the reload is caused solely by the `filterByGenre → load()` clear+refetch.

## Goal
- Entering a tab leaves focus on the **active tab** (or moves into that screen's content), never snaps to
  Home.
- Picking a genre filters **in place** — the genre row and the focused chip stay mounted, genres are not
  refetched, and the genre is actually applied server-side.

## Requirements
1. **Entry focus follows the active tab.** Make the AppBar entry-point requester land on `activeNav`
   instead of index 0 — e.g. in `AppBar.kt:95-96` place `navFR` at the `activeNav` slot, or give
   `AppBar` an `initialFocusNav`/per-item requesters so `BrowseScreen` requests the active item. The
   existing `onDown`/`onUp` content↔bar bridges keep working.
2. **Filter genres without unmounting.** Split a `genre`-only path in `BrowseStore` that does **not** go
   through `BrowseState.Loading` and does **not** call `getFacets()` — keep the loaded `facets` and the
   `GenreChips` row mounted, swap only the grid results in place. The focused chip survives → focus never
   escapes. Cache facets once per kind.
3. **Actually apply the genre.** Add a `genre` parameter to `browse()` / the `/api/tv/browse` call so the
   selected genre filters server-side (today it's a no-op).
4. **Belt-and-suspenders focus.** With the row kept mounted, `focusRestorer()` (`:212`) works; optionally
   retain a `FocusRequester` for the active chip and re-request after results swap.

## Scope
- `ravilo-ui/.../components/AppBar.kt` (entry-focus on active tab)
- `ravilo-ui/.../screens/BrowseScreen.kt` (`BrowseStore.load`/`filterByGenre`, in-place filter, nav focus)
- `ravilo-ui/.../screens/ChannelScreen.kt` (same pattern if it shares the genre/filter flow)
- `shared/.../TvApiClient.kt` + backend `/api/tv/browse` (add `genre` param) — and confirm `BrowseService`
  applies it.

## Non-goals
- No change to the R40 kept-store model or the R42/43/47 focus-animation work.

## Acceptance
- Select Movies/Series from the navbar → focus stays on that tab (highlight + focus agree); no jump to Home.
- Pick a genre → the grid filters (server actually returns that genre), the genre row doesn't flicker/reload,
  and focus stays on the chosen chip.
