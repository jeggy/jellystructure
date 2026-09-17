# Phase R258 — A scrub preview dies with its episode

> Found on the stue TV 2026-09-17 while re-testing R246: after switching episodes in the player the
> elapsed-time label stayed frozen at **9:02** — the *previous* episode's scrub target — and a stray
> tick sat at 46 % of the new episode's bar, while the real thumb advanced underneath it.

## Status

`✓ Built` 2026-09-17 — spec'd and fixed the same hour; Android compiles; not dev-reviewed; the fix
itself not yet re-checked on a device. Client-only (`PlayerScreen.kt`).

**Numbering:** verified against `STATUS.md` 2026-09-17 — Ravilo taken through **R257**.

## Cause

On the seek bar, Left/Right do not seek: they start a **preview** (`scrubbing = true`, `scrubPos`),
committed by OK or by moving Down. `scrubbing`/`scrubPos` are `remember`ed for the life of the player
screen, and nothing clears them when `itemId` changes (episode rail, *Next*, auto-advance). While
`scrubbing` is true the label renders `scrubPos` (`:2113`) and the bar draws the target tick
(`:2177`/`:2228`) — so a preview left pending on episode A is drawn over episode B indefinitely.
A later OK would also have *committed* it: a seek to A's position inside B.

## Requirements

- **FR-R258-1** — a change of `itemId` cancels any pending scrub preview (`scrubbing = false`); it is
  never committed against the new item.
- **FR-R258-2** — nothing else about the preview model changes (R251's reveal-only first key, the
  Down-commits rule, the handset's drag).

## Also seen in the same pass, recorded, not changed

- **Search → Back:** returning to Search shows an empty query field over the previous result, relabelled
  *Suggestions*.
- **On Now:** arriving on the row from the row above leaves its heading under the app bar — FR-R257-5's
  class of defect on a row J never opens on.
- **TV Guide:** a long programme that started before the visible window (DR2, 13:00–18:15) draws as an
  untitled block; the title is not pinned to the visible part of its cell.

## Acceptance

Seek bar → Right ×3 (preview pending) → Episodes → pick another episode: the label shows the new
episode's real position and no tick is drawn.
