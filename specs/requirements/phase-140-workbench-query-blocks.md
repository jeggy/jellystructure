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
cond  : { kind: "cond", facet, op, values[] }   // unchanged from R32 (+ R87 rows[] for content_row)
```

- The **root is always a group** (`join` = the operator *between* top-level blocks) and its children are
  the top-level **blocks** (each itself a group); conditions never sit directly at the root.
- **`join`** applies between a group's children; every group picks its own AND/OR independently.
- **`not: true`** inverts the whole group ("exclude everything this block matches").
- **Empty semantics:** a condition with no values is ignored; a group with no live children is
  **neutral** (matches everything, so a fresh block doesn't zero the results) — including when the
  group has NOT set, which deliberately diverges from the design's literal `matchNode` (see
  § Empty-NOT below). A pruned/persisted query never contains empty nodes.
- **Depth cap 3** (block → sub-block → one more) — enforced in the editor only (the model and evaluator
  are depth-agnostic); deeper nesting is unreadable and unnecessary.

**Migration (one-way, on read):** legacy `{ match, conditions[] }` auto-converts —
`ALL` → one single-condition OR-block per condition under an AND root; `ANY` → one OR-block holding all
conditions. Converting flat→tree is lossless.

**Client-compat reality (backend review):** "old clients are not supported reading tree queries" is
only true for the admin frontend (ships with the backend). The **Ravilo TV app also deserializes the
full `RaviloConfig`** — `TvApiClient.getConfig()` → `GET /api/tv/config` returns the same shared DTOs,
and installed APKs ship separately. Its `Json` is `ignoreUnknownKeys = true; isLenient = true`
(`shared/…/TvApiClient.kt:27`), so the tree **must be an additive new field** (`query`) on
`ChannelConfig`/`RowConfig`, never a repurposing of `conditions`/`match` — old APKs then skip the
unknown key and keep working (they never evaluate filters; evaluation is server-side). On save the
editor writes `query` and empties the legacy `conditions` list; on read `query` wins when present.

**Serialization shape (backend review):** the `cond | group` union must be a kotlinx-serialization
**sealed interface** in `:shared` — e.g. `@Serializable @JsonClassDiscriminator("kind") sealed interface
QueryNode`, with the existing `Condition` retrofitted as `@SerialName("cond") … : QueryNode` (additive:
its standalone serialized shape is unchanged) and `@SerialName("group") ConditionGroup(join, not,
children: List<QueryNode>) : QueryNode`. That reproduces the design's `kind: "group"|"cond"` JSON
exactly and works identically across the backend's Json instances, the admin frontend, and
`TvApiClient`. Serialize `join` lowercase (`"and"`/`"or"`) via `@SerialName` on the enum values.

**Empty-NOT decision (backend review):** the design's `matchNode` literally returns `!n.not` for an
empty group — i.e. an empty block with NOT on matches *nothing* and zeroes the live count. Implement
**neutral-regardless-of-NOT** instead (an exclusion over nothing excludes nothing); persisted trees are
pruned so this only affects in-editor counts, and "a fresh block doesn't zero the results" should hold
with NOT toggled too. Mirror this back into the design JS when convenient.

## Requirements

### A. Shared model + evaluator (backend, `:shared`)
1. `:shared` gains the serialisable tree (`ConditionGroup(join, not, children)` with a
   `cond | group` node union — see § Serialization shape) next to the existing `Condition`, as a new
   `query: ConditionGroup? = null` field on **`ChannelConfig` and `RowConfig`** (heroes have no filters
   — `HeroConfig` is a picked item; the layout-side "hero" surface is already just the existing
   `hero_item` *facet*). Legacy `conditions`/`match` fields stay parseable and migrate on read
   (§ Migration). Storage note: layouts live as **opaque JSON in the `ravilo_config` DB table**
   (global row `__global__` + per-user rows) — not in `config.toml` — so no SQL migration is needed.
   **Recursion gotcha:** `Condition.rows` (the R87 `content_row` facet) embeds whole `RowConfig`s
   verbatim — migration and pruning must recurse into those embedded rows too.
2. `ConditionEvaluator` (`src/linuxX64Main/…/tv/ConditionEvaluator.kt`) evaluates the tree recursively
   (per-group short-circuit; `not` inverts; neutral-empty — incl. NOT, see § Empty-NOT). The flat path
   becomes a thin wrapper over a migrated tree — **one** evaluator, no parallel code path. Must keep
   the R86 WS-I Tier-1 `ItemFacets` precompute (facets built **once per item**, shared across the whole
   recursion). A condition with no values is **skipped** (nodeLive semantics) — note this deliberately
   replaces today's `setMatch` quirk where an empty-values `is_any_of` evaluates to *false*.
   Call sites that change signature (mechanical, not "for free"): `HomeFeedService.kt:342` (custom
   rows) + `:444` (channel matcher), `MediaStore.list` / `countBatch` / `facetsNarrowed`, and the
   internal `rowMatches` (R87) which must route embedded rows through the same tree eval.
   The pre-R32 legacy typed channel filters (`filterNetwork/-Studio/-Genre/-Tag`,
   `HomeFeedService.kt:444-448`) are **not** migrated — keep the existing fallback chain, now
   `query → conditions → typed filters`.
3. **Stale reference — corrected:** there is no backend "simple/positive-stack classification" today,
   and `Workbench.kt:332` (cited from the perf report) points at chip-wiring code. What the report
   actually meant is the admin's **lossy URL flattening** in `Library.kt` `applyWorkbenchToLibrary`
   (~`:655` — "Only the is_any_of / contains subset maps to the Library's URL filter"), which silently
   *drops* `is_none_of` conditions on apply today. This phase **deletes** that flattening (§ B.1).
   The WS-I SQL-pushdown classification remains a future concern; when WS-I lands, *simple* =
   AND-of-OR-blocks of positive membership conditions with no `not`, everything else in-memory.

### B. Transport / persistence
1. **Library — corrected picture (backend review):** today the Library **page URL** never carries
   `conditions=` at all — it carries flat per-facet params (`studios=`, `genres=`, `match=`, plus a
   special `coverage=` param holding one JSON `content_row` condition), and the *fetch*
   (`Library.kt` `loadMore` → `MediaApi.list`) re-derives a condition stack from that URL state
   (`libConditionsFromState()`, ~`:623`) and sends it as `/api/media?conditions=…&match=…` (R74).
   The workbench-apply path (`applyWorkbenchToLibrary`, ~`:655`) lossily flattens the stack into
   those URL params, silently dropping `is_none_of`. New model:
   - `/api/media` gains a `query=` parameter (JSON tree, URL-encoded — same scale as today's
     `conditions=` JSON); `conditions=`/`match=` remain accepted and migrate server-side.
   - The Library **URL** gains `query=` as the workbench's carrier; `applyWorkbenchToLibrary`'s
     flattening and `libConditionsFromState()` are retired. Legacy per-facet URL params remain
     parseable (they migrate into a tree on load) — they're also the deep-link surface used by the
     Metadata pages, dashboard issue chips, and quick chips, which keep navigating via simple params.
   - `kind`/`search`/`sort`/`filter`/`tracker` are **not** conditions and stay separate params.
   - The `coverage=` special-case param folds into `query=` (it only existed because the URL
     couldn't carry ops).
2. **Count/facet endpoints (was "reused as-is" — impossible):** `POST /api/media/batch-count`
   (channel/row badges, and now per-block badges) and R127's `POST /api/media/facets` (narrowed
   value-picker counts) both take a flat `{match, conditions}` **request body** today
   (`MediaRoutes.kt:216/:269`, `MediaStore.countBatch/facetsNarrowed`). Both bodies gain the
   optional tree (`query`) with the flat fields still accepted; no *new* endpoints.
3. **Ravilo layout:** channels and content rows in the per-user/global layout (the `ravilo_config`
   DB table) store the tree in the new `query` field (§ A.1 — heroes have no filters). Write-through:
   saving from the config editor persists pruned trees (prune + depth-cap enforced in
   `RaviloConfigService.validate()`/`save()`); reading migrates legacy shapes. R143 per-channel system
   rows keep referencing **the channel's query as a whole** — their model (`ChannelSystemRows`, scope
   strings only) is untouched.
4. The R59 coverage/catch-all handoff ("Add catch-all row" from the coverage gap) now composes the
   channel's blocks verbatim: the generated filter = the channel's top-level blocks **AND** a
   `row isNot [existing rows]` block (was: flattened conditions), carried in the Library URL's
   `query=` (replacing the `coverage=` hop).

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
   the last block leaves one empty block; empty groups dissolve). Badge mechanism (backend review):
   one `POST /api/media/batch-count` call per refresh — N blocks → N entries, each entry's query =
   (channel tree AND that block) in channel scope, else the block alone (§ B.2); don't fire N separate
   `GET /api/media` counts. The overall "N titles match" count + preview grid keep using
   `GET /api/media` with the full tree.
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
   **Bug this fixes en passant (backend review):** today's channel-scoped preview count composes the
   scope by *concatenating* condition lists — `wbConds + wbBaseConds` (`Workbench.kt` `wbRefreshPreview`,
   ~`:422`) — which is only correct under `ALL`; with `match=ANY` the channel scope gets OR'd into the
   row's own conditions and the count over-matches. The tree composition (`channelTree AND rowTree`)
   is the correct-by-construction replacement; call this out in the commit so the behavior change
   (channel-scoped ANY counts shrinking) isn't mistaken for a regression.
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

## Scope / critical files *(paths verified against the tree, 2026-07-05)*
- `:shared` — `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt`: `QueryNode`
  sealed interface + `ConditionGroup`, `Condition : QueryNode` retrofit, `query` field on
  `ChannelConfig` (~`:472`) + `RowConfig` (~`:508`), legacy migration helpers.
- Backend (`src/linuxX64Main/kotlin/dev/jellystructure/`):
  - `tv/ConditionEvaluator.kt` — recursive eval, keep the `ItemFacets` once-per-item precompute,
    tree-aware `rowMatches` (R87 embedded rows).
  - `media/MediaStore.kt` — `list` (~`:249`), `countBatch` (~`:569`), `facetsNarrowed` (~`:582`)
    signatures gain the tree.
  - `server/routes/MediaRoutes.kt` — `GET /media` `query=` param (~`:192`), `POST /media/batch-count`
    (~`:216`) + `POST /media/facets` (~`:269`) body DTOs gain the tree.
  - `tv/HomeFeedService.kt` — the two evaluator call sites (~`:342` rows, ~`:444` channel matcher with
    its `query → conditions → typed-filters` fallback chain).
  - `tv/RaviloConfigService.kt` — `validate()` (~`:143`) + `save()`: prune, depth ≤ 3, and reject a
    persisted locked-scope copy on rows.
- Admin wasmJs (`src/wasmJsMain/kotlin/dev/jellystructure/`):
  - `ui/Workbench.kt` — blocks renderer + join/NOT/sub-block wiring, locked channel block (replaces
    `wbRenderScopeBanner`), tree-composed preview counts (replaces `wbConds + wbBaseConds`).
  - `ui/Library.kt` — per-block chips; `query=` URL read/write in `updateLibraryUrl` (~`:112`) +
    `loadMore` (~`:515`); **delete** `applyWorkbenchToLibrary` (~`:655`) and
    `libConditionsFromState` (~`:623`) in favor of the tree.
  - `api/MediaApi.kt` — `list`/count call signatures gain the tree.
  - `ui/RaviloConfig.kt` — row/channel `groupSummary` subtitles, catch-all handoff, batch-count bodies.
  - Workbench CSS (`app.css` — port `.cf-block*`, `.cf-jpill`, `.cf-not*`, `.cf-lock*`,
    `.cf-body/.cf-main/.cf-side` + the ≤760px / coarse-pointer rules from the design).
- Design reference (shipped in `design/`): `app/ravilo-builders.js` (`mkGroup`/`mkCond`/`matchNode`/
  `migrateState`/`pruneState`/`groupSummary` + the block renderer — all verified present),
  `app/ravilo-builders.css`, `app/library.html`, `app/ravilo-config.html` (the seeded **Kringvarp**
  channel demos the flagship query).

## Non-goals
- **No Ravilo TV app change** — the TV renders server-pushed rows/channels; only the backend evaluator
  learns trees (constitution: frontend renders server-pushed state only).
- No drag-and-drop of conditions between blocks, no block duplication, no collapse/expand of the
  locked block (candidates for a follow-up once the model settles).
- No new facets, ops, or count *endpoints* — R127's `POST /api/media/facets` and the existing
  `POST /api/media/batch-count` are reused, but their request bodies do gain the tree field (§ B.2);
  "as-is" is not possible since both take flat `{match, conditions}` today.
- **Do not port the design's demo vocabulary.** The mockup's facet keys (`ageRating`, `audioLang`,
  `audioTitle`, `hero`, `row`), op names (`isAny`/`isNot`/`contains`/`ncontains`), and its unused
  `num` facet type + `gte`/`lte`/`eq` ops are demo-only. The wire vocabulary stays the backend's
  existing strings: facets `studio`/`network`/`genre`/`tag`/`age_rating`/`audio_language`/
  `audio_codec`/`track_title`/`hero_item`/`content_row`; ops `is_any_of`/`is_none_of` +
  `contains`/`not_contains`. There is no numeric facet in the backend; don't add one here.
- No SQL pushdown of tree queries (WS-I stays a future option; § A.3 records where the report's
  "classification" actually lived — the Library URL flattening — and that this phase deletes it).
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
  URLs still filter correctly — note those are the flat **per-facet params**
  (`studios=…&genres=…&match=ANY`, plus `coverage=`), not `conditions=` JSON, which never appeared in
  page URLs (§ B.1); `/api/media?conditions=…&match=…` API back-compat is verified separately.
- Library active-filter chips show one chip per block; removing a chip removes exactly that block.
- At 390×844 the filter modal opens as a bottom sheet with the matches panel below the query, every
  control reachable and ≥ 44px, and value popovers fully on-screen.
- An already-installed Ravilo TV APK keeps working against a layout containing `query` trees — the
  tree is additive and `TvApiClient`'s Json has `ignoreUnknownKeys = true` (§ Client-compat); cover it
  with a round-trip test deserializing a tree-bearing `RaviloConfig` through the shared client Json.
- Backend verified via real `compileKotlinLinuxX64` + admin `compileKotlinWasmJs`; evaluator unit
  tests cover join mixing, NOT, neutral-empty groups (incl. NOT-on-empty staying neutral), depth-3
  nesting, both legacy migrations, empty-values conditions being skipped, and `content_row` embedded-
  row migration. Infra note: the `linuxX64Test` source set already exists with `kotlin("test")` wired
  (`build.gradle.kts` ~`:340`) but contains **zero tests today** — these are the repo's first native
  unit tests; they run via `./gradlew linuxX64Test` (same `JAVA_HOME=~/.jdks/jbr-21.0.11` as every
  other Gradle task).
