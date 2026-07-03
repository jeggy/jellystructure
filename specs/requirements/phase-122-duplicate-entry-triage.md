# Phase 122 — Triage: detect (and prevent) duplicate library entries sharing one Jellyfin id (FR-TR3)

## Goal
When two library rows share the same Jellyfin id — so both cards open the exact same detail page —
flag them as an issue with a full overview, and stop new duplicates from accumulating. The user
regularly sees the same title twice in the Library.

> Shares the triage subsystem with **Phase 121** (`TriageDetection` / `TriageRoutes` / `MediaStore.list`
> / `Library.kt`); the two can be implemented together, but are specced separately as they were
> reported separately.

## Current state (verified in code)
- **Storage permits it.** The `media` table PRIMARY KEY is the slug `id`; `jellyfinId` lives inside the
  JSON blob with **no unique constraint**; `getAll` has no `ORDER BY` (`Media.sq`). `list()` iterates
  `allItems()` with no jellyfinId dedup → both rows render; `Library.kt:607` gives both the same
  `data-id` → `:577` both navigate to the same detail (`resolve → resolveByJellyfinId`).
- **Existing dedup is write-triggered + single-target.** `addOrUpdate` (`MediaStore.kt:426-444`)
  deletes only the one row `resolveByJellyfinId` returns; the id-index is **last-wins**
  (`associateBy { it.jellyfinId }` keeps the last row per key, `:346-352`), so it can see/delete at most
  one twin.
- **How twins persist:** (a) **concurrency** — `runScan` fans out ≤32 unlocked workers
  (`MediaRoutes.kt:1749-1796`) each calling `addOrUpdate` (an unsynchronized read→delete→upsert over
  shared caches; `allItemsMutex` only guards the local list), and the realtime webhook
  (`RealtimeIngestService.kt:102`) can run during a scan. When a slug changes across scans (title/year
  drift via `slugify`, `Scanner.kt:809-824`) and a second path touches the same jellyfinId concurrently,
  the dedup lookup misses the old row and both land. (b) **non-convergence** — last-wins + undefined
  `getAll` order means even a full rescan can keep the wrong twin alive every time. (c) **legacy rows**
  predating slug-stability (Phase 53-B). Null/blank jellyfinId is correctly excluded (`:434`, `:349`);
  episodes are nested (not rows), so grouping **top-level** items is right.

## Requirements

### A. Prevention — converge to zero
1. In `addOrUpdate`, replace the single-victim `resolveByJellyfinId` deletion with **delete ALL rows
   where `jellyfinId == item.jellyfinId && id != item.id`** (an O(n) `allItems().filter`) — turning
   last-wins into delete-all-twins so one rescan converges and the count trends to zero.
2. The thorough concurrency fix is a store-level `Mutex` around the resolve→delete→upsert (the store has
   none today). Recommended but may be a follow-up; delete-all-twins + detection cover the reported
   symptom.

### B. New triage type "duplicate"
1. `TriageDetection`: add a whole-library helper (a **relationship**, not a per-item predicate)
   `duplicateIds(items): Set<String>` = group items by non-blank `jellyfinId`, keep groups with size
   > 1, flatten to member `id`s.
2. `TriageRoutes /count`: compute
   `dupGroups = all.filter { !it.jellyfinId.isNullOrBlank() }.groupBy { it.jellyfinId }.filterValues { it.size > 1 }`;
   add `TriageTypeCount("duplicate", "Duplicate library entries", "The same Jellyfin item appears more
   than once — both open the same detail page; re-scan or remove the extra entry.",
   instances = dupGroups.values.sumOf { it.size }, titles = dupGroups.size)`. `total` auto-sums.
   Memoized by `libraryVersion` (existing `triageCountCache`), O(n) over the ~307 cached items.
3. `MediaStore.list`: add `"duplicate" -> items.filter { it.id in dupIds }` with
   `dupIds = TriageDetection.duplicateIds(base)` **hoisted out of the per-item lambda** (base = the
   `allItems()`/`liveItems()` already in scope in `list()`).
4. `Library.kt`: one line — `"duplicate" to "Duplicate entries"` in `ISSUE_FILTER_LABELS`. This
   auto-wires the removable "Issue: …" chip, the sync branch, and the deep-link count. **Dashboard
   unchanged** (its breakdown loops `count.types` and navigates `/library?filter=$key` generically);
   `MediaApi.TriageTypeCount` is already generic.

### C. Count definition (UX)
Use `instances` = total member rows (e.g. 5) and `titles` = number of distinct duplicated sets
(e.g. 2) → renders "5 (2 titles)". Preserves the framework's `instances ≥ titles` invariant. The
Library `?filter=duplicate` shows all 5 members so the admin can pick which to keep.

## Scope
- Backend: `MediaStore.addOrUpdate` (prevention), `TriageDetection` (helper), `TriageRoutes` (count),
  `MediaStore.list` (filter). FE: `Library.kt` (one line). No migration.
- Optional secondary surface: a `duplicate` flag on GET `/triage`'s `toTriageItem` so the floating
  Triage dock steps through members too.

## Non-goals
- No **auto-delete** of the extra row — the admin decides which to keep (removing the wrong slug could
  orphan curated state).
- No unique DB constraint / schema migration this phase.
- The full store-`Mutex` concurrency hardening is an optional follow-up (delete-all-twins is enough for
  the reported symptom).

## Acceptance
- With two rows sharing a jellyfinId, the dashboard shows a "Duplicate library entries" row
  ("N (M titles)"); `#/library?filter=duplicate` lists all members with a removable "Issue: Duplicate
  entries" chip.
- After the delete-all-twins prevention, a single full rescan collapses each set to one row and the
  count trends to zero.
- Titles with distinct jellyfinIds (or null jellyfinId) never appear in the duplicate set.
