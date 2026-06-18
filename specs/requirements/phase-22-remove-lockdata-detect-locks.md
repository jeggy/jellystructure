# Phase 22 — Remove lockdata & Detect Jellyfin Field Locks (FR-LK1)

**Status:** Planned

## Problem
Two related things:
1. The project originally mandated `<lockdata>true</lockdata>` in NFO files. That requirement is
   **dropped** — NFOs must stay *unlocked* so Jellyfin can re-read them whenever we (or it) want. All
   mention of lockdata should be removed from code and specs.
2. Separately, when Jellyfin itself has **locked** one or more metadata fields on an item (set by a
   user via "Edit metadata" in Jellyfin), our NFO writes are silently ignored for those fields. We
   need to detect this and surface a clear, actionable error on the Media Detail page.

## Current state (as-is)
- `<lockdata>` lines are already **commented out** in `NfoWriter.buildMovieXml`, `buildTvShowXml`,
  `buildEpisodeXml`, and the Media Detail "lockdata=true" UI block is commented out (MediaDetail.kt
  ~line 377). See [`_investigation-findings.md`](_investigation-findings.md).
- The constitution (§"NFO Files") still says "Always include `<lockdata>true</lockdata>`" — stale.
- `JellyfinItem` model does not request or carry `LockData` / `LockedFields`.
- `JellyfinClient.getItems` requests `Fields=Path,ProviderIds,ProductionYear` only.

## Requirements

### Part A — remove lockdata everywhere
1. Delete the commented-out `<lockdata>` lines in `NfoWriter` (all three builders) and the commented
   lockdata UI block in `MediaDetail.kt`.
2. Remove the `<lockdata>` mandate from the constitution and any spec mention. Replace with an
   explicit invariant: **NFOs are written unlocked; Jellystructure never sets lockdata.** (Add to
   [`../constitution.md`](../constitution.md) §"NFO Files" and Key Invariants.)
3. No NFO output should contain `<lockdata>`.

### Part B — detect Jellyfin-side field locks
4. Extend `JellyfinItem` to capture lock state from Jellyfin:
   - `@SerialName("LockData") val lockData: Boolean = false`
   - `@SerialName("LockedFields") val lockedFields: List<String> = emptyList()`
   Add `LockData` (and rely on `LockedFields` being returned) to the `Fields=` query in `getItems`,
   and/or add a single-item fetch `getItem(baseUrl, token, jellyfinId)` for on-demand checks.
5. Persist lock state on `MediaItem` so the detail page can show it without a live call:
   - Add `lockedFields: List<String> = emptyList()` and `lockData: Boolean = false` to `MediaItem`
     (commonMain), populated by the scanner from the Jellyfin item.
   - Alternatively (if avoiding a model change is preferred) provide
     `GET /api/media/{id}/jellyfin-locks` → `{ lockData: Boolean, lockedFields: [String] }` that
     fetches live from Jellyfin on demand. **Recommended: do both** — store at scan time for the list/
     badge, and offer the live endpoint for a "re-check" button. Pick one as primary in the plan.
6. A "locked" item is one where `lockData == true` or `lockedFields` is non-empty.

### Frontend — Media Detail error
7. When the current item is locked, show a prominent **error banner** at the top of the Media Detail
   page (style consistent with `nfo-perm-banner`), stating the item has an issue that must be fixed in
   Jellyfin, with these exact remediation steps:
   > This item has locked metadata fields in Jellyfin, so your changes here may be ignored. To fix it:
   > open the item in Jellyfin → **Edit metadata** → scroll to the bottom → **uncheck all locks** →
   > save.
8. The banner lists which fields are locked (from `lockedFields`) when available, and includes the
   existing "Jellyfin ↗" deep link to the item.
9. Optional: a "Re-check locks" button that calls the live endpoint and updates the banner.

## Notes
- Jellyfin field names in `LockedFields` are PascalCase (e.g. `Name`, `Overview`, `Genres`,
  `Studios`); display them readably.
- This does not attempt to *unlock* fields via the API (Jellyfin's unlock is a metadata update with
  `LockData=false`); scope is detection + guidance. Auto-unlock could be a later enhancement.
