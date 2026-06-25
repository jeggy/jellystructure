# Phase R54 — Rows config simplification: workbench-only custom rows

## Problem

The Content Rows section in the Ravilo Config editor is confusing in two ways:

1. **Adding a row presents a kind picker** that includes Continue and Newly Added, even though
   those are already visible as fixed system rows above. The operator doesn't need to add more
   of them, and seeing them in a dropdown implies they can be duplicated.

2. **Genre is a redundant kind.** Genre rows in the picker were meant as a shortcut (filter by
   genre name parsed from the row title), but any Genre row could be replicated — and improved —
   by a Custom workbench-filter row. Having two different ways to do the same thing (Genre vs
   Custom) makes the UI harder to understand, not easier.

   Additionally, there was a separate "Build with workbench" button next to "Add row",
   which split the add-row flow into two paths that both produce rows but feel different.

## Goal

Make the rows section match the design spec: **two clearly distinct groups** — fixed system rows
at the top and operator-configured workbench-filter rows below — with one consistent add path.

## Change

### System rows (CONTINUE, NEWLY_ADDED)

- Rendered at the top of the row list with their existing R66 badges.
- **No delete button.** The operator can toggle them on/off and rename them, but not remove them.
- **No kind/media selector.** Their type is fixed.
- Still draggable for reordering.

### Custom rows (all non-system rows)

- Always show an **"Edit filter"** button that opens the workbench modal.
- Show a conditions summary badge if conditions are configured, or a muted
  `"No filter — shows all media"` note when not yet configured.
- **No kind selector.** New rows are always created as `RowKind.CUSTOM` with conditions.
- Delete button present (as before).
- Existing `RowKind.GENRE` rows (from old stored configs) display identically and migrate to
  `RowKind.CUSTOM` the first time the operator edits their filter via the workbench.

### "Add row" button

- Replaced the two-button pair (`+ Add row` + `⚙ Build with workbench`) with a **single
  `+ Add row`** that opens the workbench directly.
- On apply: creates a `RowKind.CUSTOM` row with the workbench conditions.

### Default rows

`DEFAULT_ROWS` in `RaviloConfigService` trimmed to the 3 system rows only:

```
Continue Watching      (CONTINUE)
Movies — Newly Added   (NEWLY_ADDED, mediaKind = MOVIE)
Series — Newly Added   (NEWLY_ADDED, mediaKind = SERIES)
```

The four genre preset rows (Drama / Action & Adventure / Sci-Fi & Fantasy / Comedy) are removed
from the default. Operators who want genre rows can build them via the workbench.

## Scope

- Admin UI only (`RaviloConfig.kt`): `renderRows`, `collectConfig` rows section, event listeners.
- Backend defaults (`RaviloConfigService.kt`): `DEFAULT_ROWS`.
- `RowKind.GENRE` enum value and `buildRows` GENRE handler kept for backward compat.
- No API changes, no TV app changes.

## Non-goals

- Removing `RowKind.GENRE` from the data model (kept for stored-config compat).
- A bulk migration of existing GENRE rows to CUSTOM (lazy migration on edit is sufficient).
