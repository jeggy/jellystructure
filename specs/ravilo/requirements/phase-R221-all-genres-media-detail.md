# Phase R221 — Every genre on a media detail, and a way out of the page

> A Ravilo detail page shows **one** genre. *Severance* is filed in the library as Drama, Mystery,
> Sci-Fi and Thriller; the hero renders `Drama` between the year and the age badge and gives no hint
> the other three exist. The fix is not only "show the rest" — it is deciding what genres are *for* on
> this screen. This phase makes them the front door to the browse page, the same way R190 made a cast
> face one.

**Status:** ✓ Built — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) (design-authored 2026-09-01, not yet dev-reviewed)

Design: `design/ravilo/Media Detail Genres - Directions.html` (Direction B chosen, with C's primacy
tint; A and C recorded as rejected). **Built into the mockups 2026-09-01:** `design/ravilo/ravilo.css`
(`.dhero-genres` / `.gchip2`), `design/ravilo/ravilo-app.js` (`genreRowHTML`, chip → browse, Back
focus restore), `design/ravilo/ravilo-browse.js` (genre seed), `design/ravilo/ravilo-i18n.js`
(`genre_one`/`genre_many`), `design/ravilo/Ravilo Mobile.html` (phone chips), and
`design/ravilo/ravilo-data.js` (a canonical per-title genre list — the demo catalog previously let the
same title carry different single genres in different rows).

**Noir resolved while building:** the amber accent did read as a warning state, so
`[data-skin="noir"] .gchip2.lead` drops the tint and marks the primary genre with weight and full-ink
colour instead. Open question 3 below is answered.

## Current state

`ravilo-app.js` builds the detail hero's meta line from a single string:

```js
sub2: `${item.year || ''} · ${item.genre || ''} · <b>…`
```

`item.genre` is one value. The library behind it is not single-valued — the admin's Metadata page
manages genres as a set per title, the workbench has a multi-value **Genre** facet, and the browse page
(R187) filters on genre with counts. Every layer of the system treats genres as a list except the one
screen a viewer actually reads.

The destination already exists too: R187's browse page with its Genre facet, reachable today only from a
row's **See all** tile. R190 established the pattern of a detail-page element seeding it — press OK on a
cast face, land on that person's filmography. A genre chip is the same move with a simpler seed.

## Goal

Every genre visible on the detail page, in TMDB's own order, with the primary one legible as primary —
and each one a way into the browse page filtered to it.

## What was rejected, and why it matters

Two cheaper directions were drawn and turned down; recording them so the decision isn't relitigated:

- **A — all genres in the existing meta line**, dot-separated with a `+N` tail. Costs nothing and is
  impossible to get wrong. Rejected because that row already carries format, year, certification and
  IMDb score; four more values turn the most-read line in the hero into a spec sheet, and it leaves
  genres as inert description.
- **C — primary genre promoted into the kicker**, the rest as a quiet "also …" trail. The most
  editorial and the calmest, and it needs no new focus row. Rejected as the whole answer because it
  asserts that genres two-to-four are footnotes — true for *Severance*, wrong for a Comedy/Horror — and
  because it still gives the viewer nowhere to go. **Its primacy idea is kept** as the accent tint on
  B's first chip.

## Requirements

### A. The genre row

#### FR-RV-GEN1-1 — Genres get their own labelled row
Genres leave the meta line and become a row of their own between the meta line and the synopsis, built
from the **same pattern as the AUDIO / SUBTITLES flag strip** directly below it: a 13px dim caps label,
then the values. Nothing new is invented — an existing pattern gets a second use, which is why the row
reads as native rather than added.

Label is **Genres**, or **Genre** when the title has exactly one. A title with no genres renders **no
row at all** — never a label with an empty row beside it.

#### FR-RV-GEN1-2 — TMDB's order, never alphabetical
Genres render in the order the library stores them, which is TMDB's order. The first genre is the
**primary** one and carries an accent tint (`--accent` at ~20% over the chip's base). Re-sorting
alphabetically would destroy the only signal we have about which genre the title actually is.

#### FR-RV-GEN1-3 — Cap at four visible, remainder in a focusable `+N`
At most four chips render; the rest collapse into a `+N` chip. The row **never wraps to a second line
on TV** — the hero has no vertical room, and a wrapped row pushes the Play button below the fold on a
720p panel. On the phone (R60) the row wraps freely and there is no cap.

### B. Navigation

#### FR-RV-GEN1-4 — OK on a genre opens browse, seeded to that genre
Each chip is focusable. OK opens the R187 browse page seeded to that genre, scoped exactly as R190's
person seed is: the seed is the page's subject, not a removable chip, the full facet bar is available to
narrow further, and **Back returns to the detail page with focus restored to the same chip**.

`+N` opens browse with the title's **full genre set** applied as facet values, so "everything like this
one" is one press away.

#### FR-RV-GEN1-5 — Focus order keeps Play where it is
The genre row sits **above** the synopsis, so the path down the hero is: title → genres → synopsis →
actions. This is the reason the row goes above rather than below the buttons — it adds one stop on the
way down without moving Play further from the top of the page than the synopsis already puts it.

Left/Right moves along the chips; Down from any chip reaches the synopsis. Focus styling is the
standard Ravilo focus treatment (light fill, dark ink, accent ring) plus a `›` affordance so the chip
reads as navigable rather than as a label.

### C. Where genres do *not* change

#### FR-RV-GEN1-6 — Rows, tiles and the hero carousel are untouched
A poster tile's subtitle keeps showing one genre. Four would not fit, and the tile is not where this
question gets asked. The Home hero carousel likewise keeps its single-genre line.

## i18n

#### FR-RV-GEN1-7 — en/da/fo
One new string: the row label, in singular and plural (`Genre` / `Genres`, `Genre` / `Genrer`,
`Sjangur` / `Sjangrar`). The genre **values** themselves are a separate question — see open question 2.

## Skins

The accent tint on the primary chip must be checked in all three skins. In **Noir** the accent is amber
and a tinted chip risks reading as a warning state; if it does, Noir's primary chip should fall back to
a heavier weight or a brighter ink rather than a tint.

## Open questions for dev review

1. **Does the Ravilo payload carry the full genre list?** The admin manages genres as a set and the
   browse facet filters on them, so the data almost certainly exists — but `item.genre` is singular in
   the client model and may be flattened at scan or serialization time. This is the one thing that could
   turn a UI phase into a backend phase; confirm before scheduling.
2. **Localised genre names.** Ravilo is translated (en/da/fo) but genre values come from TMDB in
   English. TMDB returns localised genre lists per language, so this may fall out of Phase 184's
   metadata-language work for free — or it may need its own fetch and a genre-name map. Until then the
   chips leak English, which is the status quo (today's single genre does too).
3. **Does a genre chip's browse land in the title's own visibility scope?** R219 made scope explicit for
   Continue Watching; a genre browse opened from a channel-scoped detail page should presumably inherit
   that channel's scope rather than the whole library. Worth deciding rather than defaulting.
