# Phase R201 — Season picker unfocusable when the auto-selected season is scrolled off-screen (bug fix, FR-RV-SD1)

> Reported live: "the season picker within series is often hard/impossible to focus, making it impossible
> to pick the correct season" — named `Klovn` (11 seasons, fully watched) as a repeat offender.
> Live-reproduced on stue TV via adb: from Klovn's Play/My List buttons, pressing Down never lands
> anywhere in the visible "Season 1…7" pill row — focus effectively goes nowhere. Root-caused in code
> immediately after R200's investigation surfaced the same failure shape (a `FocusRequester` that's never
> actually attached to a composed node).

**Status:** Implemented. Verified via `:ravilo-ui:compileKotlinWasmJs` + `:ravilo-ui:compileDebugKotlinAndroid`
(compile-check only — no on-device Ravilo APK rebuild/install this session).

## Bug report
"the season picker within series is often hard/impossible to focus, making it impossible to pick the
correct season for a series... I know I've seen this a few times for the 'Klovn' series." (2026-08-18.)

## Investigation
Live-reproduced on stue TV: opened Klovn (11 seasons, "99 of 99 episodes watched"), Play had focus by
default, pressed Down — the visible season pill row ("Season 1" through "Season 7", the rest scrolled off
to the right) never showed a focus ring on any pill, and a subsequent Right press jumped focus straight to
the top-bar search icon, confirming Down had landed nowhere reachable inside the row at all.

### Root cause
`SeriesDetailScreen.kt:278-286` auto-selects a season once the playstate overlay loads:
```kotlin
val activeIdx = detail.seasons.indexOfFirst { season ->
    season.episodes.any { ep -> overlay[ep.id]?.played != true }
}.takeIf { it >= 0 } ?: (detail.seasons.size - 1)
selectedSeasonIdx = activeIdx
```
For a fully-watched series every season's `indexOfFirst` predicate is false, so `activeIdx` falls back to
`seasons.size - 1` — **the last season**. For Klovn that's Season 11, index 10.

`SeasonPicker.kt:73` attaches the Down-navigation bridge target to **whichever pill is selected**, not
pill 0:
```kotlin
focusRequester = if (i == selectedIndex) firstFocusRequester else null,
```
a deliberate design (jumping into a partially-watched series should land Down on the season you're
mid-way through, not always Season 1) — but `SeasonPicker`'s `LazyRow` (`:57-61`) has no `state` param at
all, so it never scrolls itself to bring an off-screen `selectedIndex` pill into its composed window. When
`SeriesDetailScreen.kt:507-511`'s Down handler calls `seasonFirstFR.requestFocus()`, that requester was
never attached to anything — Season 11's pill was never composed, since a `LazyRow` only composes items
near its current scroll position (Season 1-7 or so). The request silently fails inside `runCatching`, and
because the same handler unconditionally returns `true` (key consumed) the moment it fires — before the
async scroll/focus work even runs — native Compose focus search never gets a chance to find anything else
either. Down from Play becomes a total dead end.

This reproduces for **any series whose auto-selected season sits outside the picker's initial
composition window** — a fully-watched series (→ last season, as here) or, symmetrically, a long series
resumed deep into a high season number while a short list of low-numbered seasons happens to render first.
Klovn (11 seasons, binge-completed) is simply the easiest case to hit by accident.

## Requirements

### FR-RV-SD1-1 — The season picker scrolls itself to the selected pill
`SeasonPicker` must own a `LazyListState` for its `LazyRow` (currently implicit/unexposed) and scroll to
`selectedIndex` whenever it changes (including on first composition), so the pill that
`firstFocusRequester` targets is always actually composed and attachable before anything tries to focus
it. This also fixes a secondary rough edge noted during investigation: today, scrolling the page down to
the season row shows Season 1 first regardless of which season is actually active/selected — a viewer
resuming deep into a series has to manually scroll the pill row to even *see* the highlighted season.

### FR-RV-SD1-2 — Harden the Down-from-Play focus bridge the same way as R200
`SeriesDetailScreen.kt:510`'s `runCatching { seasonFirstFR.requestFocus() }` must use
`requestFocusRetrying` (the R200-hardened bounded-retry helper) instead of a single attempt, as
defense-in-depth against any residual one-frame gap between the picker's self-scroll (FR-RV-SD1-1) landing
and the pill's `Modifier.focusRequester` actually attaching.

## Invariants
- **The season picker's currently-selected pill is always focusable via Down from the action buttons**,
  regardless of how many seasons precede it or how the season was chosen (default, auto-selected, or a
  prior manual pick).

## Out of scope
- Auto-scrolling the season picker on *plain* vertical scroll-into-view when the viewer never uses the
  Down bridge at all (e.g. arrives via mouse/touch scroll) — FR-RV-SD1-1's `LaunchedEffect(selectedIndex)`
  already covers this for free (it fires on first composition regardless of navigation method), so no
  separate requirement, but called out since it wasn't the reported symptom.
- The `SeasonPicker`/`ContentRow` off-screen-FocusRequester failure mode in the abstract — R200 already
  hardened the general retry helper; this phase fixes the specific missing self-scroll that made
  `SeasonPicker` hit it in the first place.

## Source references
- Auto-select logic: `ravilo-ui/.../screens/SeriesDetailScreen.kt:278-286`.
- Down-navigation bridge: `ravilo-ui/.../screens/SeriesDetailScreen.kt:499-516` (esp. `:507-511`).
- Bug site: `ravilo-ui/.../components/SeasonPicker.kt` (`LazyRow`, `:57-61`; `firstFocusRequester`
  attachment, `:73`).
- Retry helper: `ravilo-ui/.../focus/FocusModifiers.kt` (`requestFocusRetrying`, hardened by R200).
- Related: **R200** (`phase-R200-content-row-focus-restore-stuck.md`) — same investigation session, same
  underlying failure shape (an off-screen/unattached `FocusRequester`), different specific cause.
