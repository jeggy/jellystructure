# R232 — series detail & player D-pad navigation polish

**Status:** ✓ Built 2026-09-04 (design-authored, spec'd and built same session; live-tested on
stue TV against `dev.jellystructure.ravilo` release build; not yet dev-reviewed).

## Context

Live on-device navigation sweep on stue TV, requested by the owner specifically to stress-test
the season picker ("we have had a lot of small weird issues with the season picker... even some
issues that only happens sometimes or only on series with some specific state"), plus a general
D-pad edge-case sweep across the app. Reproduced and fixed three real bugs, all on Fjollerne (11
seasons, 100/101 episodes watched — only S1E1 unwatched).

## FR-R232-1: player transport Right no longer teleports focus to the top-bar Back button

`transportOrder()` in `PlayerScreen.kt` appended `PlFocus.BACK` as the last element of the same
linear Left/Right cycle as the bottom transport row (Seek/Skip/Play/Skip/Tracks/NextEp) — but
`BackButton` lives in the top bar, spatially unrelated to that row. Pressing Right past the last
real control (Next Episode, or Tracks with no next episode) jumped focus up to the top-left Back
button, which read as "Right does Up" — exactly the bug the owner named up front. Fix: `BACK` is
no longer part of the order; Right now no-ops at the row's true last control. Back stays reachable
via mouse/touch hover (`onControlHover` sets `focus` directly, independent of the order list) and
via the hardware Back key, which already is the primary D-pad way to leave the player.

## FR-R232-2: series detail season row no longer renders clipped under the AppBar

Pressing Down from the hero used `listState.animateScrollToItem(1)`, landing the season-picker row
flush at scroll offset 0 — exactly where the overlay AppBar (drawn last, outside the LazyColumn)
sits on top of it, clipping the pills' top ~40% under the bar. Reproduced at rest (not just
mid-animation) on Fjollerne. Fix: the same inset already defined for this screen's BringIntoView spec
(`appBarHeight + 24.dp`) is now passed as a negative pixel `scrollOffset` directly to
`animateScrollToItem`, the same pattern `LiveTvGuideScreen` already uses to land content below a
fixed header.

## FR-R232-3: season row's first Down press now reliably focuses the selected pill

The real bug behind the "sometimes" reports. The Down-key handler used to launch the row's scroll
and call `requestFocusRetrying(seasonFirstFR)` **concurrently** (two independent coroutines).
`requestFocusRetrying`'s first attempt runs synchronously, before the scroll coroutine has even
started — on a season row never scrolled to before, that always fails as expected, and its 30-frame
retry loop was supposed to catch it a few frames later. But this page also lands an async playstate
overlay around the same time (R84), and reproduced live: the resulting recomposition churn ate the
whole retry budget without the target ever settling. Symptom: the first Down press visibly scrolls
the season row into view (looks correct) but leaves *real* focus behind on the hero's action-button
row with no visual cue; the next directional press moves within that row (e.g. Resume → My List)
or, under a fast Right burst, escapes the row entirely to the AppBar's profile avatar. Only a
*second* Down press actually focused the season pill. Fix: sequence the two operations — await the
scroll finishing inside the same coroutine, then request focus. The season row is now guaranteed
composed and settled before the first (and normally only) focus attempt; the retry loop remains as
a pure safety net.

## FR-R232-4: season pills use explicit per-pill focus targets, not native spatial search

`SeasonPicker`'s interior pills left `onLeft`/`onRight` null (framework-documented pattern: let
Compose's native focus search move between lazy-list items, since it composes off-screen items and
scrolls them into view). Only the two true ends had explicit no-op guards (R223). Kept as a
defense-in-depth alongside FR-R232-3 above (which was the actual root cause of what looked like an
"escape to the avatar" bug in initial testing) — every pill now targets a specific
`FocusRequester` for its immediate neighbor, so Left/Right no longer depends on native search
timing at all. No-ops remain at both true ends of the row.

## Verification

Live-tested on stue TV against Fjollerne (S1: 9/10 watched, S2–S11 fully watched — the exact
"fully-watched-but-one-episode" shape that made R201 necessary):
- Single Down from hero → season row visible below AppBar, Season 1 pill shows the focus ring
  immediately (previously required a second Down press).
- 6-press Right burst (120ms apart) → lands cleanly on Season 7, no escape to the avatar.
- 8 more Right presses → lands exactly on Season 11 (the last pill), one more Right is a true no-op.
- Select on Season 11 → episode list updates to Season 11 (7/7 watched) correctly.
- 12-press Left burst → returns cleanly to Season 1, no-op at the left edge.
- Up from Season 1 → returns to the hero.
- Player: Next Episode button + Right → no-op (previously jumped to the top-bar Back button).

Not yet dev-reviewed. Broader app-wide D-pad sweep (Home rows, Movies/Series grids, Search,
Discover, player picker) still in progress in the same session.
