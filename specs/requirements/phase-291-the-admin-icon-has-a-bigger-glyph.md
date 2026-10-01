# Phase 291 — The admin's icon has a bigger glyph

> Owner, 2026-10-01: *"Let's go with R0 and A0, but for A0, let's make it a little bigger, so the icon within is
> bigger."* And: *"Jellystructure is not bare."*

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/App Icons - Flat Directions.html` (A0). Not dev-reviewed.
**Numbering:** first written as 290; the dev side took 290 the same day (*an album's year is the year it first came
out*), so this is **291**, checked free against `main` (tree `ef52889`) on 2026-10-01.

**Changes:** Phase 264's drawing (FR-264-1 and build note 1). Ravilo's side is R341.

## Requirements

**FR-291-1 — The quartet-and-play glyph fills 59 % of the tile, up from 51 %.** On the 100-unit tile the glyph's
transform goes from `translate(18 18) scale(.64)` to **`translate(13 13) scale(.74)`**. It stays centred: the inset
goes from 18 % to 13 %.

Everything else is unchanged:
- the corner radius
- the gradient `#b15cd0 → #7b6ef0 → #00a4dc`
- the white glyph, its half-tone squares, the outlined fourth square and the play triangle

**FR-291-2 — Everywhere the tile is drawn.** The mark appears in these places, and they must stay one drawing:
- `Shell.kt`'s `brand-mark`, in the sidebar and the top bar
- `Login.kt`
- `favicon.svg`, `favicon.ico` (16/32/48) and `apple-touch-icon.png` (180, on its `#0b0d14` ground), all from 264
- the info site's nav mark and favicon (264's item on the deployment directory)

The design mockups take the same transform: `app/app-shell.js` `BRAND()` and `app/login.html`.

**FR-291-3 — The admin keeps its tile everywhere, favicon included.** Ravilo's tab icon goes bare (R341). The admin's
square tile beside Ravilo's round jellyfish is what tells the two tabs apart, so the admin never drops its tile.

## Non-goals

- Colours, the radius, the drop shadow under the sidebar mark: none of them change.
- Ravilo's icons (R341).

## Acceptance

1. **Sidebar, top bar, login and the browser tab** all show the bigger glyph, as one drawing.
2. At 16 px the four squares stay separate and the play triangle is visible.
3. A Ravilo tab and an admin tab side by side: a bare jellyfish and a square tile.

## Open questions (for the dev review)

The code review comes later. These are left for it to answer.

1. **16 px:** at 16 px the glyph's 6-unit outline on the fourth square is under 1 px. Does the outlined square still read in `favicon.ico`'s 16 px frame at the new size, or does that frame need its own heavier stroke?
2. **The apple-touch icon:** at 13 % inset, does `apple-touch-icon.png`'s tile (inset on `#0b0d14` so iOS's rounding doesn't clip it) need its own inset re-checked?
3. **The info site:** the info site lives outside this repository (264). Who re-renders its copy, and when?
