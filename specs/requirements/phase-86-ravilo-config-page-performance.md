# Phase 86 — Ravilo Config page: performance overhaul (FR-RC-PF1)

## Problem
The Ravilo Config admin page (`/#/ravilo`) becomes progressively slower as a layout grows. With
a realistic configuration (10–20 heroes, 10–20 channels, 10–20 rows) the page exhibits:
- Visible delay opening the page while 4 metadata fetches run in sequence.
- A UI freeze every time hero display hints re-resolve — the code fetches the **entire** media
  library page by page (one HTTP call per 100 items) to find 3–10 hero titles.
- 20+ simultaneous API calls firing the moment the Channels section renders — one per channel to
  fetch its item count — each causing a full SQLite scan + full library JSON deserialize on the
  backend.
- Noticeable lag on every workbench interaction: the filter-builder modal replaces its entire DOM
  and re-wires 50+ event listeners on every toggle, condition add, or value pick — including
  every single keystroke in the title input.
- Sluggish hero-height slider: the `input` event fires while the user drags and each event
  rebuilds the entire preview panel as a string.
- Full server round-trip and complete DOM re-render when switching between Global and User scope.

## Findings

### Finding 1 — `resolveHeroDisplayHints()` fetches the whole library (critical)
`RaviloConfig.kt:505`. When heroes lack a `displayTitle` (freshly added from an item ID), the
function fetches every item in the library in pages of 100 — `O(library size / 100)` sequential
`GET /api/media` calls — just to build a lookup map for 3–10 hero items.

```
heroes = 10, library = 3 000 items → 30 sequential API calls
heroes = 10, library = 10 000 items → 100 sequential API calls
```

A guard (`heroHintsResolving`) prevents concurrent runs but doesn't abort early once all heroes
are resolved mid-flight.

**Fix:** Resolve display hints per item using `GET /api/media/{jellyfinId}` — at most N calls
for N heroes, typically 3–10, and all can run in parallel.

### Finding 2 — N+1 per-channel count API calls (critical)
`RaviloConfig.kt:931`. After the Channels section renders, one `scope.launch` fires **per
channel** calling `countMatching()` → `GET /api/media?conditions=…&pageSize=1&match=…`.

- 20 channels = 20 HTTP requests hitting the backend simultaneously.
- Each backend request: full SQLite blob fetch → JSON deserialize for every item → in-memory
  condition filter → total count.

**Fix:** A single `POST /api/media/batch-count` endpoint accepts an array of filter descriptors
`[{match, conditions}]` and returns `[{index, total}]`. One HTTP request, one SQLite scan, one
deserialize pass — results fanned out to all channel badges.

### Finding 3 — `MediaStore.list()` with `pageSize=1` deserializes everything (critical)
`MediaStore.kt:80`. `countMatching` sets `pageSize=1` to get only the total. But `list()` still:
1. Fetches every JSON blob from SQLite.
2. Deserializes every item.
3. Filters in memory.
4. Sorts the full filtered set.
5. Then paginates (drops N, takes 1).

Steps 4–5 are pure waste when only the total count is needed. The batch endpoint in Finding 2 can
expose a count-only code path in `MediaStore` that stops after step 3: `count = filtered.size`.

### Finding 4 — `render()` in `ravilo-builders.js` rebuilds the full modal DOM + re-wires 50+ listeners on every interaction (critical)
`ravilo-builders.js:257`. The `render()` function:
1. Rebuilds the entire modal HTML (150+ lines of template string) via `node.innerHTML = …`.
2. Calls `renderConds()` — rebuilds all condition rows.
3. Calls `wire()` — re-attaches 50+ event listeners via `querySelectorAll().forEach(b => b.onclick …)`.

`render()` is called from **10+ interaction handlers**:
- Match toggle (ALL/ANY)
- Include toggle (All/Movies/Series)
- Style toggle (Logo/Text/Custom)
- Hero toggle
- Rows mode toggle
- Add condition
- Every facet picker selection
- Every operator picker selection
- Every value picker selection

Including **every keystroke in the title input** (title changes trigger `render()` via the
`oninput` handler wired in `wire()`). A user typing a 12-character title fires 12 full rebuilds.

**Fix:**
- Use a single delegated click/input listener on the modal root, dispatching on `data-*`
  attributes, instead of 50+ individually wired handlers.
- For toggle-state changes (match, include, style, hero on/off): update only the affected
  element's class/attribute — no full re-render.
- For condition-list changes (add, remove, value pick): re-render only `renderConds()`, not the
  full modal.
- Title input: update only the channel-preview watermark element in place.

### Finding 5 — `renderPreview()` on every `input` event including range slider (high)
`RaviloConfig.kt:349`. A delegated `input` listener on `#rav-sections` calls
`collectConfig(container); renderPreview(container)` when the `#hero-height` range input fires.
`renderPreview()` rebuilds the full preview panel as a string. Range `input` fires continuously
while the thumb is dragged — ~100 calls for a full-range drag.

**Fix:** For the hero-height slider, directly update the affected element's `style.height` and
label text (two targeted DOM writes). Reserve `renderPreview()` for discrete changes where the
full structure genuinely changes (row list, channel list, behaviour skin).

