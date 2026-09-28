# Phase 285 — The Dashboard is one overview of what could be fixed

> Owner, 2026-09-28: every warning, suggestion and count on the Dashboard should read as **one overview of what
> could be fixed** — grouped by what it is about, and saying what fixing means. And: *"We want to just fully remove
> this dock from our application."*

## Status

`Planned` — written 2026-09-28 from `specs/design-brief-dashboard-one-overview-2026-09-28.md` (§A–§G) and the mockup
`design/app/index.html` on `design/app/dashboard-data.js` · `dashboard.js` · `dashboard.css`, with the dock removed
from `design/app/app-shell.js`. **Not dev-reviewed.** Number verified free on `main` 2026-09-28. A presentation
phase in the shape of 146/257, plus one count-endpoint change (FR-285-9).

**Supersedes:** 146's fixed 28-row attention list and `ATTENTION_ROW_ORDER` · 257's advisor card placement · the
*Recently processed* column · the *Quick actions* chips · the floating attention dock (117/146) everywhere.
**Amends:** 212/257/273 (the advisor cards in Settings become one indicator each) · 150/SEG (a missing intro is not
reported).

## Decisions (owner, 2026-09-28)

| # | Question | Answer |
|---|---|---|
| Q1 | Direction | **Direction 2 — severity first, domain as chips.** (The brief's lean was 1) |
| Q2 | What a row counts | **The things that have the problem** — *1 065 tracks*, not *48 series*; the titles go in the sentence; the headline sums the same unit per row |
| Q3 | Rows at zero | **Hidden** |
| Q4 | Fold | **3 per group, then *+N more*** |
| Q5 | Host findings | **On the Dashboard as *This server*.** Settings holds settings only: at most one small indicator (*5 findings · see the Dashboard →*) |
| Q6 | Intro detection | **A missing intro is not a jellystructure problem** — no *No intro or credits found* row, no *Waiting for intro detection* row |
| Q7 | *Recently processed* | **Replaced by *Since your last visit*** |
| Q8 | Quick actions | **Folded into the groups they serve**; *Scan* and *Activity* stay in the pagebar |
| Q9 | Per-kind counts | **Yes** — Films and Series split server-side |
| Q10 | The floating dock | **Removed from the application** (§D rule 9) |
| — | A row that stops playback | **Critical** (owner confirmed the lean) |
| — | *Since your last visit* is measured from | **The last time this admin opened the Dashboard** (per admin user, server-side) |

## Requirements

**FR-285-1 — Eight domains, one list.** Every existing attention type, advisor finding and card lands in exactly one
domain: **Films · Series · Music · Audiobooks · Subtitles · Jellyfin · This server · Services** (brief §C's table).
The page is **one list ordered critical → warning → info**, then by count; each row carries its domain as a chip.
The domains are **filter chips** at the top (*All · Films 4 · Series 6 · …*, a count per chip, absent at zero);
picking one filters the list, and the choice is remembered.

**FR-285-2 — One row grammar** for all three of today's vocabularies:

> severity mark · **label** — one plain sentence (the titles it concerns, named) · **count** of the things with the
> problem · domain chip · **what fixing means** — one of: **one click here** (Repair · Convert… · Apply in Bazarr ·
> Re-check · Remove) · **open the item** (Find match…, the Tracks tab, the segment editor) · **change it elsewhere**
> (Jellyfin's exact label and path) · **for information**.

Every row opens somewhere. The count is **the unit the problem is in** (tracks, episodes, files, albums, findings)
and says it (*1 065 tracks*); the sentence names the titles (*in 48 series, most in Sommeren ’92*).

**FR-285-3 — Severity comes from the server** for every row, in the advisor's three words (critical · warning ·
info). `ATTENTION_ROW_ORDER` goes. **A row that stops playback is critical**: zero audio · MKV structure · damaged
file · cover muxed as video · duplicate episode files · an advisor finding marked as stopping playback.

**FR-285-4 — One fold rule.** 3 rows per severity band in the unfiltered list and 3 per domain when filtered, then
*+N more*; *Show all* on the page remembers itself. With everything folded the page fits a 1080p screen.

**FR-285-5 — Zero is silence.** A type at zero is absent; a domain with nothing is absent (and so is its chip); the
page with nothing renders one sentence: *Nothing needs you. Last scan 13:30, next 14:30.* Empty filters stay
reachable from Library's facets.

**FR-285-6 — The headline** above the list: the critical and warning counts in words (*2 critical · 18 warnings*) and
the domain chips. The sidebar badge and the stat tile show **the same number of rows** (not a sum of mixed units);
`/api/stats.issues` follows or is retired.

**FR-285-7 — Removed rows.** `no_segments` (*No intro or credits found*) and the segment queue's *waiting* row are not
reported anywhere on the Dashboard (owner: not a jellystructure problem). The segment editor keeps its own
filters; `segments_lowconf` stays as a Series warning. macOS leftover files are not a row (284 FR-284-13 removes them
on every scan).

**FR-285-8 — *Since your last visit*** replaces *Recently processed*: one strip under the headline, what changed by
domain since this admin last opened the Dashboard (*2 albums matched · 41 subtitles checked, 7 out of sync · 1
damaged file replaced*), or *Nothing new since Tuesday 21:40.* The raw log stays on Activity. The *last opened*
time is stored per admin user on the server, updated when the page is left.

**FR-285-9 — Per-kind counts.** `/api/triage/count` (or its successor) splits every file-level type by **film /
series** so the Films and Series chips count honestly.

**FR-285-10 — Quick actions fold into the rows they serve** (*Repair corrupt artwork* on the missing-poster row,
*Sync NFOs* on Jellyfin's NFO row, …). The pagebar keeps **▶ Scan library** (split: *full rescan*) · *next run* ·
*Activity*.

**FR-285-11 — The scan banner stays at the top** in every state (scanning · paused for a TV · stopped with
subtitles finishing · complete · failed to start).

**FR-285-12 — Settings holds settings only.** The Jellyfin advisor, the per-library findings, the Bazarr advisor
and the memory-budget swap line leave Settings; each place they stood shows one indicator: *N findings · see the
Dashboard →* (linking to the list filtered to that domain), absent at zero.

**FR-285-13 — The attention dock is removed from the application.** Not hidden: gone from every page — the
floating dock, *Show attention dock*, *Closed the dock?*, the dock's own item queue, `openAttentionDock`, its ⌘K
entries and its n / p / o keys. The sidebar count stays as a plain link to the Dashboard.

**FR-285-14 — States** (all reachable in the mockup's preview fence): all clear · one critical (the NFO saver) ·
first run before any scan · Jellyfin unreachable (its domain and *This server* replaced by one line) · Bazarr off
(Subtitles absent) · Seerr off · scanning / paused / stopped · one type with 1 065 rows (the fold holds) · music
library only (Films/Series absent) · folded vs *Show all* · a domain whose only rows are information · *Since your
last visit* with nothing new.

## Out of scope

New checks; any change to what a row's target page does; the Activity page.

## Acceptance

1. On the household server the list opens with the 2 critical rows first, each saying what fixing means.
2. No row shows `✓ 0`; picking *Music* shows only music rows and the choice survives a reload.
3. The untagged-tracks row reads *1 065 tracks* with the series named in its sentence.
4. No page has a floating dock; ⌘K has no dock entries; the sidebar count opens the Dashboard.
5. Settings shows no advisor finding, only *N findings · see the Dashboard →* where one stood.
6. No Dashboard row mentions a missing intro.

## Open questions (for the dev review)

1. Whether `/api/stats.issues` is kept (as a row count) or retired with its last caller.
