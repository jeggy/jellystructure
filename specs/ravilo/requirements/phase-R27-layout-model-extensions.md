# Phase R27 — Layout model extensions (FR-RV27)


## What was built

New fields on `RaviloConfig` / `HeroConfig` / `HomeFeed`:

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `HeroConfig.enabled` | `Boolean` | `true` | Toggles hero section on/off |
| `HeroConfig.order` | `Int` | `0` | Sort position among feed sections |
| `RaviloConfig.heroHeightPct` | `Float` | `0.55` | Hero panel height as fraction of viewport, clamped `[0.3, 1.0]` |
| `RaviloConfig.autoAdvanceSeconds` | `Int` | `7` | Hero auto-advance interval; `0` = manual only |

`HomeFeedService.buildFeed()`:
- Filters channels and rows by `enabled = true`.
- Sorts channels and rows by their `order` field before emitting.
- Passes `heroHeightPct` and `autoAdvanceSeconds` through in the `HomeFeed` response so the client
  doesn't need to fetch config separately just for layout parameters.

`HeroCarousel` in `:ravilo-ui` reads `autoAdvanceSeconds` from the feed and starts a `LaunchedEffect`
ticker; setting to `0` disables auto-advance.
