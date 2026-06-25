# Phase R60 — Channel content-rows UX fixes (FR-RV-R2, FR-RV-R3)

**Status:** ✓ Done

> Fixes two UX bugs introduced in **[R59](phase-R59-per-channel-content-rows.md)** (per-channel
> content rows). No model changes; frontend-only.

---

## Bug 1 — Row workbench ignores the channel's content filter (FR-RV-R2)

### Problem

When a user opens a channel that has a **content filter** (e.g. "Genre = Action") and then
clicks **+ Add row** → the workbench opens and shows live results/counts as if there is **no
channel filter at all**. The results should be pre-scoped to items that already match the
channel filter; the row's own conditions then further narrow that set.

The channel filter is read-only context for the row workbench — the user configured it
separately on the channel's "Filter" section, and it should not be editable inside the row
workbench.

### Root cause (code)

`openWorkbench` in `src/wasmJsMain/kotlin/dev/jellystructure/ui/Workbench.kt` has no concept
of a base/scope filter. `wbRefreshPreview()` calls `MediaApi.list(...)` using only the
current row's `wbConds`, never intersecting with the channel's `conditions`/`match`.

The "Add row" call in `openChannelEditorPage` (`RaviloConfig.kt` ~line 1253) passes only the
row title and `onApply` callback — it never passes the channel's `c.conditions`/`c.match`.

### Change

**`Workbench.kt` — add `baseConds` parameter**

1. Add two new parameters to `openWorkbench`:
   ```
   baseConds: List<WbCond> = emptyList()
   baseMatch: String = "ALL"
   ```
   Store them as private `wbBaseConds`/`wbBaseMatch` module-level vars alongside the existing
   `wbConds`/`wbMatch`.

2. In `wbRefreshPreview()`, merge `wbBaseConds` into the query when building the `vals(facet)`
   lookups. The merge strategy: union the values of each facet across both `wbConds` and
   `wbBaseConds` (i.e. intersect at the API query level — each non-empty facet param is an
   AND-joined filter in `/api/media`). This is correct for the common case where the channel
   filter covers one or two facets and the row filter covers different facets.

   Concretely, replace:
   ```kotlin
   fun vals(f: String) = wbConds.filter { it.facet == f && it.op == "is_any_of" }.flatMap { it.values }.distinct()
   ```
   with:
   ```kotlin
   fun vals(f: String) = (wbConds + wbBaseConds).filter { it.facet == f && it.op == "is_any_of" }.flatMap { it.values }.distinct()
   ```
   Also apply the same union for `heroVals`, `untagged`, `trackTitle`, `audioCodec`.

3. In the workbench modal HTML, when `wbBaseConds` is non-empty, render a **read-only scope
   banner** just above the condition builder:

   ```
   ┌─ Scoped to channel filter ────────────────────────────────┐
   │  Genre: Action · Studio: A24  (match all)                 │
   │  (Results shown are already limited to items in this       │
   │   channel. Not editable here.)                             │
   └────────────────────────────────────────────────────────────┘
   ```

   Render it only when `wbBaseConds.isNotEmpty()`. Use the same `<span class="badge">` pill
   style as the filter summary chips elsewhere in the config UI. The banner should have a
   slightly muted/info-coloured background (e.g. `var(--hi-soft)` with a subtle border) so it
   reads as context, not an editable row.

**`RaviloConfig.kt` — pass channel conditions to row workbench**

In `openChannelEditorPage`, update the "Add row" `openWorkbench` call (~line 1254) to pass:
```kotlin
baseConds = if (c.conditions.isNotEmpty()) wbCondsFrom(c.conditions) else emptyList(),
baseMatch = c.match.name,
```

When an existing row is later editable (edit button, to be added in FR-RV-R3 below), apply
the same `baseConds`/`baseMatch` when re-opening the workbench for that row.

---

## Bug 2 — Adding a row only shows "Rows: 1", no row list (FR-RV-R3)

### Problem

