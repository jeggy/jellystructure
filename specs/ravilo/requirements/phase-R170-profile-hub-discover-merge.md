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

---

## Dev-review addenda (2026-07-04 — Compose/app design, verified against code)

> The mock is accepted; this maps it onto the real `ravilo-ui` nav shell. Pairs with
> [R171](phase-R171-seerr-request-tab.md) (Request tab contents) and [Phase 136](../../requirements/phase-136-seerr-connection-retire-charts.md)
> (`seerr.enabled`). Note the `TvApiClient.unpair()` "missing" note elsewhere is **stale** — it exists
> (`shared/.../tv/TvApiClient.kt:225`, R161).

### A. Nav model — `NavItems.kt` + `AppBar.kt`
- **`raviloNavItems(upcomingAvailable, discoverAvailable)`** (`ravilo-ui/.../screens/NavItems.kt:14`) today emits a
  variable row (Home/Movies/Series + optional Upcoming + optional "Top 10" + always My List). Rework to a **fixed
  Home · Movies · Series + one optional Discover**: `["Home","Movies","Series"] + if (discoverShown) ["Discover"] else []`.
  **My List leaves the section row** (moves to the avatar menu, §B). `discoverShown = upcomingAvailable || seerrEnabled`.
- **`enum RaviloNavTarget`** (`:21`) drop `UPCOMING`/(the Top-10 target) as section tabs; keep `DISCOVER`, `MY_LIST`
  (now reached from the menu, not the tab index). Simplify `raviloNavTarget(index, …)` (`:26`) index math — one
  optional slot instead of two, so the fragile `index - 3 in optional.indices` collapses.
- **`AppBar`** (`ravilo-ui/.../components/AppBar.kt:61`): tabs render 3–4 items (`:148-195`); the right cluster
  (`SearchIcon :199`, `ClockDisplay :208`, `ProfileAvatar :209`) stays — **clock retained** (invariant). D-pad
  wiring rightmost-tab → search → avatar (`:185-217`) still holds.

### B. Avatar dropdown (`profmenu`) — new modal composable, render-only re-routing
Today the avatar calls `onProfile = { push(Dest.ProfilePicker) }` (`RaviloApp.kt:485` etc.) — one callback. Replace
with a **dropdown overlay** anchored under `ProfileAvatar` (`AppBar.kt:222`), modeled on the existing detail
`overlay` modal (D-pad up/down through items, Select activates, Back/Esc closes, pointer-clickable). Items re-route
into **existing** destinations/actions — no new server state:
- **My List** → `Dest.Browse(BrowseKind.MY_LIST, …)` (the browse screen already supports MY_LIST).
- **Settings** → `Dest.Settings(…)` (opens `SettingsScreen`, R161 — skin/language/unpair/playback toggles).
- **Switch profile** (menu header) → `Dest.ProfilePicker` ("Who's watching", `ProfilePickerScreen.kt:94`).
- **Unpair this TV** → the confirm + `SettingsStore.unpairDevice()` path (`SettingsScreen.kt:111-116`, iterates
  `MultiTokenStore.getAll()` → `apiClient.unpair(token)` → `MultiTokenStore.clear()` → `resetTo(Dest.Pairing)`).
  ⚠ **Today unpair only exists *inside* `SettingsScreen`** — extract the confirm+revoke into a small reusable
  overlay (or route Unpair to open Settings focused on that block) so the menu can invoke it directly.

### C. Discover screen — merge Upcoming (Coming Soon) + Request under a segment
- **Dest model** (`RaviloApp.kt:133-176`): collapse the separate `Dest.Upcoming` + `Dest.Discover(Top10)` into a
  single **`Dest.Discover(segment: ComingSoon | Request)`**. Selecting the Discover tab opens the default segment:
  `upcomingEnabled() ? ComingSoon : Request`.
- **Content:** the segment bar sits above the content; **Coming Soon** renders the existing `UpcomingScreen`
  body (R160 calendar — `UpcomingScreen.kt`, unchanged behaviour); **Request** renders R171's Seerr surface
  (rebuilt from `DiscoverScreen.kt`). Segment reachable by D-pad **up** from the content; both keep AppBar `cur =
  Discover`. Remove the standalone `DISCOVER_NAV_INDEX` pinning (`DiscoverScreen.kt:82`, `UpcomingScreen.kt:289`
  `activeNav = 3`) in favour of the resolved Discover index.
