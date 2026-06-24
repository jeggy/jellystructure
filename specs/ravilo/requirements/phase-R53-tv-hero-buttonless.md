# R53 — Ravilo TV: button-less hero carousel (whole-card click → detail, dots on the right) (FR-RH1)

**Status:** ✅ Done — `HeroCarousel` is now one focusable surface (`dpadFocusable` on the hero box):
select → `onOpenDetail`, Left/Right page the carousel cyclically, Up → app bar, Down → content; the 3
buttons + their callbacks are gone (`HomeScreen` passes `onOpenDetail = onItemSelect`). Page dots moved to
a bottom-right overlay (active = accent pill). Full-bleed focus shown as a subtle inset ring (no scale).
The design mockup (`ravilo-app.js`/`ravilo.css`) was synced to the button-less hero too.
**Depends on:** R10 (Home / hero), R13 (detail), R42/R47 (draw-only focus), R45 (hero re-frame)

## Goal

Simplify the Home hero carousel: **remove all three action buttons** (Play · More Info · + My List).
The **whole hero becomes one clickable surface** — selecting it opens the title's **detail page**, and
**Left/Right pages the carousel** (previous/next item). Move the **page dots to the right side** of the
hero (they currently sit below the now-removed buttons).

## Current state

`ravilo-ui/.../components/HeroCarousel.kt`:
- Three `RaviloButton`s — Play (`onPlay`), More Info (`onMoreInfo`), + My List (`onMyList`) — in a Row.
  The **Play button is the hero's focus entry** (`focusRequester`); Left/Right move between the buttons
  and page the carousel only at the row's edges.
- The **page dots** are the last element in the bottom-start body column, **below the buttons**
  (inactive 6 dp → active 24 dp pill).
- `HomeScreen` wires `onPlay = onItemPlay`, `onMoreInfo = onItemSelect` (→ detail), `onMyList = {}`.

## Target behaviour

- **No buttons.** Remove the action Row and the `onPlay` / `onMyList` params. Keep one
  `onOpenDetail` callback (= `onItemSelect`, the detail page).
- **Whole hero is the focusable.** Put `dpadFocusable` on the hero surface with the passed-in
  `focusRequester` (the existing `heroFR`):
  - `onSelect` → `onOpenDetail(active.item)` — opens **detail** for the current slide (movie **and**
    series both go to detail; no direct Play from the hero anymore).
  - `onLeft` → previous slide, `onRight` → next slide — **cyclic** (wrap at the ends) so a press always
    switches, since a full-bleed hero has no horizontal neighbour. Each manual change resets
    auto-advance (`resetTick`).
  - `onUp` → app bar (existing `onUp`); `onDown` → first content row (native focus / `columnFR`, as today).
  - Keep R45: when the hero regains focus, the list snaps to top so the hero re-frames.
- **Focus treatment:** the hero is full-bleed, so do **not** apply the tile scale (R42/R47) — that would
  scale the whole backdrop. Indicate focus subtly (e.g. a faint inset ring/elevated dots), consistent
  with "always exactly one visible focus target" (constitution) without a viewport jump.
- **Dots on the right.** Move the page-dots out of the bottom-start body column to a **bottom-right**
  overlay, matching the design `.hero-dots { position:absolute; right:64px; bottom:92px }` (inactive
  ~12 dp circle, **active ~34 dp pill**, accent). Dots still reflect `activeIndex` and auto-advance.

## Knock-on changes

- `HomeScreen`: drop `onPlay`/`onMyList` from the `HeroCarousel(...)` call; pass `onOpenDetail =
  onItemSelect`. `onItemPlay` may become unused by the hero (still used by rows) — leave row wiring intact.
- The carousel's auto-advance, live-config resize guard (R33) and empty-list guard are unchanged.

## Non-goals / invariants

- **Server-pushed state only** — the hero renders the feed's heroes; no derived state.
- **D-pad-first** — one clear focus target; Back is unaffected; never strand focus.
- Movies + series both open **detail** from the hero (series resume/Play still lives on the detail screen).

## Mockup

`design/ravilo/Ravilo TV.html` + `ravilo.css` `.hero` / **`.hero-dots` (already right-aligned)** +
`ravilo-app.js` `setHero`/dot handling. **Done:** the mockup's `.hero-actions` buttons were removed and
replaced with a focusable `.hero-hit`/`.hero-cta` whole-hero surface (`data-hero` → detail, Left/Right
pages the carousel), so design and app match.