### Finding 6 — `loadFacets()` makes 4 sequential HTTP calls on page load (high)
`RaviloConfig.kt:129`. Networks, Studios, Genres, and Tags are fetched one after another in a
single `suspend` function building a `mapOf(…)`. Each call awaits the previous one even though
they are fully independent.

**Fix:** Either: (a) Parallelize in Kotlin — four `async {}` coroutines awaiting together
(`awaitAll`), or (b) add a single `/api/metadata/all-facets` endpoint returning all four lists
in one JSON response. Option (b) is preferred: one network round-trip instead of four, and the
backend already has all the data in one SQLite pass.

### Finding 7 — Full server re-fetch + DOM re-render on scope switch (high)
`RaviloConfig.kt:247`. Clicking "Global" or switching to a different user calls
`reloadScopeIntoSections()`, which:
1. Clears sections to "Loading…".
2. Makes a `GET /tv/admin/config` call for the new scope.
3. Calls `renderFull()` — rebuilding all 6 sections.

Switching back to a scope the user was just on triggers the same round-trip.

**Fix:** Cache `RaviloConfigMeta` objects in a client-side `HashMap<String, RaviloConfigMeta>`
keyed by userId (or `"global"`). On scope switch: serve from cache if present; re-fetch only on
explicit Save or first visit to that scope. Invalidate the cache entry on successful Save.

### Finding 8 — Hero editor overlay full rebuild on every add/delete (medium)
`RaviloConfig.kt:1456, 1461, 1489`. Adding an item, deleting an item, or completing a
drag-drop in the hero editor overlay calls `ov.innerHTML = buildHtml()` — a full rebuild of the
hero list — followed by `rewire()` to re-attach drag handlers and delete buttons.

**Fix:** Insert or remove only the single affected `<div class="cfg-row">` element via DOM
methods. Use a single delegated listener on `#hero-ov-list` for delete clicks; re-wire drag
handlers per item only when drag completes.

### Finding 9 — Per-item event listeners in loops, not delegated (medium)
`RaviloConfig.kt:663, 1687, 1789`. After `renderHeroes()`, `renderChannels()`, `renderRows()`,
and `renderDiscover()`, each item's action buttons (edit, delete, up/down, enable toggle) are
attached as individual listeners in a `for` loop. With 20 channels this means:

- 3 listeners per channel × 20 channels = 60 listeners
- Re-attached from scratch on every structural re-render

**Fix:** Replace per-item attachment loops with a single delegated listener on the list
container (`#ch-list`, `#row-list`, `#sect-heroes` etc.). Inspect `event.target.closest('[data-ch-del]')`
etc. and dispatch to the appropriate handler. Listener attached once on `renderFull()`, not
after every item change.

## Goal
The config page must feel fast at any library/layout size. Specifically:
- Opening the page or switching scope: no perceptible delay after facets load.
- Channels section: count badges populate from a **single** API call, not N.
- Hero display hints: resolve via targeted per-item lookups, not a library scan.
- Workbench modal: all interactions (toggle, value pick, add condition) update only the affected
  DOM elements; no full modal re-render or mass listener re-attachment.
- Hero-height slider: drags smoothly at 60fps; no re-render per pixel.
- Scope switch: instant from cache; network only on first visit to a scope.

## Requirements

### 1. Backend — `/api/media/batch-count` endpoint
Add `POST /api/media/batch-count` accepting:
```json
[
  { "index": 0, "match": "ALL", "conditions": [...] },
  { "index": 1, "match": "ANY", "conditions": [...] }
]
```
Returns:
```json
[{ "index": 0, "total": 142 }, { "index": 1, "total": 67 }]
```
Implementation: deserialize library once, then evaluate each condition set in-memory. Single
SQLite scan for any number of channels. `index` is echoed back so the client can map results
back to channel badge elements without relying on response order.

### 2. Backend — count-only path in `MediaStore`
Add a `count(conditions, match)` function (or a `countOnly = true` flag on `list()`) that skips
the sort and pagination steps and returns just `Int`. Used by the batch-count endpoint internally
— avoids allocating a `MediaPage` object when only the total is needed.

### 3. Backend — `/api/metadata/all-facets` endpoint
Add `GET /api/metadata/all-facets` returning:
```json
{ "networks": [...], "studios": [...], "genres": [...], "tags": [...] }
```
Single SQLite pass (same data as the four individual calls). Replace `loadFacets()` 4-call
block with this one request.

### 4. Frontend (`RaviloConfig.kt`) — Fix `resolveHeroDisplayHints()`
Replace the full paginated library fetch with N parallel `MediaApi.getById(id)` calls (one per
hero whose `displayTitle` is null). Update `currentConfig.heroes` with results and call
`renderHeroes()` once when all finish. The guard flag (`heroHintsResolving`) is still needed.

### 5. Frontend (`RaviloConfig.kt`) — Batch channel count calls
Replace the per-channel `scope.launch { countMatching(...) }` loop with a single call to the
new batch endpoint. Map results back to `#ch-count-{i}` badge elements by index.

