# Phase R347 — An episode left at its credits is watched

> Found 2026-10-01 in R343's dev review, by reading the code. Owner, the same day: spec it now, fix it later.

## Status

`Planned` — written 2026-10-01 (dev-authored), from R343's dev review (item 12) against `main` `44e26871`. Not built,
not checked against live data. Number given by the coordinator. **Amends** R142's "a genuinely finished episode
(≥ 90 %) is marked played as we advance" and R182's *Skip credits*. **Used by** R343 (finished, and the tick a shuffled
episode gets). No new string, no wire change.

## What happens

A short episode the viewer watches to the credits is not ticked when the player moves on. Take an 11-minute kids'
episode with a minute and a half of credits: the credits start at about 86 %. The next-up card appears there, the
countdown runs out, and the next episode starts. The episode just watched:

- is **not ticked**;
- keeps an **86 % resume point**, so it shows as in progress on the page and in Continue watching;
- keeps its series from ever being *finished*, so R343's *Start over* never appears.

## Why it happens

1. `advanceNext` ticks the outgoing episode only at ≥ 90 % of its duration (`PlayerScreen.kt:628–643`, the test on
   `:639`). `skipCredits` uses the same test (`:670`).
2. The advance then closes the session, and the stop reports the playhead (86 %). Jellyfin's own rule ticks an item
   only past its *MaxResumePct* (90 % by default), so it saves a resume point instead.
3. The card itself fires at the credits marker when the episode has a trusted one (`:1138–1141`, trusted = positive,
   inside the duration and in the back half, `CREDITS_MARKER_MIN_FRACTION`), else in the last 20 s. So the marker
   says "the episode is over", and the tick rule ignores it.
4. The TVs that play from a cast tick nothing on advance at all: a Ravilo screen's `playNext` stops the current item at
   its playhead and plays the next (`ravilo-screen` `Screen.kt:526–535`), and the Chromecast receiver advances the same
   way. Only the 90 % rule on Jellyfin's side can tick there.

## Requirements

**FR-R347-1 — One rule for "finished".** An item is finished when the position is at or past 90 % of its duration, or
at or past its credits marker when it has a trusted one (the player's own trust rule: positive, inside the duration,
in the back half). One function in `shared`, used by the client and the server.

**FR-R347-2 — The server ticks on stop.** In `PlaybackService.stopPlayback`, an episode or film that is finished under
FR-R347-1 but below 90 % is marked played through the existing `mark` path. `mark` also zeroes the position (R185), so
no 86 % resume point is left behind. The server already knows each item's markers (the same lookup the play push uses,
`PlayPushResolver`'s `segments`). This covers every client and receiver, the watchdog's forced stop and older apps.

**FR-R347-3 — The player ticks at once.** `advanceNext` and `skipCredits` use FR-R347-1 instead of the bare 90 % test.
The tick then reaches the page and Home immediately through `WatchedBus` (R147), not only after the stop lands.

**FR-R347-4 — No marker, no change.** An item without a trusted credits marker keeps the 90 % rule.

## Out of scope

How credits markers are detected (Phase 150) · Jellyfin's own *MaxResumePct* setting · a viewer who stops before the
credits.

## Acceptance

1. An 11-minute episode whose credits start at 9:30: let the next-up countdown run out. The episode is ticked, has no
   resume bar, and is not in Continue watching.
2. The same episode, *Skip credits* on the last one of a series: it is ticked.
3. The same episode played on a TV (a Ravilo screen or a Chromecast) and advanced there: it is ticked.
4. An episode with no credits marker, stopped at 86 %: it is not ticked, and it keeps its resume point, as today.
5. A film stopped past its credits marker is ticked.
