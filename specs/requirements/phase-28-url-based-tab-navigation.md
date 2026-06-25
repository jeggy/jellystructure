# Phase 28 — URL-based tab & view-state navigation (FR-UN1)

## Problem
Top-level pages have their own URLs, but **sub-state within a page does not**. Switching to the
"Artwork" tab on a media detail page, filtering the Library, or jumping to a Settings section leaves the
address bar unchanged. So you can't link someone to "the Artwork tab of this movie", a browser refresh
drops you back to the default tab, and the Back button doesn't undo a tab switch. We want the **current
view to be fully reconstructable from the URL** everywhere — open a URL and land exactly where it points.

## Current state (as-is)
- **`Router.kt`** is hash-based: `current()` returns `window.location.hash` minus `#`; `navigate(path)`
  sets the hash; `init` re-renders on `hashchange`. There is **no query/segment parsing** — the whole
  thing after `#` is treated as one route string.
- **`Main.kt` `handleRoute`** mostly uses **exact** matches (`route == "/library"`, `route ==
  "/settings"`). A few routes hand-parse extra data ad hoc: `/media/{id}` via `removePrefix`,
  `/track-order?id=…&ep=…` via `substringAfter("id=")`. Anything with an unrecognised query form falls
  through to the dashboard (documented in [`_investigation-findings.md`](_investigation-findings.md)).
- **Tabs are in-memory only.** `MediaDetail.kt` renders a `.seg` tab bar with `data-tab` keys
  (`overview` / `episodes`|`tracks` / `artwork` / `nfo` / `history`); clicking toggles panel
  `display` and never touches the URL. Default tab on every render is `overview`.
- **Library** holds `libKind` / `libFilter` / `libSort` / `libSearch` / `libPage` in module vars; none
  are reflected in the URL, so a refresh resets the grid.
- **Settings** uses real `<a href="#sect-…">` anchors for its left sub-nav. Those fire `hashchange`,
  which the router currently does not understand as a settings sub-section (it does not start with `/`).
- **Activity** holds filter state (`activeLogCategory`, `errorsOnlyFilter`) in module vars, not the URL.
- Phase 19's planned **Metadata page** anticipates `/metadata?tab=networks` and Phase 19 already calls
  out that query-param navigation "needs router work" — this phase is that work.

## Requirements

### A. Router: parse and carry view-state
1. Extend `Router` to split the hash into a **path** and a **query map**:
   `#/media/elfie?tab=artwork` → path `/media/elfie`, query `{tab: artwork}`. Provide:
   - `currentPath(): String` and `currentQuery(): Map<String,String>` (decoded).
   - `navigate(path, query: Map<String,String> = emptyMap(), replace: Boolean = false)` that builds the
     hash. `replace = true` updates the URL **without** adding a history entry (for high-frequency
     changes like search typing); `replace = false` pushes a new entry (for discrete actions like tab
     clicks) so Back/Forward step through them.
   - A helper to update **only** the query of the current path (preserve path + other params).
2. `handleRoute` matches on **path** (use `startsWith` where an id/segment follows) and passes the query
   map to the page renderer. No recognised route should ever fall through to the dashboard because of a
   query string. Keep the existing legacy forms working (or redirect them to the canonical form).
3. Settle on **one canonical scheme** and use it everywhere: query params on the hash route
   (`#/path?key=value`). Migrate the ad-hoc `track-order?id=…&ep=…` and the Settings `#sect-…` anchors
   onto it. (Path-style sub-segments like `/media/{id}/artwork` are the alternative; pick query params
   for consistency with the already-anticipated `?tab=` / `?studio=` forms and document the choice.)

### B. Media detail tabs in the URL
4. The active tab is read from the URL on render (`?tab=…`), defaulting to `overview` when absent or
   unknown. Navigating directly to `#/media/elfie?tab=artwork` shows the Artwork tab immediately
   (including its lazy load — artwork status / history fetch fire for the initial tab, not only on
   click).
5. Clicking a tab updates the URL via `navigate(..., replace = true)` (replaceState) so the address
   bar stays in sync without firing `hashchange` and without adding a history entry. Switching tabs
   must not reload the whole page — only the tab panel toggles in the DOM. Back/Forward navigate
   between pages, not between tabs within a page (standard tab UX). Deep-linking and refresh still
   reconstruct the correct tab via the URL.

### C. Apply the pattern to the other stateful pages
6. **Library**: reflect `kind`, `filter`, `sort`, `search`, `page` in the query string. On load, restore
   from the URL; on change, write back (use `replace = true` for live search typing, `replace = false`
   for chip/sort/page changes). A refresh or shared link reproduces the exact grid. This also unblocks
   Phase 19's `/library?studio=…` deep links and Phase 29's search landing.
7. **Settings**: the active section is `?sect=…`; direct navigation scrolls/shows that section. Replace
   the bare `#sect-…` anchors with router-driven navigation.
8. **Activity**: reflect the log category filter and errors-only toggle in the query string so a filtered
   console view is linkable/refresh-stable.
9. **Triage dock (Phase 27)** already navigates to `/media/{id}`; ensure it can target a specific tab
   (e.g. open straight to `episodes`) using the same `?tab=` convention.

### D. Behaviour guarantees
10. Browser **Back/Forward** traverse page-level navigation (library → detail → back to library).
    Tab switches within a page use `replaceState` and do not add history entries — this is correct
    tab UX (users expect Back to leave the page, not cycle through tabs). Filter/section changes on
    Library/Settings that warrant history entries still use `replace = false`.
11. Deep-linking is **idempotent**: rendering from a URL produces the same view as clicking to it, and
    re-serialising that view yields the same URL (no drift).
12. Unknown/[]invalid sub-state values fall back to the page default rather than erroring or blanking.

## Invariants (must hold)
- **No Compose for Web / DOM-only** — routing stays hash-based via `kotlinx.browser` (constitution §5).
- **Frontend renders server-fetched state only** — URL drives *which* state is shown; it does not become
  a second source of truth for server data ([[fe-reflects-be-no-derived-state]]).

## Out of scope
- Switching from hash routing to History API path routing (would require server-side fallback wiring).
- Persisting view-state server-side or per-user; the URL is the only persistence here.
