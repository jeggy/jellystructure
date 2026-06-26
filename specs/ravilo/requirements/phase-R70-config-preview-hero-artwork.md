# Phase R70 — Real hero artwork in the config schematic preview (FR-RV-PV1)

> Authored from the design project. Improves the admin config **live preview** introduced with
> **[R28](phase-R28-config-editor-fidelity.md)** and used throughout the channel/hero/rows work
> (**[R52](phase-R52-per-page-hero-carousels.md)**, **[R53](phase-R53-channel-editor-page-padding.md)**).

## Problem
The Ravilo config editor's **Live preview** (the small 16:9 panel that shows the operator how their
home screen will look) renders the hero as a **schematic** — a flat gradient block with placeholder
text. Everything else in the preview is now fairly faithful (channel rail, row tiles), so the gradient
hero stands out as obviously fake and undersells the layout the operator is building. The actual hero
items are real Jellyfin titles with backdrops; the preview should show them.

## Goal
The preview hero shows the **real backdrop artwork** of the configured hero item(s) — so the operator
sees a true-to-life mini Home, including the carousel of the items they picked, instead of a coloured
rectangle.

## Requirements
1. **Render the real backdrop.** The preview hero pulls the selected hero item's **backdrop** (the same
   artwork the TV hero uses, served per R85) and draws it with the production hero scrim/gradient +
   title treatment, scaled into the preview panel.
2. **Reflect the carousel.** If multiple hero items are enabled, the preview reflects the carousel
   (the first item, or a slow auto-advance) and honours the single global **hero height %** (R58) so
   the preview band matches what the TV will show.
3. **Honour live edits.** Adding / removing / reordering hero items, or changing the height, updates the
   preview hero **live** (the editor is already live-preview; the hero just stops being schematic).
4. **Graceful fallback.** If a hero item has no backdrop yet (or artwork is still loading), fall back to
   the existing gradient block for that item only — never a broken image.

## Invariants
- The preview is a faithful miniature of the TV Home; the hero is no longer the one schematic element.
- Uses the same artwork source as the TV (R85); no separate preview image path.

## Out of scope
- Per-channel **page** hero preview accuracy beyond reusing the same renderer (R52) — same mechanism.
- Changing hero behaviour, height range (R58), or auto-advance.

## Mockup
`design/app/ravilo-config.html` (the **Live preview** panel + Hero carousel section) and
`design/app/ravilo-builders.js` (preview renderer). The preview iframe mirrors
`design/ravilo/Ravilo TV.html`'s hero.
