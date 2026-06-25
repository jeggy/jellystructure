# Phase R12 — Search screen (FR-RV12)


## Problem
A 10-foot search needs an **on-screen keyboard** and **live results** — and it must match every title
an item has ever had (a show pulled once in Danish is findable by its Danish name). Works on TV D-pad
and in the browser.

## Current state (as-is)
- R06 provides `GET /api/tv/search` (reusing Phase 29 multi-language titles). R09 provides an
  `OnScreenKeyboard` component. No search screen yet.

## Requirements
1. A `SearchStore` debounces the query and loads `SearchResults` via `TvApiClient`; the screen renders
   store state.
2. **On-screen keyboard** (A–Z, 0–9, Space, Delete, Clear) — fully focusable; each key press updates
   the query string shown in a search bar.
3. **Live results grid** below the keyboard updates as the query changes; empty query shows a
   "Suggestions" set; no matches shows a friendly empty state.
4. Multi-language: results match **every title ever pulled** + original title (server-side, R06) —
   surfaced as normal `MediaCard`s.
5. Focus model: keyboard is a focus grid (rows of keys); moving **down** off the keyboard enters the
   results grid; **Back** clears focus to the keyboard, then exits to the previous screen. Results
   open detail (R13).
6. Hardware keyboard (web) and remote text input may bypass the on-screen keyboard but keep it in sync.

## Invariants
- Multi-language search via R06 (Phase 29) — no parallel index.
- Fully D-pad/pointer navigable; one shared implementation.
- Server-pushed results; debounced, bounded.

## Out of scope
- Voice search; detail (R13); player (R14).
