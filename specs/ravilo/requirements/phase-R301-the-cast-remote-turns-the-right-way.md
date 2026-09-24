# Phase R301 — The cast remote's skip arrows turn the right way

## Status

`Planned` — written 2026-09-24 from a Pixel 9 observation, not dev-reviewed. Spec first. Closes the
gap **R257** FR-R257-6 left: it fixed `HandsetSkipButton` (the local player) and not `RemoteSkip`
(the cast remote), which was written from the same wrong glyph.

## What happens today

On the cast remote, the −10 s button's arrow turns clockwise with its head at the upper right (every
platform's *forward*) and the +30 s button's arrow the other way. The local player's buttons were the
same until R257 turned them round; the remote kept the old drawing. Seen 2026-09-24, Play 1.36.

## Requirements

- **FR-R301-1** — back turns counter-clockwise with its head at the upper left pointing left; forward
  turns clockwise with its head at the upper right pointing right: FR-R257-6, on the remote.
- **FR-R301-2** — one drawing. `RemoteSkip` uses the same glyph code as `HandsetSkipButton`
  (`SkipGlyph`, shared), so the two cannot disagree again.

## Acceptance (Pixel 9, debug)

Open the cast remote: the −10 s glyph matches the local player's −10 s glyph; +30 s likewise.
