# R62 — Ravilo TV: AppBar scroll-aware background (FR-AB1)

**Status:** Planned

## Goal

The AppBar overlays the scroll content with only a top-to-transparent gradient background. When the page is scrolled down, nav item labels and the search/profile buttons overlap media titles, artwork, and row titles — unreadable and visually messy. When at the very top, the gradient-to-transparent look is intentional and should remain.

## Current state

`AppBar.kt` uses a fixed vertical gradient:
```kotlin
Brush.verticalGradient(
    0f to Color(0x8C000000),  // semi-transparent at top
    1f to Color.Transparent,  // transparent at bottom
)
```
There is no `scrolled` parameter. No screen passes scroll state to the AppBar.

## Target behaviour

- **At scroll top** (first item fully visible, zero offset): gradient background as today.
- **Scrolled down** (any pixel of scroll): solid `colors.surface` at ~0.95 alpha. Transitions smoothly between the two states with a short animation (150–200 ms).
- `derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }` computes the boolean without recomposing the entire screen on every scroll pixel.

## Implementation

### `AppBar.kt`

Add `scrolled: Boolean = false` parameter. Animate a background color:
```kotlin
val solidBg by animateColorAsState(
    if (scrolled) colors.surface.copy(alpha = 0.95f) else Color.Transparent,
    animationSpec = tween(180), label = "appBarBg"
)
```
Apply as a `background(solidBg)` modifier on the AppBar box **beneath** (or instead of) the gradient overlay. When `scrolled = false`, `solidBg` is transparent so the gradient (applied separately) shows through. When `scrolled = true`, the solid color dominates.

Implementation detail: the gradient adds a dark-to-transparent vignette at top; the solid layer makes the bar opaque when scrolled. A two-layer approach (`Box { gradient layer; solid layer }`) or a single `Brush` that switches via `AnimatedContent` both work.

### `HomeScreen.kt` and `DiscoverScreen.kt`

Both already have `listState` in scope at the `AppBar` call site. Add:
```kotlin
val appBarScrolled by remember { derivedStateOf {
    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
} }
// …
AppBar(scrolled = appBarScrolled, …)
```

`BrowseScreen.kt` will also need this when AppBar is added (R60) — `LazyVerticalGrid` exposes `state.firstVisibleItemIndex` via `LazyGridState`, same pattern.

### `SeriesDetailScreen.kt` / `MovieDetailScreen.kt`

Detail screens do not scroll in a way that conflicts with the AppBar (their AppBar is always at the top with a dark backdrop). Default `scrolled = false` is correct for them.

## Files

| File | Change |
|------|--------|
| `ravilo-ui/.../components/AppBar.kt` | Add `scrolled: Boolean = false`; animate background color |
| `ravilo-ui/.../screens/HomeScreen.kt` | Derive `appBarScrolled` from `listState`; pass to `AppBar` |
| `ravilo-ui/.../screens/DiscoverScreen.kt` | Same |

## Non-goals

- No change to AppBar height or layout.
- No blur/frosted-glass effect — solid `surface` color at 0.95 alpha.
- No scroll-dependent font-size or padding changes.
