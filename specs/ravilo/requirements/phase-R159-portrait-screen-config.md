# Phase R159 — Portrait screen config: per-orientation overrides (first: hero height) (FR-RV-PT1)

## Goal
Ravilo gets two automatically-detected display modes: **landscape** (TVs, desktop web — the default and
primary environment) and **portrait** (a phone held upright, a portrait browser window). The config
editor gains a small **"Portrait screen"** section holding **optional overrides applied only in
portrait** — the first (and this phase's only) setting: **hero carousel height, 20–100 %** (a hero tuned
to 80 % of a TV is absurd on a phone; portrait wants a much smaller number). Landscape behaviour is
untouched.

("Portrait screen", not "Mobile config" — the mode is derived from the viewport, not the device class;
a resized browser window follows it too.)

## Current state (verified in code)
- **No orientation signal exists.** `LocalCompact` (`theme/Dimens.kt:13`, provided in
  `RaviloApp.kt:304-309`) is **width-based** (`width < 600dp`) — R145's phone reflow — not orientation;
  nothing anywhere compares height vs width. The needed input, `LocalWindowInfo.current.containerSize`,
  is already read on every target (`HomeScreen.kt:120`).
- **Hero height plumbing** (the pattern the override extends): `RaviloConfig.heroHeightPct`
  (`Models.kt:455`, default 56) → clamped 40–100 in `RaviloConfigService.normalize()` (`:115`) →
  mirrored onto the feed (`HomeFeed.heroHeightPct`, `Models.kt:182`; copied at
  `HomeFeedService.kt:98,125`) → applied by `HomeScreen.kt:120-122` / `ChannelScreen.kt:183-184`
  (`containerH * pct/100`). Detail-screen heroes are full-bleed and ignore it.
- **Config editor**: sections render in `RaviloConfig.kt:411-418` (`renderPair → renderHeroes →
  renderChannels → renderRows → renderDiscover → renderBehaviour → renderPreview`); the hero-height
  slider (min 40 / max 100) lives in `renderHeroes` (`:638-640`, handler `:690-694`); the live preview
  is **inline HTML** re-rendered from `currentConfig` (`renderPreview`, `:2127-2155`) — not an iframe.

## Requirements

### A. Orientation detection (app)
1. New `LocalPortrait` composition local beside `LocalCompact` (`RaviloApp.kt:304-309`):
   `portrait = containerSize.height > containerSize.width` (recomputed on resize/rotation). Width-based
   `LocalCompact` is unchanged — compact controls *sizing*, portrait controls *these overrides*; a
   portrait phone is typically both.

### B. Config model
1. `RaviloConfig` gains an optional block: `portrait: PortraitConfig? = null` with
   `@SerialName("hero_height_pct") heroHeightPct: Int? = null`. Null block / null field = no override.
2. Backend clamp in `RaviloConfigService.normalize()`: portrait hero height **20–100** (deliberately
   allowing far smaller than landscape's 40 floor).
3. Mirror onto the feed: `HomeFeed.portrait_hero_height_pct: Int?` (copied like `heroHeightPct`), so the
   app keeps reading everything from the one feed payload.

### C. Applying it (app)
1. `HomeScreen` / `ChannelScreen` hero sizing picks
   `if (LocalPortrait && feed.portraitHeroHeightPct != null) portraitHeroHeightPct else heroHeightPct`.
   Both values are **server-pushed**; the client only selects by its own viewport — presentation
   selection, not derived state (same class as `uiDensity` tile scaling), so the constitution holds.
2. Rotating / resizing across the portrait boundary re-applies live (state-driven recomposition — no
   refetch needed; both numbers are already on the feed).
3. No other surface changes: detail heroes stay full-bleed; rows/tiles unaffected.

### D. Config editor — "Portrait screen" section
1. New nav entry + section **after Behaviour** (`renderPortrait()` after `RaviloConfig.kt:417`): a card
   explaining the two modes in one line, containing the first override — an **enable toggle** ("Override
   hero height in portrait") + a **20–100 % slider** (disabled/greyed until toggled; toggled-off saves
   `null`). Scope-aware like every other section (global vs per-user override, R51).
2. The inline preview (`renderPreview`) gains a small **portrait thumbnail** beside the landscape
   schematic when the override is set, showing the hero at the portrait height.
3. The section is explicitly the **home for future portrait-only options** (copy says so) — one place,
   not per-setting scattering.

### E. i18n
No TV-side strings needed (behaviour is silent); editor strings are admin-side English like the rest of
the config editor.

## Scope
- Shared: `PortraitConfig` + `RaviloConfig.portrait` + `HomeFeed.portrait_hero_height_pct`
  (`Models.kt`).
- Backend: `RaviloConfigService.normalize()` clamp; `HomeFeedService` feed copy.
- App: `LocalPortrait` (`RaviloApp.kt`), hero-height selection in `HomeScreen`/`ChannelScreen`.
- Admin FE: `RaviloConfig.kt` — nav button, `renderPortrait()`, `collectConfig`, preview thumbnail.
- Design: `design/app/ravilo-config.html` portrait card (to be added to the mockup with this phase).

## Non-goals
- No portrait overrides beyond hero height this phase (the section is the container; each future
  override is its own decision).
- No per-device or per-target (phone vs web) configs — one portrait block per layout (global/user),
  applied wherever the viewport is portrait.
- No landscape clamp change (40–100 stays), no detail-hero changes, no tile/row reflow work (R145 owns
  compact sizing).

## Acceptance
- With the override set to 30 %: the phone app in portrait shows a 30 % hero; rotating to landscape
  instantly shows the configured landscape height; a TV never changes.
- With the toggle off, portrait uses the landscape value exactly as today (config carries no portrait
  block).
- Editor: slider ranges 20–100, disabled until enabled, round-trips through save/reload in both global
  and per-user scopes; values outside range are clamped server-side.
- A portrait browser window on the web target follows the override; resizing across square flips it
  live.
