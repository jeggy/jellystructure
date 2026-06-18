# Phase 27 — Merge Triage into Media Detail + floating Triage dock (FR-MT1)

**Status:** Planned

## Problem
There are two places that edit the same media: the dedicated **triage pages** and the **media detail
page**. The triage pages (`/triage/series/{id}` and the global `/triage` queue) carry the rich editing
— assign track languages, fix default flags, fetch stills, edit titles/overviews, save → NFO, save &
tell Jellyfin — while the media detail "Seasons & episodes" tab is a *read-only* mirror that links back
to triage ("Fix all in triage →"). This split means the same fix can be started from two routes, the UIs
have drifted, and the user has to leave the item they're looking at to actually change anything.

**Goal:** the **media detail page is the single editing surface** for both movies and series. Triage
stops being a page and becomes a small **floating dock** (modelled on the existing scan dock) that just
*navigates*: it shows how many items need attention and steps Next/Previous through them, opening each
one's media detail page where the fixing happens.

## Current state (as-is)

### Editing today lives in triage
- **`SeriesTriage.kt` (`/triage/series/{id}`)** — full per-series editor:
  - **Series step:** title / year / overview / genres / network fields; "Fetch all" series artwork.
  - **Episode step (per episode):** still + "Fetch still"; episode title + overview; "Re-pull from
    TMDB"; **track manager** — per-track language assignment (quick langs `fo/da/en/is` + picker),
    default-flag cascade fix, remove track; "Save episode".
  - Page-level: "Save → disk" (NFO), "Save & tell Jellyfin ↻", "Diagnose" (write-access check).
  - Left rail lists episodes with issue dots; "All / Issues (N)" filter; jumps to first issue on open.
- **`Triage.kt` (`/triage`)** — global attention queue: focus-mode with keyboard nav (↑/↓/←/→),
  table view, **both movie and series items**, bulk assign, TMDB suggestions
  (`/api/triage/{id}/suggest`) with "accept suggestion".
- Backend `TriageRoutes.kt`: `GET /api/triage` (list of `TriageItem`), `GET /api/triage/count`,
  `GET /api/triage/{id}/suggest`, `POST /api/triage/{id}/tracks/{specifier}/language`,
  `POST /api/triage/{id}/episodes/{epFilename}/tracks/{specifier}/language`. Default-flag cascade uses
  `POST /api/media/{id}/tracks/default` (a media route, already shared).

### Media detail today
- **`MediaDetail.kt`** tabs: Overview · (TV) Seasons & episodes / (movie) Tracks & order · Artwork ·
  NFO raw · History. Tab switching is in-memory only (see Phase 28).
- **Seasons & episodes tab** (`buildEpisodesTab`/`buildEpisodeRow`) is **read-only**: track chips,
  expandable per-episode track table, "Upload still", links to `/track-order` and a
  `#/triage` "Fix all in triage →" button.
- **Movie Tracks & order tab** uses `buildTracksTable` (read-only) + an "Open track editor ↗" link to
  the separate `TrackOrder.kt` page (`/track-order?id=…&ep=…`). That track-editor page already edits
  order/default/language for both movies and episodes and **stays as-is** in this phase.
- Inline metadata editing (title/year/overview/etc. with dirty indicators + diff popups, Phase 9)
  already exists on media detail's Overview tab.

### The dock pattern to reuse
- `Shell.kt` injects `#ambient-dock` (`injectDock`), a fixed-position, collapsible floating widget that
  shows live scan progress over a WebSocket (`connectDockSocket`), with show/hide (`showDock`/
  `hideDock`) and a collapse toggle. It persists across route changes (it lives on `document.body`, not
  in `#page-content`). The triage dock should look and behave like a sibling of this.
- Sidebar already has a Triage nav link with a live count badge (`#triage-count-badge`, fed by
  `MediaApi.getTriageCount()`), plus a "N to triage" status line (`updateSidebarStatus`).

## Requirements

### A. Media detail becomes the single editing surface
1. The **Seasons & episodes tab** gains all per-episode editing currently in `SeriesTriage.kt`:
   - Per-track **language assignment** (reuse the Phase 11 language picker + quick-lang buttons) wired
     to `POST /api/triage/{id}/episodes/{epFilename}/tracks/{specifier}/language` (or its media-route
     equivalent — see §D).
   - **Default-flag fix** via `POST /api/media/{id}/tracks/default`.
   - **Fetch still**, edit **episode title / overview**, **Re-pull episode from TMDB**, **Save episode**.
   - Episode-level issue indicators stay; the read-only chips/table become editable rows (expand to
     edit, mirroring the triage episode step).
