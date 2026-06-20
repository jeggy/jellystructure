# Phase R28 — Config editor fidelity (FR-RV28)

**Status:** ✓ Done (2026-06-20)

## Problem
The `/ravilo` admin page was a bare prototype — form inputs not wired to save, no drag-reorder,
no live preview, missing sections.

## What was built

Complete rebuild of the `/ravilo` config editor (`design/ravilo/ravilo-app.js` + related CSS):

- **Drag-reorder** for both channels and content rows (HTML5 drag-and-drop with ghost preview).
- **Show/hide toggles** on each channel and row card.
- **Typed filters** on channels (by provider facet) and rows (by row kind: Continue, Movies, Series,
  Channels, Custom).
- **System-row protection**: the built-in "Continue Watching" and "Newly-Added" rows cannot be
  deleted, only toggled or reordered. "Newly-Added" correctly merges Movies+Series (was: Movies only).
- **Hero height slider** (30%–100%) + **auto-advance seconds** input; live preview updates the
  schematic.
- **Tile shape selector**: Standard poster / Wide landscape / Square — sets `tileShape` on the config.
- **Live schematic preview**: mini layout diagram in the right panel updates as the user changes
  hero height, toggles rows on/off, or reorders.
- **Page chrome**: sidebar TV icon, section headers, Save/Discard buttons, unsaved-changes guard.
- Save round-trips correctly through `PUT /api/tv/admin/config` with R26 validation.
