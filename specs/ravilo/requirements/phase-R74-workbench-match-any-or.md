# Phase R74 — "Match ANY" must OR conditions in the count + Library (FR-RV-MM1)

## Problem
In the filter workbench, switching from the default **Match ALL** to **Match ANY** makes no visible
difference — the match count (and the Library results) are identical to ALL. ANY should **OR** the
conditions together instead of AND.

## Findings (premise correction: ANY already works on the TV — it's dropped in the count + Library)
- `MatchMode { ALL, ANY }` is persisted on `ChannelConfig.match`/`conditions` and `RowConfig.match`
  (`shared/.../tv/Models.kt:14,271-276,321-322,353-354`).
- **The TV feed honors it.** `ConditionEvaluator.matches(item, match, conditions, heroIds)`
  (`tv/ConditionEvaluator.kt:16-20`) does `if (match == ANY) results.any{} else results.all{}` and is used
  for CUSTOM rows (`HomeFeedService.kt:207`) and channels (`:316`). So a channel/row built with ANY **does
  OR correctly on the TV.**
- **The count drops it.** The workbench live count `wbRefreshPreview` (`Workbench.kt:363-398`) flattens the
  condition stack into per-facet lists and calls `MediaApi.list(...)` — it **never sends `wbMatch`**, so
  ALL and ANY issue the identical request → identical number. It only flips a cosmetic `≈` prefix
  (`wbExactlyServable :332-335`, note `:389-391`). This is the surface the user sees while building.
- **The Library drops it.** Library has no match state (only per-facet `libStudios/Genres/…`
  `Library.kt:50-57`); the workbench opens with hardcoded `initialMatch = "ALL"` (`:925`) and `onApply`
  discards the match arg (`:927`); `applyWorkbenchToLibrary` (`:935-950`) flattens conditions into URL
  facet params; `loadMore` (`:813`) calls `MediaApi.list` — Path Y.
- **Root cause:** `/api/media` → `MediaStore.list` has **no `match`/`conditions` param** and AND's facets
  unconditionally (OR only *within* a facet) — `MediaStore.kt:117-140`; route `MediaRoutes.kt:146-178`;
  client `MediaApi.kt:156-192`. So the count + Library structurally cannot express ANY.
  (`saveFilterToViewer` `Library.kt:957` *does* preserve `match`, so saving a Library filter to a
  channel/row ORs correctly on the TV — only the in-page count + grid are wrong.)

## Goal
ANY ORs the conditions **consistently** across the workbench count, the Library grid, and the TV feed
(which is already correct) — using one shared evaluator.

## Requirements (route the count + Library through the existing `ConditionEvaluator`)
1. **Server — make `/api/media` condition-stack aware.** Add `conditions: List<Condition>` + `match:
   MatchMode` to `MediaStore.list`; when `conditions` is non-empty, replace the per-facet AND block
   (`MediaStore.kt:117-140`) with a single
   `result = result.filter { ConditionEvaluator.matches(it, match, conditions, heroIds) }` — the same
   evaluator the TV uses, so all three surfaces agree. (`heroIds` is already derivable from the existing
   `viewer` param.) Accept the stack on `/api/media` (POST the stack, or a JSON-encoded `conditions` query
   param — `Condition` is `@Serializable` — plus `match=ALL|ANY`). Bonus: this also makes `is_none_of` /
   `not_contains` **exact** (the flatten path can't express them today).
2. **Client `MediaApi.list`** — add `match` + `conditions` and send them (`MediaApi.kt:156`).
3. **Workbench count** (`wbRefreshPreview`) — send `wbMatch` + the raw `wbConds + wbBaseConds` instead of
   flattening; drop the `≈`/`wbExactlyServable` caveat (`:332-335,389-391`) since the count is now exact.
   (This also makes the R73 per-channel counts exact.)
4. **Library** — add a `libMatch` (+ raw `libConditions`) state, round-trip `match=ANY` + the conditions
   in the URL (`parseLibraryUrl`/`updateLibraryUrl` `:61-101`), stop hardcoding `initialMatch="ALL"`
   (`:925`), have `onApply` use the match arg (`:927`), and pass match/conditions into `loadMore`'s
   `MediaApi.list` (`:813`). Keep the legacy per-facet URL params as the ALL fast path; ANY uses the
   condition-stack path.

## Why not move the predicate to `commonMain`
`ConditionEvaluator` operates on the server `model.MediaItem` (full track lists, `TrackKind`) which isn't
in `commonMain`, and the wasm client doesn't hold the whole library locally (it pages from the server) —
so it can't compute a total client-side. Keep evaluation server-side.

## Scope
- `src/linuxX64Main/.../media/MediaStore.kt` (`list` — conditions/match via `ConditionEvaluator`) +
  `server/routes/MediaRoutes.kt` (accept the stack).
- `src/wasmJsMain/.../api/MediaApi.kt` (`list` params), `ui/Workbench.kt` (send match+conditions, drop ≈),
  `ui/Library.kt` (libMatch/libConditions state + URL round-trip + apply).
- No model change (`MatchMode`/`Condition` already exist); no TV-feed change (already correct).

## Acceptance
- Build a 2-condition filter (e.g. Genre=Drama, Studio=HBO): Match ALL shows the intersection count; Match
  ANY shows the (larger) union count — different numbers — and applying ANY to the Library returns the
  OR'd set. A channel/row built with ANY shows the same count it produces on the TV.
