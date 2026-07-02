# Phase 105 — Library filter row: match the design layout + drop "Save filter as…" (FR-LS3)

> Builds on **[Phase 101](phase-101-library-search-simplification.md)** (info-box removal, inline-facet
> removal, workbench-apply de-race, Clear filters). This phase is purely about **where the controls sit**
> and **removing one control that no longer belongs here** — no query/URL/paging semantics change.

## Problem
The runtime Library page (`Library.kt`) still lays the filter controls out in the pre-design order, and it
carries a **★ Save filter as…** button that no longer makes sense now that the **Ravilo config editor** owns
channel/content-row authoring. The design mockup `design/app/library.html` is the visual target and already
reflects the intended layout:

- **Search** lives in the **top pagebar**, right-aligned next to the **Movies | TV** kind toggle — not
  buried at the far left of the filter row.
- **⚙ Add filter** is the **first (leftmost)** control in the filter row, ahead of the quick chips.
- There is **no "Save filter as…"** button anywhere on the page.

## Findings

### Current runtime layout — `Library.kt`, `renderLibrary` template
Everything is crammed into one `<div class="row center">`:
```
[lib-search] filter: [f-all][f-attention][f-artwork] [lib-workbench ⚙ Add filter]
[lib-saveas ★ Save filter as…] [lib-clear] <spacer> [lib-sort] [kindseg: All|Movies|TV] [lib-total]
```
- `lib-search` is the **first** element of the filter row (design puts it in the **pagebar**).
- The pagebar itself only holds `<h1>Library</h1>` + the `scan-btn` (`▶ Scan library`).
- `lib-workbench` (⚙ Add filter) sits **after** the three quick chips (design puts it **first**).
- `lib-saveas` (★ Save filter as…) exists, wired in `wireLibraryWorkbench` via `open("Save filter as…")`.
- `kindseg` is a **three-way** `All | Movies | TV` toggle sitting on the **right** of the filter row
  (design shows a **two-way** `Movies | TV` toggle in the **pagebar**, defaulting to Movies).

### "Save filter as…" round-trip — `Library.kt`
The button drives a whole Ravilo-write path that is now redundant with the Ravilo config editor:
- `wireLibraryWorkbench`: the `lib-saveas` click → `open("Save filter as…")` with the `onSaveAs` lambda.
- `onSaveAs` → `pickViewerThen(scope)` → `saveFilterToViewer(uid, name, target, match, include, conds)`.
- Supporting: `saveFilterToViewer`, `wbToConditions`, `pickViewerThen` (the viewer-picker modal), and the
  `applyLabel`/`onSaveAs` plumbing in `openWorkbench` (`Workbench.kt`) that only this caller uses.
- The **⚙ Add filter** path (`open("Library filter")` → `onApply` → `applyWorkbenchToLibrary`) **stays** —
  only the *save-as-Ravilo* branch goes.

Design mirror already done: `design/app/library.html` has **no** `#saveas` markup, CSS, or JS.

## Goal
The Library page matches the design: search + kind toggle in the pagebar; a lean filter row that leads with
**⚙ Add filter**, then the quick chips, then Clear filters; sort at the right; and **no** Save-filter-as
control. Filtering behaviour (workbench conditions, active chips, URL, paging, Clear filters) is unchanged.

## Requirements

### A. Move search into the pagebar
1. Move `lib-search` out of the filter `row` and into the `.pagebar`, right-aligned (after a `spacer`),
   immediately **left of** the kind toggle. Keep the id, the `input`/debounce handler
   (`attachLibraryListeners`), and `libSearch` state exactly as they are — only the DOM position changes.
2. Keep the `▶ Scan library` button in the pagebar (it can sit at the far right, or stay left of search —
   match the mockup, which keeps Scan as the primary pagebar action).

### B. Add filter goes first
1. In the filter row, order the controls: **`⚙ Add filter`** → `All` → `Needs attention` →
   `Missing artwork` → (seeded-on, see D) → `✕ Clear filters` (still `display:none` until a filter is
   active) → spacer → `sort`. No handler changes — this is source-order only.

### C. Remove "Save filter as…"
1. Delete the `lib-saveas` button from the template.
2. In `wireLibraryWorkbench`, drop the `lib-saveas` listener and the `open("Save filter as…")` call; keep
   the `lib-workbench` → `open("Library filter")` path.
3. Remove the now-dead `onSaveAs` branch, `saveFilterToViewer`, `wbToConditions`, and `pickViewerThen`.
   In `Workbench.kt`/`openWorkbench`, drop the `applyLabel`/`onSaveAs` parameters if this was their only
   caller (otherwise leave them and just pass none).
4. Rationale to record in the commit: **channel / content-row authoring now lives in the Ravilo config
   editor** (per-user & global scope, R51+); pushing filters from the admin Library into a viewer's layout
   is no longer a supported flow here.

### D. Kind toggle in the pagebar (Movies | TV)
1. Move `kindseg` into the pagebar, next to search, and render it as the design shows: **Movies | TV**.
2. **Decision to confirm with the team:** the mockup shows only two segments (no standalone "All"),
   defaulting to Movies. If we keep an "all kinds" grid as the default (`libKind == null`), either (a) keep
   a subtle third "All" segment, or (b) default the page to Movies and treat Movies/TV as the only toggle.
   Pick one; the design leans (b). Whatever is chosen, the `libKind` URL param and query contract are
   unchanged.

## Scope
- `src/wasmJsMain/.../ui/Library.kt` — `renderLibrary` template re-layout (pagebar vs filter row, control
  order), removal of `lib-saveas` + `wireLibraryWorkbench` saveas branch + `saveFilterToViewer` /
  `wbToConditions` / `pickViewerThen`.
- `src/wasmJsMain/.../ui/Workbench.kt` — drop `applyLabel`/`onSaveAs` only if unused elsewhere.
- `design/app/library.html` — already matches; no further change (reference only).

## Non-goals
- No change to the **workbench** facets, the **active-filter chips**, the **Clear filters** behaviour
  (Phase 101), the **search** logic (Phase 29/84), or **infinite-scroll paging** (Phase 43).
- No change to the `/api/media` `conditions=…&match=…` contract (R74).
- The **seeded-on (tracker) filter** (Phase 98) is out of scope here. Note for a follow-up: the design
  mockup renders an inline **🌱 seeded on: <tracker>** dropdown in the filter row, whereas the runtime only
  exposes `libTracker` via URL + an active chip. If we want parity, spec it separately.

## Acceptance
- The pagebar shows **Library**, the **search** box, and the **Movies | TV** toggle (plus the Scan action);
  the filter row shows **⚙ Add filter** first, then the quick chips, then **Clear filters**, then sort.
- **No "Save filter as…"** control appears, and no viewer-picker modal can be reached from Library.
- Building/applying a workbench filter, the active-filter chips, Clear filters, search, sort, kind toggle,
  and infinite scroll all behave exactly as before.
- A deep-linked filtered URL and a plain load both render correctly on first paint.
