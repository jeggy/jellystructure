# R52 — Ravilo TV: compact app bar + search-as-icon (FR-RN1)

**Status:** ✅ Done — `AppBar` compacted (60 dp / 15 sp / 12×6 / 18-gap, mark 26 dp + 22 sp wordmark);
Search dropped from the nav list (Home·Movies·Series·My List [+ Top 10]) and added as a drawn-magnifier
`SearchIcon` in the right cluster (search · clock · avatar) with `onSearch` → the Search screen. Nav
index maps in `RaviloApp` remapped (Top 10 now 4, `DISCOVER_NAV_INDEX = 4`), focus chain extended
(last nav → search icon → avatar).
**Depends on:** R51 (brand mark), R09 (app bar / focus), R12 (Search screen), R49 (Top 10 nav item)

## Goal

The app bar is too tall and each nav item's text/padding is too large, so only ~2–3 items fit before
crowding the clock/profile. We want all **five** primary tabs visible — **Home · Movies · Series ·
My List · Top 10** — with **Search moved to an icon** on the right (next to the clock and the profile
avatar), matching the design. Net: a shorter, denser bar that comfortably holds 5 tabs + the right
cluster.

## Current state

`AppBar.kt`: height **72 dp**, horizontal `screenPadH` (48 dp), items `spacedBy(28 dp)`; nav items are
**16 sp**, padding `14 dp × 6 dp`, radius 11 dp. The default nav list is **5 items including "Search"**
(`nav.home/movies/series/my_list/search`); R49 appends "Top 10" when available → up to **6 text items**
plus the wordmark, clock and avatar, which overflows. The right side is only `weight(1f)` →
`ClockDisplay` → `ProfileAvatar`. **Search is a nav item, not an icon.**

## Target (from the design)

`design/ravilo/ravilo.css`:
```
.appbar   { height:92px; gap:38px; padding:0 64px; }                 /* mockup scale */
.topnav   { display:flex; align-items:center; gap:8px; }
.navitem  { font-size:21px; font-weight:500; padding:9px 20px; border-radius:11px; }
.navitem.cur     { color:var(--ink); font-weight:600; }
.navitem.focused { background:var(--ink); color:#0a0c13; }           /* inverted on focus */
.appbar .right   { margin-left:auto; display:flex; align-items:center; gap:22px; }
.search-ic { width:44px; height:44px; border-radius:50%; }           /* magnifier, focusable */
.search-ic svg { width:24px; height:24px; }                          /* circle r7 + handle line */
.clock     { font-size:19px; font-variant-numeric: tabular-nums; }
.avatar    { width:44px; height:44px; border-radius:50%; background:var(--grad); }
```
Right cluster order: **search icon · clock · avatar** (gap 22). Search is the magnifier glyph
`<circle cx=11 cy=11 r=7/> <line 16.5,16.5 → 21,21/>`.

## Required changes

1. **Drop "Search" from the nav list.** Primary tabs become **Home · Movies · Series · My List**
   (+ **Top 10** when `discoverAvailable`). Update the `navItems` builders in `HomeScreen` and
   `DiscoverScreen`, the `activeNav` indices, and the `onNavSelect` index→destination maps in
   `RaviloApp` (Search is no longer an index).
2. **Add a search icon to the right cluster** in `AppBar.kt` — a focusable 44 dp circle with a
   magnifier (Compose `Icons.Default.Search` if the material-icons artifact is present, else a drawn
   vector: `Canvas` circle + handle line). Placed **before** the clock; `onSelect` → the Search screen
   (the existing `Search` destination, now reached via this icon rather than a tab). Focus treatment
   mirrors `.search-ic.focused` (inverted) like the avatar.
3. **Compact the bar.** Reduce from the current 72 dp / 16 sp / 14×6 / 28-gap toward, e.g., **bar
   ~56–64 dp**, nav **~15–16 sp**, padding **~12 × 6 dp**, item gap **~14–18 dp**, wordmark/mark scaled
   to match (R51). Tune so 5 tabs + (search · clock · avatar) sit on one row with breathing room at TV
   sizes; don't just copy the mockup's 92 px / 21 px (full-canvas scale).
4. **Focus traversal** (R09): left/right across nav items; the **rightmost nav item → search icon →
   avatar** on right-presses (extend the `allFRs`/`onRight` chain to include the new search icon between
   the last nav item and the avatar); `onDown` from any bar element still drops into the content/hero.

## Non-goals / invariants

- **Server-pushed gating** unchanged — Top 10 still shows only when `GET /api/tv/discover.available`.
- **i18n** — keep `nav.*` strings; the search icon needs only an accessible label (`nav.search`).
- **Shared-first** — all in `:ravilo-ui` common; D-pad-first; Back unchanged; never strand focus.
- Pure presentation/navigation; no API or config change.

## Mockup

`design/ravilo/Ravilo TV.html` — `.appbar` (`.brand` · `.topnav` `.navitem`s · `.right`
`.search-ic`/`.clock`/`.avatar`); the search glyph svg is in the app-bar markup.
