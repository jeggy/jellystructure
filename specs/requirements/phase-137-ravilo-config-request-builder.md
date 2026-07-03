# Phase 137 — Jellystructure: Ravilo config ▸ Request — Seerr discover rows builder

> Renumbered **135 → 137** after syncing with the repo.

> Replace the per-user **Top 10** section of the Ravilo config editor with a **Request** section: a
> **show-the-tab** toggle, a **request-permission** toggle, and a draggable list of **Seerr discover feeds**
> (with an **Add-row** picker exposing the full endpoint catalogue). This is the per-user authoring surface for
> the TV **Request** tab ([R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)); the Seerr
> **connection** is [Phase 136](phase-136-seerr-connection-retire-charts.md).

**Status:** Planned — **design built** (`design/app/ravilo-config.html`), integration unbuilt.

## Problem
Top 10 was configured per user by picking third-party chart lists (source + country + which charts, in order).
With charts retired (Phase 136) and Seerr as the source, the per-user surface must instead let an operator pick
**which Seerr discover feeds** appear on a viewer's Request tab, and in what order.

## Current state (as-is) — design built in `design/app/ravilo-config.html`
The left-nav **Top 10** item is renamed **Request**; the `sect-top10` card is repurposed:
- Header: **Request** + `Jellyseerr` badge + **show this tab** toggle (reuses the existing reveal handler).
- **Allow this user to request** toggle (off = browse & see status only; admins always may; approvals in Seerr).
- **Rows shown in Request** — a draggable `.cfg-row` list, each row a **Seerr discover feed** with a type badge
  (MOV/TV/MIX), a name, and a `discover/...` source line; per-row **show/hide** toggle + remove. Seeded example:
  Trending · Popular Movies · Action Movies · A24 · HBO Originals · Anime · Upcoming Movies (hidden).
- **＋ Add row** — an opaque popover (`.req-addmenu`, `--fill` surface) listing the endpoint catalogue, grouped:
  **Movies** (Discover / by genre / by original language / by studio / Upcoming) · **TV** (Discover / by genre /
  by original language / by network / Upcoming) · **Mixed** (Trending).
- The retired chart IIFEs no-op safely (their `#top10list`/`#t10-*` elements are gone).

## Requirements
### A. Request section (per user)
1. Per-user **show Request tab** toggle and **allow-request** permission toggle.
2. A **reorderable list** of Seerr discover feeds shown on that user's Request tab; each row toggles
   **show/hide** and can be removed. Order + visibility persist in the user's Ravilo layout.

### B. Add-row endpoint catalogue
3. **＋ Add row** offers every Seerr discover endpoint: **Movies** — Discover (popular), by genre, by original
   language, by studio, Upcoming; **TV** — Discover (popular), by genre, by original language, by network,
   Upcoming; **Mixed** — Trending. **Parameterised** feeds (genre / studio / network / language) prompt for the
   value when added.

### C. Wiring
4. Requires the Seerr **connection** (Phase 136). The resulting per-user row set drives the TV **Request** tab
   (R171). Availability/request status is resolved live from Seerr at render time (not stored in the layout).

## Invariants
- Per-user (respects the global-vs-custom layout scope switcher, like other Ravilo-config sections).
- The Add-row popover is **opaque** (`--fill`, not the translucent card token).
- Facets here are **Seerr discover endpoints**, distinct from the library workbench facets.

## Out of scope
- The Seerr **connection** (Phase 136) and the TV **Request** tab (R171).
- A live-preview of the Request tab inside the editor (possible follow-up).
- Bulk import / templates of feed sets across users.

## Source references
- Design: `design/app/ravilo-config.html` (nav "Request", repurposed `#sect-top10` card, `.cfg-row` rows,
  `.req-addmenu` picker); `design/app/wf.css` (`.cfg-row`, `--fill` popover surface).
- Related: **[Phase 136](phase-136-seerr-connection-retire-charts.md)** (connection),
  **[R171](../ravilo/requirements/phase-R171-seerr-request-tab.md)** (consumer), retired **R50** (Top-10 config).
