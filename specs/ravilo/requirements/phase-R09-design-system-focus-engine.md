# Phase R09 — Design system + focus engine (FR-RV9)

**Status:** ✓ Done · _the shared foundation every Ravilo screen is built on._

> **Superseded in part by [R30](phase-R30-native-focus-traversal.md):** the manual per-item focus
> engine specified under §"Focus engine (shared)" (`FocusRequester` + `onKeyEvent` movement) was
> replaced by **native Compose focus traversal** (`focusable` + `focusGroup` + `focusRestorer`). The
> *goals* here — predictable left↔right / up↕down order, remembered focus, focused-row scroll-into-view
> — still hold; only the mechanism changed.

## Problem
Every Ravilo screen needs the same skinnable theme, the same focusable components, and one shared
**D-pad/pointer focus engine** — written in common Compose so it runs on Android TV and browser canvas
alike. The HTML prototype in `design/ravilo/` is the visual target.

## Current state (as-is)
- R02 gave us `:ravilo-ui` + a "hello focus" screen. No real theme, components, or reusable focus
  model yet.

## Requirements

### Theme / skins
1. `RaviloTheme` exposing tokens — colors, type scale (Space Grotesk display, Sora UI), spacing,
   radii, focus treatment — for **Aurora** (default), **Midnight**, **Noir**, switchable via a
   `StateFlow<Skin>`. Values mirror `ravilo.css` token sets.
2. Brand: the **jellyfish mark** + "Ravilo" wordmark composable, tinted by the active skin's accent.
3. 10-foot sizing throughout (large type, generous spacing, TV-legible minimums); identical on both
   targets.

### Focus engine (shared)
4. A reusable focus/navigation model over multiplatform Compose (`Modifier.focusable`,
   `FocusRequester`, `onKeyEvent`): predictable left↔right within a row, up↕down between rows; exactly
   one visible focus target; **Back** always meaningful. Platforms feed it events (Android D-pad; Web
   arrow keys + pointer + optional gamepad) — **one** engine implementation.
5. Focus treatment: scale + ring/glow per skin; focused row auto-scrolls into view; focused tile
   centers horizontally in its row.
6. A "remembered focus" rule so returning to a screen restores the last focused element.

### Components
7. Focusable building blocks: `Tile` (poster + landscape variants, badge, progress bar), `ContentRow`
   (titled, lazy, horizontally scrolling), `HeroBanner`/`HeroCarousel`, `ChannelCard` (logo/text),
   `AppBar` (brand + top nav + search affordance + clock/avatar — the clock is **24-hour** time, e.g.
   `14:25`, never 12-hour/AM-PM), `Button` (primary/ghost),
   `EpisodeCard`, `SeasonPicker`, `CastCircle`, `OnScreenKeyboard`. All focus-aware, all skinned.

## Invariants
- **All shared, multiplatform Compose** — no Android-only artifact in `:ravilo-ui`.
- One focus engine; platforms only translate input.
- Tokens map to the `design/ravilo` prototype; both targets look identical.

## Out of scope
- Wiring real screens to data (R10+). Player/image real impls (R14/later).
