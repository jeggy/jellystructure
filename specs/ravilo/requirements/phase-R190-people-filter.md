# Phase R190 — Filter by person (cast/crew) + Seerr overflow row

> A viewer watching something often wants "everything else with **this** actor/director". Today the
> cast & crew circles on a Movie/Series detail are focusable but inert (they only flash the name).
> This phase makes each one a **link into the browse page (R187) seeded to that person** — their
> whole filmography in the library — and, when Seerr is configured, appends a **Seerr overflow row**
> of requestable titles with that person that the library doesn't hold yet. This is the Seerr
> integration R187 deliberately deferred.

## Goal
Press OK on any cast/crew face on a detail page → open the browse page showing every library title
featuring that person, with the full R187 facet bar available to narrow further (by genre, year,
type…). If Seerr is enabled, a single row at the very bottom — after **all** library results —
offers "More with {name} · request on Seerr", each tile opening the normal Seerr request flow.

## Current state
- `castCircle` (`ravilo-app.js`) renders a focusable `.cast` face carrying `_cast = { n, r }`; its
  activate handler only `flash()`ed the name/role.
- The browse page (R187, `ravilo-browse.js`) already seeds from a **row**, a **kind** (Movies/
  Series), or the whole catalog. It owns the facet bar, sort, and count.
- `rankTile` + `seerrCatalog()` + `seerrEnabled()` already power the Discover/Seerr request tiles
  and their `discoverDetail` request flow.

## Requirements

### A. Entry point — the cast/crew face is a link
#### FR-RV-PPL1-1 — OK on a cast/crew face opens a person browse
Each `.cast` face on a Movie/Series detail activates into `go({ type:'browse', person:{n,r},
personFrom:<sourceTitle>, from:<detailView> })`. Focus shows an affordance (a `→` overlay on the
avatar) so it reads as navigable, not just a label. Back returns to the originating detail page.

### B. The person browse page
#### FR-RV-PPL1-2 — Seeded to the person's filmography
The browse page seeds to **every library title the person appears in** (production: a Jellystructure
people/credits query keyed by person id; the design build derives a deterministic filmography from
the catalog, always including the source title). All other R187 behaviour is unchanged: the full
facet bar (Genre · Type · Maturity · Year · Watched · Audio · Channel · Quality), sort (default
Recently added), live counts, D-pad popover ownership. The **Type facet stays** (a person spans
films and series).

#### FR-RV-PPL1-3 — Header carries the person, not a chip
Breadcrumb `◂ <sourceTitle> · <person name>`, the person's **name** as the page title, and the
**role/department** (`Detective`, `Director`…) as a meta line under it. The person is the seed, not
a removable filter chip — exactly as row seeds are handled in R187.

### C. Seerr overflow row
#### FR-RV-PPL1-4 — After all library results, when Seerr is on
When `config.seerr` is set **and** the page is in person mode, append **one** row below the entire
library grid: `⚡ Seerr — More with {name} · request on Seerr`. It holds requestable titles featuring
that person that are **not already in the library** (production: a Jellyseerr person-credits query
minus library matches; design build: a deterministic slice of the Seerr catalog excluding seeded
titles, capped at 12). Tiles are the standard `rankTile`s and open the existing Seerr
`discoverDetail` request flow; **Back from a request returns to the person browse**, not to Discover
(the `discoverDetail` `from` now carries the actual origin view). The row is person-scoped, not
filter-scoped — it does not react to the facet bar. No Seerr, or no overflow matches ⇒ no row.

### D. Admin: a Cast-or-crew facet in the shared filter workbench
#### FR-RV-PPL1-6 — Person is a first-class workbench facet
The Jellystructure admin **filter workbench** (the R32/Phase-140 boolean condition builder shared by
Library filters, Channels, and Content rows — `design/app/ravilo-builders.js`) gains a **Cast or crew**
facet under a new **People** group, alongside Studio/Network/Genre/Tag/Age rating/Audio. It is an
ordinary multi-value list facet: `is any of` / `is none of`, value picker with per-value item counts
(count-ordered, channel-scope-narrowed like every other facet), composable inside AND/OR blocks and
NOT groups. This lets an operator author a channel or row like *"Cast or crew is any of {person} AND
Genre is any of Crime"* — the authoring-side complement to the viewer-side person browse (A–C).
Production maps it to the same cast/crew index the people/credits query uses; the design mock derives a
deterministic 2–3-person cast per title from a shared pool so a person spans several titles.

## i18n
#### FR-RV-PPL1-5 — en/da/fo
One new string, `br_seerr_more` = `More with {name} · request on Seerr` (da: `Mere med {name} ·
anmod på Seerr`; fo: `Meira við {name} · bið á Seerr`), in `Strings.kt` and
`design/ravilo/ravilo-i18n.js`. The role/department line reuses the existing credit label.

## Reuse (don't rebuild)
The whole R187 browse engine (facet bar, sort, popover, `buildGridRows`); `rankTile`,
`seerrCatalog`, `seerrEnabled`, and the `discoverDetail` request flow; the `.cast` component.

## Dependencies & relationships
- **R187** — this is a fourth seed source (person) for the same browse page. Ships on top of it.
- **R81** cast & crew supplies the faces and, in production, the person ids the credits query needs.
- **Seerr pivot (R170/R171)** owns the request flow the overflow row reuses.
- Degrades cleanly: without Seerr the page is just the person's library filmography.

## Non-goals
- **No standalone people index / "browse all cast" page** — entry is always from a detail face.
- **No person artwork/headshots** in this phase (the avatar keeps the initials treatment).
- **No phone/mobile person browse** (follows with the mobile browse page).
- **No persistence** — person seed + filters are per-visit, on the view object.

## Acceptance
- OK on a cast/crew face opens a browse page titled with the person, breadcrumbed to the source
  title, role line beneath; Back returns to the detail. *(Verified in the design build.)*
- The grid holds the person's library titles and the R187 facets narrow within them. *(Verified.)*
- With Seerr on, a single `⚡ Seerr` overflow row sits below all library results; its tiles open the
  request flow and Back returns to the person browse. With Seerr off, no row. *(Verified.)*
- The admin filter workbench offers a **Cast or crew** facet under a **People** group with counted
  values, composable in AND/OR/NOT blocks like any other facet. *(Verified in the design build.)*

## Status
Design-complete, **`Planned`**. Design lives in `design/ravilo/ravilo-browse.js` (person seed +
Seerr row), `design/ravilo/ravilo-app.js` (cast face → person browse, `discoverDetail` origin fix),
`design/ravilo/ravilo.css` (face affordance + Seerr row), `design/ravilo/ravilo-i18n.js`, and
`design/app/ravilo-builders.js` (the admin workbench **Cast or crew** facet, §D).
