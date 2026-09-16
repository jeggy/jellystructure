# Phase R249 — The badge that says where you are must not be covered by the one that says what's coming

> Stue TV, 2026-09-16, the Continue Watching tile for *It's Always Sunny in Philadelphia*. The
> semantics tree holds two badges at the same corner:
>
> ```
> 'S17:E8'        [97,329][173,357]
> 'Soon • S18E07' [117,331][276,359]
> ```
>
> Only `Soon • S18E07` is visible. On the one row whose tiles exist to say *which episode you are on*,
> that is the label that gets painted over.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, finding F4). Not dev-reviewed, not built.
Client-only; a tile-rendering rule.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — Ravilo taken through R245; R246–R248 by
sibling specs the same day.

## What the code does (traced against `main`, 2026-09-16)

`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/components/Tile.kt`:

- `episodeBadge` (R113) draws at `Alignment.TopStart`, padding 8 dp (`:305-322`).
- `upcomingLabel` (R149's `Soon • SxxExx`) draws at `Alignment.TopStart`, padding 8 dp (`:324-345`),
  declared later, so it is composited on top.
- The two badges that *already* share that corner know how to yield: `watched` (`:266`) and `isNew`
  (`:280`) are each guarded with `upcomingLabel == null`. `episodeBadge` has no such guard — its own
  comment (`:302-304`) says "Continue rows never set `isNew`, so it won't collide with the NEW badge",
  which was true, and `Soon` arrived after.
- `HomeScreen.kt:583-591` passes `episodeBadge` only for `RowKind.CONTINUE` and `upcomingLabel =
  card.upcomingEpisode` for every row, so the collision is exactly "a continuing series with a
  scheduled episode, in Continue Watching" — a common shape for any show a household is mid-way
  through.

## Requirements

**FR-R249-1 — On a Continue Watching tile, the episode badge owns the top-start corner.** It is never
covered, dimmed or displaced by anything.

**FR-R249-2 — `Soon` moves rather than disappears.** On a tile that shows an episode badge, the
upcoming badge renders at `TopEnd`. That corner is free on a Continue tile: the watched ✓ never
appears there (R185 removes fully-watched titles from the row) and `NEW` is never set on a Continue
row. Everywhere else `Soon` stays where it is.

**FR-R249-3 — One precedence table, not four `if`s.** `Tile` resolves its corner badges through one
explicit table, in code and in a comment:

| Corner | Priority (first wins) |
|---|---|
| Top-start | `episodeBadge` → `upcomingLabel` → `NEW` |
| Top-end | `watched ✓` → `upcomingLabel` (only when displaced from top-start) |

A badge that loses a corner it was drawn in moves to its fallback corner if the table gives it one and
is omitted otherwise. Two badges never share an anchor.

**FR-R249-4 — The mockups agree.** `design/ravilo/Ravilo TV.html`'s Continue Watching tile carries the
pair in the two corners, so the next design sync cannot re-introduce the overlap.

## Non-goals

- Badge copy, colours or the `Soon` format (R149 / R188 own them).
- The Continue Watching data itself (R219 / 205 / R248).

## Verification

1. Stue TV, release build, the same tile: both `S17:E8` and `Soon • S18E07` legible, one per corner.
2. A Newly Added row tile with `Soon`: unchanged (top-start, alone).
3. A Continue tile for a series with no scheduled episode: episode badge alone, top-start.
