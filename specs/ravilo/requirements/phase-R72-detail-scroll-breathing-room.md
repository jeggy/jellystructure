# Phase R72 — Detail-screen scroll breathing room + Play/Resume snaps to top (FR-RV-D1)

> Authored from the design project. Refines the Movie/Series detail layout from
> **[R34](phase-R34-detail-layout-content-size.md)** and the entry-scroll/focus behaviour from
> **[R45](phase-R45-entry-scroll-focus-restore.md)** / **[R47](phase-R47-detail-focusable-draw-only-scale.md)**.

## Problem
On the Movie/Series detail screen two things feel cramped/abrupt:

1. **No breathing room when scrolling.** The content rows below the hero (Cast & Crew, More Like This,
   season picker / episode rail) butt right up against the bring-into-view edge — there's too little
   padding above a row as it scrolls into view, and the bottom of the page ends abruptly, so the page
   feels tight and the focused row title can sit too close to the top edge.
2. **Play/Resume doesn't re-frame the hero.** When focus returns up to the primary **Play / Resume**
   action, the page doesn't reliably **snap back to the top**, so the viewer is left mid-scroll with a
   half-framed hero instead of the full-bleed hero the detail screen is designed around.

## Goal
The detail page scrolls with comfortable spacing (rows reveal with breathing room above; the page has
end padding), and focusing **Play / Resume** snaps the scroll to the top so the full hero re-frames.

## Requirements
1. **Breathing room above rows.** Increase the detail bring-into-view top inset / row top-padding so a
   row scrolling into view clears the top edge (and, with R79, the overlay app bar) with comfortable
   space above its title — not flush to the edge.
2. **End padding.** Add bottom padding after the last content row so the page doesn't end abruptly and
   the final row can scroll up into a comfortable reading position.
3. **Play/Resume snaps to top.** When the actions row (Play / Resume / + My List) gains focus — on entry
   and on return-up — snap the scroll to the top so the full-bleed hero re-frames (the R45/R79 actions
   `onFocusChanged` snap behaviour, made reliable on detail). This is intended detail behaviour, not the
   focus-jump bug (R67).
4. **No regression to focus draw-only scale.** Spacing changes must not reintroduce the R42/R47 viewport
   jump — fully-visible focusables don't move; only the scroll inset/padding changes.

## Invariants
- Detail opens at the top with the full hero (R34); Back stays single-stage (R79).
- Minimal-scroll / draw-only focus (R42/R43/R47) preserved.

## Out of scope
- The Home/Browse up-navigation reveal (R65) and the focus-to-Home bug (R67).
- Detail content itself (cast R81, audio/subtitle flags R75/R78) — layout/scroll only.

## Mockup
`design/ravilo/ravilo-app.js` (`renderDetail` — hero + Cast & Crew / related rows + actions),
`ravilo.css` (`.ddt`/detail spacing, `.dactions`). Code: `ravilo-ui/.../screens/MovieDetailScreen.kt`
+ `SeriesDetailScreen.kt` (bring-into-view spec, actions `onFocusChanged` snap).
