# Phase R66 — Rows config clarity: typed vs merged Newly Added (FR-RV-R1)

**Status:** ✓ Done

## Problem

The Ravilo Config editor's **Rows** section ships with two `NEWLY_ADDED` rows — "Newly Added
Movies" and "Newly Added Series" — plus a global **"Merge newly added"** toggle. Three things
make this confusing:

1. **No row-type badge.** All rows look the same in the list. There is no indication that a
   row is movies-only, series-only, or mixed. Users don't know why they see two rows with
   "Newly Added" in the title or how they differ.

2. **The merge toggle has no context.** The toggle label alone ("Merge newly added") doesn't
   explain what it merges, what the result looks like, or what happens to the typed rows when
   it is enabled.

3. **No visual feedback when merging is on.** With `mergeNewlyAdded = true`, the two typed rows
   are silently suppressed by the backend and replaced with a single combined row. The admin UI
   still shows both typed rows as active, making it look like there are three "Newly Added"
   rows — or confusing operators who wonder why the TV shows only one.

## Goal

Make the distinction between **typed rows** (scoped to a single media kind) and the **merged
row** (all media kinds, date-sorted together) immediately obvious in the admin UI, with no
tooltip or documentation required.

## Change

### 1 — Row-type badge on every row card

Each row in the Rows list gains a small **type badge** next to its title:

| Row kind | Badge |
|----------|-------|
| `NEWLY_ADDED` with `mediaKind = "MOVIE"` | `Movies only` |
| `NEWLY_ADDED` with `mediaKind = "SERIES"` | `Series only` |
| `NEWLY_ADDED` with no `mediaKind` | `All media` |
| `CONTINUE` | `Continue watching` |
| `GENRE` | `Genre` |
| `CUSTOM` | `Custom filter` |

Badges use the existing `.tag` / `.tiny` class styling. `NEWLY_ADDED` badges use `--warn`
tint (amber) to visually group them together regardless of their mediaKind.

### 2 — Merge toggle: inline explainer + live state label

The **"Merge newly added"** toggle (currently a bare checkbox + label) becomes:

```
[ ] Merge newly added
    Show movies and series in one combined row instead of separate typed rows.
    When on, the two typed rows below are replaced by a single "Newly Added" row
    on the TV — sorted by date, all media kinds mixed together.
```

The toggle label itself also gets a dynamic suffix indicating current state:
- **Off:** "Merge newly added — showing separately"
- **On:** "Merge newly added — combined into one row ✓"

### 3 — Typed rows dimmed when merge is on

When `mergeNewlyAdded = true`:
- Each `NEWLY_ADDED` typed row card is **dimmed** (opacity ~0.45) and shows a
  **"Merged — not shown separately"** inline note.
- The row's enable/disable toggle and edit controls are still accessible (they are preserved
  in config for when merge is turned off again) but the card's visual state makes clear the
  row is currently inactive on the TV.
- A collapsed **"Merged row preview"** chip appears at the top of the Rows section showing the
  effective combined row title (default: "Newly Added") and an `All media` badge.

When `mergeNewlyAdded = false`, the rows return to full opacity with no note.

### 4 — Default row titles

The default `NEWLY_ADDED` row titles are renamed in `DEFAULT_ROWS` to be unambiguous:

| Old title | New title |
|-----------|-----------|
| Newly Added Movies | Movies — Newly Added |
| Newly Added Series | Series — Newly Added |

This makes the type apparent from the title alone, independent of the badge.

## Scope

- **Admin UI only** (`RaviloConfig.kt`, `wf.css`/`app.css` badge styling if needed).
- **Default config** title strings in `RaviloConfigService.kt`.
- No API changes, no TV-app changes.

## Non-goals

- Allowing the operator to configure which media kinds the merged row includes (always all).
- A third "Newly Added" row type that mixes movies + series without the merge toggle.
- Reordering the typed rows independently of the merge toggle.
