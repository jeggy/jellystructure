# Phase 60 — Series detail: left-rail only on overview tab (FR-TD1)

**Status:** ✅ Done

## Goal

On the **series detail page**, the poster, identity card, and language card currently
render as a **persistent left rail that stays visible on every tab** — Episodes, Artwork,
NFO, and History all show it beside their content, wasting horizontal space and making those
tabs feel like "overview with extra stuff" rather than focused work surfaces.

Restructure the series layout to match how the **movie detail page** already works: the
left rail (poster + identity + language card) lives **inside the Overview tabpanel only**;
all other tabs are full-width.

Also fix a latent visual glitch surfaced by this work: the detail-page tab strip uses the
`.seg` pill-control style in Kotlin but the design mockup uses the `.tabs2` underline style
— and the active-tab class is `active` in Kotlin vs `on` in the CSS. Neither is visually
wrong enough to be reported in isolation, but with the layout restructure touching the same
area, align both at the same time.

## Current state

`MediaDetail.kt` renders the series body as:

```
div.row
  div.col(250 px)            ← leftRailHtml (poster + identity + lang card)
  div.col.fill
    div.seg#detail-tabs      ← tab strip (.seg pill style, active = .active class)
    div#tab-overview         ← only the metadata editing column (NO poster here)
    div#tab-episodes
    div#tab-artwork
    div#tab-nfo
    div#tab-history
```

Because `leftRailHtml` is a sibling of the `col.fill` wrapper (not inside any tabpanel),
it shows on **every** tab.

Movie detail already renders correctly:

```
div.seg#detail-tabs          ← at top level
div#tab-overview
  div.row
    div.col(220 px)          ← leftRailHtml (inside overview panel)
    div.col.fill
div#tab-tracks               ← full-width, no poster
div#tab-artwork              ← full-width
div#tab-nfo                  ← full-width
div#tab-history              ← full-width
```

`series.html` design mockup has the **same persistent-rail problem** and must be synced.
The series mockup also still shows the old flat pagebar buttons (plain `.btn` links) rather
than the dropdown/split pattern added in Phase 25; sync those too while the file is open.

## Target behaviour

### Layout (Kotlin + mockup)

Both series and movie detail use a single layout shape:

```
div.tabs2#detail-tabs        ← tab strip (.tabs2 underline, at top level)
div#tab-overview             ← row [ leftRailHtml | overviewMainHtml ]
div#tab-episodes  (TV only)  ← full-width
div#tab-tracks    (movie)    ← full-width
div#tab-artwork              ← full-width
div#tab-nfo                  ← full-width
div#tab-history              ← full-width
```

The `bodyHtml` branch in `MediaDetail.kt` (line ~473) disappears — both code paths end up
calling the same `tabsAndPanelsHtml` directly (no wrapping row). The `overviewPanelInner`
branch (line ~410) also becomes unconditional: always
`row [ leftRailHtml | overviewMainHtml ]`, regardless of `isTvShow`.

### Tab strip style

| | Current (Kotlin) | Target |
|---|---|---|
| Container class | `.seg` (pill control) | `.tabs2` (underline strip) |
| Item element | `<span class="seg-item">` | `<span>` (no extra class needed; `.tabs2 span` selector covers all) |
| Active class | `seg-item active` | `on` (matches `.tabs2 .on`) |
| JS selector | `.seg-item` | spans inside `#detail-tabs` (or `[data-tab]` attribute) |

`.tabs2` is already defined in `app.css` lines 189–195. No new CSS needed beyond Phase 59's
menu block.

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` | See Kotlin changes below |
| `design/app/series.html` | See mockup changes below |

## Kotlin changes (`MediaDetail.kt`)

1. **`overviewPanelInner`** — remove the `if (isTvShow)` branch; always wrap with
   `<div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap;">$leftRailHtml $overviewMainHtml</div>`.

2. **`tabsAndPanelsHtml`** — change the container:
   - `<div class="seg" id="detail-tabs" style="margin-bottom:16px;">` →
     `<div class="tabs2" id="detail-tabs">`
   - Tab item HTML: `<span class="seg-item$active" data-tab="$key">` →
     `<span${if (active.isNotEmpty()) " class=\"on\"" else ""} data-tab="$key">`
     (keep `data-tab` for the click routing; drop the `seg-item` class).

3. **`bodyHtml`** — remove the `if (isTvShow)` branch entirely; both kinds use:
   `$pagebar\n$tabsAndPanelsHtml`

4. **Tab-switching JS** (line ~657) — the selectors that toggle `.seg-item` / `.active`
   must change:
   - Query: `#detail-tabs span[data-tab]` instead of `.seg-item`
   - Active state: toggle class `on` / `` instead of `seg-item active` / `seg-item`

No backend, no routing, no new API changes.

## Design mockup changes (`series.html`)

1. **Left rail** — move the entire `<div class="col" style="width:250px;...">…</div>` block
   (currently lines 113–152) **inside** the `<div class="tabpanel" data-panel="overview">` block,
   wrapping both it and the existing metadata column in
   `<div class="row" style="align-items:flex-start;">…</div>`.

2. **Outer row** — remove the `<div class="row" style="align-items:flex-start;">` wrapper
   that currently surrounds the left rail + main column (lines 111–end-of-row); the tabpanels
   now live at the top level directly under the page content div.

3. **Tab strip** — change `<div class="tabs2" id="tabbar">` (already `.tabs2` ✓) — just
   ensure the active item has class `on` and the overview panel shows by default (currently
   the mockup has `data-panel="overview"` hidden; flip the default to "episodes" in the JS if
   needed, or leave overview as default).

4. **Pagebar buttons** — replace the four flat `.btn` links (Jellyfin ↗ / TMDB ↗ / Re-pull
   from Jellyfin… / Re-pull from TMDB / Save → disk / Save & tell Jellyfin) with the
   `.menu-wrap` / `.split` pattern that matches `media.html` and the live Kotlin code:
   - `span.menu-wrap#links-menu` → External links dropdown (Jellyfin ↗, TMDB ↗)
   - `span.menu-wrap#repull-menu` → Re-pull dropdown (From Jellyfin…, From TMDB)
   - `span.split#save-split` → Save & sync to Jellyfin ↻ (primary) + caret (dropdown: Save → disk)

5. **Episodes / Artwork / NFO / History tabpanels** — these are already full-width in the
   mockup (no left rail inside them); no structural change needed, just verify they look right
   with the new outer layout.

## Non-goals / invariants

- **No data model changes.** The series language card, poster slot, and identity card
  content is unchanged; only where they appear in the DOM changes.
- **No layout change for the movie detail page.** Movies already have the correct layout;
  the Kotlin change only removes the TV-specific branch.
- **Server-pushed state only** — no derived/optimistic state is introduced.
- Left-rail width for series stays at the current value (`width:220px` in the `col` inline
  style for movies; use the same for TV, since the series language card is the same width).
