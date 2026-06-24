# R55 — Ravilo TV: Back scrolls to top before leaving the page (FR-RBK1)

**Status:** ✅ Done — shared `Modifier.backToTopOnBack(atTop, onBackToTop)` in `focus/BackToTop.kt`,
applied to Home, Discover, Channel, Browse and Search. Each scrolls its list/grid to the top and moves
focus to a top target (Home → hero/app bar, Discover → app bar, Channel/Browse → first item/cell via a
single `FocusRequester`, Search → the keyboard) before falling through to `RaviloApp`'s pop/exit. Detail
and Player screens are excluded.
**Depends on:** R09 (focus/nav), R21 (Back navigation), R40 (store retention)

## Goal

When a content page is scrolled down and the user presses **Back**, the app should first **scroll to the
top** of that page rather than immediately popping/closing. Only a Back press **already at the top** does
the normal thing (pop to the previous page, or exit at the root). This applies **across content pages**
and makes it noticeably **harder to close the app by accident**. **Media item pages (Movie/Series
detail) are excluded** — Back there pops straight back as today.

## Current state

`RaviloApp.kt` — a single Box `onKeyEvent` consumes `Key.Back`/`Key.Escape` → `pop()` **only when
`stack.size > 1`**. At the root (Home), Back is **not** consumed, so Android finishes the activity
(exits) immediately. There is **no scroll-to-top step**, so a single accidental Back at the root Home
closes the app even when scrolled far down.

Scrollable content screens:
- **Home / Channel / Discover** — `LazyColumn` (Home + Discover keep a `rememberLazyListState()`; Channel
  does not yet).
- **Browse / Search** — `LazyVerticalGrid` (no stored grid state yet).
- **Movie / Series detail, Player** — full-bleed, no list state → **excluded**.

## Target behaviour

For each **content** screen (Home, Browse, Channel, Search, Discover), Back/Escape:
1. If the page is **not at the top** (`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`) →
   `animateScrollToItem(0)` and **consume** the event (do not pop/exit).
2. If **already at the top** → **do not consume**; the event bubbles to `RaviloApp`'s handler, which
   pops to the previous page (or exits at the root) exactly as today.

Net: root Home scrolled → Back goes to top (no exit); root Home at top → Back exits. Pushed page scrolled
→ Back goes to top; pushed page at top → Back pops. Detail/Player pages: Back pops immediately
(unchanged).

## Approach (implementation guidance, not prescriptive)

- A small shared helper — e.g. `Modifier.backToTopOnBack(state, scope)` accepting a `LazyListState` **or**
  `LazyGridState` — added at each content screen's **root** via `onKeyEvent`. Because Compose key events
  bubble **child → ancestor**, a screen that consumes Back runs **before** `RaviloApp`'s Box, so
  consuming there cleanly suppresses the pop; returning `false` lets it fall through to the existing
  pop/exit logic.
- **Store the scroll state where missing** (Channel `LazyColumn`, Browse/Search grids) so the handler can
  read/animate it.
- **Focus gotcha (must address):** if a lower tile is still focused when we jump to the top, Compose's
  bring-into-view will scroll **back down** to it, undoing the jump. So on Back-to-top **also move focus
  to a top target** (the app-bar entry `navFR`, or the first row/first cell) so focus isn't off-screen
  and the reveal doesn't fight the scroll.
- Excluded screens (`MovieDetailScreen`, `SeriesDetailScreen`, `PlayerScreen`) simply don't apply the
  helper.

## Non-goals / invariants

- **D-pad-first; Back always meaningful** (constitution): Back now means "to top, then leave"; it never
  strands focus and never silently no-ops.
- **R40 store retention** unchanged — scrolling to top doesn't reload the screen.
- Pure interaction; no API/config change.

## Mockup

Behavioural (no new visual). Cross-checks `RaviloApp` back handling + each content screen's scroll state.