2. The **series-level** fields and "Fetch all" artwork already have a home on the Overview tab; ensure
   the series-step actions from triage (genres/network edit, fetch-all artwork) are reachable on media
   detail without going to triage. Do not duplicate — fold into existing Overview/Artwork tabs.
3. Movie track editing: the existing "Tracks & order" tab + `TrackOrder.kt` page already cover this. Add
   inline language/default editing to the Tracks tab **only if** it doesn't duplicate the track-order
   page; otherwise leave the link. (Movies have no separate per-episode dimension, so the movie path is
   lighter than series.)
4. Page-level **"Save → disk (NFO)"**, **"Save & tell Jellyfin ↻"**, and **"Diagnose"** actions move
   onto media detail (the existing Sync ↻ control area is the natural host).

### B. Remove the triage pages
5. Delete the routes `/triage` and `/triage/series/{id}` (and the legacy `/series-triage?id=` form) from
   `Main.kt`'s `handleRoute`. Remove `Triage.kt` and `SeriesTriage.kt` once their functionality is
   confirmed migrated. Remove the Triage entry from the sidebar `NAV` list.
6. Replace every in-app link to triage (e.g. the episodes-tab "Fix all in triage →", any `#/triage`
   hrefs) with the new dock / media-detail flow.

### C. Floating Triage dock (navigation only)
7. Add a **Triage dock** as a sibling of `#ambient-dock`, injected once into `document.body` and
   persisting across route changes. It is **navigation only** — it contains no editing controls.
8. Contents:
   - A title + count: "N items need attention" (0 → dock hidden).
   - A **position indicator** ("3 / 12") and **Previous / Next** buttons that navigate the browser to
     `/media/{id}` of the previous/next item in the attention list. Opening media detail is where the
     user fixes things.
   - A collapse toggle, matching the scan dock.
   - Optional "open this item" affordance is redundant (Next/Prev already open it); keep it minimal.
9. The dock's ordered list of attention items comes from `GET /api/triage` (already returns items
   needing attention). The dock tracks the current index; navigating with the address bar or via the
   list keeps the indicator in sync where feasible (best-effort: derive index from the current
   `/media/{id}` route).
10. The dock refreshes its count/list when an item is saved (an item that no longer has issues drops out
    of the list). Reuse the existing triage-count mechanism (`/api/triage/count`) for the headline
    number; keep it live the way the sidebar badge is today.
11. Like the scan dock, the triage dock is suppressed on pages where it would be noise (at minimum it may
    coexist with the scan dock without overlapping — stack or offset them).

### D. Backend
12. Keep `GET /api/triage` and `GET /api/triage/count` — the dock depends on them. Decide whether the
    per-track language-assignment endpoints stay under `/api/triage/*` or are re-exposed under
    `/api/media/*` for clarity now that the UI is the media detail page. Either is acceptable; if they
    stay under `/api/triage/*`, document that the path name is now historical. No behaviour change to the
    assignment logic itself.
13. `GET /api/triage/{id}/suggest` (TMDB suggestions) and bulk/accept-suggestion flows: port the useful
    ones onto media detail if still wanted, otherwise drop them with the triage page. Note the decision
    in the spec when implementing.

## Invariants (must hold)
- **Track flags are never changed automatically** — default/language edits remain explicit user actions
  (constitution §Track flags). Moving the controls to media detail does not change this.
- **NFO writes stay atomic** (`.tmp` + rename); "Save → disk" behaviour is unchanged.
- **Frontend renders server-pushed/fetched state only** — after a save, re-fetch the item and re-render;
  no optimistic/derived state (constitution §4, [[fe-reflects-be-no-derived-state]]).
- **Jellystructure `js_tags` survive** any save/sync triggered from the new location.

## Out of scope
- The `TrackOrder.kt` track-editor page stays as a separate page (still linked from media detail).
- Changing language-resolution rules or the per-file algorithm.
- URL-addressable tabs — handled in **Phase 28** (the episodes tab gaining edit controls makes deep
  links to it more valuable, so 28 pairs naturally with this phase).
