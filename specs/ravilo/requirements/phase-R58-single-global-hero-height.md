# Phase R58 — Single global hero height + auto-advance (drop per-channel hero settings) (FR-RV-H2)

> Authored from the design project. Revises **[R52](phase-R52-per-page-hero-carousels.md)**
> (which gave each channel hero its own height + auto-advance) and
> **[R27](phase-R27-layout-model-extensions.md)**.

## Problem

R52 let **every channel hero carry its own `heroHeightPct` and `autoAdvanceSeconds`** alongside
the Home hero's. In practice both are **look-and-feel constants** a household wants to set once —
multiple per-page values just add fiddly config and let channel heroes drift from Home.

## Change

- **One hero height *and* one auto-advance config.** The **Home hero** settings (height
  40–100 %, auto-advance interval) are the **single source** and apply to **every** hero — the
  Home page hero **and** any channel/collection page hero.
- The channel editor's **Page hero** section **drops both its height slider and its
  auto-advance select**; it keeps only the **hero items**, plus a note that height and
  auto-advance follow the global Home hero settings.
- **Ravilo TV** renders a channel hero at the **same height and cadence** as the Home hero (no
  per-channel values passed).

## Data model

- Remove **`heroHeightPct`** and **`autoAdvanceSeconds`** from the **per-channel** `HeroConfig`;
  both now live **only** at the layout level (the Home hero). Channel heroes keep just
  `enabled` + `items`.

## Scope / invariants

- Single height **and** single auto-advance for all heroes; channel heroes never override
  either.
- No change to the opt-in-per-channel model, the shared hero-item builder, or the 40–100 %
  clamp / 7 s default — only the per-channel **height** and **auto-advance** controls are
  removed.

## Mockup

`design/app/ravilo-config.html` + `design/app/ravilo-builders.js` (channel Page-hero loses the
height slider **and** auto-advance select; keeps the items list), `design/ravilo/ravilo-app.js`
(`renderCategory` builds the channel hero at the Home hero height + cadence).
