# Phase R71 — Merge-Newly-Added: clearer 1-vs-2 Newly-Added rows control (FR-RV-RD2)

> Authored from the design project. Refines the `mergeNewlyAdded` control left in place by
> **[R54](phase-R54-rows-config-simplification.md)** and **[R61](phase-R61-home-rows-reduce-defaults.md)**
> (which reduced the default system rows to **Continue** + a single **Newly Added**).

## Problem
After R61 the default home ships **one** merged "Newly Added" row (all media), but the
`mergeNewlyAdded` toggle still exists for operators who prefer **two** rows (Movies — Newly Added /
Series — Newly Added). The current control is opaque: a bare toggle whose label doesn't make the
**1-row vs 2-row** outcome obvious, and it isn't clear how it interacts with the now-single system
"Newly Added" row. Operators can't tell, from the editor, whether they'll get one combined row or a
movie/series split.

## Goal
Make the choice explicit and legible: a clear **one merged row** ↔ **two split rows** control for
Newly Added, with copy + a mini preview that shows exactly which rows the viewer will get.

## Requirements
1. **Explicit 1-vs-2 control.** Replace the bare toggle with a labelled two-option control
   (e.g. a segment: **"One row · Newly Added"** / **"Two rows · Movies + Series"**), wired to
   `mergeNewlyAdded`. The current value is unambiguous at a glance.
2. **Reflect in the rows list + preview.** When set to two rows, the rows list / live preview shows the
   **Movies — Newly Added** and **Series — Newly Added** rows; when one, a single **Newly Added** row.
   The system-row protection (can't delete, toggle only) still applies to whichever form is active.
3. **Short explainer.** One line of helper copy stating that this only affects the Newly-Added
   system row(s); custom workbench rows are unaffected.
4. **Migration-safe.** Honours existing stored configs (R61): toggling does not delete any custom rows;
   it only switches the Newly-Added system row between merged and split forms at render time.

## Invariants
- `mergeNewlyAdded` stays the single backing flag (kept by R54/R61 intentionally) — this phase only
  clarifies its UI and preview, no new model field.
- System rows remain enable/disable-only; everything below stays workbench rows.

## Out of scope
- Removing `mergeNewlyAdded` (explicitly kept by R54/R61).
- Continue Watching, custom rows, or per-channel rows (R59).

## Mockup
`design/app/ravilo-config.html` (Content rows section — the merge control + rows list) and the Live
preview panel. Code: `RaviloConfig.kt` (rows editor), `RaviloConfigService.kt` (`mergeNewlyAdded`,
`DEFAULT_ROWS`), `HomeFeedService` (merge-at-render).
