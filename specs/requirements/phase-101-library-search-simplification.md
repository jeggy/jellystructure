# Phase 101 — Library search simplification: drop the info box + inline facets, fix workbench-apply, add Clear filters (FR-LS2)

## Problem
The Library page's filter row has accumulated redundancy and a real bug:
1. A wordy **info box** ("Search matches every title this item has ever had …") takes space above the
   grid.
2. **Inline facet controls** (Studio · Network · Genre · Tags · Audio Track) sit in the filter row — but
   every one of these is **also** available in the "Add filter" **workbench** popup, so they're
   duplicated UI.
3. The **workbench "Add filter" → Apply** shows **zero results** until a manual page refresh, which then
   shows the correct results.
4. There is **no "Clear filters"** action, so resetting to a clean grid is fiddly.

The design mockup `design/app/library.html` is already **ahead** of the runtime — it has **no** inline
Studio/Network/Genre/Tags/Audio controls. So #1/#2 largely bring `Library.kt` in line with the existing
design.

## Findings

### #1 — The info box (cosmetic, safe to delete)
`Library.kt:204–206`, inside the `renderLibrary` template:
```kotlin
<div id="lib-search-note" class="note blue" style="…">
  Search matches <b>every title this item has ever had</b> — each language pulled from TMDB plus the
  original title, so a show pulled once in Danish stays findable by its Danish name even after re-resolving.
</div>
```
`lib-search-note` is referenced **nowhere else**. The **multi-language search logic stays** — it's
entirely separate (backend, Phase 29), reached only via the search string: input+debounce
(`Library.kt:271–276`), `libSearch` state (`:48`, parsed `:82`, URL `:102`, restored `:256`, sent as
`search=` `:868` → `MediaApi.kt:217`). Removing the box touches none of it. Design mirror:
`library.html:92` (and orphaned CSS `.search-note` `:23–25`).

### #2 — Inline facet controls (remove UI, keep state)
The inline controls exist **only in `Library.kt`** (design already omits them). Each writes a `lib*`
state var that is **also** driven by the workbench/URL — so the **state vars stay; only the inline UI +
its populate/wiring is removed**:
- HTML template `Library.kt:145–188` — the five blocks: Studio `:145–150`, Network `:151–156`, Genre
  `:157–162`, Tags `:163–168`, Audio track `:169–188`. Keep `lib-workbench` (`:189`) + `lib-saveas`
  (`:190`).
- Initial-load fetches `:235–242` — both `scope.launch { … trackFacets … populateAudioFilterPanel }`
  and `… metaFacets … populateMetaFilterPanel` (they exist only to fill the inline panels).
- Event wiring in `attachLibraryListeners`: audio-filter-btn toggle `:313–318`; meta-panel open/close
  loop `:320–338`; document-level click-to-close `:340–345`.
- Now-dead functions to remove entirely: `populateAudioFilterPanel` (`:717–766`), `populateMetaFilterPanel`
  (`:768–840`), and consequently `buildAudioDropdown` (`:556–715`, all 8 callers were inside those two);
  module vars `openAudioSubpanel`/`openMetaPanel` (`:553–554`).
- `syncFilterUiToState` (`:258`) — drop the `af-untagged` checkbox line.
- `updateActiveChips` (`:377–381`) — remove the five `btnHighlight(...)` calls (they target the inline
  buttons). **Keep the rest** of `updateActiveChips`, especially the removable active-filter chips
  (`:385–410`) — that's how the user sees/clears workbench-applied filters.

**Must NOT be touched** (shared state + query building): the `lib*` state vars (`:54–61`) fed by the
workbench via `applyWorkbenchToLibrary` (`:997–1018`) → URL → `parseLibraryUrl` (`:84–91`) →
`libConditionsFromState` (`:965–976`) → `loadMore` (`:867–876`); `langDisplay`/`codecDisplay` (used by
active chips). At the `/api/media` call these are repackaged into one `conditions=[…]&match=ALL` param
(R74; `MediaApi.kt:222–224`), so removing the inline UI changes nothing about the query.

