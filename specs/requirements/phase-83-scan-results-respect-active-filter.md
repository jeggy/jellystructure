# Phase 83 — Live scan results must respect the active Library search/filter (FR-SQ2)

## Problem
On the Library page, while a **scan is running** and the user has an **active search or filter**, every
newly-scanned item is inserted into the visible results **immediately — even when it doesn't match the
current search/filter**. The filtered list gets polluted with non-matching items mid-scan.

## Findings
- The scan WebSocket handler inserts every scanned item **unconditionally**, never consulting the active
  query — `Library.kt:413-436` (`ws.onmessage` → `is JobEvent.ItemScanned -> appendItemToGrid(event.item, scope)`).
  `appendItemToGrid` (`:440-458`) matches an existing card by `data-id` and otherwise just
  `grid.appendChild`s the new card — purely by identity, never by whether it matches the view.
- The active query lives in module vars (`Library.kt:44-57`, parsed by `parseLibraryUrl` `:61-82`):
  `libSearch`, `libFilter`, `libKind`, `libStudios/Networks/Genres/Tags`, `libAudioLangs/TrackTitle/AudioCodec/UntaggedAudio`,
  `libSort`. The live-insert path reads none of them.
- Normal (non-live) results **are** filtered server-side — `loadMore` → `MediaApi.list(...)`
  (`Library.kt:799-853`) → `MediaStore.list` applies search + every filter (`MediaStore.kt:104-146`).
  The live insert bypasses that authoritative path entirely.
- During a scan, infinite-scroll auto-fetch is intentionally paused (`Library.kt:202-204`), so the live
  socket is the **only** thing feeding the grid mid-scan — which is why the pollution is so visible.

## Goal
While a scan runs, the filtered Library view stays correct — non-matching scanned items don't appear in a
filtered/searched list, but the user can still pull in genuinely-matching new items.

## Requirements (recommended approach: gate live-insert on "query active", offer a refresh)
1. Compute a `queryActive` flag from the active query vars:
   `libSearch != null || libFilter != null || libKind != null || libStudios/Networks/Genres/Tags.isNotEmpty()
   || libAudioLangs.isNotEmpty() || libTrackTitle != null || libAudioCodec != null || libUntaggedAudio`.
2. In the `ItemScanned` handler (`Library.kt:418`):
   - **No active query** → keep the current behavior (`appendItemToGrid`), so an unfiltered library still
     streams in live.
   - **Active query** → **do not blind-insert**. Increment a counter and surface a small **"N new items —
     refresh"** affordance (e.g. on the scan banner) that calls the already-correct
     `loadMore(scope, reset = true)` to re-run the server-filtered query. This keeps all filter semantics
     on the server (single source of truth) and honors the project invariant *"frontend reflects backend,
     no derived state."*
3. When the scan completes (or the user clears the query), clear the counter/affordance.

## Why not a client-side matcher (noted, deferred)
A faithful client matcher would need the search-across-`titlesByLang`, attention (`hasMultiDefaultAudio`),
and `matchesAudioFilter` logic — which currently lives **only** in `src/linuxX64Main` (`MediaStore.kt`)
and isn't visible to the wasm frontend. Reproducing it risks client/server drift (subtle pollution or
omission). If true live in-place updates under a filter are wanted later, **promote those predicates into
`commonMain`** (`model/Media.kt`) and share one implementation — a larger, separate effort.

## Scope
- `src/wasmJsMain/.../ui/Library.kt` — the `ItemScanned` handler (`:418`), `queryActive` helper, the
  refresh affordance, and clearing it on scan-complete / query-clear.

## Non-goals
- No backend change; the server already filters correctly.
- Not moving the matcher to `commonMain` now (deferred option above).

## Acceptance
- Start a scan, type a search / apply a filter on Library: newly-scanned non-matching items do **not**
  appear in the list; a "N new items — refresh" affordance appears and, when clicked, reloads the
  correctly-filtered results. With no active query, items still stream in live as today.
