# Phase R74 — "Match ANY" must OR conditions in the count and the Library (FR-RV-C5)

> Authored from the design project. Fixes the shared filter evaluator behind the workbench
> **[R32](phase-R32-unified-filter-workbench.md)**; the per-channel count (**[R73](phase-R73-per-channel-match-count.md)**)
> and the Library results both depend on it.

## Problem
The shared filter workbench offers a **Match ALL / Match ANY** mode on a condition stack. **ALL**
(intersection) works. **ANY** does **not**: the live match count — and the actual filtered results in
the **Library** page — appear to keep ANDing the conditions, so "Match ANY: Genre = Horror **OR**
Tag = 4k" returns only items that satisfy *both*, not *either*. The mode toggle looks like it works but
the union is never taken, so ANY is effectively broken everywhere the evaluator is used (workbench
preview, channel/row counts, Library).

## Goal
**Match ANY** truly **ORs** the conditions — an item matches if it satisfies **at least one** condition
— consistently in the live count, the per-channel match badge (R73), the result preview, and the
Library page results.

## Requirements
1. **Fix the evaluator.** In the shared condition-stack evaluator, **ANY = union** (item matches if any
   one condition matches), **ALL = intersection** (item matches only if every condition matches). The
   mode is read from the saved filter and applied identically wherever the evaluator runs.
2. **Apply everywhere the evaluator is used:**
   - the **workbench** live count + result preview (R32),
   - the **Library** page filtered results and its count,
   - the **channel / content-row** match counts (R73),
   - the TV feed (a channel/row filter saved as ANY returns the union on the TV too).
3. **Server + client agree.** The server-side filter (the `/api/media` query path and the
   `batch-count` endpoint, R86) and the client/workbench preview must compute the **same** ANY/ALL
   result — FE never diverges from BE (constitution invariant).
4. **Empty-condition + mixed cases.** ANY with a single condition == that condition; ANY with zero
   conditions matches nothing (or everything per the existing ALL-empty convention) — define one rule
   and apply it both modes-consistently.

## Invariants
- One evaluator, one definition of ALL/ANY, used by workbench + Library + channel/row counts + TV feed.
- Facets unchanged (Studio · Network · Genre · Tag · audio, R32) — this is the **combination** logic,
  not new axes.
- Frontend renders server-filtered results; the workbench preview matches the server.

## Out of scope
- New facets or per-condition operators (R32 owns the condition vocabulary).
- The count **badge** UI itself (R73) — this phase makes its number correct for ANY.

## Mockup
`design/app/ravilo-builders.js` (`RaviloBuilders.evaluate` — ALL/ANY combination) used by both
`design/app/ravilo-config.html` and `design/app/library.html`. Code: `Workbench.kt` (shared evaluator),
`Library.kt` + `/api/media` filter, `/api/media/batch-count` (R86).
