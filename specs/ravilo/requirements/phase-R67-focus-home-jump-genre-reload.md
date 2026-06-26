# Phase R67 — Stop focus jumping to Home + stop needless genre reloads (FR-RV-N2)

> Authored from the design project. Follow-up to **[R40](phase-R40-instant-back-navigation.md)**
> (instant back / screen-store retention) and **[R30](phase-R30-native-focus-traversal.md)** (native
> focus traversal).

## Problem
Two related regressions make navigation feel unreliable:

1. **Focus jumps to Home.** After certain transitions — returning from a detail page, or a live config
   push (R33) re-rendering a screen — focus lands on the **Home** nav tab / top of the app rather than
   being restored to the row + tile the viewer left. On a 10-foot UI that silent jump is disorienting:
   the viewer presses Back expecting to be where they were and instead has to re-find their place.
2. **Needless genre reloads.** Opening a Channel / category (genre-filtered) page **re-fetches** its
   feed every time it is entered, even when nothing changed since the last visit, showing a loading
   skeleton for content that is already cached. This both costs a round-trip and triggers the focus
   reset that feeds bug (1).

## Goal
Returning to a previously-visited screen **restores focus** to the exact row/tile that had it, with no
jump to Home, and a re-entered Channel/category page shows its **cached** content instantly (silent
background refresh only when stale) — no skeleton, no reload-induced focus reset.

## Requirements
1. **Persist + restore focus per screen.** Each retained screen (R40 keeps the screen + its store)
   remembers its focused `(row, column)` and restores it on re-entry / re-render, instead of defaulting
   to row 0 / the nav bar. A config-push re-render (R33) must re-apply the remembered focus, not reset
   to Home.
2. **Don't reset focus to Home on push.** A live `config_changed` refresh re-pulls data but must keep
   the viewer's current destination and focus; Home is only the focus target when the viewer is
   actually on Home.
3. **Cache category/genre feeds.** A Channel/category page reuses its cached feed on re-entry (same
   retention as R40); refetch happens **in the background** and patches in place only if the data
   actually changed — no teardown-to-skeleton for an unchanged page.
4. **Guard the empty/initial case.** First-ever entry still loads normally; the no-jump rule applies to
   *re-entry* and *re-render*, not the cold first paint.

## Invariants
- Back / re-entry is instant (R40) and lands where the viewer was — never silently on Home.
- Renders server-pushed state; the cache is a retention optimization, not a parallel source of truth.

## Out of scope
- The up-navigation reveal / app-bar clear (R65).
- The detail-screen snap-to-top (R72) — that is intended focus behaviour on detail, not a jump bug.

## Source references
- `ravilo-ui/.../RaviloApp.kt` (dispatch, screen retention, config-push handling),
  the Channel / Browse screen stores, `ravilo-ui/.../focus/*` (focus restore).
  Mockup parallel: `design/ravilo/ravilo-app.js` (`go()` / `view` retention, `focusRowByIndex`).
- Related: **R40** (instant back), **R30** (native focus), **R33** (live config push).
