# Phase 117 — Dashboard: full triage-type breakdown + per-issue Library filters (FR-TR1)

## Goal
The Dashboard's **"Needs your attention"** section gains a complete **breakdown of every triage issue
type** jellystructure collects — each with a description and a live count (**zero-count types stay
visible** at 0) — and each row **deep-links to the Library pre-filtered to the titles carrying that
issue**. Counts are issue-instance-accurate (a series with 200 untagged episode tracks counts 200), while
the Library shows the affected **titles** (those 200 instances might be 3 series) — both numbers shown so
that's legible, not confusing.

## Current state (verified in code)
- **The server already computes the breakdown and the client throws it away.** `GET /api/triage/count`
  returns `TriageCount(untagged, mismatch, multiDefault, missingArtwork, missingFromSource, total)`
  (`TriageRoutes.kt:76,88-111`) — but the FE's own `TriageCount` mirror declares only
  `untagged/mismatch/total` (`MediaApi.kt:95`) and uses just `.total` (`Shell.kt:163-166`).
- Issue types today (`TriageRoutes.toTriageItem`, `:243-311`): **untagged tracks** (episode/track-level,
  already instance-summed across a series, `:92-100`), **cascade mismatch** (default audio ≠ resolved
  language; movies only), **multiple default audio**, **language mix** (persisted flag), **missing
  poster artwork** (on-disk, Phase 92), **missing from source** (persisted, Phase 95) — plus the
  episode-level **missing overview**, which appears in dock items but is **not counted** in
  `TriageCount` at all.
- Dashboard's section is a top-8 attention table (`Dashboard.kt:44-55,145-186`) with no type breakdown;
  the headline stat is `store.totalIssueCount()` (untagged-only SQL sum) — a *different* number than the
  triage total.
- Library filtering has exactly three server filter values — `attention`, `missing_artwork`
  (`MediaStore.list:213,224-227`) — passed as `?filter=` (`parseLibraryUrl`, `Library.kt:69-94`).
  **No per-issue-type filter exists.** (Note: the `attention` union omits `missingFromSource`.)
- Reusable per-type copy exists: `triageSubline()` (`Shell.kt:44-66`).

## Requirements

### A. One canonical count, all types
1. Extend `TriageCount` with the missing member: `missingOverview` (episode-instance count), and make
   `total` the sum of all types. Counting semantics per type (locking in today's, now documented):
   **instance-counted** — untagged (per track/episode), missingOverview (per episode);
   **title-counted** — cascadeMismatch, multiDefault, languageMix, missingArtwork, missingFromSource.
2. Fix the FE `TriageCount` mirror to carry every field (today's silent drop bug).
3. Alongside counts, the endpoint also returns per-type **affected-title counts** (`titles` per type) so
   the UI can render "200 issues · 3 titles".

### B. Dashboard breakdown section
1. At the **bottom of "Needs your attention"**, render one row per issue type — **all types, always,
   including zeros**: icon/dot, name, one-line description (reuse the `triageSubline` phrasing), and the
   count (instance count, with "· N titles" when they differ). Zero rows render muted with a ✓.
2. Each non-zero row is a link → `#/library?filter=<type>`; zero rows are inert.
3. The section header's total equals `TriageCount.total` — and the Dashboard's headline "Items needing
   attention" stat is re-pointed at the same triage total so the page stops showing two different
   numbers.
4. Design: add the breakdown block to `design/app/index.html` (design mirror to be built with this
   phase — the wf.css card/badge vocabulary covers it; no new visual system).

### C. Library per-issue filters
1. Extend the `?filter=` values (the existing quick-chip mechanism precedent) with one value per type:
   `untagged`, `cascade_mismatch`, `multi_default`, `language_mix`, `missing_artwork` (exists),
   `missing_from_source`, `missing_overview`. Server-side each is a predicate in `MediaStore.list`'s
   filter branch reusing the exact `toTriageItem` detection logic (shared helpers — the filter and the
   count must never disagree).
2. The Library doesn't grow seven new chips: the three existing quick chips stay; a deep-linked
   per-type filter renders as a **removable active chip** ("Issue: untagged tracks ✕") in the active-
   filter row, like the `?tracker=` precedent. It ANDs with search/kind/workbench conditions.
3. With a per-type filter active, the results header shows **"N titles · M issues"** (M = instance count
   within the filtered set) — the explicit answer to "dashboard said 200, I see 3 shows".
4. `attention` keeps its current union (no behaviour change to existing chips) — the breakdown rows are
   the precise navigation path.

## Scope
- Backend: `TriageRoutes` (missingOverview count + per-type title counts), `MediaStore.list` per-type
  predicates (shared with triage detection), `MediaRoutes` filter param passthrough.
- FE: `MediaApi.TriageCount` fix; `Dashboard.kt` breakdown section + stat re-point; `Library.kt` filter
  values, active chip, header counts.
- Design: `design/app/index.html` breakdown block.

## Non-goals
- No new issue types and no change to what counts as an issue.
- No workbench facet for issues (filters are URL/quick-filter level, like `tracker` — issues are
  transient states, not taxonomy; consistent with Phase 98's reasoning).
- No per-type notification hooks (webhook coverage stays as-is).

## Acceptance
- The Dashboard lists **all seven** types with counts; types at zero render (muted, ✓); the section
  total, the headline stat, and the Triage dock agree on one number.
- A series with 200 untagged episode tracks contributes 200 to the breakdown; clicking the row opens the
  Library showing the 3 affected series with a header of "3 titles · 200 issues" and a removable
  "Issue: untagged tracks" chip.
- Deep-linking `#/library?filter=missing_from_source` works cold (URL-addressable, first paint
  filtered).
- Zero-issue libraries show an all-✓ breakdown, not an empty section.
