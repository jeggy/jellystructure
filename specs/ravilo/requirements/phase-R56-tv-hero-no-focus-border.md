# R56 — Ravilo TV: remove focus border from hero carousel (FR-RHB1)

**Status:** Planned

**Depends on:** R53 (button-less hero — added the inset ring this phase removes)

## Goal

R53 added a subtle inset focus ring to the hero carousel as the sole visual focus
indicator. The user finds it distracting and wants it **fully removed**. The hero should
remain D-pad-focusable and navigable as-is; only the rendered border goes away.

## Current state

`HeroCarousel.kt` (added in R53, lines ~236–242):

```kotlin
var focused by remember { mutableStateOf(false) }

// inside the hero Box, at the very end:
if (focused) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(6.dp)
            .border(3.dp, colors.focusRing, RoundedCornerShape(14.dp)),
    )
}
```

The `dpadFocusable` modifier on the hero box drives `focused`:
```kotlin
.dpadFocusable(
    focusRequester = focusRequester,
    onFocused = { focused = true },
    onBlurred = { focused = false },
    ...
)
```

`design/ravilo/ravilo.css` (added in R53):
```css
.hero-cta { ...; border: 3px solid transparent; transition: border-color .15s var(--ease); }
.hero-cta.focused { border-color: var(--ring); }
```

## Target behaviour

No visible focus ring on the hero under any state. The hero is still:
- D-pad focusable (the `dpadFocusable` modifier stays, with all its callbacks intact).
- Selectable with `Select`/`Enter` → opens detail.
- Pageable Left/Right (cyclic).
- Accessible to `onUp` (app bar) and to `onDown` (first content row).

The page dots (always visible, accent-coloured for the active slide) remain unchanged and
continue to provide carousel-state context. They are not a focus indicator, but a
sufficiently legible carousel affordance for a full-bleed surface where the hero's spatial
position in the layout is unambiguous.

## Constitution note

The Ravilo constitution (§Focus) currently reads:
> "Every interactive element is focusable; there is always exactly one visible focus
> target with a clear focused treatment (scale + ring/glow)."

This change introduces a **deliberate exception** for the full-bleed hero: it has no
visible focus treatment. The rationale is that the hero spans the full viewport width and
is the first focusable element at the top of the Home screen — its focus state is
contextually obvious from the D-pad position. The constitution should be updated to note
this exception when R56 is implemented.

Proposed constitution addition (§Focus):
> **Exception — full-bleed hero:** The hero carousel on the Home screen has no visible
> focus ring by design. Its top-of-screen position is contextually unambiguous; a ring
> would cover the backdrop art for a cosmetic gain the user finds distracting.

## Changes

### `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/HeroCarousel.kt`

1. Delete `var focused by remember { mutableStateOf(false) }`.
2. Remove `onFocused = { focused = true }` and `onBlurred = { focused = false }` from the
   `dpadFocusable(...)` call. All other callbacks (`onLeft`, `onRight`, `onUp`, `onSelect`)
   stay exactly as-is.
3. Delete the `if (focused) { Box(Modifier.matchParentSize()...) }` block entirely.

No other changes to `HeroCarousel.kt`.

### `design/ravilo/ravilo.css`

Remove the focused-border rule from `.hero-cta`:
- Change `.hero-cta { ...; border: 3px solid transparent; transition: ...; }` →
  remove the `border` and `transition` properties from `.hero-cta`.
- Delete `.hero-cta.focused { border-color: var(--ring); }`.

The `.hero-cta` class itself (position/inset/z-index) stays; only the border rules go.

### `specs/ravilo/constitution.md`

Add the exception paragraph under §Focus as described above.

## Non-goals

- No other focusable elements are affected.
- The page dots are not modified.
- No alternative focus indicator is added — the removal is deliberate and final.
