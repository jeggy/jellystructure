# The admin's Songs tab at eleven thousand songs

**Date:** 2026-10-14 · **Filed from:** DEB-1107 (*"The songs tab in the jellystructure page is very long and
seems like it's crashing the website or at least using a lot of memory."*)

## Conclusion

The report is real and the cause is not subtle: **Library → Music → Songs renders every song in the library in
one go, and the library has grown 180× since that view was specified.** Phase 278 was written on 2026-09-28
against a music library of **60 tracks / 30 albums**; it reads today against **11 011 track rows (10 931 not
missing)**. Nothing in the route, the DTO or the page caps the list, so one visit to the tab is a ~4.8 MiB JSON
response, a single ~8.9 MiB HTML string built inside Wasm, and roughly **340 000 DOM nodes in one
`<table>`** — and then the page rebuilds all of it from scratch on a click as small as opening a filter
dropdown.

The repair is small and has a working precedent on the same page: **page the Songs rows on the wire and append
them, exactly as the films Library already does** (`Library.kt`'s `loadMore` / `LIB_SLICE = 60` / `#lib-sentinel`).
The one thing that needs a decision rather than a patch is what *select all shown* and *Set version…* mean once
the page no longer holds the whole library — that is the single owner question at the foot of this note.

Specced as a dated amendment inside [phase 278's spec](../requirements/phase-278-music-in-the-admin-ui.md)
(FR-278-14/15/16); 278's `STATUS.md` row carries the dated gap.

**Decided the same day.** Owner, 2026-10-14: *"Just make the website performant so there is no need to sending
all songs to the frontend, we much rather would like a endless scrolling page etc."* — which is option A below,
and which also settles the open question at the foot of this note without it needing to be asked again: a list
of 10 723 ids is the same mistake pointing the other way, so *select all shown* means the filter
(**FR-292-16**, a dated amendment in 292). Under the same directive the Albums view's 809 cover requests became
**FR-278-16**; the check that followed it — the rest of the admin, below — found no third offender.

## What was measured

Read-only, from a WAL-complete copy of the production database. No request was made to prod, no service touched.

| | |
|---|---|
| `music_track` rows | **11 011** (10 931 with `missing_since is null`) |
| `music_album` / `music_artist` rows | 809 / 377 |
| Distinct recording keys (the folded row count 305 produces) | **10 723** |
| Tracks carrying lyrics (`synced` 9 315 + `plain` 875) | **10 190** |
| `music_version_choice` rows | **0** |
| What 278 was written against (its own FR-278-1 example line) | *30 albums · 60 songs · 23 artists* |

Two of those rows matter more than their size suggests:

- **Folding does not save the page.** Phase 305's *one copy of every song* takes 10 931 files down to 10 723
  rows — **2 %**. The default Songs view is a ten-thousand-row view, folded or not. And a Dashboard triage key
  forces `copies=all` (293's promise, `MusicBrowse.kt:126`), so a Dashboard row that opens Songs is the
  **worst** case, not a narrowed one.
- **Every row is in the heaviest state.** `music_version_choice` is empty and MusicBrainz has set no version on
  any row, so `vrBadges` emits the `＋ Version` affordance for all 10 723 rows rather than nothing; and 10 190
  of them render `MU_LYR`, a **200-character inline SVG** (`MusicUi.kt:18`) that the browser parses into four
  more element nodes each.

## Where the cost is

Derived by building each row's HTML from `MusicLibrary.kt`'s own template (`body()`, the `"songs"` branch,
lines 247–264) against the real rows:

| Stage | Cost for one visit to the tab |
|---|---|
| `GET /api/music/browse?view=songs` JSON | **≈ 4.8 MiB** (avg 461 B × 10 931 rows) |
| gzip of that response | one deflate pass over 4.8 MiB, per request, on the single-threaded native server |
| The HTML string `body()` returns | **≈ 8.9 MiB** — 6.16 MiB of row markup + ≈ 2.0 MiB of repeated lyrics SVG + ≈ 0.7 MiB of `＋ Version` chips |
| `root.innerHTML = …` | one Kotlin → JS string conversion of ~9 M chars, then a parse |
| The resulting DOM | ≈ 32 nodes/row ⇒ **≈ 340 000 nodes** in one table |
| Layout | `.mu-tbl` is `width:100%` with `border-collapse:collapse` and **no `table-layout`** (`design/app/music.css:36`), so auto layout must measure all **~86 000 cells** before first paint |
| Hover | `.mu-tbl tr:hover td{background:…}` (`music.css:39`) repaints against that table on every mouse move |

**The wire is not the bottleneck.** R316's gzip takes the 4.8 MiB response to something in the high hundreds of
KB, and this is a LAN. The two real costs are the server's encode+deflate pass and everything in the client
column above.

### The multiplier: `muRender` rebuilds the whole table for a click on anything

`muRender` (`MusicLibrary.kt:122`) reassigns `root.innerHTML` with the facet bar, the active-filter bar, the
selection bar **and the entire body** every time it is called. It is called by:

- ticking one song's checkbox (`data-vsel`, line 311);
- **opening or closing a filter dropdown** (`data-fopen`, line 323);
- clicking anywhere outside an open facet (the document listener, line 93);
- *select all shown* / *clear* (lines 312–313) — and *select all shown* first puts all 10 723 ids into
  `muSongSel`;
- each 1.5 s tick of `muPollMatch` while a MusicBrainz pass is running, if the button has gone (line 405).

So the ~8.9 MiB build, the string conversion, the parse and the 86 000-cell layout are not paid once on arrival
— they are paid again for a dropdown. That is what turns "very long" into "crashing".

And a keystroke in the search box (`muLoad`, debounced 250 ms, line 81) pays the **whole** round trip again:
server pass over 10 931 tracks, 4.8 MiB encode, gzip, parse, rebuild.

### What the server does per request

`MusicBrowse.browse` for `SONGS` (`MusicBrowse.kt:123–144`) filters 10 931 tracks, computes facet values per
track, sorts with a comparator doing map lookups per comparison, and maps every surviving row to a
`MusicSongRow`. The snapshot and both of its indexes (`MusicSongIndex`, `MusicVersionIndex`) are
`by lazy` on a **cached** snapshot (`MusicStore.kt:58,83`), so they are **not** rebuilt per request — that part
is fine and should not be blamed. What is per-request is the O(n) filter/facet pass, the O(n log n) sort, the
row mapping and the 4.8 MiB serialize+gzip, on the same single-threaded Ktor server that serves live playback
to the household and to users beyond it.

## The phases this touches

| Phase | What it owns here |
|---|---|
| **278** | FR-278-1/3 — the Music kind, the Albums · Artists · Songs segment, and the Songs table itself. The uncapped list is this phase's, written when the library was 60 tracks. |
| 287 | Put Artists first, so the Songs tab is now one click away rather than the landing view. Does not change the list's size. |
| 292 | FR-292-10/12 — the version chips on each row, the song selection and *Set version…*. Owns the selection question below. |
| 293 | A Dashboard key narrows the list *and* forces `copies=all`, so a Dashboard row opens every file. |
| 305 | FR-305-13 — folded rows and *Show every copy*. Measured above: folds 2 %. |
| **R101-era `Library.kt`** | The films Library's infinite scroll — the precedent to reuse, in this same frontend. |

## The precedent, named precisely

`src/wasmJsMain/kotlin/dev/jellystructure/ui/Library.kt` already solves this problem for a library that is
larger still:

- `LIB_SLICE = 60` (line 45) with `libSlice` / `libLoadedCount` / `libTotal` / `libEndReached` (45–52);
- `loadMore(scope, reset)` (line 604) — `reset = true` clears the grid and starts a fresh sequence for the
  current filters, otherwise it **appends**;
- it keeps fetching while `elemNearViewportBottom("lib-sentinel", 700)` is true, so a partial last row is never
  mistaken for the end (line 678);
- re-entry guarded by `libLoading`, with `libPendingReset` so a filter change arriving mid-fetch is honoured
  rather than lost (line 686);
- `#lib-total` carries the whole-set count, so the honest total survives paging;
- clicks are **delegated** to the grid, not bound per card (`bindPosterClicks`'s own note, line 690).

The server half exists too: `MediaApi.list(…, page, pageSize)` → `/api/media` has taken `page`/`pageSize` since
the 88–90 round that came out of `backend-performance-investigation.md`. `/api/music/browse` is the one list
route in the admin that never got it.

Everything the amendment asks for is a transcription of that, which is why no design round is needed for the
scroller itself.

## Options

**A — Page the Songs rows and append them (recommended).** `/api/music/browse` gains `page` / `page_size`,
applied to the Songs rows **only after** the facet counts, `total` and `folded` are computed — those are
already computed over the whole filtered set (`apply()` then `shown.size`), so they stay honest for free. The
page appends `<tbody>` rows at a sentinel and splits `muRender` so the chrome re-renders without the body.
Per request: ~50 KB instead of 4.8 MiB. Per dropdown: nothing. Builds on code that already works on the same
page, needs no design round, and fixes the server cost as a side effect. The work is one engineer, backend +
admin, no migration.

**B — Cap the list and let the filters be the way through.** Return the first 500 rows with a line saying *500
of 10 723 — narrow it with the filters*. One `take(500)` and one sentence; cheapest thing that stops the crash.
But it turns the tab into a sampler: there is then no way to walk the library by hand, and *select all shown*
becomes a lie about 500 of 10 723 songs. Worth keeping only as the fallback if A has to wait.

**C — Virtualise the table.** Best end state, most code, and no precedent in this repo. It also cannot be done
over today's markup: a virtualised table needs `table-layout: fixed` and declared column widths, which is a
`design/app/music.css` change and therefore a design round. Not now.

**Recommendation: A**, with the row-render split landing in the same piece — without it, appending is pointless,
because the first facet click throws the appended pages away.

## The owner question — asked, and answered by a directive instead

**Answered 2026-10-14, before it was read as written.** The owner's reply was *"I'm confused what you are
asking? Just make the website performant so there is no need to sending all songs to the frontend, we much
rather would like a endless scrolling page etc."* — a fair verdict on the question as well as an answer to it.
The fork below is real, but it was pitched at the wrong altitude for a discovery card: it asked him to
adjudicate the semantics of a selection bar when what he had reported was a page that falls over. **The
directive decides it:** option (i), because a 10 723-id request body is the same mistake the directive names.
Kept as written for the record of what was asked, and specced as **FR-292-16**.

The fork, as it was put:

> **At 10 723 songs, what should *select all shown* and *Set version…* mean?**
>
> Today *select all shown* means every row in the response, and `POST` sends the ids — at 10 723 × 32 chars
> that is a ~350 KB request body, and the bulk panel then counts them client-side. Once the page holds 100 rows
> at a time, "shown" has to mean one of two things:
>
> - **(i) every song matching the current filters** — the server takes the *filter*, not a list of ids, and
>   answers *Set version on 10 723 songs*. Honest, scales, and matches what the words say. It is a new shape
>   for 292's bulk call.
> - **(ii) every song loaded so far** — no server change, but the number moves as you scroll, which is the kind
>   of thing that gets a wrong version written onto a thousand songs.
>
> **Lean: (i).** It keeps the sentence true and it is the only one of the two that can set a version on a whole
> filter in one action, which is what the facets were built for.
>
> If this is not answered, the paging piece ships with *select all shown* temporarily meaning (ii) and the
> number stated as *N loaded of M* so it is at least not misleading — and the question comes back.

## Also found, not the reported symptom

Deliberately **not** folded into the amendment; each is the owner's call whether it is worth a phase.

1. **The Albums view loads 809 covers at once.** `muCoverHtml` emits a cover URL per cell with no
   `loading="lazy"`, so arriving on Albums fires 809 requests at `/api/music/image/album/{id}` — against the
   same single-threaded server that is serving playback. Far below Songs in severity, and a one-attribute fix.
2. **Artists is fine** — 377 circles, and most are wordmarks rather than images.
3. **A Dashboard music row is the worst case of all**, because 293 forces `copies=all`: 10 931 rows with no
   folding. Paging fixes it along with everything else; worth saying out loud because it is the entry point the
   Dashboard invites.
4. **`.mu-tbl` has no `table-layout`**, so even a 100-row page measures every cell. Harmless at 100 rows;
   it becomes load-bearing if C is ever wanted. A `design/` change, so Rannvá's, not ours.

## What this note does not establish

- The browser was never driven. "Crashing" is the owner's word for what he saw; the numbers above explain a
  crash and a very large memory figure equally well, and which one a given browser does is not measured here.
  **The on-device/in-browser proof belongs on the QA queue, not in this note.**
- The 10 723 figure is the count of distinct recording keys (`recordingMbid`, else `trk:<id>`), computed in SQL.
  `MusicSongIndex` also folds by `music_same_song` and `music_sound_pair`, and both tables are empty on prod
  today, so the two agree now — but the real number comes from the index, not from this query, the day they are
  not.
- No claim is made about how long any of it takes in wall-clock seconds. Every number above is a count or a
  byte size, measured; none of them is a timing.
