# Phase R243 — Browse the library by studio, network and genre

> The viewer half of **Phase 216**. Discover has been the place you ask for titles you don't have; it is
> now also the place you find the ones you do. Three tabs join its segment bar — **Studios · Networks ·
> Genres** — each a wall of logo tiles with an item count, each tile opening the browse grid that already
> exists. It is the viewer-side of Jellystructure's Metadata page: same grouping, same counts, ten-foot
> presentation, and one honest rule about logos nobody has.

## Status

`Planned` — written 2026-09-16, **not dev-reviewed**. Built into the mockups 2026-09-15 (TV + phone).

**Numbering:** verified against `main` on 2026-09-16 — Ravilo taken through **R242**, admin through
**215**, no `phase-R243-*` file and no `STATUS.md` row for it. Pairs with **216**. Next free: 217 / R244.

Design / reference implementation: `design/ravilo/Ravilo TV.html`, `ravilo-app.js`
(`discTabs`/`renderTaxonomy`/`taxoTile`), `ravilo.css` (`.taxo*`), `ravilo-browse.js` (the taxonomy
seed), `ravilo-i18n.js` (10 strings × en/da/fo), `Ravilo Mobile.html` (three phone tabs, 2-up wall,
filtered grid). Admin precedent: `design/app/metadata.html`.

## Current state

Discover is two tabs — **Coming Soon** (the Sonarr/Radarr calendar) and **Request** (Seerr) — and both
are config-gated, so the whole nav item disappears when neither integration is configured. Everything it
shows is about titles the household does *not* have.

Meanwhile the library itself can only be entered three ways: Home's rows, the Movies/Series browse pages,
and search. A viewer who just watched a Kringvarp drama and wants another one has to remember a title and
type it. The facts they would browse by are already on screen — the media detail states **Network** or
**Studio** as a fact, and R221 put every genre on it as a chip — but only one title at a time, and only
after opening it.

The admin side has had the index for years: Jellystructure's Metadata page walls the whole library by
studio, network, genre and tag, each with its count. The viewer has never had it.

## Goal

Make "what else is from here?" a two-press question, without inventing a third kind of list and without
ever stating a number the next screen contradicts.

## Functional requirements

### The tabs

**FR-R243-1 — Three new tabs, ungated, and Discover stops hiding.** The segment bar reads
`Coming Soon · Request · Studios · Networks · Genres`. The first two stay gated on their integrations;
the three taxonomy tabs index the library the viewer already has, so they are always present — and
because they are, the Discover nav item is **no longer hidden** when neither Seerr nor Sonarr is
configured. Pressing Discover lands on Coming Soon if it exists, else Request, else Studios.

**FR-R243-2 — The wall.** Each tab renders one grid of tiles: **4-up** for studios and networks, **5-up**
for genres (a genre needs no artwork, so its tile is shorter). A tile is:

- **With artwork** — the logo, contained, on a neutral card; the **name and the count** beneath it.
- **Without artwork** — the **name set as a wordmark** filling the card; the **count** beneath it, and
  nothing else.

The name is not repeated under a wordmark tile, and there is **no "no logo" badge, ever**. Phase 216
FR-216-5 makes a missing logo a permanent, expected state (TMDB has no network search); on the admin page
it is a work item, on the viewer's TV it is not a defect to report. Both tile variants carry exactly one
caption line, so the grid stays regular.

**FR-R243-3 — Render, never compute.** Groups, counts, order and `logoUrl` all arrive resolved from 216.
The client counts nothing, filters nothing into a count, and caches nothing past the payload. Formatting
a number against its own string table is not computing (FR-R243-9).

**FR-R243-4 — The header states the scope, including when it is narrowed.** Under the tab bar: the group
count, the title count, and — when 216 answers `scoped: true` — one short line saying the counts are what
this profile can watch. A kids profile seeing 6 studios where an adult sees 11 must be told why, or the
wall reads as a broken library rather than a filtered one.

**FR-R243-5 — Select opens the browse grid, seeded.** A tile opens the existing browse page filtered to
that value — **not** a new list surface. Genres reuse R221's genre seed verbatim. The crumb names the
path it came from (`◂ Discover · Networks · Dansk TV`), the page title is the value, and the facet bar,
sort and Back behave exactly as they do from any other entry point. Back returns to the tab, on the tab.

**FR-R243-6 — The count on the tile equals the number of titles on the next screen.** Restated as a
client requirement because it is the one thing that makes this surface worth having: the tile and the
grid run the same seed (216 FR-216-7). If they can ever disagree, the wall is worse than no wall.

**FR-R243-7 — Nothing here is a new focus concept.** Wall rows are ordinary D-pad rows: Left/Right along
a row, Up/Down between rows, Up from the first row returns to the tab bar. Switching tabs **keeps focus
on the tab you pressed**, so the segment stays steerable and Down drops into the wall. No tile is a focus
trap, nothing auto-focuses on load, and no reveal, dwell or animation from 202/R240 applies here.

