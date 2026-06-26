# Phase R65 — Navigate-up reveals the row title and clears the app bar (FR-RV-N1)

> Authored from the design project. Refines the R35 home sizing / R45 entry-scroll behaviour and the
> shared `AppBar` overlay (see **[R79](phase-R79-detail-screen-top-navbar.md)** for the bar itself).

## Problem
On Home / Browse / Channel / Discover the `AppBar` is an overlay pinned to the top of the screen. When
the viewer D-pads **up** from the first content row toward the top of the page, two things read wrong:
the **row title** the viewer just left can sit tucked under the bar (or never gets re-revealed once the
page has been scrolled), and the **app bar stays in its scrolled-solid state** even though focus is
returning to the top — so the transition back to the hero/top feels abrupt and the bar competes with
the content it overlays.

## Goal
Make the up-navigation read cleanly: moving focus up **reveals the row's title** (the page scrolls
just enough that the focused row's header clears the bar), and as focus reaches the top the **app bar
clears** back to its transparent state, framing the hero rather than sitting solid over it.

## Requirements
1. **Reveal the row title on up-focus.** When a content row gains focus from below, the bring-into-view
   inset must account for the bar height so the row's **title** (not just its tiles) is visible — i.e.
   reuse Home's `topInset = appBarHeight + ~34dp` reasoning on every screen that overlays the bar, so a
   row title is never hidden under the 60dp bar after navigating up into it.
2. **Clear the bar at the top.** Drive the bar's transparent↔solid state from the scroll position
   (already `scrolled` on Home/Browse via R62): at the very top the bar is **transparent** (cleared);
   once scrolled past the hero it goes solid. Returning focus to the top must reset it to transparent,
   not leave it stuck solid.
3. **Consistent across screens.** Apply the same reveal + clear behaviour to Home, Browse grids,
   Channel pages and Discover — the screens that use the overlay bar. (`ChannelBar` variant follows the
   same rule.)
4. **No focus-jump.** Revealing the title is a minimal scroll (R42/R45): already-visible rows must not
   re-center, and focus must not jump to Home or the bar (that bug is owned by R67).

## Invariants
- Per-screen overlay pattern unchanged (R79) — this tunes the inset + the scrolled-state reset, not the
  bar's structure.
- Minimal-scroll rule (R42/R45) holds: fully-visible content never moves.

## Out of scope
- The detail-screen bar (R79) and the player (no bar).
- Focus-restore / Home-jump bug (R67) and genre reloads (R67).

## Mockup
`design/ravilo/ravilo-app.js` (content-row headers + the persistent `appbar` overlay; the row-title
reveal and the top-of-page transparent bar), `ravilo.css` (`.crow-head`, app-bar scrolled state).
