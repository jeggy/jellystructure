# Phase R69 — Player transport chrome: more compact + modern play/pause (FR-RV-PC1)

## Problem
1. When the player controls wake (pause + progress + buttons), the whole transport is **too big** and
   eats a lot of screen. Make it more **compact** — shorter `-10s`, play/pause, `+30s`, Audio & Subs,
   Next buttons, and less top/bottom margin/padding.
2. The **play/pause button looks yellow and ugly**. Make it modern and consistent with the other buttons.

## Findings (all values are hardcoded literals in `screens/PlayerScreen.kt` — none are tokenized in `RaviloDimens`)

### Oversized chrome — the offenders (in `PlayerChrome`, ~`:593-702`, + the button/seek composables)
- **Control block padding** (`:641-645`): the bottom `Column` uses `.padding(horizontal = 48.dp,
  vertical = 36.dp)` — `vertical = 36.dp` is the single biggest waste of vertical space.
- **Seek bar reserves 48dp for a 12dp bar** (`:770`): `Canvas(Modifier.fillMaxWidth().height(48.dp))`
  while the visible bar is only `barH = 12.dp`/`8.dp` (`:765`) — ~36dp of empty space.
- **Button heights**: PlayPause `60/54.dp` (`:811`), Skip `−10s/+30s` `height(46.dp)` + `padding(h=16.dp)`
  (`:839,:844`), Track `Audio & Subs`/`Next` `height(44.dp)` + `padding(h=18.dp)` (`:863,:868`).
- **Title `28.sp`** (`:661`) and inter-element spacers `14/12/20.dp` (`:667,:680,:697`); row gap
  `spacedBy(12.dp)` (`:685`).
- **Bottom scrim `420.dp` tall, alpha 0.86** (`:624,:617`) — darkens a huge slice of the screen, making
  the wake-state *feel* heavy even though it's just a gradient.

### "Yellow" play/pause — what's actually happening
The play/pause fill is `Color.White` with a black glyph (`PlayPauseButton`, `:808-831`) — **not** a yellow
token. The "yellow" comes from two things:
1. **It's the default-focused control** (`:138 var focus = PlFocus.PLAY`), so on wake it always shows the
   skin's **focus ring + glow**, which on the **NOIR** skin are gold: `focusRing 0xFFFFCF6B`,
   `focusGlow 0x80FFCF6B`, `accent 0xFFF5B542` (`theme/Colors.kt:76-96`). A white disc inside a bright
   gold ring/halo reads as a yellow button. (AURORA = purple, MIDNIGHT = teal.)
2. **The `▶`/`⏸` Unicode glyphs** (U+25B6 / U+23F8) can be rendered as **colored emoji** by the Android TV
   system font, overriding `color = Color.Black` with a yellow/orange triangle. Same glyphs at the
   center pause-flash (`:502-504`) and Next-up (`:1162`).
The other transport buttons are neutral (translucent white at rest, solid-white-on-focus), so only
play/pause "pops."

## Goal
A noticeably shorter, lighter transport bar, and a play/pause button that looks modern and consistent
with the rest of the controls on every skin.

## Requirements
### A. Compact the chrome (reduce the literals in `PlayerScreen.kt`)
- Control `Column` padding `vertical = 36.dp` → ~`16–18.dp` (prefer a slightly larger `bottom` than
  `top`) (`:645`).
- Seek `Canvas` `height(48.dp)` → ~`28–30.dp` (`:770`); keep `barH` as-is.
- Button heights: Skip/Track `46/44.dp` → ~`40.dp`, horizontal padding `16/18.dp` → ~`12–14.dp`
  (`:839,844,863,868`); PlayPause `60/54.dp` → ~`50/46.dp` (`:811`).
- Trim title to ~`20–22.sp` (`:661`) and the inter-element spacers (`:667,680,697`) + row gap (`:685`).
- Bottom scrim `420.dp` → ~`260–300.dp` and/or lower alpha (`:624,617`).
- **Consider tokenizing** these into `RaviloDimens` (a `playerControl*` group) so the compact sizing is
  centralized rather than scattered literals.

### B. Modern play/pause
- Replace the `▶`/`⏸` Unicode glyphs with **vector icons** (or force a non-emoji text style) so the glyph
  is never colored by the system emoji font — at the play/pause button, the center pause-flash
  (`:502-504`), and Next-up (`:1162`).
- Rework the resting look so the default-focused play button isn't a stark gold-ringed white disc: e.g.
  use the app **`accentGradient`** (`Colors.kt:107`) or a neutral translucent fill consistent with the
  Skip/Track buttons, with a subtler focus treatment. Ensure it looks good on all three skins
  (AURORA/MIDNIGHT/NOIR), not just gold-on-NOIR.

## Scope
- `ravilo-ui/.../screens/PlayerScreen.kt` (`PlayerChrome`, `SeekRow`/`SeekBar`, `PlayPauseButton`,
  `SkipButton`, `TrackButton`, center pause-flash, Next-up) — sizing + the play/pause restyle.
- `ravilo-ui/.../theme/Dimens.kt` (optional new `playerControl*` tokens), `theme/Colors.kt` (reference
  `accentGradient`; no new yellow).

## Non-goals
- No change to player behavior/transport logic (R44 media keys, seek amounts) — visual/layout only.

## Acceptance
- On wake, the control bar is visibly shorter with tighter top/bottom spacing and a less heavy scrim;
  the buttons are compact.
- The play/pause button reads as modern/neutral (no yellow disc) and matches the other buttons across all
  three skins; the glyph is a crisp icon, never a colored emoji.