**FR-R243-8 — Empty states are one sentence.** A taxonomy with no groups for this profile renders a
single line and no grid furniture. A tile that would be empty never exists (216 FR-216-6), so there is no
empty-grid case to design.

**FR-R243-9 — Ten strings, three languages.** Tab labels, the three sub-headings, the counted group nouns
(`{n} studios` / `{n} networks` / `{n} genres`), `{n} titles` with a **singular form**, the scoped-profile
line and the empty line — en/da/fo, no string composed from fragments at runtime. Danish uses
*Stationer* for networks (not *Netværk*, which is the wrong sense); Faroese uses *Støðir*.

### The phone

**FR-R243-10 — Same index, phone-shaped.** Ravilo Mobile gets the same three tabs on its tab strip, a
**2-up** wall (3-up for genres) with the same two tile variants and the same captions, and a tap opens a
poster grid of that value's titles with a back affordance naming the tab. Counts come from the same
endpoint and must equal the TV's for the same profile — the phone must not re-count what it holds.

## Non-goals

- **No tags, no age-rating tab, no trackers.** Three of the admin page's six tabs are operator tools.
- **No sort or filter control on the wall.** Order is the server's (count descending); a wall of ten
  tiles does not need a sort on a remote.
- **No requestable titles mixed in.** These counts are what you can press play on. Seerr's catalogue
  stays in the Request tab (216 non-goals).
- **No new artwork.** The wall shows logos the artwork pipeline already captured, and never asks for one.
- **No Channels rail crossover.** Home's Channels & Collections are operator-curated; these are scanner
  metadata. Two meanings of "studio" on one screen would be a worse product than either.
- **No focus detail here.** 202/R240's line and row-open are Home-row behaviour; a logo tile has no
  synopsis to reveal and no dwell to wait for.

## Acceptance

Run on the stue TV with an adult profile and the kids profile, and on the phone.

1. Discover shows five tabs with both integrations configured, and still shows **Studios · Networks ·
   Genres** with neither configured — the nav item does not disappear.
2. Studios shows only films' companies, Networks only series' broadcasters; a title whose company names
   two appears under both.
3. Every tile with a captured logo shows it with the name beneath; every tile without shows the name as a
   wordmark and the count only. No tile anywhere says "no logo".
4. Selecting any tile opens the browse grid, and the grid's title count **equals the tile's count** —
   spot-checked across at least ten values per kind, on both profiles.
5. The crumb reads `◂ Discover · <tab> · <value>`, on one line, and Back returns to that tab with the tab
   still selected.
6. On the kids profile the wall is shorter, every count is smaller, and the header states that the counts
   are scoped. No unrated title is reachable through any tile.
7. Up from the first wall row returns to the tab bar; pressing a tab leaves focus on that tab; Down
   enters the wall. No tile is reachable that does nothing.
8. A genre tile opens the same page an R221 genre chip opens for that genre.
9. Danish and Faroese: every label translated, `1 title` singular correct in all three, nothing clips in a
   tile at 4-up or 5-up.
10. Phone: the three tabs render the same groups and the same numbers as the TV for the same profile; a
    tap opens the filtered grid; back returns to the wall.

## Source references

- `design/ravilo/ravilo-app.js` — `discTabs()` (tab order + gating), `renderTaxonomy()` (header, scope
  line, 4/5-up wall), `taxoTile()` (the two tile variants and the caption rule).
- `design/ravilo/ravilo-browse.js` — the taxonomy seed, resolved through the same `taxoValues()` that
  produced the count, plus the crumb.
- `design/ravilo/ravilo-data.js` — `taxonomy`/`taxonomySummary`/`taxoValues`/`libraryFor`/`normAge`: the
  demo's stand-in for 216, deliberately one resolver so TV, phone and grid cannot disagree.
- `design/ravilo/ravilo.css` — `.taxo*`; also two pre-existing defects fixed in the same pass: a long
  browse crumb wrapped against the `h1`'s own width, and a short browse result stretched its posters
  across six columns (now capped at the 6-up column width).
- `design/app/metadata.html` — the admin surface this mirrors.
- Related: **216** (the index), **R187** (browse), **R221** (genres + genre seed), **R233** (scoped views),
  **155** (age normalisation), **192** (the only source of logo artwork).

## Open questions

1. **Should a household be able to turn these tabs off?** 216 FR-216-10 ships them ungated. A per-user
   switch is a follow-up if anyone asks for one — nobody has.
2. **Count-descending, or A–Z?** Count-descending answers "where is most of my library from", which is
   the question the wall poses; A–Z answers "where is X", which is what search is for. Shipping
   count-descending, untested with a viewer.
3. **The phone tab strip is now seven chips** (Home · Movies · Series · Top 10 · Studios · Networks ·
   Genres) and scrolls horizontally. Whether the three belong behind one "Browse" chip on the phone is a
   layout question the TV does not have.
4. **Does "Studios" read as the wrong word to a viewer** who also sees Home's Channels rail? The admin
   page calls them studios because TMDB does. If it confuses the household, the tab label — not the
   grouping — is what changes.
