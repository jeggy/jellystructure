# Phase R171 — Ravilo TV: Seerr-powered Request tab (browse · request · search)

> Renumbered **R167 → R171** after syncing with the repo.

> Inside **Discover** (see [R170](phase-R170-profile-hub-discover-merge.md)), the **Request** tab is a full
> **Jellyseerr / Overseerr** integration: rows of Seerr **discover feeds** (configured per user by
> [Phase 137](../../requirements/phase-137-ravilo-config-request-builder.md)), a **Seerr-scoped search**, and
> a request action that hands off to Seerr → Radarr/Sonarr. Replaces the retired Top 10 charts.

**Status:** Planned — **design built**, Compose app integration unbuilt.

## Problem
Requesting missing titles from the sofa used to ride on third-party **Top 10 charts** (Netflix/JustWatch/etc.)
gated by Radarr. That vendor stack is being retired ([Phase 136](../../requirements/phase-136-seerr-connection-retire-charts.md));
**Seerr** becomes the single browse-and-request source. The TV needs to render Seerr's discover feeds and
place requests, without any of the chart framing (ranks, "weeks on chart", trend arrows, "why it's trending").

## Architectural constraint
Ravilo renders **server-pushed state**. The Request rows, their order/visibility, and each item's availability
+ request status all come from the server (Jellystructure proxying Seerr). The client formats feeds and issues
a request intent; it does not talk to Seerr directly, and does not compute availability.

## Current state (as-is) — design built in `design/ravilo/`
- **`ravilo-app.js`**
  - `renderDiscover()` (Request tab): a `dischead` (title + `request_sub`), the Coming Soon / Request segment,
    a **Search Seerr** pill (`data-seerrsearch`), then one `discoverRow(list)` per configured feed.
  - `rankTile(it, list)` is now a **plain poster tile**: poster + `statusMark(it)` badge + title + **metadata**
    (`year · genre · rating+`). **No** rank numeral, "weeks", trend arrow, or "new this week". Row headers show
    the feed title only (scope/`note` chips removed).
  - **Request status** vocabulary retained: `not_requested` → **Request**, `requested`, `queued` (#pos),
    `downloading` (%), `importing`, `available` (✓ In Library), `failed`; live ticker + `requestFetch`.
  - **Seerr search**: the pill opens `renderSearch({ seerr: true })` — the on-screen-keyboard search screen
    scoped to the **Seerr catalogue** (`seerrCatalog()` over the discover feeds, not the library `catalog()`).
    `renderSearchResults` builds request tiles; a result opens the **request detail**. Back returns to Request.
  - **Request detail** (`renderDiscoverDetail`): the chart-only **"Why it's trending"** block (rank / on-chart /
    trend / views stats) is **removed** — detail is hero + synopsis + request actions only.
- **`ravilo-data.js`** — the discover feeds relabeled to Seerr-style names (Trending Movies/Series, Popular
  Movies, International Films, All-Time Popular); stands in for the server-proxied Seerr feeds.
- **`ravilo-i18n.js`** — `search_seerr`.

## Requirements
### A. Feed rows
1. The Request tab renders one **row per configured Seerr discover feed** (order + visibility from Phase 137).
   Each row is a horizontal rail of **plain poster tiles**: poster, title, `year · genre · rating`, and a
   **request-status badge**. No ranks/weeks/trend/chart wording anywhere.

### B. Request action + status
2. Each tile/detail exposes the request lifecycle from server state: **Request** (not in library) → requested →
   queued → downloading (%) → importing → **available** (✓ In Library), plus **failed** (retry). Requesting
   is subject to the per-user permission (Phase 137); approvals happen in **Seerr**; fulfilment via Radarr/Sonarr.

### C. Seerr-scoped search
3. A **Search Seerr** affordance on the Request tab opens the keyboard search **scoped to the Seerr catalogue
   only** (never the local library). Results are request tiles that open the request detail. Back returns to
   the Request tab.

### D. Request detail
4. The request detail shows hero art, title, meta (cert/year/genre), synopsis and **request actions** only —
   **no** rank / trend / "why it's trending" / chart-source framing.

## Invariants
- **No chart framing** — ranks, weeks-on-chart, trend arrows, "new this week", "why it's trending" are gone.
- Search on the Request tab **only** searches Seerr (library search stays on the AppBar search icon).
- Render-only + request-intent-only; availability, status and feed contents are server-pushed.
- Requesting obeys the per-user **allow-request** permission; admins may always request.

## Out of scope
- The admin **connection** (Settings) and per-user **row config** → Phases **136** / **137**.
- People/actor search results, Seerr issue reporting, request management/approval UI (lives in Seerr).
- Coming Soon tab contents (R160/R170).

## Source references
- Design: `design/ravilo/ravilo-app.js` (`renderDiscover`, `rankTile`, `discoverRow`, `seerrCatalog`,
  `searchFilter`, `renderSearchResults`, `renderSearch` seerr mode, `renderDiscoverDetail` w/o `ddt-why`,
  `requestFetch`/status machinery); `design/ravilo/ravilo-data.js` (relabeled `discoverLists`);
  `design/ravilo/ravilo.css` (`.rtile` plain tile, `.dsearch`).
- Related: **136** (Seerr connection), **137** (per-user Request rows), **R170** (Discover shell),
  **R48–R50** (retired chart request flow this replaces).
