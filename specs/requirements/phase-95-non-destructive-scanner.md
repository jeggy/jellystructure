# Phase 95 — Non-destructive scanner: vanished items become a triage issue, never a deletion (FR-SC2)

## Problem (data-loss incident)

`runScan` ended with `store.deleteMissing(allItems.map { it.id })`, where `allItems` is only the items the
run **processed**. A full manual scan processes everything, so that was fine — but a **freshness-filtered
scheduled scan** only re-checks a *stale subset* (Phase 91), so `deleteMissing` pruned **every item it
didn't re-check**. Phase **93b** made the scheduler actually fire (it never had), and the first scheduled
run wiped the live catalog from **~323 items to 6** (only the freshness-due ones survived) — no hero, almost
no content in Ravilo.

## Rule (from the user)

> The scanner shouldn't be removing stuff — only adding new or updating existing. If something is gone, it
> should be surfaced as a **triage issue**, not silently deleted.

## Change

- **Never delete.** `runScan` drops `deleteMissing`. On a **full** scan it instead calls
  `MediaStore.flagMissingFromSource(presentJellyfinIds, now)`: any catalog item whose Jellyfin id is no
  longer returned is **flagged** `missingFromSource = true` (+ `missingSince`); items that re-appear have the
  flag cleared. Local-only items (no `jellyfinId`) are untouched. A **per-library** scan skips this entirely
  (it can't tell a removal from an item that lives in another library).
- **Surfaced as a triage issue.** Each newly-missing item logs an **Activity warning** ("… no longer in
  Jellyfin — kept and flagged for triage (the scanner never deletes)"), and `TriageCount` / `TriageItem`
  gain `missingFromSource` so the items show in the **Triage dock** and its count. Removal stays a manual,
  deliberate admin action — never a scan side-effect.
- **`MediaItem.missingFromSource` / `missingSince`** (serialized; default false/null).
- **`SCAN_ON_START=1`** env ops hook (`Main.kt`): a full items-only scan on startup, to rebuild the catalog
  after an incident (used to restore the 319 items lost here).

## Invariants

- A scan's outcome on the catalog is **add ∪ update ∪ flag** — never delete.
- "Missing" is judged against **all** Jellyfin-returned ids, independent of the freshness/working-set
  filter (which only governs *re-processing*).

## Files

- `model/Media.kt` (`missingFromSource`, `missingSince`), `media/MediaStore.kt` (`flagMissingFromSource`;
  `deleteMissing` retained but no longer called by the scanner), `server/routes/MediaRoutes.kt` (`runScan`
  prune → flag), `server/routes/TriageRoutes.kt` (count + item), `Main.kt` (`SCAN_ON_START`).
- Frontend: the Triage dock shows the "missing from library" category.
