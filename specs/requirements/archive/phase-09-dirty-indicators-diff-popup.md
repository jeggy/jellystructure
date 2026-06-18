# Phase 9 — Per-Field Dirty Indicators + Diff Popup (FR-D2)

**Status:** ✓ Done

## Problem
On the Media Detail page, when the user edits metadata fields the only feedback is a "Save changes"
button that appears at the top of the card. There is no indication of *which* fields changed, and no
way to see the old vs new value before committing.

## Current state (as-is at time of spec)
- `checkDirty()` in `MediaDetail.kt`: compares current DOM values to `origValues` map (populated at render time from the server `MediaItem`). If any differ → shows `#save-metadata-btn`; if none differ → hides it.
- No per-field CSS change.
- Tracked fields: `edit-title`, `edit-year`, `edit-original-title`, `edit-overview`, `edit-director`, `edit-studio`.
- Tags are managed separately (add/remove chips); they trigger `saveBtn.style.display = "inline-flex"` directly but are not in `origValues`.
- "Save changes" → `PATCH /api/media/{id}/metadata` → server updates in-memory store.
- "Save → disk" / "Save & tell Jellyfin" → `POST /api/media/{id}/nfo` — write NFO, independent of in-memory store state.

## Requirements

### Per-field dirty indicator
1. When a field's current DOM value differs from its original server value, that `<input>`/`<textarea>` gets CSS class `field-dirty`. When it matches again, the class is removed.
2. The `field-dirty` class applies: orange/amber left border (3 px, `var(--warn)`) + a subtle background tint (`var(--warn)` at ~6% opacity). Must work in both light and dark themes.
3. `checkDirty()` is extended to apply/remove `field-dirty` per element, in addition to showing/hiding the save button.
4. Tags: when the current tag set differs from the original (by membership, order-insensitive), the tags container (`#tags-section`) gets `field-dirty` styling on its border/wrapper — not on individual chips. Added tags shown with a subtle green tint chip; removed tags not re-rendered but dirty state still captured.
5. The dirty indicator clears automatically when the page re-renders after a successful save.

### Diff popup
1. Each dirty field shows a small diff trigger inline — a compact icon button (e.g. `⟷` or `≠`) to the right of the field label, visible only when the field is dirty.
2. Clicking the trigger opens a modal overlay (centred, max-width ~560 px, darkened backdrop) showing:
   - Header: field label + "Changes"
   - Two labelled sections: **Before** (original server value) and **After** (current edited value)
   - Word-level diff highlighting: removed words/substrings in red with strikethrough, added in green — GitHub inline-diff convention.
3. For numeric fields (year), show raw before/after values without word diff.
4. For tags: **Before** as a row of chips in red, **After** as a row of chips in green, chips that exist in both shown neutral.
5. The modal is dismissed by: clicking the backdrop, pressing Escape, or clicking a close button (×).
6. Only one diff popup can be open at a time. Opening a second closes the first.
7. No backend changes. The diff is computed entirely in the frontend from `origValues` vs current DOM.

## Scope
- Applies only to the metadata editing section of the Media Detail overview tab.
- Episode metadata editing (title/overview per episode) is out of scope for this phase.
- The "Save → disk" buttons are not affected; they operate independently of in-memory dirty state.
