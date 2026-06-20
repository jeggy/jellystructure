# Phase R27 — Ravilo layout model: hero height, auto-advance, per-item enable & order (FR-RV27)

**Status:** Done · _depends on R26 (unified DTO). Backend + shared-model only; TV consumption is
downstream (still pending in `:ravilo-ui`)._

## Problem

The R16 mockup (`design/app/ravilo-config.html`) exposes layout controls that have **no backing
field** in the shared `RaviloConfig` (`shared/.../tv/Models.kt`):

- **Hero height** — "How much of the TV screen the banner fills (30–70%)".
- **Hero auto-advance** — carousel interval (e.g. "Every 6 seconds").
- **Per-hero show toggle** and **per-hero order** (the hero list is drag-reorderable with show/hide).

`HeroConfig` currently carries only `item_id` + `override` — no `enabled`, no `order`, no title. The
global hero-height and auto-advance values do not exist anywhere. Until the model can express these,
the editor (R28) cannot offer the controls and the server cannot compose a feed that honours them.

## Current state (as-is)

- `HeroConfig(item_id, override)` — no `enabled`/`order`.
- `ChannelConfig` and `RowConfig` already have `enabled` + `order`. ✓
- `RaviloConfig` has no hero-height or auto-advance fields.
- `RaviloConfigService.DEFAULT_CONFIG` seeds default rows but cannot seed values that don't exist.
- The server composes the home feed in `tv/HomeFeedService.kt`; it has no hero-height/auto-advance to
  emit and does not currently filter/sort heroes by `enabled`/`order`.

## Requirements

### Shared model (`:shared`, `tv/Models.kt`)

1. Extend `HeroConfig`:
   ```kotlin
   data class HeroConfig(
       @SerialName("item_id") val itemId: String,
       val enabled: Boolean = true,
       val order: Int = 0,
       val override: Boolean = false,   // keep existing field
   )
   ```
2. Extend `RaviloConfig` with global hero behaviour:
   ```kotlin
   @SerialName("hero_height_pct")      val heroHeightPct: Int = 56,      // clamp 30..70
   @SerialName("auto_advance_seconds") val autoAdvanceSeconds: Int = 6,  // 0 = no auto-advance
   ```
   Keep the field defaults so older stored JSON decodes cleanly (`ignoreUnknownKeys` already set).

### Backend (jellystructure server)

3. `RaviloConfigService` `DEFAULT_CONFIG` sets `heroHeightPct = 56`, `autoAdvanceSeconds = 6`.
4. Validation (folds into the R26 `PUT` validation): **clamp** `heroHeightPct` to `30..70` and
   `autoAdvanceSeconds` to `0..30`; drop heroes with a blank `item_id`; assign contiguous `order`.
5. `HomeFeedService` composition honours the new fields:
   - Emit `heroHeightPct` and `autoAdvanceSeconds` on the composed home feed so the client renders a
     server-owned hero (Constitution Invariant 4 — *server composes, TV renders*; the TV must not
     keep hard-coded hero height / interval).
   - **Filter** heroes (and channels) by `enabled`, and **sort** by `order`, when composing the feed.
6. If the home-feed DTO does not already carry these, add them to the feed payload type in `:shared`
   (single definition, reused by backend + TV).

## Invariants

- Hero height and auto-advance are **server-owned config**, surfaced through the feed — never
  TV-local constants (Constitution Invariant 4).
- Ranges are validated/clamped **server-side** before persisting.
- New fields have defaults so pre-existing stored configs decode without migration.

## Out of scope

- The editor UI for these controls — sliders, toggles, drag-reorder (**R28**).
- The TV client actually consuming `heroHeightPct`/`autoAdvanceSeconds` in `HeroCarousel` (downstream
  client work, tracked against R10/R24 — the current scope is jellystructure-side only).
- DTO unification (**R26**).