### #3 — Workbench "Apply" → zero results (pagination-state race; ROOT CAUSE)
This is **not** a routing bug — the apply path *does* re-render; it renders empty because **stale
infinite-scroll state leaks into the new load**.

Apply path: Workbench Apply (`Workbench.kt:296–305`) → `applyWorkbenchToLibrary` (`Library.kt:997–1018`)
→ `App.navigate("/library?…")` → `Router.navigate` sets `location.hash` (`Router.kt:22–30`) →
`hashchange` → `App.handleRoute` → `renderLibrary` (`Main.kt:63`). The **WASM module is not reloaded**,
so all top-level `var`s persist and `App.scope` is the same across navigations.

The leak: `renderLibrary` (`:122–127`) resets `libScanSocket/libScannedCount/libPendingScanCount` and
calls `parseLibraryUrl()`, but does **NOT** reset the pagination vars `libSlice`, `libLoadedCount`,
`libTotal`, `libEndReached`, `libLoading` (`:41–45`) — those reset only **inside**
`loadMore(reset = true)` (`:856–857`).

The race (the wrong `loadMore` wins):
1. Canonical first load is gated behind a network call — `:224–234`:
   `scope.launch { val status = MediaApi.scanStatus(); … loadMore(reset = true) }` — it **awaits
   `scanStatus()` before** calling `loadMore`.
2. The infinite-scroll observer is armed at `:220–222`; `IntersectionObserver` **fires an initial
   callback on `observe()`** (`JsInterop.kt:36–45`), and on the fresh empty grid `lib-sentinel` is within
   the 700px root-margin → it immediately launches `loadMore(reset = false)`.
3. The reset=false load grabs the `libLoading` lock first (`:852–853`) because the reset=true load is
   stuck behind `scanStatus()`. With `reset=false` it skips the pagination reset (`:855–860`) and reads
   the **carried-over** `libSlice`/`libEndReached` from the previous view → fetches page **N+1 of the new
   narrower filter**; for a smaller result set that page is empty → `page.items.isEmpty()` →
   `libEndReached=true` (`:895–901`) → grid stays empty = **ZERO results**. (Large result set → renders
   the wrong slice, skipping items 1…N·60.)
4. The gated reset=true load finally runs but hits `if (libLoading) return` (`:852`) while the bad load
   holds the lock → the correct reset never happens.

Why a refresh fixes it: a full reload reloads the WASM module → `libSlice=0 / libEndReached=false /
libLoading=false` fresh → both loads start at page 1.

### #4 — Clear filters (no existing control)
Place a `<button id="lib-clear" class="chip">✕ Clear filters</button>` in the filter row after
`lib-saveas` (`:190`) — optionally shown only when something is active; the `#active-chips` container
(`:207`) is the alternative home. The canonical reset-and-reload path already exists and is exactly the
bulk version of the per-chip "×" handler (`:406–407`).

## Goal
A lean Library filter row — search box + quick chips + "Add filter" workbench + "Save filter as…" +
"Clear filters" — where applying a workbench filter shows the right results **immediately** (no refresh),
and one click resets to a clean grid.

## Requirements

### A. Remove the info box (#1)
1. Delete `Library.kt:204–206` (`#lib-search-note`). Delete the design mirror `library.html:92` (and the
   orphaned `.search-note` CSS `:23–25`). **Do not** touch any search/`libSearch` logic.

### B. Remove the inline facet controls (#2)
1. Remove the inline Studio/Network/Genre/Tags/Audio controls and their populate/wiring per Findings #2,
   keeping `lib-workbench`, `lib-saveas`, the active-filter chips, all `lib*` state vars, and the
   query-building chain intact. Net effect: those filters are reached **only** via the workbench, exactly
   as the design mockup already shows.

