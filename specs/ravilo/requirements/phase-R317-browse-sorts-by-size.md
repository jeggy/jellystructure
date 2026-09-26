# Phase R317 — Ravilo's browse page sorts by size

> Owner, 2026-09-26: *"Let's add a new filesize ordering support, both in Ravilo and in Jellystructure
> library and as a sort order within ravilo content rows configuration within jellystructure."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client (`ravilo-ui`: TV, phone, web) and strings. Needs **Phase 268** first: the size, the
one rule that computes it, and `BrowseCard.size_bytes`. **Numbering:** verified against `STATUS.md` the
same day — Ravilo taken through **R316**.

## What is there today

Movies, Series and the phone's Library all open `SeededBrowseScreen`, which holds the whole matching set
and sorts it on the client. Its fields are `SortField { RECENT, TITLE, YEAR, MATURITY, IMDB }`, each with a
default direction and a direction sub-label (*Newest first*, *A–Z*, *Highest first*). Every field reads a
value the server put on `BrowseCard` or its `MediaCard`: IMDb, for example, rides on `BrowseCard` only, a
deliberate browse-only field. R253 maps a row's served `sort_by` onto these fields when *See all* opens
(`initialBrowseSort`), and has no entry for a key it does not know: it falls back to *Recently added*.

## Requirements

**FR-R317-1 — A *Size* sort.** `SortField` gains `SIZE`, reading `BrowseCard.size_bytes` (268 FR-268-7).
Default direction **largest first**. Direction sub-labels: *Largest first* / *Smallest first*. Titles of
unknown size go last in both directions, ties by title, like the page's other fields. The client sorts
only by the number the server sent: it never derives a size (a series' total is 268's
`sizeBytes()`).

**FR-R317-2 — A row ordered by size opens *See all* by size.** `initialBrowseSort` maps `"size"` to
`SortField.SIZE`, with the row's direction, so a row the admin ordered by size (268 FR-268-6) opens its
*See all* page in that same order (R253 FR-R253-2).

**FR-R317-3 — Strings, in all three languages.** Three new keys: `browse.sort.size`,
`browse.sort.largest`, `browse.sort.smallest`.

| | en | da | fo |
|---|---|---|---|
| `browse.sort.size` | Size | Størrelse | Stødd |
| `browse.sort.largest` | Largest first | Største først | Størst fyrst |
| `browse.sort.smallest` | Smallest first | Mindste først | Minst fyrst |

The Faroese follows R288's pattern (`Hægst fyrst` / `Lægst fyrst`). The lexicon is regenerated in the
same commit (`check-i18n-spelling.sh --update-lexicon`).

**FR-R317-4 — Old server, no sort.** On a backend without 268 no card carries `size_bytes`. The *Size*
option is then not offered at all, rather than shown and sorting everything as unknown. It appears once
any loaded card has a size.

## Non-goals

- Showing a title's size on a tile or a detail page. The owner asked for the order, not for viewers to see
  gigabytes.
- The Discover walls and Search, which have no sort control.

## Acceptance

1. On the TV, Movies → Sort → *Size*: the largest films first. The sort chip reads *Size ▼*, the popover
   says *Largest first*, and flipping it gives *Smallest first*.
2. Series → *Size*: the series order matches the admin Library's *size, largest first* for series.
3. A content row the admin ordered by size, *See all*: the page opens sorted by *Size*, in the row's
   direction.
4. In Faroese the options read *Stødd*, *Størst fyrst*, *Minst fyrst*.
5. The phone and the web app offer the same option.

## Dev review (2026-09-26, against `main` `0e5e434f`)

1. **Everything is in `SeededBrowseScreen.kt`:**
   - `SortField` `:87`;
   - default directions `:101-107`;
   - the sort itself `:320-327`, which adds `SortField.SIZE -> filtered.sortedBy { it.sizeBytes ?: … }`
     with unknowns placed last explicitly (not by a sentinel value that flips with the direction);
   - labels `:646-650`, direction labels `:655` onward;
   - `initialBrowseSort` `:92-94`, which gains `"size" -> SortField.SIZE`.
2. **The field** is 268's `BrowseCard.size_bytes` (`Models.kt:350-364` gains it as `sizeBytes`). The
   client never sums or derives a size.
3. **FR-R317-4's "offered only when a card has a size"** reads the store's loaded items once per load;
   the popover's list is built from `SortField.entries` minus `SIZE` when no card has one.
4. **Strings.** Three keys in three files. Run `check-i18n-spelling.sh --update-lexicon` so `stødd`,
   `størst` and `minst` enter the Faroese lexicon in the same commit.

**Net effect.** One enum value, one sort branch, one mapping, three strings. Depends on 268 being live.
