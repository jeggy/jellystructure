# Phase R73 — Per-channel item-match count in the Channels list (FR-RV-C4)

> Authored from the design project. Builds on the shared filter workbench
> **[R32](phase-R32-unified-filter-workbench.md)** (live match counts) and the channel editor
> **[R36](phase-R36-channel-button-editor.md)** / **[R53](phase-R53-channel-editor-page-padding.md)**.

## Problem
In the config editor's **Channels** list, each channel row shows its name + a filter summary, but not
**how many library items actually match** that channel's filter. The match count is computable (the
workbench already shows a live count while editing a filter), but at the list level the operator can't
see, at a glance, that "Nordic Noir" matches 240 titles while "Faroese Documentaries" matches 3 — so
empty or near-empty channels (a button that opens an almost-blank page on the TV) are invisible until
the operator opens each one or checks the TV.

## Goal
Each channel row in the Channels list shows a **match count badge** — the number of library items its
filter currently matches — so empty/thin channels are obvious and the operator can prune or fix them.

## Requirements
1. **Count per channel row.** Show a compact badge on each Channels-list row (e.g. `240 titles`) =
   the number of library items matching that channel's saved conditions, using the **same evaluation**
   as the workbench live count (R32) and the same facets (Studio · Network · Genre · Tag · audio).
2. **Honour Match ALL / ANY.** The count must respect the channel's match mode — and **"Match ANY"
   ORs** its conditions (see **[R74](phase-R74-match-any-or-conditions.md)**, which fixes the shared
   evaluator this badge depends on). ALL = intersection, ANY = union.
3. **Flag empty channels.** A channel matching **0** items is visually flagged (warning tint / "0
   titles") so the operator sees a dead channel without opening it.
4. **Batch + live.** Compute counts efficiently for the whole list in one pass (reuse the R86
   `batch-count` endpoint rather than N scans), and refresh a row's count when its filter is edited or
   the library changes — no per-row full-library scan.

## Invariants
- The list badge and the in-editor live count use **one** evaluator (R32/R74) — they can never disagree.
- Server-computed counts; the editor renders them, it doesn't recompute client-side from a parallel
  taxonomy.

## Out of scope
- Showing the matched *items* in the list (that's opening the channel) — count only.
- The Library page count (covered alongside R74).
- Per-row preview thumbnails.

## Mockup
`design/app/ravilo-config.html` (Channels list rows — add the count badge) and
`design/app/ravilo-builders.js` (`RaviloBuilders.evaluate` / match count). Code: `RaviloConfig.kt`
(channels list), the `/api/media/batch-count` endpoint (R86), `Workbench.kt` (shared evaluator).
