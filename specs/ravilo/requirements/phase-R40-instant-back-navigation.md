# Phase R40 — Instant back navigation (screen-state caching)

**Status:** ✓ Done · _pressing Back shows a loading skeleton instead of the screen we just came from.
Cache screen state so Back is instant; refresh silently in the background._

## Problem
Navigating into a screen and pressing Back re-shows a loading state and re-fetches, even though we were
just there. Root cause: navigation is a custom stack in `ravilo-ui/.../RaviloApp.kt` rendered via
`AnimatedContent(contentKey = { it::class })`. Leaving a screen **disposes its composable**, which
disposes the `remember { …Store }` holding its data; returning creates a **new** store whose
`init { load() }` (or the screen's `LaunchedEffect`) sets state to `Loading` and re-fetches → the skeleton
flashes (~1–2 s). Home uses `remember { HomeStore }` (no key); detail/channel/browse stores are keyed by
id but are still recreated on re-entry. Every store does `_state.value = Loading` at the top of `load()`.

## Goal
Returning to a previously-visited screen renders its **last state instantly** (no skeleton); any refresh
happens silently in the background. New navigations (first visit, or a different id) still show the normal
loading state.

## Requirements
### A. Retain store state across back navigation
Keep store instances (and their last `Loaded` data) alive across navigation so re-entry has data
immediately. Approach (decide at implementation): (1) hoist/retain stores in a registry keyed by
destination identity **above** the `AnimatedContent`; or (2) give each store a cached-last-result it emits
synchronously on (re)creation. Either way: **if cached data exists for this destination, emit it
synchronously — never flash `Loading`.**

### B. Silent background refresh
On re-entry with cached data, optionally refresh in the background and swap in fresh data with **no**
loading state (consistent with the existing silent refresh on `HomeScreen.kt`). First visits / new ids
refresh with the normal `Loading`.

### C. Preserve scroll + focus
Returning restores scroll position and focus where possible (natural if the composition/store is retained;
otherwise save/restore explicitly).

### D. Scope
Apply to Home, Channel, Browse, Movie/Series detail, Search — every screen with a store + loading state.

## Invariants
- The TV still renders server-served state (constitution) — this is a presentation cache of the **last
  server response**, not invented/derived catalog state. Live config push (R33) still forces a refresh.
- No stale-forever data: a cached screen refreshes in the background (or on an explicit signal such as R33).

## Out of scope
- Persisting cache across app restarts (in-memory for the session is enough).
- A full migration to Jetpack Compose Navigation (only if it proves necessary).

## Design reference
`ravilo-ui/.../RaviloApp.kt` (nav stack + `AnimatedContent`, lines ~155/162/178); the stores under
`ravilo-ui/.../screens/` (`HomeStore`, `DetailStore`, `BrowseStore`, `ChannelStore`, `SearchStore`); the
screen `LaunchedEffect` load triggers.
