# Phase R72 — Detail-screen scroll breathing room + Play/Resume snaps to top (FR-RV-DS1)

## Problem
On a Movie/Series detail page:
1. Navigating with the D-pad scrolls the newly-focused element **flush to the screen edge** — buttons/rows
   sit hard against the top/bottom with no padding. We want a little extra scroll so there's breathing
   room around the focused element, for **all** focusables (season picker, episode rail, cast, related,
   action buttons).
2. Focusing the **Play/Resume** button should scroll **all the way to the top** so the full backdrop +
   description are visible again — exactly like initial entry to the detail page. Right now it often
   doesn't.

## Findings
- Both detail screens scroll a plain `verticalScroll` Column (every child always composed) —
  `MovieDetailScreen.kt:95,109`, `SeriesDetailScreen.kt:133,166`.
- They install the **bare, parameterless** `EdgeBringIntoViewSpec` via `LocalBringIntoViewSpec`
  (`MovieDetailScreen.kt:108`, `SeriesDetailScreen.kt:165`). That object (`focus/BringIntoView.kt:23-29`)
  reveals a clipped target at the **exact edge** — bottom-clip → `offset+size-containerSize` (flush to
  `containerSize`), top-clip → `offset` (flush to `y=0`) — **no peek, no inset**. That's Issue 1.
  Home/Channel/Discover instead use `rememberEdgeBringIntoViewSpec(peekDp = 80.dp)`
  (`HomeScreen.kt:158`, etc.) which adds an 80 dp bottom peek; detail is the only vertical scroller that
  uses the bare object. (R65 already proposes adding a `topInsetDp` param to that helper.)
- The Play/Resume **snap-to-top exists** (R45) — an `onFocusChanged { animateScrollTo(0) }` on the actions
  `Row` wrapping Play + My List (`MovieDetailScreen.kt:165-168`, `SeriesDetailScreen.kt:229-232`). **But
  it loses a race:** returning up from below, the bare spec reveals Play clipped-at-top flush to `y=0`
  (not the full hero), and that bring-into-view scroll and the `animateScrollTo(0)` both run on the scroll
  container's `MutatorMutex` at `MutatePriority.Default`, so whichever finishes last wins — frequently
  leaving Play parked flush at the top instead of the page at 0 (full hero). On **entry** it works because
  Play is already fully visible (`else -> 0f`, no competing reveal). This is exactly the R45 §C.4 caveat
  ("snap-to-top should be a single smooth scroll, not a fight with bring-into-view").

## Goal
- Every focusable on detail is revealed with a little headroom (not flush to the edge).
- Focusing Play/Resume reliably reframes the full backdrop (scrolls to the very top).

## Requirements
1. **Give the detail spec peek + top inset.** Replace the bare `EdgeBringIntoViewSpec` on both detail
   screens with `rememberEdgeBringIntoViewSpec(peekDp = …, topInsetDp = …)` (the param added by R65;
   if R65 isn't landed first, add `topInsetDp` to that helper here). Detail has **no app bar**, so the top
   inset is small breathing room (~16–24 dp) and the bottom peek ~ the existing 80 dp; this applies to
   every vertically-revealed focusable via `LocalBringIntoViewSpec`. Note the top inset alone does **not**
   fix Play (Play needs the *full* hero, not the inset line) — that's requirement 2.
2. **Make the Play/Resume snap authoritative.** Run the actions-row `animateScrollTo(0)` so it supersedes
   the competing bring-into-view — e.g. at a higher mutate priority (`scrollState.scroll(MutatePriority.UserInput){…}`),
   or re-assert `animateScrollTo(0)` a frame after the focus event settles. (Bring-into-view runs at
   `Default`, so a `UserInput`-priority scroll deterministically wins.) Optionally match Home's instant
   `scrollTo(0)` for reliability, trading the smooth animation — prefer the priority approach to keep R45's
   smooth feel.

## Scope
- `ravilo-ui/.../focus/BringIntoView.kt` (`topInsetDp` on `rememberEdgeBringIntoViewSpec` — shared with R65)
- `ravilo-ui/.../screens/MovieDetailScreen.kt` + `screens/SeriesDetailScreen.kt` (spec install line + the
  actions-row `onFocusChanged` snap priority)

## Non-goals
- Home/Channel/Discover scroll behavior (their peek already exists; R65 covers their top inset).
- No change to entry focus (`playFR.requestFocus()`) or the R47 draw-only focus scale.

## Acceptance
- Navigate around a Series/Movie detail page: focused buttons/rows have a small margin from the screen
  edges, not flush.
- Move focus up to Play/Resume from anywhere on the page → the page scrolls to the very top, showing the
  full backdrop image + description, every time (matching initial entry).
