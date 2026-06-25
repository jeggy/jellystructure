# Phase 70 — Brand mark "Quartet Play" + app logo wiring (FR-BR1)

> Note: authored from the design project without the full latest `specs/` tree in
> context — slot into the phase index where it fits; the FR code (FR-BR1) is the stable
> reference.

## Goal

The app shipped a **placeholder** logo: a CSS-drawn gradient rounded-square with an
L-shaped corner bracket (`.glyph::after`), injected in three places (sidebar, mobile
topbar, login). Replace it with a real, intentional brand mark and wire that mark in
everywhere the placeholder lived — with no new color tokens and no layout change.

## The mark — "Quartet Play"

A 2×2 grid of rounded tiles where the fourth (bottom-right) slot is an **outline tile
holding a play triangle**. It fuses the two ideas the product is about:

- **Structure** — the 2×2 of tiles reads as an organized library grid (the top-left
  tile solid, the top-right / bottom-left at 50% opacity for depth).
- **Playback** — the open slot resolving into a play triangle ties the organizer to the
  Jellyfin media it feeds.

Chosen over five other directions (Lattice, Cascade, Bracket, J-Block, Playframe) and
nine play×structure hybrids explored in `design/app/Jellystructure Logo.html`.

### Geometry (100×100 glyph grid, white-on-gradient)

```
tile TL   rect x10  y10  w35 h35 rx9   fill #fff
tile TR   rect x55  y10  w35 h35 rx9   fill #fff opacity .5
tile BL   rect x10  y55  w35 h35 rx9   fill #fff opacity .5
slot BR   rect x55  y55  w35 h35 rx9   fill none stroke #fff stroke-width 6
play      path M68 64 L84 72.5 L68 81 Z   fill #fff
```

As an **app icon**: a `rx="23"` rounded-square filled with the Aurora gradient, with the
white glyph placed via `translate(18 18) scale(.64)`.

## Color

Uses the **existing** accent system only — no new tokens. The gradient is the same three
stops as `--grad`: `#b15cd0` (magenta-violet) → `#7b6ef0` (indigo, at 52%) → `#00a4dc`
(jellyfin blue), on a top-left→bottom-right axis. The mark is theme-independent (gradient
fill + white glyph) so it reads on both Light and Dark surfaces.

## Wordmark

`Jellystructure` in **Space Grotesk 600**, `-0.02em` tracking, set as one word with the
`Jelly` portion in the gradient (background-clip) and `structure` in `--ink`. The wired-in
chrome (sidebar/topbar/login) keeps the wordmark in plain `--ink` next to the mark; the
gradient-split wordmark is for marketing lockups (login subtitle, OG image).

## Implementation (mockups)

- **`design/app/app-shell.js`** — added a `BRAND(size)` helper returning the self-contained
  SVG (own `<linearGradient>` id per call so multiple instances don't collide). Both the
  sidebar logo (`BRAND(30)`) and mobile topbar (`BRAND(24)`) now emit it; the
  `<span class="glyph">` placeholders are gone.
- **`design/app/app.css`** — removed `.app-side .logo .glyph` / `.tb-logo .glyph` rules and
  their `::after` brackets; replaced with `.brand-mark` sizing + a `drop-shadow()` filter
  (the rounded corners + gradient now live in the SVG).
- **`design/app/login.html`** — inline SVG mark (40px, `rx="30"`) replaces the placeholder
  span; `.login-brand .glyph` CSS replaced with `.brand-mark`.

## Asset set

`design/app/Jellystructure Logo.html` is the reference sheet — app-icon sizes (512/256/128/64),
favicon (32/16), monochrome variants (on-light, knockout, glyph-only), dark + light lockups,
login mark, and a 1200×630 social/OG image. All derive from the one glyph above.

## Scope / invariants

- **No new color tokens.** Reuses `--grad`'s three stops; nothing in `wf.css` changes.
- **Theme-independent.** The mark is gradient + white glyph, valid on Light and Dark.
- **Frontend note (per the production-CSS invariant):** the real frontend would render the
  same SVG (or a static asset built from it); the geometry above is the source of truth.
  No `<lockdata>`/scraper interaction — this is pure chrome.

## Mockup

`design/app/app-shell.js`, `design/app/app.css`, `design/app/login.html` (wired);
`design/app/Jellystructure Logo.html` (exploration + asset set).