### C. Fix the workbench-apply zero-results race (#3) — the core bug
1. **Reset pagination/lock state on every `renderLibrary`.** Alongside the existing resets (`:123–126`),
   also set `libLoading = false; libSlice = 0; libLoadedCount = 0; libTotal = 0; libEndReached = false`.
   This removes the empty-grid case even if the observer load wins (it now starts at page 1).
2. **Stop racing the canonical first load behind `scanStatus()`.** Either (a) gate the sentinel observer
   with an "initial load done" flag so `loadMore(reset=false)` cannot run until the first
   `loadMore(reset=true)` completes, **or** (b) kick `loadMore(reset = true)` **immediately** and check
   scan status **concurrently** rather than sequentially. Implement (1) **and** one of (2a)/(2b) — (1)
   alone fixes the symptom, (2) removes the underlying race so future entry paths stay correct.
3. Acceptance is specifically: applying a workbench filter updates the grid in place with the correct
   results, **no manual refresh**, for both shrinking and growing result sets, and regardless of prior
   scroll depth on the previous view.

### D. Add "Clear filters" (#4)
1. Add `#lib-clear` after `lib-saveas` (`:190`); mirror it in `design/app/library.html` next to
   `#add-filter`.
2. On click, reset the full filter state: `libSearch=null`, `libFilter=null`,
   `libStudios/libNetworks/libGenres/libTags=emptyList()`, `libAudioLangs=emptyList()`,
   `libTrackTitle=null`, `libAudioCodec=null`, `libUntaggedAudio=false`, `libCoverageCond=null`,
   `libTracker=null`, and (reset-to-scratch) `libKind=null`, `libSort=null`, `libMatch="ALL"`. Then reuse
   the canonical path: `syncFilterUiToState(scope)` (`:246–260`) + `scope.launch { loadMore(scope,
   reset = true) }` — `reset=true` rewrites the hash to a clean `#/library` (`updateLibraryUrl()` `:856`),
   resets pagination, and re-fetches a clean grid. (This calls `loadMore` directly, not via
   `App.navigate`, so it doesn't depend on the #3 race — but landing C first makes the whole page robust.)
3. Optionally show `#lib-clear` only when at least one filter/search is active (reuse the
   `updateActiveChips` empty-check at `:383`).

## Scope
- `src/wasmJsMain/.../ui/Library.kt` — template edits (`:145–206`), removal of inline populate/wiring +
  dead functions (`:235–242`, `:313–345`, `:553–554`, `:556–715`, `:717–840`), `renderLibrary` state
  reset (`:122–127`), first-load/observer de-race (`:220–234`), new `#lib-clear` handler.
- `src/wasmJsMain/.../ui/Workbench.kt` — no logic change expected; the Apply→navigate path is correct
  once #3 is fixed.
- `design/app/library.html` — delete the info box (`:92`, CSS `:23–25`); add the `#lib-clear` chip.

## Non-goals
- No change to the **workbench** facets themselves (Studio · Network · Genre · Tag stay the four facets;
  Audio Track + tracker filters keep working through the same conditions/URL contract).
- No change to the multi-language **search** behaviour (Phase 29) — only its explainer box is removed.
- No backend/API change — the `/api/media` `conditions=…&match=…` contract (R74) is unchanged.
- No change to the search **debounce** (Phase 84) or infinite-scroll **paging** semantics (Phase 43)
  beyond resetting their state on entry.

## Acceptance
- The Library filter row shows only: search box, quick chips, **Add filter**, **Save filter as…**,
  **Clear filters** — no inline Studio/Network/Genre/Tags/Audio dropdowns, no info box.
- Studio/Network/Genre/Tags/Audio filtering still fully works via the workbench, with active-filter chips
  appearing and individually removable.
- Applying a workbench filter shows the correct results **immediately** — no refresh — whether the new
  result set is smaller or larger than the previous view, and regardless of how far the previous view was
  scrolled.
- **Clear filters** returns the page to a clean, unfiltered grid in one click, clears the chips, and
  resets the URL to `#/library` (refresh reproduces the clean state).
- A normal page load and a deep-linked filtered URL both render correct results on first paint (the
  de-raced first load).
