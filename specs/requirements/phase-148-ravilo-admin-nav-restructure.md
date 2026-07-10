# Phase 148 — Admin "Ravilo" nav section: split editor, move pages, collapsible groups, Collections rename (FR-RV-NAV1)

> Reorganizes the jellystructure admin sidebar so everything Ravilo lives under one **Ravilo** section,
> and breaks the single overloaded "Ravilo TV" editor into focused destinations. Also renames the Ravilo
> filter-**collections** to "Collections" so **"Channels" can mean Live TV** (Phases 147 / R177).
> Design mockups: **`design/app/app-shell.js`** (sidebar), **`design/app/ravilo-config.html`** (tabbed
> editor), **`design/app/ravilo-users.html`** (moved page), **`design/app/livetv.html`** (Phase 147).

## Goal
A cleaner admin IA: a collapsible **Ravilo** section — **Layout · Live TV · Requests · Users & devices ·
Preferences** — instead of one catch-all "Ravilo TV" page plus scattered Ravilo settings.

## Current state (verified)
- Sidebar groups: Dashboard/Library/Activity, **Setup** (Metadata · Settings), **Apps** (single item:
  Ravilo TV → `ravilo-config.html`).
- `ravilo-config.html` is one long page holding **all** Ravilo config: Hero carousel, Channels (filter
  collections), Content rows, Request (Seerr feeds), Behaviour & preferences — with one global/per-user
  **scope switcher** and an in-page left section-nav. Live TV config (Phase 147) sat under Setup.
- **Users & devices** (Phase 143) lived as a **Settings tab** (`settings.html?tab=users`).
- The Ravilo TV app + admin call filter-collections **"Channels"**, which now collides with Live TV.

## Requirements

### A. The "Ravilo" section
1. Rename the sidebar **"Apps"** group to **"Ravilo"**, containing, in order: **Layout · Live TV · Requests
   · Users & devices · Preferences**.
2. **Collapsible groups:** each sidebar group header toggles its items open/closed, with a rotating
   chevron, persisted (localStorage `js-nav-collapsed:<group>`). Applies to all groups (Setup + Ravilo).