### 6. Frontend (`RaviloConfig.kt`) — Scope config cache
Add `private val scopeConfigCache = HashMap<String, RaviloConfigMeta>()` to the
`RaviloConfigState` object. In `reloadScopeIntoSections()`:
- Serve from cache if present (no loading flash, instant render).
- Fetch from server only on cache miss.
Invalidate the cache entry for the current scope immediately before any `RaviloApi.putConfig()`
or `RaviloApi.putGlobalConfig()` call so a re-visit after save re-fetches fresh data.

### 7. Frontend (`RaviloConfig.kt`) — Incremental preview updates
Split the `renderPreview()` call-sites:
- **Hero-height slider** (`input` event): directly update `sect.querySelector("#hero-hero")?.style?.height`
  and the label `#hero-height-val`. No `collectConfig`, no `renderPreview`.
- **Discrete changes** (channel name, row title, behaviour toggle): keep `collectConfig +
  renderPreview` but ensure `renderPreview` itself uses targeted `element.textContent = ...` /
  `element.style.height = ...` DOM writes rather than `innerHTML = buildString { … }` for the
  elements that change.

### 8. Frontend (`RaviloConfig.kt`) — Event delegation for list sections
Remove the per-item `addEventListener` loops in `renderHeroes()`, `renderChannels()`,
`renderRows()`, `renderDiscover()`. Replace with a single delegated listener on each list
container attached once in `renderSections()`. Dispatch on `data-hero-del`, `data-ch-edit`,
`data-row-del`, etc.

### 9. Frontend (`RaviloConfig.kt`) — Hero editor overlay: DOM-patch single rows
In `openHeroEditorOverlay`, on add/delete/drag-end, insert or remove the single affected
`cfg-row` element rather than rebuilding `ov.innerHTML`. Attach delete handlers via delegation
on `#hero-ov-list`. Re-attach drag handlers only to the newly inserted row.

### 10. Frontend (`ravilo-builders.js`) — Event delegation + targeted DOM updates in workbench
Replace the `wire()` mass-attachment pattern:
- Attach a **single delegated** `click` listener on `node` at modal creation time.
- Toggle `data-m` / `data-inc` / `data-st` classes: update only the `.active` class on the
  toggled buttons; re-render only `renderConds()` when `state.match` changes (to update the
  AND/OR labels on the condition rows).
- Title input (`oninput`): update only the watermark preview element, not re-`render()`.
- Condition add / remove / facet / op change: call `renderConds()` only, not `render()`.
- Value pick / remove: update the affected `.cf-cond` row's value chips only.
- Call full `render()` only for structural changes that require rebuilding the entire sidebar
  (hero on/off toggle, channel style logo↔text — anything that changes the left-panel layout).

## Scope

**Backend (`src/linuxX64Main/kotlin/dev/jellystructure/`)**
- `media/MediaStore.kt` — `count(conditions, match)` helper.
- `server/routes/MediaRoutes.kt` — `POST /api/media/batch-count` handler.
- `server/routes/MetadataRoutes.kt` — `GET /api/metadata/all-facets` handler.

**Admin frontend (`src/wasmJsMain/kotlin/dev/jellystructure/`)**
- `ui/RaviloConfig.kt` — findings 4–9 above.
- `api/MediaApi.kt` — `batchCount()` client function.
- `api/MetadataApi.kt` — `getAllFacets()` client function replacing `getNetworks + getStudios + getGenres + getTags`.

**Design/JS (`design/app/`)**
- `ravilo-builders.js` — finding 10 (event delegation + targeted DOM).

## Expected improvement

| Interaction | Before | After |
|---|---|---|
| Page open (facets) | 4 sequential HTTP calls | 1 call (`/api/metadata/all-facets`) |
| Hero hints resolve | O(library/100) sequential calls | N parallel per-item calls (N = hero count, typically ≤10) |
| Channels render — count badges | N × `GET /api/media` (full library scan each) | 1 `POST /api/media/batch-count` (single library scan) |
| Workbench — match/include toggle | Full modal innerHTML + wire() | 2 class toggles, `renderConds()` |
| Workbench — title typing | Full modal innerHTML + wire() per keystroke | 1 element `.textContent = ` |
| Hero-height slider drag | `renderPreview()` per px (string rebuild) | 1 `style.height` write per px |
| Scope switch (warm) | Server fetch + full DOM re-render | Instant from cache |
| Hero editor add item | `buildHtml()` + `rewire()` (full list rebuild) | DOM insert of 1 row |

## Notes
- The batch-count endpoint uses `POST` (not `GET`) because conditions is a JSON body that may
  be large (many channels with multi-value conditions). A `GET` with a conditions query param
  works for single calls but becomes unwieldy for batches.
- The scope config cache must be invalidated **before** the save call returns successfully so
  a back-navigation to the same scope shows fresh data, not stale.
- `ravilo-builders.js` uses event delegation on the modal `node` rather than on `document` —
  scoped to the single open modal, no cross-contamination with other page elements.
- The Workbench builder's `evaluate()` function filters the local `TITLES` demo array — this is
  fast and not a bottleneck. The real library matching happens server-side.
