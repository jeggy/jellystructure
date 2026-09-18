# Phase 216 — The library indexed by studio, network and genre

> The server half of **R243**. Jellystructure's Metadata page already groups the library by studio,
> network and genre and states an item count for each; Ravilo has no equivalent, and no viewer-side way
> to ask "what else is from here?". This phase makes that index a served, per-viewer resource: one
> endpoint, counts that are true for the profile asking, and a seed the existing browse query can take
> verbatim. No new metadata field, no new artwork pipeline, no third kind of list.

## Status

`Planned` — written 2026-09-16, **not dev-reviewed**.

**Numbering:** verified against `main` on 2026-09-16 — admin taken through **215**, Ravilo through
**R242**, no `phase-216-*` file and no `STATUS.md` row for it. Pairs with **R243**. Next free: 217 / R244.

Design: `design/ravilo/Ravilo TV.html` + `ravilo-app.js` (`renderTaxonomy`/`taxoTile`),
`ravilo-data.js` (`taxonomy`/`taxonomySummary`/`taxoValues`/`libraryFor`/`normAge`),
`ravilo.css`, `Ravilo Mobile.html`. Admin precedent: `design/app/metadata.html`.

## Current state

Three facts about the library are already resolved per title and already shown:

- `aboutFor()`-equivalent server data answers **one company field** plus a flag saying whether that
  company is a broadcaster or a studio — the media detail labels its fact **Network** for a series and
  **Studio** for a film from exactly that flag.
- **Genres** are a ` · `-separated list in TMDB's own order, normalised by phase 155/R221's rules, and
  already drive R221's genre chips and the browse facet.
- The **admin** Metadata page counts all three across the whole library and shows the counts to an
  operator, with logos where one was captured at scan time.

What is missing is the viewer's version of the same index. Nothing on the wire tells Ravilo how many
titles a studio has, and nothing could: the client holds one page of one feed, never the library, so any
count it derived would be a count of what it happens to have loaded. The count is the whole point of the
surface — a wall of logos without numbers is a wall of logos.

Two constraints make this a server phase rather than a client one:

1. **Counts must be scoped to the viewer.** A kids profile can see a smaller library, and a count that
   promises 14 titles and opens a grid of 6 is worse than no count.
2. **The count and the grid must come from one query.** R233 FR-R233-5 settled the shape for system rows:
   the canonical list is built once per `(user, visibility scope)` and every surface is a *filtered view*
   of it, never a re-derivation. The same rule applies here, for the same reason.

## Goal

One per-viewer taxonomy index, correct by construction against the grid it opens, carrying artwork only
where artwork genuinely exists.

## Functional requirements

**FR-216-1 — One endpoint, three kinds.** `GET /api/tv/taxonomy?kind=studios|networks|genres` returns
the groups for the calling user, already counted and already sorted. No other shape: the client must not
be able to ask for "the library" and count it itself, and no per-title fetch backs this surface.

```jsonc
{
  "kind": "networks",
  "groups": 10,                     // = values.length, stated so the header needs no arithmetic
  "titles": 22,                     // titles carrying ≥1 value of this kind, counted ONCE (see FR-216-3)
  "library": 50,                    // titles visible to this profile, for "22 of 50" style copy
  "scoped": true,                   // this profile sees less than the whole library (FR-216-2)
  "values": [
    { "value": "Dansk TV", "count": 4, "logoUrl": "/artwork/net/dansk-tv.png" },
    { "value": "DR",       "count": 3 }
  ]
}
```

**FR-216-2 — Counts are the profile's, not the library's.** Every count is computed over the titles this
user may see, using the **same visibility scope** R219/R233 already key their caches on — maturity
ceiling, library restrictions, kids flag, all of it. Per phase 155 FR-AGE1-2 an item with **no mapped
rating is treated as 18**, so an unrated title is never counted for a kids profile. `scoped: true` when
the visible library is smaller than the full one, so the client can say so rather than silently
under-reporting.

**FR-216-3 — A title counts once per surface, but under every value it names.** The company field may
name two (`RÚV · Dansk TV`); it is split on ` · `, trimmed, and the title is counted under **each** —
exactly as the admin Metadata page counts it. `titles` at the top level is therefore **not** the sum of
`count` — it is the number of distinct titles carrying at least one value of that kind.

**FR-216-4 — Studio vs network is the existing flag, not a new field.** `kind=networks` selects titles
whose company field is flagged as a broadcaster (series), `kind=studios` those where it is not (films).
No new column, no second company field, no per-kind metadata pull. One consequence must be honoured
rather than worked around: **a film's company is a production studio**, so a film whose company was
imported from a broadcaster list is mis-filed at the source and belongs in a metadata fix, not in a
special case here. (The mockups hit this: films were resolving out of the broadcaster pool, and the
Studios wall was all broadcasters. Fixed in the demo's resolver; flagged here because real data may have
the same shape.)

**FR-216-5 — Artwork only where artwork exists.** `logoUrl` is present **only** when a logo was actually
captured for that value. There is no placeholder URL, no "no logo" sentinel, and no on-demand fetch
triggered by this endpoint — TMDB has no network search, which the admin page already states, so most
networks will never have one. Absent `logoUrl` is a normal, expected, permanent state and the client
renders the name instead (R243 FR-R243-2).