After clicking **+ Add row** and applying the workbench, the channel editor shows only a bare
counter `"Rows: 1"`. There is no list showing the row's title, its filter conditions, or
edit/delete affordances. The user cannot see what rows they have configured, rename them,
reorder them, or delete them.

### Root cause (code)

The channel editor template in `openChannelEditorPage` (~line 1070–1073,
`RaviloConfig.kt`) renders only:
```
Rows: <b id="ch-rows-count">N</b>
<button id="ch-rows-edit">+ Add row</button>
```
There is no `#ch-rows-list` container. The `onApply` callback (line 1266–1267) updates only
`#ch-rows-count`; it never re-renders a row list.

### Change

**Channel editor template — add rows list**

Inside `<div id="ch-rows-custom-body">`, replace the bare count line and add a proper rows
list, mirroring the global `renderRows` pattern:

```
<div id="ch-rows-list" style="display:flex;flex-direction:column;gap:6px;margin-bottom:10px">
  <!-- one cfg-row per RowConfig in c.rows?.items -->
</div>
<button id="ch-rows-edit" class="btn sm ghost">+ Add row</button>
```

Each row entry in `#ch-rows-list` renders:
- Row **title** (editable inline `<input>`, same as global rows — `data-row-ch-title="$i"`)
- **Filter summary** — condition count + match mode (e.g. `"2 condition(s) · match all"`) or
  `"No filter — shows all media"` if empty; use the same `<div class="src">` style
- **Edit filter** button (`data-row-ch-edit="$i"`) — opens workbench with existing conditions
  pre-loaded plus the channel `baseConds`
- **Delete** button (`data-row-ch-del="$i"`) — removes the row

No drag-reorder in this phase (rows are shown in insertion order). Reorder can be a follow-up.

**Re-render after mutate**

Extract a `renderChannelRowsList(container: Element, idx: Int)` helper (or simply re-call
`openChannelEditorPage(container, scope, idx)`) after any of:
- Row added via workbench `onApply`
- Row title edited (on `input`/`change` event)
- Row deleted

The simplest correct approach is to re-call `openChannelEditorPage` after each mutation so
the entire editor section re-renders from the updated `currentConfig`. This avoids partial
state and is consistent with how the rest of the config editor works (e.g. `structural(...,
::renderRows)` for global rows).

**Wire edit/delete buttons**

After rendering the row list, wire:
- `[data-row-ch-edit="$i"]` → `openWorkbench(...)` pre-loaded with `initialConds =
  wbCondsFrom(row.conditions)`, `initialMatch = row.match.name`, `initialInclude = ...`,
  `baseConds = wbCondsFrom(c.conditions)`, `baseMatch = c.match.name`, `applyLabel = "Update
  row"`, `onApply` updates `list[idx].rows.items[i]` and re-renders.
- `[data-row-ch-del="$i"]` → remove item at `i` from `list[idx].rows.items`, update
  `currentConfig`, re-render.
- `[data-row-ch-title="$i"]` → on `change`, update `list[idx].rows.items[i].title`, update
  `currentConfig` (no re-render needed for just the title field).

---

## Scope

| Requirement | Component | Files |
|-------------|-----------|-------|
| FR-RV-R2 — base-scope filter in row workbench | frontend | `Workbench.kt` · `RaviloConfig.kt` |
| FR-RV-R3 — render row list + edit/delete | frontend | `RaviloConfig.kt` |

No backend changes. No model changes. No new API endpoints.

---

## Acceptance criteria

- **FR-RV-R2:** Opening "+ Add row" (or "Edit filter") inside a channel with a non-empty
  content filter shows a read-only scope banner listing the channel's filter conditions.
  The live result count/preview in the workbench reflects items matching **both** the channel
  filter AND the row's conditions.
- **FR-RV-R3:** After adding a row, the channel editor shows a list of custom rows with each
  row's title, filter summary, "Edit filter" button, and delete button.
- Edit filter on an existing row opens the workbench pre-loaded with the row's conditions and
  the channel scope banner.
- Delete removes the row from the list immediately (after re-render).
- The "Same as Home / Custom" toggle state is preserved across re-renders.
