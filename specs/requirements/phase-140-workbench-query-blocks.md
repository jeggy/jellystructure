# Phase 140 — Workbench query blocks: nestable AND/OR groups + locked channel scope (FR-WB1)

> Upgrades the shared filter Workbench (R32) from a flat condition stack to **blocks** — recursive
> AND/OR groups with per-block NOT and nestable sub-blocks — everywhere the Workbench opens: the
> **Library filter**, **Channels**, **Home content rows**, and **per-channel rows** (where the owning
> channel's query appears as a **locked, read-only first block**). Design-only for now: the interaction
> model is built and iterated in `design/app/ravilo-builders.js` / `ravilo-builders.css` +
> `design/app/library.html` / `ravilo-config.html`. Related: R127 (facet-value counts + channel-scoped
> narrowing — preserved), R143 (per-channel system rows — untouched), R59 (coverage gap / catch-all
> handoff — carried over).

## Problem

The R32 Workbench is a single flat list of conditions under one `match: ALL|ANY` toggle. That cannot
express the everyday two-clause query
**`(Tag nordic-noir OR dansk-tv OR Genre Thriller) AND Network Kringvarp`** — the operator must choose
ALL (loses the OR between tags/genre) or ANY (loses the Network gate). Operators work around it with
over-broad channels or several near-duplicate rows.

Per-channel rows have a second, subtler gap: R127 narrows facet *counts* to the channel, but nothing in
the editor **shows** that the channel's own filter constrains the row — the scoping is invisible and
the row's conditions look like the whole query.

## Query model

A filter is a **tree**, replacing `{ match, conditions[] }`:

```
group : { kind: "group", join: "and"|"or", not: Boolean, children: [ cond | group ] }
cond  : { kind: "cond", facet, op, values[] }            // unchanged from R32
```

- The **root is always a group** (`join` = the operator *between* top-level blocks) and its children are
  the top-level **blocks** (each itself a group); conditions never sit directly at the root.
- **`join`** applies between a group's children; every group picks its own AND/OR independently.
- **`not: true`** inverts the whole group ("exclude everything this block matches").
- **Empty semantics:** a condition with no values is ignored; a group with no live children is
  **neutral** (matches everything, so a fresh block doesn't zero the results — matches the design's
  `matchNode`). A pruned/persisted query never contains empty nodes.
- **Depth cap 3** (block → sub-block → one more) — enforced in the editor only (the model and evaluator
  are depth-agnostic); deeper nesting is unreadable and unnecessary.

**Migration (one-way, on read):** legacy `{ match, conditions[] }` auto-converts —
`ALL` → one single-condition OR-block per condition under an AND root; `ANY` → one OR-block holding all
conditions. Converting flat→tree is lossless; old clients are not supported reading tree queries
(admin frontend + backend ship together).

## Requirements

### A. Shared model + evaluator (backend, `:shared`)
1. `:shared` gains the serialisable tree (`ConditionGroup(join, not, children)` with a
   `cond | group` node union) next to the existing `Condition`, used by the admin DTOs and the Ravilo
   layout config alike. Legacy `conditions`/`match` fields stay parseable and migrate on read
   (§ Migration) so existing `config.toml` / per-user layouts survive untouched.
2. `ConditionEvaluator` evaluates the tree recursively (per-group short-circuit; `not` inverts;
   neutral-empty). The flat path becomes a thin wrapper over a migrated tree — **one** evaluator,
   no parallel code path. `HomeFeedService` (custom rows + channels) and the Library query pipeline
   pick this up for free.
3. The Workbench simple/positive-stack classification (`Workbench.kt:332`, used by the
   backend-performance report's WS-I SQL-pushdown idea) is updated honestly: a tree classifies as
   *simple* only if it is AND-of-OR-blocks of positive membership conditions with no `not`; anything
   else falls back to the in-memory evaluator.

### B. Transport / persistence
1. **Library:** `/api/media`'s `conditions=…&match=…` query contract (R74) gains a `query=` parameter
   carrying the JSON tree; `conditions`/`match` remain accepted (migrated server-side) for URL
   back-compat, and the Library page emits `query=` from now on. Deep-linked legacy URLs keep working.
2. **Ravilo layout:** channels, content rows, and hero-item filters in the per-user/global layout store
   the tree. Write-through: saving from the config editor persists pruned trees; reading migrates
   legacy shapes. R143 per-channel system rows keep referencing **the channel's query as a whole** —
   no change to their model.
3. The R59 coverage/catch-all handoff ("Add catch-all row" from the coverage gap) now composes the
   channel's blocks verbatim: the generated filter = the channel's top-level blocks **AND** a
   `row isNot [existing rows]` block (was: flattened conditions).

### C. Workbench editor — blocks UI (admin wasmJs, `ui/Workbench.kt`)
1. The `Match ALL|ANY` seg is **gone**. The editor renders the root's blocks as cards on a connector
   rail; between blocks sits a **join pill** (AND/OR, click to flip the root join), and inside a block
   each condition row is joined by that block's own smaller join pill (click flips that block's join).
   `＋ Add block` appends a fresh OR-block; per block: `＋ Condition` and (below the depth cap)
   `＋ Sub-block` — a new sub-block defaults to the **opposite** join of its parent, since mixing
   operators is the only reason to nest.
2. Each block header carries: a **NOT** toggle (dashed chip; active = red-tinted chip + "excludes"
   label + red-tinted block border), a **live count badge** — titles the block alone matches within the
   current scope (channel scope when applicable, else library) — and a block **✕** (remove; removing
   the last block leaves one empty block; empty groups dissolve).
3. Condition rows are unchanged from R32/R127 (facet ▸ op ▸ value chips, counts, popovers); the value
   pickers keep R127 count-narrowing, scoped by the channel when in a per-channel-row context.
4. The footer summary verbalises the tree with parentheses —
   `(Tag "nordic-noir", "dansk-tv" or Genre "Thriller") and Network "Kringvarp"` — and NOT renders as
   `not (…)`. This same summary is reused for row/channel subtitle lines in the config editor.

### D. Locked channel block (per-channel row editor)
1. When the Workbench opens for a row **inside a channel**, the channel's whole query renders as a
   **locked first block** above the row's own blocks: 🔒 `Channel` eyebrow + channel name + the
   channel's title count; its conditions/joins/NOT render greyed and read-only (no popovers, no ✕);
   footer line: *"Locked — this row can only show titles inside "<channel>". Edit the filter on the
   channel itself."* A locked **AND** pill joins it to the editable blocks. (Replaces R127's
   "Scoped to channel filter" banner as the scoping surface.)