### B. Split the "Ravilo TV" editor into focused destinations (shared scope)
1. `ravilo-config.html` becomes **tab-addressable** (`?tab=layout|requests|preferences`) — one editor that
   shows only the active tab's sections, sharing the single global/per-user **scope switcher** and pagebar
   across all three (the shared switcher is why these stay one page, not three):
   - **Layout** (`?tab=layout`): Hero carousel + Collections + Content rows + a **Live TV on Home** section
     (where Live TV's “On now” row / collection sits) that appears **only when Live TV is enabled**
     (Phase 147) — disabling Live TV removes it. Live TV is never a Ravilo top-nav tab.
   - **Requests** (`?tab=requests`): the Seerr discover-feeds builder (Phase 137).
   - **Preferences** (`?tab=preferences`): Behaviour overlay — skin · language · tile shape · default
     request language (R172) · grid columns (R174). Always editable per-user (never shows the layout-lock).
2. Each tab is its own sidebar item; the page title reflects the tab ("Ravilo · Layout / Requests /
   Preferences"); the in-page section-nav is scoped to the active tab (only Layout has sub-sections).
3. Sidebar active-state resolves per tab (the page reports a distinct `data-page` per `?tab`).

### C. Move Live TV + Users & devices into the section
1. **Live TV** (Phase 147, `livetv.html`) moves out of Setup into the Ravilo section.
2. **Users & devices** moves out of the Settings tab bar into its **own page** (`ravilo-users.html`) with
   its own pagebar — no Settings chrome. Remove it from the Settings tab rail entirely (markup, CSS, JS).
   The page keeps everything from Phase 143: connected summary, now-watching, Ravilo devices + admin web
   sessions, access lines (restricted/kids), recently-watched with show-more, confirm-to-revoke +
   sign-out-everywhere.

### D. Collections rename (so "Channels" = Live TV)
1. Rename the Ravilo filter-**collections** feature from "Channels" to **"Collections"** across the admin
   editor (section header, sidebar/section-nav labels, "Add collection", scope-lock copy) **and** the
   Ravilo TV app + i18n (en/da/fo) — the model ids (`ChannelConfig`, `data-kind="channel"`, css) can stay;
   this is a display-string rename only, like R64. After this, **"Channels" refers exclusively to Live TV**.

## Non-goals
- No change to what each editor section *does* (only where it lives + its label).
- No forking of the shared scope switcher into per-page copies (one editor keeps it shared).
- No model/id migration for the Collections rename (display strings only).

## Acceptance
- The sidebar shows a collapsible **Ravilo** group: Layout · Live TV · Requests · Users & devices ·
  Preferences; collapse state persists across pages.
- `ravilo-config.html?tab=layout|requests|preferences` each show only their sections, share one scope
  switcher, and highlight the matching sidebar item.
- Users & devices renders as its own page with no Settings chrome and is gone from the Settings tab rail.
- "Channels" no longer refers to collections anywhere (admin + TV + i18n); collections read "Collections".

## Status note
Design-authored, `Planned`, not yet dev-reviewed. Exports to
`specs/requirements/phase-148-ravilo-admin-nav-restructure.md`; `scripts/check-phases.sh` will flag it
for a `STATUS.md` row. **Next admin number after this is 149.**

## Dev-review addenda (2026-07-10)
Verified against the shipped wasmJs admin frontend. The IA changes are sound and mostly contained, but the
spec's central mockup citation is misleading and one precedent can't be browsed.

**A. THE nav is Kotlin (`Shell.kt`), NOT `app-shell.js` — the cited mockup never ships.** `syncDesignAssets`
copies only `wf.css/app.css/detail.css/metadata.css/seeding.css/seeding.js` from `design/app`;
**`app-shell.js` is not in that list**, and the wasm `index.html` loads only `seeding.js` +
`jellystructure.js`. The runtime sidebar is built entirely in `Shell.kt` (`NAV` list + `shellHtml()`/
`renderShell()`), currently exactly Dashboard/Library/Activity · Setup(Metadata/Settings) · Apps(→"Ravilo
TV"). So renaming Apps→Ravilo, adding the five items, and the **collapsible-group chevron +
`localStorage 'js-nav-collapsed:'` logic (§A2) all get re-implemented in Kotlin `Shell.kt`** — the
equivalent code already sitting in `design/app/app-shell.js` (which is *already at the Phase-148 target*)
is a visual reference only, wired to nothing at runtime. A dev reader must not assume editing app-shell.js
changes the app. This is the single most important correction in the spec.

**B. Query routing already works; per-tab active-state does not.** `Router.currentQuery()` already parses
query params and `RaviloConfig.kt` already reads them (`["channel"]`), so making the editor
`?tab`-addressable is trivial. But active-nav is **href string-matching** in `Shell.kt` (there is **no
`data-page` mechanism** in the Kotlin runtime — that exists only in the mockup), and hrefs render as
`#/ravilo` while routes are path-only `/ravilo` (a latent `#`-prefix mismatch worth an on-device check).
Three sidebar items sharing the `/ravilo` path need **query-aware hrefs + matching** for §B3's per-tab
highlight — the one non-trivial piece.

**C. The editor has SIX sections, not five — the spec omits "Portrait screen" (R159).** `renderSections()`
renders Hero carousel · Channels · Content rows · Request · Behaviour · **Portrait screen**, all at once
(2595-line file; a scrollspy section-nav, not tab-gating). The scope switcher + pagebar live in the shared
`renderFull` scaffold, so gating `renderSections`/the section-nav by `?tab` cleanly preserves the shared
switcher (matches §B1's rationale — a contained refactor). Assign the unmentioned **Portrait screen**
section to a tab explicitly (Layout is the natural home).

**D. Users & devices extraction is confirmed and self-contained.** It lives as a Settings tab
(`Settings.kt`: the `users` nav item, `sect-users`, `SECTION_TAB`/`SETTINGS_TABS`, `loadUsersCard`/
`refreshUsersList`/`loadUserHistory`). The three `users*` functions only touch `#users-list`/
`#users-summary`, so extraction = delete the tab wiring + move them into a new page renderer + a `Main.kt`
route + a `Shell.kt` NAV entry. Moderate, low-risk.

**E. Collections rename: ~20-25 user-facing strings; model ids stay; the R64 precedent is git-history only.**
Admin `RaviloConfig.kt` has ~12 distinct "Channel(s)" display strings (section nav/header, "+ Add channel",
workbench "Create channel", "Channel button", placeholders, fallback names). The TV runtime i18n is **3
keys × 3 langs = 9 values** (`section.channels`, `section.channels_sub`, `browse.empty_channel` in
`ravilo-ui/.../i18n/Strings.kt`) — **keys stay, values change**. Plus the design files. **Do not touch**
`up.network="Kanal"` (that's the *Network* metadata field, a different concept). Model ids
(`ChannelConfig`, `data-kind="channel"`, CSS) stay per §D. **Note:** the "like R64" precedent can't be
browsed — R64's spec was pruned to git history in the earlier cleanup — but the pattern (display-strings-
only rename, keys/ids unchanged) is sound and low-risk regardless.

**F. Ordering.** This rename is the prerequisite that frees the word "Channels" for Live TV (Phases
147/R177). Land 148 §D **before — or together with —** the Live TV epic, or the admin + TV app briefly
carry two "Channel" meanings.
