# Phase R21 — Back navigation + focus polish (FR-RV21)

## Problem
D-pad Back exited the app instead of navigating to the previous screen. Focus transitions between
tiles felt sluggish and lacked TV-appropriate animation character.

## What was built

### Back navigation
Root `Box.onKeyEvent` intercept in `RaviloApp` catches `Key.Back` / `Key.Escape` when
`stack.size > 1` and calls `pop()`. When on the root screen the event falls through and the OS
exits the app normally.

### Focus spring animation
All focusable components (`Tile`, `ChannelCard`, `EpisodeCard`, `RaviloButton`, `SeasonPicker`)
switched from tween-based scale to `spring(dampingRatio = 0.65, stiffness = MediumLow)`. Scale
targets raised to 1.06–1.10× depending on component. Border width animates `0 → 3 dp` via
`animateDpAsState`. Focused state renders a colored drop shadow (using `Modifier.shadow` with
`RaviloColors.focusGlow` as the spot color) to create a TV-appropriate "halo" effect.
