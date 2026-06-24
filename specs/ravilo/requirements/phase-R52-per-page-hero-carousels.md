# Phase R52 — Per-page hero carousels + hero naming (FR-RV-H1)

**Status:** ✓ Done

> Authored from the design project. Revises **[R27](phase-R27-layout-model-extensions.md)**
> (single per-user hero) and the hero builder of
> **[R32](phase-R32-unified-filter-workbench.md)**.

## Problem

Only the **Home** page had a hero carousel. Channels / collections opened straight into
their content rows, with no way to spotlight titles on a channel page. The product also
used the word **"featured"** inconsistently for what is really the **hero carousel**.

## Change

- **Home keeps its hero**, now labelled **"Home hero carousel"**.
- **Every channel / collection page can have its own optional hero carousel**:
  - **Opt-in per channel**, **at most one per page**, **not shared** across pages.
  - Configured in the channel editor's **"Page hero"** section: an enable toggle, a list of
    **hero items** (built with the **shared hero-item builder** — title search, badge,
    tagline, backdrop, clearlogo toggle — the same one Home uses), and **per-carousel**
    **hero height** + **auto-advance**.
- **Ravilo TV** renders the channel hero at the top of that channel's page when configured;
  **← / →** cycles it and **select** opens the focused title's detail. Pages with no hero
  open straight into the rows (unchanged).

## Naming

The concept is **"hero carousel" / "hero item"** everywhere; the word **"featured" is
removed from the UI**:

- Config: **Add hero item** (Home + channel), "Home hero carousel".
- Shared builder modal: **New / Edit hero item**, **Add to hero carousel**.
- Media detail action: **★ Add to hero carousel…** (was "Feature in…").
- Workbench facet reads **In hero / Not in hero** (was Featured / Not featured).

## Hero height clamp

The hero-height range is now **40–100 %** (was 30–100 % in R27) on **both** the Home hero
and channel heroes. Auto-advance default **7 s** unchanged.

## Data model

`HeroConfig` becomes **per-placement**: a `HeroConfig` for Home plus an **optional
`HeroConfig` per channel** (`enabled`, `items`, `heroHeightPct` [40–100], `autoAdvanceSeconds`).
The feed serves the correct hero for the page being opened; absent/disabled → no hero.

## Scope / invariants

- One hero per page; heroes are **not** reused across pages (each page owns its config).
- Hero items reuse the R32 builder verbatim — no parallel item editor.

## Mockup

`design/app/ravilo-config.html` (Home hero + channel **Page hero**),
`design/app/ravilo-builders.js` (shared hero-item builder reused for channel heroes),
`design/ravilo/` (Ravilo TV renders a channel hero — HBO is the wired example).
