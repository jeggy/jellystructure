# Phase R87 — Channel row-coverage gap via a workbench "Content row" facet (FR-RV-C5)

> Authored from the design project. Builds on the per-channel content rows of
> **[R59](phase-R59-per-channel-content-rows.md)**, the shared filter workbench
> **[R32](phase-R32-unified-filter-workbench.md)** / **[R74](phase-R74-match-any-or-conditions.md)**
> (one evaluator, ALL/ANY), the per-channel match count **[R73](phase-R73-per-channel-match-count.md)**,
> and the Library ↔ Ravilo round-trip of the unified workbench.

## Problem

A channel is a **filter** (a pool of titles); its page is built from **content rows**, each its own
filter. When a channel uses **Custom** content rows (R59), nothing guarantees the rows actually
*cover* the channel's pool. A title can match the channel filter yet match **no** row — so it's
reachable from search but **invisible while browsing that channel**: it never appears in any row on
the channel page. Today the operator has no way to see these orphaned titles short of eyeballing the
TV. The per-channel match count (R73) tells them *how many* titles a channel has, but not *which* of
them fall through the gaps between the rows.

## Goal

Make "in this channel but in none of its rows" an **ordinary workbench filter** — not a bespoke
report. The workbench gains a **`Content row` membership facet**; the channel editor surfaces the gap
inline and hands it to the **Library** as a normal, editable filter built from that facet. Because it
is just workbench conditions, it reuses one evaluator, renders with the existing chip UI, and is
tweakable like any other filter.

## Requirements

1. **`Content row` membership facet (the core of this phase).** Add a facet to the **shared workbench
   evaluator** (R32/R74) whose value is **membership in another saved filter** — i.e. a content row.
   - Operators: **is any of** / **is none of** (list semantics).
   - Each condition value is a **row filter spec** (`{ name, state }`, where `state` is a standard
     `RowConfig` condition stack). A title satisfies "is any of [rows]" iff it matches **any** listed
     row's filter; "is none of [rows]" iff it matches **none**.
   - Evaluated by the **same** per-title matcher used for every other condition (`matchesState`) — a
     content-row condition is evaluated by running its rows' own filters through the shared evaluator.
     No parallel logic.
   - The facet is **contextual**: the builder only offers it (in the facet dropdown + a value picker)
     when a **rows context** is supplied (the set of rows that can be referenced — e.g. a channel's
     own rows). Without that context the facet still **evaluates and renders** (so a handed-off filter
     works everywhere), it just isn't offered for fresh authoring.
2. **Coverage = a workbench filter.** "In channel X, not shown by any of its rows" is expressed as a
   plain **Match ALL** filter: the channel's own conditions **AND** `Content row is none of <X's
   rows>`. This is the canonical representation — there is **no** special "coverage" code path or
   stored flag; it's conditions all the way down.
3. **Inline gap panel (Custom rows only).** In the channel editor's **Content rows** section (Custom
   mode), show a **"Not shown by any row"** panel: the gap titles as poster tiles + an `N of M` badge
   (`N` uncovered of `M` in the channel pool), a **green/cleared** state when `N = 0`, recomputed
   **live** as rows or the channel filter change. It is the visual read-out of requirement 2's filter.
4. **Catch-all shortcut.** Offer **"＋ Add a catch-all row for these"** — adds a content row with **no
   conditions** (matches everything, scoped to the channel by R59), which by definition closes the
   gap. Opens through the **shared row builder**, not a bespoke editor.
5. **Library hand-off = a normal filter.** Offer **"Open these N in Library ↗"**. It carries the
   workbench filter from requirement 2 (channel conditions + the `Content row is none of …`
   condition) **plus** the rows context, and the Library applies it as an **ordinary active filter** —
   real editable condition chips ("Network is any of HBO" · "Content row is none of Sci-Fi, …"), a
   live count, **edit ⚙** (opens the shared builder with the rows context so the row condition's
   values can be changed), and **clear all**. The set is **derived live** by the shared evaluator on
   arrival — never a frozen list of IDs.
6. **No special Library mode.** The Library has **no** dedicated coverage view, chip, or exit path —
   it's the standard filter bar. The Movies/TV toggle, quick chips, search, and **Save filter as…**
   all behave exactly as for any workbench filter. (A small "from ‹channel› · Ravilo config"
   breadcrumb may hint at the filter's origin; removing the conditions removes it.)

## Data model / transport

- **No new persisted config.** The gap is **derived** from a `ChannelConfig` (its conditions) and its
  `rows.items` (R59 `RowConfig[]`). The only addition is a **facet type** in the shared evaluator.
- The `Content row` condition's values are **`RowConfig` specs** (`{ name, state }`) — the row model
  reused verbatim, embedded as condition values. **No parallel taxonomy, no IDs to resolve.**
- The Library hand-off payload is a transient `{ label, filter: <workbench state>, rows: RowConfig[] }`
  passed editor → Library (mockup: `localStorage['js-cov-filter']` + `library.html?view=coverage`;
  product: in-app navigation/query state). It is a **filter, not a baked title list**; the Library
  recomputes it.
- Server-side, the `Content row` facet is a set-membership test in the existing evaluate/`batch-count`
  path (R86): membership in another filter's result. ALL/ANY (R74) and facets (Studio · Network ·
  Genre · Tag · audio) are unchanged.

## Scope / invariants

- The gap, the inline panel, the per-channel match count (R73), and the Library all share **one**
  evaluator (R32/R74) — they can never disagree.
- Coverage is **Custom-rows only** (R59). In **Same as Home** mode the channel doesn't own its rows,
  so there's no gap panel.
- **System rows excluded.** Continue Watching / Newly Added are time-based and are **not** rows in the
  `Content row` facet; only workbench-filter rows close the gap. The panel says so.
- The `Content row` facet is a **first-class workbench condition** — same chip UI, same ALL/ANY, same
  evaluator — not a one-off. Channels never get a bespoke editor; the catch-all reuses the row builder.

## Out of scope

- Coverage for **inherited** (Same as Home) rows.
- Counting system rows (Continue / Newly Added) as coverage.
- Auto-fixing the gap beyond the catch-all row (no automatic per-title row generation).
- Nesting depth limits on the facet (a row referencing a row referencing a row, etc.) — not exercised.
- A TV-side surfacing of the gap (operator/config concern only).

## Mockup

`design/app/ravilo-builders.js` — shared workbench: `FACETS.row` (`type: 'rows'`, `contextual`),
`matchRows` + the factored `matchesState` (exported on `RaviloBuilders`), `evaluate` unchanged in
behaviour for existing facets; the facet dropdown / value picker honour `opts.rowsContext`.
`design/app/ravilo-config.html` — channel editor Content-rows **gap panel** (`rowCoverage`), catch-all
row, and **Open in Library** building the `Content row is none of …` workbench filter.
`design/app/library.html` — `?view=coverage` loads that filter as a normal active filter (with rows
context for editing); no bespoke mode. Code: `Workbench.kt` (shared evaluator + the membership facet),
the evaluate/`batch-count` path (R86), `RaviloConfig.kt` (channel editor), `Library.kt`.