**FR-216-6 — Zero never ships.** A value with no visible titles for this user is omitted entirely. A kids
profile therefore sees a shorter wall, not a wall of zeroes, and no tile can open an empty grid.

**FR-216-7 — The grid is seeded from the same resolver.** Opening a value runs the existing browse query
with a taxonomy seed (`kind` + `value`) resolved by the **same** code that produced the count. The
acceptance test for this phase is arithmetic: the grid's result count equals the tile's count, for every
value, for every profile. Genres reuse R221's existing genre seed — no second genre path.

**FR-216-8 — Cached per `(user, visibility scope)`, invalidated by `libraryVersion`.** Never keyed on the
requested `kind`, the selected value, or anything narrower — R233 FR-R233-5's rule, restated because this
is the same trap: filtering is a view, never a re-derivation. A cold cache computes; a scan invalidates.

**FR-216-9 — Values are normalised before they are grouped.** Trim, collapse internal whitespace, and
group case-insensitively while displaying the most frequent spelling. `HBO Nordic` and `HBO  nordic` are
one network with one count, or the wall quietly lies twice.

**FR-216-10 — No new config gates this.** These three tabs read the library the viewer already has, so
they need neither Seerr nor Sonarr nor a per-user switch, and Ravilo's Discover nav item no longer
depends on either integration being configured (R243 FR-R243-1). If a household wants them off, that is a
new `RaviloConfig` field and a follow-up phase — see open question 1 — not something to design in
speculatively.

## Non-goals

- **No tags and no age-rating tab.** The admin Metadata page has six tabs; three of them are operator
  tools (tags a human maintains, certification mappings, trackers) and have no viewer meaning.
- **No artwork acquisition.** This endpoint reads what the artwork pipeline (phase 192) already captured.
  It never fetches, never queues a fetch, and never reports a missing logo as a problem.
- **No new list type.** Selecting a value opens the browse grid that already exists.
- **No cross-over with Seerr.** These counts are the library on the shelf. Requestable titles are the
  Request tab's business and must never be added into a count of what you can press play on.
- **No sort control.** Ordering is fixed by FR-216-1 (count descending, name as tie-break) — a sort
  affordance on a wall of ten tiles is a control nobody needs on a remote.
- **No Channels/Collections here.** Jellystructure's configured channels (the Disney+-style rail on Home)
  are a curated, operator-defined concept. Conflating them with scanner metadata would put two different
  meanings of "studio" on one screen.

## Acceptance

1. `GET /api/tv/taxonomy?kind=studios` as an adult profile returns every studio with ≥1 visible title,
   count-descending, and `groups` equals `values.length`.
2. The same call as a **kids profile** returns fewer groups and smaller counts; no unrated title is
   counted anywhere in the response; `scoped` is `true`.
3. A title whose company field reads `RÚV · Dansk TV` appears in the count of **both**, and raises
   top-level `titles` by exactly **one**.
4. For every value in every kind, opening the browse grid with that seed returns exactly `count` titles,
   for the same profile.
5. A value whose only titles are hidden from this profile is **absent**, not present with `count: 0`.
6. A network with no captured logo returns **no** `logoUrl` key; nothing in the response distinguishes
   "no logo" from "logo pending".
7. `kind=networks` returns no films; `kind=studios` returns no series.
8. Two spellings differing only by case or padding produce **one** group.
9. A library write bumps `libraryVersion` and the next call recomputes; repeated calls in between are
   served from cache and issue no Jellyfin round trip.
10. Response for a 50-title library is ≲4 KB and issues no image request of its own.

## Source references

- `design/app/metadata.html` — the admin half this mirrors: grouping, counts, the "TMDB has no network
  search" note that FR-216-5 formalises.
- `design/ravilo/ravilo-data.js` — reference resolvers `taxonomy()`, `taxonomySummary()`, `taxoValues()`,
  `libraryFor()`, `normAge()`; the demo's stand-in for this endpoint, written so the TV wall, the phone
  wall and the filtered grid cannot state different numbers.
- Related: **155** (age normalisation; no rating ⇒ 18), **R221** (genre naming + the existing genre
  browse seed), **R233** (one canonical list per `(user, visibility scope)`; filtering is a view),
  **R219** (the visibility-scope cache key), **192** (artwork/clearlogo pipeline — the only source of
  `logoUrl`), **R187** (the browse page this seeds).

## Open questions

1. **Should the tabs be per-user config?** Nothing gates them today (FR-216-10). The argument for a
   switch is a household that wants Discover to stay a request surface; the argument against is that
   every other library-browse affordance (Movies, Series, browse facets) is ungated too. Left out
   deliberately rather than guessed.
2. **Precomputed at scan time, or per request?** The counts are cheap over a 500-title library and
   FR-216-8's cache absorbs the rest, but a large library may want the index materialised alongside the
   scan. Measure before building it.
3. **Is the company field trustworthy enough per-kind?** FR-216-4 says a mis-filed film is a metadata
   problem. Whether the real library has enough of them to need a repair sweep (phase 192's shape) is a
   question for the first run against production data, not for this spec.
4. **Should `titles` vs `library` be surfaced at all?** R243 renders "10 networks · 22 titles". Whether a
   viewer benefits from also knowing 28 titles carry no network is untested copy.
