# Phase R307 — The detail page's meta line: no word broken, no genre said twice

> Owner, 2026-09-26, looking at the Pixel 9: *"Yes, we need a solution to that watc hed issue."* And:
> *"Also it says crime on the top row and then again shows crime and thriller on the second row. So the top
> row crime should be removed."*

## Status

`✓ Built` — written and built 2026-09-26, not dev-reviewed. `components/DetailMetaFlow.kt` (a `FlowRow`,
10 dp between pieces, 6 dp between lines, centred) replaces both pages' `Row`; the meta texts and the
watched mark are single-line; the genre is dropped from the meta text when `detail.genres` is not empty.
**Seen:** on the Pixel 9, the watched film's line is *2025 · 112 min · NO 9 · IMDb 5.9 · ✓ Watched* on one
line, *Watched* whole; on the stue TV the film page reads the same with no genre, a series page reads
*2015 · US TV-14 · IMDb 9.0* above its *Comedy* chip, and nothing else on either page moved.

## What is wrong

1. **"✓ Watched" breaks mid-word on a phone.** The film page's meta line — `2025 · 112 min · Crime`, the
   age badge, the IMDb chip, and *✓ Watched* when the film is played — is a plain `Row`. On the Pixel 9 the
   pieces are wider than the screen, so the row squeezes its last child and the text wraps inside it:
   *"Watc / hed"*. The series page builds the same line (without the Watched chip) and overflows the same
   way on a narrow screen.
2. **The genre is said twice.** The meta line's text ends with the title's first genre, and R221's genre
   row directly beneath shows every genre, that one first: *Crime* on the top line, then *Crime · Thriller*
   as chips. The mockup never had it twice — since R221 its detail meta line is badge, year, rating, IMDb
   and the watched mark, no genre (`design/ravilo/ravilo-app.js`, the detail hero's `.hero-meta`); the port
   kept the older `year · runtime · genre` string.

## Requirements

**FR-R307-1 — The line wraps by pieces, never inside one.** The film and series meta lines are a flow
layout: when the pieces do not fit, a whole piece moves to the next line (the same spacing between pieces,
a small gap between lines). No piece breaks internally — the texts are single-line. On a TV the line fits
and nothing changes.

**FR-R307-2 — The genre is said once.** The meta text drops the genre whenever the genre row is shown (the
title has genres). A title with a genre but no genre row — none today, but the fields are separate — keeps
it in the meta text, so it is never said zero times.

## Non-goals

- Anything else on the line (the badge, IMDb chip, the watched mark) or the genre row itself.

## Acceptance

1. On the Pixel 9, a watched film: *✓ Watched* on one line (moved whole to a second line if it must).
2. On the stue TV and the Pixel 9, a film and a series page: the top line has no genre; the genre row
   under it is unchanged; nothing else moved on the TV.
