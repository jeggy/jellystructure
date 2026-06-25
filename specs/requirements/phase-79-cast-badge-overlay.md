# Phase 79 — Cast tab: episode-count badge overlaid on the photo (FR-CC3)

**Status:** ✓ Done

> The `▸ N eps` badge on each series cast card currently floats in the text area below the
> photo, which looks broken. Move it to overlay the **lower-left corner of the photo**.

## Problem

On the series-detail **Cast & crew** tab (Series scope), each main-cast card shows a TMDB
total-episode-count badge `▸ N eps`. It renders in the wrong place — visually detached, sitting
over the name/role text rather than on the image.

Two causes, both in the person-card render + CSS:

1. **DOM placement** — in `renderPersonCardHtml` (`MediaDetail.kt:3136–3146`) the badge is emitted
   as a direct child of `.person`, *before* the `.ph` photo `<div>`:
   ```kotlin
   append("""<div class="person$inh"$drag>""")
   if (showEpBadge && p.episodeCount > 0) append("""<span class="epb">▸ ${p.episodeCount} eps</span>""")
   ...
   append("""<div class="ph" ...><img ...></div>""")   // photo comes AFTER the badge
   ```

2. **CSS anchoring** — `.epb` is absolutely positioned relative to the whole `.person` card and
   anchored to the **card bottom** (`design/app/wf.css:390`):
   ```css
   .person .epb { position: absolute; bottom: 42px; left: 6px; ... }
   ```
   Because `.person` is `position: relative` (wf.css:375) and contains the photo (`.ph`,
   `aspect-ratio: 2/3`) **plus** a variable-height `.pbody` text block (name + role, wf.css:378),
   `bottom: 42px` lands inside/over the text area — not on the photo. The offset is also fragile:
   any name that wraps to two lines shifts the badge.

The same fragile `bottom: 42px; left: 6px` rule is shared by `.tag` (the `⤓ inherited` badge,
wf.css:391) used on inherited episode-scope cards — it has the identical problem.

## Goal

The `▸ N eps` badge (and the `⤓ inherited` tag) sit cleanly **over the photo, in its lower-left
corner**, regardless of how many lines the name/role text wraps to — matching the existing
`.gtag` "guest" pill which already overlays the photo's upper-left (wf.css:389).

## Fix

**DOM** (`renderPersonCardHtml`, `MediaDetail.kt`): render the `.epb` / `.tag` badge **inside the
`.ph` photo `<div>`** (both the `<img>` branch and the initials-fallback branch) so it is
positioned relative to the photo, not the card.

**CSS** (`design/app/wf.css`):
- Add `.person .ph { position: relative; }` so the photo is the positioning context.
- Re-anchor the overlay badges to the photo's lower-left:
  ```css
  .person .epb,
  .person .tag { position: absolute; bottom: 6px; left: 6px; ... }   /* was bottom: 42px */
  ```
- Keep the dark translucent pill background (already present) for legibility over artwork; the
  existing `z-index: 1` keeps it above the `<img>`.

No behavioural change — display only. `.gtag` (upper-left) and `.prm` remove button (upper-right)
are unaffected and won't collide with the lower-left badge.

## Scope

- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` — `renderPersonCardHtml` badge
  placement.
- `design/app/wf.css` — `.person .ph`, `.epb`, `.tag` rules (shipped verbatim to the frontend via
  `syncDesignAssets`).
- Mirror the same markup into `design/app/media.html` / `series.html` mockups if they hand-render a
  person card, so the mockup stays the visual source of truth.

## Verification

Open a series detail with a populated cast → **Cast & crew** → Series scope. The `▸ N eps` badge
overlays the bottom-left of each photo; cards with one-line and two-line names both anchor the
badge to the image edge (not the text). Episode scope: inherited cards show `⤓ inherited` in the
same corner.

## Non-goals

- Changing what the badge counts (still TMDB `total_episode_count` from `aggregate_credits`).
- Touching the presence matrix (covered by Phase 80).