- **Top 10 as a section is gone** — no `nav_top10`/standalone destination (its requestable role → R171).

### D. Gating + backend flag
`HomeStore` already exposes `upcomingAvailable` (from `getUpcoming().enabled`) and `discoverAvailable` (from
`getDiscover().available`) as `StateFlow` (`HomeStore.kt:35-39/79-83`), lifted into `RaviloApp` (`:268-270/474-477`).
Repoint `discoverAvailable` at **`seerrEnabled`**: add `seerr.enabled` to the TV config/home DTO (backend — a bool
from `configStore.current.seerr?.enabled`, Phase 136) and expose it like the retired chart `available`. `Discover`
tab shows when `upcomingAvailable || seerrEnabled`.

### Source references (app anchors)
- `ravilo-ui/.../screens/NavItems.kt:14/21/26`; `components/AppBar.kt:61/148-217/222`.
- `screens/RaviloApp.kt:133-176/268-270/474-477/485` (nav stack, gating, avatar callback);
  `screens/ProfilePickerScreen.kt:94`; `screens/SettingsScreen.kt:111-116/119`; `screens/HomeStore.kt:35-39/79-83`.
- `screens/UpcomingScreen.kt` (Coming Soon body), `screens/DiscoverScreen.kt:82` (Request body — rebuilt in R171).
- `shared/.../tv/TvApiClient.kt:225` (`unpair`), `:233/247` (`getDiscover`/`getUpcoming`).
- Mock: `design/ravilo/ravilo-app.js` (`profmenu`, `discSegment`, `updateDiscoverNav`/`seerrEnabled`).

**Implementation note (2026-07-04):** shipped per the design above, with three call-outs on where the
build deviated from (or made a concrete choice within) the plan:

1. **Unpair extraction** — took the first of §B's two suggested options: `UnpairConfirmOverlay` in
   `SettingsScreen.kt` lost its `private` modifier and `SettingsStore.unpairDevice()` now delegates to a
   new top-level `unpairAllSessions(apiClient)`, so the new `ProfileMenu.kt` (in `components/`, not
   `screens/`) calls the exact same revoke logic without instantiating a whole `SettingsStore` (which
   would also trigger an unrelated settings-config load).
2. **No shared chrome shell for the segment switch** — §C describes "the segment bar sits above the
   content" for both segments, which reads as one shell hosting swappable content. The build instead
   keeps `UpcomingScreen`/`DiscoverScreen` as two complete, independent screens (each still renders its
   own full `AppBar`), reached via one `Dest.Discover(displayName, segment)` — `RaviloApp.kt`'s `when`
   picks which composable renders. Each screen gained a small `DiscoverSegmentPill` "↔ switch" affordance
   in its header column (shown only when the *other* segment is also available). Rationale: extracting a
   shared AppBar+segment-bar shell would mean pulling the content out of both existing screens into
   chromeless sub-composables — real work that's hard to justify before R171 replaces `DiscoverScreen`'s
   entire body anyway. Switching segments uses `replaceTop`, and because both segments share the same
   `Dest.Discover` class, `AnimatedContent`'s `contentKey = { it::class }` treats it as a same-class swap
   (no slide transition) — the same treatment every other same-class tab switch already gets.
3. **Request segment is still empty** — `DiscoverScreen` renders against the Phase-136-stubbed
   `/tv/discover` route, so `data.rows` is always `[]` even once Seerr is connected (R171 wires the real
   feed rows). Rather than ship a broken-looking bare "Top 10" header with zero rows, the empty-rows case
   now renders a "Nothing to request yet — add feeds in the Ravilo config editor" placeholder line;
   `DiscoverScreen`'s header text itself was also renamed from "Top 10" to "Request" per Phase 137's
   rename. The `seerrEnabled` repoint (§D) landed as a one-line change to `TvRoutes.kt`'s `/tv/discover`
   handler (`configStore.current.seerr?.enabled == true`) since `configStore: ConfigStore` was already a
   parameter there — no new DTO field was needed, `DiscoverResponse.available` carries it as designed.
