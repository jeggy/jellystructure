# Phase R54 — Admin drag-and-drop reorder

**Status:** ✓ Done

> UX improvement to the admin config editor (`RaviloConfig.kt`). TV behaviour is unchanged.
> Supersedes the "drag-reorder" note in **[R28](phase-R28-config-editor-fidelity.md)** — in
> practice R28 shipped up/down buttons; this phase replaces them with real drag-and-drop.

## Problem

Reordering channels, content rows, and discover lists in the admin used **↑/↓ buttons**. With more
than a handful of items this was tedious: moving an item from position 8 to position 2 required six
button presses. Drag-and-drop is the standard affordance for list reordering and was already called
out in R28 but never implemented there.

## Change

HTML5 drag-and-drop reordering replaces the button pair on all three reorderable lists in
`RaviloConfig.kt`:

- **Channel list** — the list of configured channels/collections.
- **Content-row list** — the content rows inside a channel editor page.
- **Discover list** — the per-user Top 10 discovery lists (R50).

### Implementation

A shared `wireDragReorder(listSelector, prefix, onReorder)` helper manages the common logic:

- Each row renders a **drag handle** (⠿) as its first element; the row has `draggable="true"` and
  `data-<prefix>-i="<index>"` to identify its position.
- **`dragstart`** records the source index.
- **`dragover`** computes whether the pointer is in the top or bottom half of the target row and
  adds `.drop-before` or `.drop-after` CSS classes for the drop indicator; prevents default to
  allow the drop.
- **`drop`** splices the item array at the new position and calls `onReorder` with the updated
  order; the view is re-rendered.
- **`dragend`** clears all `.dragging`/`.drop-before`/`.drop-after` classes.

### Visual feedback

- The dragged row gets `.dragging` (reduced opacity, grab cursor).
- Adjacent rows show a `.drop-before` or `.drop-after` line indicating the insertion point.

## Scope / invariants

- Admin-only change; no TV code is modified.
- Reorder still writes through the standard `RaviloConfig` save path (R04/R26) and live-pushes
  via R33 like any other config edit.
- The ↑/↓ buttons are fully removed; the drag handle is the only reorder affordance.
