# Phase R13 — Movie & Series detail screens (FR-RV13)

**Status:** Planned · _the calm series experience; the heart of Ravilo._

## Problem
Build the **movie** and **series** detail screens. The series screen is the priority: it must make
**watched vs unwatched obvious**, surface the **resume/next episode** as the default action, let you
go **one episode back** trivially, switch **seasons** in place, and **browse all episodes** — all in
one view, without the Jellyfin-app confusion.

## Current state (as-is)
- R07 provides `GET /api/tv/movie/{id}` + `GET /api/tv/series/{id}` with per-item `PlaybackState` and
  a server-computed resume/up-next pointer. R09 provides `EpisodeCard`, `SeasonPicker`, `CastCircle`.

## Requirements

### Shared (both)
1. A `DetailStore` loads the payload; the screen renders it. A **cinematic hero band** (backdrop +
   logo/title + meta + synopsis) with primary actions: **Play/Resume**, **Trailer**, **+ My List**.
2. **Cast & Crew** row and **More Like This** row (selecting opens that item's detail).
3. Back returns to the originating screen with focus restored.

### Movie
4. Primary button is **Resume · N min left** when in progress (from `PlaybackState`), else **Play**.

### Series (the priority)
5. Primary button is **server-driven**: "Resume · E4" / "Play · E1" from the R07 resume pointer; a
   one-line **Up Next** note ("Resume S1·E4 'Útróður' · 22 min left").
6. A **watched overview**: "X of Y watched" + a season progress bar.
7. **Season picker** pills swap the episode rail **in place** (no navigation away).
8. **Episode rail** with `EpisodeCard`s showing: number, still, runtime, overview; a green **✓** +
   dimming when watched; a **progress bar** when in progress; an **UP NEXT** ribbon on the resume
   episode.
9. **One-press next/back**: pressing **down** from Play lands focus directly on the **Up Next**
   episode; the previous (watched) episode is one press **left** — so resuming, repeating, or stepping
   back is trivial; all episodes browsable in the same rail.
10. Selecting an episode → playback (R14) of that episode; selecting Play/Resume → the resume episode.

## Invariants
- **Resume/up-next is server-driven** (R07) — the client never guesses which episode is next.
- Watched/progress treatments come from per-item `PlaybackState`.
- One shared implementation on TV + web; fully focus-navigable.

## Out of scope
- The actual player (R14) — these screens launch it.
- Editing metadata (never — that's jellystructure).
