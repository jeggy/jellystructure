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

### Fix

`openWorkbench` gains `baseConds: List<WbCond>` + `baseMatch: String` parameters. `wbRefreshPreview()`
unions base (channel) conditions with row conditions for the live count/preview query. The
workbench modal shows a read-only "Scoped to channel filter" banner above the condition builder
when `baseConds` is non-empty. Channel "Add row" / "Edit filter" calls pass the channel's
conditions as `baseConds`.

---

## Bug 2 — Adding a row only shows "Rows: N", no row list (FR-RV-R3)

### Problem

After clicking **+ Add row** and applying the workbench, the channel editor shows only a bare
counter `"Rows: 1"`. There is no list showing the row's title, its filter conditions, or
edit/delete affordances.

### Fix

Channel editor template gains `<div id="ch-rows-list">` rendering each `RowConfig` with inline
title input, filter summary, "Edit filter" button, and delete button (same `.cfg-row` pattern
as the global rows section). After any add/edit/delete, `openChannelEditorPage` re-renders the
full editor from the updated config. Edit-filter reopens workbench with existing conditions +
channel `baseConds`.

---

## Scope

Frontend-only changes to `Workbench.kt` and `RaviloConfig.kt`. No backend changes.
