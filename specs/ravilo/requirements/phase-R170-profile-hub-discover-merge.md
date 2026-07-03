# Phase R170 — Ravilo TV: profile-hub nav + unified Discover (Coming Soon / Request)

> Renumbered **R166 → R170** after syncing with the repo (R166–R169 already taken).

> Declutter the top **AppBar**: trim the section tabs to **Home · Movies · Series · Discover**, move the
> personal destinations under the **avatar**, and fold the separate **Upcoming** calendar and **Top 10**
> screens into a single **Discover** screen with a **Coming Soon / Request** segment switch. Retires **Top 10**
> as a standalone tab (its requestable-content role moves to **Request** — see [R171](phase-R171-seerr-request-tab.md)).

**Status:** Planned — **design built**, Compose app integration unbuilt.

## Problem
The top nav grew to six section tabs (Home / Movies / Series / Upcoming / Top 10 / My List) plus search,
clock and avatar. Upcoming and Top 10 only appear when their backends are enabled, so the row's width keeps
changing; on a 10-foot TV the dense row has no visual rest. **My List** — a personal destination — competes
with the browse sections. Two adjacent screens (Upcoming calendar, Top 10 charts) are really the same idea:
"what's coming and what you can pull in".

## Current state (as-is) — design built in `design/ravilo/`
- **`ravilo-app.js`**
  - AppBar `topnav` is now **Home · Movies · Series · Discover** (`data-nav="discover"`, `#rv-nav-discover`).
  - **Profile menu** (`profmenu`): the avatar opens a dropdown with **My List**, **Settings**, **Unpair this
    TV**, and a **Switch** action in the header. `openProfMenu`/`closeProfMenu`/`moveProfMenu`/`activateProfMenu`
    plug into the existing D-pad focus engine (modal like the detail `overlay`); **Settings** → `openSettings()`
    (settings panel), **Switch** → `openProfiles('switch')` ("Who's watching"), **Unpair** → `renderUnpairConfirm`.
  - **Discover** is one screen with a `discSegment(active)` bar: `renderUpcoming()` renders the **Coming Soon**
    tab (the Sonarr/Radarr calendar), `renderDiscover()` renders the **Request** tab; the segment chips
    (`data-disctab`) switch views. The Discover nav opens the default tab (`upcomingEnabled()` → Coming Soon,
    else Request). Both set the AppBar `cur` to `discover`.
  - Gating: `updateDiscoverNav()` shows `#rv-nav-discover` when `upcomingEnabled() || seerrEnabled()`
    (`config.seerr` added to `ravilo-data.js`).
- **`ravilo.css`** — `.profmenu` (+ `.pm-*`) dropdown, `.discseg`/`.dseg` segment.
- **`ravilo-i18n.js`** — `nav_discover`, `pm_switch`/`pm_settings`/`pm_unpair`, `seg_coming`/`seg_request`,
  `request_sub` (en/da/fo).

**The gap:** the Compose AppBar still renders six tabs and a switch-only avatar; Upcoming and Top 10 are
separate destinations.

## Requirements
### A. Nav
1. Section tabs are exactly **Home · Movies · Series · Discover**. Search, clock and avatar stay in the right
   cluster (clock retained per user preference).
2. **Discover** is shown when **Coming Soon** (Sonarr/Radarr) **or** **Request** (Seerr) is available; hidden
   when neither is configured.

### B. Profile menu (avatar)
3. The avatar opens a **dropdown**, not the full "Who's watching" grid: **My List**, **Settings**, **Unpair
   this TV**, plus **Switch profile** in the header. **Settings** opens the settings panel directly; **Switch**
   opens the "Who's watching" screen; **Unpair** opens the existing confirm.
4. The menu is D-pad navigable (up/down through items, Select activates, Back/Esc closes) and pointer-clickable.

### C. Discover screen
5. One Discover screen with a **Coming Soon / Request** segment. **Coming Soon** = the existing upcoming
   calendar (R160). **Request** = the Seerr surface (R171). Entering Discover lands on the default tab; the
   segment is reachable by D-pad UP from the content.
6. **Top 10** as a standalone section/tab is **removed**; there is no `nav_top10` destination.

## Invariants
- Clock stays in the AppBar.
- Coming Soon keeps its current Sonarr/Radarr source and behaviour (unchanged by this phase).
- Profile menu is **render-only** over existing profile/settings actions — it re-routes, it doesn't add new
  server state.

## Out of scope
- The **Request** tab's Seerr feeds, rows, search and tiles → **[R171](phase-R171-seerr-request-tab.md)**.
- Moving Coming Soon off Sonarr/Radarr onto Seerr's upcoming endpoints (open product decision).
- Any redesign of Home / Movies / Series / detail screens.

## Source references
- Design: `design/ravilo/ravilo-app.js` (AppBar markup, `profmenu` + `openProfMenu`/`moveProfMenu`/
  `activateProfMenu`, `discSegment`, `renderDiscover`/`renderUpcoming`, `updateDiscoverNav`/`seerrEnabled`);
  `design/ravilo/ravilo.css` (`.profmenu`, `.discseg`); `design/ravilo/ravilo-i18n.js` (new keys).
- Related: **R160** (Upcoming calendar → Coming Soon), **R48–R50** (the retired Top 10 / Discover charts),
  **R161** (in-app settings panel reused by the menu), **constitution** (renders server-pushed state).
