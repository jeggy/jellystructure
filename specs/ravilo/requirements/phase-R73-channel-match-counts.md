# Phase R73 — Per-channel item-match count in the Channels list (FR-RV-CC2)

## Problem
On `/#/ravilo`, the **Channels** section lists each channel but shows no indication of **how many items
match that channel's filter**. We want each channel row to display the number of available items in that
channel.

## Findings
- `renderChannels` (`RaviloConfig.kt:868-904`) renders each channel row with a logo chip, an editable
  name, Edit/Show/delete — and a summary badge that is **the number of filter *conditions***
  (`"${c.conditions.size} condition(s) · ${c.match.name}"`, `:873-885`), **not** an item count.
- `ChannelConfig` (`shared/.../tv/Models.kt:310-332`) holds the filter as `match: MatchMode` +
  `conditions: List<Condition>` (plus legacy single-typed `filter_*` fields). Converters already exist:
  `wbCondsFrom(conditions)` (`RaviloConfig.kt:82`) and `legacyToConds(c)` (`:862`) turn a channel's filter
  into the workbench `WbCond` stack.
- The R32 **live match count** already does exactly this for the workbench — `wbRefreshPreview`
  (`Workbench.kt:363-398`) maps the `WbCond` stack to `MediaApi.list(...)` args and reads `.total`.
  `MediaApi.list` (`MediaApi.kt:156-192`) returns `MediaPage(total = …)` and `MediaStore.list`
  (`MediaStore.kt:148-150`) sets `total = sorted.size` — the **full filtered count before paging** — so
  `list(filter, pageSize = 1).total` gives the channel's count while transferring one item (cheap).
- The reusable bit is currently **private inline** (`fun vals(...)` inside `wbRefreshPreview`), so it must
  be extracted to be callable per-channel.

## Goal
The Channels list on `/#/ravilo` shows a per-channel **N items** badge reflecting the channel's filter.

## Requirements
1. **Extract a reusable count helper** from `Workbench.kt:wbRefreshPreview` (`:363-398`), e.g.
   `suspend fun countMatching(match: String, include: String, conds: List<WbCond>, viewer: String?): MediaPage?`
   — the `vals()` facet mapping + `MediaApi.list(..., pageSize = 1)` call. Have `wbRefreshPreview` call it
   too (no behavior change there).
2. **Render a per-channel count badge** in `renderChannels` (`:868`): for each channel `c`, build the
   condition stack (`if (c.conditions.isNotEmpty()) wbCondsFrom(c.conditions) else legacyToConds(c)`) and,
   on `rcScope`, async-call `countMatching(c.match.name, include = "all", conds, viewer = currentUserId)`
   (use `currentUserId` so `hero_item`/viewer scope resolves, like the workbench passes `viewer`), filling
   a `data-ch-count` placeholder badge with `page.total`. The list paints immediately; each badge fills in
   when its count returns; re-render (on edit/reorder) refreshes the counts.
3. **ANY caveat (until R73's sibling R74):** the workbench count is exact for `match = ALL` + positive
   `is_any_of` conditions, and **approximate (`≈`) for `match = ANY`** or `is_none_of`/`not_contains`
   (see `wbExactlyServable` `Workbench.kt:332-335`), because `/api/media` AND's facets. Mirror that here
   (show `≈ N` for ANY) until **R74** routes counts through `ConditionEvaluator` and makes them exact —
   then drop the `≈`.

## Scope
- `src/wasmJsMain/.../ui/Workbench.kt` (extract `countMatching`).
- `src/wasmJsMain/.../ui/RaviloConfig.kt` (`renderChannels` — per-channel async count badge).
- No backend change (the `/api/media` `total` already serves the count).

## Non-goals
- Exact ANY counts — that's R74 (the count becomes exact once `/api/media` honors `MatchMode`).
- Not adding counts to content rows here (channels are the ask; rows could follow the same helper later).

## Acceptance
- The `/#/ravilo` Channels section lists every channel with a match-count badge (e.g. "128 items");
  editing a channel's filter updates its count on re-render. ANY-mode channels show `≈` until R74 lands.
