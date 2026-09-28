# Phase R325 — A title's About section, and the audit's three other open calls

> Owner, 2026-09-28, on the About section of a film or series page: *"Let's keep all of that about section and then
> write a spec for it."* The same week the owner answered the design-vs-implementation audit's other three calls
> (tile badge, a Cast or crew facet, genres in search).

## Status

`Planned` — written 2026-09-28 from `research-reports/ravilo-design-vs-implementation-audit-2026-09-27.md` §2.1, §2.2,
§2.5 and the mockups `design/ravilo/ravilo-app.js` (TV detail, `tileQ()`), `design/ravilo/Ravilo Mobile.html`
(`aboutHTML()`, `.pb`), `design/ravilo/Focus Detail - Directions.html` §F. **Not dev-reviewed.** Number verified free
on `main` 2026-09-28 (Ravilo tops at R323; R324 is the speakers phase).

## Decisions (owner, 2026-09-28)

| Audit | Question | Answer |
|---|---|---|
| §2.1 | The About section (drawn since the Focus Detail round, never spec'd, not built) | **Keep all of it**, TV and phone |
| §2.2 | The quality badge on a tile | **Keep it, only for 4K and HDR** |
| §2.5 | A viewer *Cast or crew* facet | **Yes, a facet like Genre** |
| §2.5 | Does search match genres | **Yes — genres as their own result group** |

## Requirements

**FR-R325-1 — The About section** sits on a film or series detail page after the synopsis (and after Episodes on a
series), headed **About**, a two-column grid of label · value pairs, each absent when the server has no value:

| Label | Film | Series |
|---|---|---|
| Runtime | *112 min* | *42 min per episode* |
| Released / First aired | the date in the viewer's locale | the first air date |
| Director | the first director | the creator when there is no director |
| Studio / Network | the first studio | the first network |
| Country | production country | origin country |
| Original language | the language's name in the viewer's language | same |
| Seasons | — | *3 · 30 episodes* (library counts) |
| In your library since | the date the first file was added | same |

On the TV the full synopsis sits beside the grid; on the phone the grid follows the synopsis. No value is invented
and nothing says *Unknown*.

**FR-R325-2 — Where the values come from.** `GET /api/tv/items/{id}` gains an additive `about` block
`{runtime_min, released, director, studio_or_network, country, original_language, added}` from the facts the
server already holds (NFO / TMDB / the scan's added date); `seasons`/`episodes` are the library's counts. WireCompat
(R319) passes: the block is optional, an older app ignores it.

**FR-R325-3 — The tile quality badge** is shown only for **4K** and **HDR** (and *4K HDR*, *Dolby Vision*); a 1080p or
SD title has no badge. The hero and the detail page's meta line keep the full quality as today.

**FR-R325-4 — A *Cast or crew* facet** in the viewer's browse facets, beside Genre: a searchable list of people in
this viewer's libraries (top 40 by title count, then *Search…*), multi-select OR within the facet, AND with the
others; each person's count. The admin workbench's facet of the same name (R190 §D) is the model.

**FR-R325-5 — Search matches genres** as their own result group: *Genres* with a chip per matching genre (the
viewer's normalised genre names, R-normGenre), above titles when the query is a genre's full name; a chip opens
Browse filtered to it.

## Acceptance

1. A film with all eight facts shows all eight; a film with no director shows seven and no gap.
2. The Danish UI reads *Originalsprog · Engelsk*.
3. A 1080p film's tile has no badge; a 4K HDR film's reads *4K HDR*.
4. Browse ▸ Cast or crew ▸ one actor narrows the grid to that actor's titles, with the count shown.
5. Searching *horror* shows a Genres group with *Horror* above the titles.

## Open questions (for the dev review)

1. Whether *In your library since* should be the first file's date or the title's (they differ for a series).
