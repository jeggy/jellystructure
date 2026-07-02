# Phase 108 — Correct "Newly Added" sort (by JS created-at) + surface all timestamps on the detail page

## Goal
1. Sort Ravilo's **Newly Added** rows by the date a title was **added to Jellystructure** (its
   `createdAt`), **descending**. For a **series**, sort by the **most-recently-added episode's** added
   date (a new episode re-floats the series to the top), not the series record's own createdAt.
2. Make those timestamps **visible** on the jellystructure detail page so an admin can see exactly what
   drives the order: a **Timestamps** section on the Overview tab, per-episode timestamps in Seasons &
   episodes, a **newest-episode** panel, and a **"?"** explainer tying createdAt to Newly Added.

## Problem
"Newly Added" ordering wasn't clearly defined and (for series) didn't account for episodes trickling in
over time — a long-running series that just got a new episode could sit below a movie added months ago.
And none of the underlying timestamps (created / updated / scanned, in Jellystructure **and** Jellyfin)
were surfaced, so the ordering was opaque.

## Requirements

### A. Newly Added sort
1. **Movies:** order by Jellystructure `createdAt` desc.
2. **Series:** order by **max(episode.createdAt)** across the series' episodes (the most recently added
   episode), desc — so a series jumps back to the top when a new episode lands. Series with no episodes
   fall back to the series `createdAt`.
3. This is the sort key for the Ravilo **Newly Added** rows (both the split Movies/Series rows and the
   merged variant). Ties broken by title.

### B. Timestamps section — Overview tab (movie + series)
Add a full-width **Timestamps** section at the **bottom of the Overview tab** showing every timestamp we
hold, grouped by system:
- **Jellystructure:** Created, Updated, **Last scanned**.
- **Jellyfin:** Created (`DateCreated`), Updated.
Dates shown absolute (mono) with a relative "· N ago" hint where useful.

### C. "?" explainer
Next to **Created** (Jellystructure), a small **"?"** icon whose hover tooltip explains: *Created in
Jellystructure is when the title was first added to your library — the timestamp Ravilo's Newly Added rows
sort by (for a series, the most-recently-added episode).*

### D. Series — Seasons & episodes tab
1. **Under each episode:** show its timestamps — Added · JS, Updated · JS, Scanned, Created · Jellyfin —
   plus a compact "added <date>" on the episode row itself.
2. **Left side of the tab:** a **Newest episode** panel showing which episode is the most-recently-added
   and its timestamps, with the same "?" explainer, and a line noting this is what places the series in
   Ravilo's Newly Added.

## Data
Requires per-item and **per-episode** `createdAt` / `updatedAt` (Jellystructure), `lastScannedAt`, and the
Jellyfin `DateCreated` / `DateLastSaved` carried onto the detail DTOs. Episode `createdAt` is the field the
series Newly-Added sort keys on.

## Scope
- `design/app/media.html` — Overview Timestamps section (**built**).
- `design/app/series.html` — Overview Timestamps section; per-episode timestamps + row "added" chip;
  Newest-episode side panel; "?" explainers (**built**).
- Ravilo backend/feed: Newly Added sort key (movie createdAt; series max-episode createdAt).
- Detail DTOs + scanner: expose created/updated/scanned (JS) + Jellyfin DateCreated/DateLastSaved, and
  per-episode createdAt.

### Design reference (already built)
`media.html`/`series.html`: `.ts-cols` timestamps grid, `.help-dot` tooltip, series per-episode `.ep-ts`
injection + `#ep-newest` side panel (shown on the Seasons & episodes tab).

## Non-goals
- No "date added" column in the Library grid (detail page + Ravilo sort only this phase).
- No manual override of createdAt.
- No change to other Ravilo row sorts (Continue Watching, channels, custom rows).