2. The match count ("N titles **in <channel>**"), each block's count badge ("N in channel"), and the
   R127 value-picker counts are all evaluated inside the channel scope. A channel whose query is empty
   shows the locked block with "No conditions — the channel shows everything."
3. The channel-rows list in the channel editor shows each row's count **within the channel**.

### E. Library page (admin `ui/Library.kt`)
1. Active-filter chips become **one chip per top-level block** (chip text = the block's parenthesised
   summary; ✕ removes the whole block), joined by the root operator word. Edit/Clear behaviour, quick
   chips, search, tracker chip, and paging are untouched (Phase 101/105 semantics).
2. "Filter matches N titles" and the workbench-apply de-race (Phase 101) operate on the tree.

### F. Mobile / responsive (shared workbench chrome)
Desktop-first, but usable on a phone (matches the shipped design CSS):
1. **≤ 760px:** the filter + hero modals become full-width **bottom sheets** (rounded top, ≤ 94dvh);
   the "Matches now" side panel stacks **below** the query as a full-width section; hero editor's
   fixed left pane stacks likewise; rails/pills/paddings tighten.
2. **Coarse pointers:** facet/op chips, value chips, join pills, NOT and remove targets get enlarged
   padding (≥ 44px effective targets).
3. **Popovers clamp to the viewport on both axes** (value pickers near the bottom edge must not run
   off-screen).

## Scope / critical files
- `:shared` — tree model + (de)serialisation + legacy migration.
- Backend: `ConditionEvaluator` (recursive eval), `Workbench.kt:332` classification,
  `HomeFeedService.kt` (rows/channels — should be call-site-neutral), Media routes (`query=` param),
  `RaviloConfigService` validation (prune, depth ≤ 3, locked-scope not persisted on the row).
- Admin wasmJs: `ui/Workbench.kt` (blocks renderer + join/NOT/sub-block wiring + locked channel block),
  `ui/Library.kt` (per-block chips, `query=` URLs), `ui/RaviloConfig.kt` (row/channel summaries,
  catch-all handoff), workbench CSS (`app.css` — port `.cf-block*`, `.cf-jpill`, `.cf-not*`,
  `.cf-lock*`, `.cf-body/.cf-main/.cf-side` + the ≤760px / coarse-pointer rules from the design).
- Design reference (shipped in `design/`): `app/ravilo-builders.js` (`mkGroup`/`mkCond`/`matchNode`/
  `migrateState`/`pruneState`/`groupSummary` + the block renderer), `app/ravilo-builders.css`,
  `app/library.html`, `app/ravilo-config.html` (the seeded **Kringvarp** channel demos the flagship
  query).

## Non-goals
- **No Ravilo TV app change** — the TV renders server-pushed rows/channels; only the backend evaluator
  learns trees (constitution: frontend renders server-pushed state only).
- No drag-and-drop of conditions between blocks, no block duplication, no collapse/expand of the
  locked block (candidates for a follow-up once the model settles).
- No new facets, ops, or count endpoints — R127's `POST /api/media/facets` narrowing is reused as-is.
- No SQL pushdown of tree queries (WS-I stays a future option; §A.3 only keeps the classification
  honest).
- No editing the channel query from inside a row's editor (that's what the lock is for).

## Acceptance
- The flagship query builds in ≤ 6 clicks from a fresh channel filter:
  `(Tag nordic-noir OR dansk-tv OR Genre Thriller) AND Network Kringvarp`, live counts updating per
  block and in total; saving and reopening round-trips the tree exactly (pruned).
- Flipping any join pill (root or in-block) re-evaluates immediately; NOT on a block inverts its
  contribution and tints it; a sub-block evaluates with its own join inside its parent.
- Opening a row editor inside a channel shows the locked channel block (read-only, correct count),
  and all counts — match total, block badges, value-picker counts — are channel-scoped.
- A legacy saved filter (flat ALL and flat ANY, both) opens as the equivalent blocks, evaluates
  identically over the same library, and persists as a tree on next save. Legacy deep-linked Library
  URLs (`conditions=…&match=…`) still filter correctly.
- Library active-filter chips show one chip per block; removing a chip removes exactly that block.
- At 390×844 the filter modal opens as a bottom sheet with the matches panel below the query, every
  control reachable and ≥ 44px, and value popovers fully on-screen.
- Backend verified via real `compileKotlinLinuxX64` + admin `compileKotlinWasmJs`; evaluator unit
  tests cover join mixing, NOT, neutral-empty groups, depth-3 nesting, and both legacy migrations.
