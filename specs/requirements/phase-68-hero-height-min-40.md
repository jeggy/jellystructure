# Phase 68 — Hero height minimum: 30 → 40 (FR-HH1)

**Status:** Planned

## Goal

The Ravilo hero carousel height slider in the admin config has a minimum of 30% (of screen height). At 30% the hero is too short to be usable on a TV. Raise the minimum to 40%.

## Files and exact changes

| File | Line | Change |
|------|------|--------|
| `design/app/ravilo-config.html` | `min="30"` on `#hero-h` slider | `min="40"` |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt` | `min="30"` on `#hero-height` slider | `min="40"` |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/RaviloConfig.kt` | `.coerceIn(30, 100)` in `renderPreview` | `.coerceIn(40, 100)` |
| `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/HomeScreen.kt` | `feed.heroHeightPct.coerceIn(30, 100)` | `.coerceIn(40, 100)` |
| `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` | comment `(30..100)` on `heroHeightPct` | `(40..100)` |

The default of `56` is within the new range — no migration needed. Any stored config with `heroHeightPct < 40` will be clamped to 40 by the `coerceIn` calls.

## Non-goals

- No change to the maximum (100 stays).
- No change to the step size (1% steps stay).
